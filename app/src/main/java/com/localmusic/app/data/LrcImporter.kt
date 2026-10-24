// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.net.Uri
import java.util.Locale

object LrcImporter {
    suspend fun importFiles(context: Context, songs: List<Song>, uris: List<Uri>): Int {
        val candidates = songs.map { it to keys(it) }
        var imported = 0
        for (uri in uris) {
            val name = displayName(context, uri)
            val normalized = normalize(name)
            val match = candidates.firstOrNull { (_, keys) -> keys.any { key -> key == normalized || key.contains(normalized) || normalized.contains(key) } }?.first
                ?: continue
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                ?: continue
            if (LyricsRepository.parse(text, "批量导入") != null && LyricCache.write(context, match.uri, text)) imported++
        }
        return imported
    }

    private fun keys(song: Song): Set<String> = setOf(song.title, "${song.artist} ${song.title}", song.uri.substringAfterLast('/'))
        .map(::normalize).filter { it.isNotBlank() }.toSet()

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\.(flac|mp3|m4a|wav|ogg|opus|aac|lrc)$"), "")
        .replace(Regex("[\\s_\\-—–·•]+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else uri.lastPathSegment.orEmpty()
        } ?: uri.lastPathSegment.orEmpty()
    }.getOrDefault(uri.lastPathSegment.orEmpty()).substringBeforeLast('.')
}
