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
import kotlinx.coroutines.flow.first
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

/** 播放页里封面/标题的最终位置（窗口坐标），由播放页自己上报。 */
object PlayerTargets {
    var cover by mutableStateOf<Rect?>(null)
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
    // 播放页封面的**布局位置**。必须先在没有缩放的状态下量一次，
    // 否则 onGloballyPositioned 拿到的是被缩放后的矩形，会自己追自己。
    var ready by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Rect?>(null) }
    val anim = remember { Animatable(if (visible) 0f else 1f) }

    LaunchedEffect(visible) {
        if (visible) {
            kept = true
            ready = false
            PlayerTargets.cover = null
            // 等播放页完成一次布局（最多 120ms），拿到封面最终位置后再开始动画
            target = kotlinx.coroutines.withTimeoutOrNull(120L) {
                snapshotFlow { PlayerTargets.cover }.first { it != null }
            }
            ready = true
            anim.snapTo(0f)
            anim.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
        } else {
            anim.animateTo(0f, tween(durationMillis = 300, easing = FastOutSlowInEasing))
            kept = false
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { anim.value }.collect {
            PlayerRevealProgress.value = it
            PlayerRevealProgress.backdropAlpha = smooth((it - 0.10f) / 0.55f)
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
        val e = smooth(p)

        // 卡片封面矩形（卡片顶部正方形）
        val cardCover = origin?.let {
            Rect(it.left - boxOffset.x, it.top - boxOffset.y, it.right - boxOffset.x, it.top - boxOffset.y + it.width)
        }
        // 播放页封面矩形（等待阶段量到的布局值；量不到时用屏幕中上部兜底）
        val measured = target?.let {
            Rect(it.left - boxOffset.x, it.top - boxOffset.y, it.right - boxOffset.x, it.bottom - boxOffset.y)
        } ?: Rect(fullW * 0.07f, fullH * 0.12f, fullW * 0.93f, fullH * 0.12f + fullW * 0.86f)

        // 把"播放页整体"做一次缩放+平移：p=0 时它的封面正好压在卡片封面上，p=1 时回到原位。
        // 于是封面是"位移 + 放大到播放页封面的位置"，标题/控件一起平滑就位，
        // 不再有"先放大到全屏、再缩回播放页"的两段跳。
        val startScale = if (cardCover != null && measured.width > 1f) {
            (cardCover.width / measured.width).coerceIn(0.05f, 1f)
        } else 0.86f
        val startTx = if (cardCover != null) cardCover.left - measured.left * startScale else 0f
        val startTy = if (cardCover != null) cardCover.top - measured.top * startScale else 0f
        val s = startScale + (1f - startScale) * e
        val tx = startTx * (1f - e)
        val ty = startTy * (1f - e)

        // 遮罩：把曲库压暗，避免"两张封面同时存在"的穿帮；播放页铺满后自动退场
        val scrim = smooth(p / 0.55f) * (1f - smooth((p - 0.80f) / 0.20f))
        if (scrim > 0.001f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * scrim)))
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (ready) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = s
                        scaleY = s
                        translationX = tx
                        translationY = ty
                    } else {
                        // 等待测量：保持原尺寸但完全透明，只参与布局
                        alpha = 0f
                    }
                },
        ) {
            content()
        }
    }
}
