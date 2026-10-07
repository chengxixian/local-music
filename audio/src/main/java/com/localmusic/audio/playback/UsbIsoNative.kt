// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.audio.playback

/**
 * 原生等时输出（阶段 2b）的 Kotlin 门面。
 *
 * 背景：Android 的 Java USB API 不支持等时传输（`UsbRequest.initialize` 对等时端点
 * 直接返回 false，已在 logcat tag LMUsb 实测）。音频流必须等时，所以只能：
 *   `UsbDeviceConnection.getFileDescriptor()` → 把 usbfs 的原始 fd 交给原生层 →
 *   原生层用 `USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURB` 提交/回收等时 URB。
 *
 * 加载失败（例如尚未接入 CMake 构建）时 `available` 为 false，调用方回退到
 * Java 路径并如实报告，不假装成功。
 */
object UsbIsoNative {

    /** 原生库是否可用。 */
    val available: Boolean = try {
        System.loadLibrary("usbisoc")
        true
    } catch (t: Throwable) {
        android.util.Log.i("LMUsb", "原生等时库不可用：" + t.message)
        false
    }

    /**
     * 播放测试音（阻塞直到发完）。
     *
     * @param fd           UsbDeviceConnection.getFileDescriptor()
     * @param epAddr       等时 OUT 端点地址（本例 0x04）
     * @param frameBytes   每帧字节数（384k/32bit/立体声 = 384）
     * @param rateHz       采样率
     * @param toneHz       测试音频率
     * @param frames       帧数（时长 = frames/8000 秒）
     * @param fbEpAddr     异步反馈 IN 端点地址（本例 0x85）；<=0 表示无反馈、按固定速率发
     * @return 0 成功；-1 参数错、-2 分配失败、-3 首次提交失败
     */
    external fun nativePlayTone(
        fd: Int,
        epAddr: Int,
        frameBytes: Int,
        rateHz: Int,
        toneHz: Double,
        frames: Int,
        fbEpAddr: Int,
    ): Int

    /**
     * 等时播放**由 Kotlin 通过管道送来的 PCM** —— 真实音频要走的同一条通路：
     * Kotlin（解码器/测试音）--write--> pipe --read--> 原生 --> 等时 URB --> DAC。
     *
     * @param pcmFd   管道的**读端** fd（`ParcelFileDescriptor[0].fd`）
     * @return 0 成功；-1 参数错、-2 分配失败、-3 首次提交失败
     */
    external fun nativePlayPipe(
        fd: Int,
        epAddr: Int,
        frameBytes: Int,
        rateHz: Int,
        frames: Int,
        fbEpAddr: Int,
        pcmFd: Int,
    ): Int
}
