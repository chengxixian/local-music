// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.localmusic.app.R
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
    query: String = "",
    onQueryChange: ((String) -> Unit)? = null,
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
            // 曲库/喜欢页：顶栏直接当搜索框用（原来那张"搜索 + 我喜欢"的卡片已经去掉）
            if (onQueryChange != null) {
                Icon(Icons.Rounded.Search, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MiuixTheme.textStyles.title4.copy(color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.player_search_hint),
                                    style = MiuixTheme.textStyles.title4,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
                // 清除键：有字才出现，出现/消失走图标换形同一套（0.2 倍弹出 / 缩回）
                androidx.compose.animation.AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = fadeIn(tween(160)) + androidx.compose.animation.scaleIn(Motion.glyph(), initialScale = 0.2f),
                    exit = fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(160), targetScale = 0.2f),
                ) {
                    CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurfaceVariantSummary) {
                        MotionIconButton(onClick = { onQueryChange("") }, size = 32.dp) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.player_clear_search), Modifier.size(20.dp))
                        }
                    }
                }
            } else {
                // 切页时标题做一次"上滑淡入"，而不是整条顶栏瞬间换字
                AnimatedContent(
                    targetState = title,
                    transitionSpec = {
                        // 新标题自下而上滑入、旧标题向上滑出；位移用 Standard 缓动，与切页的 fade-through 同节拍
                        (fadeIn(tween(210, delayMillis = 60, easing = Motion.FadeThroughIn)) +
                            slideInVertically(tween(Motion.CommonMillis, easing = Motion.Standard)) { it / 2 })
                            .togetherWith(fadeOut(tween(90, easing = Motion.FadeThroughOut)) +
                                slideOutVertically(tween(Motion.CommonMillis, easing = Motion.Standard)) { -it / 3 })
                    },
                    label = "topBarTitle",
                ) { t ->
                    Text(
                        text = t,
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Compact always-on-top-in-the-app lyrics capsule. */
@Composable
fun FloatingLyricsOverlay(
    backdrop: LayerBackdrop?,
    song: Song?,
    positionFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (song == null) return
    val context = LocalContext.current
    val position by positionFlow.collectAsState()
    var lyrics by remember(song.uri) { mutableStateOf<LyricsRepository.Lyrics?>(null) }
    LaunchedEffect(song.uri) {
        lyrics = withContext(Dispatchers.IO) { LyricsRepository.load(context.applicationContext, song) }
    }
    val current = lyrics?.let { LyricsRepository.currentIndex(it, position) } ?: -1
    // 没有歌词 / 当前行恰好是空行时，以前直接 return —— 开关明明打开了却什么都不出现，
    // 用户会以为"这个开关没用"。现在退回显示「♪ 歌名」，至少能看到开关真的生效了。
    val text = lyrics?.lines?.getOrNull(current)?.text?.takeIf { it.isNotBlank() }
        ?: androidx.compose.ui.res.stringResource(com.localmusic.app.R.string.floating_lyrics_none, song.title)
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    GlassPanel(
        backdrop = backdrop,
        shape = RoundedCornerShape(22.dp),
        refractionHeight = 12.dp,
        refractionAmount = 18.dp,
        modifier = modifier
            .elasticPress(source, growth = 10.dp, maxScale = 0.05f)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        // 换行：新歌词从下方滑入、旧的向上淡出（像滚动的歌词，而不是整行瞬间换字）
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(Motion.CommonMillis, easing = Motion.Standard)) { it })
                    .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(Motion.CommonMillis, easing = Motion.Standard)) { -it })
            },
            label = "floatingLyric",
        ) { line ->
            Text(
                text = line,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                style = MiuixTheme.textStyles.body2,
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
    onQueue: () -> Unit = {},
) {
    // 整条按下轻微下沉（大面积元素用"沉"，小图标用"鼓"）；播放键单独鼓起 + 换形
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    GlassPanel(
        backdrop, RoundedCornerShape(24.dp),
        modifier.height(56.dp).fillMaxWidth()
            // 转场起点：播放页从这条胶囊"长"出来，关闭时（卡片不在屏幕上时）缩回这里
            .zoomSource(MiniZoomKey)
            .elasticPress(source, sink = true, sinkAmount = 0.03f),
    ) {
        Row(
            Modifier.fillMaxSize()
                .clickable(interactionSource = source, indication = null, onClick = onOpen)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.inline),
        ) {
            // 切歌：封面与文字一起 fade-through，而不是瞬间换掉
            AnimatedContent(
                targetState = song,
                transitionSpec = { fadeThrough(initialScale = 0.86f) },
                modifier = Modifier.zoomSourceCover(MiniZoomKey),
                label = "miniArt",
            ) { s -> Artwork(s, Modifier.size(40.dp), radius = 14, requestPx = 420) }
            AnimatedContent(
                targetState = player.title to player.artist,
                transitionSpec = { fadeThrough(initialScale = 1f) },
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f),
                label = "miniText",
            ) { (title, artist) ->
                Column(Modifier.fillMaxWidth()) {
                    Text(title, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artist, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MotionIconButton(onClick = onToggle) {
                MorphIcon(
                    if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (player.playing) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                )
            }
            // 直接打开「播放队列」编辑页（不必先进播放页）
            MotionIconButton(onClick = onQueue, size = 40.dp) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.mini_queue), Modifier.size(22.dp))
            }
        }
    }
}

