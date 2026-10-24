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
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
            // 新一轮刮削：清掉上一轮"这首歌没有自带封面"的**否定**缓存。
            // 旧实现把否定结果和肯定结果一样永久记住 —— 一次瞬时读图失败（provider 忙、
            // 文件刚拷进来还没索引好）就让这首歌从此再也抓不到封面，只能杀进程重来。
            ArtworkProbe.beginRound()
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
                            val old = probeGet("https://music.163.com/api/song/detail?ids=%5B${netease.id}%5D", neteaseHeaders())
                            val v3 = probeGet(
                                "https://music.163.com/api/v3/song/detail?c=%5B%7B%22id%22%3A${netease.id}%7D%5D",
                                neteaseHeaders(),
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

    @Volatile private var authCookie: String = ""

    /** Optional user supplied MUSIC_U / NMTID cookie for account-only endpoints. */
    fun setNeteaseCookie(cookie: String?) { authCookie = cookie.orEmpty().trim() }

    /**
     * 默认伪装 cookie（只在用户没填自己的 cookie 时用）。
     *
     * 原来**只有**用户手填 cookie 才会带 `Cookie` 头 —— 匿名请求会被网易云当脚本：
     * 搜索返回 200 但 `result.songs` 为空、歌词返回 200 但 `lrc.lyric` 为空，
     * 于是整批歌"搜到了却没有封面/歌词"。上游 CoverFetcher.kt:44-48 与
     * NeteaseLyricsFetcher.kt:100-106 是全程都带一个伪造的
     * `os=pc; appver=3.0.1; deviceId=…`，这里照做。
     * deviceId 在进程内固定：每次请求都换一个新身份反而更像批量脚本（上游注释 88-91）。
     */
    private val fakeDeviceId: String by lazy {
        java.util.UUID.randomUUID().toString().replace("-", "").uppercase()
    }
    private val defaultCookie: String by lazy {
        "os=pc; osver=Microsoft-Windows-10-Professional-build-19045-64bit; appver=3.0.1; " +
            "deviceId=$fakeDeviceId; _ntes_nuid=$fakeDeviceId"
    }
    private fun cookieHeader(): String = authCookie.ifBlank { defaultCookie }

    private fun neteaseHeaders(): Map<String, String> = buildMap {
        put("Referer", "https://music.163.com/")
        put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        put("Accept", "*/*")
        put("Cookie", cookieHeader())
    }

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
                val body = runCatching { get(url, neteaseHeaders()) }.getOrNull()
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

    /**
     * 拆「歌手 - 歌名」这类本地命名。
     *
     * 以前只认 `" - "`（空格+半角横线+空格），于是这些命名全部落空：
     * `卢广仲_我爱你`、`歌手 – 歌名`（en dash）、`歌手 — 歌名`（em dash）。实测库里不少歌是
     * 下划线连接的（尤其整轨合辑），拆不开就把整串当歌名去搜，必然搜不到。
     */
    private val titleSeparators = listOf(" - ", " – ", " — ", "－", " _ ", "_")

    private fun splitArtistTitle(raw: String, artist: String): Pair<String, String> {
        for (sep in titleSeparators) {
            val i = raw.indexOf(sep)
            if (i > 0) return raw.substring(0, i).trim() to raw.substring(i + sep.length).trim()
        }
        return "" to raw.trim()
    }

    /**
     * 洗掉本地文件名里的噪音：`(Live)` `[Official MV]` `feat. XXX` 等。
     *
     * 这些后缀会让"歌名"和网易云的正式歌名对不上（`titleSimilar` 是包含式比较，
     * 但整串带着 `(Live)` 时，搜索排名会先落到一堆现场版/翻唱上）。
     */
    private fun cleanTitle(raw: String): String = raw
        .substringBeforeLast('.')
        .replace(Regex("(?i)\\((?:live|remaster(?:ed)?|mv|official[^)]*|inst\\.?|instrumental|伴奏|纯音乐|翻自[^)]*)\\)"), " ")
        .replace(Regex("\\[[^\\]]*]"), " ")
        .replace(Regex("(?i)\\b(?:feat|ft|featuring)\\.?\\s+.*$"), " ")
        .replace(Regex("[_\\s]+"), " ")
        .trim()

    /**
     * 搜索关键词候选，**按优先级逐个试**：
     *   ① `歌手 歌名`（最准）
     *   ② `歌名`
     *   ③ 清洗过噪音的歌名
     *
     * 为什么要一串：库里元数据质量参差（歌手常是「未知艺术家」、歌名里混着歌手名与
     * `(Live)`、合辑甚至把十几个歌手用下划线串成一串）。一条搜不到就换下一条，
     * 比"一条搜不到就放弃"能救回一大批。
     */
    private fun searchKeywords(title: String, artist: String): List<String> {
        val (artistFromTitle, titlePart) = splitArtistTitle(title, artist)
        val artistHint = artistFromTitle.ifBlank {
            artist.takeIf { it.isNotBlank() && !it.startsWith("未知") }.orEmpty()
        }.trim()
        // 合辑那种"十几个歌手用下划线串起来"的：只取第一个歌手名做提示
        val firstArtist = artistHint.split(Regex("[_/、,，&]+")).firstOrNull()?.trim().orEmpty()
        val clean = cleanTitle(titlePart)
        return buildList {
            if (firstArtist.isNotBlank() && clean.isNotBlank()) add("$firstArtist $clean")
            if (clean.isNotBlank()) add(clean)
            // 兜底：歌名太长时（合辑/古典长标题）截一段，避免搜索词本身把结果带偏
            clean.take(24).takeIf { it.length in 2..23 }?.let { add(it) }
            titlePart.trim().takeIf { it.isNotBlank() && it != clean }?.let { add(it) }
            add(title.trim())
        }.map { it.take(60) }.filter { it.isNotBlank() }.distinct()
    }

    internal fun neteaseSearch(title: String, artist: String): NeteaseSong? {
        for (keyword in searchKeywords(title, artist)) {
            currentKeyword = keyword
            searchOnce(keyword)?.let { return it }
        }
        return null
    }

    /** 上次实际用的搜索词（只用于日志诊断）。 */
    private var currentKeyword: String = ""

    /** 搜一次；失败/无结果会带诊断写 logcat（标签 LMScrape），便于在真机上定位。 */
    private fun searchOnce(keyword: String): NeteaseSong? {
        val url = "https://music.163.com/api/search/get?s=${encode(keyword)}&type=1&offset=0&limit=10"
        val body = get(url, neteaseHeaders())
        if (body == null) {
            android.util.Log.i("LMScrape", "search '$keyword' -> HTTP 失败或非 2xx")
            return null
        }
        if (searchRawDiag.compareAndSet(true, false)) {
            android.util.Log.i("LMScrape", "searchRaw '$keyword' head=${body.take(300).replace('\n', ' ')}")
        }
        val songs = runCatching { JSONObject(body).optJSONObject("result")?.optJSONArray("songs") }.getOrNull()
        if (songs == null || songs.length() == 0) {
            android.util.Log.i("LMScrape", "search '$keyword' -> 无结果（前80字 ${body.take(80).replace('\n', ' ')}）")
            return null
        }
        val titleHint = cleanTitle(splitArtistTitle(keyword, "").second).ifBlank { keyword }
        var loose: NeteaseSong? = null
        val top = mutableListOf<String>()
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val name = song.optString("name")
            if (top.size < 3) top += name
            if (!titleSimilar(titleHint, name)) continue
            val artists = song.optJSONArray("artists")?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                    .joinToString("/")
            }.orEmpty()
            val candidate = NeteaseSong(song.optLong("id"), name, artists, null)
            if (artists.isNotBlank()) return candidate
            if (loose == null) loose = candidate
        }
        if (loose == null) {
            android.util.Log.i("LMScrape", "search '$keyword' -> 有 ${songs.length()} 条但都对不上（前3：${top.joinToString(" / ")}）")
        }
        return loose
    }

    /** 只打一次原始响应，用来确认"到底拿到了什么"。 */
    private val searchRawDiag = java.util.concurrent.atomic.AtomicBoolean(true)


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
        val searchResult = probeGet(searchUrl, neteaseHeaders())
        android.util.Log.i("LMNetease", "search(html) $searchResult")
        // 网页搜索会是 302（要 cookie），所以直接以 API 搜索为准；这里把三件事都验一遍
        val apiResult = probeGet("https://music.163.com/api/search/get?s=$keyword&type=1&limit=2", neteaseHeaders())
        android.util.Log.i("LMNetease", "apiSearch ${apiResult.summary}")
        val search = apiResult.body ?: return@withContext
        // 用真实搜索结果走一遍完整链路（含默认占位图过滤），另附一首固定歌曲做对照
        android.util.Log.i("LMNetease", "probe title=卢广仲 - 我爱你 match=${neteaseSearch("卢广仲 - 我爱你", "未知艺术家")}")
        android.util.Log.i("LMNetease", "probe albumCover(186016)=${neteaseCoverById(186016)}")
        android.util.Log.i("LMNetease", "lyricApi " + probeGet("https://music.163.com/api/song/lyric?id=186016&lv=-1&kv=-1&tv=-1", neteaseHeaders()))
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

    /**
     * 网易云返回的 `picUrl` 经常是 `http://p1.music.126.net/...`。
     * targetSdk≥28 的应用默认**禁止明文 HTTP**（本应用没有 `usesCleartextTraffic`），
     * 于是 `fetchImage("http://…")` 直接抛异常 → 封面永远抓不到，
     * 而代码还以为是"没匹配上"。统一升级成 https（上游 CoverFetcher.kt:107、139 同款处理）。
     */
    private fun httpsUrl(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    private fun usableCover(url: String?): String? =
        url?.takeIf { it.isNotBlank() && neteasePlaceholders.none { p -> it.contains(p) } }
            ?.let(::httpsUrl)

    /**
     * 取**专辑封面**：必须显式取 `songs[0].album.picUrl`。
     *
     * 之前用正则抓"第一个 picUrl"，结果抓到了 **artist 的 picUrl**（默认剪影）——用户看到的
     * "歌手举麦克风"就是这么来的。现在统一走上面的合批实现（见 neteaseCoversByIds）。
     */

    /**
     * 取歌词：**weapi 加密 POST 优先**，旧的明文 GET 只当兜底。
     *
     * 旧实现直接 GET `/api/song/lyric` —— 这个半废弃接口在没有登录态时经常
     * "HTTP 200 但 `lrc.lyric` 为空"（上游 NeteaseLyricsFetcher.kt:24-41 记录了同样的结论），
     * 于是库里一大批歌永远拿不到网易云歌词、只能靠 LRCLIB 碰运气。
     * 现在按上游做法走网页版真正在用的 `/weapi/song/lyric`（AES 两层 + RSA 加密随机密钥），
     * 并且把请求里已经要了的 `tlyric`（翻译）取回来按时间戳合并，不再白扔。
     */
    internal fun neteaseLyrics(id: Long): String? {
        weapiLyrics(id)?.let { return it }
        return legacyNeteaseLyrics(id)
    }

    /** weapi 版：失败（网络/风控/空 lrc）返回 null，由调用方回落旧接口。 */
    private fun weapiLyrics(id: Long): String? = try {
        val params = JSONObject().apply {
            put("id", id)
            put("lv", -1)
            put("kv", -1)
            put("tv", -1)
            put("csrf_token", "")
        }
        val body = weapiPost("/song/lyric", params) ?: return null
        val json = JSONObject(body)
        val main = json.optJSONObject("lrc")?.optString("lyric")
        val trans = json.optJSONObject("tlyric")?.optString("lyric")
        if (main.isNullOrEmpty()) {
            android.util.Log.i("LMScrape", "weapi lyric 仍为空 id=$id（风控或该曲无词）")
            null
        } else {
            pick(mergeTranslation(main, trans))
        }
    } catch (_: Exception) { null }

    /** 旧的明文 GET：weapi 拿不到时再试一次（少数曲目反而只有它返回）。 */
    private fun legacyNeteaseLyrics(id: Long): String? = try {
        val body = get("https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1", neteaseHeaders()) ?: return null
        val json = JSONObject(body)
        val main = json.optJSONObject("lrc")?.optString("lyric")
        val trans = json.optJSONObject("tlyric")?.optString("lyric")
        if (main.isNullOrEmpty()) null else pick(mergeTranslation(main, trans))
    } catch (_: Exception) { null }

    /**
     * 把翻译按时间戳并进主歌词（参考上游 QQMusicLyricsFetcher.mergeLyrics）。
     * 同一时间戳输出两行：第一行原文、第二行译文 —— 本地解析本来就支持同时间戳多行。
     */
    private fun mergeTranslation(main: String, trans: String?): String {
        if (trans.isNullOrBlank()) return main
        val tag = Regex(LyricsRepository.LRC_TIME)
        val byTime = HashMap<Long, String>()
        trans.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val matches = tag.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { byTime[LyricsRepository.tagMs(it)] = text }
        }
        if (byTime.isEmpty()) return main
        val out = StringBuilder()
        main.lineSequence().forEach { raw ->
            out.append(raw).append('\n')
            val matches = tag.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach
            val text = raw.substring(matches.last().range.last + 1).trim()
            val translated = byTime[LyricsRepository.tagMs(matches.last())]
            if (translated != null && translated != text) {
                out.append(raw.substring(0, matches.last().range.last + 1)).append(translated).append('\n')
            }
        }
        return out.toString().trimEnd()
    }

    // ── weapi 加密（网易云网页版自己用的请求签名，上游 NeteaseLyricsFetcher.kt:42-85 同款）──
    private const val WEAPI_AES_KEY = "0CoJUm6Qyw8W8jud"
    private const val WEAPI_AES_IV = "0102030405060708"
    private const val WEAPI_RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val WEAPI_RSA_PUBLIC_EXP = "010001"
    private const val WEAPI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val rng = SecureRandom()

    private fun aesEncryptBase64(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(WEAPI_AES_IV.toByteArray(Charsets.UTF_8)),
        )
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    /** 网易云这里不是标准 RSA 填充：把密钥整个反转后当大数做 modPow，再补齐到 256 个十六进制字符。 */
    private fun rsaEncryptSecretKey(secretKey: String): String {
        val text = BigInteger(1, secretKey.reversed().toByteArray(Charsets.UTF_8))
        val result = text.modPow(BigInteger(WEAPI_RSA_PUBLIC_EXP, 16), BigInteger(WEAPI_RSA_MODULUS, 16))
        val hex = result.toString(16)
        return if (hex.length < 256) "0".repeat(256 - hex.length) + hex else hex
    }

    private fun randomSecretKey(): String {
        val chars = "abcdef0123456789"
        return buildString { repeat(16) { append(chars[rng.nextInt(chars.length)]) } }
    }

    /** POST `https://music.163.com/weapi<path>?csrf_token=`，返回响应体；失败返回 null。 */
    private fun weapiPost(path: String, params: JSONObject): String? {
        var connection: HttpURLConnection? = null
        return try {
            val secret = randomSecretKey()
            val encParams = aesEncryptBase64(aesEncryptBase64(params.toString(), WEAPI_AES_KEY), secret)
            val encSecKey = rsaEncryptSecretKey(secret)
            val form = "params=" + URLEncoder.encode(encParams, "UTF-8") +
                "&encSecKey=" + URLEncoder.encode(encSecKey, "UTF-8")
            connection = (URL("https://music.163.com/weapi$path?csrf_token=").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                doOutput = true
                setRequestProperty("User-Agent", WEAPI_UA)
                setRequestProperty("Referer", "https://music.163.com/")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Cookie", cookieHeader())
            }
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                android.util.Log.i("LMScrape", "weapi $path HTTP $code")
                null
            } else connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            android.util.Log.i("LMScrape", "weapi $path 失败：${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    // ── 封面（iTunes → Deezer 兜底；网易云在 scrape 里优先试）──

    private fun findCover(title: String, artist: String): ByteArray? {
        if (title.isBlank()) return null
        val term = if (artist.isBlank() || artist.startsWith("未知")) title else "$artist $title"
        itunesCover(term, title, artist)?.let { return it }
        val deezer = deezerCover(term, title, artist)
        // 诊断：网易云给"不相干结果"时，要能分清兜底源是"连不上"还是"没匹配上"
        android.util.Log.i("LMScrape", "fallback '$term' -> " + (if (deezer != null) "Deezer 命中" else "iTunes/Deezer 都未命中"))
        return deezer
    }

    private fun itunesCover(term: String, title: String, artist: String): ByteArray? = try {
        val body = get("https://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + encode(term)) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        var found: ByteArray? = null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            if (!isMatch(title, artist, item.optString("trackName"), item.optString("artistName"))) continue
            val art = item.optString("artworkUrl100")
            if (art.isBlank()) continue
            // iTunes 的 URL 里尺寸是写死的，换掉就拿到大图
            found = fetchImage(art.replace("100x100", "600x600")) ?: fetchImage(art)
            if (found != null) break
        }
        found
    } catch (_: Exception) { null }

    private fun deezerCover(term: String, title: String, artist: String): ByteArray? = try {
        val body = get("https://api.deezer.com/search?limit=8&q=" + encode(term)) ?: return null
        val data = JSONObject(body).optJSONArray("data") ?: return null
        var found: ByteArray? = null
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val resultArtist = item.optJSONObject("artist")?.optString("name").orEmpty()
            if (!isMatch(title, artist, item.optString("title"), resultArtist)) continue
            val album = item.optJSONObject("album") ?: continue
            val cover = album.optString("cover_xl").ifBlank { album.optString("cover_big") }
            if (cover.isBlank()) continue
            found = fetchImage(httpsUrl(cover))
            if (found != null) break
        }
        found
    } catch (_: Exception) { null }

    /** 归一化：忽略大小写、标点、空格（保留中日韩字母与数字）。 */
    private fun normText(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * 搜索阶段的**歌名**相似判断（网易云搜索用；歌手在后面单独判断）。
     *
     * 保留原来"包含式"比较（搜索结果常带 `(Live)`/`feat.` 后缀），但加了一条：
     * 较短一方至少 2 个字符 —— 否则单字歌名（"雨"）会把所有结果都当成命中。
     * 封面来源（iTunes/Deezer）不再用这个宽松规则，见 [isMatch]。
     */
    private fun titleSimilar(wanted: String, candidate: String): Boolean {
        val a = normText(wanted)
        val b = normText(candidate)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || (minOf(a.length, b.length) >= 2 && (b.contains(a) || a.contains(b)))
    }

    /**
     * 歌名 + 歌手**双重**匹配（移植上游 CoverFetcher.isMatch）。
     *
     * 原来只比歌名、而且是双向 `contains`：一首叫「雨」的歌会把 8 条结果里的第一条当封面，
     * 所以用户会看到"封面是别人的"。现在：
     *  · 歌手已知时**必须**有交集，否则丢弃；
     *  · 歌名允许互相包含，但较短的一方至少 2 个字符（防止单字歌名通配一切）。
     */
    private fun isMatch(queryTitle: String, queryArtist: String, resultTitle: String, resultArtist: String): Boolean {
        val qt = normText(queryTitle)
        val rt = normText(resultTitle)
        if (qt.isBlank() || rt.isBlank()) return false
        val titleOk = qt == rt || (minOf(qt.length, rt.length) >= 2 && (rt.contains(qt) || qt.contains(rt)))
        if (!titleOk) return false
        val qa = queryArtist.trim()
        // 本地没有歌手信息（"未知艺术家"/空）→ 没有可比对的东西，只能按歌名放行
        if (qa.isBlank() || qa.startsWith("未知")) return true
        val ra = resultArtist.trim()
        if (ra.isBlank()) return false
        val qn = normText(qa)
        val rn = normText(ra)
        if (qn.isBlank() || rn.isBlank()) return false
        if (qn == rn || rn.contains(qn) || qn.contains(rn)) return true
        // 合唱/多歌手（`A/B/C`、`A & B`）：任一对上就算
        val qParts = qa.split(Regex("[/&,、;，]")).map(::normText).filter { it.length >= 2 }
        val rParts = ra.split(Regex("[/&,、;，]")).map(::normText).filter { it.length >= 2 }
        return qParts.any { q -> rn.contains(q) || rParts.any { r -> r.contains(q) || q.contains(r) } } ||
            rParts.any { qn.contains(it) }
    }

    // ── 歌词 ──

    /**
     * 单曲联网取词 —— 播放页的**一次性兜底**入口（见 `LyricsRepository.load` 第 3 步）。
     *
     * 只发一条搜索关键词 + 一次歌词请求，再退到 LRCLIB：打开一首没歌词的歌不应该把
     * `searchKeywords` 那四五条关键词全部发一遍（那是整批刮削才做的事，否则播放页要等几十秒）。
     * 每个请求都受 8 秒 socket 超时约束，所以最坏等待是有界的。
     */
    internal suspend fun fetchLyricsFor(song: Song): String? = withContext(Dispatchers.IO) {
        val fromNetease = runCatching {
            val keyword = searchKeywords(song.title, song.artist).firstOrNull() ?: return@runCatching null
            currentKeyword = keyword
            searchOnce(keyword)?.let { neteaseLyrics(it.id) }
        }.getOrNull()
        fromNetease ?: runCatching { findLyrics(song) }.getOrNull()
    }

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

    /**
     * 歌词最少字符数。写入端（这里）与读取端（[LyricCache]）必须用同一个门槛。
     *
     * 原来写入接受 `length in 8..200000`（含 8），而 [LyricCache.read] 要求 `length() > 8`
     * —— 正好 8 个字符的歌词"写成功但永远读不到"，于是每次刮削都重复下载同一首。
     */
    private const val MIN_LYRIC_CHARS = 8
    private const val MAX_LYRIC_CHARS = 200_000

    private fun pick(text: String?): String? =
        text?.trim()?.takeIf { it.isNotBlank() && it.length in MIN_LYRIC_CHARS..MAX_LYRIC_CHARS }

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
        // 兜底再升级一次 scheme：只要调用方漏了，明文 HTTP 在 targetSdk≥28 上必失败
        val target = httpsUrl(url)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(target).openConnection() as HttpURLConnection).apply {
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
 *
 * ⚠️ 缓存策略区分肯定/否定：**肯定**结果（真的有内嵌图）可以长期记住；
 * **否定**结果只在当前这一轮刮削内有效（[beginRound] 清空）。
 * 原来两者一样永久记住 —— 一次瞬时失败（provider 忙、文件刚拷进来还没索引好）
 * 就把这首歌永久排除在刮削之外，只能杀进程重来。
 */
object ArtworkProbe {
    private val withArt = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val withoutArt = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 每一轮刮削开始前调用：丢掉上一轮的否定缓存，让瞬时失败过的歌重新有机会被探测。 */
    fun beginRound() { withoutArt.clear() }

    fun hasOwnArtwork(context: Context, song: Song): Boolean {
        if (withArt.contains(song.uri)) return true
        if (withoutArt.contains(song.uri)) return false
        val result = runCatching { probe(context, song) }.getOrDefault(false)
        if (result) withArt.add(song.uri) else withoutArt.add(song.uri)
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

/**
 * 刮削（或用户手动放的）歌词缓存：`filesDir/lyrics/<sha1(uri)>.lrc`。
 *
 * 读/写必须用**同一套长度门槛**：原来写入端接受 8 字符、读取端要求 `> 8` 字节，
 * 正好 8 个字符的歌词会被判为"写入成功"却永远读不回来（每次都重复下载）。
 * 现在短于 [MIN_BYTES] 的一律不落盘，且读回时统一走 [LrcText.decode]
 * （老版本/手工放进来的 GBK 缓存也能正常读）。
 */
object LyricCache {
    private const val MIN_BYTES = 8L
    private const val MAX_BYTES = 400_000L

    fun file(context: Context, songUri: String): File =
        File(File(context.filesDir, "lyrics").apply { mkdirs() }, sha1(songUri) + ".lrc")

    fun read(context: Context, songUri: String): String? = try {
        file(context, songUri)
            .takeIf { it.isFile && it.length() in MIN_BYTES..MAX_BYTES }
            ?.readBytes()
            ?.let(LrcText::decode)
    } catch (_: Exception) { null }

    fun write(context: Context, songUri: String, text: String): Boolean = try {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // 与读取端同一门槛：太短的不写（写了也读不回来），太大的不写（多半是网页错误页）
        if (bytes.size.toLong() in MIN_BYTES..MAX_BYTES) {
            file(context, songUri).writeBytes(bytes)
            true
        } else false
    } catch (_: Exception) { false }

    fun clear(context: Context): Int {
        val dir = File(context.filesDir, "lyrics")
        val files = dir.listFiles() ?: return 0
        return files.count { it.delete() }
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
