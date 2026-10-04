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
        val dir = File(context.filesDir, "dsd-cache").apply { mkdirs() }
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
     * DSD → WAV（176.4kHz / 24bit / 与源相同声道数）。
     *
     * 滤波：两级 4:1 箱式平均级联 = 16 点三角窗（sinc²），对 DSD 高频噪声是必需的；
     * 直接抽样会把超声噪声原样搬进可听带内，听起来发毛。
     */
    fun convertToWav(src: File, out: File, info: Info, onProgress: ((Int) -> Unit)? = null): Boolean = try {
        val ratio = (info.sampleRate / PCM_RATE).coerceAtLeast(1)   // DSD64=16, DSD128=32
        val stage = 4                                                // 两级各 4:1
        require(ratio % (stage * stage) == 0 || ratio >= stage * stage) { "unsupported ratio $ratio" }
        val decim = ratio / (stage * stage)                          // 一级内部再抽 decim（DSD128 时为 2）
        RandomAccessFile(src, "r").use { f ->
            f.seek(info.dataOffset)
            val bytesPerChannel = info.bits / 8
            val perChannel = ByteArray(minOf(bytesPerChannel, Int.MAX_VALUE.toLong()).toInt())
            // DSF 的交错块要按块读：每声道一块
            val channelData = arrayOfNulls<ByteArray>(info.channels)
            for (c in 0 until info.channels) channelData[c] = ByteArray(perChannel.size)
            if (info.blockSize > 0) {
                val block = info.blockSize
                val chunks = (bytesPerChannel + block - 1) / block
                for (b in 0 until chunks) {
                    for (c in 0 until info.channels) {
                        val start = (b * block).toInt()
                        val n = minOf(block.toLong(), bytesPerChannel - b * block).toInt()
                        if (n <= 0) continue
                        val tmp = ByteArray(n)
                        f.readFully(tmp)
                        System.arraycopy(tmp, 0, channelData[c]!!, start, n)
                    }
                }
            } else {
                // DFF：整段读入后按声道切分
                val all = ByteArray(minOf(f.length() - info.dataOffset, Int.MAX_VALUE.toLong()).toInt())
                f.readFully(all)
                val per = all.size / info.channels
                for (c in 0 until info.channels) System.arraycopy(all, c * per, channelData[c]!!, 0, per)
            }

            val samplesPerChannel = (info.bits / ratio).toInt()
            val outChannel = Array(info.channels) { IntArray(samplesPerChannel) }
            for (c in 0 until info.channels) {
                outChannel[c] = decimate(channelData[c]!!, samplesPerChannel, stage, decim, info.msbFirst)
                onProgress?.invoke((c + 1) * 100 / info.channels)
            }

            out.parentFile?.mkdirs()
            writeWav24(out, outChannel, info.channels, PCM_RATE)
        }
        true
    } catch (e: Exception) {
        Log.i(TAG, "convert failed: ${e::class.java.simpleName} ${e.message}")
        false
    }

    /** 两级箱式平均 + 抽取，输出 -1..1 的 24bit 整数。 */
    private fun decimate(src: ByteArray, outLen: Int, stage: Int, decim: Int, msbFirst: Boolean): IntArray {
        val out = IntArray(outLen)
        var o = 0
        var acc1 = 0          // 第一级 4 点
        var acc1Count = 0
        var acc2 = 0          // 第二级 4 点
        var acc2Count = 0
        var inner = 0
        var byteIndex = 0
        var bitIndex = 0
        while (o < outLen && byteIndex < src.size) {
            val b = src[byteIndex].toInt()
            val bit = if (msbFirst) (b shr (7 - bitIndex)) and 1 else (b shr bitIndex) and 1
            bitIndex++
            if (bitIndex == 8) { bitIndex = 0; byteIndex++ }
            acc1 += bit
            if (++acc1Count == stage) {
                acc2 += acc1
                acc1 = 0; acc1Count = 0
                if (++acc2Count == stage) {
                    if (++inner >= decim) {
                        inner = 0
                        // acc2 ∈ 0..16 → 映射到 -1..1（DSD 的 0/1 均值 0.5 为中点）
                        val v = ((acc2 - stage * stage / 2) * (1 shl 24) / (stage * stage)).coerceIn(-(1 shl 23), (1 shl 23) - 1)
                        out[o++] = v
                    }
                    acc2 = 0; acc2Count = 0
                }
            }
        }
        return out
    }

    /** 写 24bit PCM 小端 WAV。 */
    private fun writeWav24(out: File, channels: Array<IntArray>, chCount: Int, rate: Int) {
        val frames = channels.minOf { it.size }
        val dataBytes = frames * chCount * 3
        out.outputStream().buffered(1 shl 16).use { os ->
            fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
            fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
            os.write("RIFF".toByteArray()); os.write(le32(36 + dataBytes)); os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray()); os.write(le32(16)); os.write(le16(1)); os.write(le16(chCount))
            os.write(le32(rate)); os.write(le32(rate * chCount * 3)); os.write(le16(chCount * 3)); os.write(le16(24))
            os.write("data".toByteArray()); os.write(le32(dataBytes))
            val buf = ByteArray(frames * chCount * 3)
            var p = 0
            for (i in 0 until frames) {
                for (c in 0 until chCount) {
                    val v = channels[c][i]
                    buf[p++] = (v and 0xFF).toByte()
                    buf[p++] = ((v shr 8) and 0xFF).toByte()
                    buf[p++] = ((v shr 16) and 0xFF).toByte()
                }
            }
            os.write(buf)
        }
    }

    /** 转码结果缓存路径（同目录同名 + .pcm.wav，放在应用私有目录）。 */
    fun cacheFile(context: android.content.Context, src: File): File {
        val dir = File(context.filesDir, "dsd-cache").apply { mkdirs() }
        val key = (src.absolutePath + src.length() + src.lastModified()).hashCode().toUInt().toString(16)
        return File(dir, "$key.wav")
    }
}
