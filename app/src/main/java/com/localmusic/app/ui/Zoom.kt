// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * ── 缩放转场（zoom / container transform）────────────────────────────────────
 *
 * 移植自 FlClash 的 prism_ui `open_container.dart`（它复刻的是 iOS 27 的 zoom 转场，
 * 参数是作者在模拟器上从 UIKit 读出来的）：
 *
 *  - 打开：临界阻尼 340ms 弹簧，起步速度 = 0.016 × 刚度；
 *    关闭：340ms、bounce 0.08 的弹簧（落回卡片时有极轻的一下回弹）。
 *  - 一个圆角矩形「容器」从卡片矩形插值到整屏，圆角从卡片圆角插值到 0；
 *  - 容器里两层：**卡片快照**（按容器宽度等比缩放）在后 65% 的进度里淡出，
 *    **目标页**（同样按宽度等比缩放）在前 65% 的进度里淡入 —— 于是全程只有一个连续的物体在变形，
 *    没有"先放大再换页"的两段感；
 *  - 容器外一层随进度加深的阴影 / 压暗（40%）。
 *  - 下拉关闭：越过 10dp 开始跟手，超过 60% 屏高后橡皮筋；每拖 60% 屏高缩小 0.4；
 *    松手时「位移 + 0.1s 速度」≥ 150dp 就关闭（400ms 临界阻尼 morph 回卡片），否则 250ms 弹回。
 *
 * 在此之上本工程加了一个**共享封面**：封面单独一层，起点跟着卡片快照走、终点跟着目标页走，
 * 两端按同一进度插值 —— 封面始终不透明、连续地从卡片封面变成播放页封面。
 *
 * 性能：所有逐帧变化的量都只在 graphicsLayer / draw 阶段读取，转场期间目标页**不重组**。
 */

/** Flutter `SpringDescription.withDurationAndBounce` → Compose spring。 */
private fun springOf(durationMs: Int, bounce: Float = 0f): SpringSpec<Float> {
    val omega = 2f * PI.toFloat() / (durationMs / 1000f)
    return spring(dampingRatio = 1f - bounce, stiffness = omega * omega)
}

private val OpenSpring = springOf(340)
private val OpenStartVelocity = 0.016f * (2f * PI.toFloat() / 0.34f).let { it * it }
private val CloseSpring = springOf(340, 0.08f)
private val MorphSpring = springOf(400)
private val ReturnSpring = springOf(250)
private const val FadeEnd = 0.65f
private const val ScrimAlpha = 0.4f

/** 转场的起点（卡片 / 迷你播放条）。所有矩形都是**窗口坐标**（boundsInRoot）。 */
class ZoomSource(
    val key: Any,
    /** 起点矩形；返回 null 表示它已经不在屏幕上了。 */
    val rect: () -> Rect?,
    val radius: Float,
    /** 起点的绘制快照（卡片自己每次绘制时录进去的那份）。 */
    val tile: () -> GraphicsLayer? = { null },
    /** 起点里的封面矩形（共享元素的起点）。 */
    val cover: () -> Rect? = { null },
    val coverRadius: Float = 0f,
)

class ZoomFrame(val rect: Rect, val radius: Float, val closed: Rect, val progress: Float)

@Stable
class ZoomState {
    val progress = Animatable(0f)

    /** 宿主是否在组合里（打开中 / 已打开 / 关闭动画中）。 */
    var active by mutableStateOf(false)
    var source by mutableStateOf<ZoomSource?>(null)

    /** 目标页静止时的矩形（宿主上报）。 */
    var open by mutableStateOf(Rect.Zero)

