// SPDX-License-Identifier: GPL-3.0-or-later
// 均衡器实现思路参考 Rueded/AURALIS（GPLv3）：都基于系统 audiofx 效果器绑定播放会话。
package com.localmusic.audio.playback

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqBand(val index: Int, val centerHz: Int, val levelMb: Int)

data class EqState(
    val attached: Boolean = false,
    val available: Boolean = false,
    val enabled: Boolean = false,
    /** USB 直通生效时系统混音被跳过，效果器插不进去 —— 界面要如实说明，而不是假装有效。 */
    val bypassed: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val bassSupported: Boolean = false,
    val bassStrength: Int = 0,
    val loudnessSupported: Boolean = false,
    val loudnessGainMb: Int = 0,
    val message: String = "",
)

/**
 * 系统均衡器 / 低音增强 / 响度增强。
 *
 * 用 Android 的 `android.media.audiofx` 作用于**当前播放会话**（不是自研 DSP）：
 * 好处是所有解码路径都自动生效、不需要重采样；代价是必须拿到 audioSessionId，
 * 而且一旦开启 USB 直通（绕过系统混音）就失效——这一点在界面上明确提示。
 */
object EqualizerController {
    private const val TAG = "Eq"
    private const val PREFS = "eq"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BANDS = "bands"
    private const val KEY_BASS = "bass"
    private const val KEY_LOUDNESS = "loudness"

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private val lock = Any()
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var appContext: Context? = null
    private var sessionId: Int = 0
    private var bypassed = false

    /** 预设：以毫贝(mB)为单位，0 = 不平抑。 */
    private val presets: Map<String, IntArray> = mapOf(
        "平坦" to intArrayOf(0, 0, 0, 0, 0),
        "低音" to intArrayOf(600, 400, 100, -100, 0),
        "人声" to intArrayOf(-200, 0, 300, 400, 200),
        "摇滚" to intArrayOf(500, 200, -100, 300, 500),
    )
    val presetNames: List<String> get() = presets.keys.toList()

