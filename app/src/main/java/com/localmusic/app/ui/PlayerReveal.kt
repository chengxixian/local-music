// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 曲库卡片 → 播放页的"应用打开"式转场。
 *
 * 点卡片时记下卡片在窗口里的矩形，播放页从那个矩形放大到全屏（系统打开 App 的
 * container-transform 观感）：内容先缩在卡片位置，再一边放大一边淡入，
 * 关闭时反向缩回卡片。没有共享元素 API 的依赖，纯 graphicsLayer 实现。
 */
object PlayerRevealOrigin {
    /** 最近一次布局的曲库卡片矩形（窗口坐标）。卡片在 onGloballyPositioned 里更新。 */
    var lastCard: Rect = Rect.Zero
}

@Composable
fun PlayerReveal(
    visible: Boolean,
    origin: Rect?,
    content: @Composable () -> Unit,
) {
    var kept by remember { mutableStateOf(visible) }
    val anim = remember { Animatable(if (visible) 0f else 1f) }

    LaunchedEffect(visible) {
        if (visible) {
            kept = true
            anim.animateTo(1f, tween(durationMillis = 360, easing = FastOutSlowInEasing))
        } else {
            anim.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
            kept = false
        }
    }
    if (!kept) return

    var boxOffset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { boxOffset = it.positionInRoot() },
    ) {
        val fullW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val fullH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        // 卡片矩形是"窗口坐标"，而这一层被 contentPadding 往下推了（状态栏）；
        // 先换算到本层坐标，否则放大起点会整体偏下一条状态栏。
        val o = origin?.let {
            Rect(it.left - boxOffset.x, it.top - boxOffset.y, it.right - boxOffset.x, it.bottom - boxOffset.y)
        } ?: Rect(
            fullW * 0.08f,
            fullH * 0.16f,
            fullW * 0.92f,
            fullH * 0.84f,
        )
        val p = anim.value
        val startScaleX = (o.width / fullW).coerceIn(0.06f, 1f)
        val startScaleY = (o.height / fullH).coerceIn(0.06f, 1f)
        val scaleX = startScaleX + (1f - startScaleX) * p
        val scaleY = startScaleY + (1f - startScaleY) * p
        val corner = (20f * (1f - p)).dp

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                    translationX = o.left * (1f - p)
                    translationY = o.top * (1f - p)
                    // 起点带一点透明，放大过程中迅速变实 —— 和系统 App 打开动画一致
                    alpha = 0.45f + 0.55f * p
                }
                .clip(RoundedCornerShape(corner)),
        ) {
            content()
        }
    }
}
