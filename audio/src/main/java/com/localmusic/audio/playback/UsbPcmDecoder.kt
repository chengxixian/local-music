// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.audio.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把本地音频文件解码成 **32bit 定点 PCM** 写进管道，交给原生等时层直送 DAC。
 *
 * 这是"实现 USB 直通"的最后一块数据来源：解码与重采样都不做多余动作 ——
 * PCM 以**文件原始采样率**输出，采样率由调用方通过 UAC2 控制传输设到 DAC 上
 *（这样链路上唯一可能引入误差的地方只剩 USB 传输本身）。
 *
 * 精度处理：
 *  - 优先请求 `ENCODING_PCM_FLOAT`（保留 24/32bit 源的全部精度），
 *    按 `× 2147483647` 转成 32bit 定点（做饱和钳位）；
 *  - 解码器若只给 16bit，则 `shl 16` 对齐到 32bit（低位补零，不伪造精度）；
 *  - 解码器若本来就给 32bit，原样透传。
 */
object UsbPcmDecoder {

    private const val TAG = "LMUsb"
    private const val TIMEOUT_US = 10_000L

    /** 每写入这么多字节 PCM 打一条进度日志（约 5~6 秒音频），便于中途核对是否在推进 */
    private const val PROGRESS_EVERY = 2_000_000L

