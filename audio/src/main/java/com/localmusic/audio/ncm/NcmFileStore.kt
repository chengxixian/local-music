package com.localmusic.audio.ncm

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/** Called under NcmImport's process and OS locks. Kept JVM-only for failure-path tests. */
internal object NcmFileStore {
    private const val MAX_PAYLOAD_BYTES = 8L * 1024 * 1024 * 1024
    fun convert(
        directory: File,
        openSource: () -> InputStream,
        displayName: String,
        transcodeMp3: (File, File, () -> Unit) -> Unit,
        checkpoint: () -> Unit = {}
    ): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create internal NCM directory")
        val payload = File.createTempFile(".ncm-payload-", ".tmp", directory)
        var encoded: File? = null
        var sidecarTemp: File? = null
        var publishedSidecar: File? = null
        var committed = false
        try {
            checkpoint()
            val digest = MessageDigest.getInstance("SHA-256")
            val metadata = openSource().use { original ->
                DigestInputStream(original, digest).use { input ->
                    FileOutputStream(payload).use { fileOut ->
                        val bounded = object : OutputStream() {
                            var count = 0L
                            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                count += len
                                if (count > MAX_PAYLOAD_BYTES) throw IOException("NCM payload exceeds 8 GiB")
                                fileOut.write(b, off, len)
                            }
                        }
                        NcmDecoder.decode(input, bounded, checkpoint).also { fileOut.fd.sync() }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            // Hash the complete original container, not a user-controlled name or URI.
            val target = File(directory, "ncm-$hash.flac")
            val sidecar = File(directory, "ncm-$hash.json")
            if (target.isFile) {
                val valid = try { FlacValidation.validate(target, checkpoint); true }
                    catch (_: IOException) { false }
                if (valid) { checkpoint(); return target }
                // Never reuse a merely nonempty/FLAC-named file. Invalid cache replaced only on success.
            }
            val prefix = payload.inputStream().use { input -> ByteArray(4).also {
                if (input.read(it) != 4) throw IOException("Truncated NCM audio payload")
            } }
            val native = prefix.contentEquals("fLaC".toByteArray())
            val complete = if (native) payload else {
                val mp3 = prefix[0] == 'I'.code.toByte() && prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte() ||
                    prefix[0].toInt() and 255 == 255 && prefix[1].toInt() and 0xe0 == 0xe0
                if (!mp3) throw IOException("Unsupported NCM payload: expected native FLAC or MP3")
                val file = File.createTempFile(".ncm-flac-", ".tmp", directory)
                encoded = file
                transcodeMp3(payload, file, checkpoint)
                file
            }
            val info = FlacValidation.validate(complete, checkpoint)
            checkpoint()
            val metadataFile = File.createTempFile(".ncm-meta-", ".tmp", directory)
            sidecarTemp = metadataFile
            val json = buildString {
                append("{\n  \"schema\": 1,\n  \"sourceSha256\": ").append(quote(hash))
                append(",\n  \"displayName\": ").append(quote(displayName.take(4096)))
                append(",\n  \"payloadFormat\": ").append(quote(if (native) "flac" else "mp3"))
                append(",\n  \"sampleRate\": ${info.sampleRate},\n  \"channels\": ${info.channels},\n  \"bitsPerSample\": ${info.bitsPerSample},\n  \"samples\": ${info.samples}")
                // Preserve raw upstream metadata without depending on Android org.json on the JVM.
                append(",\n  \"ncmMetadataJson\": ").append(metadata.metadataJson?.let(::quote) ?: "null")
                append("\n}\n")
            }
            FileOutputStream(metadataFile).use { it.write(json.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            checkpoint()
            atomicMove(metadataFile, sidecar); publishedSidecar = sidecar
            // Same-filesystem atomic replacement; never fall back to non-atomic copy.
            atomicMove(complete, target)
            committed = true
            return target
        } finally {
            payload.delete()
            encoded?.delete()
            sidecarTemp?.delete()
            if (!committed) publishedSidecar?.delete()
        }
    }

    private fun atomicMove(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun quote(value: String): String = buildString {
        append('\"')
        for (c in value) when (c) {
            '\"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 32 || c.isSurrogate()) append("\\u%04x".format(c.code)) else append(c)
        }
        append('\"')
    }
}
