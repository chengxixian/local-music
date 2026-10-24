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
import android.os.SystemClock
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
import kotlin.math.pow

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
    private var fadeGeneration = 0
    private var sleepTimerEnd = 0L
    private var fadeOutIndex = -1
    /** 当前正在走原生 USB 直通的那首（null = 没有直通会话）。 */
    @Volatile private var passthroughUri: String? = null
    private val platformAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bitPerfect" || key == "fadeDurationMs" || key == "normalizeVolume") handler.post {
            if (key == "bitPerfect") {
                updateRoute()
                // 开关一变就对齐原生通道：开 → 当前这首走直通；关 → 立刻关管道回到系统输出。
                syncPassthrough()
                applyPlaybackVolume(fadeIn = false)
            } else applyPlaybackVolume(fadeIn = false)
        }
        if (key == "sleepTimerEnd") handler.post { refreshSleepTimer() }
        if (key == "pcWiredSpeaker") handler.post {
            if (prefs.getBoolean("pcWiredSpeaker", false)) PcWiredSpeakerServer.start() else PcWiredSpeakerServer.stop()
        }
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
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    fadeOutIndex = -1
                    // 直通通道要跟着"当前这首"走：切歌时不换源，DAC 会继续把上一首播完，
                    // 而 ExoPlayer 已经在放下一首（静音）→ 声音与界面彻底脱节。
                    syncPassthrough()
                    applyPlaybackVolume(fadeIn = true)
                }
                override fun onTracksChanged(tracks: Tracks) {
                    // ⚠️ 这里**不再**调 updateRoute()：路由（mixer 属性 / 首选设备）与"哪一首"无关，
                    // 而 updateRoute() 会 clearMixer + 重新申请 6 档 mixer 属性（binder 调用），
                    // 每首歌来一遍等于在歌曲边界拆掉再重建 AudioTrack —— 爆音/断音，EQ 状态也会闪。
                    // 只有在**开关变化 / 设备插拔**时才需要重新协商。
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    syncPassthrough()
                    applyPlaybackVolume(fadeIn = false)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && player.playWhenReady) applyPlaybackVolume(fadeIn = true)
                }
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
        refreshSleepTimer()
        scheduleFadeMonitor()
        if (prefs.getBoolean("pcWiredSpeaker", false)) PcWiredSpeakerServer.start()
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
        if (!prefs.getBoolean("bitPerfect", false)) {
            status("系统音频输出 · USB 直通关闭")
            applyPlaybackVolume(fadeIn = false)
            return
        }
        if (Build.VERSION.SDK_INT < 34) { status("USB Bit-perfect 需要 Android 14 及以上 · 已使用系统输出"); applyPlaybackVolume(fadeIn = false); return }
        val usb = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: run { status("等待连接兼容 USB DAC · 当前使用系统输出"); applyPlaybackVolume(fadeIn = false); return }
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
            // 申请被拒 ≠ 直通失败：真正出声的是自研 UAC2 原生等时通道。
            // 但也不能在这里静音 —— 静音只由 syncPassthrough/applyPlaybackVolume 在
            // "确认原生通道在跑"之后决定。
            player.setPreferredAudioDevice(null)
            status("USB 直通已启用 · 声音由自研 UAC2 等时通道输出（向系统申请 mixer 直通属性在本机型被拒，属正常）")
            applyPlaybackVolume(fadeIn = false)
            return
        }
        try {
            player.playbackParameters = PlaybackParameters.DEFAULT
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
        applyPlaybackVolume(fadeIn = false)
    }

    /** Apply ReplayGain track gain and optionally ramp the track in. USB passthrough stays bit-perfect. */
    private fun applyPlaybackVolume(fadeIn: Boolean) {
        if (!::player.isInitialized) return
        // 只有**确认在跑的直通通道**才允许静音系统输出。
        // 旧实现是"prefs.bitPerfect 为真就 volume=0"，于是：没插 DAC / Android < 14 /
        // 采样率不在 DAC 支持列表 / 没有 USB 权限 时，唯一能出声的系统输出被自己静音了 ——
        // 表现就是"进度条在走、一点声音没有"，也就是用户说的"开了这个开关就没声了"。
        val passthrough = prefs.getBoolean("bitPerfect", false) && UsbUac2Stream.isStreaming
        if (passthrough) { player.volume = 0f; return }
        val gain = if (prefs.getBoolean("normalizeVolume", true)) {
            player.currentMediaItem?.mediaMetadata?.extras?.getFloat("replayGainDb", 0f) ?: 0f
        } else 0f
        // A conservative ceiling prevents a tagged +6 dB track from clipping the mixer.
        // 下限放到 0.06：ReplayGain 的标签常是 -10…-20 dB，旧下限 0.25（≈ -12 dB）
        // 会把安静轨道的衰减截断，"音量标准化"就只对一部分歌生效。
        val target = (10.0.pow((gain.coerceIn(-30f, 6f) / 20.0).toDouble())).toFloat().coerceIn(0.06f, 1f)
        val duration = prefs.getLong("fadeDurationMs", 900L).coerceIn(0L, 10_000L)
        fadeGeneration++
        val generation = fadeGeneration
        handler.removeCallbacksAndMessages("volumeFade")
        if (!fadeIn || duration == 0L) { player.volume = target; return }
        player.volume = 0f
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (generation != fadeGeneration || !::player.isInitialized) return
                val p = ((System.currentTimeMillis() - started).toFloat() / duration).coerceIn(0f, 1f)
                player.volume = target * p
                if (p < 1f) handler.postAtTime(this, "volumeFade", SystemClock.uptimeMillis() + 16L)
            }
        }
        handler.post(tick)
    }

    /**
     * 让原生 UAC2 直通通道**跟着播放状态走**。
     *
     * 以前这条通道只由界面点歌那一下启动（`MainActivity.playSong`），播放服务完全不知道它存在：
     * 暂停、拖动进度、下一首、自动续播、退出应用都不会停它 —— DAC 继续把上一首放完，
     * 而 ExoPlayer 已经在放下一首（静音），声音和界面各走各的。
     *
     * 现在由服务统一对齐：本曲真正播放时才启动，暂停/切歌/停止时释放。
     * 直通跑完（或失败）后会重新应用音量，保证系统输出能出声。
     */
    private fun syncPassthrough() {
        if (!::player.isInitialized) return
        val want = prefs.getBoolean("bitPerfect", false) && player.isPlaying
        if (!want) {
            if (passthroughUri != null) {
                passthroughUri = null
                UsbUac2Stream.stop()
                applyPlaybackVolume(fadeIn = false)
            }
            return
        }
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: item.mediaId
        if (uri.isBlank() || uri == passthroughUri) return
        passthroughUri = uri
        Thread({
            runCatching { UsbUac2Stream.playFile(this, uri) }
            passthroughUri = null
            handler.post {
                val why = UsbUac2Stream.lastResult
                PlaybackDiagnostics.mutable.value =
                    "USB 直通结束 · 已回到系统输出" + if (why.isNotBlank()) "（$why）" else ""
                applyPlaybackVolume(fadeIn = false)
            }
        }, "local-music-usb-passthrough").start()
        // 直通是异步启动的：`isStreaming` 要等接口真正占用后才为真。
        // 稍后再对一次账 —— 成功就静音系统输出，失败就保持系统输出有声，两种情况都对。
        handler.postDelayed({ applyPlaybackVolume(fadeIn = false) }, 400L)
    }

    private fun scheduleFadeMonitor() {
        val monitor = object : Runnable {
            override fun run() {
                if (!::player.isInitialized) return
                val fade = prefs.getLong("fadeDurationMs", 900L).coerceIn(0L, 10_000L)
                // 只有"真的在放歌 + 开了淡出"才需要 10Hz 轮询；暂停/关闭时降到 1Hz，
                // 免得息屏后还在后台每秒醒 10 次（耗电）。
                val active = fade > 0 && player.isPlaying && player.duration > 0
                if (active && !prefs.getBoolean("bitPerfect", false) && player.hasNextMediaItem()) {
                    val remaining = player.duration - player.currentPosition
                    if (remaining in 1..fade && fadeOutIndex != player.currentMediaItemIndex) {
                        fadeOutIndex = player.currentMediaItemIndex
                        val gain = if (prefs.getBoolean("normalizeVolume", true)) player.currentMediaItem?.mediaMetadata?.extras?.getFloat("replayGainDb", 0f) ?: 0f else 0f
                        val target = (10.0.pow((gain.coerceIn(-30f, 6f) / 20.0).toDouble())).toFloat().coerceIn(0.06f, 1f)
                        player.volume = target * (remaining.toFloat() / fade)
                    }
                }
                handler.postDelayed(this, if (active) 100L else 1_000L)
            }
        }
        handler.post(monitor)
    }

    private fun refreshSleepTimer() {
        sleepTimerEnd = prefs.getLong("sleepTimerEnd", 0L)
        handler.removeCallbacksAndMessages("sleepTimer")
        if (sleepTimerEnd <= 0L) return
        val check = object : Runnable {
            override fun run() {
                if (!::player.isInitialized) return
                val remaining = sleepTimerEnd - System.currentTimeMillis()
                if (remaining <= 0L) {
                    player.pause()
                    prefs.edit().remove("sleepTimerEnd").apply()
                    PlaybackDiagnostics.mutable.value = "睡眠定时器已结束 · 播放已暂停"
                } else handler.postAtTime(this, "sleepTimer", SystemClock.uptimeMillis() + minOf(remaining, 1000L))
            }
        }
        handler.post(check)
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
        // 直通会话是独立线程 + 原始 fd：服务销毁时必须主动停，否则 DAC 会继续放下去。
        runCatching { UsbUac2Stream.stop() }
        passthroughUri = null
        PcWiredSpeakerServer.stop()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(listener)
        if (::manager.isInitialized) { manager.unregisterAudioDeviceCallback(deviceCallback); clearMixer() }
        session?.release(); session = null
        EqualizerController.detach()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
