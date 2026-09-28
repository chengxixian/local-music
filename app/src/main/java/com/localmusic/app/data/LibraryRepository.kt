// SPDX-License-Identifier: GPL-3.0-or-later
// MediaStore + cached true audio specifications adapted from Rueded/AURALIS.
package com.localmusic.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract as Doc
import android.provider.MediaStore
import com.localmusic.audio.ncm.NcmImport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class ScanStatus(val running: Boolean = false, val message: String = "等待扫描", val imported: Int = 0, val failures: List<String> = emptyList())

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
    private var refreshJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshJob?.cancel()
            refreshJob = scope.launch { delay(1800); scan() }
        }
    }
    init {
        scope.launch { _songs.value = db.all() }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
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
        _status.value = ScanStatus(true, "正在扫描本地音乐…")
        val warnings = mutableListOf<String>()
        var imported = 0
        try {
            val cached = db.all().associateBy { it.uri }
            val found = mutableListOf<Song>()
            val complete = mutableSetOf<String>()
            // 同一个物理文件既可能被 MediaStore 收录、又落在用户授权的目录里（授权目录通常包含
            // 已经在媒体库里的 mp3/flac），那样列表里会出现两条一模一样的歌。这里按真实路径去重：
            // MediaStore 那趟记下 _data，SAF 那趟把 documentId 还原成路径再比对。
            val mediaPaths = mutableSetOf<String>()
            val primaryRoot = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
            fun enrich(base: Song, file: File? = null): Song {
                val old = cached[base.uri]
                return if (old != null && old.modified == base.modified && old.size == base.size && base.modified > 0) old
                    else MetadataReader.read(context, base, file)
            }
            if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                for (volume in MediaStore.getExternalVolumeNames(context)) {
                    currentCoroutineContext().ensureActive()
                    val origin = "media:$volume"
                    try {
                        val uri = MediaStore.Audio.Media.getContentUri(volume)
                        val columns = arrayOf("_id", "title", "artist", "album", "duration", "_size", "date_modified", "_display_name", "_data", "album_id")
                        val cursor = context.contentResolver.query(uri, columns, "is_pending=0 AND _size>0", null, null)
                            ?: error("媒体索引暂不可用")
                        cursor.use { c -> while (c.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            fun str(i: Int) = c.getString(i).orEmpty()
                            val source = ContentUris.withAppendedId(uri, c.getLong(0))
                            val ext = str(7).substringAfterLast('.', "").lowercase(Locale.ROOT)
                            val artwork = ContentUris.withAppendedId(Uri.parse("content://media/$volume/audio/albumart"), c.getLong(9)).toString()
                            val base = Song(source.toString(), str(1).ifBlank { str(7) }, str(2).replace("<unknown>", "未知艺术家"), str(3),
                                c.getLong(4), c.getLong(5), c.getLong(6), ext, origin = origin, artwork = artwork)
                            found += enrich(base, str(8).takeIf { it.isNotBlank() }?.let(::File))
                            str(8).takeIf { it.isNotBlank() }?.let { mediaPaths += it }
                        } }
                        complete += origin
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { warnings += "存储卷 $volume：${e.message}" }
                }
            } else warnings += "未授权读取音频；仍可使用已授权文件夹和已导入音乐。"

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
                                } catch (e: CancellationException) { throw e } catch (e: Exception) { warnings += "${entry.name}：${e.message}" }
                            } else if (ext in MetadataReader.extensions) {
                                // documentId 形如 "primary:Download/x.mp3" → /storage/emulated/0/Download/x.mp3
                                val path = entry.id.removePrefix("raw:")
                                    .takeIf { it.startsWith("primary:") }
                                    ?.removePrefix("primary:")
                                    ?.let { "$primaryRoot/$it" }
                                if (path != null && path in mediaPaths) continue // 媒体库里已经有了，别重复
                                found += enrich(Song(source.toString(), entry.name.substringBeforeLast('.'), "未知艺术家", "未知专辑", 0, entry.size, entry.modified, ext, origin = tree))
                            }
                        }
                    }
                    complete += tree
                } catch (e: CancellationException) { throw e } catch (e: Exception) { warnings += "授权文件夹：${e.message}；可在设置中重新授权。" }
            }

            val privateRoot = File(context.filesDir, "music/ncm")
            privateRoot.mkdirs()
            privateRoot.listFiles()?.filter { it.isFile && it.extension.equals("flac", true) }?.forEach { file ->
                currentCoroutineContext().ensureActive()
                val named = SidecarNaming.read(File(file.parentFile, file.nameWithoutExtension + ".json"))
                val base = Song(
                    uri = Uri.fromFile(file).toString(),
                    title = named?.title ?: file.nameWithoutExtension,
                    artist = named?.artist ?: "未知艺术家",
                    album = named?.album ?: "NCM 导入",
                    duration = 0, size = file.length(), modified = file.lastModified(),
                    format = "flac", origin = "private",
                )
                found += enrich(base, file)
            }
            complete += "private"
            db.merge(found.distinctBy { it.uri }, complete)
            _songs.value = db.all()
            _status.value = ScanStatus(false, "扫描完成 · ${_songs.value.size} 首歌曲", imported, warnings.takeLast(20))
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
