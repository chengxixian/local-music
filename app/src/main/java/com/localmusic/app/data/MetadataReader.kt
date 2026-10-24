// SPDX-License-Identifier: GPL-3.0-or-later
// jaudiotagger-first file metadata strategy adapted from Rueded/AURALIS MusicUtils.kt.
package com.localmusic.app.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.Locale

object MetadataReader {
    val extensions = setOf("mp3", "aac", "m4a", "mp4", "flac", "wav", "ogg", "opus", "amr", "3gp", "dsf", "dff")
    fun read(context: Context, base: Song, physicalFile: File? = null): Song {
        var result = base
        // DSD：Media3 / jaudiotagger 都读不了，规格只能自己从文件头拿
        // （这一点与上游 AURALIS 的 "DSD/DXD tagging" 同一层，但我们还多了解码）
        //
        // ⚠️ 这里**不能** return：`.dsf` 里通常有完整的 ID3v2 标签（歌名/艺术家/专辑），
        // 提前返回会让 DSD 曲目永远显示成「文件名 / 未知艺术家」。
        // 正确做法：先记下容器规格，继续走通用标签读取，最后再把规格覆盖回去。
        var dsdInfo: Dsd.Info? = null
        if (Dsd.isDsd(base.uri)) {
            dsdInfo = physicalFile?.takeIf { it.canRead() }?.let { Dsd.probe(it) }
                ?: runCatching { Dsd.probeUri(context, Uri.parse(base.uri)) }.getOrNull()
            if (dsdInfo != null) {
                result = result.copy(
                    format = dsdInfo.name,              // DSD64 / DSD128 / DSD256
                    sampleRate = dsdInfo.sampleRate,    // 2822400 / 5644800 / 11289600
                    bitDepth = 1,
                    channels = dsdInfo.channels,
                    duration = dsdInfo.durationMs,
                )
            }
        }
        if (physicalFile?.canRead() == true) {
            try {
                val a = AudioFileIO.read(physicalFile)
                val h = a.audioHeader
                val t = a.tag
                result = result.copy(
                    title = t?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() } ?: base.title,
                    artist = t?.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() } ?: base.artist,
                    album = t?.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() } ?: base.album,
                    duration = h.trackLength.toLong() * 1000,
                    sampleRate = h.sampleRateAsNumber.coerceAtLeast(0),
                    bitDepth = if (base.lossless) h.bitsPerSample.coerceAtLeast(0) else 0,
                    replayGainDb = readReplayGain(t, base.format) ?: base.replayGainDb,
                )
            } catch (_: Exception) { /* provider-only files use Android below */ }
        }
        val uri = Uri.parse(base.uri)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun text(key: Int) = retriever.extractMetadata(key)?.takeIf { it.isNotBlank() && it != "<unknown>" }
            result = result.copy(
                title = text(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: result.title,
                artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: result.artist,
                album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: result.album,
                duration = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: result.duration,
            )
        } catch (_: Exception) { } finally { runCatching { retriever.release() } }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("audio/")) continue
                fun int(key: String) = if (f.containsKey(key)) f.getInteger(key) else 0
                result = result.copy(
                    sampleRate = result.sampleRate.takeIf { it > 0 } ?: int(MediaFormat.KEY_SAMPLE_RATE),
                    channels = int(MediaFormat.KEY_CHANNEL_COUNT),
                    format = if (mime.contains("alac")) "alac" else result.format,
                )
                break
            }
        } catch (_: Exception) { } finally { extractor.release() }
        // FLAC STREAMINFO is authoritative even when Android reports resampled metadata.
        if (base.format.lowercase(Locale.ROOT) == "flac") try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val h = ByteArray(42)
                var got = 0
                while (got < h.size) { val n = input.read(h, got, h.size - got); if (n < 0) break; got += n }
                if (got == 42 && String(h, 0, 4, Charsets.US_ASCII) == "fLaC" && (h[4].toInt() and 127) == 0) {
                    var packed = 0L
                    for (i in 18..25) packed = (packed shl 8) or (h[i].toLong() and 255)
                    val rate = (packed ushr 44).toInt()
                    val channels = ((packed ushr 41) and 7).toInt() + 1
                    val bits = ((packed ushr 36) and 31).toInt() + 1
                    val samples = packed and 0xFFFFFFFFFL
                    if (rate > 0) result = result.copy(sampleRate = rate, channels = channels, bitDepth = bits, duration = samples * 1000 / rate)
                }
            }
        } catch (_: Exception) { }
        // WAV：SAF 条目拿不到物理文件，jaudiotagger 那条路不执行，
        // 位深会一直是 0（曲库规格行只剩 "WAV · 44.1kHz"）。RIFF `fmt ` 块就在文件头 44 字节内，
        // 自己读一次就能补齐声道 / 采样率 / 位深。
        if (base.format.lowercase(Locale.ROOT) == "wav") try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val h = ByteArray(64)
                var got = 0
                while (got < h.size) { val n = input.read(h, got, h.size - got); if (n < 0) break; got += n }
                if (got >= 36 && String(h, 0, 4, Charsets.US_ASCII) == "RIFF" && String(h, 8, 4, Charsets.US_ASCII) == "WAVE") {
                    fun u16(at: Int) = (h[at].toInt() and 255) or ((h[at + 1].toInt() and 255) shl 8)
                    fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
                    if (String(h, 12, 4, Charsets.US_ASCII) == "fmt ") {
                        val channels = u16(22)
                        val rate = u32(24)
                        val bits = u16(34)
                        if (rate > 0) result = result.copy(
                            sampleRate = rate,
                            channels = channels,
                            bitDepth = if (bits > 0) bits else result.bitDepth,
                        )
                    }
                }
            }
        } catch (_: Exception) { }
        // ReplayGain 兜底：SAF 条目没有物理文件，jaudiotagger 读不到，必须自己从容器头里取。
        // DSD 的容器规格（1bit / 2.8MHz）是权威值，通用的 MediaExtractor/Retriever 可能
        // 把它报成别的数字，这里最后再盖回去。
        dsdInfo?.let { info ->
            result = result.copy(
                format = info.name,
                sampleRate = info.sampleRate,
                bitDepth = 1,
                channels = info.channels,
                duration = if (result.duration > 0) result.duration else info.durationMs,
            )
        }
        return enrichReplayGain(context, result)
    }

    /**
     * ReplayGain：**两条路都要走**。
     *
     *  - `content://`（用户选定的 SAF 文件夹 = 现在唯一的曲库来源）没有物理文件，
     *    jaudiotagger 拿不到 File，所以直接读容器头部（[ReplayGainTag]）；
     *  - 有物理文件时先用 jaudiotagger 的结果，读不到再用头部解析兜底。
     *
     * 以前只走 jaudiotagger，而曲库全是 SAF 条目 → 450 首歌的 `replayGainDb` 全是 NULL，
     * 「音量标准化」开关等于没接线。
     */
    private fun readReplayGain(tag: org.jaudiotagger.tag.Tag?, format: String): Float? {
        val fromTag = runCatching {
            val fields = tag?.fields ?: return@runCatching null
            var track: Float? = null
            var album: Float? = null
            while (fields.hasNext()) {
                val field = fields.next() ?: continue
                val id = runCatching { field.id }.getOrNull().orEmpty().uppercase(Locale.ROOT)
                val value = runCatching { field.toString() }.getOrNull().orEmpty()
                when {
                    id.contains("REPLAYGAIN_TRACK_GAIN") && track == null -> track = parseGainValue(value)
                    id.contains("REPLAYGAIN_ALBUM_GAIN") && album == null -> album = parseGainValue(value)
                }
            }
            track ?: album
        }.getOrNull()
        return fromTag
    }

    private fun parseGainValue(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val text = raw.substringAfterLast(':').replace(',', '.')
        val m = Regex("-?\\d+(\\.\\d+)?").find(text) ?: return null
        return m.value.toFloatOrNull()?.coerceIn(-40f, 40f)
    }

    /**
     * `content://` 上的 ReplayGain（和安全起见也给 jaudiotagger 失败的情况兜底）。
     * 由调用方在 `read()` 末尾统一调用一次。
     */
    private fun enrichReplayGain(context: Context, song: Song): Song {
        if (song.replayGainDb != null) return song
        val gain = ReplayGainTag.read(context, Uri.parse(song.uri), song.format) ?: return song
        return song.copy(replayGainDb = gain)
    }
}
