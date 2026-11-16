// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * 全局动效参数（0.9.1 起统一用这一份）。
 *
 * 数值取自 FlClash 的 UI 库 prism_ui（`motion.dart` / `navigation_dock.dart` / `fade_box.dart`），
 * 换算方法：Flutter 的 `SpringDescription.withDurationAndBounce(d, b)` 对应
 *   stiffness = (2π / d)²，dampingRatio = 1 − b（mass = 1）。
 *
 * 用法上的三条约定：
 *  1. **点按**：一律 [elasticPress] —— 按下"鼓起"，拖动时被手指"拉"一点，松手回弹。
 *     快速点按时也会先鼓到满再落回（FlClash 的 liftSnap），所以轻点同样看得见反馈。
 *  2. **图标切换**（播放⇄暂停、♡⇄♥、循环⇄单曲）：一律 [MorphIcon] —— 旧图标缩小、模糊、淡出，
 *     新图标从 0.2 倍弹出，不再"啪"地换图。
 *  3. **内容切换**（切页、封面⇄歌词⇄队列）：一律 fade-through（[fadeThrough]），
 *     旧内容先在 90ms 内淡出，新内容再淡入 + 轻微放大，两层不会同时半透明地叠在一起。
 */
object Motion {
    /** 按下鼓起 / 松手回落。prism `_pressSpring`：k = 311，ζ = 0.593（轻微过冲一次）。 */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.593f, stiffness = 311f)

    /** 指示器 / 滑块位移。prism `_travelSpring`：400ms，bounce 0.15。 */
    fun <T> travel(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = 246.7f, visibilityThreshold = visibilityThreshold)

    /** 落位（选中图标放大、弹窗入场）。prism `_settleSpring`：500ms，bounce 0.32。 */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.68f, stiffness = 157.9f)

    /** 图标换形的缩放。prism `_GlyphSwap` 一档（≈292ms），带一点 easeOutBack 式的回弹。 */
    fun <T> glyph(): SpringSpec<T> = spring(dampingRatio = 0.62f, stiffness = 463f)

    /** 手指拖拽跟随。prism `_trackSpring`：120ms，临界阻尼。 */
    fun <T> track(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 2741f)

    /** 颜色 / 透明度过渡（PressSurface 的 `_lookDuration`）。 */
    fun <T> look(): FiniteAnimationSpec<T> = tween(MidMillis, easing = Standard)

    const val CommonMillis = 300
    const val MidMillis = 200

    /** 快速点按时：鼓起进度到这里才允许回落（prism `_liftSnap`）。 */
    const val LiftSnap = 0.975f

    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val FadeThroughIn = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val FadeThroughOut = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

/**
 * Material / FlClash 的 fade-through：总长 300ms，旧内容在前 30%（90ms）加速淡出，
 * 新内容在后 70% 减速淡入并从 [initialScale] 放大到 1。
 *
 * [slidePx] ≠ 0 时再叠一段同向的小位移（FlClash `FadeSlideEnterTransition` 的 24dp），保留切页的方向感。
 */
fun <S> AnimatedContentTransitionScope<S>.fadeThrough(
    slidePx: Int = 0,
    initialScale: Float = 0.92f,
): ContentTransform {
    val hold = (Motion.CommonMillis * 0.3f).toInt()
    val rest = Motion.CommonMillis - hold
    var enter = fadeIn(tween(rest, delayMillis = hold, easing = Motion.FadeThroughIn))
    if (initialScale < 1f) {
        enter += scaleIn(tween(rest, delayMillis = hold, easing = Motion.FadeThroughIn), initialScale = initialScale)
    }
    var exit = fadeOut(tween(hold, easing = Motion.FadeThroughOut))
    if (slidePx != 0) {
        enter += slideInHorizontally(tween(Motion.CommonMillis, easing = Motion.Standard)) { slidePx }
        exit += slideOutHorizontally(tween(Motion.CommonMillis, easing = Motion.Standard)) { -slidePx / 2 }
    }
    return (enter togetherWith exit).using(SizeTransform(clip = false))
}

