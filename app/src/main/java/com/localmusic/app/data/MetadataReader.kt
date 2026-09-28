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
    val extensions = setOf("mp3", "aac", "m4a", "mp4", "flac", "wav", "ogg", "opus", "amr", "3gp")
    fun read(context: Context, base: Song, physicalFile: File? = null): Song {
        var result = base
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
        return result
    }
}
