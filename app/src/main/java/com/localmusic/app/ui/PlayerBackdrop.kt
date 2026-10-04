// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, song?.uri) {
        value = withContext(Dispatchers.IO) {
            song?.let { ArtworkStore.load(context.applicationContext, it, requestPx = 192)?.asImageBitmap() }
        }
    }
    Box(modifier.fillMaxSize()) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // 放大一点点：模糊会把边缘拉出透明/暗边，撑开就没缝了
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = 52.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
            )
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
