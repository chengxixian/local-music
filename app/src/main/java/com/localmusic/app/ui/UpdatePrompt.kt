// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import com.localmusic.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.localmusic.app.data.UpdateChecker
import com.liquidmiuix.theme.LiquidSpacing
import com.liquidmiuix.ui.LiquidStatusBanner
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新版本提示（液态玻璃面板）。
 *
 * 显示远端 tag、说明与体积，按钮：下载并安装 / 以后再说。
 * 下载交给系统 DownloadManager，完成后拉起系统安装器（会要求"允许安装未知应用"）。
 */
@Composable
fun UpdatePrompt(
    backdrop: LayerBackdrop?,
    update: UpdateChecker.Update,
    downloading: Boolean,
    progress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    error: String?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    GlassPanel(
        backdrop = backdrop,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        refractionHeight = 20.dp,
        refractionAmount = 30.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = LiquidSpacing.page),
    ) {
        Column(Modifier.fillMaxWidth().padding(LiquidSpacing.page)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(LiquidSpacing.inline))
                Text(stringResource(R.string.update_found, update.tag), style = MiuixTheme.textStyles.title4)
            }
            Spacer(Modifier.height(LiquidSpacing.tight))
            Text(
                buildString {
                    append(stringResource(R.string.update_note_outdated))
                    if (update.sizeBytes > 0) append(stringResource(R.string.update_note_size, update.sizeBytes / 1024 / 1024))
                    append(stringResource(R.string.update_note_install))
                },
                style = MiuixTheme.textStyles.body2,
                color = scheme.onSurfaceVariantSummary,
            )
            if (update.notes.isNotBlank()) {
                Spacer(Modifier.height(LiquidSpacing.inline))
                Text(
                    update.notes,
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onSurfaceVariantSummary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            error?.let {
                Spacer(Modifier.height(LiquidSpacing.inline))
                LiquidStatusBanner(text = it, icon = Icons.Rounded.CloudDownload, color = scheme.error)
            }
            Spacer(Modifier.height(LiquidSpacing.item))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (downloading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(LiquidSpacing.inline))
                    Text(
                        buildString {
                            append(stringResource(R.string.update_downloading) + " ")
                            append("${(progress * 100).toInt()}%")
                            if (totalBytes > 0) {
                                append(stringResource(R.string.update_progress_mb, downloadedBytes / 1024 / 1024, totalBytes / 1024 / 1024))
                            }
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onUpdate) {
                        Icon(Icons.Rounded.CloudDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.update_download_install))
                    }
                }
            }
            if (downloading) {
                Spacer(Modifier.height(LiquidSpacing.tight))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
