// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.localmusic.app.PlaybackUi
import com.localmusic.app.QueueItem
import com.localmusic.app.R
import com.localmusic.app.data.LyricsRepository
import com.localmusic.app.data.Song
import com.localmusic.app.data.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * **第二套播放页**（波浪版）。与 [PlayerPage]（液态玻璃版）**同契约**：
 * 参数名 / 类型 / 顺序逐字对应，回调语义一致，只换画法。
 *
 * 视觉来源（用户给的参考图）：
 *  - 背景不是玻璃，而是**封面本身**：模糊 + 降饱和 + 渐变压暗，由 `PlayerBackdrop` 在
 *    采集层里画好，这一页只负责在上面摆内容（所以本文件一个玻璃类型都不需要）。
 *  - 主色 = 封面平均色（[rememberPlayerAccent]）→ 圆形钮、波浪进度条、活动态都用它。
 *  - 文字黑/白由 [luminance] 判定（背景亮用黑字，暗用白字），和玻璃版同一套判据。
 *  - 进度条是**已播部分是波浪线、未播部分是直线**的手绘条 + 竖直短棒滑块（参考图那支）。
 *  - 歌词按距离渐隐，当前行加粗放大；支持点歌词回封面。
 *
 * ⚠️ `backdrop` 收下但**不用**：波浪版没有任何玻璃元素，保留它只是为了让调用点
 * 两个分支的实参表一字不差。
 */
