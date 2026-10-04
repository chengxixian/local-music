// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.localmusic.app.R
import com.localmusic.app.PlaybackUi
import com.localmusic.app.data.Playlist
import com.localmusic.app.data.ScanStatus
import com.liquidmiuix.glass.*import com.localmusic.app.data.Song
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
                        Text(if (nowPlaying != null) stringResource(R.string.home_now_playing) else stringResource(R.string.home_start_listening), style = MiuixTheme.textStyles.title4, color = scheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(player.title, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(player.artist, style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // 没有当前曲目时，这个按钮要真的开始放歌（之前是空操作）；
                    // 已有曲目时它相当于stringResource(R.string.home_open_player)。
                    FilledIconButton(
                        onClick = onStartListening,
                        enabled = songs.isNotEmpty() || player.id != null,
                    ) {
                        Icon(
                            if (player.id == null) Icons.Rounded.PlayArrow else Icons.Rounded.GraphicEq,
                            if (player.id == null) stringResource(R.string.home_start_play) else stringResource(R.string.home_open_player),
                        )
                    }
                }
                player.error?.let { LiquidStatusBanner(text = stringResource(R.string.player_error_banner, it), icon = Icons.Rounded.ErrorOutline, color = scheme.error) }
            }
        }
        item {
            LiquidCard(modifier = Modifier.staggeredEntry(1)) {
                Text(stringResource(R.string.lib_overview), style = MiuixTheme.textStyles.title4)
                Row(horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.item)) {
                    Stat(stringResource(R.string.stat_all), songs.size.toString(), Modifier.weight(1f))
                    Stat(stringResource(R.string.stat_lossless), lossless.toString(), Modifier.weight(1f))
                    Stat(stringResource(R.string.stat_hires), hiRes.toString(), Modifier.weight(1f))
                }
                // 我喜欢的音乐：这里给入口，收藏动作在曲库列表和播放页的心形按钮上
                LiquidListItem(
                    title = stringResource(R.string.settings_favorites),
                    subtitle = if (favoriteCount > 0) stringResource(R.string.fav_count_tap, favoriteCount) else stringResource(R.string.fav_none),
                    leading = Icons.Rounded.Favorite,
                    onClick = onOpenFavorites,
                    showDivider = true,
                )
                LiquidListItem(title = stringResource(R.string.scan_storage), subtitle = stringResource(R.string.scan_storage_sub), leading = Icons.Rounded.Refresh, onClick = onScan)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recent_added), style = MiuixTheme.textStyles.title4, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenLibrary) { Text(stringResource(R.string.all)) }
            }
        }
        if (songs.isEmpty()) {
            item { LiquidEmptyState(stringResource(R.string.lib_empty), hint = stringResource(R.string.lib_empty_hint)) }
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
    onAddToPlaylist: (Song) -> Unit = {},
    header: (@Composable () -> Unit)? = null,
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
    val playlistState = rememberUpdatedState(onAddToPlaylist)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LiquidSpacing.page, end = LiquidSpacing.page, top = topPadding, bottom = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
        verticalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
    ) {
        // 乐单详情等场景要在这张网格上方插一块自己的卡片（改名 / 换封面 / 删除）
        header?.let { h ->
            item(span = { GridItemSpan(maxLineSpan) }) { h() }
        }
        if (songs.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LiquidEmptyState(
                    when {
                        favoritesOnly -> stringResource(R.string.empty_favorites)
                        filtering -> stringResource(R.string.empty_no_match)
                        else -> stringResource(R.string.empty_library)
                    },
                    hint = if (favoritesOnly) stringResource(R.string.empty_favorites_hint) else stringResource(R.string.empty_library_hint),
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
                    playlistState = playlistState,
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
    playlistState: State<(Song) -> Unit>,
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
                // 左上角：加入乐单（和右上角的心形成一对，操作逻辑一致 —— 点一下即加/去）
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(32.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.34f))
                        .clickable { playlistState.value(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.QueueMusic, stringResource(R.string.pl_add_title), tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.34f))
                        .clickable { favoriteState.value(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (favorite) stringResource(R.string.player_unfavorite) else stringResource(R.string.player_favorite),
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
                    Icon(Icons.Rounded.Add, stringResource(R.string.card_add_queue), modifier = Modifier.size(20.dp), tint = Color.White)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.34f))
                        .clickable { playState.value(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, stringResource(R.string.player_play), modifier = Modifier.size(20.dp), tint = Color.White)
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
internal fun DotMatrixMark(modifier: Modifier = Modifier, cell: Dp = 4.dp, accent: Boolean = true) {
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
                // 红点：m 右上角那颗竖的顶端（第 2 行、最右列）。
                // ⚠️ 不能写成第 1 行 —— 那行只有 L 的竖（第 0 列），条件永远不成立，
                // 红点就一直画不出来（这是真实存在过的 bug）。
                val isAccent = accent && lit && y == 2 && x == rows.first().length - 1
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

/**
 * 图标用的点阵字形（方案 A：7 格大写 L + 5 格小写 m）。
 *
 * ⚠️ 基线必须齐：L 的横（第 6 行）和 m 的最后一排竖（第 6 行）要落在**同一行**上。
 * 之前手写这份表时把 m 放在第 1~4 行，底部比 L 高两行，看上去像"m 飘在半空"。
 */
internal val DOT_MARK_ROWS = listOf(
    "X..........",
    "X..........",
    "X.....X.X.X",
    "X.....XXXXX",
    "X.....X.X.X",
    "X.....X.X.X",
    "XXXXX.X.X.X",
)

/** 设置：USB 直通、存储授权、ncm 自动转换、扫描诊断。 */
@Composable
fun SettingsPage(
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
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
    onOpenAbout: () -> Unit = {},
    onOpenLanguage: () -> Unit = {},
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
                Text(stringResource(R.string.settings_audio_output), style = MiuixTheme.textStyles.title4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("USB Bit-perfect", style = MiuixTheme.textStyles.title4)
                        Text(stringResource(R.string.set_usb_note),
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = bitPerfect, onCheckedChange = onBitPerfect)
                }
                LiquidStatusBanner(text = diagnostics, icon = Icons.Rounded.Headphones, color = scheme.primary)
                // 均衡器入口：面板本身是液态玻璃浮层（见 EqualizerSheet），这里只做入口与状态摘要
                LiquidListItem(
                    title = stringResource(R.string.settings_equalizer),
                    subtitle = when {
                        !eq.available -> eq.message.ifBlank { stringResource(R.string.settings_eq_unavailable) }
                        eq.bypassed -> stringResource(R.string.settings_eq_bypassed)
                        eq.enabled -> "已启用 · " + eq.bands.joinToString(" / ") { "${"%.0f".format(it.levelMb / 100f)}" } + " dB"
                        else -> stringResource(R.string.settings_eq_off)
                    },
                    leading = Icons.Rounded.GraphicEq,
                    onClick = onOpenEq,
                )
            }
        }
        item {
            LiquidCard(modifier = Modifier.wheelCursor(wheelIndex in 2..4, scheme.primary)) {
                Text(stringResource(R.string.settings_ncm_import), style = MiuixTheme.textStyles.title4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_auto_convert2), style = MiuixTheme.textStyles.title4)
                        Text(stringResource(R.string.set_ncm_auto_long),
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = autoNcm, onCheckedChange = onAutoNcm)
                }
                TextButton(onClick = onPickTree) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.set_add_folder)) }
                LiquidListItem(
                    title = stringResource(R.string.settings_export_dir),
                    subtitle = exportLabel?.let { stringResource(R.string.set_export_at, it) }
                        ?: stringResource(R.string.settings_export_none),
                    leading = Icons.Rounded.DriveFileMove,
                    onClick = onPickExport,
                    showDivider = true,
                )
                if (trees.isEmpty()) {
                    Text(stringResource(R.string.set_no_tree_a) +
                        stringResource(R.string.set_no_tree_b) +
                        stringResource(R.string.set_no_tree_c),
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                } else trees.forEach { tree ->
                    LiquidListItem(
                        title = treeLabel(tree),
                        subtitle = stringResource(R.string.settings_tree_persisted),
                        leading = Icons.Rounded.Folder,
                        showDivider = true,
                        trailing = { TextButton(onClick = { onRemoveTree(tree) }) { Text(stringResource(R.string.settings_remove)) } },
                    )
                }
            }
        }
        item {
            LiquidCard(modifier = Modifier.wheelCursor(wheelIndex in 5..9, scheme.primary)) {
                Text(stringResource(R.string.settings_scrape), style = MiuixTheme.textStyles.title4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_scrape_auto), style = MiuixTheme.textStyles.title4)
                        Text(stringResource(R.string.set_scrape_note),
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = autoScrape, onCheckedChange = onAutoScrape)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_scrape_cover), style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Switch(checked = scrapeCover, onCheckedChange = onScrapeCover, enabled = autoScrape)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_scrape_lyrics), style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Switch(checked = scrapeLyrics, onCheckedChange = onScrapeLyrics, enabled = autoScrape)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_netease), style = MiuixTheme.textStyles.title4)
                        Text(stringResource(R.string.set_netease_note),
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = neteaseSource, onCheckedChange = onNeteaseSource)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(scrapeStatus.message, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onScrapeNow, enabled = !scrapeStatus.running) { Text(if (scrapeStatus.running) stringResource(R.string.settings_scraping) else stringResource(R.string.settings_scrape_now)) }
                }
                // 之前刮削把歌曲自带的封面盖掉过？这里一键把原封面放回来（用户自己设的封面不动）
                LiquidListItem(
                    title = stringResource(R.string.settings_restore_covers),
                    subtitle = stringResource(R.string.set_restore_covers_sub),
                    leading = Icons.Rounded.Restore,
                    onClick = onRestoreCovers,
                )
                LiquidListItem(
                    title = stringResource(R.string.settings_clear_scraped),
                    subtitle = stringResource(R.string.set_clear_scraped_sub),
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
                Text(stringResource(R.string.settings_wheel_tint), style = MiuixTheme.textStyles.title4)
                Text(stringResource(R.string.set_tint_note),
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
                            Text(stringResource(tint.nameRes), style = MiuixTheme.textStyles.footnote1,
                                color = if (index == tintState.index) scheme.primary else scheme.onSurfaceVariantSummary)
                        }
                    }
                }
                // 调色盘：色相 / 饱和度 / 透光率。拖动任意一个即进入"自定义"，实时生效
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_hue), style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Text("${tintState.hue.toInt()}°", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                }
                Slider(
                    value = tintState.hue,
                    onValueChange = { onGlassTint(tintState.copy(index = -1, hue = it)) },
                    valueRange = 0f..360f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_saturation), style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Text("${(tintState.sat * 100).toInt()}%", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                }
                Slider(
                    value = tintState.sat,
                    onValueChange = { onGlassTint(tintState.copy(index = -1, sat = it)) },
                    valueRange = 0f..1f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_transmission), style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
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
                        stringResource(R.string.set_tint_current, stringResource(tintState.labelRes)) + if (tintState.index < 0) stringResource(R.string.set_tint_detail, tintState.hue.toInt(), (tintState.sat * 100).toInt(), (tintState.level * 100).toInt()) else "",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item {
            LiquidCard(modifier = Modifier.wheelCursor(wheelIndex == 11, scheme.primary)) {
                Text("曲库", style = MiuixTheme.textStyles.title4)
                LiquidListItem(title = stringResource(R.string.settings_rescan), subtitle = status.message, leading = Icons.Rounded.Refresh, onClick = onScan)
                if (status.running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(status.message, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.scan_stop)) }
                    }
                }
                status.failures.forEach { LiquidStatusBanner(text = it, icon = Icons.Rounded.WarningAmber, color = scheme.error) }
            }
        }
        item {
            // 「关于」在设置里只留一个入口，点进去是独立页面（见 PlayerOverlay.kt 的 AboutPage）——
            // 之前把 logo/版本/动作行/分组/二维码全平铺在这里，设置页被撑得很长。
            LiquidCard {
                LiquidListItem(
                    title = stringResource(R.string.about_entry_title),
                    subtitle = stringResource(R.string.about_entry_subtitle),
                    leading = Icons.Rounded.Info,
                    onClick = onOpenAbout,
                )
                // 语言：默认英文（res/values 就是英文），中文在 values-zh；
                // 点一下在 跟随系统 -> English -> 简体中文 之间循环。
                val langCtx = LocalContext.current
                LiquidListItem(
                    title = "语言 / Language",
                    subtitle = com.localmusic.app.data.LanguagePref.label(langCtx) + stringResource(R.string.settings_lang_tap),
                    leading = Icons.Rounded.Settings,
                    onClick = onOpenLanguage,
                )
            }
        }
    }
}