    // ── 下拉 / 预测性返回 ──
    var dragX by mutableFloatStateOf(0f)
    var dragY by mutableFloatStateOf(0f)
    var shrink by mutableFloatStateOf(0f)
    var pivot by mutableStateOf(Offset.Zero)
    var dragRadius by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)

    /** 松手关闭那一刻被"冻住"的页面矩形：关闭动画从这里 morph 回卡片，而不是先弹回整屏。 */
    var frozenOpen by mutableStateOf<Rect?>(null)

    /** 完全打开且静止（组合期可读：只在动画开始 / 结束时翻转，不会逐帧重组）。 */
    var restful by mutableStateOf(false)
        internal set

    /** 目标页根节点的坐标（未经过转场变换），用来把页面里的元素换算成"静止时的位置"。 */
    internal var pageCoords: LayoutCoordinates? = null

    /** 目标页里的共享元素（封面）坐标，由 [zoomShared] 上报。 */
    internal var sharedCoords: LayoutCoordinates? = null

    /** 共享元素在**静止布局**下的窗口矩形。 */
    fun sharedRect(): Rect? {
        val p = pageCoords?.takeIf { it.isAttached } ?: return null
        val c = sharedCoords?.takeIf { it.isAttached } ?: return null
        return runCatching { p.localBoundingBoxOf(c, clipBounds = false).translate(open.topLeft) }.getOrNull()
    }

    /** 宿主每次组合时写入：共享封面用哪张图。 */
    internal var sharedImage: () -> ImageBitmap? = { null }

    /** 这一帧宿主会不会画飞行中的共享封面（不会的话，页面里的封面就不该隐藏）。 */
    fun canShare(): Boolean =
        source?.cover?.invoke() != null && sharedImage() != null && sharedRect() != null

    // 当前被"借走"（隐藏）的起点。只允许同时隐藏一个，重新打开 / 换落点时先还回去。
    private var hiddenKey: Any? = null
    internal fun hide(key: Any?) {
        if (hiddenKey == key) return
        hiddenKey?.let { k -> ZoomRegistry.hidden.removeAll { it == k } }
        hiddenKey = key
        if (key != null && key !in ZoomRegistry.hidden) ZoomRegistry.hidden.add(key)
    }

    /** 不播动画直接收起（切 tab 时用：卡片已经不在屏幕上，没有地方可以"缩回去"）。 */
    suspend fun snapClosed() {
        hide(null)
        progress.snapTo(0f)
        active = false
        restful = false
        frozenOpen = null
        resetDrag()
    }

    val settled: Boolean
        get() = !progress.isRunning && progress.value >= 1f && !dragging &&
            dragX == 0f && dragY == 0f && shrink == 0f && frozenOpen == null

    val pageAlpha: Float get() = (progress.value / FadeEnd).coerceIn(0f, 1f)
    val tileAlpha: Float get() = ((1f - progress.value) / FadeEnd).coerceIn(0f, 1f)

    fun openNow(): Rect {
        frozenOpen?.let { return it }
        val o = open
        if (shrink == 0f && dragX == 0f && dragY == 0f) return o
        val s = 1f - shrink
        return Rect(
            pivot.x + (o.left - pivot.x) * s + dragX,
            pivot.y + (o.top - pivot.y) * s + dragY,
            pivot.x + (o.right - pivot.x) * s + dragX,
            pivot.y + (o.bottom - pivot.y) * s + dragY,
        )
    }

    private fun fallbackClosed(): Rect {
        val o = open
        val c = o.center
        return Rect(c.x - o.width * 0.42f, c.y - o.height * 0.42f, c.x + o.width * 0.42f, c.y + o.height * 0.42f)
    }

    fun frame(): ZoomFrame? {
        val o = openNow()
        if (o.width <= 0f) return null
        val src = source
        val closed = src?.rect?.invoke()?.takeIf { it.width > 1f } ?: fallbackClosed()
        val p = progress.value
        val rect = Rect(
            lerpF(closed.left, o.left, p), lerpF(closed.top, o.top, p),
            lerpF(closed.right, o.right, p), lerpF(closed.bottom, o.bottom, p),
        )
        val radius = lerpF(src?.radius ?: dragRadius, dragRadius, p.coerceIn(0f, 1f)).coerceAtLeast(0f)
        return ZoomFrame(rect, radius, closed, p)
    }

    internal fun resetDrag() {
        dragX = 0f; dragY = 0f; shrink = 0f; dragRadius = 0f; dragging = false
    }

    private var returnJob: Job? = null
    internal fun springBack(scope: CoroutineScope) {
        dragging = false
        val x0 = dragX; val y0 = dragY; val s0 = shrink; val r0 = dragRadius
        returnJob?.cancel()
        returnJob = scope.launch {
            val k = Animatable(1f)
            k.animateTo(0f, ReturnSpring) {
                dragX = x0 * value; dragY = y0 * value; shrink = s0 * value; dragRadius = r0 * value
            }
            resetDrag()
        }
    }

    internal fun cancelReturn() { returnJob?.cancel() }
}

internal fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(
    lerpF(a.left, b.left, t), lerpF(a.top, b.top, t), lerpF(a.right, b.right, t), lerpF(a.bottom, b.bottom, t),
)

private fun roundRectPath(rect: Rect, radius: Float) = Path().apply {
    addRoundRect(RoundRect(rect, CornerRadius(radius)))
}

