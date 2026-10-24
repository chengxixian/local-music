// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.Locale

/**
 * 直接读容器里的 ReplayGain 标签（不经过 jaudiotagger）。
 *
 * 为什么需要它：曲库现在只扫**用户选定的 SAF 文件夹**，歌曲 URI 是 `content://…`，
 * 没有可交给 `AudioFileIO.read(File)` 的物理文件 —— 于是 jaudiotagger 那条路整段不执行，
 * `replayGainDb` 永远是 NULL，「音量标准化」这个开关实际上没有任何效果
 * （实测设备库里 450 首全是 NULL 就是这个原因）。
 *
 * 这里只读文件**头部很小的一段**（FLAC 的 metadata 块 / MP3 的 ID3v2 标签），
 * 所以每首歌的代价接近零，也不需要把整首拷到临时文件。
 *
 * 支持：
 *  - FLAC：`VORBIS_COMMENT`（block type 4）里的 `REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_ALBUM_GAIN`
 *  - MP3：ID3v2 的 `TXXX` 帧（含 v2.3 / v2.4、UTF-16 描述等情形，用字节扫描兜底）
 *  - 其它格式返回 null（交给调用方回落到 jaudiotagger / 无增益）
 */
object ReplayGainTag {
    /** 标签里可能出现的键（大小写不敏感）。 */
    private val TRACK_KEYS = listOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_GAIN_DB")
    private val ALBUM_KEYS = listOf("REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_GAIN_DB")

    fun read(context: Context, uri: Uri, format: String): Float? = runCatching {
        when (format.lowercase(Locale.ROOT)) {
            "flac" -> readFlac(context, uri)
            "mp3", "mp2" -> readId3(context, uri)
            else -> null
        }
    }.getOrNull()

    // ───────────────────────── FLAC ─────────────────────────

    private fun readFlac(context: Context, uri: Uri): Float? {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw)
            val magic = ByteArray(4)
            if (input.readFully(magic) != 4 || String(magic, Charsets.US_ASCII) != "fLaC") return null
            var track: Float? = null
            var album: Float? = null
            while (true) {
                val header = ByteArray(4)
                if (input.readFully(header) != 4) break
                val last = (header[0].toInt() and 0x80) != 0
                val type = header[0].toInt() and 0x7f
                val length = ((header[1].toInt() and 0xff) shl 16) or
                    ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
                if (type == 4) {
                    val block = ByteArray(length)
                    if (input.readFully(block) != length) break
                    val comments = parseVorbisComment(block)
                    comments.forEach { (k, v) ->
                        val key = k.uppercase(Locale.ROOT)
                        when {
                            key in TRACK_KEYS && track == null -> track = parseGain(v)
                            key in ALBUM_KEYS && album == null -> album = parseGain(v)
                        }
                    }
                } else if (length > 0) {
                    if (input.skipFully(length.toLong()) != length.toLong()) break
                }
                if (last) break
            }
            return track ?: album
        }
        return null
    }

    /** `vendor_len(4LE) vendor count(4LE) [len(4LE) "KEY=value"]*` */
    private fun parseVorbisComment(block: ByteArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var p = 0
        fun u32(): Long? {
            if (p + 4 > block.size) return null
            val v = (block[p].toLong() and 0xff) or
                ((block[p + 1].toLong() and 0xff) shl 8) or
                ((block[p + 2].toLong() and 0xff) shl 16) or
                ((block[p + 3].toLong() and 0xff) shl 24)
            p += 4
            return v
        }
        val vendorLen = u32() ?: return out
        if (vendorLen < 0 || p + vendorLen > block.size) return out
        p += vendorLen.toInt()
        val count = u32() ?: return out
        var i = 0L
        while (i < count && p + 4 <= block.size) {
            val len = u32() ?: break
            if (len < 0 || p + len > block.size) break
            val text = String(block, p, len.toInt(), Charsets.UTF_8)
            p += len.toInt()
            val eq = text.indexOf('=')
            if (eq > 0) out += text.substring(0, eq) to text.substring(eq + 1)
            i++
        }
        return out
    }

    // ───────────────────────── MP3 / ID3v2 ─────────────────────────

    private fun readId3(context: Context, uri: Uri): Float? {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw)
            val head = ByteArray(10)
            if (input.readFully(head) != 10) return null
            if (String(head, 0, 3, Charsets.US_ASCII) != "ID3") return null
            val major = head[3].toInt()
            val flags = head[5].toInt()
            val size = ((head[6].toInt() and 0x7f) shl 21) or
                ((head[7].toInt() and 0x7f) shl 14) or
                ((head[8].toInt() and 0x7f) shl 7) or
                (head[9].toInt() and 0x7f)
            // 标签本体（不含 10 字节头；v2.3/v2.4 可能还有 10 字节扩展头，一并在里面）
            val body = ByteArray(size.coerceAtMost(8 * 1024 * 1024))
            if (input.readFully(body) <= 0) return null
            // 标签里的文本字段基本都是 ASCII/Latin-1（UTF-16 只影响描述串的编码），
            // 所以直接在字节里找键名再取后面一小段，比按版本逐帧解析稳得多。
            val track = grepGain(body, TRACK_KEYS)
            if (track != null) return track
            if (flags and 0x10 != 0) { /* footer：v2.4 才有，键一定在 body 里，忽略 */ }
            if (major == 0) return null
            return grepGain(body, ALBUM_KEYS)
        }
        return null
    }

    /** 在字节里找 `KEY`（忽略大小写），取其后 ≤64 字节的可打印文本解析成 dB。 */
    private fun grepGain(data: ByteArray, keys: List<String>): Float? {
        val lower = String(data, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        for (key in keys) {
            var from = 0
            val needle = key.lowercase(Locale.ROOT)
            while (true) {
                val at = lower.indexOf(needle, from)
                if (at < 0) break
                val tail = lower.substring(at + needle.length, minOf(lower.length, at + needle.length + 64))
                val gain = parseGain(tail)
                if (gain != null) return gain
                from = at + needle.length
            }
        }
        return null
    }

    // ───────────────────────── 数值解析 ─────────────────────────

    /**
     * `-6.50 dB` / `-6.5` / `: -6.5 dB` / `=+3.20dB` 都能解析。
     * 只接受带小数点的数字，避免把键名里/帧长度里的整数误当成增益。
     */
    private fun parseGain(raw: String): Float? {
        val text = raw.replace(',', '.')
        val m = Regex("-?\\d+\\.\\d+").find(text) ?: return null
        val value = m.value.toFloatOrNull() ?: return null
        return value.coerceIn(-40f, 40f)
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        if (total == 0) throw EOFException()
        return total
    }

    /** `InputStream.skip` 允许少跳，循环补足。 */
    private fun InputStream.skipFully(count: Long): Long {
        var remaining = count
        while (remaining > 0) {
            val n = skip(remaining)
            if (n <= 0) {
                // skip 返回 0 时退回"读一小块丢掉"，避免死循环
                if (read() < 0) break
                remaining--
                continue
            }
            remaining -= n
        }
        return count - remaining
    }
}
