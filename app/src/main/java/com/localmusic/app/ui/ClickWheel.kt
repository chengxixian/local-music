// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import android.os.VibrationEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmiuix.glass.liquidGlass
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.launch
import kotlin.math.atan2

/**
 * iPod 式滚轮：外圈液态玻璃环 + 中间的按键。
 *
 * 交互（按用户要求）：
 *  - **转动外圈** → `onTick(+1 / -1)`：在当前列表里上下移动选中项
 *  - **单击中间键** → `onCenterTap()`：切换页面（曲库 → 设置 → 喜欢 → …）
 *  - **双击中间键** → `onCenterDoubleTap()`：确认选择（播放选中的歌 / 执行选中的设置项）
 *
 * 实现说明：
 *  - 转动的角度在**手指位置相对圆心**上累加，所以"绕圈转"和"沿环上下拖"都能识别；
 *    手指落在中间键范围内时不动（交给中间键处理点击），改用它相对圆心的纵向位移兜底，
 *    这样直线拖动也能转（`adb input swipe` 只能走直线，验证时就靠这条）。
 *  - 玻璃环必须挂在**不含子内容**的 Box 上，按键内容是它的兄弟（库的硬性要求）。
 */
@Composable
fun ClickWheel(
    backdrop: LayerBackdrop?,
    label: String,
    caption: String?,
    onTick: (Int) -> Unit,
    onCenterTap: () -> Unit,
    onCenterDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    // 直接拿 Vibrator 自己发短震：强度可控（Compose 的 HapticFeedbackType 只有固定几档，
    // TextHandleMove 是最轻的"滴答"，用户反馈太弱）。
    val vibrator = remember { context.getSystemService(android.os.Vibrator::class.java) }
    val density = LocalDensity.current
    // 手势闭包只会在首次组合时创建一次（pointerInput(Unit) 不会重建），
    // 所以回调必须用 rememberUpdatedState 包一层，否则双击时拿到的是**第一次组合**的列表和下标
    // —— 这正是"双击播放的不是选中的音乐"的原因。
    val tick by rememberUpdatedState(onTick)
    val centerTap by rememberUpdatedState(onCenterTap)
    val centerDoubleTap by rememberUpdatedState(onCenterDoubleTap)
    val pendingSingle = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    // 一格 = 环上滚过 30dp 的弧长。之前是 52dp，转起来发钝（用户反馈"触摸不灵敏"）。
    // 每帧仍然最多响一格并清空余量，所以调小步长只会更跟手，不会回到"一划十几格"。
    val stepPx = with(density) { 30.dp.toPx() }

    Box(modifier, contentAlignment = Alignment.Center) {
        // ① 玻璃环：没有任何子内容
        Box(
            Modifier.matchParentSize().liquidGlass(
                backdrop = backdrop,
                shape = CircleShape,
                refractionHeight = 20.dp,
                refractionAmount = 32.dp,
            )
        )
        // ② 转动识别
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                // 内圈半径必须**严格等于中间键的半径**（中间键是 0.46 边长 → 半径 0.23）。
                // 之前写成 0.30，于是中间那圈 7% 的环带是死区：点上去既不转也不点。
                val inner = minOf(size.width, size.height) * 0.23f
                var lastHapticAt = 0L
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        if ((down.position - center).getDistance() < inner) continue
                        var lastY = down.position.y
                        var lastAngle = angleDeg(down.position - center)
                        var accum = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val vec = change.position - center
                            if (vec.getDistance() >= inner) {
                                val a = angleDeg(vec)
                                var d = a - lastAngle
                                if (d > 180f) d -= 360f
                                if (d < -180f) d += 360f
                                // 角度差 → 弧长：这才是"手指在环上滚了多远"
                                val dRad = d * 0.017453292f
                                accum += (dRad * vec.getDistance()) / stepPx
                                lastAngle = a
                            } else {
                                accum += (lastY - change.position.y) / stepPx
                            }
                            var fired = false
                            lastY = change.position.y
                            // 每帧最多响一格，并且不让多余的量攒着——否则手指快划一下会连跳十几格
                            // （用户反馈"太灵敏转得太快"就是这个：硬件滚轮一帧也只会过一格）。
                            if (accum >= 1f) { accum = 0f; tick(1); fired = true }
                            else if (accum <= -1f) { accum = 0f; tick(-1); fired = true }
                            if (fired) {
                                // 触感节流：每次 tick 都震会明显掉帧
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastHapticAt > TICK_THROTTLE_MS) {
                                    lastHapticAt = now
                                    val sent = runCatching {
                                        val v = vibrator
                                        if (v == null || !v.hasVibrator()) {
                                            false
                                        } else {
                                            when {
                                                // ① 最好：CLICK 原语（系统专为"咔哒"设计的极短脉冲）
                                                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                                                    v.vibrate(
                                                        VibrationEffect.startComposition()
                                                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                                                            .compose()
                                                    )
                                                // ② 本机（HyperOS）不支持原语，走系统预定义 CLICK ——
                                                //    **实测这就是最清脆的那一档**（用户确认），别再换成
                                                //    自己拼的短脉冲：22ms/240 与 8ms/255 都试过，都比它闷。
                                                else -> v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                                            }
                                            true
                                        }
                                    }.getOrDefault(false)
                                    if (!sent) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            change.consume()
                            if (!change.pressed) break
                        }
                    }
                }
            }
        )
        // ③ 中间键：单击切页 / 双击确认
        Column(
            Modifier.fillMaxSize(0.46f)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.34f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        // 单击延迟一点点再执行：给双击留出判定窗口。
                        // 不这样做的话，双击会被拆成两次单击 → 一次双击跳两页（用户反馈"页面切换太快"）。
                        onTap = {
                            pendingSingle.value?.cancel()
                            pendingSingle.value = scope.launch {
                                kotlinx.coroutines.delay(DOUBLE_TAP_WINDOW_MS)
                                centerTap()
                            }
                        },
                        onDoubleTap = {
                            pendingSingle.value?.cancel()
                            centerDoubleTap()
                        },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                label,
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (!caption.isNullOrBlank()) {
                Text(
                    caption,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** 手指位置相对圆心、以正右方为 0°、顺时针为正的角度。 */
private fun angleDeg(v: Offset): Float =
    Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()

/** 单击要等这么久再执行，给双击留判定窗口（平台的双击超时约 300ms）。 */
private const val DOUBLE_TAP_WINDOW_MS = 340L

/** 转动一格时的那一下震动节流（毫秒）：太密会糊成一片，就分不出"一格一格"了。 */
private const val TICK_THROTTLE_MS = 45L
