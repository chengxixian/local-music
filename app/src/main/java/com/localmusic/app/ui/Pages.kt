// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.localmusic.app.PlaybackUi
import com.localmusic.app.data.ScanStatus
import com.localmusic.app.data.Song
import com.localmusic.app.data.formatTime
import com.liquidmiuix.theme.LiquidSpacing
import com.liquidmiuix.ui.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 把 SAF 的 tree URI 还原成人能读的路径：`...tree/primary%3ADownload%2FMusic` → `Download/Music`。 */
private fun treeLabel(tree: String): String = runCatching {
    val decoded = android.net.Uri.decode(tree.substringAfter("/tree/"))
    decoded.substringAfter(':').ifBlank { decoded }.ifBlank { tree }
}.getOrDefault(tree)

/** 主页：莫奈（壁纸）取色 + 当前曲目封面主导的渐变头部。 */
@Composable
fun HomePage(
    songs: List<Song>,
    player: PlaybackUi,
    nowPlaying: Song?,
    onPlay: (Song) -> Unit,
    onOpenPlayer: () -> Unit,
    onScan: () -> Unit,
    onOpenLibrary: () -> Unit,
    onStartListening: () -> Unit,
    favoriteCount: Int = 0,
    onOpenFavorites: () -> Unit = {},
    topPadding: Dp = LiquidSpacing.page,
) {
    val scheme = MiuixTheme.colorScheme
    val lossless = remember(songs) { songs.count { it.lossless } }
    val hiRes = remember(songs) { songs.count { it.bitDepth >= 24 || it.sampleRate > 48000 } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LiquidSpacing.page, end = LiquidSpacing.page, top = topPadding, bottom = 200.dp),
        verticalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
    ) {
        item {
            LiquidCard(modifier = Modifier.staggeredEntry(0)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.leading)) {
                    Artwork(nowPlaying ?: songs.firstOrNull(), Modifier.size(72.dp), radius = 18, requestPx = 220)
                    Column(Modifier.weight(1f)) {
                        Text(if (nowPlaying != null) "正在播放" else "开始聆听", style = MiuixTheme.textStyles.title4, color = scheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(player.title, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(player.artist, style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // 没有当前曲目时，这个按钮要真的开始放歌（之前是空操作）；
                    // 已有曲目时它相当于"打开播放器"。
                    FilledIconButton(
                        onClick = onStartListening,
                        enabled = songs.isNotEmpty() || player.id != null,
                    ) {
                        Icon(
                            if (player.id == null) Icons.Rounded.PlayArrow else Icons.Rounded.GraphicEq,
                            if (player.id == null) "开始播放" else "打开播放器",
                        )
                    }
                }
                player.error?.let { LiquidStatusBanner(text = "播放器：$it", icon = Icons.Rounded.ErrorOutline, color = scheme.error) }
            }
        }
        item {
            LiquidCard(modifier = Modifier.staggeredEntry(1)) {
                Text("曲库概览", style = MiuixTheme.textStyles.title4)
                Row(horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.item)) {
                    Stat("全部歌曲", songs.size.toString(), Modifier.weight(1f))
                    Stat("无损", lossless.toString(), Modifier.weight(1f))
                    Stat("高解析", hiRes.toString(), Modifier.weight(1f))
                }
                // 我喜欢的音乐：这里给入口，收藏动作在曲库列表和播放页的心形按钮上
                LiquidListItem(
                    title = "我喜欢的音乐",
                    subtitle = if (favoriteCount > 0) "$favoriteCount 首 · 点这里查看" else "还没有收藏 —— 在曲库或播放页点心形按钮加入",
                    leading = Icons.Rounded.Favorite,
                    onClick = onOpenFavorites,
                    showDivider = true,
                )
                LiquidListItem(title = "扫描存储器", subtitle = "重新索引本地歌曲与 ncm 导入", leading = Icons.Rounded.Refresh, onClick = onScan)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近添加", style = MiuixTheme.textStyles.title4, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenLibrary) { Text("全部") }
            }
        }
        if (songs.isEmpty()) {
            item { LiquidEmptyState("还没有歌曲", hint = "授予存储权限后会自动扫描；也可在设置里授权 ncm 文件夹") }
        } else {
            items(songs.take(30), key = { it.uri }) { song ->
                LiquidCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.leading)) {
                        Artwork(song, Modifier.size(50.dp), radius = 14, requestPx = 160)
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${song.artist} · ${song.spec}", style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onPlay(song) }) { Icon(Icons.Rounded.PlayArrow, "播放") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MiuixTheme.textStyles.title2, color = MiuixTheme.colorScheme.primary)
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 曲库 / 喜欢：两列封面网格。搜索在顶栏（额头）里，扫描状态与重新扫描在设置页。 */
@Composable
fun LibraryPage(
    songs: List<Song>,
    nowPlaying: String?,
    favorites: Set<String>,
    highlightIndex: Int = -1,
    favoritesOnly: Boolean = false,
    filtering: Boolean = false,
    onToggleFavorite: (Song) -> Unit,
    onPlay: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit = {},
    topPadding: Dp = LiquidSpacing.page,
) {
    val favoriteSongs = emptyList<Song>() // 过滤已在顶层完成，这里只负责画
    // 两列网格：每格是一张竖长方形卡片 —— 上半正方形封面铺满，下半放歌名与信息
    val gridState = rememberLazyGridState()
    val gridContext = LocalContext.current
    // 滚轮选中哪一格就立刻让它可见——**不播动画、不去抖**：
    // 之前用 animateScrollToItem + 120ms 去抖，转动时列表干脆不动、停下来才补一次动画，
    // 于是"列表跟不上滚轮"。已经可见时才做轻微动画跟随，避免边缘抖动。
    LaunchedEffect(highlightIndex, songs) {
        if (highlightIndex !in songs.indices) return@LaunchedEffect
        val visible = gridState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (highlightIndex < first || highlightIndex > last) {
            gridState.scrollToItem(highlightIndex)
        } else {
            gridState.animateScrollToItem(highlightIndex)
        }
    }
    // 预取下一屏的封面：滚到它时只剩纹理上传，帧时间更平（见 ArtworkStore.prefetch）
    LaunchedEffect(gridState, songs) {
        snapshotFlow {
            val visible = gridState.layoutInfo.visibleItemsInfo
            visible.lastOrNull()?.index ?: 0
        }.collect { last ->
            val from = last + 1
            val to = (last + 8).coerceAtMost(songs.lastIndex)
            if (from <= to) {
                withContext(Dispatchers.IO) { ArtworkStore.prefetch(gridContext.applicationContext, songs.subList(from, to + 1), 420) }
            }
        }
    }
    // 高亮与回调都用 State 传给卡片：转动时只触发**绘制/图层**更新，卡片本身可以跳过重组
    // （原来每个 tick 都让所有可见卡片重组一遍，这是转动卡的主因）
    val highlightState = rememberUpdatedState(highlightIndex)
    val playState = rememberUpdatedState(onPlay)
    val queueState = rememberUpdatedState(onAddToQueue)
    val favoriteState = rememberUpdatedState(onToggleFavorite)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LiquidSpacing.page, end = LiquidSpacing.page, top = topPadding, bottom = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
        verticalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
    ) {
        if (songs.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LiquidEmptyState(
                    when {
                        favoritesOnly -> "「喜欢」还是空的"
                        filtering -> "没有匹配的歌曲"
                        else -> "曲库为空"
                    },
                    hint = if (favoritesOnly) "在曲库或播放页点心形按钮加入" else "支持 mp3 / aac(m4a) / flac / wav / ogg / opus 等",
                )
            }
        } else {
            itemsIndexed(songs, key = { _, song -> song.uri }) { index, song ->
                LibraryGridCard(
                    song = song,
                    favorite = favorites.contains(song.uri),
                    playing = song.uri == nowPlaying,
                    index = index,
                    highlightState = highlightState,
                    playState = playState,
                    queueState = queueState,
                    favoriteState = favoriteState,
                )
            }
        }
    }
}

/**
 * 曲库网格里的一张卡片：**竖长方形**。
 *
 * 上半是正方形封面（`aspectRatio(1f)` 铺满卡片宽度），下半是歌名 + 艺术家 + 格式/规格。
 * 心形与播放做成封面上的两个半透明小圆钮 —— 窄卡片里再塞一行按钮会把信息挤没，
 * 而半透明底保证在任何封面上都看得清。
 */
@Composable
private fun LibraryGridCard(
    song: Song,
    favorite: Boolean,
    playing: Boolean,
    index: Int,
    highlightState: State<Int>,
    playState: State<(Song) -> Unit>,
    queueState: State<(Song) -> Unit>,
    favoriteState: State<(Song) -> Unit>,
) {
    val scheme = MiuixTheme.colorScheme
    Card(
        onClick = { playState.value(song) },
        modifier = Modifier.fillMaxWidth()
            // 高亮只在图层/绘制里读，不在组合里读：转动时不会让卡片重组，只重画
            .graphicsLayer {
                val s = if (highlightState.value == index) 1.055f else 1f
                scaleX = s; scaleY = s
            }
            .drawWithContent {
                drawContent()
                if (highlightState.value == index) {
                    drawRoundRect(
                        color = scheme.primary,
                        cornerRadius = CornerRadius(18.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Column {
            // 上半：正方形封面铺满
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                Artwork(song, Modifier.matchParentSize(), radius = 0, requestPx = 420)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.34f))
                        .clickable { favoriteState.value(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (favorite) "取消喜欢" else "加入我喜欢的音乐",
                        modifier = Modifier.size(17.dp),
                        tint = if (favorite) scheme.primary else Color.White,
                    )
                }
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp).size(32.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.34f))
                        .clickable { queueState.value(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, "加入播放列表", modifier = Modifier.size(20.dp), tint = Color.White)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.34f))
                        .clickable { playState.value(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, "播放", modifier = Modifier.size(20.dp), tint = Color.White)
                }
            }
            // 下半：歌名 + 信息
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    song.title,
                    style = MiuixTheme.textStyles.title4,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (playing) scheme.primary else scheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    song.artist,
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LiquidPill(song.format.uppercase())
                    Text(
                        when {
                            song.bitDepth > 0 -> "${song.bitDepth}bit/${song.sampleRate / 1000.0}kHz"
                            song.duration > 0 -> formatTime(song.duration)
                            else -> song.album
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 滚轮选中时的"光标"：描边 + 微微弹出（和曲库卡片的选中效果一致）。 */
private fun Modifier.wheelCursor(active: Boolean, color: Color): Modifier = this
    .graphicsLayer {
        val s = if (active) 1.03f else 1f
        scaleX = s; scaleY = s
    }
    .then(if (active) Modifier.border(2.dp, color, RoundedCornerShape(18.dp)) else Modifier)

/**
 * 滚轮动作下标 → 设置页里第几张卡（`wheelActions` 的顺序见 MainActivity）。
 * 只用来滚动定位，改动作顺序时这里有对应关系要一起看。
 */
private val SETTINGS_CARD_OF_WHEEL = mapOf(
    0 to 0, 1 to 0,          // 音频输出卡：USB Bit-perfect / 均衡器
    2 to 1, 3 to 1, 4 to 1,  // ncm 卡：自动转换 / 导出位置 / 添加文件夹
    5 to 2, 6 to 2, 7 to 2, 8 to 2, 9 to 2, // 自动刮削卡：四项 + 恢复封面
    12 to 2,                 // 自动刮削卡：清除刮削封面并重刮
    10 to 3,                 // 滚轮玻璃颜色卡
    11 to 4,                 // 曲库卡：重新扫描
)

/**
 * App 内的点阵 "Lm" 标记（和图标同一套字形）。
 *
 * 为什么红点在这里、不在自适应图标里：自适应图标只有 background / foreground 两层，
 * 塞不进"额外一层"红点；而且贴近圆角遮罩的角上会被裁掉。所以图标保持纯白点阵，
 * 红点作为**品牌标记**画在 App 内（设置页「关于」卡）。
 */
@Composable
private fun DotMatrixMark(modifier: Modifier = Modifier, cell: Dp = 4.dp, accent: Boolean = true) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val rows = DOT_MARK_ROWS
    androidx.compose.foundation.Canvas(modifier) {
        val c = cell.toPx()
        val w = c * rows.first().length
        val h = c * rows.size
        val ox = (size.width - w) / 2f
        val oy = (size.height - h) / 2f
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val cx = ox + (x + 0.5f) * c
                val cy = oy + (y + 0.5f) * c
                val lit = ch == 'X'
                // 红点：m 右上角那颗
                val isAccent = accent && lit && y == 1 && x == rows.first().length - 1
                val color = when {
                    isAccent -> Color(0xFFD71921)
                    lit -> onSurface
                    else -> onSurface.copy(alpha = 0.12f)
                }
                val radius = if (lit) c * 0.215f else c * 0.15f
                drawCircle(color = color, radius = radius, center = androidx.compose.ui.geometry.Offset(cx, cy))
            }
        }
    }
}

/** 图标用的点阵字形（方案 A：7 格大写 L + 5 格小写 m，底部对齐）。 */
private val DOT_MARK_ROWS = listOf(
    "X..........",
    "X....X.X.X.",
    "X....XXXXX.",
    "X....X.X.X.",
    "X....X.X.X.",
    "X..........",
    "XXXXX......",
)

/** 设置：USB 直通、存储授权、ncm 自动转换、扫描诊断。 */
@Composable
fun SettingsPage(
    status: ScanStatus,
    bitPerfect: Boolean,
    autoNcm: Boolean,
    diagnostics: String,
    trees: List<String>,
    onBitPerfect: (Boolean) -> Unit,
    onAutoNcm: (Boolean) -> Unit,
    onPickTree: () -> Unit,
    onRemoveTree: (String) -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit = {},
    exportLabel: String? = null,
    onPickExport: () -> Unit = {},
    eq: com.localmusic.audio.playback.EqState = com.localmusic.audio.playback.EqState(),
    onOpenEq: () -> Unit = {},
    autoScrape: Boolean = true,
    scrapeCover: Boolean = true,
    scrapeLyrics: Boolean = true,
    scrapeStatus: com.localmusic.app.data.Scraper.Status = com.localmusic.app.data.Scraper.Status(),
    onAutoScrape: (Boolean) -> Unit = {},
    onScrapeCover: (Boolean) -> Unit = {},
    onScrapeLyrics: (Boolean) -> Unit = {},
    onScrapeNow: () -> Unit = {},
    onRestoreCovers: () -> Unit = {},
    onClearScrapedCovers: () -> Unit = {},
    neteaseSource: Boolean = true,
    onNeteaseSource: (Boolean) -> Unit = {},
    tintState: WheelTintState = WheelTintState(),
    wheelIndex: Int = 0,
    onGlassTint: (WheelTintState) -> Unit = {},
    topPadding: Dp = LiquidSpacing.page,
) {
    val scheme = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    // 滚轮转到哪一项，就把对应的卡片滚到可见位置（定位映射见 SETTINGS_CARD_OF_WHEEL）
    LaunchedEffect(wheelIndex) {
        SETTINGS_CARD_OF_WHEEL[wheelIndex]?.let { listState.animateScrollToItem(it) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LiquidSpacing.page, end = LiquidSpacing.page, top = topPadding, bottom = 200.dp),
        verticalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
    ) {
        item {
            LiquidCard(modifier = Modifier.wheelCursor(wheelIndex == 0 || wheelIndex == 1, scheme.primary)) {
                Text("音频输出", style = MiuixTheme.textStyles.title4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("USB Bit-perfect", style = MiuixTheme.textStyles.title4)
                        Text("Android 14+ 且外接 USB DAC 时，按源 PCM 规格申请直通；不支持时自动回落到系统混音。",
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = bitPerfect, onCheckedChange = onBitPerfect)
                }
                LiquidStatusBanner(text = diagnostics, icon = Icons.Rounded.Headphones, color = scheme.primary)
                // 均衡器入口：面板本身是液态玻璃浮层（见 EqualizerSheet），这里只做入口与状态摘要
                LiquidListItem(
                    title = "均衡器",
                    subtitle = when {
                        !eq.available -> eq.message.ifBlank { "本机不可用" }
                        eq.bypassed -> "USB 直通中 · 当前不生效"
                        eq.enabled -> "已启用 · " + eq.bands.joinToString(" / ") { "${"%.0f".format(it.levelMb / 100f)}" } + " dB"
                        else -> "未启用 · 点击打开玻璃面板调节"
                    },
                    leading = Icons.Rounded.GraphicEq,
                    onClick = onOpenEq,
                )
            }
        }
        item {
            LiquidCard(modifier = Modifier.wheelCursor(wheelIndex in 2..4, scheme.primary)) {
                Text("网易云 ncm 导入", style = MiuixTheme.textStyles.title4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动转换为 MP3 / FLAC", style = MiuixTheme.textStyles.title4)
                        Text("扫描授权文件夹时自动转换：优先转 FLAC；转不出 FLAC 就回退成 MP3 或原样发布，不再直接失败。输出到导出文件夹（未设则落应用私有 files/music/ncm），原文件保留。",
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = autoNcm, onCheckedChange = onAutoNcm)
                }
                TextButton(onClick = onPickTree) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("添加音乐 / ncm 文件夹") }
                LiquidListItem(
                    title = "导出位置",
                    subtitle = exportLabel?.let { "$it（转换后的 FLAC / MP3 导出到这里，卸载应用也不会删）" }
                        ?: "尚未选择 —— 目前只会暂存到应用私有目录，卸载即丢",
                    leading = Icons.Rounded.DriveFileMove,
                    onClick = onPickExport,
                    showDivider = true,
                )
                if (trees.isEmpty()) {
                    Text("未授权任何文件夹。网易云下载的 ncm 就在 /storage/emulated/0/download/netease/cloudmusic/Music，" +
                        "点上面的按钮会直接打开这个目录；若在 Android/data/com.netease.cloudmusic 里，那部分受系统保护，任何第三方应用都读不到。" +
                        "注意 Download 根目录本身被系统禁止授权，只能授权它的子目录。",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                } else trees.forEach { tree ->
                    LiquidListItem(
                        title = treeLabel(tree),
                        subtitle = "读取权限已持久化，重启后仍然有效",
                        leading = Icons.Rounded.Folder,
                        showDivider = true,
                        trailing = { TextButton(onClick = { onRemoveTree(tree) }) { Text("移除") } },
                    )
                }
            }
        }
        item {
            LiquidCard(modifier = Modifier.wheelCursor(wheelIndex in 5..9, scheme.primary)) {
                Text("自动刮削", style = MiuixTheme.textStyles.title4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("扫描后自动补全", style = MiuixTheme.textStyles.title4)
                        Text("只补缺的：已有封面/歌词的歌会跳过。来源 iTunes · Deezer（封面）、LRCLIB（歌词），结果缓存在应用目录，之后离线可用。",
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = autoScrape, onCheckedChange = onAutoScrape)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("刮封面", style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Switch(checked = scrapeCover, onCheckedChange = onScrapeCover, enabled = autoScrape)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("刮歌词", style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Switch(checked = scrapeLyrics, onCheckedChange = onScrapeLyrics, enabled = autoScrape)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("网易云音乐源", style = MiuixTheme.textStyles.title4)
                        Text("优先用网易云音乐搜索封面与歌词（非官方接口，不需要账号；可能被限流或随时失效，失败会自动回落到 iTunes / Deezer / LRCLIB）。",
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = neteaseSource, onCheckedChange = onNeteaseSource)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(scrapeStatus.message, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onScrapeNow, enabled = !scrapeStatus.running) { Text(if (scrapeStatus.running) "刮削中…" else "立即刮削") }
                }
                // 之前刮削把歌曲自带的封面盖掉过？这里一键把原封面放回来（用户自己设的封面不动）
                LiquidListItem(
                    title = "恢复歌曲自带封面",
                    subtitle = "撤掉自动刮削下载的封面，让文件内嵌/系统专辑封面重新显示（手动设过的封面保留）",
                    leading = Icons.Rounded.Restore,
                    onClick = onRestoreCovers,
                )
                LiquidListItem(
                    title = "清除自动刮削的封面并重刮",
                    subtitle = "只清自动下载的（手动设过的封面保留），清完立刻用修好的逻辑重刮一遍 —— 之前抓错的封面靠这个刷新",
                    leading = Icons.Rounded.RestartAlt,
                    onClick = onClearScrapedCovers,
                )
                scrapeStatus.failures.forEach {
                    LiquidStatusBanner(text = it, icon = Icons.Rounded.CloudOff, color = scheme.onSurfaceVariantSummary)
                }
            }
        }
        item {
            LiquidCard(modifier = Modifier.wheelCursor(wheelIndex == 10, scheme.primary)) {
                Text("滚轮圆环玻璃颜色", style = MiuixTheme.textStyles.title4)
                Text("有色玻璃是「吸光」的：颜色越沉、透光率越低。只改滚轮那一圈，dock / 顶栏 / 播放控件保持无色玻璃。",
                    style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WheelGlassTints.forEachIndexed { index, tint ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(30.dp).clip(CircleShape)
                                    .background(tint.color)
                                    .border(
                                        width = if (index == tintState.index) 3.dp else 1.dp,
                                        color = if (index == tintState.index) scheme.primary else scheme.onSurfaceVariantSummary.copy(alpha = 0.35f),
                                        shape = CircleShape,
                                    )
                                    .clickable { onGlassTint(tintState.copy(index = index)) }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(tint.name, style = MiuixTheme.textStyles.footnote1,
                                color = if (index == tintState.index) scheme.primary else scheme.onSurfaceVariantSummary)
                        }
                    }
                }
                // 调色盘：色相 / 饱和度 / 透光率。拖动任意一个即进入"自定义"，实时生效
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("色相", style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Text("${tintState.hue.toInt()}°", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                }
                Slider(
                    value = tintState.hue,
                    onValueChange = { onGlassTint(tintState.copy(index = -1, hue = it)) },
                    valueRange = 0f..360f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("饱和度", style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Text("${(tintState.sat * 100).toInt()}%", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                }
                Slider(
                    value = tintState.sat,
                    onValueChange = { onGlassTint(tintState.copy(index = -1, sat = it)) },
                    valueRange = 0f..1f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("透光率（越低调越暗）", style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Text("${(tintState.level * 100).toInt()}%", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                }
                Slider(
                    value = tintState.level,
                    onValueChange = { onGlassTint(tintState.copy(index = -1, level = it)) },
                    valueRange = 0.15f..1f,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline)) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(tintState.color))
                    Text(
                        "当前：${tintState.label}${if (tintState.index < 0) "（色相 ${tintState.hue.toInt()}° · 饱和 ${(tintState.sat * 100).toInt()}% · 透光 ${(tintState.level * 100).toInt()}%）" else ""}",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item {
            LiquidCard(modifier = Modifier.wheelCursor(wheelIndex == 11, scheme.primary)) {
                Text("曲库", style = MiuixTheme.textStyles.title4)
                LiquidListItem(title = "重新扫描", subtitle = status.message, leading = Icons.Rounded.Refresh, onClick = onScan)
                if (status.running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(status.message, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("停止扫描") }
                    }
                }
                status.failures.forEach { LiquidStatusBanner(text = it, icon = Icons.Rounded.WarningAmber, color = scheme.error) }
            }
        }
        item {
            LiquidCard {
                Text("关于", style = MiuixTheme.textStyles.title4)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline)) {
                    // 品牌标记：点阵 Lm + 一颗 Nothing 红（图标里不放红点，只有背景/前景两层）
                    DotMatrixMark(Modifier.size(52.dp, 34.dp), cell = 4.6.dp)
                    Text("local music", style = MiuixTheme.textStyles.title4)
                }
                Text("播放内核移植自 Rueded/AURALIS（GPLv3），前端使用 chengxixian/liquid-miuix，ncm 解码移植自 taurusxin/ncmdump。",
                    style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
            }
        }
    }
}
