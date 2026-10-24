// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * DSD（`.dsf` / `.dff`）支持。
 *
 * 为什么必须自己解码：Media3/ExoPlayer 没有 DSD 解码器（上游 AURALIS 也一样，它的 "DSD/DXD" 是
 * **规格解析**那一层）。所以这里走"**转码 + 缓存**"路线：
 *
 *     DSD 1bit 码流  --两次 4:1 箱式平均（等价 16 点三角窗）-->  PCM 176.4kHz/24bit  -->  WAV
 *
 * 转好的 WAV 交给现有播放链（还能继续走 USB bit-perfect）。**首次播放要转一次**，之后读缓存。
 *
 * 两个容易错的细节：
 *  · DSF 的比特序是 **LSB first**，DFF 是 **MSB first** —— 搞反了就是满耳噪声。
 *  · DSF 的数据是"每声道 4096 字节一块"交错存放，不是逐样本交错。
 */
object Dsd {

    private const val TAG = "LMDsd"

    /** 输出 PCM 采样率固定 176.4kHz（DSD 的 44.1k 家族整数倍，避免额外重采样）。 */
    const val PCM_RATE = 176_400

    /**
     * 流式转换每次处理的输入上限（**每声道**的 DSD 字节数）—— 峰值内存的唯一来源。
     * 旧实现按整轨分配，见 [convertToWav] 注释里的内存账。
     */
    private const val CHUNK_BYTES_PER_CHANNEL = 512 * 1024

    /** WAV(24bit PCM) 头长度：44 字节。流式写完后要 seek 回 0 回填。 */
    private const val WAV_HEADER_BYTES = 44

    /** DSD 源副本 / 转码结果的缓存目录（[copyToLocal]、[cacheFile]、失败清理共用）。 */
    private const val CACHE_DIR = "dsd-cache"

    data class Info(
        val sampleRate: Int,      // DSD 位率：DSD64=2822400 / DSD128=5644800 / DSD256=11289600
        val channels: Int,
        val bits: Long,           // 每声道总比特数
        val msbFirst: Boolean,
        val blockSize: Int,       // DSF 每声道块大小；DFF 为 0（紧凑排列）
        val dataOffset: Long,
    ) {
        val name: String get() = when (sampleRate) {
            2_822_400 -> "DSD64"
            5_644_800 -> "DSD128"
            11_289_600 -> "DSD256"
            else -> "DSD"
        }
        val durationMs: Long get() = if (sampleRate > 0) bits * 1000 / sampleRate else 0
    }

    fun isDsd(path: String): Boolean {
        val n = path.lowercase()
        return n.endsWith(".dsf") || n.endsWith(".dff")
    }

    /** 从文件头字节解析（DSF/DFF）。`totalLen` 只用于 DFF 的边界判断。 */
    fun probeBytes(head: ByteArray, totalLen: Long = head.size.toLong()): Info? = try {
        when (String(head, 0, 4, Charsets.US_ASCII)) {
            "DSD " -> parseDsfHead(head)
            "FRM8" -> parseDffHead(head)
            else -> null
        }
    } catch (e: Exception) {
        Log.i(TAG, "probeBytes failed: ${e.message}")
        null
    }