@Composable
fun WavePlayerPage(
    @Suppress("UNUSED_PARAMETER") backdrop: LayerBackdrop?,
    song: Song?,
    player: PlaybackUi,
    favorite: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    positionFlow: StateFlow<Long>,
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
    val accent = rememberPlayerAccent(song) ?: Color(0xFF8AB4F8)
    // 背景偏亮 → 深色字；偏暗 → 浅色字。与玻璃版同一档阈值。
    val onBackdrop = if (accent.luminance() > 0.68f) Color(0xFF141414) else Color.White
    val darkBackdrop = onBackdrop.luminance() > 0.5f

    // 封面平均色经常偏灰、甚至很暗（深色封面），直接拿来当图标色就会"看不清"。
    // 两步处理：先提饱和（色相明显），再按背景明暗把亮度夹进可读区间。
    val vividAccent = vivid(accent)
    // 所有圆钮共用的图标色：深底提亮、浅底压暗，色相仍是封面的。
    val chipInk = readable(vividAccent, darkBackdrop)
    // 次级文字（艺术家、时间）再收敛一档，不要整屏都在喊。
    val themeInk = if (darkBackdrop) lerp(chipInk, Color.White, 0.22f) else lerp(chipInk, Color.Black, 0.12f)
    // 播放键与小按钮用**同一支主题色** chipInk：底 = chipInk 实心，图标 = 反色对比。
    // 深色页/浅色页都是同一套颜色 —— 不会再有"深色页跑成浅色档"的问题。
    val keyFill = chipInk
    val keyIcon = if (chipInk.luminance() > 0.5f) Color(0xFF141414) else Color.White

    val context = LocalContext.current
    val livePosition by positionFlow.collectAsState()

    var middle by remember(song?.uri) { mutableStateOf(WaveMiddle.Cover) }
    var lyrics by remember(song?.uri) { mutableStateOf<LyricsRepository.Lyrics?>(null) }
    var lyricsLoaded by remember(song?.uri) { mutableStateOf(false) }

    // 入场：淡入 + 轻微放大（比玻璃版朴素的弹簧，参考图是"整页浮上来"的观感）。
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    }
    val stage = enter.value

    LaunchedEffect(song?.uri, middle) {
        if (middle == WaveMiddle.Lyrics && !lyricsLoaded && song != null) {
            lyrics = withContext(Dispatchers.IO) { LyricsRepository.load(context.applicationContext, song) }
            lyricsLoaded = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = stage
                val s = 0.96f + 0.04f * stage
                scaleX = s; scaleY = s
            }
            .padding(top = topPadding + 8.dp, bottom = bottomPadding)
            .padding(horizontal = PagePad),
    ) {
        // ① 顶部：只有收起键（参考图封面页没有顶栏，返回钮贴着左上角）。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircleChip(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                description = stringResource(R.string.player_close),
                tint = chipInk,
                onClick = onClose,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ② 中间三态：封面 / 歌词 / 队列
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (middle) {
                WaveMiddle.Cover -> CoverPane(
                    song = song,
                    onOpenLyrics = { middle = WaveMiddle.Lyrics },
                )
                WaveMiddle.Lyrics -> LyricsPane(
                    lyrics = lyrics,
                    loaded = lyricsLoaded,
                    positionMs = livePosition,
                    onBackToCover = { middle = WaveMiddle.Cover },
                )
                WaveMiddle.Queue -> QueuePane(
                    queue = player.queue,
                    ink = chipInk,
                    onJump = onJumpTo,
                    onMove = onMoveInQueue,
                    onRemove = onRemoveFromQueue,
                    onClose = { middle = WaveMiddle.Cover },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ③ 信息行：封面页是大标题 + 艺术家；歌词页前面多一张小方封面（参考图那样）。
        InfoRow(
            song = song,
            title = player.title,
            artist = player.artist,
            compact = middle != WaveMiddle.Cover,
            favorite = favorite,
            ink = chipInk,
            artistTint = themeInk,
            titleTint = onBackdrop,
            onFavorite = onFavorite,
            onMore = onChangeCover,
        )

        Spacer(Modifier.height(18.dp))

        // ④ 波浪进度条 + 时间
        WaveSeek(
            player = player,
            livePosition = livePosition,
            tint = chipInk,
            onSeek = onSeek,
        )

        Spacer(Modifier.height(16.dp))

        // ⑤ 主控件：圆形上一首 / 大圆角浅色播放键 / 圆形下一首（参考图的比例关系）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleChip(
                icon = Icons.Rounded.SkipPrevious,
                description = stringResource(R.string.player_prev),
                tint = chipInk,
                size = MainCircleSize,
                iconSize = MainCircleIcon,
                onClick = onPrev,
            )
            Spacer(Modifier.width(14.dp))
            PlayKey(
                playing = player.playing,
                fill = keyFill,
                iconTint = keyIcon,
                onToggle = onToggle,
            )
            Spacer(Modifier.width(14.dp))
            CircleChip(
                icon = Icons.Rounded.SkipNext,
                description = stringResource(R.string.player_next),
                tint = chipInk,
                size = MainCircleSize,
                iconSize = MainCircleIcon,
                onClick = onNext,
            )
        }

        Spacer(Modifier.height(26.dp))

        // ⑥ 底部一排功能钮：乱序 / 循环 / 歌词 / 均衡器 / 队列
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleChip(
                icon = Icons.Rounded.Shuffle,
                description = stringResource(R.string.player_shuffle),
                tint = chipInk,
                active = player.shuffle,
                    onClick = onShuffle,
            )
            CircleChip(
                icon = if (player.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                description = stringResource(R.string.player_repeat),
                tint = chipInk,
                active = player.repeat != Player.REPEAT_MODE_OFF,
                    onClick = onRepeat,
            )
            CircleChip(
                icon = Icons.Rounded.GraphicEq,
                description = stringResource(R.string.settings_equalizer),
                tint = chipInk,
                onClick = onOpenEq,
            )
            CircleChip(
                icon = Icons.Rounded.PlaylistPlay,
                description = stringResource(R.string.player_queue),
                tint = chipInk,
                active = middle == WaveMiddle.Queue,
                    onClick = {
                    middle = if (middle == WaveMiddle.Queue) WaveMiddle.Cover else WaveMiddle.Queue
                },
            )
        }

        player.error?.let { code ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.player_error, code),
                color = Color(0xFFFF8A80),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}



/**
 * 把颜色沿白/黑方向推到"在背景上看得清"的亮度区间（保留色相）。
 * 深底要够亮（>=0.72），浅底要够暗（<=0.30）—— 这是"图标不能糊在背景里"的硬约束。
 */
