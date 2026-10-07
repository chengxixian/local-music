// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.audio.playback

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 阶段 1：UAC2 控制传输 —— 读类描述符、把 DAC 的时钟切到目标采样率、并把结果读回来验证。
 *
 * ## UAC2 的寻址方式（容易搞错，写清楚）
 * Clock Source 的请求：
 *   bmRequestType = 0x21（host→device, class, interface）
 *   bRequest      = 0x01 SET_CUR / 0x81 GET_CUR
 *   wValue        = (CS << 8) | 0     CS_SAM_FREQ_CONTROL = 0x01
 *   wIndex        = (bClockID << 8) | 接口号(0)
 *   数据          = 4 字节小端采样率
 * AS 接口（Streaming）的采样率控制则把接口号放在低字节。
 *
 * 所有 controlTransfer 的返回值都会打日志：返回字节数 = 成功，-1 = 失败。
 * 最后用 GET_CUR 把采样率**读回来**，那才是"真的切过去了"的证据。
 */
object UsbUac2Control {

    private const val TAG = "LMUsb"

    private const val REQ_SET_CUR = 0x01
    private const val REQ_GET_CUR = 0x81
    private const val DESCRIPTOR_CS_INTERFACE = 0x24

    private const val UAC2_HEADER = 0x01
    private const val UAC2_CLOCK_SOURCE = 0x0A
    private const val UAC2_CLOCK_SELECTOR = 0x0B
    private const val UAC2_INPUT_TERMINAL = 0x02
    private const val UAC2_OUTPUT_TERMINAL = 0x03

    private const val CS_SAM_FREQ_CONTROL = 0x01

