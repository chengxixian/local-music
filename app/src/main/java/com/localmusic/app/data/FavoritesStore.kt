// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「我喜欢的音乐」。
 *
 * 收藏只存 song.uri（与曲库同一把主键），所以：文件被移走/删掉后，
 * 曲库清理会把它从 songs 表删掉，而收藏表里那条就成了孤儿——读取时按当前曲库过滤，
 * 界面上不会出现点了没反应的僵尸条目。
 */
object FavoritesStore {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile private var cache: Set<String>? = null
    private val lock = Any()

    fun all(context: Context): Set<String> = cache ?: synchronized(lock) {
        cache ?: MusicDatabase(context.applicationContext).favorites().toSet().also { cache = it }
    }

    /** 按加入时间倒序，只保留仍在曲库里的。 */
    fun ordered(context: Context, library: List<Song>): List<Song> {
        val order = MusicDatabase(context.applicationContext).favorites()
        val byUri = library.associateBy { it.uri }
        return order.mapNotNull { byUri[it] }
    }

    fun isFavorite(context: Context, uri: String?): Boolean = uri != null && all(context).contains(uri)

    fun set(context: Context, uri: String, favorite: Boolean): Boolean = try {
        MusicDatabase(context.applicationContext).setFavorite(uri, favorite)
        synchronized(lock) { cache = null }
        _revision.value += 1
        true
    } catch (_: Exception) { false }
}
