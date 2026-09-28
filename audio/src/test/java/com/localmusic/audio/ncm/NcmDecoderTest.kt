package com.localmusic.audio.ncm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 用 taurusxin/ncmdump 仓库自带的真实样本 `test/test.ncm` 验证移植正确性。
 *
 * 判据（不依赖任何"我自己的期望值"）：
 *  - 解密后的第一个 chunk 必须落在 ncmdump 自己认的两种格式头之一（`fLaC` / `ID3`）——
 *    密钥盒只要错一个字节，这里就必然不是合法文件头；
 *  - 元数据 JSON 必须能被解出来并带 `musicName` 字段；
 *  - 载荷必须是**语法完整、逐帧 CRC 自洽**的 FLAC（复用 FlacValidation，非仅比对魔数）。
 */
class NcmDecoderTest {
    /**
     * 上游 ncmdump 仓库自带的 `test/test.ncm`（一段 4 秒的歌曲片段，属于第三方版权内容），
     * **不随本仓库分发**。想跑这个用例，把它放到这里即可：
     *   audio/src/test/resources/ncm/test.ncm
     * 取法：`git clone https://github.com/taurusxin/ncmdump`，拷 `test/test.ncm`。
     */
    private fun fixture(): ByteArray? =
        javaClass.getResourceAsStream("/ncm/test.ncm")?.use { it.readBytes() }

    private fun decode(bytes: ByteArray): Pair<NcmDecoder.Result, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = NcmDecoder.decode(ByteArrayInputStream(bytes), out)
        return result to out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test
    fun decodesUpstreamFixtureToValidFlac() {
        val bytes = fixture()
        assumeTrue("未提供 ncmdump 的 test.ncm 样本，跳过（见类注释）", bytes != null)
        val (result, payload) = decode(bytes!!)
        assertEquals(result.payloadBytes, payload.size.toLong())
        assertTrue("payload unexpectedly small: ${payload.size}", payload.size > 10_000)
        assertEquals("fLaC", String(payload, 0, 4, Charsets.US_ASCII))

        val file = File.createTempFile("ncm-test-", ".flac")
        try {
            file.writeBytes(payload)
            val info = FlacValidation.validate(file)
            assertTrue("sample rate", info.sampleRate > 0)
            assertTrue("samples", info.samples > 0)
            assertTrue("bits", info.bitsPerSample in 4..32)
            println("fixture FLAC: ${info.bitsPerSample}bit ${info.sampleRate}Hz ${info.channels}ch ${info.samples} samples, payload=${payload.size} bytes")
        } finally { file.delete() }

        assertNotNull("upstream fixture carries metadata", result.metadataJson)
        val json = result.metadataJson!!
        assertTrue("metadata should contain musicName: $json", json.contains("\"musicName\""))
        println("fixture metadata: ${json.take(400)}")
    }

    @Test
    fun decodingIsDeterministic() {
        val bytes = fixture()
        assumeTrue("未提供 ncmdump 的 test.ncm 样本，跳过", bytes != null)
        val first = decode(bytes!!).second
        val second = decode(bytes).second
        assertEquals(sha256(first), sha256(second))
    }

    @Test
    fun rejectsWrongMagic() {
        assertThrows(NcmFormatException::class.java) {
            NcmDecoder.decode(ByteArrayInputStream(ByteArray(64)), ByteArrayOutputStream())
        }
        val corrupted = "CTENFDAM".toByteArray() + ByteArray(8) // 头对了、后续是垃圾
        assertThrows(java.io.IOException::class.java) {
            NcmDecoder.decode(ByteArrayInputStream(corrupted), ByteArrayOutputStream())
        }
    }

    @Test
    fun rejectsTruncatedHeader() {
        assertThrows(java.io.IOException::class.java) {
            NcmDecoder.decode(ByteArrayInputStream(ByteArray(20)), ByteArrayOutputStream())
        }
    }

    @Test
    fun propagatesCheckpointFailure() {
        val boom = IllegalStateException("cancelled")
        val thrown = assertThrows(IllegalStateException::class.java) {
            NcmDecoder.decode(ByteArrayInputStream(ByteArray(64)), ByteArrayOutputStream()) { throw boom }
        }
        assertEquals("cancelled", thrown.message)
    }
}
