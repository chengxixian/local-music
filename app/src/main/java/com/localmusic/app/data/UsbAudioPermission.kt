// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * USB 设备授权（探针用）。
 *
 * Android 规定：要用某个 USB 设备（`openDevice` / `claimInterface`）必须先由用户授权，
 * 系统会弹一次窗。这里把"找音频设备 → 弹窗 → 回调结果"包起来，供自研 UAC 驱动的
 * go/no-go 探针使用。
 *
 * 注意：广播接收器用 `RECEIVER_NOT_EXPORTED`（API 33+ 强制要求），
 * `PendingIntent` 用 `FLAG_MUTABLE`（系统需要往里塞 EXTRA_PERMISSION_GRANTED）。
 */
object UsbAudioPermission {

    private const val ACTION = "com.localmusic.app.USB_PERMISSION"

    /** 若已有权限直接回调 true；否则弹窗，用户在弹窗上选择后回调。 */
    fun ensure(context: Context, onResult: (Boolean) -> Unit) {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: run {
            onResult(false); return
        }
        val dev = um.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        } ?: run { onResult(false); return }

        if (um.hasPermission(dev)) { onResult(true); return }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                runCatching { context.unregisterReceiver(this) }
                onResult(intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) == true)
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        runCatching {
            context.registerReceiver(receiver, IntentFilter(ACTION), flags)
        }.onFailure { onResult(false); return }

        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        runCatching { um.requestPermission(dev, pi) }.onFailure { onResult(false) }
    }
}
