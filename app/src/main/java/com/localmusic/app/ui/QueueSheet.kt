// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.localmusic.app.QueueItem
import com.localmusic.app.R
import com.localmusic.app.data.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max

/** 队列里的一行在界面上的身份：key 在拖动 / 删除 / 外部刷新之间保持不变，动画才不会乱跳。 */
private data class QRow(val key: Long, val id: String, val title: String, val artist: String)

/**
 * **播放队列编辑页**（底部弹出、占满 92% 屏高）。
 *
 * - 右侧把手按住即拖：被拖的那行浮起、钉在手指下，其余行弹簧让位；拖到边缘自动滚动；
 *   松手才把"从 i 挪到 j"提交给播放器一次（不会拖一路写几十次）。
 * - 左滑删除：越过 40% 宽度红底里的垃圾桶"跳"一下，松手即删，底部出现 4 秒的「撤销」。
 * - 点行跳播；顶部可切乱序 / 循环、定位到当前、清空其余。
 * - 下拉把手区或列表顶部继续下拉 → 跟手下滑，过线或快速一甩即关闭；系统返回也关闭。
 */
@Composable
fun QueueSheet(
    visible: Boolean,
    queue: List<QueueItem>,
    playing: Boolean,
    shuffle: Boolean,
    repeat: Int,
    songsByUri: Map<String, Song>,
    accent: Color,
    onJump: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onInsert: (Int, Song) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onClearOthers: () -> Unit,
    onDismiss: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    var kept by remember { mutableStateOf(visible) }
    var dragY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            kept = true
            dragY = 0f
            progress.animateTo(1f, Motion.travel())
        } else if (kept) {
            progress.animateTo(0f, tween(240, easing = Motion.FadeThroughOut))
            kept = false
            dragY = 0f
        }
    }
    BackHandler(enabled = visible) { onDismiss() }
    if (!kept) return

    val scheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissPx = with(density) { 120.dp.toPx() }

    // ── 本地行模型：与播放器队列按位置对齐，尽量复用旧 key ──
    val rows = remember { mutableStateListOf<QRow>() }
    var committed by remember { mutableStateOf<List<QRow>>(emptyList()) }
    var nextKey by remember { mutableStateOf(1L) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState,
        onMove = { from, to ->
            val f = rows.indexOfFirst { it.key == from }
            val t = rows.indexOfFirst { it.key == to }
            if (f >= 0 && t >= 0) rows.add(t, rows.removeAt(f))
        },
        onDrop = { key ->
            val from = committed.indexOfFirst { it.key == key }
            val to = rows.indexOfFirst { it.key == key }
            if (from >= 0 && to >= 0 && from != to) onMove(from, to)
            committed = rows.toList()
            // 此时 lastApplied 仍是拖动前应用的那份（旧）队列，松手后重跑 effect 会因 === 而跳过，
            // 只有上游给出新的列表引用才会重建，旧列表不会把刚排好的顺序冲回去
        },
    )
    // 上一次已应用到 rows 的上游列表引用
    var lastApplied by remember { mutableStateOf<List<QueueItem>?>(null) }
    LaunchedEffect(queue, reorder.draggingKey) {
        if (reorder.draggingKey != null) return@LaunchedEffect
        if (queue === lastApplied) return@LaunchedEffect
        lastApplied = queue
        val incoming = queue.map { it.id to it.title }
        if (incoming == rows.map { it.id to it.title }) return@LaunchedEffect
        val pool = rows.toMutableList()
        val rebuilt = queue.map { q ->
            val i = pool.indexOfFirst { it.id == q.id && it.title == q.title }
            if (i >= 0) pool.removeAt(i).copy(artist = q.artist)
            else QRow(nextKey++, q.id, q.title, q.artist)
        }
        rows.clear(); rows.addAll(rebuilt)
        committed = rebuilt
    }
    val currentIndex = queue.indexOfFirst { it.current }
    val currentKey = rows.getOrNull(currentIndex)?.takeIf { it.id == queue.getOrNull(currentIndex)?.id }?.key

    // 打开时直接停在当前曲目附近
    LaunchedEffect(visible) {
        if (visible && currentIndex > 0) listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
    }

    // ── 撤销 ──
    var undo by remember { mutableStateOf<Pair<Int, QRow>?>(null) }

    // ── 下拉关闭 ──
    // 回弹协程要能被新的拖动取消，否则两边同时写 dragY 会打架
    val releaseJob = remember { arrayOfNulls<Job>(1) }
    fun release(velocity: Float) {
        releaseJob[0]?.cancel()
        if (dragY > dismissPx || velocity > 1800f) onDismiss()
        else releaseJob[0] = scope.launch {
            val a = Animatable(dragY)
            a.animateTo(0f, Motion.press()) { dragY = value }
        }
    }
    val sheetDrag = rememberDraggableState { d -> dragY = (dragY + d).coerceAtLeast(0f) }
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (dragY > 0f && available.y < 0f) {
                    releaseJob[0]?.cancel()
                    val take = max(available.y, -dragY)
                    dragY += take
                    return Offset(0f, take)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (reorder.draggingKey == null && source == NestedScrollSource.UserInput && available.y > 0f) {
                    releaseJob[0]?.cancel()
                    dragY += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragY > 0f) { release(available.y); return available }
                return Velocity.Zero
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheetH = maxHeight * 0.92f
        val sheetPx = with(density) { sheetH.toPx() }
        // 遮罩：随进度与下拉量变淡；点空白处关闭
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    alpha = p * (1f - (dragY / sheetPx).coerceIn(0f, 1f))
                }
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetH)
                .graphicsLayer {
                    translationY = (1f - progress.value) * sheetPx + dragY
                    shadowElevation = 24.dp.toPx()
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    clip = true
                }
                .background(scheme.surface)
                .clickable(remember { MutableInteractionSource() }, indication = null) { }
                .nestedScroll(nested),
        ) {
            // 把手 + 标题区：整块可拖
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(sheetDrag, Orientation.Vertical, onDragStarted = { releaseJob[0]?.cancel() }, onDragStopped = { v -> release(v) })
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(scheme.onSurfaceVariantSummary.copy(alpha = 0.35f)),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.queue_page_title), style = MiuixTheme.textStyles.title2, color = scheme.onSurface)
                        androidx.compose.animation.AnimatedContent(
                            targetState = rows.size,
                            transitionSpec = { fadeThrough(initialScale = 1f) },
                            label = "queueCount",
                        ) { n ->
                            Text(stringResource(R.string.queue_count, n), style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        }
                    }
                    CompositionLocalTint(if (shuffle) accent else scheme.onSurfaceVariantSummary) {
                        MotionIconButton(onClick = onShuffle, size = 44.dp) {
                            MorphIcon(Icons.Rounded.Shuffle, stringResource(R.string.player_shuffle))
                        }
                    }
                    CompositionLocalTint(if (repeat != Player.REPEAT_MODE_OFF) accent else scheme.onSurfaceVariantSummary) {
                        MotionIconButton(onClick = onRepeat, size = 44.dp) {
                            MorphIcon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, stringResource(R.string.player_repeat))
                        }
                    }
                    CompositionLocalTint(scheme.onSurface) {
                        MotionIconButton(
                            onClick = { if (currentIndex >= 0) scope.launch { listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0)) } },
                            size = 44.dp,
                        ) { Icon(Icons.Rounded.MyLocation, stringResource(R.string.queue_locate), Modifier.size(20.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.queue_hint), style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary.copy(alpha = 0.8f), modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onClearOthers, enabled = rows.size > 1) {
                        Text(stringResource(R.string.queue_clear_others), style = MiuixTheme.textStyles.body2, color = if (rows.size > 1) accent else scheme.onSurfaceVariantSummary)
                    }
                }
            }
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    DotMatrixMark(Modifier.size(96.dp, 62.dp), cell = 7.5.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.queue_empty), style = MiuixTheme.textStyles.title4, color = scheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.queue_empty_hint), style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                ) {
                    itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
                        val isCurrent = row.key == currentKey
                        // 被拖的行不能再套 animateItem：它的位移由 reorderLift 按目标布局算，叠加放置动画会每次交换跳一行
                        Box(
                            Modifier
                                .then(if (reorder.isLifted(row.key)) Modifier else Modifier.animateItem())
                                .reorderLift(reorder, row.key),
                        ) {
                            SwipeToRemove(
                                enabled = reorder.draggingKey == null,
                                onRemove = {
                                    val i = rows.indexOfFirst { it.key == row.key }
                                    if (i >= 0) {
                                        rows.removeAt(i)
                                        committed = rows.toList()
                                        onRemove(i)
                                        undo = if (songsByUri.containsKey(row.id)) i to row else null
                                    }
                                },
                            ) {
                                QueueRow(
                                    index = index,
                                    row = row,
                                    song = songsByUri[row.id],
                                    current = isCurrent,
                                    playing = playing,
                                    accent = accent,
                                    lifted = reorder.draggingKey == row.key,
                                    onClick = {
                                        val i = rows.indexOfFirst { it.key == row.key }
                                        if (i >= 0) onJump(i)
                                    },
                                    handle = Modifier.reorderHandle(reorder, row.key),
                                )
                            }
                        }
                    }
                }
            }
        }
        UndoBar(
            message = undo?.second?.title?.let { stringResource(R.string.queue_removed, it) },
            actionLabel = stringResource(R.string.action_undo),
            onAction = {
                undo?.let { (i, row) ->
                    songsByUri[row.id]?.let { song -> onInsert(i, song) }
                }
                undo = null
            },
            onTimeout = { undo = null },
            token = undo,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
        )
    }
}

