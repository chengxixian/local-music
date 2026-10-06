// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import com.localmusic.app.data.CoverStore
import com.localmusic.app.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 播放页主题色：从**当前歌曲封面**里取一个"鲜艳色"当强调色。
 *
 * 为什么不用平均色：封面里通常有大片暗背景或灰白文字，平均下来是一片浑浊的灰褐色，
 * 当强调色既不好看也不醒目。这里改成打分挑色：
 *   分数 = 饱和度 × 亮度适中度（越接近略偏亮的中间调越好）
 * 挑中之后再把饱和度/亮度**拉到可用区间**，保证在深色背景上足够显眼、
 * 又不会因为原图过暗/过灰而变成看不清的颜色。
 *
 * 取图只有 96px（只要颜色，不要细节），并且走与播放页背景**同一个来源**
 * （CoverStore 优先、ArtworkStore 兜底），避免 ncm 转换来的文件取不到封面。
 * ArtworkStore 与本文件同属 ui 包，不需要额外 import。
 */
private const val ACCENT_REQUEST_PX = 96

/** 从位图里挑一个鲜艳色；取不到时返回 null。 */
fun vibrantAccent(bmp: ImageBitmap): Color? {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return null

    val pixels = bmp.toPixelMap()
    var bestScore = -1f
    var bestR = 0f
    var bestG = 0f
    var bestB = 0f

    val step = max(1, min(w, h) / 32)          // 最多约 32x32 个采样点
    var y = step / 2
    while (y < h) {
        var x = step / 2
        while (x < w) {
            val col = pixels[x, y]
            if (col.alpha >= 0.125f) {         // 忽略近乎透明的像素
                val r = col.red
                val g = col.green
                val b = col.blue
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val sat = if (mx <= 0f) 0f else (mx - mn) / mx
                // 太暗或太亮的都不适合当强调色；理想亮度约 0.62
                val lumFit = 1f - abs(mx - 0.62f).coerceAtMost(0.62f) / 0.62f
                val score = sat * 0.75f + sat * lumFit * 0.25f
                if (score > bestScore) {
                    bestScore = score
                    bestR = r; bestG = g; bestB = b
                }
            }
            x += step
        }
        y += step
    }
    if (bestScore < 0f) return null

    // 拉到可用区间：保证最小饱和度与亮度，暗色封面也不会取到"看不见的颜色"
    val mx = max(bestR, max(bestG, bestB))
    val mn = min(bestR, min(bestG, bestB))
    val sat = if (mx <= 0f) 0f else (mx - mn) / mx
    val targetSat = max(sat, 0.55f)
    val targetVal = min(max(mx, 0.72f), 0.92f)

    fun channel(c: Float): Float {
        val rel = if (mx <= 0f) 0f else (c - mn) / mx      // 归一化到 0..1 的相对位置
        return (targetVal * (1f - targetSat) + targetVal * targetSat * rel).coerceIn(0f, 1f)
    }
    return Color(red = channel(bestR), green = channel(bestG), blue = channel(bestB))
}

/**
 * 记住当前歌曲的封面主题色。歌曲切换时自动重算；取不到封面时返回 null，
 * 调用方用 `?: scheme.primary` 回落到默认主题色即可。
 */
@Composable
fun rememberPlayerAccent(song: Song?): Color? {
    val context = LocalContext.current
    val state = produceState<Color?>(initialValue = null, song?.uri) {
        value = withContext(Dispatchers.IO) {
            val s = song ?: return@withContext null
            val bmp = CoverStore.decode(context.applicationContext, s.uri, requestPx = ACCENT_REQUEST_PX)
                ?: ArtworkStore.load(context.applicationContext, s, requestPx = ACCENT_REQUEST_PX)
            bmp?.let { vibrantAccent(it.asImageBitmap()) }
        }
    }
    return state.value
}
