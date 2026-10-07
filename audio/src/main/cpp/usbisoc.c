/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * usbisoc.c —— USB 等时（isochronous）输出 + **异步反馈调速**的原生实现。
 *
 * 为什么必须原生：
 *   Android 的 Java USB API（UsbRequest）只支持 control / bulk / interrupt，
 *   对等时端点调用 initialize() 会直接失败（实测，logcat tag LMUsb）。
 *   音频流必须是等时传输。
 *
 * 做法（与 libusb 同一套路）：
 *   1. Kotlin 侧用 UsbDeviceConnection.getFileDescriptor() 拿 usbfs 原始 fd；
 *   2. 用 USBDEVFS_SUBMITURB / USBDEVFS_REAPURB 提交/回收等时 URB；
 *   3. 每个 URB 带 1 个 iso packet（USB 高速 125µs 一帧）。
 *
 * 关于"异步反馈"（本轮新增）：
 *   Moondrop Old Fashioned 是**异步** UAC2 设备（播放流接口带一个 4 字节的 IN 端点 0x85）。
 *   设备通过它告诉主机"每帧应该送多少个采样点"（Q16.16 定点）。主机必须据此微调每帧
 *   字节数，否则会周期性丢样/重复（听感上是爆音或跑调）。
 *   384kHz 时：384000 / 8000 = **48 采样点/帧/声道** → 32bit 立体声 = 384 字节/帧。
 *   反馈值即以 Q16.16 表示这个 48.0000，少数帧需要 385~392 字节。
 */

#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "LMUsb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* 同时在飞的音频 URB 数：太少容易 underflow，太多增加延迟 */
#define URB_COUNT 8

typedef struct {
    struct usbdevfs_urb *urb;
    unsigned char *buf;
    int length;
} iso_slot_t;

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

/* 填充一帧正弦（32bit 立体声，左右相同） */
static void fill_tone(unsigned char *buf, int frame_bytes, double *phase, double dphase) {
    int32_t *p = (int32_t *) buf;
    int samples = frame_bytes / 8;
    for (int s = 0; s < samples; s++) {
        int32_t v = (int32_t) (sin(*phase) * 0.25 * 2147483647.0);
        *phase += dphase;
        if (*phase > 2.0 * M_PI) *phase -= 2.0 * M_PI;
        p[2 * s] = v;
        p[2 * s + 1] = v;
    }
}

