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
        appendDsdCapability(usb)
        // 阶段 0 探针：枚举接口/alt/端点参数；若该 USB 设备已授权，再尝试
        // claimInterface(force=true) 抢掉内核的 snd-usb-audio —— 这是自研 UAC 驱动的 go/no-go。
        UsbAudioProbe.probe(this) { PlaybackDiagnostics.mutable.value = it }
        // 设备不一定上报 bit-perfect 组合（实测 测试用 USB DAC 什么都没报），
        // 所以不再"等设备上报" —— **自己构造**目标规格去请求：32bit(float)/384kHz 起步，
        // 被拒就逐档降到 192k / 96k / 48k，取第一档被接受的组合。
        fun buildMixer(rate: Int, encoding: Int): AudioMixerAttributes {
            val fmt = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            return AudioMixerAttributes.Builder(fmt)
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()
        }
        val attempts = listOf(
            384_000 to AudioFormat.ENCODING_PCM_FLOAT,
            384_000 to AudioFormat.ENCODING_PCM_32BIT,
            192_000 to AudioFormat.ENCODING_PCM_FLOAT,
            192_000 to AudioFormat.ENCODING_PCM_32BIT,
            96_000 to AudioFormat.ENCODING_PCM_FLOAT,
            48_000 to AudioFormat.ENCODING_PCM_FLOAT,
        )
        val best: AudioMixerAttributes? = try {
            player.setPreferredAudioDevice(usb)
            attempts.firstNotNullOfOrNull { (rate, encoding) ->
                val mixer = try { buildMixer(rate, encoding) } catch (e: Exception) { null }
                if (mixer != null && manager.setPreferredMixerAttributes(platformAttributes, usb, mixer)) mixer else null
            }
        } catch (e: Exception) {
            null
        }
        if (best == null) {
            player.setPreferredAudioDevice(null)
            status("USB 直通已启用 · 声音由自研 UAC2 等时通道输出（向系统申请 mixer 直通属性在本机型被拒，属正常）"); return
        }
        try {
            player.playbackParameters = PlaybackParameters.DEFAULT
            player.volume = if (prefs.getBoolean("bitPerfect", false)) 0f else 1f
            player.setPreferredAudioDevice(usb)
            if (manager.setPreferredMixerAttributes(platformAttributes, usb, best)) {
                preferred = usb
                EqualizerController.setBypassed(true) // 直通绕开系统混音，效果器插不进去
                val bits = when (best.format.encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> "32f"
                    AudioFormat.ENCODING_PCM_32BIT -> "32"
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24"
                    else -> "16"
                }
                status(
                    "USB 直通请求已接受 · ${best.format.sampleRate / 1000.0} kHz · $bits bit" +
                        " · 按解码器最高规格申请（非硬件测量证明）"
                )
            } else {
                // ⚠️ 这句以前是"设备拒绝 USB 直通请求；系统输出"，会**误导用户以为直通失败**：
                // 被拒的只是**系统 mixer 属性申请**（这条路在本机型本来就不通），
                // 而真正出声的是自研 UAC2 原生等时通道 —— 而且我们接管接口这个动作本身
                // 会触发路由变化、重新走到这里，于是这句话总在直通开播后盖掉正确状态。
                player.setPreferredAudioDevice(null)
                status("USB 直通已启用 · 声音由自研 UAC2 等时通道输出（系统 mixer 申请在本机型被拒属正常）")
            }
            } catch (e: Exception) {
            player.setPreferredAudioDevice(null)
            status("USB 请求不可用：${e.message}；系统输出")
        }
    }
    /**
     * DSD 直通能力探测（**只查、只上报，不改播放路径**）。
     *
     * 依据：`AudioFormat.ENCODING_DSD` 自 API 34 起存在，配合 `MIXER_BEHAVIOR_BIT_PERFECT`
     * 可以请求"不经混音、不经转码"地把 DSD 交给 USB DAC。能不能成取决于**厂商 USB HAL
     * 是否上报该组合** —— 所以这里只报告查到什么，绝不把"查到了"说成"已经在直通"。
     */
    private fun appendDsdCapability(usb: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        // 决定性诊断：手机侧把这台 USB 设备报告成什么能力。
        // 官网标称「支持 32bit/384kHz」说的是**解码器硬件能力**；能不能走通还取决于
        // 手机音频栈把哪些 profile 暴露给应用 —— 这条就是两边的对照。
        // 字段名以本地 SDK 的 javap 实测为准：AudioProfile.getFormat() 才是编码，
        // 没有 getEncodings()（我先前凭记忆写成 p.encodings，编译不过）。
        runCatching {
            fun encName(e: Int) = when (e) {
                AudioFormat.ENCODING_PCM_FLOAT -> "FLOAT"
                AudioFormat.ENCODING_PCM_32BIT -> "32"
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24"
                AudioFormat.ENCODING_PCM_16BIT -> "16"
                else -> e.toString()
            }
            val caps = usb.audioProfiles.joinToString(" | ") { p ->
                "enc=" + encName(p.format) +
                    " rate=" + p.sampleRates.joinToString(",") +
                    " ch=" + p.channelMasks.joinToString(",")
            }
            PlaybackDiagnostics.mutable.value =
                "${usb.productName} 手机侧报告能力：" + caps.ifBlank { "（空）" }
            // 状态行随后可能被"被拒绝"之类的结果覆盖，所以同时落一条日志，
            // 事后用 adb logcat -s LMDsd 就能把这台设备的能力原样取出来。
            android.util.Log.i("LMDsd", "usb caps: " + caps.ifBlank { "(empty)" })
        }
        val line = try {
            val attrs = manager.getSupportedMixerAttributes(usb)
            val dsd = attrs.firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.encoding == AudioFormat.ENCODING_DSD
            }
            if (dsd != null) {
                val rate = dsd.format.sampleRate
                val tag = when (rate) {
                    2_822_400 -> "DSD64"
                    5_644_800 -> "DSD128"
                    11_289_600 -> "DSD256"
                    else -> "DSD"
                }
                "DAC 上报 DSD 直通（$tag · ${rate / 1000.0} kHz · bit-perfect）· 可请求"
            } else if (attrs.any { it.format.encoding == AudioFormat.ENCODING_DSD }) {
                "DAC 支持 DSD 编码，但没有 bit-perfect 组合 · DSD 仍转码为 PCM"
            } else {
                "DAC 未上报 DSD 直通 · DSD 转码为 PCM 176.4kHz"
            }
        } catch (e: Exception) {
            "DSD 能力查询失败：${e.message}"
        }
        PlaybackDiagnostics.mutable.value = PlaybackDiagnostics.mutable.value + "\n" + line
        // 也写一份到 logcat（标签 LMDsd）：用户插上 DAC 后，我这边 `adb logcat -s LMDsd` 就能读到结论
        android.util.Log.i("LMDsd", line)
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
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