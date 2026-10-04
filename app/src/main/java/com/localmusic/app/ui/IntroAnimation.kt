// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * 宣传动画（**不由启动触发**，供录屏制作宣传视频用）。
 *
 * 触发方式（只有这样才能看到，正常启动不会播）：
 *   adb shell am start -n com.localmusic.app/.MainActivity --ez promo true
 * 录屏：
 *   adb shell screenrecord --size 1080x2400 --bit-rate 20000000 /sdcard/promo.mp4
 *
 * 时间轴（总长 4.2s，一条 0..1 线性进度驱动）：
 *   0.00–0.05  全黑
 *   0.05–0.32  底色点阵自左上向右下逐点淡入（灰）
 *   0.28–0.46  Lm 形状的点亮成白 —— 字母从点阵里浮出来
 *   0.46–0.56  m 右上角的点（图标里的红点）闪一圈红光后定格为红
 *   0.56–0.82  **点阵全字**：L 与 m 左右拉开到 "local music" 的位置，
 *              中间的 o c a l ␣ u s i c 用同一套 5 列点阵逐字补全
 *   0.82–1.00  成形落位（轻微上移）
 *
 * 全部是点阵圆点，没有普通字体：字形由 GLYPHS 定义，底边对齐。
 */
private val MARK = listOf(
    "X..........",
    "X..........",
    "X.....X.X.X",
    "X.....XXXXX",
    "X.....X.X.X",
    "X.....X.X.X",
    "XXXXX.X.X.X",
)

/** 5 列点阵字形。行数可变：高的（l）7 行，x-height 的 5 行，底边对齐。 */
private val GLYPHS: Map<Char, List<String>> = mapOf(
    'l' to listOf("X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"),
    'o' to listOf(".XXX.", "X...X", "X...X", "X...X", ".XXX."),
    'c' to listOf(".XXX.", "X....", "X....", "X....", ".XXX."),
    'a' to listOf(".XXX.", "X...X", "XXXXX", "X...X", "X...X"),
    'm' to listOf("X.X.X", "XXXXX", "X.X.X", "X.X.X", "X.X.X"),
    'u' to listOf("X...X", "X...X", "X...X", "X...X", ".XXXX"),
    's' to listOf(".XXXX", "X....", ".XXX.", "....X", "XXXX."),
    'i' to listOf("..X..", ".....", "..X..", "..X..", "..X..", "..X.."),
    ' ' to listOf(".....", "....."),
)

private const val GLYPH_W = 5
private const val GAP = 1
private const val SPACE_W = 3
private const val TEXT = "local music"
private const val TOTAL_ROWS = 7

/** "local music" → 每个点的目标 (列, 行)。 */
private fun textDots(): Set<Pair<Int, Int>> {
    val out = HashSet<Pair<Int, Int>>()
    var cursor = 0
    for (ch in TEXT) {
        val glyph = GLYPHS[ch] ?: continue
        val top = TOTAL_ROWS - glyph.size
        for (r in glyph.indices) {
            for (c in glyph[r].indices) {
                if (glyph[r][c] == 'X') out += (cursor + c) to (top + r)
            }
        }
        cursor += (if (ch == ' ') SPACE_W else GLYPH_W) + GAP
    }
    return out
}

/** 标记里每个点 → 它在 "local music" 里的目标位置。 */
private fun markMoves(): List<Triple<Int, Int, Pair<Int, Int>>> {
    var cursor = 0
    var lCol = 0
    var mCol = 0
    for ((idx, ch) in TEXT.withIndex()) {
        if (idx == 0) lCol = cursor
        if (ch == 'm') { mCol = cursor; break }
        cursor += (if (ch == ' ') SPACE_W else GLYPH_W) + GAP
    }
    val out = ArrayList<Triple<Int, Int, Pair<Int, Int>>>()
    for (y in MARK.indices) {
        for (x in MARK[y].indices) {
            if (MARK[y][x] != 'X') continue
            val target: Pair<Int, Int>? = if (x <= 4) {
                (lCol + x) to y                                  // L：7 行，与字形 l 同形
            } else {
                val mc = x - 6                                   // m：标记中占 5 列 5 行（y=2..6）
                val mr = y - 2
                if (mr in 0..4 && mc in 0..4) (mCol + mc) to (TOTAL_ROWS - 5 + mr) else null
            }
            if (target != null) out += Triple(x, y, target)
        }
    }
    return out
}

