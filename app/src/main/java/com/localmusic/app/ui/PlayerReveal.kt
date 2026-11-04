// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.localmusic.app.data.Song

/**
 * 曲库卡片 → 播放页的转场：**卡片直接放大，再交叉淡入播放页**。
 *
 *   ① 卡片里的封面从卡片矩形放大到全屏（圆角同步收到 0）；
 *   ② 放大到位后，封面层与播放页交叉淡化 —— 播放页从整屏封面里"显影"出来，
 *      同时播放页自己的背景（模糊封面）跟着淡入。
 *
 * 不做黑色遮罩：中间那一下压黑反而会打断"同一张封面连续放大"的连续感。
 * 关闭时反向播放（播放页淡出 → 封面缩回卡片）。
 *
 * 没有卡片来源（迷你播放条 / 悬浮歌词）时跳过 ①，直接淡入播放页。
 */
object PlayerRevealOrigin {
    /** 最近一次布局的曲库卡片矩形（窗口坐标）。卡片在 onGloballyPositioned 里更新。 */
    var lastCard: Rect = Rect.Zero
}

/**
 * 0..1 的转场进度，供播放页背景在**采集层**里同步淡入。
 * 只在 graphicsLayer 的绘制 lambda 里读取，所以每帧只重绘那一层，不触发重组。
 */
object PlayerRevealProgress {
    var value by mutableFloatStateOf(0f)

    /** 播放页背景的透明度：封面铺满屏幕之后才开始出现。 */
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

        // ① 卡片放大：0 → 0.58
        val expand = smooth(p / 0.58f)
        // ② 封面层退场 / 播放页淡入：0.55 → 1
        val contentAlpha = smooth((p - 0.55f) / 0.45f)
        val coverAlpha = 1f - contentAlpha

        val o = origin?.let {
            Rect(it.left - boxOffset.x, it.top - boxOffset.y, it.right - boxOffset.x, it.bottom - boxOffset.y)
        }

        // ① 封面层：从卡片矩形长到全屏
        if (o != null && coverAlpha > 0.001f) {
            val startScaleX = (o.width / fullW).coerceIn(0.05f, 1f)
            val startScaleY = (o.height / fullH).coerceIn(0.05f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = startScaleX + (1f - startScaleX) * expand
                        scaleY = startScaleY + (1f - startScaleY) * expand
                        translationX = o.left * (1f - expand)
                        translationY = o.top * (1f - expand)
                        alpha = coverAlpha
                    }
                    .clip(RoundedCornerShape((18f * (1f - expand)).dp)),
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                Artwork(song, Modifier.fillMaxSize(), radius = 0, requestPx = 900)
            }
        }

        // ② 播放页：从整屏封面里显影出来
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
