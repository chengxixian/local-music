// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.audio.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PC 有线音箱的接收端：第一个连上来的客户端发送裸 44.1kHz / 16bit / 立体声（小端）PCM。
 *
 * 与 AURALIS 的 `PcAudioReceiver` 同协议（`adb reverse tcp:8899 tcp:8899`）。
 *
 * ⚠️ **socket 生命周期是这里的关键**：`Thread.interrupt()` 不会唤醒阻塞在 `accept()` 上的线程，
 * 所以关掉开关时必须**直接 close() ServerSocket** 才能把线程放出来、把 8899 端口真正释放。
 * 以前的实现只在 `stop()` 里 interrupt，线程一直卡在 accept()，端口没释放：
 * 再次打开开关就 `bind failed: EADDRINUSE`，界面显示「PC 有线音箱已停止」——
 * 也就是"打开了但用不了"。
 */
object PcWiredSpeakerServer {
    private const val PORT = 8899
    private const val SAMPLE_RATE = 44_100

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var track: AudioTrack? = null

    /**
     * 这一路自己的状态（`idle` / `listening` / `connected` / `error:<原因>`）。
     *
     * 以前它把状态写进 `PlaybackDiagnostics` —— 那是**全 App 共用的一行**，
     * USB 路由、DSD 能力、睡眠定时器都在抢，用户看到的那条横幅经常是别的子系统的。
     * 现在设置页「PC 有线音箱」这一行自己订阅这条流。
     */
    private val _status = kotlinx.coroutines.flow.MutableStateFlow("idle")
    val status: kotlinx.coroutines.flow.StateFlow<String> = _status

    @Synchronized fun start() {
        if (running.get()) return
        // 上一轮若留下未释放的监听 socket，先清掉（双保险，正常路径由 stop() 负责）
        closeQuietly(server); server = null
        closeQuietly(client); client = null
        closeTrack()

        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            running.set(false)
            _status.value = "error:${e.message}"
            return
        }
        server = socket
        running.set(true)
        thread = Thread({ acceptLoop(socket) }, "local-music-pc-speaker").apply {
            isDaemon = true
            start()
        }
        _status.value = "listening"
    }

    @Synchronized fun stop() {
        val wasRunning = running.getAndSet(false)
        // ① 先关客户端：让正在播的那一路 read() 立刻返回，线程不再占着 AudioTrack
        closeQuietly(client); client = null
        // ② 再关监听 socket：这是唯一能唤醒 accept() 的办法
        closeQuietly(server); server = null
        // ③ AudioTrack 的 write 是阻塞的，停止它才能让线程及时退出
        closeTrack()
        thread?.interrupt()
        thread = null
        if (wasRunning) _status.value = "idle"
    }

    /** 开关状态（界面/诊断用）。 */
    val isRunning: Boolean get() = running.get()

    private fun acceptLoop(serverSocket: ServerSocket) {
        try {
            while (running.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    // stop() 关闭监听 socket 时 accept 会抛 SocketException —— 这是正常的退出路径
                    null
                } ?: break
                if (!running.get()) { closeQuietly(socket); break }
                client = socket
                _status.value = "connected"
                try {
                    play(socket)
                } catch (e: Exception) {
                    _status.value = "error:${e.message}"
                } finally {
                    client = null
                    closeQuietly(socket)
                    if (running.get()) _status.value = "listening"
                }
            }
        } finally {
            running.set(false)
            closeTrack()
        }
    }

    private fun play(client: Socket) {
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = audioTrack
        val buffer = ByteArray(min)
        audioTrack.play()
        try {
            client.getInputStream().use { input ->
                while (running.get()) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    // PCM 必须是整帧（4 字节）才能写，否则左右声道会错位
                    audioTrack.write(buffer, 0, n - (n % 4), AudioTrack.WRITE_BLOCKING)
                }
            }
        } finally {
            track = null
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
        }
    }

    private fun closeQuietly(socket: ServerSocket?) = runCatching { socket?.close() }.let { }
    private fun closeQuietly(socket: Socket?) = runCatching { socket?.close() }.let { }

    private fun closeTrack() {
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.stop() }
        runCatching { t.release() }
    }
}