private fun readable(color: Color, darkBackdrop: Boolean): Color {
    // 关键：只推 HSV 的**明度**，饱和度留着 —— 往白/黑插值会把颜色洗掉，
    // 结果就是"看得见但没颜色"（之前那版图标发灰、看不清就是这个原因）。
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    if (darkBackdrop) {
        hsv[2] = hsv[2].coerceAtLeast(0.94f)   // 深底：明度拉满
        hsv[1] = hsv[1].coerceAtLeast(0.40f)   // 但保留颜色，不许变白
    } else {
        hsv[2] = hsv[2].coerceAtMost(0.56f)    // 浅底：压暗
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
    }
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * 把封面平均色往"更像主题色"的方向推：饱和度至少抬到 [minSaturation]，明度不动。
 * 浅色封面的平均色往往接近白灰 —— 不提饱和的话，图标看着就是"没上色"。
 */
private fun vivid(color: Color, minSaturation: Float = 0.45f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(minSaturation)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 中间区域的三种状态。 */
private enum class WaveMiddle { Cover, Lyrics, Queue }

private val PagePad: Dp = 20.dp
private val MainCircleSize: Dp = 72.dp
private val MainCircleIcon: Dp = 24.dp
private val PlayKeyWidth: Dp = 116.dp
private val PlayKeyHeight: Dp = 64.dp
private val ChipSize: Dp = 44.dp
private val ChipIcon: Dp = 20.dp

/** 封面页：大圆角封面（点一下去歌词）。 */
@Composable
private fun CoverPane(song: Song?, onOpenLyrics: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 竖屏按宽度取 0.94 屏宽；横屏按高度取 —— 否则正方形封面在横屏会溢出，
        // 把下面的标题/控件整片盖掉。
        val side = minOf(maxWidth * 0.94f, maxHeight * 0.92f)
        Box(
            Modifier
                .size(side)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onOpenLyrics),
        ) {
            Artwork(song, Modifier.matchParentSize(), radius = 22, requestPx = 900)
        }
    }
}

/**
 * 歌词页：**按距离渐隐**（参考图的核心观感）。
 *
 * 当前行最亮最大，往上/往下各档递减 —— 用固定的 alpha 阶梯而不是连续插值，
 * 于是"相邻两行"的亮度关系永远稳定，不会因行距/字数看起来忽明忽暗。
 */
@Composable
private fun LyricsPane(
    lyrics: LyricsRepository.Lyrics?,
    loaded: Boolean,
    positionMs: Long,
    onBackToCover: () -> Unit,
) {
    when {
        !loaded -> Box(Modifier.fillMaxSize())
        lyrics == null -> Column(
            Modifier.fillMaxSize().clickable(onClick = onBackToCover),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.lyrics_missing_short),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.lyrics_missing_hint_short),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
        else -> {
            val listState = rememberLazyListState()
            val current = LyricsRepository.currentIndex(lyrics, positionMs)
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize().clickable(onClick = onBackToCover)) {
                // 上下各留半屏：当前行因此被"顶"到正中，换行时整列往上走；
                // 再叠上每行的弹簧缩放/淡入，就是参考图里"当前行弹出来"的观感。
                val half = maxHeight / 2
                val centerOffset = with(density) { -(half.toPx() - 16.sp.toPx()) }.toInt()
                LaunchedEffect(current) {
                    if (current >= 0) {
                        // +1 是因为列表第一项是占位 Spacer；负偏移把行压到视口正中。
                        listState.animateScrollToItem(current + 1, scrollOffset = centerOffset)
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item { Spacer(Modifier.height(half)) }
                    itemsIndexed(lyrics.lines) { index, line ->
                        val distance = kotlin.math.abs(index - current)
                        val isCurrent = index == current || current < 0 && index == 0
                        val pop by animateFloatAsState(
                            targetValue = if (isCurrent) 1f else 0.84f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
                            label = "lyricPop$index",
                        )
                        val fade by animateFloatAsState(
                            targetValue = if (isCurrent) 1f else 0.12f + 0.4f * lyricFade(distance),
                            animationSpec = tween(280),
                            label = "lyricFade$index",
                        )
                        Text(
                            text = line.text.ifBlank { "♪" },
                            fontSize = 23.sp,
                            lineHeight = 32.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = Color.White,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .graphicsLayer {
                                    scaleX = pop; scaleY = pop
                                    alpha = fade
                                    // 从左边缘长大：放大后不会被左边界裁掉
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                },
                        )
                    }
                    item { Spacer(Modifier.height(half)) }
                }
            }
        }
    }
}

