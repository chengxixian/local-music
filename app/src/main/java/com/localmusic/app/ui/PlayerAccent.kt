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
import kotlin.math.max
import kotlin.math.min

/**
 * 播放页 / dock 的主题色：取**当前歌曲封面的平均色**。
 *
 * 刻意不做"鲜艳色提纯"：封面里的高饱和区块往往很跳，抽出来当强调色会喧宾夺主；
 * 平均色更接近封面整体的氛围（和播放页背景的兜底色用的是同一套算法），
 * 在深色界面上低调、不刺眼。
 *
 * 取图只有 96px（只要颜色、不要细节），来源与播放页背景一致
 * （CoverStore 优先、ArtworkStore 兜底），ncm 转换来的文件也能取到。
 * 取不到封面时返回 null，调用方用 `?: scheme.primary` 回落即可。
 * ArtworkStore 与本文件同属 ui 包，不需要额外 import。
 */
private const val ACCENT_REQUEST_PX = 96

/** 封面平均色；取不到时返回 null。 */
fun averageCoverColor(bmp: ImageBitmap): Color? {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return null

    val pixels = bmp.toPixelMap()
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var n = 0
    val step = max(1, min(w, h) / 16)          // 最多约 16x16 个采样点，够算平均色
    var y = step / 2
    while (y < h) {
        var x = step / 2
        while (x < w) {
            val c = pixels[x, y]
            if (c.alpha >= 0.125f) {           // 忽略近乎透明的像素
                r += c.red
                g += c.green
                b += c.blue
                n++
            }
            x += step
        }
        y += step
    }
    if (n == 0) return null
    return Color(red = (r / n).toFloat(), green = (g / n).toFloat(), blue = (b / n).toFloat())
}

/**
 * 记住当前歌曲封面平均色。歌曲切换时自动重算；取不到封面时返回 null。
 */
@Composable
fun rememberPlayerAccent(song: Song?): Color? {
    val context = LocalContext.current
    val state = produceState<Color?>(initialValue = null, song?.uri) {
        value = withContext(Dispatchers.IO) {
            val s = song ?: return@withContext null
            val bmp = CoverStore.decode(context.applicationContext, s.uri, requestPx = ACCENT_REQUEST_PX)
                ?: ArtworkStore.load(context.applicationContext, s, requestPx = ACCENT_REQUEST_PX)
            bmp?.let { averageCoverColor(it.asImageBitmap()) }
        }
    }
    return state.value
}
