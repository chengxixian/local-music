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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.QueueMusic
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

/** 「问名字」请求：标题 + 初始值 + 确认回调。放进 state，由**浮层**渲染对话框。 */
private data class NamingRequest(val title: String, val initial: String, val onConfirm: (String) -> Unit)

/**
 * 问名字对话框（玻璃版）。
 *
 * 两个要点：
 *  1. 必须画在浮层：页面在采集层内，而滚轮/顶栏在浮层 —— 画在页面里会被滚轮盖住"确定/取消"。
 *  2. 玻璃与内容必须是兄弟（玻璃 Box 里不能有子内容），否则就是自引用 backdrop。
 */
@Composable
private fun NameDialogGlass(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val scheme = MiuixTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth(0.86f)) {
            Box(
                Modifier.matchParentSize().liquidGlass(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(24.dp),
                )
            )
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MiuixTheme.textStyles.title3)
                Spacer(Modifier.height(12.dp))
                // 输入框本身也做成液态玻璃
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.matchParentSize().liquidGlass(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(14.dp),
                        )
                    )
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = MiuixTheme.textStyles.body1.copy(color = scheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    androidx.compose.material3.TextButton(
                        onClick = onDismiss,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = scheme.onSurfaceVariantSummary,
                        ),
                    ) { Text("取消") }
                    androidx.compose.material3.TextButton(
                        onClick = { onConfirm(text) },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = scheme.primary),
                    ) { Text("确定") }
                }
            }
        }
    }
}

private enum class Page(val title: String, val icon: ImageVector) {    Library("曲库", Icons.Rounded.LibraryMusic),
    Playlists("乐单", Icons.Rounded.QueueMusic),
    Favorites("喜欢", Icons.Rounded.Favorite),
    Settings("设置", Icons.Rounded.Settings),
}

