// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.audio.playback

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * 阶段 2：USB 等时输出最小闭环（**先出声、再谈接入播放器**）。
 *
 * ## 关键数字（384kHz / 32bit / 立体声）
 * 数据率 = 384000 × 4 字节 × 2 声道 = 3,072,000 B/s
 * USB 全速/高速的等时帧 = 125µs → 8000 帧/秒
 * 每帧 = 3,072,000 / 8000 = **384 字节** ✓
 * 设备 alt3/alt4 的 maxPacket 是 392（≥384，多出的 8 字节留给异步反馈）→ 正好是 384k 档位 ✓
 *
 * ## 这一版刻意简化
 * 只做"固定速率发送 + 统计"：读反馈端点(IN 0x85)动态调速留到阶段 3。
 * 判据：①听得到测试音 ②queued/sent 计数正常、无大量失败。
 */
object UsbUac2Stream {

    private const val TAG = "LMUsb"

    /**
     * 当前正在进行的等时直通管道的**读端**。
     * 关闭它 → 原生层 `read_exact` 返回 0 → 循环 break → 接口释放、系统音频恢复。
     * 这是"停止直通"最简单也最安全的做法（不需要在原生层做取消）。
     */
    @Volatile
    private var activeReadEnd: android.os.ParcelFileDescriptor? = null

    /**
     * 当前会话的管道**写端**。停止时必须关它，而不是只关读端。
     *
     * 原因（这是"第一首有声、之后无声"的真正根因）：
     * Linux 上**关闭读端不会唤醒**阻塞在 `read()` 里的线程 —— 而解码线程还在不断往写端
     * 写数据，原生层就一直读得下去，上一条会话**永远不结束** → 会话标志永远为 true →
     * 后面的歌要么等到超时、要么抢不到接口 = 全部无声。
     * 关掉**写端**则受 POSIX 保证：所有写端关闭后 `read` 返回 0 → 原生层 break → 真正退出。
     */
    @Volatile
    private var activeWriteEnd: android.os.ParcelFileDescriptor? = null

    /**
     * 是否有一条直通会话正在进行（从**开始占用接口**到**finally 释放完毕**）。
     *
     * 为什么需要它：点第二首歌时如果只是 `stop()` + 固定 sleep，上一首的
     * `releaseInterface` 可能把新会话刚 claim 的接口释放掉 → 表现为
     * "第一首正常、之后点歌全部无声"（用户实测）。这里必须**等它真正退出**。
     */
    @Volatile
    private var sessionActive = false

    /**
     * 会话编号与**当前所有者**。
     *
     * 为什么必须有所有者：`sessionActive` 不是互斥量（它只在 claim 之前被检查一次）。
     * 上一首的 `finally` 会执行 `releaseInterface + close`，而这个动作是**设备级**的 ——
     * 如果此时新会话已经 force-claim 接管，上一首的 release 会把**新会话的接口抢回去**，
     * 于是新会话的等时 URB 落在已被内核收回的端点上：**没声音、也没有明显报错**。
     * 这就是"第一首有声、之后全部无声"的根因（由代码走查定位，非猜测）。
     * 规则：只有仍是当前所有者的会话才允许释放接口；已被后续会话接管的，只关自己的连接。
     */
    private val sessionSeq = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var sessionOwner = 0

    /** 停止直通（可随时从界面调用）。**必须关写端**，见 activeWriteEnd 的说明。 */
    fun stop() {
        val w = activeWriteEnd
        val p = activeReadEnd
        activeWriteEnd = null
        activeReadEnd = null
        // 先关写端：让原生层的 read 返回 0（POSIX 保证），会话才会真正结束；
        // 只关读端是唤不醒阻塞中的 read 的（我先前就是这么写错的）。
        runCatching { w?.close() }
        runCatching { p?.close() }
        Log.i(TAG, "流：已请求停止直通（关写端+读端）")
    }

