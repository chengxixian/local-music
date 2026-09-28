// SPDX-License-Identifier: GPL-3.0-or-later
// Audio specification caching adapted from Rueded/AURALIS MusicUtils.kt.
package com.localmusic.app.data

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.Locale

data class Song(
    val uri: String, val title: String, val artist: String, val album: String,
    val duration: Long, val size: Long, val modified: Long, val format: String,
    val sampleRate: Int = 0, val bitDepth: Int = 0, val channels: Int = 0,
    val origin: String = "media", val artwork: String? = null,
) {
    val lossless: Boolean get() = format.lowercase(Locale.ROOT) in setOf("flac", "wav", "alac", "aiff")
    val spec: String get() = listOfNotNull(
        format.uppercase(Locale.ROOT).takeIf { it.isNotBlank() },
        if (bitDepth > 0) "${bitDepth}bit" else null,
        if (sampleRate > 0) "${sampleRate / 1000.0}kHz" else null,
    ).joinToString(" · ")
    fun mediaItem(): MediaItem = MediaItem.Builder().setMediaId(uri).setUri(Uri.parse(uri))
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title).setArtist(artist).setAlbumTitle(album)
            .apply { artwork?.let { setArtworkUri(Uri.parse(it)) } }
            .setExtras(Bundle().apply {
                putInt("sampleRate", sampleRate); putInt("bitDepth", bitDepth); putInt("channels", channels)
            }).build()).build()
}

fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