    fun attach(context: Context, newSessionId: Int) {
        if (newSessionId == 0) return
        synchronized(lock) {
            appContext = context.applicationContext
            if (sessionId == newSessionId && equalizer != null) return
            releaseLocked()
            sessionId = newSessionId
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var available = false
            try {
                equalizer = Equalizer(0, sessionId).also { eq ->
                    eq.enabled = false
                    available = true
                    val range = eq.bandLevelRange
                    val bands = (0 until eq.numberOfBands).map { i ->
                        EqBand(i, (eq.getCenterFreq(i.toShort()) / 1000), eq.getBandLevel(i.toShort()).toInt())
                    }
                    _state.value = _state.value.copy(
                        attached = true, available = true, bands = bands,
                        minLevelMb = range[0].toInt(), maxLevelMb = range[1].toInt(),
                    )
                    // 恢复用户上次的调节
                    val saved = prefs.getString(KEY_BANDS, null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                    if (saved != null) {
                        bands.forEach { band -> saved.getOrNull(band.index)?.let { eq.setBandLevel(band.index.toShort(), it.toShort()) } }
                        refreshBands()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "均衡器不可用：${e.message}")
                equalizer = null
            }
            try {
                bassBoost = BassBoost(0, sessionId).also { it.enabled = false }
            } catch (e: Exception) { bassBoost = null }
            try {
                loudness = LoudnessEnhancer(sessionId).also { it.enabled = false }
            } catch (e: Exception) { loudness = null }

            _state.value = _state.value.copy(
                attached = true, available = available,
                bassSupported = bassBoost != null, bassStrength = prefs.getInt(KEY_BASS, 0),
                loudnessSupported = loudness != null, loudnessGainMb = prefs.getInt(KEY_LOUDNESS, 0),
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                message = if (available) "" else "这台设备没有可用的系统均衡器",
            )
            applyLocked()
        }
    }

    fun detach() {
        synchronized(lock) {
            releaseLocked()
            _state.value = EqState(bypassed = bypassed, message = "")
        }
    }

    /** USB 直通开启/关闭时调用。 */
    fun setBypassed(value: Boolean) {
        synchronized(lock) {
            bypassed = value
            _state.value = _state.value.copy(bypassed = value)
            applyLocked()
        }
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            _state.value = _state.value.copy(enabled = enabled)
            persistLocked()
            applyLocked()
        }
    }

    fun setBand(index: Int, levelMb: Int) {
        synchronized(lock) {
            val eq = equalizer ?: return
            val range = eq.bandLevelRange
            val clamped = levelMb.coerceIn(range[0].toInt(), range[1].toInt())
            runCatching { eq.setBandLevel(index.toShort(), clamped.toShort()) }
                .onFailure { Log.w(TAG, "设置频段失败：${it.message}") }
            refreshBands()
            persistLocked()
        }
    }

    fun setBass(strength: Int) {
        synchronized(lock) {
            val value = strength.coerceIn(0, 1000)
            runCatching { bassBoost?.setStrength(value.toShort()) }
            _state.value = _state.value.copy(bassStrength = value)
            persistLocked()
            applyLocked()
        }
    }

    fun setLoudness(gainMb: Int) {
        synchronized(lock) {
            val value = gainMb.coerceIn(0, 1500)
            runCatching { loudness?.setTargetGain(value) }
            _state.value = _state.value.copy(loudnessGainMb = value)
            persistLocked()
            applyLocked()
        }
    }

    /** 预设按频段数量就近映射（不同设备频段数不同，常见 5 段）。 */
    fun applyPreset(name: String) {
        synchronized(lock) {
            val eq = equalizer ?: return
            val preset = presets[name] ?: return
            val count = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            for (i in 0 until count) {
                val source = if (preset.size == count) preset[i]
                else preset[(i.toFloat() / (count - 1).coerceAtLeast(1) * (preset.size - 1)).toInt().coerceIn(0, preset.size - 1)]
                runCatching { eq.setBandLevel(i.toShort(), source.coerceIn(range[0].toInt(), range[1].toInt()).toShort()) }
            }
            refreshBands()
            persistLocked()
        }
    }

    /** 把用户调节写回设备（会话重建后要重新 apply）。 */
    private fun applyLocked() {
        val on = _state.value.enabled && !bypassed
        runCatching { equalizer?.enabled = on }
            .onFailure { Log.w(TAG, "均衡器开关失败：${it.message}") }
        if (_state.value.bassSupported) runCatching {
            bassBoost?.setStrength(_state.value.bassStrength.toShort())
            bassBoost?.enabled = on && _state.value.bassStrength > 0
        }
        if (_state.value.loudnessSupported) runCatching {
            loudness?.setTargetGain(_state.value.loudnessGainMb)
            loudness?.enabled = on && _state.value.loudnessGainMb > 0
        }
    }

    private fun refreshBands() {
        val eq = equalizer ?: return
        val bands = (0 until eq.numberOfBands).map { i ->
            EqBand(i, eq.getCenterFreq(i.toShort()) / 1000, eq.getBandLevel(i.toShort()).toInt())
        }
        _state.value = _state.value.copy(bands = bands)
    }

    private fun persistLocked() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putBoolean(KEY_ENABLED, _state.value.enabled)
            .putString(KEY_BANDS, _state.value.bands.joinToString(",") { it.levelMb.toString() })
            .putInt(KEY_BASS, _state.value.bassStrength)
            .putInt(KEY_LOUDNESS, _state.value.loudnessGainMb)
            .apply()
    }

    private fun releaseLocked() {
        runCatching { equalizer?.release() }; equalizer = null
        runCatching { bassBoost?.release() }; bassBoost = null
        runCatching { loudness?.release() }; loudness = null
        sessionId = 0
    }
}
