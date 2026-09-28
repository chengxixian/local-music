// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmiuix.glass.GlassRegularBlurRadius
import com.liquidmiuix.glass.liquidGlass
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃面板。
 *
 * ⚠️ 库的绘制顺序是 `onDrawBehind → drawBackdropLayer(含形状裁剪) → onDrawSurface → drawContent()`，
 * 子内容画在玻璃那一层的裁剪里。所以玻璃修饰符必须挂在**不含子内容**的 Box 上，
 * 内容做它的**兄弟**；否则超出面板的子元素会被裁掉。
 *
 * ⚠️ 尺寸的坑：玻璃层用 `matchParentSize`（它不参与父级测量），所以**必须有一个普通子项**
 * 决定外层的尺寸。若内容层也用 `matchParentSize`，外层在"没有显式高度"的场合会塌成 0 —— 
 * 表现就是"弹窗逻辑明明执行了，却什么都看不见"（踩过一次）。
 *
 * 注意：玻璃只能采样**同一个窗口**里的采集层。所以「弹窗」如果是真正的 Dialog 窗口，
 * 就折射不到任何东西 —— 本项目的弹窗都是画在同一窗口里的浮层（见 [com.localmusic.app.ui.ExportFolderPrompt]）。
 */
@Composable
fun GlassPanel(
    backdrop: LayerBackdrop?,
    shape: Shape,
    modifier: Modifier = Modifier,
    blurRadius: Dp = GlassRegularBlurRadius,
    refractionHeight: Dp = 28.dp,
    refractionAmount: Dp = 40.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Box(
            Modifier.matchParentSize().liquidGlass(
                backdrop = backdrop,
                shape = shape,
                blurRadius = blurRadius,
                refractionHeight = refractionHeight,
                refractionAmount = refractionAmount,
            )
        )
        // 普通子项：由它决定外层的尺寸（见上面那条尺寸说明）
        Box { content() }
    }
}

/**
 * 独立的液态玻璃图标按钮（圆形）。
 *
 * 用于播放页的「返回 / 换封面 / 均衡器」这类**单个动作**：不再把图标塞进一整条大玻璃里，
 * 而是各自一个小玻璃圆钮，折射量按小尺寸单独调小（高度只有 44dp，40dp 的位移会糊成一团）。
 */
@Composable
fun GlassIconButton(
    backdrop: LayerBackdrop?,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = MiuixTheme.colorScheme.onSurface,
) {
    Box(modifier.size(size).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize().liquidGlass(
                backdrop = backdrop,
                shape = CircleShape,
                blurRadius = 4.dp,
                refractionHeight = 10.dp,
                refractionAmount = 18.dp,
            )
        )
        Icon(icon, contentDescription, Modifier.size(size * 0.5f), tint = tint)
    }
}
