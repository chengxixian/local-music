// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import com.localmusic.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmiuix.theme.LiquidSpacing
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次打开时的「选一个导出文件夹」提示。
 *
 * 为什么画成浮层而不是真 Dialog：液态玻璃要采样**同窗口**的采集层，
 * `Dialog` 是另一个窗口，玻璃在里面折射不到任何东西，只能做出一块假的半透明色块。
 */
@Composable
fun ExportFolderPrompt(
    backdrop: LayerBackdrop?,
    onPick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 同均衡器面板：不压暗背景，靠烟熏玻璃自身变暗
    Box(
        Modifier.fillMaxSize().clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            backdrop = backdrop,
            shape = RoundedCornerShape(32.dp),
            refractionHeight = 22.dp,
            refractionAmount = 32.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(40.dp), tint = scheme.primary)
                Spacer(Modifier.height(LiquidSpacing.item))
                Text(stringResource(R.string.export_pick_title), style = MiuixTheme.textStyles.title2, color = scheme.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(LiquidSpacing.inline))
                Text(
                    stringResource(R.string.export_pick_body),
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Start,
                )
                Spacer(Modifier.height(LiquidSpacing.page))
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.export_pick_action))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
            }
        }
    }
}