/**
 * 容器**之外**的压暗（只压被盖住的那一层，容器里面不压 —— 播放页的背景在采集层里，
 * 若整屏压暗，静止时播放页会一直蒙着 40% 的黑）。
 */
@Composable
fun Modifier.zoomScrim(state: ZoomState): Modifier {
    val origin = remember { OffsetHolder() }
    return this
        .onGloballyPositioned { origin.value = it.positionInRoot() }
        .drawBehind {
            if (!state.active || state.settled) return@drawBehind
            val p = state.progress.value.coerceIn(0f, 1f)
            val a = ScrimAlpha * p * (1f - state.shrink)
            if (a <= 0.001f) return@drawBehind
            val f = state.frame() ?: return@drawBehind
            clipPath(roundRectPath(f.rect.translate(-origin.value), f.radius), androidx.compose.ui.graphics.ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = a))
            }
        }
}

/** 布局位置的可变持有者（只在绘制 / 图层阶段读，不需要是 State）。 */
class OffsetHolder { var value: Offset = Offset.Zero }

/**
 * 把一块"全屏背景"裁成容器的形状（播放页的模糊封面背景在采集层里，只能这样跟着容器走）。
 * 透明度 = 进度（FlClash：页面的底色随进度填满容器）。
 */
@Composable
fun Modifier.zoomFill(state: ZoomState): Modifier {
    val holder = remember { OffsetHolder() }
    return this
        .onGloballyPositioned { holder.value = it.positionInRoot() }
        .drawWithContent {
            if (state.settled) { drawContent(); return@drawWithContent }
            val f = state.frame() ?: return@drawWithContent
            val a = f.progress.coerceIn(0f, 1f)
            if (a <= 0.001f) return@drawWithContent
            clipPath(roundRectPath(f.rect.translate(-holder.value), f.radius)) {
                drawIntoCanvas { c ->
                    c.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = a })
                    this@drawWithContent.drawContent()
                    c.restore()
                }
            }
        }
}

/**
 * 跟着容器一起缩放 / 淡入的**附属层**（比如画在另一层里的玻璃工具栏）。
 * 只做位置与透明度，不裁剪。
 */
@Composable
fun Modifier.zoomFollow(state: ZoomState): Modifier {
    val holder = remember { OffsetHolder() }
    return this
        .onGloballyPositioned { holder.value = it.positionInRoot() }
        .graphicsLayer {
            if (!state.active || state.settled) return@graphicsLayer
            val origin = holder.value
            val f = state.frame() ?: run { alpha = 0f; return@graphicsLayer }
            val open = state.open
            if (open.width <= 0f) return@graphicsLayer
            val s = f.rect.width / open.width
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = s; scaleY = s
            translationX = f.rect.left + (origin.x - open.left) * s - origin.x
            translationY = f.rect.top + (origin.y - open.top) * s - origin.y
            alpha = state.pageAlpha
        }
}

/**
 * 缩放转场的宿主：铺满父布局，依次画 ① 卡片快照 ② 目标页 ③ 共享封面。
 *
 * @param visible 打开 / 关闭
 * @param fill 容器的底色（null = 由调用方在别处用 [zoomFill] 画，例如播放页的模糊背景）
 * @param dismissible 允许下拉关闭与预测性返回
 * @param backEnabled 系统返回由这里接（预测性返回：页面跟着返回手势缩小）
 * @param onDismiss 用户手势要求关闭（调用方把 visible 置 false）
 * @param closingSource 关闭那一刻再取一次"回到哪里"（切过歌就回到当前歌的卡片 / 迷你条）
 * @param sharedImage 共享封面的位图；sharedTarget 是目标页里封面的矩形（静止布局，窗口坐标）
 */
