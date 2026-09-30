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
            val prefix = payload.inputStream().use { input -> ByteArray(4).also {
                if (input.read(it) != 4) throw IOException("Truncated NCM audio payload")
            } }
            val native = prefix.contentEquals("fLaC".toByteArray())
            val isMp3 = prefix[0] == 'I'.code.toByte() && prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte() ||
                prefix[0].toInt() and 255 == 255 && prefix[1].toInt() and 0xe0 == 0xe0

            // ── 输出阶梯：优先 FLAC；转不出 FLAC 就回退，而不是直接失败 ──
            //   ① FLAC 载荷 + 校验通过                 → 原样发布 .flac（无损、零重编码）
            //   ② FLAC 载荷 + 校验不通过（截断/失同步） → 仍发布 .flac 但标记 flac-unverified-raw：
            //      至少能播到断点，也能拿别的工具抢救；比"什么都不给"有用
            //   ③ MP3 载荷 + 转 FLAC 成功               → .flac
            //   ④ MP3 载荷 + 转 FLAC 失败               → 直接发布原始 .mp3（它本来就是 mp3）
            var publish: File
            var extension: String
            var fallback: String? = null
            var info: FlacValidation.Info? = null
            when {
                native -> {
                    info = try { FlacValidation.validate(payload, checkpoint) } catch (_: IOException) { null }
                    publish = payload
                    extension = "flac"
                    if (info == null) fallback = "flac-unverified-raw"
                }
                isMp3 -> {
                    val flacFile = File.createTempFile(".ncm-flac-", ".tmp", directory).also { encoded = it }
                    val converted = try {
                        transcodeMp3(payload, flacFile, checkpoint)
                        info = FlacValidation.validate(flacFile, checkpoint)
                        true
                    } catch (_: Exception) { false }
                    if (converted) { publish = flacFile; extension = "flac" }
                    else { publish = payload; extension = "mp3"; fallback = "mp3-passthrough"; info = null }
                }
                else -> throw IOException("Unsupported NCM payload: expected native FLAC or MP3")
            }

            val sidecar = File(directory, "ncm-$hash.json")
            val target = File(directory, "ncm-$hash.$extension")
            if (target.isFile && target.length() > 4096 && fallback == null) {
                val stillValid = try { FlacValidation.validate(target, checkpoint); true } catch (_: IOException) { false }
                if (stillValid) { checkpoint(); return target }
            }

            checkpoint()
            val metadataFile = File.createTempFile(".ncm-meta-", ".tmp", directory)
            sidecarTemp = metadataFile
            val json = buildString {
                append("{\n  \"schema\": 1,\n  \"sourceSha256\": ").append(quote(hash))
                append(",\n  \"displayName\": ").append(quote(displayName.take(4096)))
                append(",\n  \"payloadFormat\": ").append(quote(if (native) "flac" else "mp3"))
                append(",\n  \"outputFormat\": ").append(quote(extension))
                append(",\n  \"fallback\": ").append(fallback?.let(::quote) ?: "null")
                append(",\n  \"sampleRate\": ${info?.sampleRate ?: 0},\n  \"channels\": ${info?.channels ?: 0},\n  \"bitsPerSample\": ${info?.bitsPerSample ?: 0},\n  \"samples\": ${info?.samples ?: 0L}")
                // Preserve raw upstream metadata without depending on Android org.json on the JVM.
                append(",\n  \"ncmMetadataJson\": ").append(metadata.metadataJson?.let(::quote) ?: "null")
                append("\n}\n")
            }
            FileOutputStream(metadataFile).use { it.write(json.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            checkpoint()
            atomicMove(metadataFile, sidecar); publishedSidecar = sidecar
            // Same-filesystem atomic replacement; never fall back to non-atomic copy.
            atomicMove(publish, target)
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
