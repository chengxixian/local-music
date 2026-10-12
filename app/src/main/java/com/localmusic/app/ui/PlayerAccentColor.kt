// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * 播放页的**自适应取色**（封面平均色 → 主题色）。
 *
 * 这段逻辑单独放一个文件，是为了能**直接单元测试** —— 之前它躲在 Composable 文件里、
 * 又依赖 `android.graphics.Color` 的 HSV 转换，结果出了一个只有真机才看得见的 bug：
 *
 *  > 黑白 / 灰白封面的平均色饱和度接近 0，而 HSV 里**饱和度 0 的色相是无意义的 0（红）**。
 *  > 我原先写的是 `saturation = saturation.coerceAtLeast(0.45)`，于是"把灰提饱和"= 提纯红，
 *  > 整屏被染成砖红。用户实测的"自适应取色效果不对"就是这个。
 *
 * 现在的规则：
 *  - 饱和度低于 [NeutralSaturation] 视为**中性色**：只调明度，**绝不注入色相**；
 *  - 只有本身就带色相的封面才提饱和（且上限 0.62，避免荧光感）。
 */
private const val NeutralSaturation = 0.12f

/** RGB(0..1) → HSV：返回 `[hue 0..360, saturation 0..1, value 0..1]`。纯 Kotlin，不碰 Android 框架。 */
internal fun toHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val hueRaw = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    val hue = if (hueRaw < 0f) hueRaw + 360f else hueRaw
    val saturation = if (max <= 0f) 0f else d / max
    return floatArrayOf(hue, saturation, max)
}

/** HSV → Color。 */
internal fun fromHsv(hue: Float, saturation: Float, value: Float): Color {
    val c = value * saturation
    val h = ((if (hue < 0f) hue + 360f else hue) / 60f)
    val x = c * (1f - abs(h % 2f - 1f))
    val r: Float
    val g: Float
    val b: Float
    when {
        h < 1f -> { r = c; g = x; b = 0f }
        h < 2f -> { r = x; g = c; b = 0f }
        h < 3f -> { r = 0f; g = c; b = x }
        h < 4f -> { r = 0f; g = x; b = c }
        h < 5f -> { r = x; g = 0f; b = c }
        else -> { r = c; g = 0f; b = x }
    }
    val m = value - c
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

/** 这个颜色是否"本该是灰的"（没有可用的色相）。 */
internal fun isNeutral(color: Color): Boolean = toHsv(color)[1] < NeutralSaturation

/**
 * 提饱和：**只有本身带色相的封面才会被加强**；灰/黑白封面原样返回。
 *
 * 上限压在 0.62：再高会变成荧光色，和"哑光 / 高级灰"的观感相冲。
 */
internal fun vivid(color: Color): Color {
    val hsv = toHsv(color)
    val s = hsv[1]
    if (s < NeutralSaturation) return color
    return fromHsv(hsv[0], (s * 1.6f).coerceIn(NeutralSaturation, 0.62f), hsv[2])
}

/**
 * 按背景明暗把颜色推到"看得清"的明度区间：
 * 深底 `value >= 0.94`、浅底 `value <= 0.56`。
 *
 * 关键：**中性色只推明度**。给灰加饱和就等于加红（HSV 里灰的色相是 0），
 * 那正是把黑白封面染红的元凶。
 */
internal fun readable(color: Color, darkBackdrop: Boolean): Color {
    val hsv = toHsv(color)
    val neutral = hsv[1] < NeutralSaturation
    val value = if (darkBackdrop) hsv[2].coerceAtLeast(0.94f) else hsv[2].coerceAtMost(0.56f)
    val saturation = if (neutral) {
        hsv[1]
    } else {
        if (darkBackdrop) hsv[1].coerceAtLeast(0.40f) else hsv[1].coerceAtLeast(0.55f)
    }
    return fromHsv(hsv[0], saturation, value)
}
