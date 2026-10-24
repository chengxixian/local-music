// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份包：数据库 + 设置 + 歌词 + 封面（+ 乐单封面），打包成一个 ZIP，目标可选任意 SAF 提供方（网盘也行）。
 *
 * 这个类要解决三个以前会"看着成功、实际没用"的问题：
 *
 * 1. **导出时路径判错**：旧实现用 `file.path.contains("\\lyrics\\")` 判目录 —— Android 的路径分隔符是
 *    `/`，于是所有歌词都被写进 `covers/`，恢复回来歌词全丢。现在统一用 `relativeTo(dir)` 算相对路径。
 * 2. **导出的是"活的"数据库**：SQLite 连接还开着就 `copyTo` 拿到的可能是半写状态（还有 `-journal`）。
 *    现在优先 `VACUUM INTO` 取一致快照，失败才退回文件复制，并且不再把 `-journal/-wal` 打进去。
 * 3. **恢复时直接盖掉正在使用的文件**：数据库被 SQLiteOpenHelper 打开着，覆盖后会被连接缓存/回滚日志
 *    反写回去，等于没恢复；`shared_prefs` 也已经读进内存。现在恢复只**解包到暂存目录并留下标记**，
 *    由 [LocalMusicApplication] 在下次启动、任何 DB/prefs 打开**之前**应用（[applyPendingRestore]）。
 */
object BackupManager {
    private const val PREFS_SETTINGS = "shared_prefs/settings.xml"
    private const val PREFS_EQ = "shared_prefs/eq.xml"
    private const val PREFS_IMPORTS = "shared_prefs/imports.xml"
    private const val DB_ENTRY = "databases/library.db"
    private const val META_ENTRY = "meta.json"

    private const val STAGE_DIR = "restore-pending"
    private const val MARKER = "pending-restore.flag"

    /** 允许出现在备份包里的顶层前缀：恢复时据此拒绝陌生/越界路径（zip-slip）。 */
    private val allowedPrefixes = listOf("databases/", "shared_prefs/", "lyrics/", "covers/", "playlist-covers/")

    // ───────────────────────── 导出 ─────────────────────────

