// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.localmusic.app.R
import com.liquidmiuix.theme.LiquidAccentColor

/**
 * 界面皮肤。
 *
 *  - [NOTHING]：点阵皮肤 —— 纯黑 / 白 / 灰 + 信号红，图标点阵化。
 *  - [M3E]：原本的液态玻璃界面（Material 3 Expressive 风格），保留原有强调色体系。
 */
enum class UiSkin(val id: String, @StringRes val labelRes: Int) {
    NOTHING("nothing", R.string.skin_nothing),
    M3E("m3e", R.string.skin_m3e),
    ;

    companion object {
        fun fromId(v: String?): UiSkin = entries.firstOrNull { it.id == v } ?: NOTHING
    }
}

/** 深 / 浅模式。默认深色 —— 两套皮肤都是深色更好看，但用户可以改。 */
enum class UiMode(val id: String, @StringRes val labelRes: Int) {
    SYSTEM("system", R.string.ui_mode_system),
    LIGHT("light", R.string.ui_mode_light),
    DARK("dark", R.string.ui_mode_dark),
    ;

    companion object {
        fun fromId(v: String?): UiMode = entries.firstOrNull { it.id == v } ?: DARK
    }
}

/** M3E 皮肤下可选的强调色（在原库的 6 种之外又补了 5 种）。 */
data class AccentOption(
    val value: LiquidAccentColor,
    @StringRes val labelRes: Int,
    val preview: Color,
    val isDynamic: Boolean = false,
)

val AccentOptions: List<AccentOption> = listOf(
    AccentOption(LiquidAccentColor.Dynamic, R.string.accent_dynamic, Color(0xFF8A8A8A), isDynamic = true),
    AccentOption(LiquidAccentColor.MiuBlue, R.string.accent_miublue, Color(0xFF3482FF)),
    AccentOption(LiquidAccentColor.Blue, R.string.accent_blue, Color(0xFF415F91)),
    AccentOption(LiquidAccentColor.Violet, R.string.accent_violet, Color(0xFF6750A4)),
    AccentOption(LiquidAccentColor.Green, R.string.accent_green, Color(0xFF356A35)),
    AccentOption(LiquidAccentColor.Orange, R.string.accent_orange, Color(0xFF8B4F23)),
    AccentOption(LiquidAccentColor.Red, R.string.accent_red, Color(0xFFD71921)),
    AccentOption(LiquidAccentColor.Cyan, R.string.accent_cyan, Color(0xFF00696D)),
    AccentOption(LiquidAccentColor.Amber, R.string.accent_amber, Color(0xFF8B5000)),
    AccentOption(LiquidAccentColor.Rose, R.string.accent_rose, Color(0xFF9C4146)),
    AccentOption(LiquidAccentColor.Lime, R.string.accent_lime, Color(0xFF4F6A00)),
)

/**
 * 界面偏好（皮肤 / 深浅 / 强调色）。
 *
 * 和 [PlayerStyleState] 同一套写法：内存里一份 Compose 可观察的真源，
 * SharedPreferences 只负责"下次启动还记得"。所有读它的界面都会即时重组。
 */
object UiSettings {
    var skin by mutableStateOf(UiSkin.NOTHING)
        private set
    var mode by mutableStateOf(UiMode.DARK)
        private set
    var accent by mutableStateOf(LiquidAccentColor.Dynamic)
        private set

    private const val KEY_SKIN = "uiSkin"
    private const val KEY_MODE = "uiMode"
    private const val KEY_ACCENT = "uiAccent"

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        skin = UiSkin.fromId(p.getString(KEY_SKIN, null))
        mode = UiMode.fromId(p.getString(KEY_MODE, null))
        accent = LiquidAccentColor.fromId(p.getString(KEY_ACCENT, null))
    }

    fun setSkin(context: Context, value: UiSkin) {
        skin = value
        prefs(context).edit().putString(KEY_SKIN, value.id).apply()
    }

    fun setMode(context: Context, value: UiMode) {
        mode = value
        prefs(context).edit().putString(KEY_MODE, value.id).apply()
    }

    fun setAccent(context: Context, value: LiquidAccentColor) {
        accent = value
        prefs(context).edit().putString(KEY_ACCENT, value.name).apply()
    }
}
