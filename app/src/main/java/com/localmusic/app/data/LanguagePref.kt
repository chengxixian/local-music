// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.app.LocaleManager

/**
 * 应用语言。
 *
 * 约定（用户要求"英语优先、跟随用户语言"）：
 *  · `res/values/` 放**英文** —— 它是所有未翻译语言的兜底，所以日/俄/法用户看到的是英文而不是中文
 *  · `res/values-zh/` 放中文
 *  · 支持的语言在 `res/xml/locales_config.xml` 里声明，系统设置里也能改
 *
 * 切换走 Android 13+ 的 **LocaleManager**（本项目 minSdk 33，正好可用），
 * 不需要 AppCompat：设置后系统会重建 Activity，界面即时切换。
 */
object LanguagePref {

    private const val KEY = "appLanguage"

    /** null = 跟随系统。 */
    val options: List<Pair<String?, String>> = listOf(
        null to "跟随系统 / System",
        "en" to "English",
        "zh-CN" to "简体中文",
        "ja" to "日本語",
        "ru" to "Русский",
    )

    fun currentTag(context: Context): String? = context
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString(KEY, null)

    fun label(context: Context): String =
        options.firstOrNull { it.first == currentTag(context) }?.second ?: options.first().second

    /** 记住选择并立即生效；传 null 表示跟随系统。 */
    fun set(context: Context, tag: String?) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString(KEY, tag).apply()
        apply(context, tag)
    }

    /** 启动时按上次的选择套用一次（跟随系统就清空覆盖）。 */
    fun applyStored(context: Context) = apply(context, currentTag(context))

    private fun apply(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales =
            if (tag.isNullOrBlank()) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(tag)
    }
}
