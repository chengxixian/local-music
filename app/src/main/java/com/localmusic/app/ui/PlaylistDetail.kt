// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmiuix.glass.liquidGlass
import com.liquidmiuix.ui.staggeredEntry
import com.localmusic.app.R
import com.localmusic.app.data.Playlist
import com.localmusic.app.data.Song
import com.localmusic.app.data.formatTime
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * **乐单详情页**（0.9.1 重做）。
 *
 * 原来是"曲库网格 + 顶上压一张工具卡"，和曲库长得一模一样，只是多了一行字。现在是一张独立的页：
 *  - 头图：大封面（从乐单卡片的封面**飞**过来，见 [ZoomHost] 的共享元素）、名字、曲目数与总时长；
 *    向上滚动时封面视差上移、缩小、淡出，顶部玻璃栏同步浮出乐单名。
 *  - 「播放全部」「随机播放」两个主按钮。
 *  - 歌曲改成**列表行**：长按整行拖动排序（写回数据库），左滑移出乐单（4 秒内可撤销）。
 *
 * 内容画在采集层里（顶部玻璃栏要折射它），玻璃栏本身见 [PlaylistDetailBar]。
 */
@Composable
fun PlaylistDetailPage(
    playlist: Playlist,
    songs: List<Song>,
    nowPlaying: String?,
    playing: Boolean,
    favorites: Set<String>,
    cover: ImageBitmap?,
    zoom: ZoomState?,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp,
    accent: Color,
    onPlay: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onReorder: (List<String>) -> Unit,
    onRemove: (Song) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val rows = remember(playlist.id) { mutableStateListOf<Song>() }
    val reorder = rememberReorderState(
        listState,
        movable = { it is String },
        onMove = { from, to ->
            val f = rows.indexOfFirst { it.uri == from }
            val t = rows.indexOfFirst { it.uri == to }
            if (f >= 0 && t >= 0) rows.add(t, rows.removeAt(f))
        },
        onDrop = { onReorder(rows.map { it.uri }) },
    )
    // 只有上游给出新的列表引用才重建；松手后旧的 songs 不会把刚排好的顺序冲回去
    var lastApplied by remember(playlist.id) { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(songs, reorder.draggingKey) {
        if (reorder.draggingKey != null) return@LaunchedEffect
        if (songs === lastApplied) return@LaunchedEffect
        lastApplied = songs
        if (songs.map { it.uri } != rows.map { it.uri }) { rows.clear(); rows.addAll(songs) }
    }
    val totalMs = remember(songs) { songs.sumOf { it.duration.coerceAtLeast(0) } }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = bottomInset),
    ) {
        item(key = HeroKey, contentType = "hero") {
            Column(
                Modifier.fillMaxWidth().padding(top = topInset + 72.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 视差：头图随滚动以 0.45 倍速上移、缩到 0.86、淡出（只在图层里读滚动，不重组）
                Box(
                    Modifier
                        .size(224.dp)
                        .graphicsLayer {
                            val off = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else size.height * 2
                            val t = (off / (size.height * 1.2f)).coerceIn(0f, 1f)
                            translationY = off * 0.45f
                            val s = 1f - 0.14f * t
                            scaleX = s; scaleY = s
                            alpha = 1f - t
                            shadowElevation = 18.dp.toPx() * (1f - t)
                            shape = RoundedCornerShape(28.dp)
                            clip = true
                        }
                        .zoomShared(zoom),
                ) {
                    when {
                        cover != null -> Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        playlist.cover != null -> FileImage(playlist.cover, Modifier.fillMaxSize())
                        else -> Box(Modifier.fillMaxSize().background(scheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            DotMatrixMark(Modifier.size(150.dp, 97.dp), cell = 12.dp)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    playlist.name, style = MiuixTheme.textStyles.title1, color = scheme.onSurface,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.pl_summary, songs.size, formatLong(totalMs)),
                    style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PillButton(
                        icon = Icons.Rounded.PlayArrow, label = stringResource(R.string.pl_play_all),
                        container = accent, content = if (accent.luminanceF() > 0.55f) Color.Black else Color.White,
                        enabled = songs.isNotEmpty(), onClick = onPlayAll, modifier = Modifier.weight(1f),
                    )
                    PillButton(
                        icon = Icons.Rounded.Shuffle, label = stringResource(R.string.pl_shuffle_play),
                        container = accent.copy(alpha = 0.14f), content = accent,
                        enabled = songs.isNotEmpty(), onClick = onShuffleAll, modifier = Modifier.weight(1f),
                    )
                }
                if (songs.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.pl_reorder_hint), style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary.copy(alpha = 0.75f))
                }
            }
        }
        if (rows.isEmpty() && songs.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.pl_songs_empty), style = MiuixTheme.textStyles.title4, color = scheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.pl_songs_empty_hint), style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        }
        itemsIndexed(rows, key = { _, s -> s.uri }) { index, song ->
            Box(
                Modifier
                    // 被拖的行不套 animateItem，否则放置动画会和手指位移叠加、每次交换跳一行
                    .then(if (reorder.isLifted(song.uri)) Modifier else Modifier.animateItem())
                    .reorderLift(reorder, song.uri)
                    .then(if (index < 12) Modifier.staggeredEntry(index) else Modifier),
            ) {
                SwipeToRemove(enabled = reorder.draggingKey == null, onRemove = {
                    rows.remove(song)
                    onRemove(song)
                }) {
                    PlaylistSongRow(
                        index = index,
                        song = song,
                        current = song.uri == nowPlaying,
                        playing = playing,
                        favorite = favorites.contains(song.uri),
                        accent = accent,
                        lifted = reorder.draggingKey == song.uri,
                        modifier = Modifier.reorderLongPress(reorder, song.uri),
                        onClick = { onPlay(song) },
                        onFavorite = { onToggleFavorite(song) },
                        onQueue = { onAddToQueue(song) },
                    )
                }
            }
        }
    }
}

