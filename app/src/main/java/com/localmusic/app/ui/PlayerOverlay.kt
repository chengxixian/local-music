// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.localmusic.app.R
import com.localmusic.app.PlaybackUi
import com.localmusic.app.data.LyricsRepository
import com.localmusic.app.data.Song
import com.localmusic.app.data.formatTime
import com.liquidmiuix.glass.liquidGlass
import com.liquidmiuix.theme.LiquidSpacing
import com.liquidmiuix.ui.LiquidPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 悬浮玻璃顶栏（"额头"）。
 *
 * 与 dock 同样是**采集层之外**的玻璃浮层：页面内容不再为顶栏留高度，
 * 而是滚到它下面被折射/模糊掉。玻璃修饰符挂在空 Box 上，标题做它的兄弟。
 */
@Composable
fun GlassTopBar(
    backdrop: LayerBackdrop?,
    title: String,
    modifier: Modifier = Modifier,
    query: String = "",
    onQueryChange: ((String) -> Unit)? = null,
) {
    GlassPanel(
        backdrop = backdrop,
        // 细长条用比 dock 更小的折射量：高度只有 56dp，40dp 的边缘位移会整条糊成一团。
        shape = RoundedCornerShape(28.dp),
        refractionHeight = 16.dp,
        refractionAmount = 26.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = LiquidSpacing.page),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline),
        ) {
            // 曲库/喜欢页：顶栏直接当搜索框用（原来那张"搜索 + 我喜欢"的卡片已经去掉）
            if (onQueryChange != null) {
                Icon(Icons.Rounded.Search, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MiuixTheme.textStyles.title4.copy(color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.player_search_hint),
                                    style = MiuixTheme.textStyles.title4,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Close, stringResource(R.string.player_clear_search),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(20.dp).clickable { onQueryChange("") },
                    )
                }
            } else {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 悬浮玻璃迷你播放条（dock 之上，采集层之外）。 */
@Composable
fun MiniPlayerBar(
    backdrop: LayerBackdrop?,
    song: Song?,
    player: PlaybackUi,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(backdrop, RoundedCornerShape(28.dp), modifier.height(64.dp).fillMaxWidth()) {
        Row(
            Modifier.fillMaxSize().clickable(onClick = onOpen).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline),
        ) {
            Artwork(song, Modifier.size(44.dp), radius = 14)
            Column(Modifier.weight(1f)) {
                Text(player.title, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(player.artist, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onToggle) {
                Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) stringResource(R.string.player_pause) else stringResource(R.string.player_play))
            }
        }
    }
}

/** 播放页标题行距状态栏的呼吸位。 */
private val TopRowGap = 8.dp

/**
 * 全屏播放器。
 *
 * 设计要点（按用户要求）：**不是整页一块玻璃**，而是
 *  ① 围着封面一圈的玻璃光环（折射的是它下面的页面内容）
 *  ② 播放控件单独一块玻璃面板
 *  ③ 页面中间一块区域有三种状态：封面 / 歌词 / **播放列表**（可增删、可上下调序）
 *
 * 点封面 → 歌词；点歌词 → 回封面；顶部「列表」玻璃钮 → 播放列表。
 */
private enum class Middle { Cover, Lyrics, Queue }

