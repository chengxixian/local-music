// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.localmusic.app.data.Song
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * 曲库卡片 → 播放页的转场（共享元素式，不是整页缩放）：
 *
 *   ① 卡片封面单独一层，从卡片封面矩形**位移 + 放大**到播放页封面的最终位置；
 *   ② 卡片标题 / 艺术家各自从卡片文字的位置飞到播放页文字的位置，逐字错峰落位；
 *   ③ 播放页本身在飞行快到位时才淡入（控件不会在卡片上先出现），
 *      飞行层与播放页自己的封面/文字在终点完全重合，交接不闪。
 *
 * 关闭：控件先淡出 → 封面层从播放页封面缩回卡片封面 → 文字飞回卡片。
 */
object PlayerRevealOrigin {
    /** 每张卡片自己的矩形（窗口坐标），按歌曲 uri 存。 */
    val cardBounds = mutableMapOf<String, Rect>()

    /** 卡片标题 / 艺术家的矩形，用于文字飞入。 */
    val cardTitle = mutableMapOf<String, Rect>()
    val cardArtist = mutableMapOf<String, Rect>()
}

/** 转场进度；只在绘制层读取，不触发重组。 */
object PlayerRevealProgress {
    var value by mutableFloatStateOf(0f)

    /** 播放页背景的透明度。 */
    var backdropAlpha by mutableFloatStateOf(0f)

    /** 播放页自己标题/艺术家的透明度（转场期间隐藏，交给飞行文字）。 */
    var textAlpha by mutableFloatStateOf(1f)
}

/** 播放页里封面 / 标题 / 艺术家的最终位置与样式，由播放页自己上报。 */
object PlayerTargets {
    var cover by mutableStateOf<Rect?>(null)

    var titleRect by mutableStateOf<Rect?>(null)
    var titleStyle by mutableStateOf<TextStyle?>(null)
    var titleColor by mutableStateOf(Color.Unspecified)
    var titleText by mutableStateOf("")

    var artistRect by mutableStateOf<Rect?>(null)
    var artistStyle by mutableStateOf<TextStyle?>(null)
    var artistColor by mutableStateOf(Color.Unspecified)
    var artistText by mutableStateOf("")
}

private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