@Composable
private fun CompositionLocalTint(color: Color, content: @Composable () -> Unit) {
    val c by animateColorAsState(color, Motion.look(), label = "tint")
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c, content = content)
}

@Composable
private fun QueueRow(
    index: Int,
    row: QRow,
    song: Song?,
    current: Boolean,
    playing: Boolean,
    accent: Color,
    lifted: Boolean,
    onClick: () -> Unit,
    handle: Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val bg by animateColorAsState(
        when {
            lifted -> scheme.surfaceContainerHigh
            current -> accent.copy(alpha = 0.12f)
            else -> scheme.surface
        },
        Motion.look(), label = "rowBg",
    )
    val titleColor by animateColorAsState(if (current) accent else scheme.onSurface, Motion.look(), label = "rowTitle")
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .elasticPress(source, sink = true, sinkAmount = 0.02f, pull = false)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Artwork(song, Modifier.size(46.dp), radius = 12, requestPx = 160)
            if (current) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.42f)))
                NowPlayingBars(playing = playing, color = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.title, style = MiuixTheme.textStyles.body1, color = titleColor,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(row.artist, song?.spec.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "#${index + 1}" },
                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // 拖动把手：按住即拖（命中区 48dp）
        Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DragHandle, null, tint = scheme.onSurfaceVariantSummary, modifier = Modifier.size(22.dp))
        }
    }
}

/** 「正在播放」的三根跳动小柱；暂停时缓缓落到静止高度。 */
@Composable
fun NowPlayingBars(playing: Boolean, color: Color, modifier: Modifier = Modifier, barWidth: Dp = 3.dp) {
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "barsPhase")
    val amp by androidx.compose.animation.core.animateFloatAsState(if (playing) 1f else 0f, Motion.settle(), label = "barsAmp")
    Canvas(modifier) {
        val w = barWidth.toPx()
        val gap = (size.width - 3 * w) / 2f
        for (i in 0 until 3) {
            val local = (phase + i * 0.29f) % 1f
            val wave = 0.5f + 0.5f * kotlin.math.sin(local * 2f * Math.PI.toFloat())
            val h = size.height * (0.3f + 0.7f * wave * amp + 0.15f * (1f - amp) * (i % 2))
            drawRoundRect(
                color,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f),
            )
        }
    }
}