@Composable
fun PlayerPage(
    backdrop: LayerBackdrop?,
    song: Song?,
    player: PlaybackUi,
    favorite: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    positionFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onFavorite: () -> Unit,
    onClose: () -> Unit,
    onChangeCover: () -> Unit,
    onOpenEq: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 主题色：优先取当前歌曲封面的鲜艳色，取不到再回落默认主题色
    val accent = rememberPlayerAccent(song) ?: scheme.primary
    val context = LocalContext.current
    // 播放进度在播放页内部收集：只有这一屏会跟着 400ms 的进度重组，曲库网格不受影响
    val livePosition by positionFlow.collectAsState()
    var middle by remember(song?.uri) { mutableStateOf(Middle.Cover) }
    var lyrics by remember(song?.uri) { mutableStateOf<LyricsRepository.Lyrics?>(null) }
    var lyricsLoaded by remember(song?.uri) { mutableStateOf(false) }

    // ── 进入播放页的"弹出"动画 ──
    // 一个 0→1 的弹簧进度，再按窗口切成三拍错峰：图标钮先出现 → 封面放大淡入 → 控件面板从下往上推到
    // 位。用弹簧（有回弹）而不是 tween，配合液态玻璃的形变手感。
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
    }
    val progress = enter.value
    fun stage(from: Float, until: Float): Float =
        ((progress - from) / (until - from).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    val iconStage = stage(0f, 0.45f)
    val coverStage = stage(0.10f, 0.75f)
    val controlStage = stage(0.30f, 1f)

    LaunchedEffect(song?.uri, middle) {
        if (middle == Middle.Lyrics && !lyricsLoaded && song != null) {
            lyrics = withContext(Dispatchers.IO) { LyricsRepository.load(context.applicationContext, song) }
            lyricsLoaded = true
        }
    }

    // 页面版本：上下留白交给调用方（顶部是玻璃顶栏、底部是 dock）
    Box(
        Modifier.fillMaxSize().padding(top = topPadding + TopRowGap, bottom = bottomPadding),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = LiquidSpacing.page),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部四个**独立的玻璃圆钮**：返回（收起播放页）/ 播放列表 / 换封面 / 均衡器。
            // 不放文字标题，歌名信息在下面的控件面板里，避免两处重复。第一拍入场。
            Row(
                Modifier.fillMaxWidth().graphicsLayer {
                    alpha = iconStage
                    val s = 0.7f + 0.3f * iconStage
                    scaleX = s; scaleY = s
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(backdrop, Icons.Rounded.ArrowBack, "返回", onClose)
                Spacer(Modifier.weight(1f))
                GlassIconButton(
                    backdrop,
                    if (middle == Middle.Queue) Icons.Rounded.QueueMusic else Icons.Rounded.PlaylistPlay,
                    stringResource(R.string.player_queue),
                    onClick = { middle = if (middle == Middle.Queue) Middle.Cover else Middle.Queue },
                )
                Spacer(Modifier.width(LiquidSpacing.inline))
                GlassIconButton(backdrop, Icons.Rounded.AddPhotoAlternate, stringResource(R.string.player_pick_cover), onChangeCover)
                Spacer(Modifier.width(LiquidSpacing.inline))
                GlassIconButton(backdrop, Icons.Rounded.GraphicEq, "均衡器", onOpenEq)
            }

            Spacer(Modifier.height(LiquidSpacing.item))

            // ① 中间区域三态：封面（带玻璃光环）/ 歌词 / 播放列表。第二拍入场：放大 + 淡入。
            Box(
                Modifier.weight(1f).fillMaxWidth().graphicsLayer {
                    alpha = coverStage
                    val s = 0.88f + 0.12f * coverStage
                    scaleX = s; scaleY = s
                },
                contentAlignment = Alignment.Center,
            ) {
                when (middle) {
                    Middle.Cover -> CoverWithGlassRing(backdrop = backdrop, song = song, onClick = { middle = Middle.Lyrics })
                    Middle.Lyrics -> GlassPanel(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(28.dp),
                        refractionHeight = 20.dp,
                        refractionAmount = 30.dp,
                        modifier = Modifier.fillMaxSize().clickable { middle = Middle.Cover },
                    ) {
                        LyricsPane(lyrics = lyrics, loaded = lyricsLoaded, positionMs = livePosition)
                    }
                    Middle.Queue -> GlassPanel(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(28.dp),
                        refractionHeight = 20.dp,
                        refractionAmount = 30.dp,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        QueuePane(
                            queue = player.queue,
                            onJump = onJumpTo,
                            onMove = onMoveInQueue,
                            onRemove = onRemoveFromQueue,
                            onClose = { middle = Middle.Cover },
                        )
                    }
                }
            }

            Spacer(Modifier.height(LiquidSpacing.item))

            // ② 播放控件：单独一块玻璃。第三拍入场：从下往上推 + 淡入 + 轻微放大。
            GlassPanel(
                backdrop = backdrop,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    alpha = controlStage
                    translationY = (1f - controlStage) * 56.dp.toPx()
                    val s = 0.94f + 0.06f * controlStage
                    scaleX = s; scaleY = s
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = LiquidSpacing.card, vertical = LiquidSpacing.item),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(player.title, style = MiuixTheme.textStyles.title4, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(player.artist, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        song?.let {
                            LiquidPill(it.format.uppercase())
                            if (it.bitDepth > 0) LiquidPill("${it.bitDepth}bit / ${it.sampleRate / 1000.0}kHz")
                            if (it.channels > 0) LiquidPill("${it.channels}ch")
                        }
                    }
                    Spacer(Modifier.height(LiquidSpacing.inline))
                    SeekBar(player, livePosition, onSeek)
                    Spacer(Modifier.height(LiquidSpacing.tight))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onShuffle) {
                            Icon(Icons.Rounded.Shuffle, stringResource(R.string.player_shuffle), tint = if (player.shuffle) accent else scheme.onSurfaceVariantSummary)
                        }
                        IconButton(onClick = onPrev) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_prev)) }
                        FilledIconButton(onClick = onToggle, modifier = Modifier.size(56.dp)) {
                            Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (player.playing) stringResource(R.string.player_pause) else stringResource(R.string.player_play), modifier = Modifier.size(30.dp))
                        }
                        IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next)) }
                        IconButton(onClick = onFavorite) {
                            Icon(
                                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                if (favorite) stringResource(R.string.player_unfavorite) else stringResource(R.string.player_favorite),
                                tint = if (favorite) accent else scheme.onSurfaceVariantSummary,
                            )
                        }
                        IconButton(onClick = onRepeat) {
                            Icon(
                                if (player.repeat == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                stringResource(R.string.player_repeat),
                                tint = if (player.repeat != androidx.media3.common.Player.REPEAT_MODE_OFF) accent else scheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    player.error?.let {
                        Spacer(Modifier.height(LiquidSpacing.tight))
                        Text(stringResource(R.string.player_error, it), style = MiuixTheme.textStyles.footnote1, color = scheme.error)
                    }
                }
            }
        }
    }
}

