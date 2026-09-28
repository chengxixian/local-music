package com.localmusic.audio.ncm

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Streaming, lossless PCM16 FLAC writer. Verbatim subframes trade compression for simplicity.
 * Accepts interleaved little-endian PCM16, including arbitrarily split sample bytes.
 * [finish] is mandatory; closing an unfinished writer deliberately does not finalize it.
 */
class VerbatimFlacEncoder(file: File, val sampleRate: Int, val channels: Int) : Closeable {
    private val output: RandomAccessFile
    private val pcm: ByteArray
    private val md5 = MessageDigest.getInstance("MD5")
    private var pending = 0
    private var samples = 0L
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0
    private var minBlock = BLOCK
    private var finished = false
    init {
        require(sampleRate in 1..655350) { "Unsupported FLAC sample rate" }
        require(channels in 1..8) { "FLAC requires 1..8 channels" }
        pcm = ByteArray(BLOCK * channels * 2)
        output = RandomAccessFile(file, "rw")
        try {
            output.setLength(0)
            output.writeBytes("fLaC")
            output.write(byteArrayOf(0x80.toByte(), 0, 0, 34)) // Last metadata block: STREAMINFO
            output.write(ByteArray(34))
        } catch (e: Throwable) { output.close(); throw e }
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        check(!finished) { "FLAC encoder already finished" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var pos = offset
        var remaining = length
        while (remaining > 0) {
            val n = minOf(remaining, pcm.size - pending)
            bytes.copyInto(pcm, pending, pos, pos + n)
            pending += n; pos += n; remaining -= n
            if (pending == pcm.size) emitFrame()
        }
    }

    fun finish() {
        check(!finished)
        if (pending % (channels * 2) != 0) throw IOException("Partial PCM sample at end of stream")
        if (pending > 0) emitFrame()
        if (samples == 0L) throw IOException("Decoded audio contains no PCM samples")
        output.seek(8)
        output.writeShort(maxOf(16, minBlock))
        output.writeShort(BLOCK)
        write24(minFrame); write24(maxFrame)
        val packed = (sampleRate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or
            (15L shl 36) or samples
        output.writeLong(packed)
        output.write(md5.digest())
        output.fd.sync()
        finished = true
    }

    private fun write24(value: Int) { output.write(value ushr 16); output.write(value ushr 8); output.write(value) }

    private fun emitFrame() {
        val count = pending / (channels * 2)
        if (samples + count >= (1L shl 36)) throw IOException("FLAC sample count exceeds 36 bits")
        val frame = ByteArrayOutputStream(pending + 32)
        frame.write(0xff); frame.write(0xf9) // 14-bit sync; variable-block sample numbering
        frame.write(0x70) // 16-bit block size follows; sample rate from STREAMINFO
        frame.write(((channels - 1) shl 4) or 8) // independent channels; 16-bit samples
        writeUtf8Number(frame, samples)
        frame.write((count - 1) ushr 8); frame.write(count - 1)
        frame.write(FlacCrc.crc8(frame.toByteArray()))
        for (channel in 0 until channels) {
            frame.write(2) // zero pad, VERBATIM subframe (000001), no wasted bits
            for (sample in 0 until count) {
                val pos = (sample * channels + channel) * 2
                frame.write(pcm[pos + 1].toInt() and 255)
                frame.write(pcm[pos].toInt() and 255)
            }
        }
        val bytes = frame.toByteArray()
        val crc = FlacCrc.crc16(bytes)
        output.write(bytes); output.writeShort(crc)
        minFrame = minOf(minFrame, bytes.size + 2); maxFrame = maxOf(maxFrame, bytes.size + 2)
        minBlock = minOf(minBlock, count)
        md5.update(pcm, 0, pending) // FLAC MD5 is interleaved signed little-endian PCM.
        samples += count
        pending = 0
    }

    override fun close() = output.close()

    companion object {
        private const val BLOCK = 4096
        private fun writeUtf8Number(out: ByteArrayOutputStream, value: Long) {
            if (value < 128) { out.write(value.toInt()); return }
            val bytes = when {
                value < (1L shl 11) -> 2
                value < (1L shl 16) -> 3
                value < (1L shl 21) -> 4
                value < (1L shl 26) -> 5
                value < (1L shl 31) -> 6
                else -> 7
            }
            out.write(((0xff shl (8 - bytes)) and 255) or (value ushr (6 * (bytes - 1))).toInt())
            for (i in bytes - 2 downTo 0) out.write(0x80 or ((value ushr (6 * i)).toInt() and 63))
        }
    }
}

internal object FlacCrc {
    fun crc8(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor (b.toInt() and 255)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x80 != 0) 7 else 0) and 255 }
        }
        return crc
    }
    fun crc16(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) crc = update16(crc, b.toInt() and 255)
        return crc
    }
    fun update16(previous: Int, byte: Int): Int {
        var crc = previous xor (byte shl 8)
        repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x8005 else 0) and 65535 }
        return crc
    }
}
