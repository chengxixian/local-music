/* Kotlin port of taurusxin/ncmdump src/ncmcrypt.cpp (MIT).
 * This is not the upstream executable. Full upstream notice: LICENSE.ncmdump.txt. */
package com.localmusic.audio.ncm

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class NcmFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** JVM-only NCM container decoder. Does not close streams or remove the source. */
object NcmDecoder {
    data class Result(val payloadBytes: Long, val metadataJson: String?)
    private const val MAX_KEY = 4096
    private const val MAX_METADATA = 4 * 1024 * 1024
    private const val MAX_COVER = 32 * 1024 * 1024
    private val coreKey = "hzHRAmso5kInbaxW".toByteArray(Charsets.US_ASCII)
    private val metadataKey = byteArrayOf(0x23,0x31,0x34,0x6c,0x6a,0x6b,0x5f,0x21,0x5c,0x5d,0x26,0x30,0x55,0x3c,0x27,0x28)

    /** [checkpoint] may throw (including CancellationException); it is never swallowed. */
    fun decode(source: InputStream, destination: OutputStream, checkpoint: () -> Unit = {}): Result {
        fun exact(n: Int): ByteArray {
            val bytes = ByteArray(n)
            var offset = 0
            while (offset < n) {
                checkpoint()
                val count = source.read(bytes, offset, n - offset)
                if (count < 0) throw EOFException("Truncated NCM header")
                if (count == 0) {
                    val b = source.read()
                    if (b < 0) throw EOFException("Truncated NCM header")
                    bytes[offset++] = b.toByte()
                } else offset += count
            }
            return bytes
        }
        fun length(limit: Int, label: String): Int {
            val b = exact(4)
            val n = (b[0].toLong() and 255) or ((b[1].toLong() and 255) shl 8) or
                ((b[2].toLong() and 255) shl 16) or ((b[3].toLong() and 255) shl 24)
            if (n > limit) throw NcmFormatException("NCM $label length exceeds $limit bytes")
            return n.toInt()
        }
        if (!exact(8).contentEquals("CTENFDAM".toByteArray(Charsets.US_ASCII)))
            throw NcmFormatException("Bad NCM magic (expected CTENFDAM)")
        exact(2)
        val keyLength = length(MAX_KEY, "key")
        if (keyLength == 0 || keyLength % 16 != 0) throw NcmFormatException("Invalid NCM key length")
        val encryptedKey = exact(keyLength).also { xor(it, 0x64) }
        val key = decrypt(encryptedKey, coreKey)
        val prefix = "neteasecloudmusic".toByteArray(Charsets.US_ASCII)
        // Upstream strips the complete 17-byte neteasecloudmusic prefix.
        if (key.size <= 17 || !key.copyOfRange(0, 17).contentEquals(prefix))
            throw NcmFormatException("Invalid NCM key prefix")
        val streamKey = key.copyOfRange(17, key.size)
        val box = IntArray(256) { it }
        var last = 0
        var keyOffset = 0
        for (i in 0..255) {
            val swap = box[i]
            val c = (swap + last + (streamKey[keyOffset].toInt() and 255)) and 255
            keyOffset = (keyOffset + 1) % streamKey.size
            box[i] = box[c]
            box[c] = swap
            last = c
        }
        val metadataLength = length(MAX_METADATA, "metadata")
        val metadata = if (metadataLength == 0) null else {
            val data = exact(metadataLength).also { xor(it, 0x63) }
            val marker = "163 key(Don't modify):".toByteArray(Charsets.US_ASCII)
            // The wire prefix includes one trailing space (22 bytes in upstream).
            if (data.size <= 22 || !data.copyOfRange(0, marker.size).contentEquals(marker))
                throw NcmFormatException("Invalid NCM metadata prefix")
            val decoded = try { Base64.getDecoder().decode(data.copyOfRange(22, data.size)) }
                catch (e: IllegalArgumentException) { throw NcmFormatException("Invalid NCM metadata base64", e) }
            val plain = decrypt(decoded, metadataKey)
            if (plain.size < 6 || !plain.copyOfRange(0, 6).contentEquals("music:".toByteArray()))
                throw NcmFormatException("Invalid NCM metadata plaintext")
            String(plain, 6, plain.size - 6, Charsets.UTF_8)
        }
        exact(5) // CRC32 and image version, ignored by upstream as well.
        val coverFrameLength = length(MAX_COVER, "cover frame")
        val coverLength = length(MAX_COVER, "cover")
        if (coverLength > coverFrameLength) throw NcmFormatException("Cover exceeds NCM cover frame")
        var remaining = coverFrameLength
        while (remaining > 0) { val count = minOf(remaining, 8192); exact(count); remaining -= count }
        val mask = IntArray(256) { offset ->
            val j = (offset + 1) and 255
            box[(box[j] + box[(box[j] + j) and 255]) and 255]
        }
        val buffer = ByteArray(32768)
        var position = 0L
        while (true) {
            checkpoint()
            var n = source.read(buffer)
            if (n < 0) break
            if (n == 0) { val b = source.read(); if (b < 0) break; buffer[0] = b.toByte(); n = 1 }
            for (i in 0 until n) buffer[i] = (buffer[i].toInt() xor mask[((position + i) and 255).toInt()]).toByte()
            destination.write(buffer, 0, n)
            position += n
        }
        if (position == 0L) throw EOFException("NCM audio payload is empty")
        return Result(position, metadata)
    }

    private fun xor(data: ByteArray, value: Int) {
        for (i in data.indices) data[i] = (data[i].toInt() xor value).toByte()
    }
    private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % 16 != 0) throw NcmFormatException("Invalid NCM AES block length")
        try {
            return Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(data)
            }
        } catch (e: java.security.GeneralSecurityException) {
            throw NcmFormatException("Invalid NCM AES key or padding", e)
        }
    }
}
