// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.localmusic.app.data.UiMode
import com.localmusic.app.data.UiSettings
import com.localmusic.app.data.UiSkin
import com.liquidmiuix.theme.LiquidAccentColor
import com.liquidmiuix.theme.LiquidTheme

/**
 * 应用主题入口：皮肤 × 深浅两轴。
 *
 *  - `NOTHING`：黑白灰红 + 点阵图标，走 [NothingSkin] 的覆盖配色；
 *  - `M3E`：原来的液态玻璃（MiuiX / Monet），强调色由用户选择。
 *
 * 两套都支持跟随系统 / 浅色 / 深色。状态栏图标明暗在 [SideEffect] 里统一同步，
 * 因为 LiquidTheme 只知道自己的 dark，而 Nothing 浅色模式的背景与它一致。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (UiSettings.mode) {
        UiMode.SYSTEM -> isSystemInDarkTheme()
        UiMode.LIGHT -> false
        UiMode.DARK -> true
    }
    val activity = LocalContext.current as? Activity

    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    when (UiSettings.skin) {
        UiSkin.NOTHING -> LiquidTheme(
            accentColor = LiquidAccentColor.MiuBlue,
            darkTheme = dark,
        ) {
            SerifTextTheme {
                NothingSkin(dark = dark) {
                    content()
                }
            }
        }

        UiSkin.M3E -> LiquidTheme(
            accentColor = UiSettings.accent,
            darkTheme = dark,
        ) {
            SerifTextTheme {
                content()
            }
        }
    }
}
