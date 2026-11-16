// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.CoroutineScope
import androidx.compose.animation.core.VisibilityThreshold
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * LazyColumn 里的**拖动排序**。
 *
 * 显示位移 = 起手时的位置 + 手指累计位移 − 当前布局位置。
 * 这样无论列表在拖动中被交换了几次、被自动滚动了多少，被拖的那一行都**钉在手指下面**；
 * 其余行的让位由 `Modifier.animateItem()` 负责（弹簧）。
 *
 * 数据只在**本地列表**里实时交换（[onMove]），松手时才把"从第几个挪到第几个"提交一次（[onDrop]），
 * 播放器 / 数据库不会在拖动过程中被连续写几十次。
 */
@Stable
class ReorderState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    /** key 是否可以参与交换（表头、正在播放的那首等可以排除）。 */
    private val movable: (Any) -> Boolean,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
    private val onDrop: (key: Any) -> Unit,
    private val haptic: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    private var initialOffset = 0
    private var delta by mutableFloatStateOf(0f)
    private var autoScroll: Job? = null
    private var scrollSpeed = 0f
    // 交换后布局信息要等下一次 measure 才更新；期间不允许再交换，否则会来回抖（双重交换）
    private var pendingSwap = false
    private var swappedAt: Any? = null
    private var layoutWatcher: Job? = null
    private var settleJob: Job? = null

    private fun info(key: Any?): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** 被拖那一行此刻应当的位移（只在绘制层读取）。 */
    fun translationOf(key: Any): Float {
        if (key != draggingKey) return 0f
        val i = info(key) ?: return delta
        return initialOffset + delta - i.offset
    }

    fun start(key: Any) {
        val i = info(key) ?: return
        // 上一次松手的落位动画还没播完：直接结束，新的拖动接管
        settleJob?.cancel(); settleJob = null
        dropping = null; dropOffset = 0f
        draggingKey = key
        initialOffset = i.offset
        delta = 0f
        pendingSwap = false
        haptic()
        // 布局信息一变（measure 完成）就解除"刚交换过"的锁
        layoutWatcher?.cancel()
        layoutWatcher = scope.launch {
            snapshotFlow { listState.layoutInfo }.collect { li ->
                if (pendingSwap && li !== swappedAt) pendingSwap = false
            }
        }
        autoScroll?.cancel()
        autoScroll = scope.launch {
            while (isActive) {
                // 与帧同步，而不是固定 delay(16)，避免和 measure 错拍
                withFrameNanos { }
                if (scrollSpeed != 0f && draggingKey != null) {
                    listState.scrollBy(scrollSpeed)
                    swapIfNeeded()
                }
            }
        }
    }

    fun drag(dy: Float) {
        if (draggingKey == null) return
        delta += dy
        swapIfNeeded()
        // 拖到上 / 下边缘：自动滚动，越靠边越快
        val dragged = info(draggingKey) ?: return
        val top = initialOffset + delta
        val bottom = top + dragged.size
        val start = listState.layoutInfo.viewportStartOffset + listState.layoutInfo.beforeContentPadding
        val end = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.afterContentPadding
        val edge = dragged.size * 1.2f
        scrollSpeed = when {
            top < start + edge -> -((start + edge - top) / edge).coerceIn(0f, 1f) * 28f
            bottom > end - edge -> ((bottom - (end - edge)) / edge).coerceIn(0f, 1f) * 28f
            else -> 0f
        }
    }

    private fun swapIfNeeded() {
        val key = draggingKey ?: return
        if (pendingSwap) {
            // 兜底：layoutInfo 已经换了新对象就直接解锁（flow 可能还没来得及收到）
            if (listState.layoutInfo !== swappedAt) pendingSwap = false else return
        }
        val dragged = info(key) ?: return
        val top = initialOffset + delta
        val center = top + dragged.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != key && movable(it.key) && center.toInt() in it.offset..(it.offset + it.size)
        } ?: return
        // 第一项被换走时，LazyColumn 会把滚动锚定到新的第一项，看起来像整列跳了一下 —— 先把位置钉住
        val first = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        swappedAt = listState.layoutInfo
        pendingSwap = true
        onMove(key, target.key)
        haptic()
        if (dragged.index == first || target.index == first) {
            scope.launch { listState.scrollToItem(first, firstOffset) }
        }
    }

    fun end() {
        // 可重入：已经结束就什么都不做
        val key = draggingKey ?: return
        autoScroll?.cancel(); autoScroll = null
        layoutWatcher?.cancel(); layoutWatcher = null
        pendingSwap = false
        scrollSpeed = 0f
        val i = info(key)
        val settleFrom = if (i != null) initialOffset + delta - i.offset else 0f
        // 先同步写好 dropping / dropOffset，再清 draggingKey：同一帧里这一行不会闪回原位
        dropping = key
        dropOffset = settleFrom
        draggingKey = null
        delta = 0f
        onDrop(key)
        // 松手：从当前位移弹簧落位，而不是瞬间归零；新的松手会取消上一个落位
        settleJob?.cancel()
        settleJob = scope.launch {
            val a = Animatable(settleFrom)
            a.animateTo(0f, Motion.settle()) { dropOffset = value }
            if (dropping == key) { dropping = null; dropOffset = 0f }
        }
    }

    var dropping by mutableStateOf<Any?>(null)
        private set
    var dropOffset by mutableFloatStateOf(0f)
        private set

    fun isLifted(key: Any) = key == draggingKey || key == dropping
    fun offsetOf(key: Any): Float = when (key) {
        draggingKey -> translationOf(key)
        dropping -> dropOffset
        else -> 0f
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    movable: (Any) -> Boolean = { true },
    onMove: (fromKey: Any, toKey: Any) -> Unit,
    onDrop: (key: Any) -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val move = androidx.compose.runtime.rememberUpdatedState(onMove)
    val drop = androidx.compose.runtime.rememberUpdatedState(onDrop)
    val canMove = androidx.compose.runtime.rememberUpdatedState(movable)
    return remember(listState) {
        ReorderState(
            listState, scope,
            movable = { canMove.value(it) },
            onMove = { a, b -> move.value(a, b) },
            onDrop = { k -> drop.value(k) },
            haptic = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
        )
    }
}

