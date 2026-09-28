package com.localmusic.audio.ncm

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Syntax/integrity validation, including all frame CRCs (not a PCM decoder). */
object FlacValidation {
    data class Info(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val samples: Long, val nonFinalShortFrames: Int = 0)

    fun validate(file: File, checkpoint: () -> Unit = {}): Info = file.inputStream().buffered().use { input ->
        fun byte(): Int = input.read().also { if (it < 0) throw EOFException("Truncated FLAC metadata") }
        fun bytes(n: Int): ByteArray {
            val b = ByteArray(n)
            for (i in b.indices) { if (i % 8192 == 0) checkpoint(); b[i] = byte().toByte() }
            return b
        }
        if (!bytes(4).contentEquals("fLaC".toByteArray())) fail("Missing native FLAC marker")
        var first = true
        var info: Info? = null
        var metadataBytes = 0L
        var minBlock = 0
        var maxBlock = 0
        while (true) {
            checkpoint()
            val type = byte()
            val size = (byte() shl 16) or (byte() shl 8) or byte()
            metadataBytes += size + 4
            if (metadataBytes > 64L * 1024 * 1024) fail("FLAC metadata exceeds 64 MiB")
            if (first && (type and 127 != 0 || size != 34)) fail("Missing FLAC STREAMINFO")
            if (!first && type and 127 == 0) fail("Duplicate FLAC STREAMINFO")
            if (type and 127 == 127) fail("Invalid FLAC metadata type")
            if (first) {
                val b = bytes(34)
                fun u(i: Int) = b[i].toInt() and 255
                minBlock = (u(0) shl 8) or u(1); maxBlock = (u(2) shl 8) or u(3)
                val rate = (u(10) shl 12) or (u(11) shl 4) or (u(12) ushr 4)
                val channels = ((u(12) ushr 1) and 7) + 1
                val bits = (((u(12) and 1) shl 4) or (u(13) ushr 4)) + 1
                var total = (u(13) and 15).toLong()
                for (i in 14..17) total = (total shl 8) or u(i).toLong()
                if (rate !in 1..655350 || bits !in 4..32 || minBlock < 16 || maxBlock < minBlock)
                    fail("Invalid FLAC STREAMINFO values")
                info = Info(rate, channels, bits, total)
            } else {
                var left = size
                val scratch = ByteArray(8192)
                while (left > 0) {
                    checkpoint()
                    val n = input.read(scratch, 0, minOf(left, scratch.size))
                    if (n < 0) throw EOFException("Truncated FLAC metadata block")
                    if (n == 0) { byte(); left-- } else left -= n
                }
            }
            first = false
            if (type and 128 != 0) break
        }
        val stream = info!!
        val reader = Bits(input, checkpoint)
        var total = 0L
        var frameIndex = 0L
        var strategy: Int? = null
        var previousBlock = 0
        var fixedBlock = 0
        var shortFrames = 0
        while (true) {
            checkpoint()
            val sync = input.read()
            if (sync < 0) break
            // 规范要求"比 STREAMINFO 里 minBlockSize 更短的帧"只能是最后一帧。实测有的网易云
            // 资源里 STREAMINFO 的 minBlockSize 与实际帧不符（非末尾短帧）——这类文件用 libFLAC/
            // ExoPlayer 都能正常解码。所以这里只计数、不再判死；真正的完整性由下面三件事守住：
            // 逐帧 header CRC8 + 帧 CRC16、采样序号连续、总采样数与 STREAMINFO 完全相等（见文件末尾）。
            if (frameIndex > 0 && previousBlock < minBlock) shortFrames++
            reader.begin(sync)
            val second = reader.read(8).toInt()
            if (sync != 255 || second and 0xfe != 0xf8) fail("Invalid FLAC frame sync")
            val variable = second and 1
            if (strategy != null && strategy != variable) fail("FLAC blocking strategy changes")
            strategy = variable
            val sizes = reader.read(8).toInt()
            val blockCode = sizes ushr 4
            val rateCode = sizes and 15
            val layout = reader.read(8).toInt()
            val assignment = layout ushr 4
            val depthCode = (layout ushr 1) and 7
            if (layout and 1 != 0 || assignment > 10) fail("Invalid FLAC channel assignment")
            val channels = if (assignment < 8) assignment + 1 else 2
            val depth = when (depthCode) { 0 -> stream.bitsPerSample; 1 -> 8; 2 -> 12; 4 -> 16; 5 -> 20; 6 -> 24; 7 -> 32; else -> fail("Reserved FLAC bit depth") }
            if (channels != stream.channels || depth != stream.bitsPerSample) fail("FLAC frame format changes")
            val number = reader.utf8Number()
            if (number != if (variable == 1) total else frameIndex) fail("FLAC frame/sample sequence mismatch")
            if (variable == 0 && number >= (1L shl 31)) fail("FLAC frame index exceeds 31 bits")
            val block = when (blockCode) {
                0 -> fail("Reserved FLAC block size"); 1 -> 192
                in 2..5 -> 576 shl (blockCode - 2)
                6 -> reader.read(8).toInt() + 1
                7 -> reader.read(16).toInt() + 1
                else -> 256 shl (blockCode - 8)
            }
            val rate = when (rateCode) {
                0 -> stream.sampleRate; 1 -> 88200; 2 -> 176400; 3 -> 192000
                4 -> 8000; 5 -> 16000; 6 -> 22050; 7 -> 24000; 8 -> 32000; 9 -> 44100; 10 -> 48000; 11 -> 96000
                12 -> reader.read(8).toInt() * 1000; 13 -> reader.read(16).toInt(); 14 -> reader.read(16).toInt() * 10
                else -> fail("Reserved FLAC sample rate")
            }
            if (rate != stream.sampleRate || block > maxBlock) fail("Invalid FLAC frame rate/block size")
            if (variable == 0) {
                if (frameIndex == 0L) fixedBlock = block
                else if (previousBlock != fixedBlock || block > fixedBlock) fail("Invalid fixed FLAC block sequence")
            }
            val header = reader.header.toByteArray()
            val expectedHeaderCrc = reader.read(8).toInt()
            reader.captureHeader = false
            if (FlacCrc.crc8(header) != expectedHeaderCrc) fail("FLAC header CRC mismatch")
            for (channel in 0 until channels) {
                var bps = depth + if ((assignment == 8 && channel == 1) || (assignment == 9 && channel == 0) || (assignment == 10 && channel == 1)) 1 else 0
                if (reader.read(1) != 0L) fail("Invalid FLAC subframe padding")
                val type = reader.read(6).toInt()
                if (reader.read(1) != 0L) {
                    var wasted = 1
                    while (reader.read(1) == 0L) { wasted++; if (wasted >= bps) fail("Invalid FLAC wasted bits") }
                    bps -= wasted
                    if (bps <= 0) fail("Invalid FLAC wasted bits")
                }
                when {
                    type == 0 -> reader.skip(bps.toLong())
                    type == 1 -> reader.skip(block.toLong() * bps)
                    type in 8..12 || type in 32..63 -> {
                        val order = if (type < 32) type - 8 else type - 31
                        if (order > block) fail("FLAC predictor order exceeds block")
                        reader.skip(order.toLong() * bps)
                        if (type >= 32) {
                            val precision = reader.read(4).toInt() + 1
                            if (precision == 16) fail("Invalid FLAC LPC precision")
                            reader.skip(5L + precision.toLong() * order)
                        }
                        val method = reader.read(2).toInt()
                        if (method > 1) fail("Reserved FLAC residual method")
                        val partitions = 1 shl reader.read(4).toInt()
                        if (block % partitions != 0 || block / partitions < order) fail("Invalid FLAC residual partitions")
                        val parameterBits = if (method == 0) 4 else 5
                        for (partition in 0 until partitions) {
                            val parameter = reader.read(parameterBits).toInt()
                            val count = block / partitions - if (partition == 0) order else 0
                            if (parameter == (1 shl parameterBits) - 1) {
                                reader.skip(count.toLong() * reader.read(5))
                            } else repeat(count) {
                                reader.unary()
                                reader.skip(parameter.toLong())
                            }
                        }
                    }
                    else -> fail("Reserved FLAC subframe type")
                }
            }
            reader.alignZero()
            val actualCrc = reader.crc
            val expectedCrc = reader.read(16).toInt()
            if (actualCrc != expectedCrc) fail("FLAC frame CRC mismatch")
            total += block; frameIndex++; previousBlock = block
            if (total >= (1L shl 36) || (stream.samples != 0L && total > stream.samples)) fail("FLAC sample count overflow")
        }
        if (frameIndex == 0L || (stream.samples != 0L && total != stream.samples)) fail("Truncated/empty FLAC audio")
        stream.copy(samples = total, nonFinalShortFrames = shortFrames)
    }