    /**
     * SAF / MediaStore 的 `content://` 文件：读前 1MB 就够解析文件头。
     * （解码需要整份数据，走 [copyToLocal] 落一份再转。）
     */
    fun probeUri(context: android.content.Context, uri: android.net.Uri): Info? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(1 shl 20)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            probeBytes(buf.copyOf(n))
        }
    } catch (e: Exception) {
        Log.i(TAG, "probeUri failed: ${e.message}")
        null
    }

    /** 把 DSD 源落一份到应用私有目录（解码要按字节随机访问，SAF 流做不到）。 */
    fun copyToLocal(context: android.content.Context, uri: android.net.Uri, name: String = "source.dsf"): File? = try {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        val out = File(dir, "src-" + (uri.toString().hashCode().toUInt().toString(16)) + "-" + name)
        if (!out.isFile || out.length() == 0L) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().buffered(1 shl 16).use { os -> input.copyTo(os) }
            } ?: return null
        }
        out
    } catch (e: Exception) {
        Log.i(TAG, "copyToLocal failed: ${e.message}")
        null
    }

    private fun parseDsfHead(head: ByteArray): Info? {
        if (head.size < 92) return null
        if (String(head, 28, 4, Charsets.US_ASCII) != "fmt ") return null
        val channels = le32(head, 28 + 24)
        val rate = le32(head, 28 + 28)
        val bits = le64(head, 28 + 36)
        val block = le32(head, 28 + 44)
        if (String(head, 80, 4, Charsets.US_ASCII) != "data") return null
        return Info(rate, channels.coerceAtLeast(1), bits, msbFirst = false, blockSize = block, dataOffset = 92)
    }

    private fun parseDffHead(buf: ByteArray): Info? {
        val len = buf.size
        var rate = 0
        var channels = 2
        var dataOffset = -1L
        var dataSize = 0L
        var i = 12
        while (i + 12 <= len) {
            val id = String(buf, i, 4, Charsets.US_ASCII)
            val size = be64(buf, i + 4).toInt()
            when (id) {
                "PROP" -> {
                    var j = i + 12
                    val end = (i + 12 + size).coerceAtMost(len)
                    while (j + 12 <= end) {
                        val cid = String(buf, j, 4, Charsets.US_ASCII)
                        val csz = be64(buf, j + 4).toInt()
                        if (cid == "FS  " && j + 16 <= len) rate = be32(buf, j + 12)
                        if (cid == "CHNL" && j + 14 <= len) {
                            channels = ((buf[j + 12].toInt() and 0xFF) shl 8) or (buf[j + 13].toInt() and 0xFF)
                        }
                        j += 12 + csz + (csz and 1)
                    }
                }
                "DSD " -> { dataOffset = (i + 12).toLong(); dataSize = size.toLong() }
            }
            if (dataOffset > 0) break
            i += 12 + size + (size and 1)
        }
        if (rate <= 0 || dataOffset < 0) return null
        return Info(rate, channels.coerceAtLeast(1), dataSize * 8 / channels, msbFirst = true, blockSize = 0, dataOffset = dataOffset)
    }

    /** 解析文件（应用私有目录里的实体文件）。 */
    fun probe(file: File): Info? = try {
        RandomAccessFile(file, "r").use { f ->
            val n = minOf(f.length(), 1L shl 20).toInt()
            val head = ByteArray(n).also { f.seek(0); f.readFully(it) }
            probeBytes(head, f.length())
        }
    } catch (e: Exception) {
        Log.i(TAG, "probe failed ${file.name}: ${e.message}")
        null
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun probeDsf(f: RandomAccessFile): Info? {
        val head = ByteArray(92).also { f.seek(0); f.readFully(it) }
        // 布局：DSD chunk(28) + fmt chunk。fmt 内偏移（相对 fmt 起点）：
        //   12 formatVersion / 16 formatID / 20 channelType / 24 channelNum /
        //   28 samplingFrequency / 32 bitsPerSample / 36 sampleCount(8字节) /
        //   44 blockSizePerChannel / 48 reserved
        if (String(head, 28, 4, Charsets.US_ASCII) != "fmt ") return null
        val channels = le32(head, 28 + 24)
        val rate = le32(head, 28 + 28)
        val bits = le64(head, 28 + 36)
        val block = le32(head, 28 + 44)
        if (String(head, 80, 4, Charsets.US_ASCII) != "data") return null
        return Info(rate, channels.coerceAtLeast(1), bits, msbFirst = false, blockSize = block, dataOffset = 92)
    }

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun be64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    /** DFF：FRM8 + 若干 chunk，找 PROP 里的 FS/CHNL，再找 "DSD " 数据块。MSB first。 */
    private fun probeDff(f: RandomAccessFile): Info? {
        val len = f.length().toInt().coerceAtMost(1 shl 20)
        val buf = ByteArray(len).also { f.seek(0); f.readFully(it) }
        var rate = 0
        var channels = 2
        var dataOffset = -1L
        var dataSize = 0L
        var i = 12
        while (i + 12 <= len) {
            val id = String(buf, i, 4, Charsets.US_ASCII)
            val size = be64(buf, i + 4).toInt()
            when (id) {
                "PROP" -> {
                    var j = i + 12
                    val end = (i + 12 + size).coerceAtMost(len)
                    while (j + 12 <= end) {
                        val cid = String(buf, j, 4, Charsets.US_ASCII)
                        val csz = be64(buf, j + 4).toInt()
                        if (cid == "FS  " && j + 12 + 4 <= len) rate = be32(buf, j + 12)
                        if (cid == "CHNL" && j + 12 + 2 <= len) channels = (buf[j + 12].toInt() and 0xFF) shl 8 or (buf[j + 13].toInt() and 0xFF)
                        j += 12 + csz + (csz and 1)
                    }
                }
                "DSD " -> { dataOffset = (i + 12).toLong(); dataSize = size.toLong() }
            }
            i += 12 + size + (size and 1)
            if (dataOffset > 0) break
        }
        if (rate <= 0 || dataOffset < 0) return null
        val bitsPerChannel = dataSize * 8 / channels
        return Info(rate, channels.coerceAtLeast(1), bitsPerChannel, msbFirst = true, blockSize = 0, dataOffset = dataOffset)
    }

    /**
     * DSD → WAV（176.4kHz / 24bit / 与源相同声道数）—— **流式**，峰值内存与曲目长度无关。
     *
     * 原来为什么坏（内存账，别再改回整轨缓冲）：旧实现把整条 DSD 码流按声道全读进内存
     * （`perChannel` 一份 + `channelData` 每声道一份，且 `perChannel` 分配了却从没被用到），
     * 再为"转换后的 PCM"分配 `IntArray(整轨采样数)`，最后 `writeWav24` 又分配一整轨字节缓冲。
     * 3 分钟 DSD64 立体声 = 每声道 63.5 MB 码流 ⇒ 63.5(废弃) + 127(channelData) +
     * 254(两声道 IntArray) + 190(WAV 字节缓冲) ≈ 630 MB，而正常手机堆只有 256~512 MB：
     * 必然 OOM，用户只看到"DSD 转换失败"。DFF（平面存储）还要再多整文件一份。
     *
     * 现在：按块（每声道 ≤ [CHUNK_BYTES_PER_CHANNEL]）读 → 抽取 → 立刻交织写 WAV 帧。
     * 峰值 = 一个读缓冲 + 每声道一个 chunk 的 IntArray + 一个 chunk 的交织字节缓冲
     * （立体声约 4 MB，6 声道约 11 MB），与曲目长度完全无关。
     *
     * DSP **逐位不变**：两级 4:1 箱式平均（= 16 点三角窗）、位序（DSF LSB-first / DFF MSB-first）、
     * 缩放常量全部沿用原来的算式，只是把跨整轨的累加器换成可分段的对象状态（[DsdDecimator]）。
     *
     * WAV 头先写 44 字节占位，结束后 seek 回 0 回填真实长度 —— 流式下总长度要等真正写完才知道
     * （源文件被截断时实际帧数会少于文件头声明的数量）。
     */
    fun convertToWav(src: File, out: File, info: Info, onProgress: ((Int) -> Unit)? = null): Boolean {
        val ratio = (info.sampleRate / PCM_RATE).coerceAtLeast(1)   // DSD64=16, DSD128=32, DSD256=64
        val stage = 4                                               // 两级各 4:1
        val decim = ratio / (stage * stage)                         // 一级内部再抽 decim（DSD128=2, DSD256=4）
        // 旧代码这里是一个恒真的 require：ratio 非法（例如 8）时 decim 会变成 0，
        // `inner >= decim` 恒成立 → 实际按 16:1 抽取却把结果标成别的速率（静默错速）。现在明确拒绝。
        if (ratio < stage * stage || ratio % (stage * stage) != 0) {
            Log.i(TAG, "convert 拒绝：不支持的 DSD 位率 ${info.sampleRate}Hz（仅 2822400/5644800/11289600）")
            return false
        }
        val channels = info.channels.coerceAtLeast(1)
        val bytesPerFrame = ratio / 8                // 每声道每个输出采样对应的 DSD 字节数（16→2, 32→4, 64→8）
        val bytesPerChannel = info.bits / 8
        val totalFrames = bytesPerChannel / bytesPerFrame
        if (totalFrames <= 0L) {
            Log.i(TAG, "convert 拒绝：${src.name} 的 DSD 数据长度为 0")
            return false
        }
        // WAV 的 RIFF/数据长度字段是 32 位：超过 2GB 写不出正确头（旧实现直接 Int 溢出，头是错的）。
        val declaredBytes = totalFrames * channels * 3
        if (declaredBytes > Int.MAX_VALUE - 64L) {
            Log.i(TAG, "convert 拒绝：${src.name} 转出的 PCM 约 $declaredBytes 字节，超过 WAV 的 2GB 上限")
            return false
        }
        val chunkFrames = (CHUNK_BYTES_PER_CHANNEL / bytesPerFrame).coerceAtLeast(1)
        var ok = false
        try {
            RandomAccessFile(src, "r").use { f ->
                out.parentFile?.mkdirs()
                RandomAccessFile(out, "rw").use { o ->
                    o.setLength(0L)
                    writeWavHeader(o, channels, PCM_RATE, 0L)          // 占位，长度最后回填
                    val decimators = Array(channels) { DsdDecimator(decim, info.msbFirst, stage) }
                    val samples = Array(channels) { IntArray(chunkFrames) }
                    val dsdBuf = ByteArray(chunkFrames * bytesPerFrame)
                    val wavBuf = ByteArray(chunkFrames * channels * 3)
                    var framesDone = 0L
                    var truncated = false
                    while (framesDone < totalFrames && !truncated) {
                        val wantFrames = minOf(chunkFrames.toLong(), totalFrames - framesDone).toInt()
                        val wantBytes = wantFrames * bytesPerFrame
                        val firstByte = framesDone * bytesPerFrame
                        var produced = wantFrames
                        for (c in 0 until channels) {
                            val got = readChannelChunk(f, info, c, firstByte, wantBytes, dsdBuf, bytesPerChannel)
                            // 文件比文件头声明的短：本块按实际读到的字节解，然后收尾。
                            if (got < wantBytes) truncated = true
                            val n = decimators[c].decode(dsdBuf, got, samples[c])
                            if (n < produced) produced = n
                        }
                        if (produced <= 0) break
                        var p = 0
                        for (i in 0 until produced) {
                            for (c in 0 until channels) {
                                val v = samples[c][i]
                                wavBuf[p++] = (v and 0xFF).toByte()
                                wavBuf[p++] = ((v shr 8) and 0xFF).toByte()
                                wavBuf[p++] = ((v shr 16) and 0xFF).toByte()
                            }
                        }
                        o.write(wavBuf, 0, p)
                        framesDone += produced
                        onProgress?.invoke(((framesDone * 100L) / totalFrames).toInt().coerceIn(0, 99))
                    }
                    if (framesDone <= 0L) {
                        Log.i(TAG, "convert 失败：${src.name} 没有解出任何采样")
                    } else {
                        if (truncated) Log.i(TAG, "convert：源文件比文件头声明的短，按实际 $framesDone 帧收尾")
                        writeWavHeader(o, channels, PCM_RATE, framesDone)
                        o.setLength(WAV_HEADER_BYTES + framesDone * channels * 3)
                        onProgress?.invoke(100)
                        ok = true
                    }
                }
            }
        } catch (t: Throwable) {
            // 原来只 catch(Exception)：OutOfMemoryError 是 Error，会直接穿透（上层 runCatching 兜住，
            // 但这里连一条日志都没有，事后完全无法判断是内存还是数据问题）。
            Log.i(TAG, "convert failed: ${t::class.java.simpleName} ${t.message}")
            ok = false
        }
        if (!ok) {
            // 残缺/0 字节的缓存文件必须删：MainActivity 用 `out.isFile && out.length() > 0`
            // 判断"已转过"，留下半成品就会被当成转换结果直接播放（静音或半首歌）。
            runCatching { if (out.isFile) out.delete() }
            // copyToLocal() 落在 filesDir/dsd-cache 的临时源也一起清：一次失败就永久占着
            // 一份整轨副本（用户看不见、也没法清），DSD 曲目一多就会越积越多。
            if (isCachedSource(src)) {
                runCatching { src.delete() }
                Log.i(TAG, "convert 失败：已清除临时源 ${src.name}")
            }
        }
        return ok
    }

    /**
     * 可分段喂数据的 DSD 抽取器：与旧 `decimate(src, outLen, stage, decim, msbFirst)` **逐位等价**
     * —— 位序、两级 4:1 箱式平均、`(acc2 - stage*stage/2) * (1 shl 24) / (stage*stage)` 的缩放
     * 全都是原样搬过来的；唯一区别是把原本"跨整轨存活"的累加器（acc1/acc1Count/acc2/
     * acc2Count/inner）提升成对象状态，所以可以按块调用、结果与一次处理整轨完全相同。
     */
    private class DsdDecimator(
        private val decim: Int,
        private val msbFirst: Boolean,
        private val stage: Int = 4,
    ) {
        private var acc1 = 0          // 第一级 4 点
        private var acc1Count = 0
        private var acc2 = 0          // 第二级 4 点
        private var acc2Count = 0
        private var inner = 0

        /** 从 [src] 的前 [len] 个字节里抽取，写进 [out]，返回实际产出的采样数。 */
        fun decode(src: ByteArray, len: Int, out: IntArray): Int {
            var o = 0
            for (i in 0 until len) {
                val b = src[i].toInt()
                var bitIndex = 0
                while (bitIndex < 8) {
                    val bit = if (msbFirst) (b shr (7 - bitIndex)) and 1 else (b shr bitIndex) and 1
                    bitIndex++
                    acc1 += bit
                    if (++acc1Count == stage) {
                        acc2 += acc1
                        acc1 = 0; acc1Count = 0
                        if (++acc2Count == stage) {
                            if (++inner >= decim) {
                                inner = 0
                                // acc2 ∈ 0..16 → 映射到 -1..1（DSD 的 0/1 均值 0.5 为中点）
                                if (o < out.size) {
                                    out[o++] = ((acc2 - stage * stage / 2) * (1 shl 24) / (stage * stage))
                                        .coerceIn(-(1 shl 23), (1 shl 23) - 1)
                                }
                            }
                            acc2 = 0; acc2Count = 0
                        }
                    }
                }
            }
            return o
        }
    }

    /**
     * 读某声道从第 [startByte] 个 DSD 字节起的 [n] 个字节到 [buf] 开头，返回实际读到的字节数
     * （源文件被截断时 < n，调用方据此收尾）。
     *
     * DSF：每声道按 [Info.blockSize] 分块**交错**存放 → 每块一次 seek/read；
     * DFF：整轨平面存放 → 一次连续 read。
     */
    private fun readChannelChunk(
        f: RandomAccessFile,
        info: Info,
        channel: Int,
        startByte: Long,
        n: Int,
        buf: ByteArray,
        bytesPerChannel: Long,
    ): Int {
        val end = minOf(startByte + n, bytesPerChannel)
        val block = info.blockSize
        val ch = info.channels.coerceAtLeast(1)
        var p = startByte
        var w = 0
        while (p < end) {
            val take: Int
            val fileOff: Long
            if (block > 0) {
                val blockIndex = p / block
                val within = (p - blockIndex * block).toInt()
                take = minOf((end - p).toInt(), block - within)
                fileOff = info.dataOffset + blockIndex * block * ch + channel.toLong() * block + within
            } else {
                take = (end - p).toInt()
                fileOff = info.dataOffset + channel.toLong() * bytesPerChannel + p
            }
            if (take <= 0) break
            f.seek(fileOff)
            var got = 0
            while (got < take) {
                val r = f.read(buf, w + got, take - got)
                if (r <= 0) return w + got      // EOF：源文件比文件头声明的短
                got += r
            }
            p += take
            w += take
        }
        return w
    }

    /**
     * 写 44 字节 WAV(24bit PCM) 头。可重复调用（内部 seek 回 0 覆盖），
     * 流式转换先写 0 帧占位、结束后用真实帧数回填。
     */
    private fun writeWavHeader(o: RandomAccessFile, chCount: Int, rate: Int, frames: Long) {
        val dataBytes = (frames * chCount * 3).toInt()
        fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        o.seek(0L)
        o.write("RIFF".toByteArray()); o.write(le32(36 + dataBytes)); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); o.write(le32(16)); o.write(le16(1)); o.write(le16(chCount))
        o.write(le32(rate)); o.write(le32(rate * chCount * 3)); o.write(le16(chCount * 3)); o.write(le16(24))
        o.write("data".toByteArray()); o.write(le32(dataBytes))
    }

    /** 是不是 [copyToLocal] 落在应用缓存目录里的临时源（只有这种文件允许在转换失败时删）。 */
    private fun isCachedSource(f: File): Boolean =
        f.parentFile?.name == CACHE_DIR && f.name.startsWith("src-")

    /** 转码结果缓存路径（同目录同名 + .pcm.wav，放在应用私有目录）。 */
    fun cacheFile(context: android.content.Context, src: File): File {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        val key = (src.absolutePath + src.length() + src.lastModified()).hashCode().toUInt().toString(16)
        return File(dir, "$key.wav")
    }
}
