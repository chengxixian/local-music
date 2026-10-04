// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 乐单（用户自建歌单）。
 *
 * 设计上刻意和 [FavoritesStore] 保持同一套写法：数据库只存 `uri` / `id`，
 * 内存里一份缓存 + 一个 `revision` 计数器，改动后 `+1` 让界面基于 revision 重新读。
 *
 * 为什么不做成 Flow 实时查询：曲库有 400+ 首、乐单通常只有几个，读一次的开销极小，
 * 而 revision 模式已经够用，且不会给数据库加常驻监听。
 */
object PlaylistStore {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val lock = Any()
    @Volatile private var cache: List<Playlist>? = null

    fun all(context: Context): List<Playlist> = cache ?: synchronized(lock) {
        cache ?: MusicDatabase(context.applicationContext).playlists().also { cache = it }
    }

    fun create(context: Context, name: String): Long = try {
        val id = MusicDatabase(context.applicationContext).createPlaylist(name.trim().ifBlank { "新乐单" })
        refresh()
        id
    } catch (_: Exception) { -1L }

    fun rename(context: Context, id: Long, name: String): Boolean = try {
        MusicDatabase(context.applicationContext).renamePlaylist(id, name.trim().ifBlank { "新乐单" })
        refresh()
        true
    } catch (_: Exception) { false }

    fun setCover(context: Context, id: Long, path: String?): Boolean = try {
        MusicDatabase(context.applicationContext).setPlaylistCover(id, path)
        refresh()
        true
    } catch (_: Exception) { false }

    fun delete(context: Context, id: Long): Boolean = try {
        MusicDatabase(context.applicationContext).deletePlaylist(id)
        refresh()
        true
    } catch (_: Exception) { false }

    /** 某个乐单里的歌曲 uri（按加入顺序）。 */
    fun songIds(context: Context, id: Long): List<String> =
        runCatching { MusicDatabase(context.applicationContext).playlistSongIds(id) }.getOrDefault(emptyList())

    /** 这首歌已经在哪些乐单里（"加入乐单"面板用来自动勾选）。 */
    fun playlistsOf(context: Context, uri: String?): Set<Long> =
        if (uri == null) emptySet()
        else runCatching { MusicDatabase(context.applicationContext).playlistsOf(uri) }.getOrDefault(emptySet())

    fun setSong(context: Context, playlist: Long, uri: String, member: Boolean): Boolean = try {
        MusicDatabase(context.applicationContext).setPlaylistSong(playlist, uri, member)
        refresh()
        true
    } catch (_: Exception) { false }

    private fun refresh() {
        synchronized(lock) { cache = null }
        _revision.value += 1
    }

    /**
     * 把用户挑的图片复制进应用目录，返回本地路径。
     *
     * 为什么不直接存 `content://`：SAF 授权的 uri 会随文件被删/权限失效而废掉，
     * 乐单封面必须长期可用，所以和歌曲自选封面一样落一份到私有目录。
     */
    fun importCover(context: Context, source: android.net.Uri): String? = try {
        val app = context.applicationContext
        val dir = java.io.File(app.filesDir, "playlist-covers").apply { mkdirs() }
        val file = java.io.File(dir, "cover-${System.currentTimeMillis()}.img")
        app.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        file.absolutePath
    } catch (_: Exception) { null }
}
