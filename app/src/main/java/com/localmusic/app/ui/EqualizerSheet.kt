// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import com.localmusic.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.localmusic.audio.playback.EqState
import com.liquidmiuix.theme.LiquidSpacing
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 均衡器：**液态玻璃面板**。
 *
 * 和导出提示一样是画在同一窗口里的浮层（不是 Dialog）——玻璃要折射，就得和采集层同窗口。
 * 内容用 verticalScroll 而不是 LazyColumn：项数量固定且不多，滚动条行为更可预期。
 */
@Composable
fun EqualizerSheet(
    backdrop: LayerBackdrop?,
    eq: EqState,
    presets: List<String>,
    onEnabled: (Boolean) -> Unit,
    onBand: (Int, Int) -> Unit,
    onBass: (Int) -> Unit,
    onLoudness: (Int) -> Unit,
    onPreset: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 不铺全屏压暗：背景保持原亮度，由烟熏玻璃自己变暗（"周围亮、玻璃暗"）。
    // 仍保留"点面板外收起"的手势。
    Box(
        Modifier.fillMaxSize().clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            backdrop = backdrop,
            shape = RoundedCornerShape(32.dp),
            refractionHeight = 22.dp,
            refractionAmount = 32.dp,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 20.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = LiquidSpacing.page, vertical = LiquidSpacing.item)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null, Modifier.size(22.dp), tint = scheme.primary)
                    Spacer(Modifier.width(LiquidSpacing.inline))
                    Text(stringResource(R.string.settings_equalizer), style = MiuixTheme.textStyles.title2, color = scheme.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.eq_collapse)) }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.eq_enable), style = MiuixTheme.textStyles.title4)
                        Text(stringResource(R.string.eq_scope), style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                    }
                    Switch(checked = eq.enabled, onCheckedChange = onEnabled, enabled = eq.available)
                }

                if (!eq.available) {
                    Spacer(Modifier.height(LiquidSpacing.inline))
                    Row(
                        Modifier.fillMaxWidth().background(scheme.error.copy(alpha = .12f), RoundedCornerShape(14.dp))
                            .padding(horizontal = LiquidSpacing.item, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(18.dp), tint = scheme.error)
                        Spacer(Modifier.width(LiquidSpacing.inline))
                        Text(eq.message.ifBlank { stringResource(R.string.eq_unavailable) }, style = MiuixTheme.textStyles.footnote1, color = scheme.error)
                    }
                }
                if (eq.bypassed) {
                    Spacer(Modifier.height(LiquidSpacing.inline))
                    Row(
                        Modifier.fillMaxWidth().background(scheme.primary.copy(alpha = .12f), RoundedCornerShape(14.dp))
                            .padding(horizontal = LiquidSpacing.item, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp), tint = scheme.primary)
                        Spacer(Modifier.width(LiquidSpacing.inline))
                        Text(stringResource(R.string.eq_bypassed_note), style = MiuixTheme.textStyles.footnote1, color = scheme.primary)
                    }
                }

                Spacer(Modifier.height(LiquidSpacing.inline))

                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    if (eq.available) {
                        if (presets.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(LiquidSpacing.tight),
                            ) {
                                presets.forEach { name -> TextButton(onClick = { onPreset(name) }) { Text(name) } }
                            }
                        }
                        eq.bands.forEach { band ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (band.centerHz >= 1000) "${"%.1f".format(band.centerHz / 1000f)} kHz" else "${band.centerHz} Hz",
                                    style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${if (band.levelMb >= 0) "+" else ""}${"%.1f".format(band.levelMb / 100f)} dB",
                                    style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                                )
                            }
                            Slider(
                                value = band.levelMb.toFloat().coerceIn(eq.minLevelMb.toFloat(), eq.maxLevelMb.toFloat()),
                                valueRange = eq.minLevelMb.toFloat()..eq.maxLevelMb.toFloat(),
                                onValueChange = { onBand(band.index, it.toInt()) },
                                enabled = eq.enabled,
                            )
                        }
                        if (eq.bassSupported) {
                            Text(stringResource(R.string.eq_bass, "%.0f".format(eq.bassStrength / 10f)), style = MiuixTheme.textStyles.body2)
                            Slider(value = eq.bassStrength.toFloat(), valueRange = 0f..1000f,
                                onValueChange = { onBass(it.toInt()) }, enabled = eq.enabled)
                        }
                        if (eq.loudnessSupported) {
                            Text(stringResource(R.string.eq_loudness, "%.1f".format(eq.loudnessGainMb / 100f)), style = MiuixTheme.textStyles.body2)
                            Slider(value = eq.loudnessGainMb.toFloat(), valueRange = 0f..1500f,
                                onValueChange = { onLoudness(it.toInt()) }, enabled = eq.enabled)
                        }
                    } else {
                        Text(
                            stringResource(R.string.eq_note_unavailable),
                            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary, textAlign = TextAlign.Start,
                        )
                    }
                    Spacer(Modifier.height(LiquidSpacing.inline))
                    Text(
                        stringResource(R.string.eq_note_bands),
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
