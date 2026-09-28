package com.localmusic.audio.ncm

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 导入一个 ncm 文档，**不修改也不删除源文件**，产出一个真正的 FLAC。
 *
 * 去向按优先级：
 *  1. 用户在「导出位置」里选定的文件夹（SAF）→ 导出后**删掉私有暂存**，只留用户文件夹里那份；
 *  2. 没选过 → 暂存到 filesDir/music/ncm（功能不因缺一步设置而不可用），界面会提示去选。
 *
 * 无论去哪，先在本模块的暂存目录里完成"解密 + 校验（逐帧 CRC + 总采样数）"，
 * 只有校验通过的完整文件才会被发布出去。
 */
object NcmImport {
    private val mutex = Mutex()

    /** @param location 发布后的位置（file:// 路径或 content:// Uri） @param name 文件名 */
    data class Exported(val location: String, val name: String, val inUserFolder: Boolean)

    suspend fun convert(context: Context, source: Uri, displayName: String): Exported =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            val checkpoint = { coroutine.ensureActive() }
            mutex.withLock {
                checkpoint()
                val directory = File(context.filesDir, "music/ncm")
                if (!directory.isDirectory && !directory.mkdirs())
                    throw IOException("Cannot create app-private NCM directory")
                // Stable lock inode is intentionally retained, including after failures.
                RandomAccessFile(File(directory, ".import.lock"), "rw").use { lockFile ->
                    var lock: FileLock? = null
                    while (lock == null) {
                        checkpoint()
                        lock = try { lockFile.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        if (lock == null) delay(25)
                    }
                    try {
                        // Recover only our own uncommitted temporaries after process death.
                        directory.listFiles { file -> file.name.startsWith(".ncm-") && file.name.endsWith(".tmp") }
                            ?.forEach { if (!it.delete()) throw IOException("Cannot remove stale NCM temporary") }
                        NcmExport.cleanTemp(context)

                        val flac = NcmFileStore.convert(
                            directory = directory,
                            openSource = { context.contentResolver.openInputStream(source)
                                ?: throw IOException("Cannot open NCM document") },
                            displayName = displayName,
                            transcodeMp3 = AndroidMp3Decoder::transcode,
                            checkpoint = checkpoint
                        )
                        checkpoint()
                        val sidecar = File(directory, flac.nameWithoutExtension + ".json")
                        val metadataJson = runCatching { sidecar.takeIf { it.isFile }?.readText(Charsets.UTF_8) }
                            .getOrNull()?.let { json ->
                                runCatching { org.json.JSONObject(json).optString("ncmMetadataJson") }.getOrNull()
                            }
                        val exportName = NcmNaming.exportName(metadataJson, displayName)

                        val exported = NcmExport.publish(context, flac, exportName)
                        if (exported != null) {
                            // 已经在用户文件夹里了，私有暂存没有存在价值（4GB 级重复没必要）。
                            val deleted = flac.delete()
                            sidecar.delete()
                            return@withLock Exported(exported.toString(), exportName, true)
                                .also { if (!deleted) android.util.Log.w("NcmImport", "私有暂存未能删除：${flac.name}") }
                        }
                        Exported(flac.absolutePath, flac.name, false)
                    } finally { lock.release() }
                }
            }
        }
}