private fun rubberBand(overshoot: Float, limit: Float): Float {
    if (limit <= 0f) return 0f
    val pull = 1f - 1f / (abs(overshoot) * 0.55f / limit + 1f)
    return limit * pull * sign(overshoot)
}

/**
 * FlClash `ElasticPress` 的 Compose 版：按下时**鼓起**（或卡片用的轻微**下沉**），
 * 拖动时被手指橡皮筋式地拉开一点，松手弹回。
 *
 * - 只改绘制（graphicsLayer），布局与命中区始终是静止时的大小，不会挤动邻居。
 * - 只**观察**指针（Initial 阶段、从不 consume），不影响 clickable / 滚动 / 拖动条。
 * - 轻点：按下与抬起常在同一两帧内完成，旧写法 `animateFloatAsState(pressed)` 几乎看不出动；
 *   这里会让鼓起先走到 [Motion.LiftSnap] 再回落，所以每次点按都有完整的一下。
 *
 * @param growth 鼓起量上限（总增长，等价于每边 growth/2）；按控件最长边折算成比例，再受 [maxScale] 封顶。
 * @param sink 为 true 时改为按下缩小到 1 − [sinkAmount]（卡片、整块面板这类大面积元素）。
 */
@Composable
fun Modifier.elasticPress(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    growth: Dp = 12.dp,
    maxScale: Float = 0.2f,
    sink: Boolean = false,
    sinkAmount: Float = 0.045f,
    pull: Boolean = true,
): Modifier {
    val lift = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    var pullX by remember { mutableFloatStateOf(0f) }
    var pullY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(interactionSource, enabled) {
        if (!enabled) {
            pressed = false
            lift.snapTo(0f)
            return@LaunchedEffect
        }
        var release: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (enabled) {
                    release?.cancel()
                    pressed = true
                    scope.launch { lift.animateTo(1f, Motion.press()) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    pressed = false
                    release?.cancel()
                    release = scope.launch {
                        // 先鼓到满再落：轻点也有完整反馈
                        if (lift.targetValue == 1f && lift.value < Motion.LiftSnap) {
                            snapshotFlow { lift.value }.first { it >= Motion.LiftSnap }
                        }
                        lift.animateTo(0f, Motion.press())
                    }
                }
            }
        }
    }

    val px by animateFloatAsState(if (pressed && pull) pullX else 0f, if (pressed) Motion.track() else Motion.press(), label = "pullX")
    val py by animateFloatAsState(if (pressed && pull) pullY else 0f, if (pressed) Motion.track() else Motion.press(), label = "pullY")

    val tracking = if (pull && enabled) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val origin = down.position
                val limit = min(size.width, size.height) * (7f / 32f)
                pullX = 0f; pullY = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val d = change.position - origin
                    pullX = rubberBand(d.x, limit)
                    pullY = rubberBand(d.y, limit)
                }
                pullX = 0f; pullY = 0f
            }
        }
    } else Modifier

    return this
        .graphicsLayer {
            val l = lift.value
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@graphicsLayer
            val swell = if (sink) {
                1f - sinkAmount * l
            } else {
                1f + l * min(maxScale, growth.toPx() / max(w, h))
            }
            scaleX = swell * (1f + abs(px) / w * 0.5f)
            scaleY = swell * (1f + abs(py) / h * 0.5f)
            translationX = px
            translationY = py
        }
        .then(tracking)
}

/**
 * 图标换形：旧图标缩到 0.2 倍 + 模糊 + 淡出，新图标从 0.2 倍弹出（FlClash `_GlyphSwap`）。
 * 颜色变化同时做 200ms 过渡，不会和换形打架。
 */
