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
    Settings("设置", Icons.Rounded.Settings),
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

    var page by remember { mutableStateOf(Page.Library) }
    // 播放页不再是 dock 里的一栏（用户觉得多余）：点歌 / 点迷你播放条才进播放页
    var playerOpen by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
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
                Box(Modifier.fillMaxSize()) {
                    // ── 采集层：背景 + 全部页面内容（玻璃唯一能采样到的东西）──
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        AppBackground()
                        AnimatedContent(
                            targetState = page,
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                (fadeIn(LiquidMotion.snappy()) + slideInHorizontally(LiquidMotion.gentle()) { if (forward) it / 5 else -it / 5 })
                                    .togetherWith(fadeOut(LiquidMotion.snappy()) + slideOutHorizontally(LiquidMotion.snappy()) { if (forward) -it / 5 else it / 5 })
                            },
                            label = "page",
                        ) { p ->
                            Box(Modifier.fillMaxSize()) {
                                when (p) {
                                    // 播放页不在这里渲染 —— 带玻璃的界面必须待在采集层之外，
                                    // 否则玻璃会采样"正在录制自己"的层 → 渲染树自引用 → RenderThread 栈溢出闪退。
                                    Page.Library -> LibraryPage(
                                        songs = songs, status = status, nowPlaying = playback.id,
                                        favorites = favorites,
                                        showFavorites = showFavorites,
                                        onShowFavorites = { showFavorites = it },
                                        onToggleFavorite = { song ->
                                            com.localmusic.app.data.FavoritesStore.set(context, song.uri, !favorites.contains(song.uri))
                                        },
                                        onPlay = { song -> player.play(songs, song); playerOpen = true },
                                        onAddToQueue = { song ->
                                            player.appendToQueue(listOf(song))
                                            android.widget.Toast.makeText(context, "已加入播放列表：${song.title}", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onScan = { library.scan() },
                                        onCancel = { library.cancel() },
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
                                        onBitPerfect = { bitPerfect = it; prefs.edit().putBoolean("bitPerfect", it).apply() },
                                        onAutoNcm = { autoNcm = it; prefs.edit().putBoolean("autoNcm", it).apply() },
                                        onPickTree = { pickFolder.launch(NeteaseTreeUri) },
                                        onPickExport = { pickExportFolder.launch(ExportInitialUri) },
                                        onRemoveTree = { library.removeTree(it); trees = library.treeUris().toList(); exportLabel = prefs.getString("exportLabel", null) },
                                        onScan = { library.scan() },
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
                                onToggle = { player.toggle() },
                                onNext = { player.next() },
                                onPrev = { player.previous() },
                                onSeek = { player.seek(it) },
                                onShuffle = { player.shuffle() },
                                onRepeat = { player.repeat() },
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
                                modifier = Modifier.align(Alignment.TopCenter)
                                    .padding(start = BarMargin, end = BarMargin, top = TopBarMargin)
                                    .fillMaxWidth()
                                    .height(TopBarHeight),
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
                                Modifier.fillMaxWidth().height(BarHeight).graphicsLayer {
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