/** 滚轮单击切页的顺序（与 dock 从左到右一致：曲库 → 乐单 → 喜欢 → 设置）。 */
private fun nextPage(current: Page): Page = when (current) {
    Page.Library -> Page.Playlists
    Page.Playlists -> Page.Favorites
    Page.Favorites -> Page.Settings
    Page.Settings -> Page.Library
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
    // 乐单：同样 revision 驱动；`openPlaylist` 是当前打开的乐单（null = 乐单列表页）
    val playlistsRevision by com.localmusic.app.data.PlaylistStore.revision.collectAsState()
    val playlists = remember(playlistsRevision, context) { com.localmusic.app.data.PlaylistStore.all(context) }
    var openPlaylist by remember { mutableStateOf<com.localmusic.app.data.Playlist?>(null) }
    // "加入乐单"面板正在为哪首歌打开；以及"换乐单封面"正在等哪张图
    var pickerSong by remember { mutableStateOf<com.localmusic.app.data.Song?>(null) }
    var playlistCoverTarget by remember { mutableStateOf<Long?>(null) }

    var playerOpen by remember { mutableStateOf(false) }

    /**
     * 统一的"播这首歌"入口。
     *
     * DSD（.dsf/.dff）Media3 解不了，所以先把 1bit 码流抽成 176.4kHz/24bit PCM WAV（结果缓存），
     * 再把转好的那份交给播放链 —— 这样它也能继续走 USB bit-perfect。
     */
    val uiScope = rememberCoroutineScope()
    val playSong: (List<com.localmusic.app.data.Song>, com.localmusic.app.data.Song) -> Unit = { list, song ->
        if (!com.localmusic.app.data.Dsd.isDsd(song.uri)) {
            player.play(list, song)
            playerOpen = true
        } else {
            android.widget.Toast.makeText(context, "DSD：正在抽取为 PCM（首次较慢，之后走缓存）…", android.widget.Toast.LENGTH_SHORT).show()
            uiScope.launch {
                val playable: java.io.File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val uri = android.net.Uri.parse(song.uri)
                        val name = song.title.ifBlank { "dsd" }.take(40) +
                            if (song.uri.lowercase().endsWith(".dff")) ".dff" else ".dsf"
                        val src = com.localmusic.app.data.Dsd.copyToLocal(context, uri, name) ?: return@runCatching null
                        val info = com.localmusic.app.data.Dsd.probe(src) ?: return@runCatching null
                        val out = com.localmusic.app.data.Dsd.cacheFile(context, src)
                        if (out.isFile && out.length() > 0L) out
                        else if (com.localmusic.app.data.Dsd.convertToWav(src, out, info)) out
                        else null
                    }.getOrNull()
                }
                if (playable == null) {
                    android.widget.Toast.makeText(context, "DSD 转换失败：文件可能损坏或格式不支持", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    // 规格必须换成"转码后的 PCM"：Song 的 sampleRate/bitDepth 会进 MediaItem extras，
                    // 而 USB 直通（AudioMixerAttributes）正是按它去申请源规格的。
                    // 不换的话会拿 DSD 的 2822400Hz/1bit 去申请 —— 任何 DAC 都不可能支持，
                    // 结果是白退化成系统输出，USB DAC 直通等于失效。
                    val converted = song.copy(
                        uri = android.net.Uri.fromFile(playable).toString(),
                        format = "wav",
                        sampleRate = com.localmusic.app.data.Dsd.PCM_RATE,
                        bitDepth = 24,
                    )
                    player.play(list.map { if (it.uri == song.uri) converted else it }, converted)
                    playerOpen = true
                }
            }
        }
    }
    // "问名字"对话框：必须画在**浮层**（滚轮在浮层、页面在采集层，画在页面里会被滚轮盖住按钮）
    var naming by remember { mutableStateOf<NamingRequest?>(null) }
    var showPlaylistMenu by remember { mutableStateOf(false) }
    val askName: (String, String, (String) -> Unit) -> Unit = { title, initial, confirm ->
        naming = NamingRequest(title, initial, confirm)
    }
    val scrapeStatus by com.localmusic.app.data.Scraper.status.collectAsState()
    val scrapeScope = rememberCoroutineScope()
    val updateState by com.localmusic.app.data.UpdateChecker.status.collectAsState()
    var autoScrape by remember { mutableStateOf(prefs.getBoolean("autoScrape", true)) }
    var scrapeCover by remember { mutableStateOf(prefs.getBoolean("scrapeCover", true)) }
    var scrapeLyrics by remember { mutableStateOf(prefs.getBoolean("scrapeLyrics", true)) }
    // 刮削是否使用网易云音乐源（非官方接口，默认开）
    var neteaseSource by remember { mutableStateOf(prefs.getBoolean("neteaseSource", true)) }
    // 滚轮圆环的玻璃颜色：预设下标（-1 = 自定义）+ 色相/饱和度/透光率
    var tintState by remember {
        mutableStateOf(
            com.localmusic.app.ui.WheelTintState(
                index = prefs.getInt("wheelGlassTint", 0),
                hue = prefs.getFloat("wheelGlassHue", 215f),
                level = prefs.getFloat("wheelGlassLevel", 0.55f),
                sat = prefs.getFloat("wheelGlassSat", 0.62f),
            )
        )
    }
    fun saveTint(state: com.localmusic.app.ui.WheelTintState) {
        tintState = state
        prefs.edit()
            .putInt("wheelGlassTint", state.index)
            .putFloat("wheelGlassHue", state.hue)
            .putFloat("wheelGlassLevel", state.level)
            .putFloat("wheelGlassSat", state.sat)
            .apply()
    }

    var page by remember { mutableStateOf(Page.Library) }
    // 搜索词提到顶层：输入框在顶栏（额头）里，曲库/喜欢两页共用它
    var searchQuery by remember { mutableStateOf("") }
    // 滚轮选中的下标（换页时归零）
    var wheelIndex by remember(page) { mutableStateOf(0) }
    // 播放页不再是 dock 里的一栏（用户觉得多余）：点歌 / 点迷你播放条才进播放页
    var showAbout by remember { mutableStateOf(false) }
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
    // 当前打开的乐单里的歌（按加入顺序；乐单里已不存在的歌自动忽略）
    val playlistSongs = remember(openPlaylist, songs, playlistsRevision) {
        val pl = openPlaylist ?: return@remember emptyList()
        val byUri = songs.associateBy { it.uri }
        com.localmusic.app.data.PlaylistStore.songIds(context, pl.id).mapNotNull { byUri[it] }
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
            com.localmusic.app.data.Scraper.scrape(context, list, scrapeCover, scrapeLyrics, limit = 40, wantNetease = neteaseSource)
        }
    }

    // 启动时查一次 GitHub Release 版本；有新版本就弹玻璃提示（失败静默，不打扰）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000)
        com.localmusic.app.data.UpdateChecker.check(context, silent = true)
    }

    // 网易云源自检：结果只写 logcat（标签 LMNetease）。手机 shell 没有网络出口，
    // 所以"能不能用"只能靠应用自己跑一遍来看。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5_000)
        runCatching { com.localmusic.app.data.Scraper.neteaseProbe() }
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

    // 乐单封面：和歌曲封面一样复制进应用目录，原图被删也不怕
    val playlistCoverPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = playlistCoverTarget
        if (uri != null && target != null) {
            coverScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.localmusic.app.data.PlaylistStore.importCover(context, uri)?.let { path ->
                    com.localmusic.app.data.PlaylistStore.setCover(context, target, path)
                }
            }
        }
        playlistCoverTarget = null
    }

    // 返回键：先关播放页，再收均衡器面板
    BackHandler(enabled = showEq) { showEq = false }
    BackHandler(enabled = playerOpen && !showEq) { playerOpen = false }
    BackHandler(enabled = pickerSong != null) { pickerSong = null }
    BackHandler(enabled = openPlaylist != null && pickerSong == null) { openPlaylist = null }

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
                            com.localmusic.app.data.Scraper.scrape(context, songs, scrapeCover, scrapeLyrics, limit = 400, wantNetease = neteaseSource)
                        }
                    },
                    "网易云音乐源" to { neteaseSource = !neteaseSource; prefs.edit().putBoolean("neteaseSource", neteaseSource).apply() },
                    "恢复歌曲自带封面" to {
                        scrapeScope.launch {
                            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.localmusic.app.data.CoverStore.restoreOriginals(context, songs)
                            }
                            android.widget.Toast.makeText(context, if (n > 0) "已恢复 $n 首的自带封面" else "没有需要恢复的", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    "滚轮玻璃颜色" to {
                        val next = if (tintState.index < 0) 0 else (tintState.index + 1) % com.localmusic.app.ui.WheelGlassTints.size
                        saveTint(tintState.copy(index = next))
                    },
                    "清除刮削封面并重刮" to {
                        scrapeScope.launch {
                            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.localmusic.app.data.CoverStore.clearScraped(context)
                            }
                            android.widget.Toast.makeText(context, "已清除 $n 张自动刮削的封面，正在重刮…", android.widget.Toast.LENGTH_SHORT).show()
                            com.localmusic.app.data.Scraper.scrape(context, songs, true, false, limit = 400, wantNetease = neteaseSource)
                        }
                    },
                    "重新扫描" to { library.scan() },
                )
                val wheelList = when (page) {
                    Page.Library -> libraryList
                    Page.Playlists -> playlistSongs
                    Page.Favorites -> favoriteList
                    Page.Settings -> emptyList()
                }
                // 每个乐单的第一首歌（没设自定义封面时，卡片/工具栏就用它的封面）
    val playlistFirstSongs = remember(playlists, songs) {
        val byUri = songs.associateBy { it.uri }
        playlists.associate { pl ->
            pl.id to com.localmusic.app.data.PlaylistStore.songIds(context, pl.id).firstNotNullOfOrNull { byUri[it] }
        }
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
                                        onPlay = { song -> playSong(songs, song) },
                                        onAddToQueue = { song ->
                                            player.appendToQueue(listOf(song))
                                            android.widget.Toast.makeText(context, "已加入播放列表：${song.title}", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onAddToPlaylist = { song -> pickerSong = song },
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
                                        onPlay = { song -> playSong(songs, song) },
                                        onAddToQueue = { song ->
                                            player.appendToQueue(listOf(song))
                                            android.widget.Toast.makeText(context, "已加入播放列表：${song.title}", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onAddToPlaylist = { song -> pickerSong = song },
                                        topPadding = pageTopPadding,
                                    )
                                    Page.Playlists -> {
                                        val opened = openPlaylist
                                        if (opened == null) {
                                            PlaylistPage(
                                                playlists = playlists,
                                                firstSongs = playlistFirstSongs.filterValues { it != null }.mapValues { it.value!! },
                                                topPadding = pageTopPadding,
                                                onCreate = { name -> com.localmusic.app.data.PlaylistStore.create(context, name) },
                                                onOpen = { openPlaylist = it },
                                                onAskName = askName,
                                            )
                                        } else {
                                            LibraryPage(
                                                songs = playlistSongs,
                                                nowPlaying = playback.id,
                                                favorites = favorites,
                                                highlightIndex = if (page == Page.Playlists) wheelIndex else -1,
                                                filtering = false,
                                                onToggleFavorite = { song ->
                                                    com.localmusic.app.data.FavoritesStore.set(context, song.uri, !favorites.contains(song.uri))
                                                },
                                                onPlay = { song -> playSong(playlistSongs, song) },
                                                onAddToQueue = { song ->
                                                    player.appendToQueue(listOf(song))
                                                    android.widget.Toast.makeText(context, "已加入播放列表：${song.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onAddToPlaylist = { song -> pickerSong = song },
                                                topPadding = pageTopPadding + 300.dp,
                                            )
                                        }
                                    }
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
                                        onClearScrapedCovers = {
                                            scrapeScope.launch {
                                                val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    com.localmusic.app.data.CoverStore.clearScraped(context)
                                                }
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "已清除 $n 张自动刮削的封面，正在用修好的逻辑重刮…",
                                                    android.widget.Toast.LENGTH_LONG,
                                                ).show()
                                                com.localmusic.app.data.Scraper.scrape(context, songs, true, false, limit = 400, wantNetease = neteaseSource)
                                            }
                                        },
                                        neteaseSource = neteaseSource,
                                        onNeteaseSource = { neteaseSource = it; prefs.edit().putBoolean("neteaseSource", it).apply() },
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
                                        onOpenAbout = { showAbout = true },
                                        onCancel = { library.cancel() },
                                        tintState = tintState,
                                        wheelIndex = wheelIndex,
                                        onGlassTint = { saveTint(it) },
                                        topPadding = pageTopPadding,
                                    )
                                }
                            }
                        }

                        // 播放页的背景：当前封面放大 + 高斯模糊（画在**采集层之内**，
                        // 这样播放页的玻璃折射到的就是这张封面，而不是背后的曲库页）
                        if (playerOpen) {
                            PlayerBackdrop(song = nowPlaying, modifier = Modifier.matchParentSize())
                        }

                        // 关于页：它是**独立一页**，不是浮在设置页上的弹层。
                        // 所以这里把设置内容整页盖掉（重画一次 App 背景），
                        // 玻璃卡片就浮在这页自己的背景上，背后不会透出设置列表。
                        if (showAbout) {
                            AppBackground()
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
                        // 乐单详情：顶部玻璃工具栏（浮层 → 真玻璃；同时这个状态收起滚轮）
                        openPlaylist?.let { opened ->
                            if (!playerOpen && !showAbout) {
                                PlaylistHeader(
                                    backdrop = backdrop,
                                    playlist = opened,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(start = 16.dp, end = 16.dp, top = 80.dp)
                                        .fillMaxWidth(),
                                    onBack = { openPlaylist = null; showPlaylistMenu = false },
                                    onMenu = { showPlaylistMenu = true },
                                )
                                // 重命名 / 换封面 / 删除：收在一个按钮弹出的面板里
                                if (showPlaylistMenu) {
                                    PlaylistActionsSheet(
                                        backdrop = backdrop,
                                        playlist = opened,
                                        onRename = {
                                            showPlaylistMenu = false
                                            askName("重命名乐单", opened.name) { name ->
                                                com.localmusic.app.data.PlaylistStore.rename(context, opened.id, name)
                                            }
                                        },
                                        onChangeCover = {
                                            showPlaylistMenu = false
                                            playlistCoverTarget = opened.id
                                            playlistCoverPicker.launch(arrayOf("image/*"))
                                        },
                                        onDelete = {
                                            showPlaylistMenu = false
                                            com.localmusic.app.data.PlaylistStore.delete(context, opened.id)
                                            openPlaylist = null
                                        },
                                        onDismiss = { showPlaylistMenu = false },
                                    )
                                }
                            }
                        }
                        // 关于页：独立页面（原本平铺在设置页里，现在只有一个入口）
                        if (showAbout) {
                            AboutPage(onBack = { showAbout = false })
                        }
                        // 问名字（新建 / 重命名乐单）：在浮层里，滚轮之上
                        naming?.let { req ->
                            NameDialogGlass(
                                backdrop = backdrop,
                                title = req.title,
                                initial = req.initial,
                                onDismiss = { naming = null },
                                onConfirm = { name -> req.onConfirm(name); naming = null },
                            )
                        }
                        // 「加入乐单」面板（和喜欢一样：点一下即加/去，不需要确认）
                        pickerSong?.let { song ->
                            val memberOf = remember(song.uri, playlistsRevision, context) {
                                com.localmusic.app.data.PlaylistStore.playlistsOf(context, song.uri)
                            }
                            AddToPlaylistSheet(
                                backdrop = backdrop,
                                song = song,
                                playlists = playlists,
                                memberOf = memberOf,
                                onToggle = { id, member ->
                                    com.localmusic.app.data.PlaylistStore.setSong(context, id, song.uri, member)
                                },
                                onCreate = { name ->
                                    val id = com.localmusic.app.data.PlaylistStore.create(context, name)
                                    if (id > 0) com.localmusic.app.data.PlaylistStore.setSong(context, id, song.uri, true)
                                },
                                onDismiss = { pickerSong = null },
                                onAskName = askName,
                            )
                        }
                        if (!playerOpen && !showAbout) {
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
                        if (!playerOpen && !showAbout && naming == null && pickerSong == null && openPlaylist == null) {
                            ClickWheel(
                                backdrop = backdrop,
                                onTick = { dir ->
                                    wheelIndex = (wheelIndex + dir).coerceIn(0, (wheelCount - 1).coerceAtLeast(0))
                                },
                                onCenterTap = { page = nextPage(page); wheelIndex = 0 },
                                onCenterDoubleTap = {
                                    if (page == Page.Settings) {
                                        wheelActions.getOrNull(wheelIndex)?.second?.invoke()
                                    } else {
                                        wheelList.getOrNull(wheelIndex)?.let { song -> playSong(wheelList, song) }
                                    }
                                },
                                modifier = Modifier.align(Alignment.BottomEnd)
                                    .padding(end = BarMargin + 6.dp, bottom = BarMargin + BarHeight + 96.dp)
                                    .size(190.dp),
                                ringTint = tintState.color,
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
                            // 关于页是独立一页：下面不挂 dock（导航栏）。
                            // 迷你播放条留着 —— 它算播放控制，不算导航。
                            if (!showAbout) {
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
                                        onSelect = { page = Page.entries[it]; playerOpen = false; showAbout = false },
                                        modifier = Modifier.matchParentSize(),
                                        backdrop = backdrop,
                                        contentHeight = BarHeight,
                                        onBarPressedChange = { barPressed = it },
                                    )
                                }
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

                    // 新版本提示（采集层之外的同窗口玻璃浮层）
                    updateState.update?.let { update ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            com.localmusic.app.ui.UpdatePrompt(
                                backdrop = backdrop,
                                update = update,
                                downloading = updateState.downloading,
                                progress = updateState.progress,
                                downloadedBytes = updateState.downloadedBytes,
                                totalBytes = updateState.totalBytes,
                                error = updateState.error,
                                onUpdate = {
                                    com.localmusic.app.data.UpdateChecker.startDownload(context, update) { apk ->
                                        com.localmusic.app.data.UpdateChecker.install(context, apk)
                                    }
                                },
                                onDismiss = { com.localmusic.app.data.UpdateChecker.dismiss() },
                            )
                        }
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
