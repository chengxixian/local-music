// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 批量导入用户选的 `.lrc`。
 *
 * 原来的三个问题：
 *  1. 整个循环跑在调用者的线程上（MainActivity 里是 Compose 的作用域）——读文件、
 *     解析、写盘全在 UI 线程；现在整体 `withContext(Dispatchers.IO)`。
 *  2. 匹配是**双向 contains**：`Hotel California` 的歌词会被塞给
 *     `Hotel California (Live)`、甚至任何名字包含它的歌；更糟的是 keys 里还塞了
 *     uri 尾部（一长串百分号编码），归一化之后基本什么都能匹配上。
 *     现在只认**精确**匹配（歌名 / 「歌手 - 歌名」/「歌名 - 歌手」）。
 *  3. 只返回一个"成功数"，分不清"没匹配上"和"读不出来"；现在 [importDetailed]
 *     返回三个计数，[importFiles] 保留旧签名（返回成功数）给现有调用方。
 */
object LrcImporter {
    /** 一次批量导入的结果。 */
    data class Result(val imported: Int, val unmatched: Int, val failed: Int) {
        val total: Int get() = imported + unmatched + failed
    }

    /** 兼容旧调用方：返回导入成功的数量。 */
    suspend fun importFiles(context: Context, songs: List<Song>, uris: List<Uri>): Int =
        importDetailed(context, songs, uris).imported

    /** 带明细的导入：成功 / 没匹配上 / 读取或解析失败。 */
    suspend fun importDetailed(context: Context, songs: List<Song>, uris: List<Uri>): Result =
        withContext(Dispatchers.IO) {
            var imported = 0
            var unmatched = 0
            var failed = 0
            // 预先建一张「严格键 → 歌」的表；重名时以曲库里的第一首为准
            val byKey = HashMap<String, Song>()
            for (song in songs) exactKeys(song).forEach { byKey.putIfAbsent(it, song) }
            for (uri in uris) {
                currentCoroutineContext().ensureActive()
                val key = normalize(displayName(context, uri))
                val match = if (key.isBlank()) null else byKey[key]
                if (match == null) { unmatched++; continue }
                val text = runCatching {
                    // 统一解码：GBK / UTF-16 的 .lrc 也要能导入（以前一律按 UTF-8 读成乱码）
                    context.contentResolver.openInputStream(uri)?.use { LrcText.decode(it.readBytes()) }
                }.getOrNull()
                if (text.isNullOrBlank()) { failed++; continue }
                if (LyricsRepository.parse(text, "批量导入") == null || !LyricCache.write(context, match.uri, text)) {
                    failed++
                    continue
                }
                imported++
            }
            Result(imported, unmatched, failed)
        }

    /** 严格匹配键：歌名、`歌手 - 歌名`、`歌名 - 歌手`（都归一化过）。 */
    private fun exactKeys(song: Song): Set<String> = buildSet {
        add(normalize(song.title))
        val artist = song.artist.takeIf { it.isNotBlank() && !it.startsWith("未知") }
        if (artist != null) {
            add(normalize("$artist - ${song.title}"))
            add(normalize("${song.title} - $artist"))
        }
        remove("")
    }

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