/* 从 fd 精确读 n 字节；返回实际读到的字节数（不足说明管道已关闭） */
static int read_exact(int fd, unsigned char *buf, int n) {
    int got = 0;
    while (got < n) {
        ssize_t r = read(fd, buf + got, (size_t) (n - got));
        if (r > 0) { got += (int) r; continue; }
        if (r < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        break;   /* EOF 或错误 */
    }
    return got;
}

/**
 * 等时播放**由 Kotlin 通过管道送来的 PCM**。
 *
 * 这是"真实音频"要走的同一条数据通路：
 *   Kotlin（解码器/测试音）--write--> pipe --read--> 这里 --> 等时 URB --> DAC
 * 用管道而不是每帧 JNI 调用：天然阻塞、天然背压、零拷贝竞争。
 *
 * 每帧读 `want` 字节（384±，由异步反馈微调）。读不满（管道空）就补静音，绝不停下等。
 *
 * @return 0 成功；-1 参数错、-2 分配失败、-3 首次提交失败
 */
JNIEXPORT jint JNICALL
Java_com_localmusic_audio_playback_UsbIsoNative_nativePlayPipe(
        JNIEnv *env, jclass clazz,
        jint fd, jint ep_addr, jint frame_bytes, jint rate_hz, jint frames,
        jint fb_ep_addr, jint pcm_fd) {

    if (fd < 0 || pcm_fd < 0 || frame_bytes <= 0 || frames <= 0 || rate_hz <= 0) return -1;

    iso_slot_t slots[URB_COUNT];
    memset(slots, 0, sizeof(slots));
    struct usbdevfs_urb *fb_urb = NULL;
    unsigned char *fb_buf = NULL;
    const int have_fb = (fb_ep_addr > 0);

    for (int i = 0; i < URB_COUNT; i++) {
        size_t urb_size = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
        struct usbdevfs_urb *u = (struct usbdevfs_urb *) calloc(1, urb_size);
        unsigned char *b = (unsigned char *) calloc(1, (size_t) frame_bytes + 16);
        if (!u || !b) {
            for (int k = 0; k < i; k++) { free(slots[k].urb); free(slots[k].buf); }
            return -2;
        }
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char) ep_addr;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->buffer = b;
        u->buffer_length = frame_bytes;
        u->number_of_packets = 1;
        u->iso_frame_desc[0].length = (unsigned int) frame_bytes;
        slots[i].urb = u;
        slots[i].buf = b;
        slots[i].length = frame_bytes;
    }
    if (have_fb) {
        size_t urb_size = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
        fb_urb = (struct usbdevfs_urb *) calloc(1, urb_size);
        fb_buf = (unsigned char *) calloc(1, 8);
        if (fb_urb && fb_buf) {
            fb_urb->type = USBDEVFS_URB_TYPE_ISO;
            fb_urb->endpoint = (unsigned char) fb_ep_addr;
            fb_urb->flags = USBDEVFS_URB_ISO_ASAP;
            fb_urb->buffer = fb_buf;
            fb_urb->buffer_length = 4;
            fb_urb->number_of_packets = 1;
            fb_urb->iso_frame_desc[0].length = 4;
            ioctl(fd, USBDEVFS_SUBMITURB, fb_urb);
        }
    }

    long submitted = 0, reaped = 0, failed = 0, status_err = 0, fb_count = 0;
    long long pcm_bytes = 0, underruns = 0;
    const double t0 = now_ms();
    int frame_idx = 0;
    uint64_t bytes_fixed = ((uint64_t) frame_bytes) << 13;
    uint64_t acc = 0;

    /* 先读满 8 帧再提交，避免一开始就 underflow */
    for (int i = 0; i < URB_COUNT && frame_idx < frames; i++, frame_idx++) {
        int got = read_exact(pcm_fd, slots[i].buf, frame_bytes);
        pcm_bytes += got;
        if (got < frame_bytes) { memset(slots[i].buf + got, 0, (size_t) (frame_bytes - got)); underruns++; }
        if (ioctl(fd, USBDEVFS_SUBMITURB, slots[i].urb) < 0) {
            if (submitted == 0) {
                LOGI("原生管道：首次 SUBMITURB 失败 errno=%d (%s)", errno, strerror(errno));
                for (int k = 0; k < URB_COUNT; k++) { free(slots[k].urb); free(slots[k].buf); }
                free(fb_urb); free(fb_buf);
                return -3;
            }
            failed++;
        } else submitted++;
    }

    struct usbdevfs_urb *done = NULL;
    while (frame_idx < frames) {
        if (ioctl(fd, USBDEVFS_REAPURB, &done) < 0) { failed++; break; }
        reaped++;
        if (have_fb && done == fb_urb) {
            uint32_t raw = (uint32_t) fb_buf[0] | ((uint32_t) fb_buf[1] << 8) |
                           ((uint32_t) fb_buf[2] << 16) | ((uint32_t) fb_buf[3] << 24);
            if (raw > 0) {
                uint64_t b = ((uint64_t) raw) >> 13;
                /*
                 * 关键：把**完整的 Q16.16 速率**放进累加器，不要先取整再还原。
                 *
                 * 推导：累加器用 Q13 表示"字节/帧"（帧长 = acc >> 13）。
                 *   bytes/frame = samples/frame × 8
                 *   而 raw 是 samples/frame 的 Q16.16
                 *   → raw 对应的字节速率 = raw × 8 / 65536 = raw / 8192 = raw >> 13
                 *   → 但那是**取整后**的值；直接令 bytes_fixed = raw 时，
                 *     acc >> 13 恰好等于 (raw × 8192) >> 16 的定点表示，即保留了小数部分。
                 *
                 * 之前写成 bytes_fixed = b << 13（b = raw >> 13）会把 44.1k 的 0.1
                 * 直接丢掉 → 每帧慢 0.23% → 十分钟漂 1.4 秒。
                 */
                if (b >= 4 && b <= 8192) bytes_fixed = (uint64_t) raw;
                fb_count++;
            }
            ioctl(fd, USBDEVFS_SUBMITURB, fb_urb);
            continue;
        }
        if (done && done->status != 0) status_err++;
        int slot = -1;
        for (int i = 0; i < URB_COUNT; i++) { if (slots[i].urb == done) { slot = i; break; } }
        if (slot < 0) continue;

        acc += bytes_fixed;
        int want = (int) (acc >> 13);
        /*
         * ⚠️ 必须把每包长度**对齐到整采样帧**（32bit 立体声 = 8 字节）。
         *
         * 血泪教训：44.1kHz 时 44100/8000 = 5.5125 采样/帧，平均 44 字节。
         * 若直接发 44 字节 = 5.5 个立体声采样 → 每个包都把左右声道切断半个采样
         * → 送到 DAC 的就是满量程噪音（实测把用户吓到了）。
         * 正确做法：包长取 8 的整数倍（40/48 字节交替），且只扣掉真正发出去的字节，
         * 余量结转到下一帧 —— 长期速率仍然精确。
         */
        const int align = 8;   /* 4 字节 × 2 声道 */
        want -= want % align;
        acc -= ((uint64_t) want) << 13;
        if (want < align) want = align;
        if (want > slots[slot].length) {
            want = slots[slot].length - (slots[slot].length % align);
        }

        int got = read_exact(pcm_fd, slots[slot].buf, want);
        pcm_bytes += got;
        if (got == 0) {
            /* 管道已关闭：说明解码输出结束 —— 停止发送，让音乐自然收尾 */
            break;
        }
        if (got < want) {
            memset(slots[slot].buf + got, 0, (size_t) (want - got));
            underruns++;
        }
        slots[slot].urb->buffer_length = want;
        slots[slot].urb->iso_frame_desc[0].length = (unsigned int) want;
        if (ioctl(fd, USBDEVFS_SUBMITURB, slots[slot].urb) < 0) failed++;
        else submitted++;
        frame_idx++;
    }

    for (int i = 0; i < URB_COUNT; i++) ioctl(fd, USBDEVFS_DISCARDURB, slots[i].urb);
    for (int i = 0; i < URB_COUNT; i++) { free(slots[i].urb); free(slots[i].buf); }
    free(fb_urb); free(fb_buf);

    const double ms = now_ms() - t0;
    LOGI("原生管道：帧=%d 提交=%ld 回收=%ld 失败=%ld 状态错=%ld 反馈=%ld PCM字节=%lld 补静音=%ld 用时=%.0fms 速率=%.0f/s",
         frames, submitted, reaped, failed, status_err, fb_count, pcm_bytes, underruns, ms,
         submitted * 1000.0 / (ms > 1 ? ms : 1));
    return 0;
}