/** 整行长按拖动。 */
fun Modifier.reorderLongPress(state: ReorderState, key: Any, enabled: Boolean = true): Modifier =
    if (!enabled) this else pointerInput(key) {
        // detectDrag* 在协程被取消（行离开组合）时不会回调 onDragCancel，用 finally 兜底，避免卡在拖动态
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.start(key) },
                onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
                onDragEnd = { state.end() },
                onDragCancel = { state.end() },
            )
        } finally {
            if (state.draggingKey == key) state.end()
        }
    }

/** 拖动把手：按住立即拖（不用等长按）。 */
fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier = pointerInput(key) {
    try {
        detectDragGestures(
            onDragStart = { state.start(key) },
            onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
        )
    } finally {
        if (state.draggingKey == key) state.end()
    }
}

/** 被拖起的那一行：跟手位移 + 浮起（放大、投影）。 */
fun Modifier.reorderLift(state: ReorderState, key: Any): Modifier =
    zIndex(if (state.isLifted(key)) 1f else 0f).graphicsLayer {
        val lifted = key == state.draggingKey
        translationY = state.offsetOf(key)
        val s = if (lifted) 1.03f else 1f
        scaleX = s; scaleY = s
        shadowElevation = if (lifted) 16.dp.toPx() else 0f
        shape = RoundedCornerShape(16.dp)
        clip = lifted
    }

/**
 * 左滑删除：前景跟手左移，背后露出红色"删除"底；超过 40% 宽度或快速一甩即删除
 * （前景继续滑出屏幕后才回调，行高的收起交给 animateItem）。右滑带阻尼、松手回弹。
 */