@Composable
fun MorphIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val animatedTint by animateColorAsState(tint, Motion.look(), label = "morphTint")
    AnimatedContent(
        targetState = icon,
        transitionSpec = {
            (fadeIn(tween(160, delayMillis = 40)) + scaleIn(Motion.glyph(), initialScale = 0.2f))
                .togetherWith(fadeOut(tween(120)) + scaleOut(tween(160, easing = Motion.FadeThroughOut), targetScale = 0.2f))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        label = "morphIcon",
    ) { target ->
        val blur by transition.animateDp(label = "morphBlur") { state ->
            if (state == EnterExitState.Visible) 0.dp else 3.dp
        }
        Icon(
            target,
            contentDescription,
            modifier.then(if (blur > 0.05.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier),
            tint = animatedTint,
        )
    }
}

/**
 * 统一的图标按钮：替代 M3 `IconButton`（它只有涟漪，没有形变）。
 *
 * 反馈 = [elasticPress] 鼓起 + 一层圆形按压高亮（按下立即出现，松手 250ms 渐隐 —— FlClash PressSurface 的 tint 手感）。
 * 尺寸、无障碍角色、禁用态透明度（0.38）与 M3 一致，直接替换不改布局。
 */
@Composable
fun MotionIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    shape: Shape = CircleShape,
    highlight: Color = Color.Unspecified,
    /** 实心底色（画在鼓起层之内，跟着一起放大）。 */
    containerColor: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val baseColor = LocalContentColor.current
    val hl = if (highlight == Color.Unspecified) baseColor.copy(alpha = 0.12f) else highlight
    val glow = remember { Animatable(0f) }
    LaunchedEffect(source) {
        source.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> glow.snapTo(1f)
                is PressInteraction.Release, is PressInteraction.Cancel ->
                    launch { glow.animateTo(0f, tween(250, easing = Motion.Standard)) }
            }
        }
    }
    val contentColor by animateColorAsState(
        if (enabled) baseColor else baseColor.copy(alpha = 0.38f), Motion.look(), label = "iconBtnColor",
    )
    Box(
        modifier
            .size(size)
            .elasticPress(source, enabled)
            .drawBehind {
                val outline = shape.createOutline(this.size, layoutDirection, this)
                if (containerColor.alpha > 0f) drawOutline(outline, containerColor)
                val a = glow.value
                if (a > 0f) drawOutline(outline, hl.copy(alpha = hl.alpha * a))
            }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * `clickable` + [elasticPress] 的组合，去掉涟漪：点哪儿哪儿"鼓"一下。
 * 用于本来就是 `Modifier.clickable { }` 的小控件（色点、胶囊、卡片角标）。
 */
@Composable
fun Modifier.springClickable(
    enabled: Boolean = true,
    sink: Boolean = false,
    growth: Dp = 12.dp,
    maxScale: Float = 0.2f,
    pull: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .elasticPress(source, enabled = enabled, growth = growth, maxScale = maxScale, sink = sink, pull = pull)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** 记住"上一次的曲目下标"，返回这次切歌的方向（+1 下一首 / −1 上一首）。 */
@Composable
fun rememberTrackDirection(index: Int): Int {
    val holder = remember { IntArray(2).also { it[0] = Int.MIN_VALUE; it[1] = 1 } }
    return remember(index) {
        if (holder[0] != Int.MIN_VALUE && index != holder[0] && index >= 0) {
            holder[1] = if (index > holder[0]) 1 else -1
        }
        if (index >= 0) holder[0] = index
        holder[1]
    }
}

/** 切歌时封面的换场：按方向横移 35% + 缩放 0.88 + 淡入淡出，位移用 travel 弹簧（有一点惯性）。 */
fun <S> AnimatedContentTransitionScope<S>.coverSwap(direction: Int): ContentTransform {
    val d = if (direction >= 0) 1 else -1
    val enter = slideInHorizontally(Motion.travel(androidx.compose.ui.unit.IntOffset.VisibilityThreshold)) { (it * 0.35f * d).toInt() } +
        fadeIn(tween(220, delayMillis = 40)) +
        scaleIn(Motion.travel(), initialScale = 0.88f)
    val exit = slideOutHorizontally(tween(260, easing = Motion.Standard)) { (-it * 0.35f * d).toInt() } +
        fadeOut(tween(160)) +
        scaleOut(tween(260, easing = Motion.Standard), targetScale = 0.88f)
    return (enter togetherWith exit).using(SizeTransform(clip = false))
}