    private class Bits(val input: InputStream, val checkpoint: () -> Unit) {
        var crc = 0
        var header = java.io.ByteArrayOutputStream(16)
        var captureHeader = true
        private var value = 0
        private var available = 0
        private var frameBytes = 0
        fun begin(first: Int) {
            crc = FlacCrc.update16(0, first); header.reset(); header.write(first)
            captureHeader = true; frameBytes = 1; available = 0
        }
        fun read(count: Int): Long {
            var result = 0L
            var left = count
            while (left > 0) {
                if (available == 0) {
                    if (frameBytes++ > 16 * 1024 * 1024) fail("FLAC frame exceeds 16 MiB")
                    if (frameBytes % 8192 == 0) checkpoint()
                    value = input.read()
                    if (value < 0) throw EOFException("Truncated FLAC frame")
                    crc = FlacCrc.update16(crc, value)
                    if (captureHeader) { if (header.size() > 32) fail("Oversized FLAC header"); header.write(value) }
                    available = 8
                }
                val take = minOf(left, available)
                result = (result shl take) or ((value ushr (available - take)) and ((1 shl take) - 1)).toLong()
                available -= take; left -= take
            }
            return result
        }
        fun skip(count: Long) {
            var left = count
            while (left > 0) { val n = minOf(left, 32).toInt(); read(n); left -= n }
        }
        fun unary() {
            var bits = 0
            while (read(1) == 0L) { if (++bits > 8 * 1024 * 1024) fail("Excessive FLAC Rice quotient") }
        }
        fun alignZero() { if (available > 0 && read(available) != 0L) fail("Nonzero FLAC frame padding") }
        fun utf8Number(): Long {
            val first = read(8).toInt()
            if (first < 128) return first.toLong()
            var count = 0
            var mask = 128
            while (first and mask != 0 && mask != 0) { count++; mask = mask ushr 1 }
            if (count !in 2..7) fail("Invalid FLAC UTF-8 number")
            var result = (first and (127 ushr count)).toLong()
            repeat(count - 1) {
                val b = read(8).toInt(); if (b and 192 != 128) fail("Invalid FLAC UTF-8 continuation")
                result = (result shl 6) or (b and 63).toLong()
            }
            val minimum = when (count) { 2 -> 128L; 3 -> 1L shl 11; 4 -> 1L shl 16; 5 -> 1L shl 21; 6 -> 1L shl 26; else -> 1L shl 31 }
            if (result < minimum || result >= 1L shl 36) fail("Noncanonical FLAC sample number")
            return result
        }
    }
    private fun fail(message: String): Nothing = throw IOException(message)
}
