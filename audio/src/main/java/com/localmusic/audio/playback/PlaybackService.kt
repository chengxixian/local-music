// SPDX-License-Identifier: GPL-3.0-or-later
// Derived from Rueded/AURALIS PlaybackService.kt (GPLv3).
// Changes: single player, supported mixer matching, route lifecycle, no DSP/network.
package com.localmusic.audio.playback

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackDiagnostics {
    internal val mutable = MutableStateFlow("系统音频输出 · 未请求 USB 直通")
    val status: StateFlow<String> = mutable.asStateFlow()
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var manager: AudioManager
    private lateinit var prefs: SharedPreferences
    private var session: MediaSession? = null
    private var preferred: AudioDeviceInfo? = null
    private val handler = Handler(Looper.getMainLooper())
    private val platformAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bitPerfect") handler.post { updateRoute() }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { updateRoute() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { updateRoute() }
    }
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true).build()
        }
        player = ExoPlayer.Builder(this, renderers).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setAudioOffloadPreferences(TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(sessionId: Int) {
                    // 均衡器等效果器绑定在"播放会话"上：会话重建（切音轨/切输出）后必须重新绑，
                    // 否则用户会看到"打开了但没效果"。
                    EqualizerController.attach(this@PlaybackService, sessionId)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateRoute() }
                override fun onTracksChanged(tracks: Tracks) { updateRoute() }
                override fun onPlayerError(error: PlaybackException) {
                    PlaybackDiagnostics.mutable.value = "播放失败：${error.errorCodeName} · ${error.message.orEmpty()}"
                }
            })
        }
        val builder = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                return super.onConnect(session, controller)
            }
        })
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        session = builder.build()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        manager.registerAudioDeviceCallback(deviceCallback, handler)
        updateRoute()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun clearMixer() {
        if (Build.VERSION.SDK_INT >= 34) preferred?.let {
            runCatching { manager.clearPreferredMixerAttributes(platformAttributes, it) }
        }
        preferred = null
        // 不再是直通状态 → 均衡器可以重新生效
        EqualizerController.setBypassed(false)
    }
    private fun updateRoute() {
        if (!::player.isInitialized) return
        clearMixer()
        player.setPreferredAudioDevice(null)
        fun status(text: String) { PlaybackDiagnostics.mutable.value = text }
        if (!prefs.getBoolean("bitPerfect", false)) { status("系统音频输出 · USB 直通关闭"); return }
        if (Build.VERSION.SDK_INT < 34) { status("USB Bit-perfect 需要 Android 14 及以上"); return }
        val usb = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: run { status("等待连接兼容 USB DAC · 当前使用系统输出"); return }
        val format = player.audioFormat
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        val rate = format?.sampleRate?.takeIf { it > 0 } ?: extras?.getInt("sampleRate", 0) ?: 0
        val channels = format?.channelCount?.takeIf { it > 0 } ?: extras?.getInt("channels", 0) ?: 0
        val bits = extras?.getInt("bitDepth", 0) ?: 0
        if (rate <= 0 || channels !in 1..2 || bits <= 0) {
            status("USB 已连接 · 源 PCM 规格未完整确认，使用系统输出"); return
        }
        try {
            // Float sink emits float for >16-bit PCM; do not request an unrelated integer format.
            val encoding = if (bits > 16) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val mixer = manager.getSupportedMixerAttributes(usb).firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.sampleRate == rate && it.format.encoding == encoding && it.format.channelMask == mask
            } ?: run { status("${usb.productName}：不支持当前 $rate Hz PCM 的直通组合；系统输出"); return }
            player.playbackParameters = PlaybackParameters.DEFAULT
            player.volume = 1f
            player.setPreferredAudioDevice(usb)
            if (manager.setPreferredMixerAttributes(platformAttributes, usb, mixer)) {
                preferred = usb
                EqualizerController.setBypassed(true) // 直通绕开系统混音，效果器插不进去
                status("USB 直通请求已接受 · ${rate / 1000.0} kHz · 非硬件测量证明")
            } else { player.setPreferredAudioDevice(null); status("设备拒绝 USB 直通请求；系统输出") }
        } catch (e: Exception) {
            player.setPreferredAudioDevice(null)
            status("USB 请求不可用：${e.message}；系统输出")
        }
    }
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(listener)
        if (::manager.isInitialized) { manager.unregisterAudioDeviceCallback(deviceCallback); clearMixer() }
        session?.release(); session = null
        EqualizerController.detach()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
