// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.localmusic.app.R

/**
 * **播放页风格**。只影响"播放页这一屏"，与皮肤/主题/其它页面无关。
 *
 *  - [GLASS]：现有的液态玻璃播放页（默认）。默认值选它，于是"没改过偏好"的用户
 *    看到的像素与引入本选项之前逐帧一致。
 *  - [WAVE]：第二套播放页 —— 封面模糊铺底 + 圆钮控件 + 大圆角主键 + **波浪形进度条** + 歌词渐隐。
 *
 * 两者共用同一份状态与回调（`PlaybackUi` / 队列 / 收藏 / 均衡器入口），
 * 所以切换风格**不会**影响播放、USB 直通、队列顺序——只是换一层皮。
 */
enum class PlayerStyle(val id: String, @StringRes val labelRes: Int) {
    GLASS("glass", R.string.player_style_glass),
    WAVE("wave", R.string.player_style_wave),
    ;

    companion object {
        fun fromId(v: String?): PlayerStyle = entries.firstOrNull { it.id == v } ?: GLASS
    }
}

/**
 * 播放页风格状态（可观察）。
 *
 * 理由与语言偏好那一套相同：SharedPreferences **不是** Compose 的可观察源，
 * 设置页改完偏好后，已经组合好的播放页不会重组。所以这里放一个进程内的
 * `mutableStateOf` 当真源：写它 → 读它的地方（MainActivity 里那个 `if`）立刻重组；
 * 偏好只负责"下次启动还记得"。
 */
object PlayerStyleState {
    var current by mutableStateOf(PlayerStyle.GLASS)

    /** 启动时从偏好汇入（`MainActivity.onCreate` 调一次）。 */
    fun load(context: Context) {
        current = PlayerStylePref.current(context)
    }

    /** 设置页那一行点击：在两套播放页之间循环 —— 即时生效 + 落盘。 */
    fun cycleAndWrite(context: Context): PlayerStyle {
        val next = PlayerStylePref.cycle(context)
        current = next
        return next
    }
}

object PlayerStylePref {

    private const val KEY = "playerStyle"

    /** 与语言 / 皮肤 / 扫描等偏好**同一个** prefs 文件（"settings"）。 */
    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun current(context: Context): PlayerStyle =
        PlayerStyle.fromId(prefs(context).getString(KEY, null))

    fun set(context: Context, style: PlayerStyle) {
        prefs(context).edit().putString(KEY, style.id).apply()
    }

    fun cycle(context: Context): PlayerStyle {
        val next = if (current(context) == PlayerStyle.GLASS) PlayerStyle.WAVE else PlayerStyle.GLASS
        set(context, next)
        return next
    }
}