    fun export(context: Context, target: Uri): Boolean = runCatching {
        val staging = File(context.cacheDir, "backup-staging").apply { deleteRecursively(); mkdirs() }
        try {
            // ① 数据库：先取一致快照，取不到再退回直接复制
            val dbSnapshot = File(staging, "library.db")
            val snapshotOk = runCatching { snapshotDatabase(context, dbSnapshot) }.getOrDefault(false)
            val liveDb = File(context.dataDir, DB_ENTRY)
            if (!snapshotOk || !dbSnapshot.isFile) liveDb.copyTo(dbSnapshot, overwrite = true)

            // ② 其它文件
            val files = mutableListOf<Pair<String, File>>()
            if (dbSnapshot.isFile && dbSnapshot.length() > 0) files += DB_ENTRY to dbSnapshot
            listOf(PREFS_SETTINGS, PREFS_EQ, PREFS_IMPORTS).forEach { entry ->
                val f = File(context.dataDir, entry)
                if (f.isFile) files += entry to f
            }
            collectZip(files, "lyrics", File(context.filesDir, "lyrics"))
            collectZip(files, "covers", File(context.filesDir, "covers"))
            collectZip(files, "playlist-covers", File(context.filesDir, "playlist-covers"))

            context.contentResolver.openOutputStream(target)?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    // meta.json：恢复端据此判断包是否完整/版本是否差太多
                    val meta = JSONObject()
                        .put("app", "local-music")
                        .put("format", 2)
                        .put("versionName", versionName(context))
                        .put("created", System.currentTimeMillis())
                        .put("files", files.size)
                    zip.putNextEntry(ZipEntry(META_ENTRY))
                    zip.write(meta.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    files.forEach { (entry, file) ->
                        zip.putNextEntry(ZipEntry(entry))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: error("无法打开备份目标")
        } finally {
            staging.deleteRecursively()
        }
    }.isSuccess

    private fun collectZip(into: MutableList<Pair<String, File>>, prefix: String, dir: File) {
        if (!dir.isDirectory) return
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(dir).invariantSeparatorsPath
            into += "$prefix/$rel" to file
        }
    }

    /**
     * `VACUUM INTO` 拿一份事务一致的数据库副本（SQLite 3.27+；Android 13 是 3.32）。
     * 有别的连接正在写时会 BUSY，此时让调用方退回普通复制。
     */
    private fun snapshotDatabase(context: Context, out: File): Boolean {
        val source = File(context.dataDir, DB_ENTRY)
        if (!source.isFile) return false
        if (out.exists()) out.delete()
        val quoted = out.absolutePath.replace("'", "''")
        SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.execSQL("VACUUM INTO '$quoted'")
        }
        return out.isFile && out.length() > 0
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    // ───────────────────────── 恢复（两阶段）─────────────────────────

    /**
     * 第一阶段：把包解开到私有暂存目录并留下标记，**不动**正在使用的数据库/设置。
     * 真正生效在下次启动时（[applyPendingRestore]）。
     */
    fun restore(context: Context, source: Uri): Boolean = runCatching {
        val stage = File(context.filesDir, STAGE_DIR).apply { deleteRecursively(); mkdirs() }
        var count = 0
        context.contentResolver.openInputStream(source)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val name = entry.name.replace('\\', '/')
                    if (name == META_ENTRY) { zip.closeEntry(); continue }
                    // 只接受已知前缀；并且规范化后必须仍在 stage 内（挡 zip-slip）
                    if (allowedPrefixes.none { name.startsWith(it) }) { zip.closeEntry(); continue }
                    val out = File(stage, name)
                    val canonical = out.canonicalFile
                    if (!canonical.path.startsWith(stage.canonicalFile.path + File.separator)) {
                        zip.closeEntry(); continue
                    }
                    canonical.parentFile?.mkdirs()
                    FileOutputStream(canonical).use { zip.copyTo(it) }
                    count++
                    zip.closeEntry()
                }
            }
        } ?: error("无法打开备份文件")
        if (count == 0) error("备份包里没有可恢复的文件")
        File(context.filesDir, MARKER).writeText(System.currentTimeMillis().toString())
        true
    }.getOrElse {
        File(context.filesDir, STAGE_DIR).deleteRecursively()
        File(context.filesDir, MARKER).delete()
        false
    }

    fun hasPendingRestore(context: Context): Boolean = File(context.filesDir, MARKER).isFile

    /**
     * 第二阶段：在 Application.onCreate 里、`LibraryRepository`/SharedPreferences 首次使用**之前**调用。
     * 把暂存文件搬到正式位置；数据库只搬 `songs/favorites/playlists/covers` 这几张由本 App 拥有的表所在的
     * 整份 db 文件 —— 因为此时还没有任何连接打开它。
     */
    fun applyPendingRestore(context: Context) {
        val stage = File(context.filesDir, STAGE_DIR)
        val marker = File(context.filesDir, MARKER)
        if (!marker.isFile || !stage.isDirectory) return
        runCatching {
            // ① 数据库：先删掉旧的 -journal/-wal/-shm，否则回滚日志会把新库覆盖回旧状态
            val dbDir = File(context.dataDir, "databases")
            dbDir.mkdirs()
            listOf("", "-journal", "-wal", "-shm").forEach { suffix ->
                File(dbDir, "library.db$suffix").delete()
            }
            stage.walkTopDown().filter { it.isFile }.forEach { src ->
                val rel = src.relativeTo(stage).invariantSeparatorsPath
                val dst = when {
                    rel.startsWith("databases/") -> File(context.dataDir, rel)
                    rel.startsWith("shared_prefs/") -> File(context.dataDir, rel)
                    else -> File(context.filesDir, rel)
                }
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
            // ② 让系统知道 SharedPreferences 变了：这要求进程还没读过它们 → 由调用时机保证。
            //    这里只把文件放好，Android 会在首次 getSharedPreferences 时重新解析。
        }
        stage.deleteRecursively()
        marker.delete()
    }
}
