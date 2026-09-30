// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 自动刮削：封面 + 歌词。
 *
 * 用的是**公开接口、不需要 key**：
 *  - 封面：iTunes Search API → Deezer 兜底（都只取"能匹配上歌名"的第一条，避免配错图）
 *  - 歌词：LRCLIB（`/api/get` 精确匹配，失败退到 `/api/search`），优先拿带时间戳的 LRC
 *
 * 结果落到应用自己的目录里（封面走 CoverStore，歌词走 [LyricCache]），
 * 所以刮过一次就永远离线可用，不会每次打开都重新联网。
 */
object Scraper {
    private const val UA = "local-music/0.1 (Android; personal music player)"
    private const val TIMEOUT_MS = 8000
    private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024

    data class Status(
        val running: Boolean = false,
        val message: String = "未开始",
        val covers: Int = 0,
        val lyrics: Int = 0,
        val tried: Int = 0,
        val failures: List<String> = emptyList(),
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 只补缺：已有封面/歌词的歌会跳过（不会白耗流量）。[limit] 限制单次尝试的歌曲数。 */
    suspend fun scrape(
        context: Context,
        songs: List<Song>,
        wantCover: Boolean,
        wantLyrics: Boolean,
        limit: Int = 40,
    ) = withContext(Dispatchers.IO) {
        var covers = 0
        var lyrics = 0
        var tried = 0
        val failures = mutableListOf<String>()
        _status.value = Status(true, "开始刮削…")
        try {
            for (song in songs) {
                currentCoroutineContext().ensureActive()
                if (tried >= limit) break
                val needCover = wantCover && CoverStore.pathFor(context, song.uri) == null
                val needLyrics = wantLyrics && LyricCache.read(context, song.uri) == null
                if (!needCover && !needLyrics) continue
                tried++
                _status.value = Status(true, "刮削中：${song.title}", covers, lyrics, tried, failures.takeLast(3))
                if (needCover) {
                    val bytes = runCatching { findCover(song.title, song.artist) }.getOrNull()
                    if (bytes != null && CoverStore.setFromBytes(context, song.uri, bytes)) covers++
                    else failures += "${song.title}：没找到封面"
                    delay(180)
                }
                if (needLyrics) {
                    val hit = runCatching { findLyrics(song) }.getOrNull()
                    if (hit != null && LyricCache.write(context, song.uri, hit)) lyrics++
                    else failures += "${song.title}：没找到歌词"
                    delay(180)
                }
            }
            _status.value = Status(false, "刮削完成：封面 $covers · 歌词 $lyrics（共试 $tried 首）", covers, lyrics, tried, failures.takeLast(5))
        } catch (e: CancellationException) {
            _status.value = Status(false, "已停止：封面 $covers · 歌词 $lyrics", covers, lyrics, tried, failures.takeLast(3))
            throw e
        }
    }

    // ── 封面 ──

    private fun findCover(title: String, artist: String): ByteArray? {
        if (title.isBlank()) return null
        val term = if (artist.isBlank() || artist.startsWith("未知")) title else "$artist $title"
        itunesCover(term, title)?.let { return it }
        return deezerCover(term, title)
    }

    private fun itunesCover(term: String, title: String): ByteArray? = try {
        val body = get("https://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + encode(term)) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        var found: ByteArray? = null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            if (!titleMatches(title, item.optString("trackName"))) continue
            val art = item.optString("artworkUrl100")
            if (art.isBlank()) continue
            // iTunes 的 URL 里尺寸是写死的，换掉就拿到大图
            found = fetchImage(art.replace("100x100", "600x600")) ?: fetchImage(art)
            if (found != null) break
        }
        found
    } catch (_: Exception) { null }

    private fun deezerCover(term: String, title: String): ByteArray? = try {
        val body = get("https://api.deezer.com/search?limit=8&q=" + encode(term)) ?: return null
        val data = JSONObject(body).optJSONArray("data") ?: return null
        var found: ByteArray? = null
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (!titleMatches(title, item.optString("title"))) continue
            val album = item.optJSONObject("album") ?: continue
            val cover = album.optString("cover_xl").ifBlank { album.optString("cover_big") }
            if (cover.isBlank()) continue
            found = fetchImage(cover)
            if (found != null) break
        }
        found
    } catch (_: Exception) { null }

    /** 标题匹配（忽略大小写/标点/空格）：避免"同名不同歌"配错封面。 */
    private fun titleMatches(wanted: String, candidate: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val a = norm(wanted)
        val b = norm(candidate)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || b.contains(a) || a.contains(b)
    }

    // ── 歌词 ──

    private fun findLyrics(song: Song): String? {
        val artist = song.artist.takeIf { it.isNotBlank() && !it.startsWith("未知") } ?: ""
        val album = song.album.takeIf { it.isNotBlank() && !it.startsWith("未知") } ?: ""
        val seconds = (song.duration / 1000).toInt()
        if (artist.isNotBlank()) {
            val url = buildString {
                append("https://lrclib.net/api/get?artist_name=").append(encode(artist))
                append("&track_name=").append(encode(song.title))
                if (album.isNotBlank()) append("&album_name=").append(encode(album))
                if (seconds > 0) append("&duration=").append(seconds)
            }
            val hit = runCatching {
                val json = JSONObject(get(url) ?: return@runCatching null)
                pick(json.optString("syncedLyrics")) ?: pick(json.optString("plainLyrics"))
            }.getOrNull()
            if (hit != null) return hit
        }
        val query = encode(listOf(song.title, artist).filter { it.isNotBlank() }.joinToString(" "))
        val search = runCatching {
            val array = JSONArray(get("https://lrclib.net/api/search?q=$query") ?: return@runCatching null)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val text = pick(item.optString("syncedLyrics")) ?: pick(item.optString("plainLyrics"))
                if (text != null) return text
            }
            null
        }.getOrNull()
        return search
    }

    private fun pick(text: String?): String? = text?.takeIf { it.isNotBlank() && it.length in 8..200_000 }

    // ── HTTP ──

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun fetchImage(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_IMAGE_BYTES) return null
                }
                out.toByteArray()
            }
            if (isImage(bytes)) bytes else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        val webp = String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        return jpeg || png || webp
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
}

/** 刮削（或用户手动放的）歌词缓存：`filesDir/lyrics/<sha1(uri)>.lrc`。 */
object LyricCache {
    fun file(context: Context, songUri: String): File =
        File(File(context.filesDir, "lyrics").apply { mkdirs() }, sha1(songUri) + ".lrc")

    fun read(context: Context, songUri: String): String? = try {
        file(context, songUri).takeIf { it.isFile && it.length() > 8 }?.readText(Charsets.UTF_8)
    } catch (_: Exception) { null }

    fun write(context: Context, songUri: String, text: String): Boolean = try {
        file(context, songUri).writeText(text)
        true
    } catch (_: Exception) { false }

    fun clear(context: Context): Int {
        val dir = File(context.filesDir, "lyrics")
        val files = dir.listFiles() ?: return 0
        return files.count { it.delete() }
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