@Composable
fun ZoomHost(
    state: ZoomState,
    visible: Boolean,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    scrim: Boolean = false,
    dismissible: Boolean = true,
    backEnabled: Boolean = true,
    onDismiss: () -> Unit,
    closingSource: () -> ZoomSource? = { state.source },
    sharedImage: () -> ImageBitmap? = { null },
    sharedTargetRadius: Float = 0f,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismissNow by rememberUpdatedState(onDismiss)
    val closing by rememberUpdatedState(closingSource)
    val density = LocalDensity.current
    val dismissDistance = with(density) { 150.dp.toPx() }
    val maxRadius = with(density) { 34.dp.toPx() }

    LaunchedEffect(visible) {
        state.restful = false
        if (visible) {
            state.hide(state.source?.key)
            state.active = true
            state.frozenOpen = null
            state.cancelReturn()
            state.resetDrag()
            if (state.progress.value <= 0f) {
                state.progress.animateTo(1f, OpenSpring, initialVelocity = OpenStartVelocity)
            } else {
                state.progress.animateTo(1f, OpenSpring)
            }
            state.restful = true
        } else if (state.active) {
            // 关闭时重新决定落点（切过歌就回到当前这首；都不在屏幕上就原地缩小淡出）
            val next = closing()
            state.source = next
            state.hide(next?.key)
            val spec = if (state.frozenOpen != null) MorphSpring else CloseSpring
            state.progress.animateTo(0f, spec)
            // 只在正常播完时归还；被"重新打开"打断时由新的一轮接手，避免把新起点误显示出来
            state.hide(null)
            state.active = false
            state.frozenOpen = null
            state.resetDrag()
        }
    }
    // 宿主离开组合（Activity 重建、切走）时把借走的起点还回去，否则那张卡片会一直透明
    androidx.compose.runtime.DisposableEffect(state) { onDispose { state.hide(null) } }
    if (!state.active && !visible) return

    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    state.sharedImage = sharedImage

    // ── 下拉关闭 ──
    var raw by remember { mutableFloatStateOf(0f) }
    fun applyRaw(screenH: Float) {
        val reach = screenH * 0.6f
        val y = if (raw <= reach) raw.coerceAtLeast(0f) else reach + (raw - reach) * 0.35f
        state.dragY = y
        state.shrink = (0.4f * y / reach).coerceIn(0f, 0.4f)
        state.dragRadius = (maxRadius * y / (screenH * 0.12f)).coerceIn(0f, maxRadius)
    }
    fun release(velocity: Float) {
        if (!state.dragging) return
        if (raw + 0.1f * velocity >= dismissDistance) {
            state.frozenOpen = state.openNow()
            state.dragging = false
            state.dragX = 0f; state.dragY = 0f; state.shrink = 0f
            raw = 0f
            dismissNow()
        } else {
            raw = 0f
            state.springBack(scope)
        }
    }
    val dragState = rememberDraggableState { delta ->
        raw += delta
        applyRaw(state.open.height)
    }
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 已经在下拉关闭中：往回推先把下拉量吃掉
                if (state.dragging && available.y < 0f) {
                    val take = max(available.y, -raw)
                    raw += take; applyRaw(state.open.height)
                    return Offset(0f, take)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 子列表已经滚到顶还在往下拉：转成下拉关闭（只在页面完全打开时接管）
                if (!dismissible || (!state.dragging && !state.settled)) return Offset.Zero
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    if (!state.dragging) { state.dragging = true; state.pivot = state.open.center }
                    raw += available.y; applyRaw(state.open.height)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.dragging) { release(available.y); return available }
                return Velocity.Zero
            }
        }
    }

    // ── 预测性返回：页面跟着返回手势缩小、往手势方向挪一点，松手即关闭（取消则弹回）──
    PredictiveBackHandler(enabled = visible && backEnabled && dismissible) { events ->
        try {
            var started = false
            events.collect { e ->
                val t = e.progress.coerceIn(0f, 1f)
                val eased = 1f - (1f - t) * (1f - t)
                if (!started) {
                    started = true
                    state.cancelReturn()
                    state.dragging = true
                    val o = state.open
                    state.pivot = Offset(if (e.swipeEdge == BackEventCompat.EDGE_LEFT) o.right else o.left, o.center.y)
                }
                state.shrink = 0.14f * eased
                state.dragX = (if (e.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f) * with(density) { 10.dp.toPx() } * eased
                state.dragRadius = maxRadius * eased
            }
            // 三键导航没有手势进度：照常走关闭弹簧，不冻结
            if (started) state.frozenOpen = state.openNow()
            state.dragging = false
            state.dragX = 0f; state.dragY = 0f; state.shrink = 0f; if (!started) state.dragRadius = 0f
            dismissNow()
        } catch (e: CancellationException) {
            state.springBack(scope)
            throw e
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                hostOrigin = it.positionInRoot()
                state.open = it.boundsInRoot()
            }
            // 宿主吃掉落在空白处的触摸：页面在动画 / 打开期间，下面那层不该被点到
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .then(if (scrim) Modifier.zoomScrim(state) else Modifier),
    ) {
        // ① 底色 + 卡片快照
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                if (state.settled) {
                    fill?.let { drawRect(it) }
                    return@drawBehind
                }
                val f = state.frame() ?: return@drawBehind
                val rect = f.rect.translate(-hostOrigin)
                clipPath(roundRectPath(rect, f.radius)) {
                    fill?.let { drawRect(it.copy(alpha = it.alpha * f.progress.coerceIn(0f, 1f)), rect.topLeft, rect.size) }
                    val layer = state.source?.tile?.invoke()
                    val ta = state.tileAlpha
                    if (layer != null && !layer.isReleased && ta > 0.001f && f.closed.width > 0f) {
                        val sc = f.rect.width / f.closed.width
                        withTransform({
                            translate(rect.left, rect.top)
                            scale(sc, sc, Offset.Zero)
                        }) {
                            drawIntoCanvas { c ->
                                c.saveLayer(Rect(Offset.Zero, Size(f.closed.width, f.closed.height)), Paint().apply { alpha = ta })
                                drawLayer(layer)
                                c.restore()
                            }
                        }
                    }
                }
            },
        )
        // ② 目标页：静止布局铺满，按容器宽度等比缩放 + 裁成容器形状
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (state.settled) return@graphicsLayer
                    // 还没量到宿主尺寸的那一帧：先藏起来，不要整页闪一下
                    val f = state.frame() ?: run { alpha = 0f; return@graphicsLayer }
                    val open = state.open
                    if (open.width <= 0f) { alpha = 0f; return@graphicsLayer }
                    val s = f.rect.width / open.width
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = s; scaleY = s
                    translationX = f.rect.left - open.left
                    translationY = f.rect.top - open.top
                    alpha = state.pageAlpha
                    val h = f.rect.height / s
                    val r = f.radius / s
                    shape = GenericShape { sz, _ -> addRoundRect(RoundRect(0f, 0f, sz.width, h, CornerRadius(r))) }
                    clip = true
                }
                .then(
                    if (dismissible) Modifier
                        .nestedScroll(nested)
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            enabled = visible,
                            onDragStarted = { pos ->
                                state.cancelReturn()
                                state.dragging = true
                                // 接住还没弹回完的下拉量，避免从 0 重新起算导致页面一跳
                                raw = rawFromDrag(state.dragY, state.open.height)
                                state.pivot = Offset(state.open.left + pos.x, state.open.top + pos.y)
                            },
                            onDragStopped = { v -> release(v) },
                        )
                    else Modifier,
                ),
        ) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { state.pageCoords = it }) { content() }
        }
        // ③ 共享封面
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                if (state.settled) return@drawBehind
                val img = sharedImage() ?: return@drawBehind
                val f = state.frame() ?: return@drawBehind
                val src = state.source ?: return@drawBehind
                val from = src.cover() ?: return@drawBehind
                val to = state.sharedRect() ?: return@drawBehind
                val open = state.open
                if (open.width <= 0f || f.closed.width <= 0f) return@drawBehind
                val sc = f.rect.width / f.closed.width
                val so = f.rect.width / open.width
                val a = Rect(
                    f.rect.left + (from.left - f.closed.left) * sc,
                    f.rect.top + (from.top - f.closed.top) * sc,
                    f.rect.left + (from.right - f.closed.left) * sc,
                    f.rect.top + (from.bottom - f.closed.top) * sc,
                )
                val b = Rect(
                    f.rect.left + (to.left - open.left) * so,
                    f.rect.top + (to.top - open.top) * so,
                    f.rect.left + (to.right - open.left) * so,
                    f.rect.top + (to.bottom - open.top) * so,
                )
                val q = f.progress
                val r = lerpRect(a, b, q).translate(-hostOrigin)
                val radius = lerpF(src.coverRadius * sc, sharedTargetRadius * so, q.coerceIn(0f, 1f))
                drawCoverCropped(img, r, radius)
            },
        )
    }
}

