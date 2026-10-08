// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.localmusic.app.data.Song
import com.localmusic.audio.playback.PlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 播放列表里的一行（index 是在真正队列里的下标，调序/删除都按它来）。 */
data class QueueItem(val index: Int, val title: String, val artist: String, val current: Boolean)

data class PlaybackUi(
    val connected: Boolean = false,
    val id: String? = null,
    val title: String = "还没有开始播放",
    val artist: String = "从曲库选择一首音乐",
    val playing: Boolean = false,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    val queue: List<QueueItem> = emptyList(),
)

class PlayerConnection(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(PlaybackUi())
    val state = mutable.asStateFlow()

    /** 播放进度（毫秒）。单独一条流，避免 400ms 一次的进度更新把整棵界面树拖着重组。 */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private var lastQueueKey: Pair<Int, Int>? = null
    private var queueCache: List<QueueItem> = emptyList()
    private var controller: MediaController? = null
    private var closed = false
    /** 控制器还没连上时收到的播放请求先存这里，连上后立刻执行（否则冷启动头几秒的点击会被静默丢掉）。 */
    private var pending: (MediaController.() -> Unit)? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { update() }
        override fun onPlayerError(error: PlaybackException) { mutable.value = mutable.value.copy(error = error.errorCodeName) }
    }
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    init {
        future.addListener({
            if (!closed) try {
                controller = future.get().also { it.addListener(listener) }
                update()
                pending?.let { action -> pending = null; controller?.action() }
            } catch (e: Exception) { mutable.value = mutable.value.copy(error = "播放服务连接失败：${e.message}") }
        }, ContextCompat.getMainExecutor(context))
        scope.launch { while (isActive) { update(); delay(400) } }
    }
    private fun update() {
        val p = controller ?: return
        // 播放进度单独一条流：它 400ms 变一次，但不能让它把整个 AppShell（含 374 格的曲库网格）
        // 拖着一起重组。只有播放页/迷你条会收集它。
        _position.value = p.currentPosition.coerceAtLeast(0)
        // 队列只在"曲目数 / 当前曲目"变化时重建 —— 否则每 400ms 就要 new 374 个对象
        val queueKey = p.mediaItemCount to p.currentMediaItemIndex
        if (queueKey != lastQueueKey) {
            lastQueueKey = queueKey
            queueCache = buildQueue(p)
        }
        val next = PlaybackUi(true, p.currentMediaItem?.mediaId, p.mediaMetadata.title?.toString() ?: "还没有开始播放",
            p.mediaMetadata.artist?.toString() ?: "从曲库选择一首音乐", p.isPlaying,
            p.duration.coerceAtLeast(0), p.shuffleModeEnabled, p.repeatMode, p.playerError?.errorCodeName,
            queue = queueCache)
        // 值没变就不发射：稳态播放时下面这些字段其实都是常量
        if (next != mutable.value) mutable.value = next
    }

    /** 队列快照给播放列表面板用（上限 500，避免超大队列每 400ms 拼一次字符串）。 */
    private fun buildQueue(p: MediaController): List<QueueItem> {
        val count = p.mediaItemCount
        if (count <= 0) return emptyList()
        val limit = minOf(count, 500)
        return (0 until limit).map { i ->
            val item = p.getMediaItemAt(i)
            QueueItem(
                index = i,
                title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "未知曲目",
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                current = i == p.currentMediaItemIndex,
            )
        }
    }

    /** 播放列表编辑：调序 / 删除 / 追加 / 下一首播放 / 跳播。 */
    fun moveInQueue(from: Int, to: Int) {
        val p = controller ?: return
        if (from == to || from !in 0 until p.mediaItemCount || to !in 0 until p.mediaItemCount) return
        p.moveMediaItem(from, to)
        update()
    }

    fun removeFromQueue(index: Int) {
        val p = controller ?: return
        if (index !in 0 until p.mediaItemCount) return
        p.removeMediaItem(index)
        update()
    }

    fun appendToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val action: MediaController.() -> Unit = { addMediaItems(songs.map { it.mediaItem() }) }
        val p = controller
        if (p == null) pending = action else { p.action(); update() }
    }

    fun playNext(song: Song) {
        val action: MediaController.() -> Unit = {
            val at = (currentMediaItemIndex + 1).coerceIn(0, mediaItemCount)
            addMediaItems(at, listOf(song.mediaItem()))
        }
        val p = controller
        if (p == null) pending = action else { p.action(); update() }
    }

    fun jumpTo(index: Int) {
        val p = controller ?: return
        if (index !in 0 until p.mediaItemCount) return
        p.seekTo(index, 0L)
        p.play()
        update()
    }
    /**
     * 播放列表里的某一首：队列 = 传入列表，起点 = 该首在列表中的位置，
     * 之后**按列表顺序往后播到末尾**（乐单按加入顺序、喜欢按曲库顺序）。
     *
     * ⚠️ [shuffle] 默认是 **null（不动）**，不是 false：
     * 以前默认 false → `shuffleModeEnabled = false` → **每次点歌都会把用户的乱序播放关掉**，
     * 与"除非用户选择乱序播放，否则按顺序播"的预期相反。现在只有调用方明确指定时才改，
     * 否则保留用户当前的乱序/顺序选择。
     */
    fun play(songs: List<Song>, selected: Song, shuffle: Boolean? = null) {
        if (songs.isEmpty()) return
        val startIndex = songs.indexOfFirst { it.uri == selected.uri }.coerceAtLeast(0)
        // 诊断日志（tag LMQueue）：把"本次队列到底是谁、起点在哪、顺序如何"记下来。
        // 用户反馈"喜欢里的顺序不对"，而 LibraryPage 并不重排列表 —— 与其继续推理，
        // 不如留证据：下次复现后直接看这条日志就能判断是队列问题还是页面问题。
        android.util.Log.i(
            "LMQueue",
            "play 队列=${songs.size} 起点=${startIndex + 1}/${songs.size} 选中=[${selected.title}] " +
                "顺序抽样=" + songs.take(6).joinToString(" | ") { it.title },
        )
        val action: MediaController.() -> Unit = {
            setMediaItems(songs.map { it.mediaItem() }, startIndex, 0)
            // 只在调用方明确指定时才动乱序开关
            shuffle?.let { shuffleModeEnabled = it }
            // 「播放完一轮回到第一首」：默认列表循环。
            // 播放页那个循环按钮仍可切换 关 / 列表 / 单曲；若用户手动关掉，
            // 在下次点歌时会重新回到"列表循环"（因为你要求的就是一轮之后回到第一首）。
            if (repeatMode == Player.REPEAT_MODE_OFF) repeatMode = Player.REPEAT_MODE_ALL
            prepare(); play()
        }
        val p = controller
        if (p == null) pending = action else { p.action(); update() }
    }
    fun toggle() {
        val p = controller
        if (p == null) { pending = { if (isPlaying) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } }; return }
        if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
    }
    fun next() { controller?.seekToNextMediaItem() ?: run { pending = { seekToNextMediaItem() } } }
    fun previous() { controller?.seekToPrevious() ?: run { pending = { seekToPrevious() } } }
    fun seek(value: Long) { controller?.seekTo(value.coerceAtLeast(0)) }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = when(it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }
    fun close() { closed = true; scope.cancel(); controller?.removeListener(listener); MediaController.releaseFuture(future); controller = null }
}
