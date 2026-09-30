// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** 曲库：搜索 + 全量列表 + 扫描状态。 */
@Composable
fun LibraryPage(
    songs: List<Song>,
    status: ScanStatus,
    nowPlaying: String?,
    favorites: Set<String>,
    showFavorites: Boolean,
    onShowFavorites: (Boolean) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onPlay: (Song) -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    topPadding: Dp = LiquidSpacing.page,
) {
    val scheme = MiuixTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val favoriteSongs = remember(songs, favorites) { songs.filter { favorites.contains(it.uri) } }
    val base = if (showFavorites) favoriteSongs else songs
    val filtered = remember(base, query) {
        if (query.isBlank()) base else base.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
    }
    // 两列网格：每格是一张竖长方形卡片 —— 上半正方形封面铺满，下半放歌名与信息
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LiquidSpacing.page, end = LiquidSpacing.page, top = topPadding, bottom = 200.dp),
        horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
        verticalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LiquidCard {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("搜索标题 / 艺术家 / 专辑") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !showFavorites, onClick = { onShowFavorites(false) }, label = { Text("全部 ${songs.size}") })
                    FilterChip(
                        selected = showFavorites,
                        onClick = { onShowFavorites(true) },
                        label = { Text("我喜欢的 ${favoriteSongs.size}") },
                        leadingIcon = { Icon(Icons.Rounded.Favorite, null, Modifier.size(16.dp)) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline), verticalAlignment = Alignment.CenterVertically) {
                    if (status.running) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(status.message, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("停止") }
                    } else {
                        Text(status.message, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                        TextButton(onClick = onScan) { Text("重新扫描") }
                    }
                }
                if (status.failures.isNotEmpty()) {
                    LiquidStatusBanner(text = status.failures.last(), icon = Icons.Rounded.WarningAmber, color = scheme.error)
                }
            }
        }
        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LiquidEmptyState(
                    if (showFavorites) "我喜欢的音乐还是空的" else if (query.isBlank()) "曲库为空" else "没有匹配的歌曲",
                    hint = if (showFavorites) "在曲库列表或播放页点心形按钮加入" else "支持 mp3 / aac(m4a) / flac / wav / ogg / opus 等",
                )
            }
        } else {
            items(filtered, key = { it.uri }) { song ->
                LibraryGridCard(
                    song = song,
                    favorite = favorites.contains(song.uri),
                    playing = song.uri == nowPlaying,
                    onPlay = { onPlay(song) },
                    onToggleFavorite = { onToggleFavorite(song) },
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
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Card(
        onClick = onPlay,
        modifier = Modifier.fillMaxWidth(),
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
                        .clickable(onClick = onToggleFavorite),
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
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.34f))
                        .clickable(onClick = onPlay),
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
    exportLabel: String? = null,
    onPickExport: () -> Unit = {},
    eq: com.localmusic.audio.playback.EqState = com.localmusic.audio.playback.EqState(),
    onOpenEq: () -> Unit = {},
    topPadding: Dp = LiquidSpacing.page,
) {
    val scheme = MiuixTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LiquidSpacing.page, end = LiquidSpacing.page, top = topPadding, bottom = 200.dp),
        verticalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
    ) {
        item {
            LiquidCard {
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
            LiquidCard {
                Text("网易云 ncm 导入", style = MiuixTheme.textStyles.title4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动转换 ncm → FLAC", style = MiuixTheme.textStyles.title4)
                        Text("扫描授权文件夹时自动转换，输出到应用私有 files/music/ncm，原文件保留。",
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = autoNcm, onCheckedChange = onAutoNcm)
                }
                TextButton(onClick = onPickTree) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("添加音乐 / ncm 文件夹") }
                LiquidListItem(
                    title = "FLAC 导出位置",
                    subtitle = exportLabel?.let { "$it（导出到这里，卸载应用也不会删）" }
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
            LiquidCard {
                Text("曲库", style = MiuixTheme.textStyles.title4)
                LiquidListItem(title = "立即扫描", subtitle = status.message, leading = Icons.Rounded.Refresh, onClick = onScan)
                status.failures.forEach { LiquidStatusBanner(text = it, icon = Icons.Rounded.WarningAmber, color = scheme.error) }
            }
        }
        item {
            LiquidCard {
                Text("关于", style = MiuixTheme.textStyles.title4)
                Text("local music", style = MiuixTheme.textStyles.title4)
                Text("播放内核移植自 Rueded/AURALIS（GPLv3），前端使用 chengxixian/liquid-miuix，ncm 解码移植自 taurusxin/ncmdump。",
                    style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
            }
        }
    }
}
