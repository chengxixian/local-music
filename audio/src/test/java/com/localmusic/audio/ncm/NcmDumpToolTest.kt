package com.localmusic.audio.ncm

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * 诊断工具（不是断言型测试）：把某个真实 ncm 解码到指定路径，便于拿去做平台侧验证。
 *
 *     $env:LM_NCM_FILE = "...\x.ncm"; $env:LM_NCM_DUMP = "...\x.flac"
 *     gradle :audio:testDebugUnitTest --tests "*NcmDumpToolTest*"
 */
class NcmDumpToolTest {
    @Test
    fun dumpDecodedPayload() {
        val source = System.getenv("LM_NCM_FILE")?.let(::File)
        val dump = System.getenv("LM_NCM_DUMP")?.let(::File)
        assumeTrue("LM_NCM_FILE/LM_NCM_DUMP 未设置，跳过", source?.isFile == true && dump != null)

        val result = source!!.inputStream().use { input ->
            FileOutputStream(dump).use { output -> NcmDecoder.decode(input, output) }
        }
        val head = dump!!.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) }
        println("dumped ${dump.absolutePath} bytes=${dump.length()} head=$head payloadBytes=${result.payloadBytes}")
        println("metadata=${result.metadataJson?.take(200)}")
    }
}