@Composable
fun IntroOverlay(onFinished: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(durationMillis = 4_200, easing = LinearEasing))
        onFinished()
    }
    val v = p.value
    val cols = MARK.first().length
    val rows = MARK.size

    val targets = remember { textDots() }
    val moves = remember { markMoves() }
    val maxCol = remember { (targets.maxOfOrNull { it.first } ?: 0) + 1 }

    val morph = ((v - 0.56f) / 0.26f).coerceIn(0f, 1f)
    val ease = morph * morph * (3f - 2f * morph)
    val settle = ((v - 0.82f) / 0.18f).coerceIn(0f, 1f)
    val flash = ((v - 0.46f) / 0.06f).coerceIn(0f, 1f)
    val settled = ((v - 0.52f) / 0.05f).coerceIn(0f, 1f)
    val pulse = kotlin.math.sin(flash * Math.PI).toFloat().coerceAtLeast(0f)
    val lift = (1f - settle) * 26f

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val activeCols = cols + (maxCol - cols) * ease
            val cell = minOf(size.width / (activeCols + 3f), size.height / 18f)
            val gridW = activeCols * cell
            val gridH = rows * cell
            val ox = (size.width - gridW) / 2f
            val oy = (size.height - gridH) / 2f - size.height * 0.04f - lift
            val dotR = cell * 0.15f

            fun px(col: Float, row: Float) = Offset(ox + (col + 0.5f) * cell, oy + (row + 0.5f) * cell)

            // ① 底色点阵（灰）
            val gridFade = 1f - ease
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val stagger = (x + y).toFloat() / (cols + rows - 2).toFloat()
                    val a = ((v - 0.05f - stagger * 0.20f) / 0.09f).coerceIn(0f, 1f) * gridFade
                    if (a > 0.01f) drawCircle(Color(0xFF8A8A8A).copy(alpha = a * 0.5f), dotR, px(x.toFloat(), y.toFloat()))
                }
            }

            // ② Lm 的点：亮成白，迁移阶段移动到目标位置
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    if (MARK[y].getOrNull(x) != 'X') continue
                    val stagger = (x + y).toFloat() / (cols + rows - 2).toFloat()
                    val a = ((v - 0.28f - stagger * 0.14f) / 0.09f).coerceIn(0f, 1f)
                    if (a <= 0.01f) continue
                    val t = moves.firstOrNull { it.first == x && it.second == y }
                    val cx = if (t != null) x + (t.third.first - x) * ease else x.toFloat()
                    val cy = if (t != null) y + (t.third.second - y) * ease else y.toFloat()
                    if (y == 2 && x == cols - 1) {
                        if (flash > 0f || settled > 0f) {
                            drawCircle(Color(0xFFFF3B30).copy(alpha = 0.30f * pulse), dotR * (2.6f + 2.4f * pulse), px(cx, cy))
                            drawCircle(Color(0xFFFF3B30).copy(alpha = 0.35f + 0.65f * maxOf(pulse, settled)), dotR * (1f + 0.6f * pulse), px(cx, cy))
                        }
                    } else {
                        drawCircle(Color.White.copy(alpha = a), dotR, px(cx, cy))
                    }
                }
            }

            // ③ 补全的字：逐点浮出
            for (d in targets) {
                if (moves.any { it.third == d }) continue
                val order = (d.first + d.second).toFloat() / (maxCol + rows).toFloat()
                val a = ((v - 0.60f - order * 0.16f) / 0.10f).coerceIn(0f, 1f)
                if (a > 0.01f) drawCircle(Color.White.copy(alpha = a), dotR, px(d.first.toFloat(), d.second.toFloat()))
            }
        }
    }
}