/** 非当前行的亮度阶梯：越远越淡。 */
private fun lyricFade(distance: Int): Float = when (distance) {
    0 -> 1f
    1 -> 0.52f
    2 -> 0.30f
    3 -> 0.20f
    else -> 0.14f
}

/** 信息行：标题 + 艺术家，右侧星标 / 更多。歌词页前面挂一张小方封面。 */
@Composable
private fun InfoRow(
    song: Song?,
    title: String,
    artist: String,
    compact: Boolean,
    favorite: Boolean,
    ink: Color,
    artistTint: Color,
    titleTint: Color,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))) {
                Artwork(song, Modifier.matchParentSize(), radius = 10, requestPx = 200)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = if (compact) 19.sp else 25.sp,
                fontWeight = FontWeight.Bold,
                color = titleTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                fontSize = if (compact) 13.sp else 15.sp,
                color = artistTint.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        CircleChip(
            icon = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            description = if (favorite) stringResource(R.string.player_unfavorite) else stringResource(R.string.player_favorite),
            tint = ink,
            active = favorite,
            onClick = onFavorite,
        )
        Spacer(Modifier.width(8.dp))
        CircleChip(
            icon = Icons.Rounded.AddPhotoAlternate,
            description = stringResource(R.string.player_pick_cover),
            tint = ink,
            onClick = onMore,
        )
    }
}

/**
 * **波浪进度条**（参考图那支）：已播部分是正弦波，未播部分是细直线，滑块是一根竖直短棒。
 *
 * 拖动语义与玻璃版**完全一致**：拖动中自己持有 `dragValue` 并冻结显示值，**松手才** [onSeek]。
 * 手势层按 40dp 高铺开（2dp 的线点不中），上下留白由外面控制。
 *
 * 波形的相位用一个无限过渡驱动 —— 只在**绘制**里读它，所以每秒只重画这一小块 Canvas，
 * 不会让整页重组（和 400ms 的进度流同一个量级的开销）。
 */
