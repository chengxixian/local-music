// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.audio.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small LAN/USB-tether speaker endpoint compatible with the AURALIS PC wired mode:
 * the first client sends raw 44.1 kHz, 16-bit, stereo little-endian PCM.
 * It is intentionally opt-in and binds to all interfaces only while enabled.
 */
object PcWiredSpeakerServer {
    private const val PORT = 8899
    private var thread: Thread? = null
    private var running = AtomicBoolean(false)

    @Synchronized fun start() {
        if (running.get()) return
        running.set(true)
        thread = Thread {
            runCatching {
                ServerSocket(PORT).use { server ->
                    while (running.get()) server.accept().use { client -> play(client) }
                }
            }.onFailure { PlaybackDiagnostics.mutable.value = "PC 有线音箱已停止：${it.message}" }
            running.set(false)
        }.apply { name = "local-music-pc-speaker"; isDaemon = true; start() }
        PlaybackDiagnostics.mutable.value = "PC 有线音箱监听中 · TCP $PORT · 44.1kHz/16bit/立体声"
    }

    @Synchronized fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun play(client: Socket) {
        val min = AudioTrack.getMinBufferSize(
            44_100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(44_100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buffer = ByteArray(min)
        track.play()
        try {
            client.getInputStream().use { input ->
                while (running.get()) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    track.write(buffer, 0, n, AudioTrack.WRITE_BLOCKING)
                }
            }
        } finally { runCatching { track.stop() }; track.release() }
    }
}