/**
 * 标记目标页里的共享元素（封面）。转场进行中它自己隐藏，由宿主画飞行中的那一张；
 * 静止后再显示 —— 两者在终点完全重合，交接不跳。
 */
fun Modifier.zoomShared(state: ZoomState?): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned { state.sharedCoords = it }
        .graphicsLayer { alpha = if (!state.active || state.settled || !state.canShare()) 1f else 0f }
}

/** 以 center-crop 方式把位图画进圆角矩形。 */
fun DrawScope.drawCoverCropped(img: ImageBitmap, rect: Rect, radius: Float) {
    if (rect.width <= 0f || rect.height <= 0f) return
    val iw = img.width.toFloat(); val ih = img.height.toFloat()
    val target = rect.width / rect.height
    var sw = iw; var sh = ih
    if (iw / ih > target) sw = ih * target else sh = iw / target
    val sx = ((iw - sw) / 2f).roundToInt(); val sy = ((ih - sh) / 2f).roundToInt()
    clipPath(roundRectPath(rect, radius)) {
        drawImage(
            img,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1)),
            dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
            dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
        )
    }
}

/** 窗口坐标下的矩形是否还在屏幕可见范围内（用来判断"能不能回到这张卡片"）。 */
fun Rect.visibleIn(screen: Rect, minVisible: Float = 0.35f): Boolean {
    if (width <= 0f || height <= 0f) return false
    val i = intersect(screen)
    if (i.width <= 0f || i.height <= 0f) return false
    return (i.width * i.height) / (width * height) >= minVisible
}