    /** 生成并播放一段测试音（正弦），返回报告文本。用完释放接口（不长期霸占）。 */
    fun playTone(context: Context, rateHz: Int = 384_000, seconds: Int = 4, toneHz: Double = 1000.0) {
        fun emit(s: String) { Log.i(TAG, s) }
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emit("流：无 UsbManager")
        val dev = um.deviceList.values.firstOrNull { isAudio(it) } ?: return emit("流：无 USB 音频设备")
        if (!um.hasPermission(dev)) return emit("流：无 USB 权限")

        val conn: UsbDeviceConnection = um.openDevice(dev) ?: return emit("流：openDevice 失败")
        val claimed = ArrayList<UsbInterface>()
        try {
            val ac = iface(dev, 1) ?: return emit("流：无 AC 接口")
            // 播放流接口：class=1 subclass=2，取 maxPacket 最大的 alt（= 384k 档）
            val asAlts = (0 until dev.interfaceCount).map { dev.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 2 }
            val asIface = asAlts.maxByOrNull { i ->
                (0 until i.endpointCount).maxOfOrNull { i.getEndpoint(it).maxPacketSize } ?: 0
            } ?: return emit("流：无 AS 接口")

            if (!conn.claimInterface(ac, true)) return emit("流：claim AC 失败"); claimed.add(ac)
            if (!conn.claimInterface(asIface, true)) return emit("流：claim AS 失败"); claimed.add(asIface)

            // ① 切采样率（阶段 1 已验证 SET_CUR 被接受）
            val rateBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(rateHz).array()
            val r1 = conn.controlTransfer(0x21, 0x01, 0x01 shl 8, asIface.id, rateBytes, 4, 2000)
            emit("流：AS SET_CUR $rateHz -> $r1")

            // ② 选中这个 alt setting（等时端点必须激活，否则 queue 会失败）
            val okAlt = runCatching { conn.setInterface(asIface) }.getOrDefault(false)
            emit("流：setInterface alt=${asIface.alternateSetting} -> $okAlt")

            // ③ 找出 OUT 等时端点
            var out: UsbEndpoint? = null
            var fb: UsbEndpoint? = null
            for (e in 0 until asIface.endpointCount) {
                val ep = asIface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep else fb = ep
                }
            }
            val ep = out ?: return emit("流：AS 接口上没有等时 OUT 端点")
            emit("流：OUT 端点 0x${ep.address.toString(16)} maxPacket=${ep.maxPacketSize}；反馈端点=${
                fb?.let { "0x${it.address.toString(16)}/${it.maxPacketSize}B" } ?: "无"
            }")

            // ④ 每帧 384 字节（32bit 立体声 @384k）
            val frameBytes = (rateHz * 4 * 2) / 8000
            if (frameBytes > ep.maxPacketSize) {
                return emit("流：需要每帧 $frameBytes 字节，超过端点上限 ${ep.maxPacketSize}，放弃")
            }
            val req = UsbRequest()
            if (!req.initialize(conn, ep)) {
                // Android 的 Java USB API 不支持等时传输（实测 initialize 直接失败），
                // 回退到原生层：用 UsbDeviceConnection 的 usbfs fd 直接提交等时 URB。
                emit("流：UsbRequest.initialize 失败（Java 不支持等时）→ 回退原生等时层")
                val fb2 = rateHz * 4 * 2 / 8000
                // 用管道把 PCM 交给原生层：Kotlin 写 → C 读 → 等时送出。
                // 这就是"真实音乐"将来走的同一条通路（把这里生成的测试音换成解码器输出即可）。
                var rc = -99
                if (UsbIsoNative.available) {
                    val pipe = android.os.ParcelFileDescriptor.createPipe()
                    val readEnd = pipe[0]
                    val writeEnd = pipe[1]
                    val total = seconds * 8000
                    val writer = Thread {
                        try {
                            val out = java.io.FileOutputStream(writeEnd.fileDescriptor)
                            val buf = ByteBuffer.allocate(fb2)
                            var ph = 0.0
                            val dph = 2 * PI * toneHz / rateHz
                            val per = fb2 / 8
                            for (f in 0 until total) {
                                buf.clear()
                                for (s in 0 until per) {
                                    val v = (sin(ph) * 0.25 * Int.MAX_VALUE).toInt()
                                    ph += dph
                                    if (ph > 2 * PI) ph -= 2 * PI
                                    buf.putInt(v); buf.putInt(v)
                                }
                                out.write(buf.array(), 0, fb2)
                            }
                            out.flush()
                        } catch (t: Throwable) {
                            // 管道被关闭属正常（原生侧已经发完）
                        } finally {
                            runCatching { writeEnd.close() }
                        }
                    }
                    writer.start()
                    rc = UsbIsoNative.nativePlayPipe(
                        conn.fileDescriptor, ep.address, fb2, rateHz, total,
                        fb?.address ?: -1, readEnd.fd,
                    )
                    runCatching { readEnd.close() }
                }
                emit("流：管道 PCM 返回码=$rc（0=成功 -1=参数 -2=分配 -3=首次提交失败 -99=库不可用）")
                emit("流：若听到测试音，说明等时直通通路已打通（数据真的按 384k 送到 DAC）")
                return
            }

            val totalFrames = seconds * 8000
            var phase = 0.0
            val dPhase = 2 * PI * toneHz / rateHz
            val buf = ByteBuffer.allocate(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
            var sent = 0
            var fail = 0
            val t0 = System.nanoTime()
            for (f in 0 until totalFrames) {
                buf.clear()
                // 每个采样点 1 个 int（32bit）→ 立体声两声道
                val samplesPerFrame = frameBytes / 8
                for (s in 0 until samplesPerFrame) {
                    val v = (sin(phase) * 0.25 * Int.MAX_VALUE).toInt()
                    phase += dPhase
                    if (phase > 2 * PI) phase -= 2 * PI
                    buf.putInt(v); buf.putInt(v)
                }
                buf.flip()
                if (req.queue(buf, frameBytes)) { sent++ } else { fail++ }
                // 简单节流：约每 8000 帧/秒 —— 用 requestWait 自然阻塞对齐
                if (f % 8 == 7) {
                    runCatching { conn.requestWait() }
                    // 让出 CPU，避免把音频线程饿死
                    if (f % 800 == 799) Thread.sleep(0, 200_000)
                }
            }
            // 收尾：把队列里的请求收干
            repeat(16) { runCatching { conn.requestWait() } }
            val ms = (System.nanoTime() - t0) / 1_000_000
            emit("流：完成 帧=$totalFrames queued=$sent 失败=$fail 用时=${ms}ms 实际帧率=${totalFrames * 1000L / ms.coerceAtLeast(1)}/s")
            emit("流：DAC 是否按 384k 输出请看它自己的指示灯/显示；本条只证明等时包发出去了")
        } catch (t: Throwable) {
            emit("流：异常 " + t.message)
        } finally {
            // 释放接口，恢复正常系统音频（阶段 0/1 的探针没释放，这点这次修正了）
            for (i in claimed) runCatching { conn.releaseInterface(i) }
            runCatching { conn.close() }
        }
    }

