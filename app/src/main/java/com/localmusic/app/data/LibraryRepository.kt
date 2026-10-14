// SPDX-License-Identifier: GPL-3.0-or-later
// Folder-scoped scan (user-picked SAF trees) + cached true audio specifications adapted from Rueded/AURALIS.
package com.localmusic.app.data

import com.localmusic.app.R
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Doc
import com.localmusic.audio.ncm.NcmImport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class ScanStatus(val running: Boolean = false, val message: String = "等待扫描", val imported: Int = 0, val failures: List<String> = emptyList())

/**
 * 曲库来源：**只扫用户手动选定的文件夹**（SAF 目录授权）+ 本应用自己产出的 ncm 转换结果。
 *
 * ⚠️ 这里**故意不查 MediaStore**：
 * 旧版会先把整机媒体库（`MediaStore.Audio`）全量灌进曲库，等于读取用户设备上所有音频目录。
 * 现在改为"用户选哪个文件夹才读哪个" —— 没选文件夹就是空曲库，并在设置里提示去添加。
 *
 * 历史条目的处理：老版本的歌曲身份是 `content://media/...`，现在是文件夹的文档 URI。
 * [doScan] 里有一次性的对账迁移：按 (文件大小, 格式) **唯一**匹配，把收藏/乐单/自选封面
 * 改键到新条目上，然后把老的 `media:` 行删掉（宁可少迁，也不张冠李戴）。
 */
class LibraryRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = MusicDatabase(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val imports = context.getSharedPreferences("imports", Context.MODE_PRIVATE)
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()
    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()
    private var scanJob: Job? = null
    init {
        scope.launch { _songs.value = db.all() }
    }

    /** 导出物可能已经在用户自己的文件夹里（content://），也可能还在私有暂存（file://）。 */
    private fun exists(location: String): Boolean = try {
        if (location.startsWith("content://")) {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(location), "r")?.use { true } ?: false
        } else {
            File(Uri.parse(location).path ?: location).let { it.isFile && it.length() > 42 }
        }
    } catch (_: Exception) { false }

    fun treeUris(): Set<String> =
        (prefs.getStringSet("trees", emptySet())!! +
            listOfNotNull(prefs.getString("exportTree", null))).toSet()
    fun addTree(uri: Uri) {
        prefs.edit().putStringSet("trees", treeUris() + uri.toString()).apply()
        scan()
    }
    fun removeTree(uri: String) {
        prefs.edit().putStringSet("trees", treeUris() - uri).apply()
        if (prefs.getString("exportTree", null) == uri) {
            prefs.edit().remove("exportTree").remove("exportLabel").apply()
        }
        scope.launch { db.merge(emptyList(), setOf(uri)); _songs.value = db.all() }
    }
    @Synchronized fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch { doScan() }
    }
    fun cancel() { scanJob?.cancel() }
    private suspend fun doScan() {
        _status.value = ScanStatus(true, context.getString(R.string.scan_running))
        val warnings = mutableListOf<String>()
        var imported = 0
        try {
            val cached = db.all().associateBy { it.uri }
            val found = mutableListOf<Song>()
            val complete = mutableSetOf<String>()
            fun enrich(base: Song, file: File? = null): Song {
                val old = cached[base.uri]
                return if (old != null && old.modified == base.modified && old.size == base.size && base.modified > 0) old
                    else MetadataReader.read(context, base, file)
            }

            // ── 只扫用户手动选定的文件夹（SAF 授权目录）──
            for (tree in treeUris()) {
                currentCoroutineContext().ensureActive()
                try {
                    val root = Uri.parse(tree)
                    val pending = ArrayDeque<String>()
                    val visited = mutableSetOf<String>()
                    pending.add(Doc.getTreeDocumentId(root))
                    var count = 0
                    while (pending.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        val parent = pending.removeFirst()
                        if (!visited.add(parent)) continue
                        val children = Doc.buildChildDocumentsUriUsingTree(root, parent)
                        val projection = arrayOf(Doc.Document.COLUMN_DOCUMENT_ID, Doc.Document.COLUMN_DISPLAY_NAME, Doc.Document.COLUMN_MIME_TYPE, Doc.Document.COLUMN_SIZE, Doc.Document.COLUMN_LAST_MODIFIED)
                        val rows = context.contentResolver.query(children, projection, null, null, null) ?: error("目录不可用")
                        // Close provider cursor before a potentially long conversion.
                        val entries = rows.use { c -> buildList { while (c.moveToNext()) add(DocEntry(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), c.getLong(4))) } }
                        for (entry in entries) {
                            currentCoroutineContext().ensureActive()
                            if (++count > 100000) error("目录超过 100000 项，请选择更小的音乐目录")
                            if (entry.mime == Doc.Document.MIME_TYPE_DIR) { pending.add(entry.id); continue }
                            val source = Doc.buildDocumentUriUsingTree(root, entry.id)
                            val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (ext == "ncm" && prefs.getBoolean("autoNcm", true)) {
                                val key = hash(source.toString() + "|${entry.size}|${entry.modified}")
                                val prior = imports.getString(key, null)
                                if (prior != null && exists(prior)) continue
                                _status.value = ScanStatus(true, "NCM → FLAC：${entry.name}", imported, warnings.toList())
                                try {
                                    val exported = NcmImport.convert(context, source, entry.name)
                                    imports.edit().putString(key, exported.location).apply()
                                    imported++
                                    // 回退产物如实说一声：用户应该知道"这次不是无损 FLAC"
                                    exported.fallback?.let { reason ->
                                        warnings += "${entry.name}：已回退为 .${exported.format}（$reason）"
                                    }
                                } catch (e: CancellationException) { throw e } catch (e: Exception) { warnings += "${entry.name}：${e.message}" }
                            } else if (ext in MetadataReader.extensions) {
                                found += enrich(Song(source.toString(), entry.name.substringBeforeLast('.'), "未知艺术家", "未知专辑", 0, entry.size, entry.modified, ext, origin = tree))
                            }
                        }
                    }
                    complete += tree
                } catch (e: CancellationException) { throw e } catch (e: Exception) { warnings += "授权文件夹：${e.message}；可在设置中重新授权。" }
            }

            val privateRoot = File(context.filesDir, "music/ncm")
            privateRoot.mkdirs()
            // 回退产物可能是 mp3/m4a，这里要一并索引，否则"转了但曲库里看不到"
            val privateFormats = setOf("flac", "mp3", "m4a", "mp4", "ogg", "opus")
            privateRoot.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in privateFormats }?.forEach { file ->
                currentCoroutineContext().ensureActive()
                val named = SidecarNaming.read(File(file.parentFile, file.nameWithoutExtension + ".json"))
                val base = Song(
                    uri = Uri.fromFile(file).toString(),
                    title = named?.title ?: file.nameWithoutExtension,
                    artist = named?.artist ?: "未知艺术家",
                    album = named?.album ?: "NCM 导入",
                    duration = 0, size = file.length(), modified = file.lastModified(),
                    format = file.extension.lowercase(Locale.ROOT), origin = "private",
                )
                found += enrich(base, file)
            }
            complete += "private"

            // ── 一次性迁移：老版本用"整机媒体库"URI 当歌曲身份，现在换成文件夹文档 URI。
            //    按 (大小, 格式) **唯一**匹配把收藏 / 乐单 / 自选封面改键过来，然后删掉老行。
            //    匹配不唯一就跳过 —— 宁可少迁几首，也不能把 A 的收藏记到 B 头上。
            //
            //    ⚠️ 只有"用户已经选了文件夹"时才做这一步：没选文件夹时 `found` 里根本没有候选，
            //    此时删掉老行等于白白丢掉收藏 / 封面。所以那种情况下老行**继续留在库里**
            //    当迁移素材（对用户不可见，见 MusicDatabase.all()），等第一次真正扫到文件夹再迁。
            val legacy = db.songsByOriginPrefix("media:")
            if (legacy.isNotEmpty() && treeUris().isNotEmpty()) {
                val byKey = found
                    .groupBy { it.size to it.format }
                    .filterValues { it.size == 1 }
                    .mapValues { it.value.first() }
                var migrated = 0
                legacy.forEach { old ->
                    val target = byKey[old.size to old.format] ?: return@forEach
                    if (target.uri != old.uri) { runCatching { db.rekey(old.uri, target.uri) }; migrated++ }
                }
                db.deleteOriginsLike("media:")
                if (migrated > 0) warnings += context.getString(R.string.scan_migrated, migrated)
            }

            db.merge(found.distinctBy { it.uri }, complete)
            _songs.value = db.all()
            val done = if (treeUris().isEmpty()) context.getString(R.string.scan_no_folder)
                else "扫描完成 · ${_songs.value.size} 首歌曲"
            _status.value = ScanStatus(false, done, imported, warnings.takeLast(20))
        } catch (e: CancellationException) {
            _status.value = ScanStatus(false, "扫描已停止，原文件保持不变", imported, warnings.takeLast(20))
            throw e
        } catch (e: Exception) {
            _status.value = ScanStatus(false, "扫描失败：${e.message}", imported, warnings.takeLast(20))
        }
    }
    private data class DocEntry(val id: String, val name: String, val mime: String, val size: Long, val modified: Long)
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