@Suppress("unused")
private fun keepImports(x: Float) = min(x, 0f)

/*
 * ── 起点登记 ──────────────────────────────────────────────────────────────────
 * 卡片 / 迷你播放条把自己的位置、封面位置、绘制快照登记到这里；转场开始时按 key 取。
 * 快照是卡片**自己每次绘制时顺手录下来的**（GraphicsLayer 是显示列表，不是位图，几乎没有开销），
 * 所以取到的永远是卡片当前的样子（喜欢的红心、正在播放的红字都在）。
 */
object ZoomRegistry {
    class Entry {
        var coords: LayoutCoordinates? = null
        var cover: LayoutCoordinates? = null
        var layer: GraphicsLayer? = null
        var image: ImageBitmap? = null
    }

    private val entries = HashMap<Any, Entry>()

    /** 转场期间需要隐藏的起点（它"变成"了正在飞的容器）。 */
    val hidden = androidx.compose.runtime.mutableStateListOf<Any>()

    fun entry(key: Any): Entry = entries.getOrPut(key) { Entry() }

    fun rectOf(key: Any): Rect? =
        entries[key]?.coords?.takeIf { it.isAttached }?.let { runCatching { it.boundsInRoot() }.getOrNull() }

    fun source(key: Any, radius: Float, coverRadius: Float = 0f): ZoomSource = ZoomSource(
        key = key,
        rect = { rectOf(key) },
        radius = radius,
        tile = { entries[key]?.layer?.takeIf { !it.isReleased } },
        cover = { entries[key]?.cover?.takeIf { it.isAttached }?.let { runCatching { it.boundsInRoot() }.getOrNull() } },
        coverRadius = coverRadius,
    )

    fun image(key: Any): ImageBitmap? = entries[key]?.image
}

/** 起点：登记位置 + 录制快照 + 转场期间隐藏自己。 */
@Composable
fun Modifier.zoomSource(key: Any): Modifier {
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val entry = ZoomRegistry.entry(key)
    val mine = remember { arrayOfNulls<LayoutCoordinates>(1) }
    androidx.compose.runtime.DisposableEffect(key, layer) {
        entry.layer = layer
        onDispose {
            if (entry.layer === layer) entry.layer = null
            // 同一个 key 可能同时有两张卡（切页动画里曲库 / 喜欢各一张）：只清自己登记的那份
            if (entry.coords === mine[0]) entry.coords = null
        }
    }
    return this
        .onGloballyPositioned { entry.coords = it; mine[0] = it }
        .graphicsLayer { alpha = if (key in ZoomRegistry.hidden) 0f else 1f }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

/** 起点里的封面（共享元素的起点）。 */
fun Modifier.zoomSourceCover(key: Any): Modifier =
    onGloballyPositioned { ZoomRegistry.entry(key).cover = it }

fun cardZoomKey(uri: String): String = "card:$uri"
fun playlistZoomKey(id: Long): String = "pl:$id"
const val MiniZoomKey = "mini"

/** 播放页的转场状态（播放页里的共享封面用它隐藏 / 显示自己）。 */
val LocalPlayerZoom = androidx.compose.runtime.staticCompositionLocalOf<ZoomState?> { null }

/** ZoomHost 里 applyRaw 的反函数（线性段 + 橡皮筋段）。 */
internal fun rawFromDrag(y: Float, screenH: Float): Float {
    val reach = screenH * 0.6f
    return if (y <= reach) y else reach + (y - reach) / 0.35f
}