/** 播放页标题行距状态栏的呼吸位。 */
private val TopRowGap = 8.dp

/**
 * 全屏播放器。
 *
 * 设计要点（按用户要求）：**不是整页一块玻璃**，而是
 *  ① 围着封面一圈的玻璃光环（折射的是它下面的页面内容）
 *  ② 播放控件单独一块玻璃面板
 *  ③ 页面中间一块区域有三种状态：封面 / 歌词 / **播放列表**（可增删、可上下调序）
 *
 * 点封面 → 歌词；点歌词 → 回封面；顶部「列表」玻璃钮 → 播放列表。
 */
private enum class Middle { Cover, Lyrics }

@Composable
fun PlayerPage(
    backdrop: LayerBackdrop?,
    song: Song?,
    player: PlaybackUi,
    favorite: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    positionFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onFavorite: () -> Unit,
    onClose: () -> Unit,
    onChangeCover: () -> Unit,
    onOpenEq: () -> Unit,
    /** 打开「播放队列」编辑页（独立的一页，不再挤在中间区域里）。 */
    onOpenQueue: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 主题色：优先取当前歌曲封面的鲜艳色，取不到再回落默认主题色
    // NOTHING 配色：控件本身只用白 / 灰，红色只留给进度条与喜欢这类小面积信号。
    // 封面平均色只用来判断背景明暗（决定文字黑白），不再当作强调色。
    // 点阵皮肤：控件用白 / 灰，红色只做点缀；M3E：恢复"封面平均色"动态取色。
    val nothing = com.localmusic.app.data.UiSettings.skin == com.localmusic.app.data.UiSkin.NOTHING
    val coverTone = rememberPlayerAccent(song)
    val accent = if (nothing) Color.White else (coverTone ?: scheme.primary)
    // 文字随背景明暗自适应：背景偏亮用深色字，偏暗用浅色字
    // （accent 就是封面的平均色，也正是播放页背景的兜底色，可代表背景明暗）
    val onBackdrop = if ((coverTone ?: Color.Black).luminance() > 0.68f) Color.Black else Color.White
    val context = LocalContext.current
    // 播放进度在播放页内部收集：只有这一屏会跟着 400ms 的进度重组，曲库网格不受影响
    val livePosition by positionFlow.collectAsState()
    var middle by remember(song?.uri) { mutableStateOf(Middle.Cover) }
    // 封面位图提到页面层：切歌时新旧两张同时在动画里，不能各自异步加载（会闪占位唱片）
    val coverRevision by com.localmusic.app.data.CoverStore.revision.collectAsState()
    val coverBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        song?.let { ArtworkStore.peek(it, 420)?.asImageBitmap() }, song?.uri, coverRevision,
    ) {
        value = withContext(Dispatchers.IO) {
            song?.let { s ->
                (com.localmusic.app.data.CoverStore.decode(context.applicationContext, s.uri, 900)
                    ?: ArtworkStore.load(context.applicationContext, s, 900))?.asImageBitmap()
            }
        }
    }
    // 切歌方向：下一首 → 新封面从右边滑进来；上一首 → 从左边
    val trackDir = rememberTrackDirection(player.queue.indexOfFirst { it.current })
    var lyrics by remember(song?.uri) { mutableStateOf<LyricsRepository.Lyrics?>(null) }
    var lyricsLoaded by remember(song?.uri) { mutableStateOf(false) }

    // ── 进入播放页的"弹出"动画 ──
    // 一个 0→1 的弹簧进度，再按窗口切成三拍错峰：图标钮先出现 → 封面放大淡入 → 控件面板从下往上推到
    // 位。用弹簧（有回弹）而不是 tween，配合液态玻璃的形变手感。
    // 入场动画交给外层的卡片转场；这里直接给最终值，避免"外层缩放 + 内层再缩放"导致封面先小后大
    val enter = remember { Animatable(1f) }
    val progress = enter.value
    fun stage(from: Float, until: Float): Float =
        ((progress - from) / (until - from).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    val iconStage = stage(0f, 0.45f)
    val coverStage = stage(0.10f, 0.75f)
    val controlStage = stage(0.30f, 1f)

    LaunchedEffect(song?.uri, middle) {
        if (middle == Middle.Lyrics && !lyricsLoaded && song != null) {
            lyrics = withContext(Dispatchers.IO) { LyricsRepository.load(context.applicationContext, song) }
            lyricsLoaded = true
        }
    }

    // 页面版本：上下留白交给调用方（顶部是玻璃顶栏、底部是 dock）
    Box(
        Modifier.fillMaxSize().padding(top = topPadding + TopRowGap, bottom = bottomPadding),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = LiquidSpacing.page),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部四个**独立的玻璃圆钮**：返回（收起播放页）/ 播放列表 / 换封面 / 均衡器。
            // 不放文字标题，歌名信息在下面的控件面板里，避免两处重复。第一拍入场。
            Row(
                Modifier.fillMaxWidth().graphicsLayer {
                    alpha = iconStage
                    val s = 0.7f + 0.3f * iconStage
                    scaleX = s; scaleY = s
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(backdrop, Icons.Rounded.ArrowBack, stringResource(R.string.player_close), onClose)
                Spacer(Modifier.weight(1f))
                GlassIconButton(
                    backdrop,
                    Icons.AutoMirrored.Rounded.PlaylistPlay,
                    stringResource(R.string.player_queue),
                    onClick = onOpenQueue,
                )
                Spacer(Modifier.width(LiquidSpacing.inline))
                GlassIconButton(backdrop, Icons.Rounded.AddPhotoAlternate, stringResource(R.string.player_pick_cover), onChangeCover)
                Spacer(Modifier.width(LiquidSpacing.inline))
                // 这两个按钮的无障碍文案以前写死中文：英文/日文/俄文用户听到的是中文。
                GlassIconButton(backdrop, Icons.Rounded.GraphicEq, stringResource(R.string.settings_equalizer), onOpenEq)
            }

            Spacer(Modifier.height(LiquidSpacing.item))

            // ① 中间区域三态：封面（带玻璃光环）/ 歌词 / 播放列表。第二拍入场：放大 + 淡入。
            Box(
                Modifier.weight(1f).fillMaxWidth().graphicsLayer {
                    alpha = coverStage
                    val s = 0.88f + 0.12f * coverStage
                    scaleX = s; scaleY = s
                },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = middle,
                    transitionSpec = { fadeThrough(initialScale = 1f) },
                    contentAlignment = Alignment.Center,
                    // 占满中间区域：三种面板尺寸不同，若随内容测量会多出一段尺寸动画，封面位置也会跟着漂
                    modifier = Modifier.fillMaxSize(),
                    label = "playerMiddle",
                ) { pane ->
                when (pane) {
                    Middle.Cover -> CoverWithGlassRing(
                        backdrop = backdrop, song = song, cover = coverBitmap, direction = trackDir,
                        onClick = { middle = Middle.Lyrics },
                    )
                    Middle.Lyrics -> GlassPanel(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(28.dp),
                        refractionHeight = 20.dp,
                        refractionAmount = 30.dp,
                        modifier = Modifier.fillMaxSize().clickable { middle = Middle.Cover },
                    ) {
                        LyricsPane(accent = accent, lyrics = lyrics, loaded = lyricsLoaded, positionMs = livePosition)
                    }
                }
                }
            }

            Spacer(Modifier.height(LiquidSpacing.item))

            // ② 播放控件：单独一块玻璃。第三拍入场：从下往上推 + 淡入 + 轻微放大。
            GlassPanel(
                backdrop = backdrop,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    alpha = controlStage
                    translationY = (1f - controlStage) * 56.dp.toPx()
                    val s = 0.94f + 0.06f * controlStage
                    scaleX = s; scaleY = s
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = LiquidSpacing.card, vertical = LiquidSpacing.item),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val titleStyle = MiuixTheme.textStyles.title4
                    val artistStyle = MiuixTheme.textStyles.footnote1
                    Text(
                        player.title,
                        style = titleStyle,
                        color = onBackdrop,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier,
                    )
                    Text(
                        player.artist,
                        style = artistStyle,
                        color = onBackdrop.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        song?.let {
                            LiquidPill(it.format.uppercase())
                            if (it.bitDepth > 0) LiquidPill("${it.bitDepth}bit / ${it.sampleRate / 1000.0}kHz")
                            if (it.channels > 0) LiquidPill("${it.channels}ch")
                        }
                    }
                    Spacer(Modifier.height(LiquidSpacing.inline))
                    SeekBar(player, livePosition, onSeek, accent)
                    Spacer(Modifier.height(LiquidSpacing.tight))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        MotionIconButton(onClick = onShuffle) {
                            MorphIcon(Icons.Rounded.Shuffle, stringResource(R.string.player_shuffle), tint = if (player.shuffle) accent else scheme.onSurfaceVariantSummary)
                        }
                        MotionIconButton(onClick = onPrev) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_prev)) }
                        // 主播放键：实心圆 + 鼓起；底色随封面取色平滑过渡，播放⇄暂停换形
                        val keyColor by androidx.compose.animation.animateColorAsState(accent, Motion.look(), label = "playKeyColor")
                        CompositionLocalProvider(LocalContentColor provides scheme.onPrimary) {
                            MotionIconButton(
                                onClick = onToggle,
                                size = 56.dp,
                                highlight = scheme.onPrimary.copy(alpha = 0.16f),
                                containerColor = keyColor,
                            ) {
                                MorphIcon(
                                    if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    if (player.playing) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                                    Modifier.size(30.dp),
                                )
                            }
                        }
                        MotionIconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next)) }
                        MotionIconButton(onClick = onFavorite) {
                            MorphIcon(
                                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                if (favorite) stringResource(R.string.player_unfavorite) else stringResource(R.string.player_favorite),
                                tint = if (favorite) (if (nothing) Color(0xFFE1191F) else accent) else scheme.onSurfaceVariantSummary,
                            )
                        }
                        MotionIconButton(onClick = onRepeat) {
                            MorphIcon(
                                if (player.repeat == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                stringResource(R.string.player_repeat),
                                tint = if (player.repeat != androidx.media3.common.Player.REPEAT_MODE_OFF) accent else scheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    player.error?.let {
                        Spacer(Modifier.height(LiquidSpacing.tight))
                        Text(stringResource(R.string.player_error, it), style = MiuixTheme.textStyles.footnote1, color = scheme.error)
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
private fun CoverWithGlassRing(
    backdrop: LayerBackdrop?,
    song: Song?,
    cover: androidx.compose.ui.graphics.ImageBitmap?,
    direction: Int,
    onClick: () -> Unit,
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .elasticPress(source, sink = true, sinkAmount = 0.025f, pull = false)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier.matchParentSize().liquidGlass(
                backdrop = backdrop,
                shape = RoundedCornerShape(48.dp),
                refractionHeight = 20.dp,
                refractionAmount = 34.dp,
            )
        )
        // 共享元素终点：转场时这块先隐藏，由转场层画飞行中的封面，落位后再交还
        Box(
            Modifier.matchParentSize().padding(18.dp)
                .zoomShared(LocalPlayerZoom.current)
                .clip(RoundedCornerShape(34.dp)),
        ) {
            AnimatedContent(
                targetState = cover,
                transitionSpec = { coverSwap(direction) },
                modifier = Modifier.matchParentSize(),
                label = "glassCover",
            ) { bmp ->
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bmp, song?.album, Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Artwork(song, Modifier.fillMaxSize(), radius = 34, requestPx = 900)
                }
            }
        }
    }
}

@Composable
private fun LyricsPane(lyrics: LyricsRepository.Lyrics?, loaded: Boolean, positionMs: Long, accent: Color) {
    // 当前歌词行的高亮色：封面色偏暗，直接当高亮看不清 -> 与白/黑混合提亮（保留色相）
    val highlight = if (accent.luminance() > 0.68f) lerp(accent, Color.Black, 0.45f) else lerp(accent, Color.White, 0.55f)
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
            // 早先这句写死了中文、而且把来源说少了（只提 .lrc 和内嵌）——
            // 实际上**刮削缓存**才是第一优先级，用户看到这句会以为不支持在线歌词。
            Text(stringResource(R.string.lyrics_missing_short), style = MiuixTheme.textStyles.title4, color = scheme.onSurface)
            Spacer(Modifier.height(LiquidSpacing.inline))
            Text(
                stringResource(R.string.lyrics_missing_hint_full),
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
                            color = if (index == current) highlight else scheme.onSurface.copy(alpha = if (lyrics.timed) 0.75f else 1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(player: PlaybackUi, livePosition: Long, onSeek: (Long) -> Unit, accent: Color) {
    val duration = player.duration.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val position = if (dragging) dragValue.toLong() else livePosition.coerceIn(0L, duration)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = position.toFloat().coerceIn(0f, duration.toFloat()),
            valueRange = 0f..duration.toFloat(),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; onSeek(dragValue.toLong()) },
            colors = SliderDefaults.colors(
                thumbColor = if (com.localmusic.app.data.UiSettings.skin == com.localmusic.app.data.UiSkin.NOTHING) Color(0xFFE1191F) else accent,
                activeTrackColor = accent,
                inactiveTrackColor = accent.copy(alpha = 0.24f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(position), style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
            Text(formatTime(duration), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