/**
 * 播放列表（就是 ExoPlayer 当前队列）：点行跳播、↑↓ 调序、✕ 删除。
 * 改的是真正在播的顺序（`moveMediaItem` / `removeMediaItem`），不是另存一份 UI 列表。
 */
@Composable
private fun QueuePane(
    queue: List<com.localmusic.app.QueueItem>,
    onJump: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    if (queue.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(LiquidSpacing.page),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.queue_empty), style = MiuixTheme.textStyles.title4, color = scheme.onSurface)
            Spacer(Modifier.height(LiquidSpacing.inline))
            Text(
                stringResource(R.string.queue_empty_hint),
                style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary, textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = LiquidSpacing.item, vertical = LiquidSpacing.item)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.queue_title, queue.size), style = MiuixTheme.textStyles.title4, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.queue_collapse)) }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(queue, key = { _, item -> item.index }) { _, item ->
                Row(
                    Modifier.fillMaxWidth().clickable { onJump(item.index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${item.index + 1}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (item.current) scheme.primary else scheme.onSurfaceVariantSummary,
                        modifier = Modifier.width(22.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (item.current) scheme.primary else scheme.onSurface)
                        if (item.artist.isNotBlank()) {
                            Text(item.artist, style = MiuixTheme.textStyles.footnote1,
                                color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = { onMove(item.index, item.index - 1) }, enabled = item.index > 0,
                        modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.queue_move_up), modifier = Modifier.size(17.dp))
                    }
                    IconButton(onClick = { onMove(item.index, item.index + 1) }, enabled = item.index < queue.lastIndex,
                        modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.queue_move_down), modifier = Modifier.size(17.dp))
                    }
                    IconButton(onClick = { onRemove(item.index) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.queue_remove), modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

/**
 * 封面 + 围绕它的一圈液态玻璃。
 *
 * 结构：外层 Box 的尺寸由 **modifier（fillMaxWidth + aspectRatio）** 决定，
 * 所以里面两个 `matchParentSize` 子项（玻璃层 / 内容层）不会把它塌成 0。
 * 内容层留 18dp 内边距 → 玻璃作为一圈"光环"露出来。
 */
@Composable
private fun CoverWithGlassRing(backdrop: LayerBackdrop?, song: Song?, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onClick)) {
        Box(
            Modifier.matchParentSize().liquidGlass(
                backdrop = backdrop,
                shape = RoundedCornerShape(48.dp),
                refractionHeight = 20.dp,
                refractionAmount = 34.dp,
            )
        )
        Box(Modifier.matchParentSize().padding(18.dp)) {
            Artwork(song, Modifier.matchParentSize(), radius = 34, requestPx = 900)
        }
    }
}

@Composable
private fun LyricsPane(lyrics: LyricsRepository.Lyrics?, loaded: Boolean, positionMs: Long) {
    val scheme = MiuixTheme.colorScheme
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        lyrics == null -> Column(
            Modifier.fillMaxSize().padding(LiquidSpacing.page),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("没有找到歌词", style = MiuixTheme.textStyles.title4, color = scheme.onSurface)
            Spacer(Modifier.height(LiquidSpacing.inline))
            Text(
                "支持两种来源：\n· 与音频同目录、同名的 .lrc 文件\n· 音频文件里内嵌的歌词（ID3 USLT / Vorbis LYRICS）\n\n点一下可以回到封面",
                style = MiuixTheme.textStyles.body2,
                color = scheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
        else -> {
            val listState = rememberLazyListState()
            val current = LyricsRepository.currentIndex(lyrics, positionMs)
            LaunchedEffect(current) {
                if (current >= 0) listState.animateScrollToItem(current.coerceAtMost(lyrics.lines.lastIndex))
            }
            Column(Modifier.fillMaxSize().padding(horizontal = LiquidSpacing.card, vertical = LiquidSpacing.item)) {
                Text(lyrics.source, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(LiquidSpacing.inline))
                LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(LiquidSpacing.inline)) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        Text(
                            text = line.text.ifBlank { "♪" },
                            style = if (index == current) MiuixTheme.textStyles.title4 else MiuixTheme.textStyles.body1,
                            color = if (index == current) scheme.primary else scheme.onSurface.copy(alpha = if (lyrics.timed) 0.75f else 1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(player: PlaybackUi, livePosition: Long, onSeek: (Long) -> Unit) {
    val duration = player.duration.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val position = if (dragging) dragValue.toLong() else livePosition.coerceIn(0L, duration)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = position.toFloat().coerceIn(0f, duration.toFloat()),
            valueRange = 0f..duration.toFloat(),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; onSeek(dragValue.toLong()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(position), style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
            Text(formatTime(duration), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}