// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import com.kyant.backdrop.backdrops.layerBackdrop
import com.liquidmiuix.glass.*
import com.liquidmiuix.theme.LiquidMotion
import com.liquidmiuix.theme.LiquidTheme
import com.localmusic.app.ui.*
import com.localmusic.audio.playback.PlaybackDiagnostics
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askForPermissions()
        setContent { LiquidTheme { AppShell() } }
    }
    private fun askForPermissions() {
        val wanted = buildList {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }
}

private enum class Page(val title: String, val icon: ImageVector) {
    Library("曲库", Icons.Rounded.LibraryMusic),
    Favorites("喜欢", Icons.Rounded.Favorite),
    Settings("设置", Icons.Rounded.Settings),
}

/** 滚轮单击切页的顺序（按用户要求：曲库 → 设置 → 喜欢 → 曲库…）。 */
private fun nextPage(current: Page): Page = when (current) {
    Page.Library -> Page.Settings
    Page.Settings -> Page.Favorites
    Page.Favorites -> Page.Library
}

private val BarMargin = 16.dp
private val BarHeight = 64.dp
private val BarPressedScale = 1.04f
private val TopBarHeight = 56.dp
private val TopBarMargin = 8.dp

/**
 * 网易云音乐的下载目录。授权选择器（SAF）会**直接打开这一层**，
 * 省得在 DocumentsUI 的文件夹网格里翻半天；目录不存在时选择器自动回落到根目录。
 *
 * 注意 Download 根目录本身被系统禁止授权（"无法使用此文件夹"），
 * 所以只能授权它的子目录 —— 而 ncm 正好都在 netease/cloudmusic/Music 这层。
 */
private val NeteaseTreeUri: Uri = Uri.parse(
    "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fnetease%2Fcloudmusic%2FMusic"
)

/**
 * 选导出位置时，把选择器直接开到 Download 这一层：用户在这里点「新建文件夹」建一个
 * （Download 根目录本身不允许授权，必须进到子目录里）。
 */
private val ExportInitialUri: Uri = Uri.parse(
    "content://com.android.externalstorage.documents/document/primary%3ADownload"
)

/**
 * 采集层 / 玻璃层结构**必须照抄** liquid-miuix 的 AppShell —— 这是库的绘制顺序决定的：
 *
 * 1. 玻璃元素必须在采集层（`layerBackdrop`）**之外**。backdrop 默认 `onDraw = drawContent()`，
 *    挂在包含玻璃的祖先上会自引用 → 渲染树无限递归 → RenderThread 栈溢出闪退。
 * 2. 玻璃修饰符只能挂在**不含子内容**的 Box 上，内容层做它的**兄弟**，
 *    否则超出栏体的子元素会被裁掉。
 */