internal const val REPO_URL = "https://github.com/chengxixian/local-music"
internal const val AURALIS_URL = "https://github.com/Rueded/AURALIS"
internal const val LIQUID_URL = "https://github.com/chengxixian/liquid-miuix"
internal const val NCM_URL = "https://github.com/taurusxin/ncmdump"

/** 用外部浏览器打开链接（失败就静默，不弹崩溃）。 */
internal fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 关于页的动作按钮：图标 + 文字，竖排，点击有涟漪。 */
@Composable
internal fun AboutAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MiuixTheme.textStyles.body2, color = tint)
    }
}

/**
 * 通用**液态玻璃卡片**（用于乐单详情工具栏与「加入乐单」面板）。
 *
 * ⚠️ 只能画在**浮层**：`liquidGlass` 采样采集层，玻璃自己若在采集层里就是自引用 backdrop
 * （真机上渲染异常甚至崩溃）。玻璃 Box 与内容必须是兄弟，玻璃里不能放子内容。
 */
@Composable
fun GlassCard(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    contentPadding: androidx.compose.ui.unit.Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Box(Modifier.matchParentSize().liquidGlass(backdrop = backdrop, shape = shape))
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 乐单详情页顶部的卡片：乐单名 + 改名 / 换封面 / 删除。
 * 改名对话框由它自己管（只影响这一页，不必上升到 MainActivity）。
 */
@Composable
fun PlaylistHeader(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    playlist: Playlist,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onMenu: () -> Unit,
) {
    GlassCard(backdrop = backdrop, modifier = modifier, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 返回：从乐单详情回乐单列表（系统返回键也能用，但页面上得有看得见的入口）
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.pl_back), tint = MiuixTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(6.dp))
            if (playlist.cover != null) {
                FileImage(path = playlist.cover, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                DotMatrixMark(Modifier.size(44.dp, 44.dp), cell = 3.6.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.pl_count, playlist.count), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            // 重命名 / 换封面 / 删除 收进这一个按钮里（原来是三行列表，太占地方）
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.pl_settings), tint = MiuixTheme.colorScheme.onSurface)
            }
        }
    }
}

