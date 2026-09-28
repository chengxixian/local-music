package com.localmusic.audio.ncm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * 用**真实世界**的 ncm 文件跑回归（不进仓库，靠环境变量指路）：
 *
 *     $env:LM_NCM_REAL_FILE = "D:\...\宇多田ヒカル - Distance.ncm"
 *     gradle :audio:testDebugUnitTest --tests "*NcmRealFileRegressionTest*"
 *
 * 这个用例记录的是一个**已经查清的现象**，不是"期望它成功"：
 * 该文件能正常解密（载荷以 `fLaC` 开头，元数据、位深、采样率、声明时长都读得出来），
 * 但帧流在中途失去同步（"Invalid FLAC frame sync"）。逐帧 CRC8/CRC16 一路是对的，
 * 说明不是密钥/掩码问题，而是**载荷本身不完整或非标准**（源 ncm 疑似下载不完整）。
 *
 * 结论：校验器必须拒绝它 —— 宁可不产出，也不能往曲库里塞一个播到一半就断的文件。
 * 如果哪天查明这类文件其实可播，就改这个断言（放宽校验器），而不是删掉它。
 */
class NcmRealFileRegressionTest {
    @Test
    fun realWorldNcmIsDecodableButRejectedWhenFramesDesync() {
        val path = System.getenv("LM_NCM_REAL_FILE")
        assumeTrue("LM_NCM_REAL_FILE 未设置，跳过真实文件回归", !path.isNullOrBlank())
        val source = File(path!!)
        assumeTrue("文件不存在：$path", source.isFile)

        val target = File.createTempFile("ncm-real-", ".flac")
        try {
            val result = source.inputStream().use { input ->
                FileOutputStream(target).use { output -> NcmDecoder.decode(input, output) }
            }
            println("real ncm: ${source.name} (${source.length()} bytes) -> payload ${result.payloadBytes} bytes")
            println("  metadata: ${result.metadataJson?.take(200)}")
            assertEquals("fLaC", target.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
            assertTrue("解密应产出可观的载荷", result.payloadBytes > 1_000_000)

            val error = assertThrows(java.io.IOException::class.java) { FlacValidation.validate(target) }
            println("  校验器按预期拒绝：${error.message}")
        } finally {
            target.delete()
        }
    }
}
