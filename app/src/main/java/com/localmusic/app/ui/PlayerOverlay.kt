// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.localmusic.app.PlaybackUi
import com.localmusic.app.data.LyricsRepository
import com.localmusic.app.data.Song
import com.localmusic.app.data.formatTime
import com.liquidmiuix.glass.liquidGlass
import com.liquidmiuix.theme.LiquidSpacing
import com.liquidmiuix.ui.LiquidPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 悬浮玻璃顶栏（"额头"）。
 *
 * 与 dock 同样是**采集层之外**的玻璃浮层：页面内容不再为顶栏留高度，
 * 而是滚到它下面被折射/模糊掉。玻璃修饰符挂在空 Box 上，标题做它的兄弟。
 */
@Composable
fun GlassTopBar(
    backdrop: LayerBackdrop?,
    title: String,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        backdrop = backdrop,
        // 细长条用比 dock 更小的折射量：高度只有 56dp，40dp 的边缘位移会整条糊成一团。
        shape = RoundedCornerShape(28.dp),
        refractionHeight = 16.dp,
        refractionAmount = 26.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = LiquidSpacing.page),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline),
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 悬浮玻璃迷你播放条（dock 之上，采集层之外）。 */
@Composable
fun MiniPlayerBar(
    backdrop: LayerBackdrop?,
    song: Song?,
    player: PlaybackUi,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(backdrop, RoundedCornerShape(28.dp), modifier.height(64.dp).fillMaxWidth()) {
        Row(
            Modifier.fillMaxSize().clickable(onClick = onOpen).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline),
        ) {
            Artwork(song, Modifier.size(44.dp), radius = 14)
            Column(Modifier.weight(1f)) {
                Text(player.title, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(player.artist, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onToggle) {
                Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) "暂停" else "播放")
            }
        }
    }
}

/** 播放页标题行距状态栏的呼吸位。 */
private val TopRowGap = 8.dp

/** 播放页整页均匀压暗的强度（不是渐变：渐变会在边界留下可察觉的硬边）。 */
private const val ScrimAlpha = 0.28f

/**
 * 全屏播放器。
 *
 * 设计要点（按用户要求）：**不是整页一块玻璃**，而是
 *  ① 围着封面一圈的玻璃光环（折射的是它下面的页面内容）
 *  ② 播放控件单独一块玻璃面板
 *  ③ 底部的 dock 仍然是玻璃，所以本页**不给 dock 让位**（由 [bottomInset] 留白，dock 保持可见可点）
 *
 * 点一下封面 → 显示歌词（再点一下回到封面）。
 */
@Composable
fun PlayerOverlay(
    backdrop: LayerBackdrop?,
    song: Song?,
    player: PlaybackUi,
    favorite: Boolean,
    topInset: Dp,
    bottomInset: Dp,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onFavorite: () -> Unit,
    onChangeCover: () -> Unit,
    onOpenEq: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    var showLyrics by remember(song?.uri) { mutableStateOf(false) }
    var lyrics by remember(song?.uri) { mutableStateOf<LyricsRepository.Lyrics?>(null) }
    var lyricsLoaded by remember(song?.uri) { mutableStateOf(false) }

    LaunchedEffect(song?.uri, showLyrics) {
        if (showLyrics && !lyricsLoaded && song != null) {
            lyrics = withContext(Dispatchers.IO) { LyricsRepository.load(context.applicationContext, song) }
            lyricsLoaded = true
        }
    }

    // ⚠️ modifier 顺序：**先** background 再 padding。
    // 反过来的话（先 padding 再 background）压暗层只覆盖"状态栏以下、dock 以上"那块，
    // 上下会各留一条没压暗的硬边 —— 看起来就是"有的地方加深了、有的地方没有"（深色模式同样明显）。
    // 现在是一层铺满全屏的**均匀**压暗；内容间距由后面的 padding 负责。
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = ScrimAlpha))
            .padding(top = topInset + TopRowGap, bottom = bottomInset),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = LiquidSpacing.page),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部只放三个**独立的玻璃圆钮**：返回 / 换封面 / 均衡器（不放文字标题，
            // 歌名信息在下面的控件面板里，避免两处重复）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(backdrop, Icons.Rounded.ArrowBack, "返回", onClose)
                Spacer(Modifier.weight(1f))
                GlassIconButton(backdrop, Icons.Rounded.AddPhotoAlternate, "选择封面", onChangeCover)
                Spacer(Modifier.width(LiquidSpacing.inline))
                GlassIconButton(backdrop, Icons.Rounded.GraphicEq, "均衡器", onOpenEq)
            }

            Spacer(Modifier.height(LiquidSpacing.item))

            // ① 封面 + 四周的玻璃光环 / 歌词
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!showLyrics) {
                    CoverWithGlassRing(backdrop = backdrop, song = song, onClick = { showLyrics = true })
                } else {
                    GlassPanel(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(28.dp),
                        refractionHeight = 20.dp,
                        refractionAmount = 30.dp,
                        modifier = Modifier.fillMaxSize().clickable { showLyrics = false },
                    ) {
                        LyricsPane(lyrics = lyrics, loaded = lyricsLoaded, positionMs = player.position)
                    }
                }
            }

            Spacer(Modifier.height(LiquidSpacing.item))

            // ② 播放控件：单独一块玻璃
            GlassPanel(
                backdrop = backdrop,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = LiquidSpacing.card, vertical = LiquidSpacing.item),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(player.title, style = MiuixTheme.textStyles.title4, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(player.artist, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        song?.let {
                            LiquidPill(it.format.uppercase())
                            if (it.bitDepth > 0) LiquidPill("${it.bitDepth}bit / ${it.sampleRate / 1000.0}kHz")
                            if (it.channels > 0) LiquidPill("${it.channels}ch")
                        }
                    }
                    Spacer(Modifier.height(LiquidSpacing.inline))
                    SeekBar(player, onSeek)
                    Spacer(Modifier.height(LiquidSpacing.tight))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onShuffle) {
                            Icon(Icons.Rounded.Shuffle, "随机", tint = if (player.shuffle) scheme.primary else scheme.onSurfaceVariantSummary)
                        }
                        IconButton(onClick = onPrev) { Icon(Icons.Rounded.SkipPrevious, "上一首") }
                        FilledIconButton(onClick = onToggle, modifier = Modifier.size(56.dp)) {
                            Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (player.playing) "暂停" else "播放", modifier = Modifier.size(30.dp))
                        }
                        IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "下一首") }
                        IconButton(onClick = onFavorite) {
                            Icon(
                                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                if (favorite) "取消喜欢" else "加入我喜欢的音乐",
                                tint = if (favorite) scheme.primary else scheme.onSurfaceVariantSummary,
                            )
                        }
                        IconButton(onClick = onRepeat) {
                            Icon(
                                if (player.repeat == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                "循环",
                                tint = if (player.repeat != androidx.media3.common.Player.REPEAT_MODE_OFF) scheme.primary else scheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    player.error?.let {
                        Spacer(Modifier.height(LiquidSpacing.tight))
                        Text("播放错误：$it", style = MiuixTheme.textStyles.footnote1, color = scheme.error)
                    }
                }
            }
        }
    }
}

