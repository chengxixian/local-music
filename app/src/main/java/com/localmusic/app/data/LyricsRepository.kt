// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * 歌词：优先**同目录同名 .lrc**，其次是音频文件里内嵌的歌词（ID3 USLT / Vorbis LYRICS）。
 *
 * 为什么不做在线歌词：这是本地播放器，在线歌词要联网、要第三方接口、还涉及授权；
 * 用户自己的 .lrc 与内嵌歌词才是"本地"该有的来源。找不到就如实说找不到。
 */
object LyricsRepository {
    data class Line(val timeMs: Long, val text: String)
    data class Lyrics(val lines: List<Line>, val source: String, val timed: Boolean)

    suspend fun load(context: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val resolved = resolve(context, song)
        // 1) 同目录同名 .lrc（文件路径或 SAF 兄弟文档）
        runCatching { resolved?.lrcFile?.takeIf { it.isFile && it.canRead() }?.readText(Charsets.UTF_8) }
            .getOrNull()?.let { text -> parse(text, "同名 .lrc")?.let { l -> return@withContext l } }
        runCatching { readSiblingLrc(context, song) }.getOrNull()
            ?.let { text -> parse(text, "同名 .lrc")?.let { l -> return@withContext l } }
        // 2) 内嵌歌词
        resolved?.audioFile?.let { file ->
            runCatching {
                java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF
                val tag = AudioFileIO.read(file).tag ?: return@runCatching null
                val embedded = tag.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
                embedded?.let { parse(it, "内嵌歌词") }
            }.getOrNull()?.let { return@withContext it }
        }
        null
    }

    private data class Resolved(val audioFile: File?, val lrcFile: File?)

    /** 把 Uri 还原成"真实路径"（私有文件、MediaStore、主存储卷上的 SAF 文档都支持）。 */
    private fun resolve(context: Context, song: Song): Resolved? {
        val uri = Uri.parse(song.uri)
        val path: String? = when {
            uri.scheme == "file" -> uri.path
            uri.authority == "media" -> runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            uri.authority?.contains("externalstorage") == true -> runCatching {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:")) {
                    android.os.Environment.getExternalStorageDirectory().absolutePath + "/" +
                        docId.removePrefix("primary:")
                } else null
            }.getOrNull()
            else -> null
        } ?: return null
        val audio = File(path)
        val lrc = File(audio.parentFile, audio.nameWithoutExtension + ".lrc")
        return Resolved(audio.takeIf { it.isFile }, lrc)
    }

    /** SAF 场景：音频与 .lrc 都在授权目录里，按兄弟文档查一次。 */
    private fun readSiblingLrc(context: Context, song: Song): String? {
        val uri = Uri.parse(song.uri)
        if (uri.authority?.contains("externalstorage") != true) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parentId = docId.substringBeforeLast('/', "")
        val wanted = docId.substringAfterLast('/').substringBeforeLast('.') + ".lrc"
        val tree = runCatching { DocumentsContract.buildTreeDocumentUri(uri.authority, docId.substringBeforeLast('/')) }.getOrNull() ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val lrcDocId = context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            var found: String? = null
            while (c.moveToNext()) if (c.getString(1) == wanted) { found = c.getString(0); break }
            found
        } ?: return null
        return context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, lrcDocId))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 解析 LRC；没有时间戳就当纯文本逐行显示。 */
    fun parse(raw: String, source: String): Lyrics? {
        val text = raw.removePrefix("\uFEFF")
        val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        val lines = mutableListOf<Line>()
        var timed = false
        text.lineSequence().forEach { rawLine ->
            val matches = timeTag.findAll(rawLine).toList()
            val content = rawLine.substringAfterLast(']').trim()
            if (matches.isEmpty()) {
                if (content.isNotEmpty()) lines += Line(-1, content)
            } else {
                timed = true
                matches.forEach { m ->
                    val min = m.groupValues[1].toLongOrNull() ?: 0
                    val sec = m.groupValues[2].toLongOrNull() ?: 0
                    val fracRaw = m.groupValues[3]
                    val ms = when (fracRaw.length) {
                        0 -> 0L
                        1 -> fracRaw.toLong() * 100
                        2 -> fracRaw.toLong() * 10
                        else -> fracRaw.take(3).toLong()
                    }
                    lines += Line(min * 60_000 + sec * 1000 + ms, content)
                }
            }
        }
        if (lines.isEmpty()) return null
        return Lyrics(if (timed) lines.sortedBy { it.timeMs } else lines, source, timed)
    }

    /** 当前应高亮的行号（时间戳歌词）。 */
    fun currentIndex(lyrics: Lyrics, positionMs: Long): Int {
        if (!lyrics.timed) return -1
        var index = -1
        for (i in lyrics.lines.indices) {
            if (lyrics.lines[i].timeMs in 0..positionMs) index = i else if (lyrics.lines[i].timeMs > positionMs) break
        }
        return index
    }
}
