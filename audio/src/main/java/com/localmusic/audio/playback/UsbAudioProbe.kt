// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.audio.playback

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * USB 音频驱动的**阶段 0 探针**：只做两件事，用于判断"自研 UAC 驱动"这条路是否走得通。
 *
 * 1. 枚举 DAC 的接口 / alt setting / 端点参数（等时端点的 maxPacketSize、interval、方向）。
 *    这些是驱动需要的第一手信息，也是判断它是不是标准 UAC2 设备的依据。
 * 2. **尝试 `claimInterface(iface, force = true)`** —— 这一步是 go / no-go：
 *    内核的 `snd-usb-audio` 正持有音频接口，只有把它 detach 掉，应用才能自己发等时传输。
 *    Android 对 audio class 的 claim 有系统级限制，被拒就说明**普通应用做不了驱动**。
 *
 * 注意：claim 需要**已授予该 USB 设备的权限**（系统弹窗授权）。没有权限时本探针只做枚举，
 * 并明确记下"未授权"—— 绝不把"没测"说成"不支持"。
 */
object UsbAudioProbe {

    private const val TAG = "LMUsb"

    /** 枚举并（在已有权限时）尝试抢占接口。返回可读的多行报告。 */
    fun probe(context: Context, diagnostics: (String) -> Unit) {
        val report = StringBuilder()
        try {
            val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return finish("无法获取 UsbManager", diagnostics)
            val devices = um.deviceList.values.filter { isAudio(it) }
            if (devices.isEmpty()) {
                return finish("未发现 USB 音频设备（deviceList 里没有 audio class）", diagnostics)
            }
            for (dev in devices) {
                val granted = um.hasPermission(dev)
                report.append("设备 ").append(dev.productName)
                    .append(" vid=").append(dev.vendorId.toString(16))
                    .append(" pid=").append(dev.productId.toString(16))
                    .append(" 权限=").append(if (granted) "已授予" else "未授予").append('\n')
                for (i in 0 until dev.interfaceCount) {
                    val iface = dev.getInterface(i)
                    report.append("  接口#").append(iface.id)
                        .append(" class=").append(iface.interfaceClass)
                        .append(" subclass=").append(iface.interfaceSubclass)
                        .append(" protocol=").append(iface.interfaceProtocol)
                        .append(" alt=").append(iface.alternateSetting)
                        .append(" 端点数=").append(iface.endpointCount).append('\n')
                    for (e in 0 until iface.endpointCount) {
                        val ep: UsbEndpoint = iface.getEndpoint(e)
                        report.append("    端点 0x").append(ep.address.toString(16))
                            .append(if (ep.direction == UsbConstants.USB_DIR_IN) " IN" else " OUT")
                            .append(" type=").append(typeName(ep.type))
                            .append(" maxPacket=").append(ep.maxPacketSize)
                            .append(" interval=").append(ep.interval).append('\n')
                    }
                }
                // ⚠️ 抢接口的可行性测试（阶段 0）**已经完成过**，生产环境绝不能再抢：
                // 这个探针会被反复调用，每次都会把**正在播放的直通会话**的音频接口抢走
                // → 表现为"第一首有声、之后全部无声"（用户实测踩到）。
                // 因此这里只做**纯枚举**（枚举不需要 claim），到此处直接收工。
                finish(report.toString().trimEnd(), diagnostics)
                return

                // go / no-go：抢接口
                if (!granted) {
                    report.append("  claim 测试：跳过（该 USB 设备尚未授权，无法 openDevice）\n")
                    continue
                }
                val conn = um.openDevice(dev)
                if (conn == null) {
                    report.append("  claim 测试：openDevice 失败\n")
                    continue
                }
                try {
                    for (i in 0 until dev.interfaceCount) {
                        val iface = dev.getInterface(i)
                        if (iface.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                        val ok = runCatching { conn.claimInterface(iface, true) }.getOrDefault(false)
                        report.append("  claim(force) 接口#").append(iface.id)
                            .append(" class=").append(iface.interfaceClass)
                            .append(" -> ").append(if (ok) "成功" else "被拒绝").append('\n')
                        // 顺手读 Feature Unit 的静音/音量状态（**不需要出声**）：
                        // 内核驱动被应用接管后，静音位是什么状态没人知道 ——
                        // 如果它被置成 muted，表现就是"数据全对但一点声音都没有"。
                        if (ok && iface.interfaceSubclass == 1) {
                            // ① 直接问设备：时钟源支持哪些采样率（GET_RANGE）。
                            // 系统给的 profile 列表不一定完整（它没列 44100），
                            // 而设备自己的描述符才是权威 —— 这决定 66% 的 44.1k 曲库能不能直通。
                            runCatching {
                                val rb = ByteArray(128)
                                val rr = conn.controlTransfer(
                                    0x81, 0x82, 0x01 shl 8, (3 shl 8) or iface.id, rb, rb.size, 1000
                                )
                                if (rr >= 2) {
                                    fun u32(o: Int) = (rb[o].toInt() and 0xFF) or
                                        ((rb[o + 1].toInt() and 0xFF) shl 8) or
                                        ((rb[o + 2].toInt() and 0xFF) shl 16) or
                                        ((rb[o + 3].toInt() and 0xFF) shl 24)
                                    val n = (rb[0].toInt() and 0xFF) or ((rb[1].toInt() and 0xFF) shl 8)
                                    val sb2 = StringBuilder()
                                    var q = 2
                                    var k = 0
                                    while (k < n && q + 12 <= rr) {
                                        sb2.append('[').append(u32(q)).append("..").append(u32(q + 4))
                                            .append(" step ").append(u32(q + 8)).append("] ")
                                        q += 12
                                        k++
                                    }
                                    report.append("    时钟源#3 支持的采样率（").append(n).append(" 段）: ")
                                        .append(sb2).append('\n')
                                } else {
                                    report.append("    时钟源#3 GET_RANGE 读不到(").append(rr).append(")\n")
                                }
                            }
                            for (unit in intArrayOf(2, 7)) {
                                val m = ByteArray(1)
                                val mr = runCatching {
                                    conn.controlTransfer(0xA1, 0x81, 0x01 shl 8, (unit shl 8) or iface.id, m, 1, 1000)
                                }.getOrDefault(-1)
                                val v = ByteArray(2)
                                val vr = runCatching {
                                    conn.controlTransfer(0xA1, 0x81, 0x02 shl 8, (unit shl 8) or iface.id, v, 2, 1000)
                                }.getOrDefault(-1)
                                val volDb = if (vr == 2) {
                                    // 有符号 Q8.8 dB（1/256 dB）
                                    (((v[1].toInt() and 0xFF) shl 8) or (v[0].toInt() and 0xFF)).toShort() / 256.0
                                } else null
                                report.append("    FeatureUnit#").append(unit)
                                    .append(" 静音读数=").append(if (mr == 1) (m[0].toInt() != 0) else "读不到($mr)")
                                    .append(" 音量=").append(volDb?.let { "%.2f dB".format(it) } ?: "读不到($vr)")
                                    .append('\n')
                            }
                        }
                        if (ok) runCatching { conn.releaseInterface(iface) }
                    }
                } finally {
                    runCatching { conn.close() }
                }
            }
            finish(report.toString().trimEnd(), diagnostics)
        } catch (t: Throwable) {
            finish("探针异常：" + t.message, diagnostics)
        }
    }

    private fun isAudio(dev: UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            if (dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }

    private fun typeName(t: Int) = when (t) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
        else -> t.toString()
    }

    private fun finish(text: String, diagnostics: (String) -> Unit) {
        Log.i(TAG, text)
        diagnostics("USB 探针：\n" + text)
    }
}
