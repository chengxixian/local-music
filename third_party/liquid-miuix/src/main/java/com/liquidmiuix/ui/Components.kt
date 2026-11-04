package com.liquidmiuix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liquidmiuix.theme.LiquidMotion
import com.liquidmiuix.theme.LiquidRadii
import com.liquidmiuix.theme.LiquidSpacing
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 组件库。
 *
 * 与 miuix 自身的写法保持一致：
 *
 * - **卡片用 miuix `Card`**，不铺玻璃。它的默认色是 `surfaceContainer`
 *   （浅色纯白 / 深色 #242424），而页面底色是 `surface`（浅色 #F7F7F7 / 深色 #000）——
 *   两者天然差一档，靠"圆角 + 底色差"分层，**不用阴影**。
 * - **颜色一律取 `MiuixTheme.colorScheme.*`**，不写死十六进制。
 * - **文字一律用 `MiuixTheme.textStyles.*`**，不写死 sp。
 * - 列表项结构：`Row(spacedBy(13.dp)) { Icon(24dp) + Column { title(title4) + body(body2) } }`
 * - 玻璃只用在**悬浮底栏**上 —— 那里有 backdrop 的折射，效果才明显；
 *   卡片铺玻璃在浅色背景下根本看不出来（模糊是低通滤波，纯色背景糊前糊后一样）。
 */

/** 全局页面容器：统一下边距，内容可从玻璃底栏下滚过。 */
@Composable
fun LiquidPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = LiquidSpacing.page,
        end = LiquidSpacing.page,
        top = LiquidSpacing.page,
        bottom = LiquidSpacing.page,
    ),
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
        content = { item { content() } },
    )
}

/**
 * 卡片（miuix Card + 统一内边距）。
 *
 * @param onClick 非 null 时整卡可点，并带按压缩放反馈
 */
@Composable
fun LiquidCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = LiquidSpacing.card,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(LiquidSpacing.item),
        ) {
            content()
        }
    }
}

/** miuix Card 的内容作用域就是 Column，这里给个别名避免误用。 */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/**
 * 信息行：`[图标 24dp] 标题(title4) / 值(body2, onSurfaceVariantSummary)`。
 *
 * 这是最常用的行样式（图标 + 标题 + 副标题 + 尾随控件）。
 */
@Composable
fun LiquidInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.leading),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (tint == Color.Unspecified) MiuixTheme.colorScheme.primary else tint,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.title4,
            )
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = if (valueColor == Color.Unspecified) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    valueColor
                },
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 可点击的列表项：`[图标] 标题/描述 [尾部]`，带按压缩放。
 *
 * [showDivider] 为 true 时在底部画分隔线（组内非末项传 true）。
 */
@Composable
fun LiquidListItem(
    title: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null && enabled) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = LiquidSpacing.card, vertical = LiquidSpacing.item),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.leading),
        ) {
            if (leading != null) {
                Icon(
                    imageVector = leading,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    color = if (titleColor == Color.Unspecified) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        titleColor
                    },
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(LiquidSpacing.tight))
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = LiquidSpacing.card)
                    .height(1.dp)
                    .background(MiuixTheme.colorScheme.dividerLine)
            )
        }
    }
}

/** 胶囊标签。 */
@Composable
fun LiquidPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (color == Color.Unspecified) {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        } else {
            color
        },
        modifier = modifier
            .clip(RoundedCornerShape(LiquidRadii.small))
            .background(
                if (background == Color.Unspecified) {
                    MiuixTheme.colorScheme.surfaceVariant
                } else {
                    background
                }
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 状态提示条：`[图标] 文字`，用语义色着色而不铺满色块
 * （用于错误、警示、说明这类整条提示）。
 */
@Composable
fun LiquidStatusBanner(
    text: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LiquidRadii.medium))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = LiquidSpacing.card, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 进度条，用主题色。 */
@Composable
fun LiquidProgress(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(50))
            .background(MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(MiuixTheme.colorScheme.primary)
        )
    }
}

/** 空状态。 */
@Composable
fun LiquidEmptyState(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LiquidSpacing.tight),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }
    }
}

/** 列表错峰入场：第 [index] 项延迟 `index * 40ms` 后弹簧入场。 */
@Composable
fun Modifier.staggeredEntry(
    index: Int,
    baseDelayMillis: Long = LiquidMotion.StaggerMillis,
    initialOffsetY: Dp = 26.dp,
    maxStaggerIndex: Int = 8,
): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(index) {
        if (progress.value == 0f) {
            // 上限 8 项：否则列表越长、靠后的项等待越久（第 30 项要等 1.2s）
            delay(index.coerceIn(0, maxStaggerIndex) * baseDelayMillis)
        }
        progress.animateTo(1f, animationSpec = LiquidMotion.bouncy<Float>())
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * initialOffsetY.toPx()
        // 轻微放大，让入场有"浮起"的层次感
        val s = 0.94f + 0.06f * p
        scaleX = s
        scaleY = s
    }
}