    /** 执行完整流程并把报告打到 logcat（tag LMUsb）。targetRate 例如 384000。 */
    fun setSampleRate(context: Context, targetRate: Int = 384_000) {
        val log = StringBuilder()
        fun emit(s: String) { log.append(s).append('\n'); Log.i(TAG, s) }

        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emit("UAC2：无 UsbManager")
        val dev = um.deviceList.values.firstOrNull { isAudio(it) }
            ?: return emit("UAC2：未找到 USB 音频设备")
        if (!um.hasPermission(dev)) return emit("UAC2：无 USB 权限，先授权")

        val conn = um.openDevice(dev) ?: return emit("UAC2：openDevice 失败")
        try {
            val ac = firstInterface(dev, UsbConstants.USB_CLASS_AUDIO, subclass = 1)
            // 注意：必须取**播放**流接口（带等时 OUT 端点那个）。
            // 直接取第一个 subclass=2 会拿到录音流 —— 把采样率设到录音流上不会影响播放，
            // 这也是之前"SET_CUR 成功但听不出变化"的隐患所在。
            val as0 = (0 until dev.interfaceCount).map { dev.getInterface(it) }
                .firstOrNull { i ->
                    i.interfaceClass == UsbConstants.USB_CLASS_AUDIO && i.interfaceSubclass == 2 &&
                        (0 until i.endpointCount).any {
                            val e = i.getEndpoint(it)
                            e.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                e.direction == UsbConstants.USB_DIR_OUT
                        }
                } ?: firstInterface(dev, UsbConstants.USB_CLASS_AUDIO, subclass = 2)
            if (ac == null) return emit("UAC2：找不到 AudioControl 接口")

            if (!conn.claimInterface(ac, true)) return emit("UAC2：claim AC 失败")
            emit("UAC2：AC 接口 #${ac.id} 已抢占")

            // ① 读**整份配置描述符**（0x0200）：比只读 AC 类描述符可靠得多，
            //    里面已经包含全部 class-specific 描述符（时钟源/选择器都在）。
            val buf = ByteArray(4096)
            val n = conn.controlTransfer(0x80, 0x06, 0x0200, 0, buf, buf.size, 2000)
            emit("UAC2：读配置描述符 -> $n 字节")
            if (n <= 0) {
                emit("UAC2：配置描述符也读不到，阶段 1 到此为止")
                return
            }
            val desc = buf.copyOf(n)
            emit("UAC2：前 64 字节(hex) " + desc.take(64).joinToString(" ") { "%02x".format(it) })

            val clocks = ArrayList<Int>()      // Clock Source 的 bClockID（可编程采样率的）
            val selectors = ArrayList<Int>()   // Clock Selector 的 bClockID
            var p = 9
            var curIface = -1
            var curAlt = -1
            while (p + 2 <= desc.size) {
                val len = desc[p].toInt() and 0xFF
                val type = desc[p + 1].toInt() and 0xFF
                if (len < 2 || p + len > desc.size) break
                if (type == 0x04 && len >= 9) {
                    // 标准接口描述符：记下"当前哪个接口、哪个 alt setting"，
                    // 后面的 AS_GENERAL / FORMAT_TYPE 都属于它。
                    curIface = desc[p + 2].toInt() and 0xFF
                    curAlt = desc[p + 3].toInt() and 0xFF
                    emit(
                        "UAC2：接口 #$curIface alt=$curAlt class=${desc[p + 5].toInt() and 0xFF}" +
                            " subclass=${desc[p + 6].toInt() and 0xFF} 端点数=${desc[p + 4].toInt() and 0xFF}"
                    )
                } else if (type == DESCRIPTOR_CS_INTERFACE && len >= 4) {
                    val sub = desc[p + 2].toInt() and 0xFF
                    val id = desc[p + 3].toInt() and 0xFF
                    when (sub) {
                        UAC2_HEADER -> emit("UAC2：  header bcdADC=" + "%02x%02x".format(desc[p + 4], desc[p + 3]))
                        UAC2_CLOCK_SOURCE -> {
                            // bClockID, bmAttributes, bmControls, bAssocTerminal, iClockSource
                            val controls = if (len > 6) desc[p + 5].toInt() and 0xFF else 0
                            val programmable = (controls and 0x01) != 0
                            emit("UAC2：  clock source id=$id controls=0x%02x 可编程=%s".format(controls, programmable))
                            if (programmable) clocks.add(id)
                        }
                        UAC2_CLOCK_SELECTOR -> {
                            emit("UAC2：  clock selector id=$id")
                            selectors.add(id)
                        }
                        0x01 -> emit(
                            "UAC2：  [iface#$curIface alt=$curAlt] AS_GENERAL id=$id" +
                                " terminalLink=${desc[p + 4].toInt() and 0xFF} formatType=${desc[p + 5].toInt() and 0xFF}"
                        )
                        0x02 -> {
                            // FORMAT_TYPE_I：bFormatType, bSubslotSize(每采样点字节数),
                            // bBitResolution(位深), bSamFreqType, 然后采样率表(3 字节小端)
                            val ft = desc[p + 3].toInt() and 0xFF
                            val subslot = desc[p + 4].toInt() and 0xFF
                            val bits = desc[p + 5].toInt() and 0xFF
                            val nRates = desc[p + 6].toInt() and 0xFF
                            val rates = ArrayList<Int>()
                            var q = p + 7
                            var k = 0
                            while (k < nRates && q + 3 <= p + len) {
                                rates.add(
                                    (desc[q].toInt() and 0xFF) or
                                        ((desc[q + 1].toInt() and 0xFF) shl 8) or
                                        ((desc[q + 2].toInt() and 0xFF) shl 16)
                                )
                                q += 3
                                k++
                            }
                            emit(
                                "UAC2：  [iface#$curIface alt=$curAlt] FORMAT ft=$ft" +
                                    " 每采样点=${subslot}字节 位深=${bits}bit 采样率=$rates"
                            )
                        }
                        UAC2_INPUT_TERMINAL, UAC2_OUTPUT_TERMINAL -> emit("UAC2：  terminal id=$id sub=0x%02x".format(sub))
                        else -> emit("UAC2：  subtype=0x%02x id=$id len=$len".format(sub))
                    }
                }
                p += len
            }

            // ② 若流接口有采样率控制，先设 AS 接口上的
            var ok = 0
            val rateBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(targetRate).array()
            if (as0 != null) {
                conn.claimInterface(as0, true)
                val r = conn.controlTransfer(
                    0x21, REQ_SET_CUR,
                    CS_SAM_FREQ_CONTROL shl 8,
                    as0.id,
                    rateBytes, rateBytes.size, 2000,
                )
                emit("UAC2：AS #${as0.id} SET_CUR 采样率=$targetRate -> $r")
                if (r > 0) ok++
            }
            // ③ Clock Source 上再设一次（UAC2 里真正生效的通常是这里）
            for (id in clocks) {
                val r = conn.controlTransfer(
                    0x21, REQ_SET_CUR,
                    CS_SAM_FREQ_CONTROL shl 8,
                    (id shl 8) or ac.id,
                    rateBytes, rateBytes.size, 2000,
                )
                emit("UAC2：ClockSource id=$id SET_CUR 采样率=$targetRate -> $r")
                if (r > 0) ok++

                // ④ 读回来验证（这才是证据）
                val back = ByteArray(4)
                val g = conn.controlTransfer(
                    0x81, REQ_GET_CUR,
                    CS_SAM_FREQ_CONTROL shl 8,
                    (id shl 8) or ac.id,
                    back, back.size, 2000,
                )
                val got = if (g > 0) ByteBuffer.wrap(back).order(ByteOrder.LITTLE_ENDIAN).int else -1
                emit("UAC2：ClockSource id=$id GET_CUR -> $g 字节, 采样率=$got")
            }
            emit("UAC2：SET_CUR 成功次数=$ok，选择器=${selectors.size}，可编程时钟=${clocks.size}")
            emit("UAC2：接口保持抢占状态，等下一次拔插恢复系统音频")
        } catch (t: Throwable) {
            emit("UAC2：异常 " + t.message)
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun isAudio(d: UsbDevice) = (0 until d.interfaceCount).any {
        d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
    }

    private fun firstInterface(d: UsbDevice, cls: Int, subclass: Int) =
        (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull { it.interfaceClass == cls && it.interfaceSubclass == subclass }
}
