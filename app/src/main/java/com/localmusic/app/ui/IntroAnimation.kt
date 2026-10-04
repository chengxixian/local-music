// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp

/**
 * 启动动画。
 *
 * 时间轴（总长约 3.2 秒，用一条 0..1 的线性进度驱动，逐阶段取值）：
 *   0.00–0.05  全黑
 *   0.05–0.35  底色点阵自左上向右下依次亮起（灰）
 *   0.30–0.55  Lm 形状的点亮成白 —— 字母从点阵里"浮"出来
 *   0.55–0.68  m 右上角那个点（图标里的红点）闪一下红光，随后定格为红
 *   0.68–1.00  点阵收缩淡出，`local music` 由窄到宽拉伸浮现
 *
 * 点阵形状与 `Pages.kt` 的 `DotMatrixMark`、`scripts/make-dotmatrix-icon.py` 一致。
 */
private val INTRO_PATTERN = listOf(
    "X..........",
    "X..........",
    "X.....X.X.X",   // 这一行最后一列就是 m 的点（图标里的红点）
    "X.....XXXXX",
    "X.....X.X.X",
    "X.....X.X.X",
    "XXXXX.X.X.X",
)

/** 只在本进程第一次显示界面时播一次：切语言会重建 Activity，不该重播。 */
object IntroState {
    var played = false
}

@Composable
fun IntroOverlay(onFinished: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(durationMillis = 3_200, easing = LinearEasing))
        onFinished()
    }
    val v = p.value
    val cols = INTRO_PATTERN.first().length
    val rows = INTRO_PATTERN.size

    // 文字：0.68 之后由窄到宽「拉伸」浮现
    val textRaw = ((v - 0.68f) / 0.22f).coerceIn(0f, 1f)
    val markFade = (1f - ((v - 0.66f) / 0.10f)).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / 13f
            val gridW = cols * cell
            val gridH = rows * cell
            val ox = (size.width - gridW) / 2f
            val oy = (size.height - gridH) / 2f - size.height * 0.07f
            val dotR = cell * 0.15f

            fun at(x: Int, y: Int) = Offset(ox + (x + 0.5f) * cell, oy + (y + 0.5f) * cell)

            // ① 底色点阵：逐点自左上向右下淡入（灰）
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val stagger = (x + y).toFloat() / (cols + rows - 2).toFloat()
                    val a = ((v - 0.05f - stagger * 0.22f) / 0.10f).coerceIn(0f, 1f) * markFade
                    if (a > 0.01f) {
                        drawCircle(Color(0xFF8A8A8A).copy(alpha = a * 0.5f), dotR, at(x, y))
                    }
                }
            }

            // ② Lm 形状的点：亮成白
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    if (INTRO_PATTERN[y].getOrNull(x) != 'X') continue
                    val stagger = (x + y).toFloat() / (cols + rows - 2).toFloat()
                    val a = ((v - 0.28f - stagger * 0.16f) / 0.10f).coerceIn(0f, 1f) * markFade
                    if (a > 0.01f) {
                        val grow = 1f + 0.35f * (1f - a)
                        drawCircle(Color.White.copy(alpha = a), dotR * grow, at(x, y))
                    }
                }
            }

            // ③ m 的点：先闪一圈红光，再定格为红
            val flash = ((v - 0.55f) / 0.09f).coerceIn(0f, 1f)
            val settled = ((v - 0.62f) / 0.06f).coerceIn(0f, 1f)
            val pulse = kotlin.math.sin(flash * Math.PI).toFloat().coerceAtLeast(0f)
            val dotCenter = at(cols - 1, 2)
            if (markFade > 0.01f && (flash > 0f || settled > 0f)) {
                // 光晕
                drawCircle(
                    Color(0xFFFF3B30).copy(alpha = 0.30f * pulse * markFade),
                    dotR * (2.6f + 2.4f * pulse),
                    dotCenter,
                )
                // 红点本体
                drawCircle(
                    Color(0xFFFF3B30).copy(alpha = markFade * (0.35f + 0.65f * maxOf(pulse, settled))),
                    dotR * (1f + 0.6f * pulse),
                    dotCenter,
                )
            }
        }

        // ④ 完整名字：由窄到宽拉伸 + 淡入
        Text(
            text = "local music",
            color = Color.White,
            fontWeight = FontWeight.Light,
            letterSpacing = 6.sp,
            fontSize = 30.sp,
            modifier = Modifier.graphicsLayer {
                alpha = textRaw
                // 拉伸：从很窄拉回正常宽度，同时略微上移到位
                scaleX = 0.25f + 0.75f * textRaw
                scaleY = 1f
                translationY = (1f - textRaw) * 18f
            },
        )
    }
}