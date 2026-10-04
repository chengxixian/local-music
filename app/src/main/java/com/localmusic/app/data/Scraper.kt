// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import com.localmusic.app.R
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
        wantNetease: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        var covers = 0
        var lyrics = 0
        var tried = 0
        var neteaseCovers = 0
        var neteaseLyrics = 0
        var coverDiagDone = false
        val failures = mutableListOf<String>()
        _status.value = Status(true, context.getString(R.string.scrape_starting))
        try {
            // 先把"之前刮削盖住的、其实自带封面"的歌恢复原样（自动纠错，不用用户手动点）
            val restored = runCatching { CoverStore.restoreOriginals(context, songs) }.getOrDefault(0)

            // ── 阶段一：需要封面的歌，先逐首搜索拿 songId（每首之间留间隔），再**合批**取专辑图 ──
            // 分开做是为了把"搜索/详情/歌词"三连发的密度打散，避开网易云的限流。
            val coverUrlById = mutableMapOf<Long, String>()
            val songIdOf = mutableMapOf<String, Long>()
            if (wantCover && wantNetease) {
                val targets = songs.filter { song ->
                    CoverStore.pathFor(context, song.uri) == null && !ArtworkProbe.hasOwnArtwork(context, song)
                }.take(limit)
                var index = 0
                while (index < targets.size) {
                    currentCoroutineContext().ensureActive()
                    val chunk = targets.subList(index, minOf(index + 8, targets.size))
                    val ids = mutableListOf<Long>()
                    for (song in chunk) {
                        _status.value = Status(true, context.getString(R.string.scrape_searching, song.title), covers, lyrics, tried, failures.takeLast(3))
                        val hit = runCatching { neteaseSearch(song.title, song.artist) }.getOrNull()
                        if (hit != null) {
                            songIdOf[song.uri] = hit.id
                            ids += hit.id
                        }
                        delay(600)   // 搜索之间的间隔（原来三连发就是这个导致的限流）
                    }
                    if (ids.isNotEmpty()) {
                        coverUrlById.putAll(runCatching { neteaseCoversByIds(ids) }.getOrDefault(emptyMap()))
                    }
                    index += 8
                }
            }

            for (song in songs) {
                currentCoroutineContext().ensureActive()
                if (tried >= limit) break
                val needCover = wantCover && CoverStore.pathFor(context, song.uri) == null &&
                    !ArtworkProbe.hasOwnArtwork(context, song)
                val needLyrics = wantLyrics && LyricCache.read(context, song.uri) == null
                if (!needCover && !needLyrics) continue
                tried++
                _status.value = Status(true, context.getString(R.string.scrape_working, song.title), covers, lyrics, tried, failures.takeLast(3))
                // 网易云一首只搜一次，封面和歌词共用这个结果
                val netease = if (wantNetease) runCatching { neteaseSearch(song.title, song.artist) }.getOrNull() else null
                if (needCover) {
                    // 封面优先用阶段一合批拿到的地址（1 个请求 8 张），没有才回落 iTunes/Deezer
                    val batched = coverUrlById[songIdOf[song.uri]]
                    val fromNetease = batched?.let { url ->
                        val sized = if (url.contains("?")) "$url&param=600y600" else "$url?param=600y600"
                        runCatching { fetchImage(sized) ?: fetchImage(url) }.getOrNull()
                    }
                    val bytes = fromNetease ?: runCatching { findCover(song.title, song.artist) }.getOrNull()
                    if (bytes != null && CoverStore.setFromBytes(context, song.uri, bytes, refresh = false)) {
                        covers++
                        if (fromNetease != null) neteaseCovers++
                        // 攒够几张再让 UI 重载一次：每存一张就刷新会让整墙封面反复重解码（卡顿来源）
                        if (covers % 6 == 0) CoverStore.refresh()
                    } else {
                        failures += context.getString(R.string.scrape_no_cover, song.title)
                        // 逐首诊断：到底断在哪一步（搜不到 / detail 没图 / 图被当占位图拦下 / 兜底也miss）
                        val detailPic = netease?.let { runCatching { neteaseCoverById(it.id) }.getOrNull() }
                        // 头一次 miss 时做一次接口对照：旧 detail 接口 vs v3 接口，看是"没图"还是"被限流"
                        if (netease != null && !coverDiagDone) {
                            coverDiagDone = true
                            val old = probeGet("https://music.163.com/api/song/detail?ids=%5B${netease.id}%5D", neteaseHeaders)
                            val v3 = probeGet(
                                "https://music.163.com/api/v3/song/detail?c=%5B%7B%22id%22%3A${netease.id}%7D%5D",
                                neteaseHeaders,
                            )
                            android.util.Log.i("LMScrape", "diag id=${netease.id}")
                            android.util.Log.i("LMScrape", "diag old ${old.summary}")
                            android.util.Log.i("LMScrape", "diag old hasPicUrl=${old.body?.contains("\"picUrl\":\"http")}")
                            android.util.Log.i("LMScrape", "diag v3 ${v3.summary}")
                            android.util.Log.i("LMScrape", "diag v3 hasPicUrl=${v3.body?.contains("\"picUrl\":\"http")}")
                        }
                        android.util.Log.i(
                            "LMScrape",
                            "cover miss '${song.title}' | netease=" + (netease?.let { "id=${it.id} name='${it.name}' artist='${it.artist}'" } ?: "搜不到") +
                                " | albumPic=" + (detailPic ?: "无（或被当占位图拦下）") +
                                " | fallback=" + (if (bytes != null) "有图但落盘失败" else "iTunes/Deezer 也没有"),
                        )
                    }
                    delay(800)
                }
                if (needLyrics) {
                    val fromNetease = netease?.let { hit -> runCatching { neteaseLyrics(hit.id) }.getOrNull() }
                    val hit = fromNetease ?: runCatching { findLyrics(song) }.getOrNull()
                    if (hit != null && LyricCache.write(context, song.uri, hit)) {
                        lyrics++
                        if (fromNetease != null) neteaseLyrics++
                    } else {
                        failures += "${song.title}：没找到歌词"
                    }
                    delay(800)
                }
            }
            if (covers % 6 != 0) CoverStore.refresh()
            val restoredNote = if (restored > 0) "，已恢复 $restored 首自带封面" else ""
            val sourceNote = if (neteaseCovers + neteaseLyrics > 0) "，其中网易云 封面$neteaseCovers/歌词$neteaseLyrics" else ""
            _status.value = Status(false, "刮削完成：封面 $covers · 歌词 $lyrics$sourceNote（共试 $tried 首$restoredNote）", covers, lyrics, tried, failures.takeLast(5))
        } catch (e: CancellationException) {
            CoverStore.refresh()
            _status.value = Status(false, "已停止：封面 $covers · 歌词 $lyrics", covers, lyrics, tried, failures.takeLast(3))
            throw e
        }
    }

    // ── 网易云音乐（非官方接口，不需要 key；随时可能被限流或改动）──

    private val neteaseHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Accept" to "*/*",
    )

    internal data class NeteaseSong(val id: Long, val name: String, val artist: String, val cover: String?)

    /**
     * **合批取专辑图**：v3 接口一次能传多个 id，8 首只发 1 个请求。
     *
     * 为什么要合批：网易云对同一 IP 的请求密度很敏感，逐首「搜索 + 详情 + 歌词」三连发会被
     * 直接拒（`操作频繁，请稍候再试`；实测一批 35 首失败里 34 首卡在这一步）。
     * 合批把详情请求量降到 1/8，再配合请求间隔，才躲得开限流。
     */
    internal fun neteaseCoversByIds(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        var diagLogged = false
        val out = mutableMapOf<Long, String>()
        for (chunk in ids.distinct().chunked(8)) {
            val json = chunk.joinToString(",", "[", "]") { "{\"id\":$it}" }
            val payload = java.net.URLEncoder.encode(json, "UTF-8")
            val url = "https://music.163.com/api/v3/song/detail?c=$payload"
            var ok = false
            var throttled = false
            repeat(2) { attempt ->
                if (ok) return@repeat
                val body = runCatching { get(url, neteaseHeaders) }.getOrNull()
                if (body != null && !body.contains("操作频繁")) {
                    if (!diagLogged) { diagLogged = true; android.util.Log.i("LMScrape", "v3 head=${body.take(600).replace('\n', ' ')}") }
                    ok = true
                    runCatching {
                        val songs = JSONObject(body).optJSONArray("songs") ?: return@runCatching
                        for (i in 0 until songs.length()) {
                            val song = songs.optJSONObject(i) ?: continue
                            val id = song.optLong("id")
                            // ⚠️ v3 接口的字段名是简写：`al` = album、`ar` = artists、`dt` = duration。
                            // 旧接口用的是 `album`——换了接口却沿用旧字段名，就会永远解析到 null
                            //（这就是"请求成功、got=0"的原因）。这里两个都兼容。
                            val album = song.optJSONObject("al") ?: song.optJSONObject("album")
                            val pic = usableCover(album?.optString("picUrl"))
                            if (id > 0 && pic != null) out[id] = pic
                        }
                    }
                } else if (attempt == 0) {
                    throttled = true
                    Thread.sleep(1500)   // 被限流：退避后重试一次
                }
            }
            android.util.Log.i("LMScrape", "batchDetail ids=${chunk.size} ok=$ok throttled=$throttled got=${chunk.count { out.containsKey(it) }}")
            Thread.sleep(400)   // 块与块之间也留缝
        }
        return out
    }

    /** 单首取图：走合批接口，便于自检/兜底调用。 */
    internal fun neteaseCoverById(id: Long): String? = neteaseCoversByIds(listOf(id))[id]

    internal fun neteaseSearch(title: String, artist: String): NeteaseSong? {
        // 本机标题常是「歌手 - 歌名」这种形式，拆开能显著减少"翻唱/同名"误配
        val split = title.split(" - ", limit = 2)
        val titleHint = if (split.size == 2) split[1].trim() else title.trim()
        val artistHint = (if (split.size == 2) split[0].trim() else artist)
            .takeIf { it.isNotBlank() && !it.startsWith("未知") }.orEmpty()
        val keyword = listOf(artistHint, titleHint).filter { it.isNotBlank() }.joinToString(" ").ifBlank { title }
        val url = "https://music.163.com/api/search/get?s=${encode(keyword)}&type=1&offset=0&limit=10"
        val body = get(url, neteaseHeaders) ?: return null
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
        var loose: NeteaseSong? = null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val name = song.optString("name")
            if (!titleMatches(titleHint, name)) continue
            val artists = song.optJSONArray("artists")?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                    .joinToString("/")
            }.orEmpty()
            val candidate = NeteaseSong(song.optLong("id"), name, artists, null)
            // 歌手也对得上 → 直接用；只有歌名对上 → 先记着，没有更好的才用它
            if (artistHint.isNotBlank() && artists.contains(artistHint, ignoreCase = true)) return candidate
            if (loose == null) loose = candidate
        }
        return loose
    }

    /** 网易云的图片 URL 支持 `?param=600y600` 指定尺寸。 */
    internal fun neteaseCover(song: NeteaseSong): ByteArray? {
        // 搜索结果里的 cover 通常为空（响应里没有 picUrl），回落问 detail 接口
        val url = song.cover ?: neteaseCoverById(song.id) ?: return null
        val sized = if (url.contains("?")) "$url&param=600y600" else "$url?param=600y600"
        return fetchImage(sized) ?: fetchImage(url)
    }

    /**
     * 网易云**自检**：把网页与接口的真实响应写进 logcat（标签 `LMNetease`）。
     *
     * 为什么要这个：`adb shell` 里 DNS 解析不了、TCP 也出不去（实测 ping 通但 curl 全 000），
     * 所以"网易云能不能用"只能在**应用自己的网络出口**上验证。跑一次就能看清：
     * 移动版搜索页是否服务端渲染、能不能抽出 `/song?id=`、歌曲页里有没有 `og:image` 与歌词。
     */
    suspend fun neteaseProbe(sample: String = "晴天 周杰伦") = withContext(Dispatchers.IO) {
        val keyword = encode(sample)
        val searchUrl = "https://music.163.com/search/m/?s=$keyword&type=1"
        // 用 probeGet：把"HTTP 状态码 / 异常类型"也打出来，才能分清是"连不上"还是"被拒"。
        val searchResult = probeGet(searchUrl, neteaseHeaders)
        android.util.Log.i("LMNetease", "search(html) $searchResult")
        // 网页搜索会是 302（要 cookie），所以直接以 API 搜索为准；这里把三件事都验一遍
        val apiResult = probeGet("https://music.163.com/api/search/get?s=$keyword&type=1&limit=2", neteaseHeaders)
        android.util.Log.i("LMNetease", "apiSearch ${apiResult.summary}")
        val search = apiResult.body ?: return@withContext
        // 用真实搜索结果走一遍完整链路（含默认占位图过滤），另附一首固定歌曲做对照
        android.util.Log.i("LMNetease", "probe title=卢广仲 - 我爱你 match=${neteaseSearch("卢广仲 - 我爱你", "未知艺术家")}")
        android.util.Log.i("LMNetease", "probe albumCover(186016)=${neteaseCoverById(186016)}")
        android.util.Log.i("LMNetease", "lyricApi " + probeGet("https://music.163.com/api/song/lyric?id=186016&lv=-1&kv=-1&tv=-1", neteaseHeaders))
    }

    private data class Probe(val summary: String, val body: String?)

    /** 自检专用：返回状态码/异常摘要 + 响应体（正常时）。 */
    private fun probeGet(url: String, headers: Map<String, String>? = null): Probe {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                headers?.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
            Probe(
                summary = "code=$code bytes=${text?.length ?: 0} url=$url head=${text?.take(90)?.replace('\n', ' ')}",
                body = if (code in 200..299) text else null,
            )
        } catch (e: Exception) {
            Probe("FAIL ${e.javaClass.simpleName}: ${e.message} url=$url", null)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 网易云的**默认占位图**（歌手无头像时那张"举麦克风剪影"）。抓到它就等于没抓到封面，
     * 必须当成 null 让它回落到 iTunes/Deezer，否则曲库里会全是这张剪影。
     */
    private val neteasePlaceholders = listOf("6y-UleORITEDbvrOLV0Q8A==", "5639395138885805", "109951163277456216")

    private fun usableCover(url: String?): String? =
        url?.takeIf { it.isNotBlank() && neteasePlaceholders.none { p -> it.contains(p) } }

    /**
     * 取**专辑封面**：必须显式取 `songs[0].album.picUrl`。
     *
     * 之前用正则抓"第一个 picUrl"，结果抓到了 **artist 的 picUrl**（默认剪影）——用户看到的
     * "歌手举麦克风"就是这么来的。现在统一走上面的合批实现（见 neteaseCoversByIds）。
     */

    /** 取带时间戳的歌词（`lrc.lyric`）。 */
    internal fun neteaseLyrics(id: Long): String? = try {
        val body = get("https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1", neteaseHeaders) ?: return null
        val lrc = JSONObject(body).optJSONObject("lrc")?.optString("lyric")
        pick(lrc)
    } catch (_: Exception) { null }

    // ── 封面（iTunes → Deezer 兜底；网易云在 scrape 里优先试）──

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

    private fun get(url: String, headers: Map<String, String>? = null): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                headers?.forEach { (key, value) -> setRequestProperty(key, value) }
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

/**
 * 判断一首歌**本来就有封面**（文件内嵌图 / 系统专辑封面）。
 *
 * 有就不许刮削去覆盖它 —— 用户明确要求"不要改歌曲原本就有的封面"。
 * 判定很便宜：MediaStore 只查专辑封面 URI 存不存在，不解码图片；本地文件才读内嵌图。
 * 结果记在内存里（一次扫描周期内不重复探测，也不落盘）。
 */
object ArtworkProbe {
    private val withArt = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val withoutArt = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun hasOwnArtwork(context: Context, song: Song): Boolean {
        if (withArt.contains(song.uri)) return true
        if (withoutArt.contains(song.uri)) return false
        val result = runCatching { probe(context, song) }.getOrDefault(false)
        (if (result) withArt else withoutArt).add(song.uri)
        return result
    }

    private fun probe(context: Context, song: Song): Boolean {
        val uri = android.net.Uri.parse(song.uri)
        // ① 本地文件：直接按路径读**内嵌封面**——这正是"歌曲本来就带的封面"
        val path = runCatching {
            when {
                uri.scheme == "file" -> uri.path
                uri.authority == "media" -> context.contentResolver
                    .query(uri, arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                else -> null
            }
        }.getOrNull()
        if (path != null && java.io.File(path).canRead()) {
            return runCatching {
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(path)
                    retriever.embeddedPicture != null
                }
            }.getOrDefault(false)
        }
        // ② SAF 文档：只能按 uri 读
        return runCatching {
            android.media.MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture != null
            }
        }.getOrDefault(false)
    }
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