/** 乐单操作面板（重命名 / 换封面 / 删除）。 */
@Composable
fun PlaylistActionsSheet(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    playlist: Playlist,
    onRename: () -> Unit,
    onChangeCover: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(0.82f)) {
            Text(playlist.name, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            LiquidListItem(title = stringResource(R.string.pl_rename), leading = Icons.Rounded.CreateNewFolder, onClick = onRename, showDivider = true)
            LiquidListItem(title = stringResource(R.string.pl_change_cover), leading = Icons.Rounded.AddPhotoAlternate, onClick = onChangeCover, showDivider = true)
            LiquidListItem(title = stringResource(R.string.pl_delete), subtitle = stringResource(R.string.pl_delete_sub), leading = Icons.Rounded.DeleteOutline, onClick = onDelete)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                ) { Text(stringResource(R.string.pl_cancel)) }
            }
        }
    }
}

/** 从本地路径读一张图（乐单封面 / 用户自选封面）。分离到 IO 线程，避免卡首帧。 */
@Composable
internal fun FileImage(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            path?.let { p ->
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(p)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    bitmap?.let {
        Image(bitmap = it, contentDescription = null, contentScale = contentScale, modifier = modifier)
    } ?: Box(modifier.background(MiuixTheme.colorScheme.surfaceVariant))
}

/**
 * 乐单页：列出所有乐单。每张卡片左边封面（用户自选图，没设就画点阵标记）、
 * 右边名称与曲目数，点击进入乐单详情。顶部一行是「新建乐单」。
 */
@Composable
fun PlaylistPage(
    playlists: List<Playlist>,
    firstSongs: Map<Long, Song> = emptyMap(),
    topPadding: Dp = LiquidSpacing.page,
    onCreate: (String) -> Unit,
    onOpen: (Playlist) -> Unit,
    onAskName: (String, String, (String) -> Unit) -> Unit = { _, _, _ -> },
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LiquidSpacing.page, end = LiquidSpacing.page, top = topPadding, bottom = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
        verticalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // onClick 是普通 lambda，不能在里面调 stringResource，先在组合作用域取好
            val plNewLabel = stringResource(R.string.pl_new)
            LiquidListItem(
                title = stringResource(R.string.pl_new),
                subtitle = stringResource(R.string.pl_new_sub),
                leading = Icons.Rounded.Add,
                onClick = { onAskName(plNewLabel, "") { name -> onCreate(name) } },
            )
        }
        if (playlists.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LiquidCard {
                    Text(stringResource(R.string.pl_empty), style = MiuixTheme.textStyles.title4)
                    Text(
                        stringResource(R.string.pl_empty_hint),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        // 与曲库同款：两列竖卡片（上半正方形封面、下半名称与曲目数）
        items(playlists, key = { it.id }) { playlist ->
            PlaylistGridCard(playlist = playlist, firstSong = firstSongs[playlist.id], onClick = { onOpen(playlist) })
        }
    }
}

/**
 * 乐单卡片（和曲库的乐曲卡片同款竖长方形）。
 *
 * 封面优先级：**用户自选的封面** > **乐单里第一首歌的封面** > 点阵标记兜底。
 */
@Composable
private fun PlaylistGridCard(playlist: Playlist, firstSong: Song?, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                when {
                    playlist.cover != null -> FileImage(path = playlist.cover, modifier = Modifier.matchParentSize())
                    firstSong != null -> Artwork(firstSong, Modifier.matchParentSize(), radius = 0, requestPx = 420)
                    else -> Box(Modifier.matchParentSize().background(scheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        DotMatrixMark(Modifier.size(120.dp, 78.dp), cell = 9.6.dp)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(playlist.name, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.pl_count, playlist.count),
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 改名 / 新建通用的小输入框（玻璃版，画在浮层里，见 MainActivity 的 NameDialogGlass）。
 * 这里只负责"问名字"这件事，对话框本体在浮层渲染 —— 页面在采集层内，滚轮会盖在它上面。
 */
@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // 兜底实现：万一有人直接调用，仍然能工作（不带玻璃）
    var text by remember { mutableStateOf(initial) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        LiquidCard(Modifier.clickable(enabled = false) {}) {
            Text(title, style = MiuixTheme.textStyles.title4)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                ) { Text(stringResource(R.string.pl_cancel)) }
                TextButton(
                    onClick = { onConfirm(text) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MiuixTheme.colorScheme.primary),
                ) { Text(stringResource(R.string.pl_confirm)) }
            }
        }
    }
}

/**
 * 「加入乐单」面板：列出所有乐单 + 勾选状态，下面能直接新建一个。
 * 交互和"喜欢"一致：点一下就是加/去，不需要再确认。
 */
@Composable
fun AddToPlaylistSheet(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    song: Song,
    playlists: List<Playlist>,
    memberOf: Set<Long>,
    onToggle: (Long, Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    onAskName: (String, String, (String) -> Unit) -> Unit = { _, _, _ -> },
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(0.88f)) {
            Text(stringResource(R.string.pl_add_title), style = MiuixTheme.textStyles.title4)
            Text(
                song.title,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            // onClick 是普通 lambda，不能在里面调 stringResource，先在组合作用域取好
            val plNewLabel = stringResource(R.string.pl_new)
            LiquidListItem(
                title = stringResource(R.string.pl_add_new),
                leading = Icons.Rounded.Add,
                onClick = { onAskName(plNewLabel, "") { name -> onCreate(name) } },
            )
            playlists.forEach { playlist ->
                val checked = playlist.id in memberOf
                LiquidListItem(
                    title = playlist.name,
                    subtitle = stringResource(R.string.pl_count, playlist.count),
                    leading = if (checked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    onClick = { onToggle(playlist.id, !checked) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = MiuixTheme.colorScheme.primary),
                ) { Text(stringResource(R.string.pl_done)) }
            }
        }
    }
}

/**
 * **关于页**：设置里只留一个入口，点进来是这个独立页面（照 Lawnchair 关于页的排布）。
 *
 * 为什么独立成页：logo + 版本 + 动作行 + 三组清单全平铺在设置页里，会把设置撑得很长。
 * 捐赠也不再平铺二维码 —— 点「捐赠」才弹出，避免页面上一直挂着一张收款码。
 */
@Composable
fun AboutPage(
    onPlayAnimation: () -> Unit = {},onBack: () -> Unit) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
    var showDonate by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 底部留白只给迷你播放条（关于页没有 dock 了，原来按 dock 高度留 120dp 会空一大截）
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LiquidCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.pl_back_about)) }
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.about_title), style = MiuixTheme.textStyles.title3)
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        DotMatrixMark(Modifier.size(168.dp, 108.dp).clickable { onPlayAnimation() }, cell = 13.4.dp)
                        Spacer(Modifier.height(2.dp))
                        Text("local music", style = MiuixTheme.textStyles.title3)
                        Text(
                            stringResource(R.string.about_tagline_versioned, version),
                            style = MiuixTheme.textStyles.body2,
                            color = scheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                                                        AboutAction(Icons.Rounded.Code, stringResource(R.string.about_action_source), scheme.onSurface) { openUrl(context, REPO_URL) }
                        AboutAction(Icons.Rounded.SystemUpdate, stringResource(R.string.about_action_update), scheme.onSurface) {
                            scope.launch { com.localmusic.app.data.UpdateChecker.check(context, silent = false) }
                        }
                        AboutAction(Icons.Rounded.Description, stringResource(R.string.about_action_readme), scheme.onSurface) { openUrl(context, "$REPO_URL#readme") }
                        // 捐赠用「手托爱心」，不用爱心本身（爱心在本 App 里是「喜欢」的意思）
                        AboutAction(Icons.Rounded.VolunteerActivism, stringResource(R.string.about_action_donate), scheme.onSurface) { showDonate = true }
                    }
                }
            }
            item {
                LiquidCard {
                    Text(stringResource(R.string.about_group_product), style = MiuixTheme.textStyles.title4)
                    LiquidListItem(
                        title = stringResource(R.string.about_kernel_title),
                        subtitle = stringResource(R.string.about_kernel_subtitle),
                        leading = Icons.Rounded.GraphicEq,
                        onClick = { openUrl(context, AURALIS_URL) },
                        showDivider = true,
                    )
                    LiquidListItem(
                        title = stringResource(R.string.about_ui_title),
                        subtitle = stringResource(R.string.about_ui_subtitle),
                        leading = Icons.Rounded.Layers,
                        onClick = { openUrl(context, LIQUID_URL) },
                        showDivider = true,
                    )
                    LiquidListItem(
                        title = stringResource(R.string.about_ncm_title),
                        subtitle = stringResource(R.string.about_ncm_subtitle),
                        leading = Icons.Rounded.CloudDownload,
                        onClick = { openUrl(context, NCM_URL) },
                    )
                }
            }
            item {
                LiquidCard {
                    Text(stringResource(R.string.about_group_community), style = MiuixTheme.textStyles.title4)
                    LiquidListItem(
                        title = stringResource(R.string.about_repo_title),
                        subtitle = "chengxixian/local-music",
                        leading = Icons.Rounded.Code,
                        onClick = { openUrl(context, REPO_URL) },
                        showDivider = true,
                    )
                    LiquidListItem(
                        title = stringResource(R.string.about_releases_title),
                        subtitle = stringResource(R.string.about_releases_subtitle),
                        leading = Icons.Rounded.NewReleases,
                        onClick = { openUrl(context, "$REPO_URL/releases") },
                    )
                }
            }
            item {
                LiquidCard {
                    Text(stringResource(R.string.about_group_legal), style = MiuixTheme.textStyles.title4)
                    LiquidListItem(
                        title = stringResource(R.string.about_license_title),
                        subtitle = stringResource(R.string.about_license_subtitle),
                        leading = Icons.Rounded.Gavel,
                        onClick = { openUrl(context, "$REPO_URL/blob/main/LICENSE") },
                    )
                }
            }
        }

        // 捐赠：点一下才弹出（点背景关闭），不再一直挂在页面上
        if (showDonate) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { showDonate = false },
                contentAlignment = Alignment.Center,
            ) {
                LiquidCard {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.donate_title), style = MiuixTheme.textStyles.title4)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.donate_subtitle), style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(10.dp))
                        Image(
                            painter = painterResource(R.drawable.donate_alipay),
                            contentDescription = stringResource(R.string.donate_qr_desc),
                            modifier = Modifier.fillMaxWidth(0.72f).clip(RoundedCornerShape(14.dp)),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.donate_hint), style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { showDonate = false }) { Text(stringResource(R.string.donate_close)) }
                    }
                }
            }
        }
    }
}
/**
 * 语言选择弹窗（**液态玻璃**）。
 *
 * 只能从浮层调用：liquidGlass 采样采集层，设置页在采集层内部，玻璃画在那里会自引用。
 * 所以设置页只负责"通知外层打开"，弹窗由 MainActivity 在浮层渲染。
 */
@Composable
fun LanguageDialog(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    currentTag: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(0.84f)) {
            Text("语言 / Language", style = MiuixTheme.textStyles.title4)
            Spacer(Modifier.height(8.dp))
            com.localmusic.app.data.LanguagePref.options.forEach { (tag, label) ->
                LiquidListItem(
                    title = label,
                    subtitle = if (tag == currentTag) "当前 / Current" else null,
                    leading = if (tag == currentTag) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    onClick = { onPick(tag) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                ) { Text(stringResource(R.string.pl_cancel)) }
            }
        }
    }
}