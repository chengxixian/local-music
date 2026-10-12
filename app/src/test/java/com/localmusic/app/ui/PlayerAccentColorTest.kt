// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页自适应取色的回归测试。
 *
 * 这里锁的是一个**只有真机才看得见**的 bug：灰/黑白封面的平均色饱和度≈0，
 * HSV 里"饱和度 0 的色相"是无意义的 0（红），一旦对灰强行提饱和，
 * 整屏就被染成砖红。
 */
class PlayerAccentColorTest {

    private fun assertNeutral(label: String, color: Color) {
        val r = color.red
        val g = color.green
        val b = color.blue
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        assertTrue(
            "$label 应该是中性的（不应带色相），实际 rgb=$r,$g,$b（极差 $spread）",
            spread < 0.02f,
        )
    }

    @Test
    fun `gray cover stays neutral`() {
        val gray = Color(0.5f, 0.5f, 0.5f, 1f)
        assertNeutral("灰色封面 vivid() 后", vivid(gray))
        assertTrue("灰色封面不该被判定为有颜色", isNeutral(gray))
    }

    @Test
    fun `white cover stays neutral on light backdrop`() {
        val white = Color(0.95f, 0.95f, 0.95f, 1f)
        val ink = readable(vivid(white), darkBackdrop = false)
        assertNeutral("近白封面在浅底上的图标色", ink)
        assertTrue("浅底图标要压暗", ink.luminance() < 0.45f)
    }

    @Test
    fun `black and white cover stays neutral on dark backdrop`() {
        val black = Color(0.05f, 0.05f, 0.05f, 1f)
        val ink = readable(vivid(black), darkBackdrop = true)
        assertNeutral("近黑封面在深底上的图标色", ink)
        assertTrue("深底图标要提亮", ink.luminance() > 0.6f)
    }

    @Test
    fun `colorful cover keeps its hue and gets more saturated`() {
        val softPink = Color(0.85f, 0.55f, 0.68f, 1f)
        val before = toHsv(softPink)
        val after = toHsv(vivid(softPink))
        assertTrue("有颜色的封面要提饱和", after[1] > before[1])
        assertTrue("色相不能变", kotlin.math.abs(after[0] - before[0]) < 3f)
        assertTrue("饱和度上限 0.62", after[1] <= 0.63f)
    }

    @Test
    fun `dark red cover becomes readable without turning into pure red noise`() {
        val darkRed = Color(0.18f, 0.02f, 0.02f, 1f)
        val ink = readable(vivid(darkRed), darkBackdrop = true)
        assertTrue("深底要提亮", ink.luminance() > 0.25f)
        assertTrue("仍然是红色系（红分量最高）", ink.red > ink.green && ink.red > ink.blue)
    }

    @Test
    fun `hsv round trip is stable`() {
        val original = Color(0.42f, 0.71f, 0.33f, 1f)
        val hsv = toHsv(original)
        val back = fromHsv(hsv[0], hsv[1], hsv[2])
        assertEquals(original.red, back.red, 0.01f)
        assertEquals(original.green, back.green, 0.01f)
        assertEquals(original.blue, back.blue, 0.01f)
    }
}