@Composable
fun SwipeToRemove(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onRemove: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val removeNow by rememberUpdatedState(onRemove)
    // 手指位移直接写进 float 状态（同步），不再经由 Animatable.snapTo 排队 —— 否则会盖掉退场动画
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var removing by remember { mutableStateOf(false) }
    var anim by remember { mutableStateOf<Job?>(null) }
    val armed by remember { derivedStateOf { -offsetPx > width * 0.4f } }
    val drag = rememberDraggableState { d ->
        val was = -offsetPx > width * 0.4f
        val next = offsetPx + if (offsetPx + d > 0f) d * 0.25f else d
        offsetPx = next.coerceIn(-width, width * 0.2f)
        val now = -offsetPx > width * 0.4f
        if (now != was) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val danger = MiuixTheme.colorScheme.error
    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .draggable(
                drag, Orientation.Horizontal, enabled = enabled && !removing,
                onDragStarted = { anim?.cancel(); anim = null },
                onDragStopped = { v ->
                    val remove = -offsetPx > width * 0.4f || v < -2200f
                    anim?.cancel()
                    // 动画放在组合级 scope 里，手势重启 / enabled 变化不会把它取消
                    anim = scope.launch {
                        if (remove) {
                            removing = true
                            try {
                                Animatable(offsetPx).animateTo(-width, tween(180, easing = Motion.FadeThroughOut)) { offsetPx = value }
                            } finally {
                                // 已决定删除：即使动画被取消也必须回调
                                removeNow()
                            }
                            // 若行还在（删除被拒绝等），600ms 后弹回原位
                            delay(600)
                            Animatable(offsetPx).animateTo(0f, Motion.press()) { offsetPx = value }
                            removing = false
                        } else {
                            Animatable(offsetPx).animateTo(0f, Motion.press()) { offsetPx = value }
                        }
                    }
                },
            ),
    ) {
        // 背后的删除底：随滑出距离显形，越过阈值时图标"跳"一下
        Box(
            Modifier.matchParentSize().graphicsLayer {
                alpha = (-offsetPx / (width * 0.25f)).coerceIn(0f, 1f)
            }.clip(RoundedCornerShape(16.dp)).background(danger.copy(alpha = 0.16f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val iconScale by androidx.compose.animation.core.animateFloatAsState(if (armed) 1.25f else 1f, Motion.press(), label = "trash")
            Icon(
                Icons.Rounded.DeleteOutline, null,
                tint = danger,
                modifier = Modifier.padding(end = 22.dp).size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale },
            )
        }
        Box(Modifier.offset { IntOffset(offsetPx.roundToInt(), 0) }) { content() }
    }
}

/** 底部"已移除 · 撤销"条。4 秒后自动消失。 */
@Composable
fun UndoBar(
    message: String?,
    actionLabel: String,
    onAction: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
    /** 连续两次删除文案可能相同；传入一个每次都不同的对象才能重新计时。 */
    token: Any? = message,
) {
    var shown by remember { mutableStateOf(message) }
    if (message != null) shown = message
    LaunchedEffect(message, token) {
        if (message != null) { delay(4000); onTimeout() }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(Motion.travel(androidx.compose.ui.unit.IntOffset.VisibilityThreshold)) { it } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(180, easing = Motion.FadeThroughOut)) { it } + fadeOut(tween(140)),
        modifier = modifier,
    ) {
        val scheme = MiuixTheme.colorScheme
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.onSurface)
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shown.orEmpty(), color = scheme.surface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(actionLabel, color = scheme.primary.let { if (it.luminanceSafe() < 0.4f) Color(0xFF8AB4F8) else it }, style = MiuixTheme.textStyles.body2)
            }
        }
    }
}

private fun Color.luminanceSafe(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Suppress("unused") private fun unused(a: Float) = abs(a)
