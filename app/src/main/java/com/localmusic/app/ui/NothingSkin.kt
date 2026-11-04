// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * NOTHING 皮肤配色。
 *
 * 只改颜色，不动布局 / 尺寸 / 交互：
 *  - 底色压到纯黑（浅色模式压到接近纯白）；
 *  - 强调色统一用信号红，且**只做点缀**：正在播放的标题、喜欢的红心、进度条手柄、
 *    当前播放队列项。其余选中态一律白（深色）/ 黑（浅色）；
 *  - 其余彩色（蓝、紫、青）全部去掉。
 *
 * 做法：miuix 的语义色是一个可 `copy` 的 Colors 实例，主题本身仍由 liquid-miuix
 * 提供（玻璃、组件都读 MiuixTheme.colorScheme），这里在它外面再包一层覆盖，
 * 所以所有页面、所有玻璃组件会一起换色，而不需要改它们的代码。
 */
internal data class NothingColors(
    val background: Color,
    val panel: Color,
    val panelHigh: Color,
    val panelHighest: Color,
    val panelVariant: Color,
    val outline: Color,
    val divider: Color,
    val text: Color,
    val textHi: Color,
    val textMid: Color,
    val textLo: Color,
    val ink: Color,
    val onInk: Color,
)

internal val NothingDark = NothingColors(
    background = Color(0xFF000000),
    panel = Color(0xFF121212),
    panelHigh = Color(0xFF1A1A1A),
    panelHighest = Color(0xFF222222),
    panelVariant = Color(0xFF0C0C0C),
    outline = Color(0xFF2E2E2E),
    divider = Color(0xFF242424),
    text = Color(0xFFF2F2F2),
    textHi = Color(0xFFA6A6A6),
    textMid = Color(0xFF808080),
    textLo = Color(0xFF5A5A5A),
    ink = Color(0xFFF2F2F2),
    onInk = Color(0xFF000000),
)

internal val NothingLight = NothingColors(
    background = Color(0xFFF5F5F5),
    panel = Color(0xFFFFFFFF),
    panelHigh = Color(0xFFEFEFEF),
    panelHighest = Color(0xFFE6E6E6),
    panelVariant = Color(0xFFFAFAFA),
    outline = Color(0xFFD6D6D6),
    divider = Color(0xFFE4E4E4),
    text = Color(0xFF101010),
    textHi = Color(0xFF555555),
    textMid = Color(0xFF6E6E6E),
    textLo = Color(0xFF9A9A9A),
    ink = Color(0xFF101010),
    onInk = Color(0xFFFFFFFF),
)

/** 信号红：唯一保留的彩色。 */
internal val SignalRed = Color(0xFFE1191F)
internal val SignalRedHi = Color(0xFFFF4B48)

@Composable
fun NothingSkin(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) NothingDark else NothingLight
    val base = MiuixTheme.colorScheme
    val colors = base.copy(
        primary = p.ink,
        onPrimary = p.onInk,
        primaryVariant = p.textHi,
        onPrimaryVariant = p.onInk,
        primaryContainer = p.panelHigh,
        onPrimaryContainer = p.text,

        secondary = p.text,
        onSecondary = p.background,
        secondaryVariant = p.textHi,
        onSecondaryVariant = p.background,
        secondaryContainer = p.panel,
        onSecondaryContainer = p.text,
        secondaryContainerVariant = p.panelHigh,
        onSecondaryContainerVariant = p.textHi,

        tertiaryContainer = p.panelHigh,
        onTertiaryContainer = p.textHi,
        tertiaryContainerVariant = p.panel,

        background = p.background,
        onBackground = p.text,
        onBackgroundVariant = p.textHi,
        surface = p.background,
        onSurface = p.text,
        surfaceVariant = p.panelVariant,
        onSurfaceSecondary = p.textHi,
        onSurfaceVariantSummary = p.textMid,
        onSurfaceVariantActions = p.textHi,
        disabledOnSurface = p.textLo,

        surfaceContainer = p.panel,
        onSurfaceContainer = p.text,
        onSurfaceContainerVariant = p.textHi,
        surfaceContainerHigh = p.panelHigh,
        onSurfaceContainerHigh = p.text,
        surfaceContainerHighest = p.panelHighest,
        onSurfaceContainerHighest = p.text,

        outline = p.outline,
        dividerLine = p.divider,
        windowDimming = Color(0xB3000000),

        error = SignalRed,
        onError = Color.White,
        errorContainer = if (dark) Color(0xFF3A0A0C) else Color(0xFFFFDAD8),
        onErrorContainer = if (dark) SignalRedHi else Color(0xFF8A1114),

        disabledPrimary = p.textLo,
        disabledOnPrimary = p.textHi,
        disabledPrimaryButton = p.panelHighest,
        disabledOnPrimaryButton = p.textMid,
        disabledPrimarySlider = p.outline,
        disabledSecondary = p.panelHighest,
        disabledOnSecondary = p.textMid,
        disabledSecondaryVariant = p.outline,
        disabledOnSecondaryVariant = p.textMid,

        sliderKeyPoint = SignalRed,
        sliderKeyPointForeground = Color.White,
        sliderBackground = p.outline,
    )

    MiuixTheme(colors = colors) {
        // Material3 那一层也跟着走：少数 Dialog / TextField / Card 用的是它。
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                primary = p.ink,
                onPrimary = p.onInk,
                primaryContainer = p.panelHigh,
                onPrimaryContainer = p.text,
                secondary = p.text,
                onSecondary = p.background,
                secondaryContainer = p.panel,
                onSecondaryContainer = p.text,
                tertiary = p.textHi,
                onTertiary = p.background,
                background = p.background,
                onBackground = p.text,
                surface = p.background,
                onSurface = p.text,
                surfaceVariant = p.panelVariant,
                onSurfaceVariant = p.textHi,
                outline = p.outline,
                outlineVariant = p.divider,
                error = SignalRed,
                onError = Color.White,
                errorContainer = if (dark) Color(0xFF3A0A0C) else Color(0xFFFFDAD8),
                onErrorContainer = if (dark) SignalRedHi else Color(0xFF8A1114),
                surfaceContainer = p.panel,
                surfaceContainerHigh = p.panelHigh,
                surfaceContainerHighest = p.panelHighest,
                surfaceTint = SignalRed,
            ),
            content = content,
        )
    }
}