private const val HeroKey = 0L

@Composable
private fun PlaylistSongRow(
    index: Int,
    song: Song,
    current: Boolean,
    playing: Boolean,
    favorite: Boolean,
    accent: Color,
    lifted: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onQueue: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val bg by animateColorAsState(
        when { lifted -> scheme.surfaceContainerHigh; current -> accent.copy(alpha = 0.10f); else -> scheme.surface.copy(alpha = 0f) },
        Motion.look(), label = "plRowBg",
    )
    val titleColor by animateColorAsState(if (current) accent else scheme.onSurface, Motion.look(), label = "plRowTitle")
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            // 播放页可以从这一行"长"出来，关闭时也缩回这一行
            .zoomSource("plrow:${song.uri}")
            .elasticPress(source, sink = true, sinkAmount = 0.02f, pull = false)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1}", style = MiuixTheme.textStyles.footnote1,
            color = scheme.onSurfaceVariantSummary, textAlign = TextAlign.Center, modifier = Modifier.width(26.dp),
        )
        Box(Modifier.size(50.dp).zoomSourceCover("plrow:${song.uri}"), contentAlignment = Alignment.Center) {
            Artwork(song, Modifier.size(50.dp), radius = 12, requestPx = 160)
            if (current) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.42f)))
                NowPlayingBars(playing = playing, color = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MiuixTheme.textStyles.body1, color = titleColor,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(song.artist, song.spec).filter { it.isNotBlank() }.joinToString(" · "),
                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariantSummary) {
            MotionIconButton(onClick = onFavorite, size = 42.dp) {
                MorphIcon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(if (favorite) R.string.player_unfavorite else R.string.player_favorite),
                    Modifier.size(20.dp),
                    tint = if (favorite) (if (com.localmusic.app.data.UiSettings.skin == com.localmusic.app.data.UiSkin.NOTHING) Color(0xFFE1191F) else accent) else scheme.onSurfaceVariantSummary,
                )
            }
            MotionIconButton(onClick = onQueue, size = 42.dp) {
                Icon(Icons.Rounded.Add, stringResource(R.string.card_add_queue), Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(if (enabled) container else container.copy(alpha = container.alpha * 0.4f), Motion.look(), label = "pillBg")
    Row(
        modifier
            .height(50.dp)
            .springClickable(enabled = enabled, growth = 8.dp, maxScale = 0.06f, onClick = onClick)
            .clip(RoundedCornerShape(25.dp))
            .background(bg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = content, style = MiuixTheme.textStyles.title4)
    }
}

/**
 * 乐单详情的**玻璃顶栏**（浮层）：左返回、右菜单；中间的乐单名在头图滚出去之后才浮现。
 * 它跟着转场容器一起缩放淡入（[zoomFollow]），不会在页面还没展开时就先挂在屏幕顶上。
 */
@Composable
fun PlaylistDetailBar(
    backdrop: LayerBackdrop?,
    title: String,
    listState: LazyListState,
    zoom: ZoomState,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val collapsed by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 }
    }
    val titleAlpha by androidx.compose.animation.core.animateFloatAsState(if (collapsed) 1f else 0f, Motion.look(), label = "barTitle")
    Row(
        modifier.zoomFollow(zoom).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(backdrop, Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.pl_back), onBack, size = 48.dp)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(48.dp), contentAlignment = Alignment.Center) {
            // 标题胶囊：头图还在时完全透明，滚出去后淡入 + 从下方浮起
            GlassPanel(
                backdrop = backdrop,
                shape = RoundedCornerShape(24.dp),
                refractionHeight = 10.dp,
                refractionAmount = 16.dp,
                modifier = Modifier.graphicsLayer {
                    alpha = titleAlpha
                    translationY = (1f - titleAlpha) * 10.dp.toPx()
                },
            ) {
                Text(
                    title, style = MiuixTheme.textStyles.title4, color = scheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
        GlassIconButton(backdrop, Icons.Rounded.MoreVert, stringResource(R.string.pl_settings), onMenu, size = 48.dp)
    }
}

private fun Color.luminanceF(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun formatLong(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else formatTime(ms)
}