    /** 只读文件头，拿到原始采样率与声道数（用于给 DAC 设时钟、算每帧字节数）。 */
    fun probe(path: String): Pair<Int, Int>? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            // 与 decodeTo() 共用同一个选轨函数：两处对同一文件必须得出同样的
            // (采样率, 声道数)，否则 DAC 的时钟和实际送出的数据长度会对不上（=变速）。
            val f = findAudioTrack(ex)?.second ?: return null
            f.getInteger(MediaFormat.KEY_SAMPLE_RATE) to f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { ex.release() }
        }
    }

    /** 第一条音频轨（下标 + 格式）。[probe] 与 [decodeTo] 必须共用，避免两处各自解析出不同结果。 */
    private fun findAudioTrack(ex: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i to f
        }
        return null
    }

    /**
     * 解码 [path] 并把 32bit 定点 PCM 写入 [out]（阻塞直到文件结束或管道关闭）。
     *
     * @return 实际写入的 PCM 字节数
     */
    fun decodeTo(path: String, out: OutputStream): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var written = 0L
        try {
            ex.setDataSource(path)
            val found = findAudioTrack(ex) ?: run {
                Log.i(TAG, "解码：文件里没有音频轨")
                return 0
            }
            val track = found.first
            val fmt = found.second
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: run {
                Log.i(TAG, "解码：音频轨没有 mime，无法创建解码器")
                return 0
            }
            // 采样率/声道数与 probe() 同源（同一个 findAudioTrack），不会两处算出不同结果。
            val inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            /*
             * ── 声道闸：原来这里什么都不查，直接把解码器输出硬塞进管道 ──────────────
             *
             * 原生层（usbisoc.c）每帧固定按 2 声道 × 32bit = 每采样帧 8 字节消费，
             * 而这里送出去的长度完全取决于源文件的声道数：
             *   单声道（4 字节/采样帧）→ DAC 按 8 字节消费 → 采到的数据永远比实际少 →
             *     听起来就是 2 倍速（音高翻倍）；
             *   5.1（12 字节/采样帧）→ 3 倍速。
             * 这不是"音质差一点"，是**放错**，所以不能再静默继续。
             *
             * 1 声道：下面用 monoToStereo() 把每个采样复制成左右两声道（样本值不变，仍可直通）。
             * >2 声道：明确拒绝 + 在上层状态栏说明原因（不再变速播放）。
             */
            if (inChannels !in 1..2) {
                Log.i(
                    TAG,
                    "解码：拒绝直通 —— 源文件 $inChannels 声道（直通只支持立体声，" +
                        "单声道会自动复制成左右声道）",
                )
                PlaybackDiagnostics.mutable.value =
                    "USB 直通不支持该文件：$inChannels 声道（直通只支持立体声）"
                return 0
            }
            /*
             * ⚠️ 不要在这里请求 KEY_PCM_ENCODING = FLOAT（我曾这么写，是两次噪音的真凶）。
             *
             * 这个库里的文件轨道常常是 `audio/raw`（未压缩直通）—— 解码器只是原样透传，
             * 它会把我"请求"的 FLOAT **回报**在输出格式里，但字节仍然是文件本来的 16bit。
             * 于是我按 float 去解释 16bit 数据 → 得到"大部分为 0、偶发满量程"的垃圾
             * （实测统计：非静音仅 0.3%、相邻跳变 5812 万）→ 送到 DAC 就是刺耳噪音。
             *
             * 正确做法：不请求、不假设，完全按解码器**真实输出格式**里的编码来处理。
             */

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outEncoding = AudioFormat.ENCODING_PCM_16BIT
            // 解码器真正的输出声道数会在 INFO_OUTPUT_FORMAT_CHANGED 里覆盖它；
            // 在那之前按源声道数处理（单声道需要复制成左右两声道）。
            var outChannels = inChannels
            // 采样率用**源格式**里那一份（与 probe() 同源）；解码器回报的只用来校验，
            // 不用来参与计算 —— DAC 的时钟是调用方按 probe() 的结果设的。
            val outRate = inRate
            var lastProgress = -1L
            // 安全闸的统计量：接近满量程的样本数 / 已检查样本数
            var hotSamples = 0L
            var checkedSamples = 0L
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            Log.i(TAG, "解码：开始 时长=${durUs / 1000}ms 采样率=$outRate 声道=$outChannels mime=$mime")

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val ib = c.getInputBuffer(inIdx)!!
                        // 关键：readSampleData 从缓冲区**当前位置**开始写。
                        // 不清位置的话，第二次以后会从上次的偏移继续写 → 喂进去的样本残缺
                        // → 解码器很快报 EOS（实测表现就是"只播了 2 秒"）。
                        ib.clear()
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    outEncoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val ofRate = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        inRate
                    }
                    Log.i(TAG, "解码：输出编码=$outEncoding 声道=$outChannels " +
                        "采样率=$ofRate（源 $inRate）")
                    /*
                     * ── 采样率闸 ────────────────────────────────────────────────
                     * DAC 的时钟/每帧字节数是调用方按 probe() 读到的 $inRate 设死的。
                     * 解码器若自己变采样率（典型：HE-AAC 的 SBR 让输出变成源的两倍），
                     * 再按 $inRate 硬送就是**变速播放**。宁可不出声并留下可读原因，
                     * 也不静默变速 —— 这正是"直通"最不能妥协的一条。
                     */
                    if (ofRate > 0 && ofRate != inRate) {
                        Log.i(TAG, "解码：拒绝直通 —— 解码器输出 ${ofRate}Hz ≠ 文件 ${inRate}Hz，直通会变速")
                        PlaybackDiagnostics.mutable.value =
                            "USB 直通不支持该文件：解码输出 ${ofRate}Hz ≠ ${inRate}Hz（会变速）"
                        return written
                    }
                    // 同一轨道的声道数不可能中途变化；真变了说明数据布局假设不成立，不能继续送。
                    if (outChannels !in 1..2) {
                        Log.i(TAG, "解码：拒绝直通 —— 解码器输出 $outChannels 声道（只支持 1/2）")
                        PlaybackDiagnostics.mutable.value =
                            "USB 直通不支持该文件：解码输出 $outChannels 声道"
                        return written
                    }
                    continue
                }
                if (outIdx < 0) continue

                val ob: ByteBuffer? = c.getOutputBuffer(outIdx)
                if (ob != null && info.size > 0) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val src = ob.slice().order(ByteOrder.nativeOrder())
                    val pcm32 = toInt32(src, outEncoding)
                    /*
                     * 送给原生层的数据**永远是 2 声道 × 32bit = 每采样帧 8 字节**。
                     * 单声道源在这里补成左右两声道（原来不补 → 2 倍速播放）。
                     */
                    val bytes = if (outChannels == 1) monoToStereo(pcm32) else pcm32
                    out.write(bytes)
                    written += bytes.size
                    /*
                     * 安全闸（我曾两次把刺耳噪音送进 DAC，用户被吓到，所以必须加）：
                     * 编码误判那次的特征是"绝大多数样本铺满量程"（实测满量程乱跳）。
                     * 正常音乐也可能偶尔接近满量程，但不会大比例如此。
                     * 因此累计统计：满量程占比 >20% 且样本数足够时，**主动中止**本次解码，
                     * 让原生层收到 EOF 自然收尾 —— 宁可不出声，也绝不放噪音。
                     */
                    var bi = 0
                    while (bi + 3 < bytes.size) {
                        val v = (bytes[bi].toInt() and 0xFF) or
                            ((bytes[bi + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[bi + 2].toInt() and 0xFF) shl 16) or
                            (bytes[bi + 3].toInt() shl 24)
                        if (v > 2_000_000_000 || v < -2_000_000_000) hotSamples++
                        checkedSamples++
                        bi += 4
                    }
                    if (checkedSamples > 400_000 && hotSamples * 100L / checkedSamples > 20L) {
                        Log.i(
                            TAG,
                            "解码：疑似垃圾数据（满量程占比 ${hotSamples * 100L / checkedSamples}%）" +
                                " → 主动中止，避免把噪音送进 DAC"
                        )
                        return written
                    }
                    // 进度日志：用于中途核对"解码是否在持续推进"
                    if (written / PROGRESS_EVERY != lastProgress) {
                        lastProgress = written / PROGRESS_EVERY
                        // 字面上送出去的永远是 2ch×32bit = 8 字节/采样帧，不再按源声道数换算
                        // （否则单声道文件这条"已写 ≈ N 秒"会算多一倍）。
                        val secs = written.toDouble() / (outRate * 8.0)
                        Log.i(TAG, "解码：已写 PCM=${written} 字节 ≈ ${"%.1f".format(secs)} 秒音频")
                    }
                }
                c.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
            runCatching { out.flush() }
            Log.i(TAG, "解码：完成，写入 PCM 字节=$written")
            return written
        } catch (t: Throwable) {
            // 管道关闭会让 write 抛异常，属正常收尾
            Log.i(TAG, "解码：结束（" + (t.message ?: t.javaClass.simpleName) + "），已写入 $written 字节")
            return written
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    /**
     * 单声道 → 立体声：每个采样复制成左右两声道（样本值一个字节都不改）。
     *
     * 原生层按 2ch/32bit 固定消费 8 字节/采样帧，单声道源若不补，DAC 拿到的数据只有
     * 需要的一半 → 2 倍速（音高翻倍）。输入/输出都是小端 32bit 定点，所以这里只做
     * "4 字节一组复制两遍"，不引入任何增益或滤波。
     */
    private fun monoToStereo(mono: ByteArray): ByteArray {
        val frames = mono.size / 4
        val dst = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        val src = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
        while (src.remaining() >= 4) {
            val v = src.getInt()
            dst.putInt(v)
            dst.putInt(v)
        }
        return dst.array()
    }

    /** 把解码器输出统一转成 32bit 定点（小端）字节序的数组。 */
    private fun toInt32(src: ByteBuffer, encoding: Int): ByteArray {
        val out = ByteArray(src.remaining() / bytesOf(encoding) * 4)
        val dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val f = src.order(ByteOrder.nativeOrder()).asFloatBuffer()
                while (f.remaining() > 0) {
                    val v = f.get()
                    val s = (v * 2147483647f).toLong().coerceIn(-2147483648L, 2147483647L)
                    dst.putInt(s.toInt())
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val i = src.order(ByteOrder.nativeOrder()).asIntBuffer()
                while (i.remaining() > 0) dst.putInt(i.get())
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                while (src.remaining() >= 3) {
                    val b0 = src.get().toInt() and 0xFF
                    val b1 = src.get().toInt() and 0xFF
                    val b2 = src.get().toInt() and 0xFF
                    // 24bit 有符号 → 左移 8 位对齐到 32bit
                    val v = (b0 or (b1 shl 8) or (b2 shl 16)) shl 8
                    dst.putInt(v)
                }
            }
            else -> { // 16bit
                val s = src.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (s.remaining() > 0) dst.putInt(s.get().toInt() shl 16)
            }
        }
        return out
    }

    private fun bytesOf(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        else -> 2
    }
}
