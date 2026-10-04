// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localmusic.app.data.CoverStore
import com.localmusic.app.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放页的背景：当前歌曲封面**放大 + 高斯模糊**，再叠一层上下深、中间透的渐变。
 *
 * 两个要点：
 *  1. 它要画在**采集层之内**（`layerBackdrop` 里）。否则播放页那些玻璃（封面光环、控件面板、
 *     dock）折射到的还是它背后的曲库页，玻璃就会"文不对题"。
 *  2. 只取一张很小的图（192px）来糊：模糊后细节全无，取大图纯属浪费内存与解码时间。
 */
@Composable
fun PlayerBackdrop(song: Song?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<Pair<androidx.compose.ui.graphics.ImageBitmap, Color>?>(null, song?.uri) {
        value = withContext(Dispatchers.IO) {
            song?.let { s ->
                // ⚠️ 必须和播放页走同一个来源：ArtworkStore 只读系统缩略图/内嵌标签，
                // **不读刮削下来的封面**（ncm 转换来的文件几乎没有系统缩略图），
                // 所以只调 ArtworkStore 会拿到 null → 上一层就等于没画。
                // 位图要够大：只有 192px 的话，放大到 1080 宽本身就糊，再叠模糊就成色块了。
                // 取 384px 才留得住封面细节，"降低模糊程度"时才有东西可看。
                val bmp = CoverStore.decode(context.applicationContext, s.uri, requestPx = 384)
                    ?: ArtworkStore.load(context.applicationContext, s, requestPx = 384)
                bmp?.let { bmp ->
                    // 取平均色当作兜底底色：模糊图的边缘一定是半透明的，
                    // 不铺一层不透明的底，下面就一定会漏出曲库页（上一版就是这样露的）。
                    var r = 0L; var g = 0L; var b = 0L; var n = 0
                    val step = (bmp.width / 16).coerceAtLeast(1)
                    var y = 0
                    while (y < bmp.height) {
                        var x = 0
                        while (x < bmp.width) {
                            val c = bmp.getPixel(x, y)
                            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                            x += step
                        }
                        y += step
                    }
                    val avg = if (n == 0) Color.Black else Color(
                        red = (r / n / 255f).toFloat(),
                        green = (g / n / 255f).toFloat(),
                        blue = (b / n / 255f).toFloat(),
                    )
                    bmp.asImageBitmap() to avg
                }
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        val pair = state
        if (pair != null) {
            // ① 不透明兜底色（保证严丝合缝盖住下层）
            Box(Modifier.fillMaxSize().background(pair.second))
            // ② 高清模糊封面：放大撑出屏幕，糊掉的边缘落在可视区外
            Image(
                bitmap = pair.first,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                    }
                    .blur(radius = 20.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle),
            )
        } else {
            // 封面还没加载出来时也要不透明，不能漏出曲库页
            Box(Modifier.fillMaxSize().background(Color(0xFF101014)))
        }
        // 不压死：中间留透（玻璃才有东西可折射），只在上下加深，保证歌名/控件读得清
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.42f),
                        0.35f to Color.Black.copy(alpha = 0.18f),
                        0.62f to Color.Black.copy(alpha = 0.22f),
                        1f to Color.Black.copy(alpha = 0.55f),
                    )
                )
        )
    }
}
