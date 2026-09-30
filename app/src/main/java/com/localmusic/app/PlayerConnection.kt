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
    val position: Long = 0,
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
        mutable.value = PlaybackUi(true, p.currentMediaItem?.mediaId, p.mediaMetadata.title?.toString() ?: "还没有开始播放",
            p.mediaMetadata.artist?.toString() ?: "从曲库选择一首音乐", p.isPlaying, p.currentPosition.coerceAtLeast(0),
            p.duration.coerceAtLeast(0), p.shuffleModeEnabled, p.repeatMode, p.playerError?.errorCodeName,
            queue = buildQueue(p))
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
    fun play(songs: List<Song>, selected: Song, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val action: MediaController.() -> Unit = {
            setMediaItems(songs.map { it.mediaItem() }, songs.indexOfFirst { it.uri == selected.uri }.coerceAtLeast(0), 0)
            shuffleModeEnabled = shuffle
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
