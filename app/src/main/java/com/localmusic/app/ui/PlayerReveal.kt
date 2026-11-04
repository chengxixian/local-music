// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmusic.app.data.Song
import com.liquidmiuix.ui.LiquidPill
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 曲库卡片 → 播放页的转场：**整张卡片放大 → 压黑遮罩 → 播放页显影**。
 *
 *   ① 卡片（封面 + 歌名 + 艺术家 + 格式）作为一个整体，从它在网格里的矩形放大到全屏；
 *   ② 放大到位后压一层黑色遮罩；
 *   ③ 遮罩之上播放页淡入，同时卡片与遮罩退场。
 *
 * 关键点：放大的是**卡片快照**（[CardSnapshot]），不是单独一张封面 ——
 * 这样点哪张卡，就是哪张卡整体飞起来；卡片里的文字也一起放大，而不是只有图在动。
 *
 * 关闭时反向播放。没有卡片来源（迷你播放条 / 悬浮歌词）时跳过 ①，只做遮罩 + 淡入。
 */
object PlayerRevealOrigin {
    /**
     * 每张曲库卡片自己的矩形（窗口坐标），按歌曲 uri 存。
     * 不能只存"最后一个布局的卡片"—— 那样无论点哪张，都会从最后布局的那张（通常是右下角）弹出。
     */
    val cardBounds = mutableMapOf<String, Rect>()
}

/**
 * 0..1 的转场进度，供播放页背景在**采集层**里同步淡入。
 * 只在 graphicsLayer 的绘制 lambda 里读取，所以每帧只重绘那一层，不触发重组。
 */
object PlayerRevealProgress {
    var value by mutableFloatStateOf(0f)

    /** 播放页背景的透明度：卡片铺满屏幕之后才开始出现。 */
    var backdropAlpha by mutableFloatStateOf(0f)
}

private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
fun PlayerReveal(
    visible: Boolean,
    origin: Rect?,
    song: Song?,
    favorite: Boolean,
    playing: Boolean,
    content: @Composable () -> Unit,
) {
    var kept by remember { mutableStateOf(visible) }
    val anim = remember { Animatable(if (visible) 0f else 1f) }

    LaunchedEffect(visible) {
        if (visible) {
            kept = true
            anim.animateTo(1f, tween(durationMillis = 460, easing = FastOutSlowInEasing))
        } else {
            anim.animateTo(0f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
            kept = false
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { anim.value }.collect {
            PlayerRevealProgress.value = it
            PlayerRevealProgress.backdropAlpha = smooth((it - 0.55f) / 0.30f)
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

        // ① 卡片整体放大：0 → 0.55
        val expand = smooth(p / 0.55f)
        // ② 遮罩：0.45 起、0.65 到顶、0.85 后退场
        val scrim = smooth((p - 0.45f) / 0.20f) * (1f - smooth((p - 0.85f) / 0.15f))
        // ③ 播放页：0.62 → 1 显影；卡片在遮罩下退场
        val contentAlpha = smooth((p - 0.62f) / 0.38f)
        val cardAlpha = 1f - smooth((p - 0.50f) / 0.25f)

        val o = origin?.let {
            Rect(it.left - boxOffset.x, it.top - boxOffset.y, it.right - boxOffset.x, it.bottom - boxOffset.y)
        }

        // ① 卡片快照：整张卡从网格里的矩形长到全屏
        if (o != null && o.width > 1f && o.height > 1f && cardAlpha > 0.001f) {
            val density = LocalDensity.current
            val cardW = with(density) { o.width.toDp() }
            val cardH = with(density) { o.height.toDp() }
            // 等比放大到"卡片宽度铺满屏幕"；高度会超出屏幕，由窗口裁掉
            val targetScale = (fullW / o.width).coerceAtLeast(1f)
            Box(
                Modifier
                    .size(cardW, cardH)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = o.left * (1f - expand)
                        translationY = o.top * (1f - expand)
                        val s = 1f + (targetScale - 1f) * expand
                        scaleX = s
                        scaleY = s
                        alpha = cardAlpha
                    }
                    .clip(RoundedCornerShape(18.dp)),
            ) {
                CardSnapshot(song, favorite, playing)
            }
        }

        // ② 遮罩
        if (scrim > 0.001f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f * scrim)))
        }

        // ③ 播放页
        if (contentAlpha > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = contentAlpha
                        val s = 0.99f + 0.01f * contentAlpha
                        scaleX = s
                        scaleY = s
                    },
            ) {
                content()
            }
        }
    }
}

/**
 * 卡片快照：和曲库网格里的卡片同一套排版（封面 + 歌名 + 艺术家 + 格式）。
 * 只用于转场，不响应点击；封面用 420px 请求，和卡片共用内存缓存，放大瞬间就有图，
 * 不会先闪一下默认唱片占位。
 */
@Composable
private fun CardSnapshot(song: Song?, favorite: Boolean, playing: Boolean) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxSize().background(scheme.surfaceContainer)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Artwork(song, Modifier.matchParentSize(), radius = 0, requestPx = 420)
            // 卡片上的四个小圆钮也一起放大，否则点下去的一瞬间它们会"消失"
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(28.dp)
                    .clip(CircleShape).background(scheme.surfaceContainerHigh.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.QueueMusic, null, Modifier.size(18.dp), tint = Color.White) }
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)
                    .clip(CircleShape).background(scheme.surfaceContainerHigh.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    null,
                    Modifier.size(17.dp),
                    tint = if (favorite) scheme.primary else Color.White,
                )
            }
            Box(
                Modifier.align(Alignment.BottomStart).padding(6.dp).size(28.dp)
                    .clip(CircleShape).background(scheme.surfaceContainerHigh.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, null, Modifier.size(20.dp), tint = Color.White) }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(28.dp)
                    .clip(CircleShape).background(scheme.surfaceContainerHigh.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp), tint = Color.White) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                song?.title.orEmpty(),
                style = MiuixTheme.textStyles.title4,
                color = if (playing) scheme.primary else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                song?.artist.orEmpty(),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                LiquidPill(song?.format?.uppercase().orEmpty())
                Text(
                    when {
                        song == null -> ""
                        song.bitDepth > 0 -> song.bitDepth.toString() + "bit/" + (song.sampleRate / 1000.0) + "kHz"
                        song.duration > 0 -> com.localmusic.app.data.formatTime(song.duration)
                        else -> song.album
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