/**
 * 播放测试音，并按设备的异步反馈微调每帧字节数。
 *
 * @param fd          usbfs fd（UsbDeviceConnection.getFileDescriptor()）
 * @param ep_addr     等时 OUT 端点（0x04）
 * @param frame_bytes 基准每帧字节数（384k/32bit/立体声 = 384）
 * @param rate_hz     采样率
 * @param tone_hz     测试音频率
 * @param frames      总帧数（时长 = frames / 8000 秒）
 * @param fb_ep_addr  反馈 IN 端点（0x85；<=0 表示无反馈，按固定速率发）
 * @return 0 成功；-1 参数错、-2 分配失败、-3 首次提交失败
 */
JNIEXPORT jint JNICALL
Java_com_localmusic_audio_playback_UsbIsoNative_nativePlayTone(
        JNIEnv *env, jclass clazz,
        jint fd, jint ep_addr, jint frame_bytes, jint rate_hz, jdouble tone_hz, jint frames,
        jint fb_ep_addr) {

    if (fd < 0 || frame_bytes <= 0 || frames <= 0 || rate_hz <= 0) return -1;

    iso_slot_t slots[URB_COUNT];
    memset(slots, 0, sizeof(slots));

    /* 反馈 URB（UAC2 异步模式：设备告诉我们每帧该送多少采样点） */
    struct usbdevfs_urb *fb_urb = NULL;
    unsigned char *fb_buf = NULL;
    const int have_fb = (fb_ep_addr > 0);

    for (int i = 0; i < URB_COUNT; i++) {
        size_t urb_size = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
        struct usbdevfs_urb *u = (struct usbdevfs_urb *) calloc(1, urb_size);
        unsigned char *b = (unsigned char *) malloc((size_t) frame_bytes + 16);
        if (!u || !b) {
            for (int k = 0; k < i; k++) { free(slots[k].urb); free(slots[k].buf); }
            return -2;
        }
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char) ep_addr;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->buffer = b;
        u->buffer_length = frame_bytes;
        u->number_of_packets = 1;
        u->iso_frame_desc[0].length = (unsigned int) frame_bytes;
        slots[i].urb = u;
        slots[i].buf = b;
        slots[i].length = frame_bytes;
    }

    if (have_fb) {
        size_t urb_size = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
        fb_urb = (struct usbdevfs_urb *) calloc(1, urb_size);
        fb_buf = (unsigned char *) calloc(1, 8);
        if (fb_urb && fb_buf) {
            fb_urb->type = USBDEVFS_URB_TYPE_ISO;
            fb_urb->endpoint = (unsigned char) fb_ep_addr;
            fb_urb->flags = USBDEVFS_URB_ISO_ASAP;
            fb_urb->buffer = fb_buf;
            fb_urb->buffer_length = 4;
            fb_urb->number_of_packets = 1;
            fb_urb->iso_frame_desc[0].length = 4;
            ioctl(fd, USBDEVFS_SUBMITURB, fb_urb);
        }
    }

    double phase = 0.0;
    const double dphase = 2.0 * M_PI * tone_hz / (double) rate_hz;
    long submitted = 0, reaped = 0, failed = 0, status_err = 0, fb_count = 0;
    const double t0 = now_ms();
    int frame_idx = 0;

    /* 每帧字节数的定点累加器（Q13）：bytes = fb_q16 >> 13 */
    uint64_t bytes_fixed = ((uint64_t) frame_bytes) << 13;
    uint64_t acc = 0;
    int last_bytes = frame_bytes;

    for (int i = 0; i < URB_COUNT && frame_idx < frames; i++, frame_idx++) {
        fill_tone(slots[i].buf, frame_bytes, &phase, dphase);
        if (ioctl(fd, USBDEVFS_SUBMITURB, slots[i].urb) < 0) {
            if (submitted == 0) {
                LOGI("原生等时：首次 SUBMITURB 失败 errno=%d (%s)", errno, strerror(errno));
                for (int k = 0; k < URB_COUNT; k++) { free(slots[k].urb); free(slots[k].buf); }
                free(fb_urb); free(fb_buf);
                return -3;
            }
            failed++;
        } else {
            submitted++;
        }
    }

    struct usbdevfs_urb *done = NULL;
    while (frame_idx < frames) {
        if (ioctl(fd, USBDEVFS_REAPURB, &done) < 0) { failed++; break; }
        reaped++;

        /* 反馈回来了：解析 Q16.16 → 每帧采样点数 → 每帧字节数 */
        if (have_fb && done == fb_urb) {
            uint32_t raw = (uint32_t) fb_buf[0] | ((uint32_t) fb_buf[1] << 8) |
                           ((uint32_t) fb_buf[2] << 16) | ((uint32_t) fb_buf[3] << 24);
            if (raw > 0) {
                /* bytes/frame = samples/frame * 8 = (raw / 65536) * 8 = raw >> 13 */
                uint64_t b = ((uint64_t) raw) >> 13;
                /*
                 * 关键：把**完整的 Q16.16 速率**放进累加器，不要先取整再还原。
                 *
                 * 推导：累加器用 Q13 表示"字节/帧"（帧长 = acc >> 13）。
                 *   bytes/frame = samples/frame × 8
                 *   而 raw 是 samples/frame 的 Q16.16
                 *   → raw 对应的字节速率 = raw × 8 / 65536 = raw / 8192 = raw >> 13
                 *   → 但那是**取整后**的值；直接令 bytes_fixed = raw 时，
                 *     acc >> 13 恰好等于 (raw × 8192) >> 16 的定点表示，即保留了小数部分。
                 *
                 * 之前写成 bytes_fixed = b << 13（b = raw >> 13）会把 44.1k 的 0.1
                 * 直接丢掉 → 每帧慢 0.23% → 十分钟漂 1.4 秒。
                 */
                if (b >= 4 && b <= 8192) bytes_fixed = (uint64_t) raw;
                if (fb_count < 5) {
                    LOGI("原生等时：反馈 #%ld raw=0x%08x → 每帧采样=%.4f 每帧字节=%llu",
                         fb_count, raw, raw / 65536.0, (unsigned long long) b);
                }
                fb_count++;
            }
            /* 重新提交反馈 URB，继续跟踪 */
            fb_urb->iso_frame_desc[0].actual_length = 0;
            ioctl(fd, USBDEVFS_SUBMITURB, fb_urb);
            continue;   /* 反馈 URB 不占音频帧额度 */
        }

        if (done && done->status != 0) status_err++;

        int slot = -1;
        for (int i = 0; i < URB_COUNT; i++) {
            if (slots[i].urb == done) { slot = i; break; }
        }
        if (slot < 0) continue;

        /* 用反馈推导出的每帧字节数（定点累加，避免长期漂移） */
        acc += bytes_fixed;
        int want = (int) (acc >> 13);
        acc &= 8191;
        if (want < 8) want = 8;
        if (want > slots[slot].length) want = slots[slot].length;
        last_bytes = want;

        fill_tone(slots[slot].buf, frame_bytes, &phase, dphase);
        slots[slot].urb->buffer_length = want;
        slots[slot].urb->iso_frame_desc[0].length = (unsigned int) want;
        if (ioctl(fd, USBDEVFS_SUBMITURB, slots[slot].urb) < 0) failed++;
        else submitted++;
        frame_idx++;
    }

    for (int i = 0; i < URB_COUNT; i++) {
        ioctl(fd, USBDEVFS_DISCARDURB, slots[i].urb);
    }
    for (int i = 0; i < URB_COUNT; i++) { free(slots[i].urb); free(slots[i].buf); }
    free(fb_urb);
    free(fb_buf);

    const double ms = now_ms() - t0;
    LOGI("原生等时：帧=%d 提交=%ld 回收=%ld 失败=%ld 状态错=%ld 反馈次数=%ld 末帧字节=%d 用时=%.0fms",
         frames, submitted, reaped, failed, status_err, fb_count, last_bytes, ms);
    LOGI("原生等时：提交速率=%.0f/s（USB 等时由帧时钟驱动，目标 8000/s）", submitted * 1000.0 / (ms > 1 ? ms : 1));
    return 0;
}
