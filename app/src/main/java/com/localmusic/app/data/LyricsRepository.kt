// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌词来源，按优先级：
 *  0. 刮削缓存（[LyricCache]）
 *  1. 同目录同名 `.lrc`（文件路径或 SAF 兄弟文档）
 *  2. 音频文件里内嵌的歌词（ID3 USLT / Vorbis LYRICS）
 *  3. **一次性联网兜底**（网易云 → LRCLIB），每个 uri 进程内只试一次
 *
 * 为什么要第 3 条：以前这里只做 0~2，`load()` 里没有任何联网分支 ——
 * 还没被批量刮削扫到的歌，打开播放页永远显示「没有找到歌词」；
 * 而设置页那个"立即刮削"是唯一入口（关掉自动刮削就彻底没歌词了）。
 * 现在只在本地全都没有时才联网，且同一首只问一次（失败也记账），不会反复重连。
 */
object LyricsRepository {
    data class Line(val timeMs: Long, val text: String)
    data class Lyrics(val lines: List<Line>, val source: String, val timed: Boolean)

    /** LRC 时间标签：`[mm:ss]` / `[mm:ss.xx]` / `[mm:ss:xx]`，分钟最多 3 位。 */
    internal const val LRC_TIME = "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]"

    /**
     * 元数据行（不是歌词）。以前这些行没有 `mm:ss` 时间标签，正好落到"没有标签就当纯文本"
     * 那条分支里，于是 `[ti:晴天]` `[ar:周杰伦]` `[by:xxx]` 会被当成歌词显示在播放页最上面。
     */
    private val metaLine = Regex(
        "^\\[(ti|ar|al|au|by|re|ve|offset|length|tool|kana|language|lyricist|composer)\\s*:[^]]*]$",
        RegexOption.IGNORE_CASE,
    )
    private val offsetTag = Regex("\\[offset\\s*:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    private val timeTag = Regex(LRC_TIME)

    /** 把时间标签换算成毫秒（1 位=百毫秒、2 位=十毫秒、3 位=毫秒）。 */
    internal fun tagMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLongOrNull() ?: 0
        val sec = m.groupValues[2].toLongOrNull() ?: 0
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> (frac.toLongOrNull() ?: 0) * 100
            2 -> (frac.toLongOrNull() ?: 0) * 10
            else -> frac.take(3).toLongOrNull() ?: 0
        }
        return min * 60_000 + sec * 1_000 + ms
    }

    /** 已经为哪些 uri 试过联网兜底（成功失败都算）—— 进程内只试一次。 */
    private val onlineTried = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(context: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
        // 0) 自动刮削缓存的歌词（最优先：用户点了"已刮到"就该显示它）
        LyricCache.read(context, song.uri)?.let { text -> parse(text, "在线刮削")?.let { l -> return@withContext l } }
        val resolved = resolve(context, song)
        // 1) 同目录同名 .lrc（文件路径或 SAF 兄弟文档）
        runCatching { resolved?.lrcFile?.takeIf { it.isFile && it.canRead() }?.let { LrcText.decodeFile(it) } }
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
        // 3) 本地全都没有 → 有界的一次性联网兜底
        fetchOnce(context, song)
    }

    /**
     * 一次性联网兜底：同一 uri 在一个进程里只请求一次。
     *
     * 成败都记账（"不能因为一次失败就每次重新请求"），但**协程被取消**（切歌/离开播放页）
     * 不算试过 —— 否则用户点一下就走会把这首歌的兜底机会白白用掉。
     * 成功后写入 [LyricCache]，下次打开就是纯本地/离线路径。
     */
    private suspend fun fetchOnce(context: Context, song: Song): Lyrics? {
        if (!onlineTried.add(song.uri)) return null
        val text = try {
            Scraper.fetchLyricsFor(song)
        } catch (e: CancellationException) {
            onlineTried.remove(song.uri)
            throw e
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        if (text.isNullOrBlank()) return null
        runCatching { LyricCache.write(context, song.uri, text) }
        return parse(text, "在线刮削")
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
        // 统一走 LrcText：GBK / UTF-16 的 .lrc 以前在这里被当成 UTF-8 直接读成乱码
        return context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, lrcDocId))
            ?.use { LrcText.decode(it.readBytes()) }
    }

    /**
     * 解析 LRC；没有时间戳就当纯文本逐行显示。
     *
     * 修了三个坑：
     *  1. `[ti:] [ar:] [al:] [by:] [offset:]` 这些元数据行以前被当成歌词显示；
     *  2. 正文用 `substringAfterLast(']')` 取 —— 正文里只要有 `]`（`把你[捧在手心]`）
     *     前半段就被截掉；现在取"最后一个时间标签之后"；
     *  3. `[offset:±N]` 完全没实现（网易云/绝大多数导出工具都带这一行）。
     */
    fun parse(raw: String, source: String): Lyrics? {
        val text = raw.removePrefix("\uFEFF")
        // LRC 规范：offset 正值表示歌词**提前**显示，所以有效时间 = 标签时间 - offset
        val offsetMs = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<Line>()
        var timed = false
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (metaLine.matches(line)) return@forEach
            val matches = timeTag.findAll(line).toList()
            val content = if (matches.isEmpty()) line else line.substring(matches.last().range.last + 1).trim()
            if (matches.isEmpty()) {
                if (content.isNotEmpty()) lines += Line(-1, content)
            } else {
                timed = true
                // 一行多个时间标签（`[00:12.34][00:15.00]text`）→ 每个标签生成一行
                matches.forEach { m -> lines += Line((tagMs(m) - offsetMs).coerceAtLeast(0), content) }
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

/**
 * `.lrc` 文本解码。
 *
 * 以前所有读取路径都写死 `Charsets.UTF_8`：GBK 的 .lrc（简体中文歌词最常见的编码）
 * 会整篇变成替换字符，UTF-16 的（Windows 记事本"另存为 Unicode"）连 `[mm:ss]` 时间标签
 * 都解析不出来 —— 于是"明明放了个 lrc 文件却没歌词"。
 * 规则：BOM 优先 → UTF-8 → 出现 `\uFFFD` 就按 GB18030 重解一次。
 */
object LrcText {
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, charset("UTF-16LE"))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, charset("UTF-16BE"))
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if ('\uFFFD' !in utf8) return utf8
        // UTF-8 解出替换字符 → 几乎一定是 GBK/GB18030（简体中文 LRC 的常见编码）
        return runCatching { String(bytes, charset("GB18030")) }.getOrDefault(utf8)
    }

    fun decodeFile(file: File): String = decode(file.readBytes())
}