@Composable
private fun WaveSeek(
    player: PlaybackUi,
    livePosition: Long,
    tint: Color,
    onSeek: (Long) -> Unit,
) {
    val duration = player.duration.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val position = if (dragging) dragValue.toLong() else livePosition.coerceIn(0L, duration)
    val fraction = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    val wave = rememberInfiniteTransition(label = "waveSeek")
    val phase by wave.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "waveSeekPhase",
    )

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(SeekTouchHeight)
                .pointerInput(duration) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragValue = (offset.x / size.width * duration).coerceIn(0f, duration.toFloat())
                        },
                        onDragEnd = {
                            dragging = false
                            onSeek(dragValue.toLong())
                        },
                        onDragCancel = { dragging = false },
                        onDrag = { change, _ ->
                            change.consume()
                            dragValue = (change.position.x / size.width * duration).coerceIn(0f, duration.toFloat())
                        },
                    )
                }
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        onSeek((offset.x / size.width * duration).toLong().coerceIn(0L, duration))
                    }
                },
        ) {
            val cy = size.height / 2f
            val played = size.width * fraction
            val trackStroke = 2.5.dp.toPx()

            // 未播：细直线（半透明）
            if (played < size.width) {
                drawLine(
                    color = tint.copy(alpha = 0.45f),
                    start = Offset(played, cy),
                    end = Offset(size.width, cy),
                    strokeWidth = trackStroke,
                    cap = StrokeCap.Round,
                )
            }
            // 已播：正弦波。逐小段连线（比贝塞尔稳，短距离也不会画飞）。
            if (played > 1f) {
                val amp = 3.4.dp.toPx()
                val wavelength = 13.dp.toPx()
                val stepPx = 2.dp.toPx().coerceAtLeast(1f)
                val path = Path()
                path.moveTo(0f, cy + amp * sin(phase))
                var x = 0f
                while (x < played) {
                    path.lineTo(x, cy + amp * sin(phase + 2f * PI.toFloat() * x / wavelength))
                    x += stepPx
                }
                path.lineTo(played, cy + amp * sin(phase + 2f * PI.toFloat() * played / wavelength))
                drawPath(
                    path = path,
                    color = tint,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            // 滑块：竖直短棒 + 外侧一圈很淡的光晕（参考图那根白棒）
            val thumbW = 3.5.dp.toPx()
            val thumbH = 22.dp.toPx()
            drawRoundRect(
                color = tint.copy(alpha = 0.18f),
                topLeft = Offset(played - thumbW * 2f, cy - thumbH * 0.75f),
                size = Size(thumbW * 4f, thumbH * 1.5f),
                cornerRadius = CornerRadius(thumbW * 2f),
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(played - thumbW / 2f, cy - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(thumbW / 2f),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatTime(position),
                fontSize = 13.sp,
                color = tint.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(duration),
                fontSize = 13.sp,
                color = tint.copy(alpha = 0.85f),
            )
        }
    }
}

private val SeekTouchHeight: Dp = 40.dp

/** 队列页：平的实色行，点行进曲，右侧三个小圆钮（上移/下移/移除）。 */
@Composable
private fun QueuePane(
    queue: List<QueueItem>,
    ink: Color,
    onJump: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
) {
    if (queue.isEmpty()) {
        Column(
            Modifier.fillMaxSize().clickable(onClick = onClose),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.queue_empty),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.queue_empty_hint),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.queue_title, queue.size),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            CircleChip(
                icon = Icons.Rounded.KeyboardArrowDown,
                description = stringResource(R.string.queue_collapse),
                tint = ink,
                onClick = onClose,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(queue, key = { _, item -> item.index }) { _, item ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clickable { onJump(item.index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontSize = 15.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.artist,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CircleChip(
                        icon = Icons.Rounded.ArrowUpward,
                        description = stringResource(R.string.queue_move_up),
                        tint = ink,
                        size = 36.dp,
                        iconSize = 18.dp,
                        enabled = item.index > 0,
                        onClick = { onMove(item.index, item.index - 1) },
                    )
                    Spacer(Modifier.width(6.dp))
                    CircleChip(
                        icon = Icons.Rounded.ArrowDownward,
                        description = stringResource(R.string.queue_move_down),
                        tint = ink,
                        size = 36.dp,
                        iconSize = 18.dp,
                        enabled = item.index < queue.lastIndex,
                        onClick = { onMove(item.index, item.index + 1) },
                    )
                    Spacer(Modifier.width(6.dp))
                    CircleChip(
                        icon = Icons.Rounded.Close,
                        description = stringResource(R.string.queue_remove),
                        tint = ink,
                        size = 36.dp,
                        iconSize = 18.dp,
                        onClick = { onRemove(item.index) },
                    )
                }
            }
        }
    }
}

/**
 * 圆形半透明钮（参考图里那一圈圆钮）。活动态用**封面强调色**填充 + 反色图标，
 * 非活动态是当前文字色的一层很淡的底 —— 于是同一排里"开着的那个"一眼可辨。
 */
@Composable
private fun CircleChip(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    size: Dp = ChipSize,
    iconSize: Dp = ChipIcon,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    // 参考图：**除中间那颗播放键**之外，所有钮都是"一层很淡的主题色圆底 + 一颗主题色图标"。
    // 图标直接用封面主题色本身（不是灰白），开态只把圆底加深一档 —— 于是整排钮都带色，
    // 但谁也没抢过播放键。
    val fill = when {
        !enabled -> tint.copy(alpha = 0.05f)
        active -> tint.copy(alpha = 0.34f)
        else -> tint.copy(alpha = 0.20f)
    }
    val iconTint = if (enabled) tint else tint.copy(alpha = 0.28f)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

/**
 * 主播放键：**大圆角矩形 + 浅色实心 + 深色图标**（参考图里最重的那一颗）。
 * 背景偏暗时它是白的、图标近黑；背景偏亮时反过来 —— 无论封面什么颜色都保证对比。
 */
@Composable
private fun PlayKey(playing: Boolean, fill: Color, iconTint: Color, onToggle: () -> Unit) {
    Box(
        Modifier
            .size(width = PlayKeyWidth, height = PlayKeyHeight)
            .clip(RoundedCornerShape(20.dp))
            .background(fill)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
            tint = iconTint,
            modifier = Modifier.size(28.dp),
        )
    }
}
