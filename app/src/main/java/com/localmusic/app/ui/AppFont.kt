// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局字体：开源衬线体。
 *
 * 用 Android 系统自带的 `serif` 家族 —— 它就是 **Noto Serif**（SIL OFL 1.1，开源），
 * 系统 fonts.xml 里还把 `times` / `times new roman` 直接别名到 `serif`，
 * 所以这就是设备上"开源版 Times New Roman"的实际落点：
 *  - 拉丁字母 / 数字：Noto Serif（与 Times 同为过渡期衬线，阅读感接近）；
 *  - 中文：Noto Serif CJK（同样开源），不会出现"中文还是黑体"的割裂。
 *
 * 不打包字体文件：APK 不增加体积，也不引入额外的字体授权。
 * 点阵字（DotText / 5×5 图标）是自绘的，不受这里影响。
 */
val AppSerif: FontFamily = FontFamily.Serif

/**
 * 把 miuix 的 14 档文字样式和 Material3 的 15 档排版整体换成 [AppSerif]。
 * 只改字体，字号 / 行高 / 字重 / 字距全部沿用原值，所以布局不会跳。
 */
@Composable
fun SerifTextTheme(content: @Composable () -> Unit) {
    val base = MiuixTheme.textStyles
    val styles = base.copy(
        main = base.main.copy(fontFamily = AppSerif),
        paragraph = base.paragraph.copy(fontFamily = AppSerif),
        body1 = base.body1.copy(fontFamily = AppSerif),
        body2 = base.body2.copy(fontFamily = AppSerif),
        button = base.button.copy(fontFamily = AppSerif),
        footnote1 = base.footnote1.copy(fontFamily = AppSerif),
        footnote2 = base.footnote2.copy(fontFamily = AppSerif),
        headline1 = base.headline1.copy(fontFamily = AppSerif),
        headline2 = base.headline2.copy(fontFamily = AppSerif),
        subtitle = base.subtitle.copy(fontFamily = AppSerif),
        title1 = base.title1.copy(fontFamily = AppSerif),
        title2 = base.title2.copy(fontFamily = AppSerif),
        title3 = base.title3.copy(fontFamily = AppSerif),
        title4 = base.title4.copy(fontFamily = AppSerif),
    )
    val typography = MaterialTheme.typography
    val m3 = typography.copy(
        displayLarge = typography.displayLarge.copy(fontFamily = AppSerif),
        displayMedium = typography.displayMedium.copy(fontFamily = AppSerif),
        displaySmall = typography.displaySmall.copy(fontFamily = AppSerif),
        headlineLarge = typography.headlineLarge.copy(fontFamily = AppSerif),
        headlineMedium = typography.headlineMedium.copy(fontFamily = AppSerif),
        headlineSmall = typography.headlineSmall.copy(fontFamily = AppSerif),
        titleLarge = typography.titleLarge.copy(fontFamily = AppSerif),
        titleMedium = typography.titleMedium.copy(fontFamily = AppSerif),
        titleSmall = typography.titleSmall.copy(fontFamily = AppSerif),
        bodyLarge = typography.bodyLarge.copy(fontFamily = AppSerif),
        bodyMedium = typography.bodyMedium.copy(fontFamily = AppSerif),
        bodySmall = typography.bodySmall.copy(fontFamily = AppSerif),
        labelLarge = typography.labelLarge.copy(fontFamily = AppSerif),
        labelMedium = typography.labelMedium.copy(fontFamily = AppSerif),
        labelSmall = typography.labelSmall.copy(fontFamily = AppSerif),
    )

    MiuixTheme(
        colors = MiuixTheme.colorScheme,
        textStyles = styles,
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            typography = m3,
            shapes = MaterialTheme.shapes,
        ) {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = AppSerif),
            ) {
                content()
            }
        }
    }
}