/**
 * 封面 + 围绕它的一圈液态玻璃。
 *
 * 结构：外层 Box 的尺寸由 **modifier（fillMaxWidth + aspectRatio）** 决定，
 * 所以里面两个 `matchParentSize` 子项（玻璃层 / 内容层）不会把它塌成 0。
 * 内容层留 18dp 内边距 → 玻璃作为一圈"光环"露出来。
 */
@Composable
private fun CoverWithGlassRing(backdrop: LayerBackdrop?, song: Song?, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onClick)) {
        Box(
            Modifier.matchParentSize().liquidGlass(
                backdrop = backdrop,
                shape = RoundedCornerShape(48.dp),
                refractionHeight = 20.dp,
                refractionAmount = 34.dp,
            )
        )
        Box(Modifier.matchParentSize().padding(18.dp)) {
            Artwork(song, Modifier.matchParentSize(), radius = 34, requestPx = 900)
        }
    }
}

@Composable
private fun LyricsPane(lyrics: LyricsRepository.Lyrics?, loaded: Boolean, positionMs: Long) {
    val scheme = MiuixTheme.colorScheme
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        lyrics == null -> Column(
            Modifier.fillMaxSize().padding(LiquidSpacing.page),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("没有找到歌词", style = MiuixTheme.textStyles.title4, color = scheme.onSurface)
            Spacer(Modifier.height(LiquidSpacing.inline))
            Text(
                "支持两种来源：\n· 与音频同目录、同名的 .lrc 文件\n· 音频文件里内嵌的歌词（ID3 USLT / Vorbis LYRICS）\n\n点一下可以回到封面",
                style = MiuixTheme.textStyles.body2,
                color = scheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
        else -> {
            val listState = rememberLazyListState()
            val current = LyricsRepository.currentIndex(lyrics, positionMs)
            LaunchedEffect(current) {
                if (current >= 0) listState.animateScrollToItem(current.coerceAtMost(lyrics.lines.lastIndex))
            }
            Column(Modifier.fillMaxSize().padding(horizontal = LiquidSpacing.card, vertical = LiquidSpacing.item)) {
                Text(lyrics.source, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(LiquidSpacing.inline))
                LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(LiquidSpacing.inline)) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        Text(
                            text = line.text.ifBlank { "♪" },
                            style = if (index == current) MiuixTheme.textStyles.title4 else MiuixTheme.textStyles.body1,
                            color = if (index == current) scheme.primary else scheme.onSurface.copy(alpha = if (lyrics.timed) 0.75f else 1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(player: PlaybackUi, onSeek: (Long) -> Unit) {
    val duration = player.duration.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val position = if (dragging) dragValue.toLong() else player.position.coerceIn(0L, duration)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = position.toFloat().coerceIn(0f, duration.toFloat()),
            valueRange = 0f..duration.toFloat(),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; onSeek(dragValue.toLong()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(position), style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
            Text(formatTime(duration), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
