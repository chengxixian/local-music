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
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    return rate to ch
                }
            }
            null
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { ex.release() }
        }
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
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) {
                Log.i(TAG, "解码：文件里没有音频轨")
                return 0
            }
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
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
            var outChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // 采样率在解码前后不会变（我们不做重采样），取输入格式里的即可
            val outRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
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
                    Log.i(TAG, "解码：输出编码=$outEncoding 声道=$outChannels " +
                        "采样率=${of.getInteger(MediaFormat.KEY_SAMPLE_RATE)}")
                    continue
                }
                if (outIdx < 0) continue

                val ob: ByteBuffer? = c.getOutputBuffer(outIdx)
                if (ob != null && info.size > 0) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val src = ob.slice().order(ByteOrder.nativeOrder())
                    val bytes = toInt32(src, outEncoding)
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
                        val ch2 = if (outChannels <= 0) 2 else outChannels
                        val secs = written.toDouble() / (outRate * 8.0 * ch2 / 2.0)
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