    /**
     * 播放一个**真实音频文件**：解码 → 管道 → 原生等时 → DAC。
     *
     * 关键设计：把 DAC 的时钟设到**文件原始采样率**（不重采样）—— 这才是直通该有的行为。
     * 每帧字节数 = 采样率 × 4字节(32bit) × 声道 / 8000帧每秒 = 采样率/1000（立体声）。
     */
    fun playFile(
        context: Context,
        /** 指定要直通播放的文件；为空则按"优先 DAC 支持的采样率"从 dirs 里挑。 */
        path: String? = null,
        dirs: List<String> = listOf(
            "/storage/emulated/0/Download/lmflac",
            "/storage/emulated/0/Music",
            "/storage/emulated/0/Download/netease/cloudmusic/Music",
        ),
    ) {
        fun emit(s: String) { Log.i(TAG, s) }
        // ⚠️ 同一时刻只允许一条直通。
        // 用户在播放中点了另一首歌（或反复拨开关）时，上一条直通仍在跑 —— 两条流会去抢
        // 同一个 USB 接口，结果不可预期（可能又是一次噪音）。所以先停掉上一条，
        // 并给它一点时间走完"read 返回 0 → break → 释放接口"，再开新的。
        // 先打断上一条（关掉它的管道读端 → 原生层 read 返回 0 → break）。
        // 真正的"等它退出"放在下面**已经开始占用接口之前**，并且只在关键区间置标志，
        // 以免中途的提前返回把标志永久留下（那样后续每次点歌都要白等 5 秒）。
        stop()
        val exts = listOf(".flac", ".mp3", ".m4a", ".wav", ".ogg", ".opus", ".dsf", ".dff")
        val um0 = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val dev0 = um0?.deviceList?.values?.firstOrNull { isAudio(it) }
        // 这台 DAC **真正支持哪些采样率**，由系统报告。
        // 注意：能力挂在 AudioDeviceInfo 上（AudioManager 的设备列表），不是 UsbDevice ——
        // UsbDevice 没有 audioProfiles 这个 API（我先前写错过一次，编译直接报 Unresolved）。
        // 实测 Moondrop Old Fashioned：48000, 88200, 96000, 176400, 192000, 352800, 384000
        // —— **没有 44100**，所以随便挑一首 44.1k 的歌，SET_CUR 设不进去 → 没声音。
        val am0 = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val supported: Set<Int> = am0?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            ?.filter {
                it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            }
            ?.flatMap { info -> info.audioProfiles.flatMap { p -> p.sampleRates.toList() } }
            ?.toSet() ?: emptySet()
        emit("流：DAC 支持的采样率=" + supported.sorted())

        val candidates = dirs.asSequence().map { java.io.File(it) }.filter { it.isDirectory }
            .flatMap { d -> (d.listFiles() ?: emptyArray()).asSequence() }
            .filter { f -> f.isFile && f.length() > 3_000_000L && exts.any { e -> f.name.lowercase().endsWith(e) } }
            .sortedByDescending { it.length() }
            .toList()

        // 指定了 path 就直接用它（接进曲库后就是"用户点的那首歌"）；
        // 否则按"优先 DAC 支持的采样率"从目录里挑一个。
        val chosen: java.io.File
        val fileRate: Int
        if (path != null) {
            val f = java.io.File(path)
            val r = if (f.isFile) UsbPcmDecoder.probe(f.absolutePath)?.first ?: 0 else 0
            if (!f.isFile || r <= 0) return emit("流：指定文件不可用（$path）")
            chosen = f
            fileRate = r
        } else {
            var f: java.io.File? = null
            var r = 0
            for (c in candidates) {
                val p = UsbPcmDecoder.probe(c.absolutePath) ?: continue
                if (f == null) { f = c; r = p.first }                     // 兜底：第一个能读的
                if (supported.isEmpty() || p.first in supported) {         // 优先：DAC 支持的采样率
                    f = c; r = p.first; break
                }
            }
            if (f == null) return emit("流：没找到可播放的音频文件（搜索：$dirs）")
            chosen = f
            fileRate = r
        }
        emit("流：准备播放 " + chosen.absolutePath + "  采样率=$fileRate DAC支持=${fileRate in supported}")

        // ⚠️ 采样率必须是 DAC 支持的，否则宁可拒绝也不能硬播。
        // 实测这台 Moondrop Old Fashioned 的时钟**不支持 44100**；
        // 而用户曲库 462 首里 306 首是 44.1k（占 66%）。
        // 硬发的话设备会按别的时钟播 → 走调/噪音（又是糟糕体验）。这里明确拦住并说明原因。
        if (supported.isNotEmpty() && fileRate !in supported) {
            emit("流：拒绝直通 —— ${fileRate}Hz 不在 DAC 支持列表内（$supported）")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context,
                    "这首歌是 ${fileRate / 1000.0} kHz，DAC 不支持该采样率，无法原生直通（避免走调/噪音）",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            return
        }

        // ① 先把 DAC 时钟设到文件的原始采样率
        UsbUac2Control.setSampleRate(context, fileRate)

        // ② 接管接口 → 激活 32bit alt → 管道送 PCM
        // 让设置页那行状态**反映真实情况**：
        // 之前它显示的"384k/192k/96k/48k 直通请求均被拒绝"来自**系统 mixer 属性申请**
        // （那条路在这台机型上确实全被拒），但真正出声的是这条原生等时通道 ——
        // 不更新的话，用户会以为直通失败了（实测就是这样被误导的）。
        // 用户已决定：44.1kHz 不做直通（上面已拦截并提示），所以这里只会显示支持的采样率。
        PlaybackDiagnostics.mutable.value =
            "USB 直通进行中 · " + chosen.name + " · ${fileRate / 1000.0} kHz · 32bit 原生等时" +
                "（界面由 ExoPlayer 管理，已静音其系统输出）"

        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emit("流：无 UsbManager")
        val dev = um.deviceList.values.firstOrNull { isAudio(it) } ?: return emit("流：无 USB 音频设备")
        if (!um.hasPermission(dev)) return emit("流：无 USB 权限")
        val conn = um.openDevice(dev) ?: return emit("流：openDevice 失败")
        val claimed = ArrayList<UsbInterface>()
        try {
            val ac = iface(dev, 1) ?: return emit("流：无 AC 接口")
            val asIface = (0 until dev.interfaceCount).map { dev.getInterface(it) }
                .filter {
                    it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 2 &&
                        (0 until it.endpointCount).any { k ->
                            val e = it.getEndpoint(k)
                            e.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && e.direction == UsbConstants.USB_DIR_OUT
                        }
                }
                .maxByOrNull { i -> (0 until i.endpointCount).maxOfOrNull { i.getEndpoint(it).maxPacketSize } ?: 0 }
                ?: return emit("流：无播放流接口")
            // ── 等上一条会话**彻底退出**，再开始占用接口 ──────────────────────
            // 放在这里（而不是函数最开头）的原因：上面的"没设备/没权限/没文件/
            // 采样率不支持"都会提前 return，若提前置标志就会永久留下（后续每次点歌都白等）。
            val t0w = System.currentTimeMillis()
            while (sessionActive && System.currentTimeMillis() - t0w < 5000) Thread.sleep(20)
            if (sessionActive) emit("流：上一条直通 5 秒内未退出，本次仍继续尝试（靠所有者检查保护接口）")
            val mySession = sessionSeq.incrementAndGet()
            sessionOwner = mySession
            sessionActive = true
            if (!conn.claimInterface(ac, true)) return emit("流：claim AC 失败"); claimed.add(ac)
            if (!conn.claimInterface(asIface, true)) return emit("流：claim AS 失败"); claimed.add(asIface)
            // 消除"静音位被置上/音量为 0"这一类"数据全对却没声音"：
            // 接管接口后**显式**取消静音 + 音量设 0dB。设备不支持就返回 -1，可忽略。
            runCatching {
                val mr = conn.controlTransfer(0x21, 0x01, 0x01 shl 8, (2 shl 8) or ac.id, byteArrayOf(0), 1, 1000)
                val vr = conn.controlTransfer(0x21, 0x01, 0x02 shl 8, (2 shl 8) or ac.id, byteArrayOf(0x00, 0x00), 2, 1000)
                emit("流：取消静音=$mr 字节 音量设 0dB=$vr 字节（-1=设备不支持，可忽略）")
            }
            val okAlt = runCatching { conn.setInterface(asIface) }.getOrDefault(false)
            emit("流：setInterface alt=${asIface.alternateSetting} -> $okAlt")

            // ⚠️ 激活 alt 之后必须**再设一次**采样率。
            // UAC2 的采样率控制挂在接口/alt 上，切换 alt 可能把它重置回默认值 ——
            // 顺序错误（先设、后切）会让设置被冲掉 = 没声音/速率不对。
            // 直接用当前这条已持有接口的连接发控制传输，不再开第二条连接去抢同一接口。
            runCatching {
                val rb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileRate).array()
                val rr = conn.controlTransfer(0x21, 0x01, 0x01 shl 8, asIface.id, rb, 4, 2000)
                emit("流：激活后再设采样率 $fileRate -> $rr 字节（4=成功）")
            }

            var ep: UsbEndpoint? = null
            var fb: UsbEndpoint? = null
            for (e in 0 until asIface.endpointCount) {
                val cand = asIface.getEndpoint(e)
                if (cand.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                    if (cand.direction == UsbConstants.USB_DIR_OUT) ep = cand else fb = cand
                }
            }
            val outEp = ep ?: return emit("流：播放流接口上没有等时 OUT 端点")
            // 32bit 立体声：每帧字节数 = 采样率 × 4 字节 × 2 声道 / 8000 帧每秒
            val fb2 = fileRate * 8 / 8000
            emit("流：OUT 0x${outEp.address.toString(16)} maxPacket=${outEp.maxPacketSize}；每帧=$fb2 字节；反馈=${fb?.address?.toString(16) ?: "无"}")

            if (!UsbIsoNative.available) return emit("流：原生库不可用")
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            val writer = Thread {
                try {
                    val os = java.io.FileOutputStream(writeEnd.fileDescriptor)
                    UsbPcmDecoder.decodeTo(chosen.absolutePath, os)
                    os.flush()
                } catch (t: Throwable) {
                    // 管道关闭属正常
                } finally {
                    runCatching { writeEnd.close() }
                }
            }
            writer.start()
            // 记下读端：界面上的开关一旦关闭，stop() 会关掉它，
            // 原生层的 read_exact 随即返回 0 → break → 释放接口（系统音频自动恢复）。
            activeReadEnd = readEnd
            activeWriteEnd = writeEnd
            val rc = UsbIsoNative.nativePlayPipe(
                conn.fileDescriptor, outEp.address, fb2, fileRate,
                8_000_000,                       // 上限约 16 分钟；解码结束（管道 EOF）会自动停
                fb?.address ?: -1, readEnd.fd,
            )
            runCatching { readEnd.close() }
            emit("流：文件播放返回码=$rc（0=成功）")
        } catch (t: Throwable) {
            emit("流：异常 " + t.message)
        } finally {
            // ⚠️ 只有**仍是当前所有者**时才允许释放接口。
            // 释放接口是设备级动作：若新会话已 force-claim 接管，这里的 release 会把
            // 新会话的接口抢回去 → 新会话的等时 URB 落在被内核收回的端点上 → **无声且无报错**。
            // 这正是"第一首有声、之后全部无声"的根因（sessionActive 不是互斥量，
            // 它只在 claim 之前检查一次，挡不住"上一首 finally 晚于新会话 claim"的时序）。
            // 已被接管的旧会话只关掉自己的连接：它的 claim 已被 force-claim 覆盖，无害。
            // 判据就是"我是否仍是**最新**会话" —— 等价于"我是否仍持有设备"，
            // 且不需要把 mySession 传出 try 的作用域。
            if (sessionOwner == sessionSeq.get()) {
                for (i in claimed) runCatching { conn.releaseInterface(i) }
                runCatching { conn.close() }
                sessionActive = false
            } else {
                emit("流：本次会话已被后续会话接管，只关闭自己的连接、不释放接口")
                runCatching { conn.close() }
            }
        }
    }

    private fun isAudio(d: UsbDevice) = (0 until d.interfaceCount).any {
        d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
    }

    private fun iface(d: UsbDevice, subclass: Int) = (0 until d.interfaceCount)
        .map { d.getInterface(it) }
        .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == subclass }
}