@Composable
private fun AppShell() {
    val context = LocalContext.current
    val app = context.applicationContext as LocalMusicApplication
    val library = app.library
    val player = app.player
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }

    val songs by library.songs.collectAsState()
    val status by library.status.collectAsState()
    val playback by player.state.collectAsState()
    val diagnostics by PlaybackDiagnostics.status.collectAsState()
    val eqState by com.localmusic.audio.playback.EqualizerController.state.collectAsState()
    // 我喜欢的音乐：DB 是一份，内存里缓存一份，改了之后 revision +1 → 这里重算
    val favoritesRevision by com.localmusic.app.data.FavoritesStore.revision.collectAsState()
    val favorites = remember(favoritesRevision, context) { com.localmusic.app.data.FavoritesStore.all(context) }
    val scrapeStatus by com.localmusic.app.data.Scraper.status.collectAsState()
    val scrapeScope = rememberCoroutineScope()
    var autoScrape by remember { mutableStateOf(prefs.getBoolean("autoScrape", true)) }
    var scrapeCover by remember { mutableStateOf(prefs.getBoolean("scrapeCover", true)) }
    var scrapeLyrics by remember { mutableStateOf(prefs.getBoolean("scrapeLyrics", true)) }
    // 滚轮圆环的玻璃颜色：预设下标（-1 = 自定义）+ 自定义的色相/透光率
    var glassTintIndex by remember { mutableStateOf(prefs.getInt("wheelGlassTint", 0)) }
    var glassHue by remember { mutableStateOf(prefs.getFloat("wheelGlassHue", 215f)) }
    var glassLevel by remember { mutableStateOf(prefs.getFloat("wheelGlassLevel", 0.55f)) }
    val glassColor = remember(glassTintIndex, glassHue, glassLevel) {
        if (glassTintIndex in com.localmusic.app.ui.WheelGlassTints.indices) {
            com.localmusic.app.ui.WheelGlassTints[glassTintIndex].color
        } else {
            com.localmusic.app.ui.WheelTint("自定义", glassHue, glassLevel).color
        }
    }

    var page by remember { mutableStateOf(Page.Library) }
    // 搜索词提到顶层：输入框在顶栏（额头）里，曲库/喜欢两页共用它
    var searchQuery by remember { mutableStateOf("") }
    // 滚轮选中的下标（换页时归零）
    var wheelIndex by remember(page) { mutableStateOf(0) }
    // 播放页不再是 dock 里的一栏（用户觉得多余）：点歌 / 点迷你播放条才进播放页
    var playerOpen by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var bitPerfect by remember { mutableStateOf(prefs.getBoolean("bitPerfect", false)) }
    var autoNcm by remember { mutableStateOf(prefs.getBoolean("autoNcm", true)) }
    var trees by remember(status) { mutableStateOf(library.treeUris().toList()) }
    var exportLabel by remember { mutableStateOf(prefs.getString("exportLabel", null)) }
    // 首次打开（或还没选过导出位置）时，用玻璃弹窗让用户建/选一个文件夹。
    // 用状态初值而不是 LaunchedEffect：判定必须在**首次组合**就成立，晚一帧弹窗就会
    // 闪一下才出现（实测偶尔整帧不出现）。
    var showExportPrompt by remember { mutableStateOf(prefs.getString("exportTree", null) == null) }

    val backdrop = rememberGlassBackdrop()
    var barPressed by remember { mutableStateOf(false) }
    val barScale by animateFloatAsState(if (barPressed) BarPressedScale else 1f, LiquidMotion.press(), label = "barScale")

    val nowPlaying = remember(songs, playback.id) { songs.firstOrNull { it.uri == playback.id } }

    // ── 滚轮导航：曲库/喜欢用过滤后的歌曲列表，设置用下面这张动作表 ──
    // 过滤上提到这里，是为了让"滚轮选中的下标"和页面真正显示的列表永远一致。
    fun matches(song: com.localmusic.app.data.Song): Boolean =
        searchQuery.isBlank() ||
            song.title.contains(searchQuery, true) ||
            song.artist.contains(searchQuery, true) ||
            song.album.contains(searchQuery, true)

    val libraryList = remember(songs, searchQuery) { songs.filter { matches(it) } }
    val favoriteList = remember(songs, favorites, searchQuery) {
        songs.filter { favorites.contains(it.uri) && matches(it) }
    }

    // 自动扫描：进入应用扫一次，之后定期复查（MediaStore 变更也会触发 ContentObserver 重扫）。
    LaunchedEffect(Unit) {
        library.scan()
        while (true) { kotlinx.coroutines.delay(60_000); library.scan() }
    }

    // 自动刮削：按批次慢慢补齐（每批 40 首，批间 3 分钟），关掉开关就完全不发请求。
    // 首次启动等 20 秒再开始，避免和扫描/首帧抢网络与 IO。
    LaunchedEffect(autoScrape, scrapeCover, scrapeLyrics) {
        if (!autoScrape) return@LaunchedEffect
        var first = true
        while (true) {
            kotlinx.coroutines.delay(if (first) 20_000 else 180_000)
            first = false
            if (com.localmusic.app.data.Scraper.status.value.running) continue
            val list = library.songs.value
            if (list.isEmpty()) continue
            com.localmusic.app.data.Scraper.scrape(context, list, scrapeCover, scrapeLyrics, limit = 40)
        }
    }

    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            library.addTree(uri)
            trees = library.treeUris().toList()
            page = Page.Library
        }
    }

    // 导出位置：要读**和**写权限，而且写入的授权要持久化（下次启动还要能继续导出）。
    val pickExportFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val label = android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfter(':')
            prefs.edit().putString("exportTree", uri.toString()).putString("exportLabel", label).apply()
            exportLabel = label
            // 导出的 FLAC 也要进曲库 → 把它加进被扫描的目录集合
            library.addTree(uri)
            trees = library.treeUris().toList()
            showExportPrompt = false
        }
    }

    // 用户自选封面：拿到图片后复制进应用目录（原图被删/权限失效也不怕）
    val coverScope = rememberCoroutineScope()
    val coverPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = nowPlaying
        if (uri != null && target != null) {
            coverScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.localmusic.app.data.CoverStore.set(context, target.uri, uri)
            }
        }
    }

    // 返回键：先关播放页，再收均衡器面板
    BackHandler(enabled = showEq) { showEq = false }
    BackHandler(enabled = playerOpen && !showEq) { playerOpen = false }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            // 正文默认色。Material3 的 Text 取的是 LocalContentColor，而它的默认值是**黑色**——
            // 我们大量文字放在 miuix Card 里（不是 material3 Surface，没人给它赋值），
            // 所以深色模式下会是黑字黑底。这里统一给成 miuix 的 onSurface：
            // 浅色主题=黑，深色主题=白，与主题同步。
            CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurface) {
            Scaffold(
                containerColor = Color.Transparent,
            ) { padding ->
                val ld = LocalLayoutDirection.current
                val contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(ld),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(ld),
                    bottom = padding.calculateBottomPadding(),
                )
                // 页面内容的顶部留白 = 状态栏 + 玻璃顶栏高度。顶栏浮在内容之上，
                // 列表滚动时会从它下面穿过去（那正是玻璃能折射到的东西）。
                val pageTopPadding = contentPadding.calculateTopPadding() + TopBarHeight + TopBarMargin + 12.dp
                // 滚轮在设置页能做的事，顺序就是转动顺序；标题显示在中间键下方。
                // 放在这里是因为要用到上面定义的 SAF launcher（局部声明必须先于使用）。
                val wheelActions: List<Pair<String, () -> Unit>> = listOf(
                    "USB Bit-perfect" to { bitPerfect = !bitPerfect; prefs.edit().putBoolean("bitPerfect", bitPerfect).apply() },
                    "均衡器" to { showEq = true },
                    "自动转换为 MP3 / FLAC" to { autoNcm = !autoNcm; prefs.edit().putBoolean("autoNcm", autoNcm).apply() },
                    "导出位置" to { pickExportFolder.launch(ExportInitialUri) },
                    "添加音乐 / ncm 文件夹" to { pickFolder.launch(NeteaseTreeUri) },
                    "自动刮削" to { autoScrape = !autoScrape; prefs.edit().putBoolean("autoScrape", autoScrape).apply() },
                    "刮封面" to { scrapeCover = !scrapeCover; prefs.edit().putBoolean("scrapeCover", scrapeCover).apply() },
                    "刮歌词" to { scrapeLyrics = !scrapeLyrics; prefs.edit().putBoolean("scrapeLyrics", scrapeLyrics).apply() },
                    "立即刮削" to {
                        scrapeScope.launch {
                            com.localmusic.app.data.Scraper.scrape(context, songs, scrapeCover, scrapeLyrics, limit = 400)
                        }
                    },
                    "恢复歌曲自带封面" to {
                        scrapeScope.launch {
                            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.localmusic.app.data.CoverStore.restoreOriginals(context, songs)
                            }
                            android.widget.Toast.makeText(context, if (n > 0) "已恢复 $n 首的自带封面" else "没有需要恢复的", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    "滚轮玻璃颜色" to {
                        glassTintIndex = if (glassTintIndex < 0) 0 else (glassTintIndex + 1) % com.localmusic.app.ui.WheelGlassTints.size
                        prefs.edit().putInt("wheelGlassTint", glassTintIndex).apply()
                    },
                    "重新扫描" to { library.scan() },
                )
                val wheelList = when (page) {
                    Page.Library -> libraryList
                    Page.Favorites -> favoriteList
                    Page.Settings -> emptyList()
                }
                val wheelCount = if (page == Page.Settings) wheelActions.size else wheelList.size
                val wheelCaption = if (page == Page.Settings) wheelActions.getOrNull(wheelIndex)?.first
                    else wheelList.getOrNull(wheelIndex)?.title
                Box(Modifier.fillMaxSize()) {
                    // ── 采集层：背景 + 全部页面内容（玻璃唯一能采样到的东西）──
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        AppBackground()
                        AnimatedContent(
                            targetState = page,
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                // 用 gentle 而不是 snappy：滚轮切页时太快会显得"闪一下"（用户反馈切换太快）
                                (fadeIn(LiquidMotion.gentle()) + slideInHorizontally(LiquidMotion.gentle()) { if (forward) it / 6 else -it / 6 })
                                    .togetherWith(fadeOut(LiquidMotion.gentle()) + slideOutHorizontally(LiquidMotion.gentle()) { if (forward) -it / 6 else it / 6 })
                            },
                            label = "page",
                        ) { p ->
                            Box(Modifier.fillMaxSize()) {
                                when (p) {
                                    // 播放页不在这里渲染 —— 带玻璃的界面必须待在采集层之外，
                                    // 否则玻璃会采样"正在录制自己"的层 → 渲染树自引用 → RenderThread 栈溢出闪退。
                                    Page.Library -> LibraryPage(
                                        songs = libraryList, nowPlaying = playback.id,
                                        favorites = favorites,
                                        highlightIndex = if (page == Page.Library) wheelIndex else -1,
                                        favoritesOnly = false,
                                        filtering = searchQuery.isNotBlank(),
                                        onToggleFavorite = { song ->
                                            com.localmusic.app.data.FavoritesStore.set(context, song.uri, !favorites.contains(song.uri))
                                        },
                                        onPlay = { song -> player.play(songs, song); playerOpen = true },
                                        onAddToQueue = { song ->
                                            player.appendToQueue(listOf(song))
                                            android.widget.Toast.makeText(context, "已加入播放列表：${song.title}", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        topPadding = pageTopPadding,
                                    )
                                    Page.Favorites -> LibraryPage(
                                        songs = favoriteList, nowPlaying = playback.id,
                                        favorites = favorites,
                                        highlightIndex = if (page == Page.Favorites) wheelIndex else -1,
                                        favoritesOnly = true,
                                        filtering = searchQuery.isNotBlank(),
                                        onToggleFavorite = { song ->
                                            com.localmusic.app.data.FavoritesStore.set(context, song.uri, !favorites.contains(song.uri))
                                        },
                                        onPlay = { song -> player.play(songs, song); playerOpen = true },
                                        onAddToQueue = { song ->
                                            player.appendToQueue(listOf(song))
                                            android.widget.Toast.makeText(context, "已加入播放列表：${song.title}", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        topPadding = pageTopPadding,
                                    )
                                    Page.Settings -> SettingsPage(
                                        status = status, bitPerfect = bitPerfect, autoNcm = autoNcm,
                                        diagnostics = diagnostics, trees = trees,
                                        exportLabel = exportLabel,
                                        eq = eqState,
                                        onOpenEq = { showEq = true },
                                        autoScrape = autoScrape,
                                        scrapeCover = scrapeCover,
                                        scrapeLyrics = scrapeLyrics,
                                        scrapeStatus = scrapeStatus,
                                        onAutoScrape = { autoScrape = it; prefs.edit().putBoolean("autoScrape", it).apply() },
                                        onScrapeCover = { scrapeCover = it; prefs.edit().putBoolean("scrapeCover", it).apply() },
                                        onScrapeLyrics = { scrapeLyrics = it; prefs.edit().putBoolean("scrapeLyrics", it).apply() },
                                        onScrapeNow = {
                                            scrapeScope.launch {
                                                com.localmusic.app.data.Scraper.scrape(context, songs, scrapeCover, scrapeLyrics, limit = 400)
                                            }
                                        },
                                        onRestoreCovers = {
                                            scrapeScope.launch {
                                                val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    com.localmusic.app.data.CoverStore.restoreOriginals(context, songs)
                                                }
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (n > 0) "已恢复 $n 首的自带封面" else "没有需要恢复的（这些歌没有自带封面）",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        },
                                        onBitPerfect = { bitPerfect = it; prefs.edit().putBoolean("bitPerfect", it).apply() },
                                        onAutoNcm = { autoNcm = it; prefs.edit().putBoolean("autoNcm", it).apply() },
                                        onPickTree = { pickFolder.launch(NeteaseTreeUri) },
                                        onPickExport = { pickExportFolder.launch(ExportInitialUri) },
                                        onRemoveTree = { library.removeTree(it); trees = library.treeUris().toList(); exportLabel = prefs.getString("exportLabel", null) },
                                        onScan = { library.scan() },
                                        onCancel = { library.cancel() },
                                        glassTintIndex = glassTintIndex,
                                        glassHue = glassHue,
                                        glassLevel = glassLevel,
                                        glassCustomColor = glassColor,
                                        onGlassTint = { index, hue, level ->
                                            glassTintIndex = index
                                            hue?.let { glassHue = it }
                                            level?.let { glassLevel = it }
                                            prefs.edit()
                                                .putInt("wheelGlassTint", glassTintIndex)
                                                .putFloat("wheelGlassHue", glassHue)
                                                .putFloat("wheelGlassLevel", glassLevel)
                                                .apply()
                                        },
                                        topPadding = pageTopPadding,
                                    )
                                }
                            }
                        }

                        // 注意：这里**不压暗背景**。用户要的是"周围亮、玻璃暗"——
                        // 压暗画在玻璃自己的 surfaceTint 上（见 ui/Glass.kt 的 DarkGlassTint），
                        // 背景保持原亮度，玻璃采样到的也就还是亮的页面，折射依然成立。
                    }

                    // ── 玻璃浮层：播放页 + 顶栏 + 迷你播放条 + dock，都在采集层之外 ──
                    Box(Modifier.fillMaxSize().padding(contentPadding)) {
                        // 播放页：带玻璃的界面必须在采集层外（见上面 when 分支的说明）。
                        // 顶栏让位给页面自己的那行玻璃圆钮。
                        if (playerOpen) {
                            PlayerPage(
                                backdrop = backdrop,
                                song = nowPlaying,
                                player = playback,
                                favorite = nowPlaying != null && favorites.contains(nowPlaying.uri),
                                topPadding = 8.dp,
                                bottomPadding = BarHeight + BarMargin + 12.dp,
                                positionFlow = player.position,
                                onToggle = { player.toggle() },
                                onNext = { player.next() },
                                onPrev = { player.previous() },
                                onSeek = { player.seek(it) },
                                onShuffle = { player.shuffle() },
                                onRepeat = { player.repeat() },
                                onClose = { playerOpen = false },
                                onFavorite = {
                                    nowPlaying?.let { song ->
                                        com.localmusic.app.data.FavoritesStore.set(context, song.uri, !favorites.contains(song.uri))
                                    }
                                },
                                onChangeCover = { coverPicker.launch(arrayOf("image/*")) },
                                onOpenEq = { showEq = true },
                                onJumpTo = { player.jumpTo(it) },
                                onMoveInQueue = { from, to -> player.moveInQueue(from, to) },
                                onRemoveFromQueue = { player.removeFromQueue(it) },
                            )
                        }
                        if (!playerOpen) {
                            GlassTopBar(
                                backdrop = backdrop,
                                title = page.title,
                                // 曲库/喜欢：顶栏直接当搜索框；设置页还是标题
                                query = searchQuery,
                                onQueryChange = if (page == Page.Settings) null else { q -> searchQuery = q },
                                modifier = Modifier.align(Alignment.TopCenter)
                                    .padding(start = BarMargin, end = BarMargin, top = TopBarMargin)
                                    .fillMaxWidth()
                                    .height(TopBarHeight),
                            )
                        }
                        // 滚轮：转一圈选一项；单击中间键切页；双击中间键确认。
                        // 先放在 dock 上方（确认可用后再去掉 dock、把它挪到 dock 的位置）。
                        if (!playerOpen) {
                            ClickWheel(
                                backdrop = backdrop,
                                label = page.title,
                                caption = wheelCaption,
                                onTick = { dir ->
                                    wheelIndex = (wheelIndex + dir).coerceIn(0, (wheelCount - 1).coerceAtLeast(0))
                                },
                                onCenterTap = { page = nextPage(page); wheelIndex = 0 },
                                onCenterDoubleTap = {
                                    if (page == Page.Settings) {
                                        wheelActions.getOrNull(wheelIndex)?.second?.invoke()
                                    } else {
                                        wheelList.getOrNull(wheelIndex)?.let { song -> player.play(wheelList, song) }
                                    }
                                },
                                modifier = Modifier.align(Alignment.BottomEnd)
                                    .padding(end = BarMargin + 6.dp, bottom = BarMargin + BarHeight + 96.dp)
                                    .size(190.dp),
                                ringTint = glassColor,
                            )
                        }
                        Column(
                            Modifier.align(Alignment.BottomCenter)
                                .padding(start = BarMargin, end = BarMargin, bottom = BarMargin)
                                .fillMaxWidth(),
                        ) {
                            // 播放页本身就是播放界面，所以只在别的页面显示迷你播放条
                            if (playback.id != null && !playerOpen) {
                                MiniPlayerBar(
                                    backdrop = backdrop, song = nowPlaying, player = playback,
                                    onToggle = { player.toggle() }, onOpen = { playerOpen = true },
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            Box(
                                // dock 只有两栏了，不必占满整屏：收窄一点更像悬浮胶囊，
                                // 而且玻璃要模糊的面积也小了（滚动时每帧都要重新采样）
                                Modifier.align(Alignment.CenterHorizontally)
                                    .fillMaxWidth(0.78f).height(BarHeight).graphicsLayer {
                                    scaleX = barScale; scaleY = barScale
                                }
                            ) {
                                // ① 玻璃底板：不含任何子内容。全 app 用同一档玻璃（库默认填充），不额外压暗。
                                Box(
                                    Modifier.matchParentSize().liquidGlass(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(50),
                                    )
                                )
                                // ② 内容层：与玻璃是兄弟
                                GlassNavBarContent(
                                    items = Page.entries.map { it.icon to it.title },
                                    selectedIndex = Page.entries.indexOf(page),
                                    onSelect = { page = Page.entries[it]; playerOpen = false },
                                    modifier = Modifier.matchParentSize(),
                                    backdrop = backdrop,
                                    contentHeight = BarHeight,
                                    onBarPressedChange = { barPressed = it },
                                )
                            }
                        }
                    }

                    // ── 首次打开：选 FLAC 导出位置（玻璃浮层，和采集层同一个窗口，折射才成立）──
                    if (showExportPrompt) {
                        ExportFolderPrompt(
                            backdrop = backdrop,
                            onPick = { pickExportFolder.launch(ExportInitialUri) },
                            onDismiss = { showExportPrompt = false },
                        )
                    }

                    // 播放界面已经不在采集层里渲染（见上面的说明）。

                    // ── 均衡器：独立的液态玻璃面板（同窗口浮层，玻璃才折射得到东西）──
                    if (showEq) {
                        EqualizerSheet(
                            backdrop = backdrop,
                            eq = eqState,
                            presets = com.localmusic.audio.playback.EqualizerController.presetNames,
                            onEnabled = { com.localmusic.audio.playback.EqualizerController.setEnabled(it) },
                            onBand = { index, level -> com.localmusic.audio.playback.EqualizerController.setBand(index, level) },
                            onBass = { com.localmusic.audio.playback.EqualizerController.setBass(it) },
                            onLoudness = { com.localmusic.audio.playback.EqualizerController.setLoudness(it) },
                            onPreset = { com.localmusic.audio.playback.EqualizerController.applyPreset(it) },
                            onClose = { showEq = false },
                        )
                    }
                }
            }
            }
        }
    }
}