@Composable
fun PlayerReveal(
    visible: Boolean,
    origin: Rect?,
    song: Song?,
    favorite: Boolean,
    playing: Boolean,
    content: @Composable () -> Unit,
) {
    val visibleNow by rememberUpdatedState(visible)
    val context = LocalContext.current
    var kept by remember { mutableStateOf(visible) }
    var ready by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Rect?>(null) }
    val anim = remember { Animatable(if (visible) 0f else 1f) }

    LaunchedEffect(visible) {
        if (visible) {
            kept = true
            ready = false
            PlayerTargets.cover = null
            // 预热封面缓存：飞行封面和卡片共用 420px 条目，避免第一帧闪默认唱片
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { song?.let { ArtworkStore.load(context.applicationContext, it, 420) } }
            }
            // 等播放页完成一次布局（最多 120ms），拿到封面最终位置后再开始动画
            target = kotlinx.coroutines.withTimeoutOrNull(120L) {
                snapshotFlow { PlayerTargets.cover }.first { it != null }
            }
            ready = true
            anim.snapTo(0f)
            anim.animateTo(1f, tween(durationMillis = 430, easing = FastOutSlowInEasing))
        } else {
            anim.animateTo(0f, tween(durationMillis = 340, easing = FastOutSlowInEasing))
            kept = false
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { anim.value }.collect { v ->
            PlayerRevealProgress.value = v
            PlayerRevealProgress.backdropAlpha = smooth((v - 0.55f) / 0.40f)
            PlayerRevealProgress.textAlpha =
                if (visibleNow) smooth((v - 0.88f) / 0.12f) else 0f
        }
    }
    if (!kept) return

    var boxOffset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { boxOffset = it.positionInRoot() },
    ) {
        val fullW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val fullH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val p = anim.value
        val opening = visible

        val cardCover = origin?.let {
            Rect(it.left - boxOffset.x, it.top - boxOffset.y, it.right - boxOffset.x, it.top - boxOffset.y + it.width)
        }
        val measured = target?.let {
            Rect(it.left - boxOffset.x, it.top - boxOffset.y, it.right - boxOffset.x, it.bottom - boxOffset.y)
        } ?: Rect(fullW * 0.07f, fullH * 0.12f, fullW * 0.93f, fullH * 0.12f + fullW * 0.86f)

        // 播放页本身：等封面/文字快到位时才淡入，控件不会先在卡片上出现
        val contentAlpha = if (opening) smooth((p - 0.70f) / 0.30f) else smooth(p / 0.40f)

        // 遮罩：压暗曲库，避免两张封面同时存在；播放页出现后退场
        val scrim = if (opening) {
            smooth(p / 0.55f) * (1f - smooth((p - 0.80f) / 0.20f))
        } else {
            0f
        }
        if (scrim > 0.001f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * scrim)))
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha },
        ) {
            content()
        }

        // ① 飞行封面：卡片封面 → 播放页封面（位移 + 放大）
        if (ready && cardCover != null && measured.width > 1f) {
            val cp = smooth(p / 0.85f)
            val left = lerp(cardCover.left, measured.left, cp)
            val top = lerp(cardCover.top, measured.top, cp)
            val w = lerp(cardCover.width, measured.width, cp)
            val h = lerp(cardCover.height, measured.height, cp)
            val alpha = if (opening) 1f - smooth((p - 0.90f) / 0.10f) else smooth(p / 0.10f)
            val density = LocalDensity.current
            // 始终保留这一层（透明也画），这样关闭时封面已经在内存里，不会闪默认唱片
            run {
                Box(
                    Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                        .graphicsLayer { this.alpha = alpha }
                        .clip(RoundedCornerShape(lerp(18f, 34f, cp).dp)),
                ) {
                    Artwork(song, Modifier.fillMaxSize(), radius = 0, requestPx = 420)
                }
            }
        }

        // ② 飞行文字：卡片文字 → 播放页文字（逐字错峰）
        val uri = song?.uri
        if (ready && uri != null) {
            val ft = smooth(p / 0.88f)
            val textFade = if (opening) 1f - smooth((p - 0.90f) / 0.10f) else smooth(p / 0.10f)
            PlayerRevealOrigin.cardTitle[uri]?.let { start ->
                PlayerTargets.titleRect?.let { end ->
                    val st = PlayerTargets.titleStyle
                    if (st != null) {
                        FlyingText(
                            text = PlayerTargets.titleText,
                            style = st,
                            color = PlayerTargets.titleColor,
                            start = Rect(start.left - boxOffset.x, start.top - boxOffset.y, start.right - boxOffset.x, start.bottom - boxOffset.y),
                            end = Rect(end.left - boxOffset.x, end.top - boxOffset.y, end.right - boxOffset.x, end.bottom - boxOffset.y),
                            progress = ft,
                            stagger = 0.022f,
                            alpha = textFade,
                        )
                    }
                }
            }
            PlayerRevealOrigin.cardArtist[uri]?.let { start ->
                PlayerTargets.artistRect?.let { end ->
                    val st = PlayerTargets.artistStyle
                    if (st != null) {
                        FlyingText(
                            text = PlayerTargets.artistText,
                            style = st,
                            color = PlayerTargets.artistColor,
                            start = Rect(start.left - boxOffset.x, start.top - boxOffset.y, start.right - boxOffset.x, start.bottom - boxOffset.y),
                            end = Rect(end.left - boxOffset.x, end.top - boxOffset.y, end.right - boxOffset.x, end.bottom - boxOffset.y),
                            progress = smooth((p - 0.04f) / 0.88f),
                            stagger = 0.018f,
                            alpha = textFade,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 飞行文字：整块从 [start] 矩形缩放到 [end] 矩形，内部每个字再带一点错峰位移。
 * 用播放页自己的字号/字重渲染，并按目标宽度截断（与播放页的 Ellipsis 一致），
 * 终点与播放页文字完全重合，交接不跳、也不会甩出屏幕。
 */
@Composable
private fun FlyingText(
    text: String,
    style: TextStyle,
    color: Color,
    start: Rect,
    end: Rect,
    progress: Float,
    stagger: Float,
    alpha: Float,
) {
    if (text.isEmpty() || end.width <= 1f || alpha <= 0.001f) return
    val measurer = rememberTextMeasurer()
    val display = remember(text, style, end.width) {
        val maxW = end.width.toInt()
        fun widthOf(v: String): Int = measurer.measure(AnnotatedString(v), style, maxLines = 1).size.width
        if (maxW <= 0 || widthOf(text) <= maxW) text else {
            var lo = 0
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (widthOf(text.take(mid) + "…") <= maxW) lo = mid else hi = mid - 1
            }
            text.take(lo) + "…"
        }
    }
    val left = lerp(start.left, end.left, progress)
    val top = lerp(start.top, end.top, progress)
    val w = lerp(start.width, end.width, progress)
    val scale = (w / end.width).coerceIn(0.12f, 2.5f)
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .graphicsLayer { this.alpha = alpha },
    ) {
        Row(
            Modifier.graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            },
        ) {
            display.forEachIndexed { i, ch ->
                val delay = (i * stagger).coerceAtMost(0.55f)
                val local = ((progress - delay) / (1f - delay).coerceAtLeast(0.25f)).coerceIn(0f, 1f)
                Text(
                    text = ch.toString(),
                    style = style,
                    color = color.copy(alpha = color.alpha * (0.72f + 0.28f * local)),
                    modifier = Modifier.graphicsLayer { translationY = (1f - local) * 9f },
                )
            }
        }
    }
}
