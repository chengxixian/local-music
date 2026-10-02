// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

/**
 * 用户自定义封面。
 *
 * 选择的图片会被**复制进应用目录**（`filesDir/covers/<hash>.img`）而不是只记一个 Uri：
 * 原图可能被移动、删除，或者权限在重启后失效（SAF 授权被回收），
 * 那样用户"设过的封面"就会变成空白——复制一份才真的持久。
 */
object CoverStore {
    private val _revision = MutableStateFlow(0)

    /** 封面变更计数：UI 用它当 key 触发重新解码。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile private var cache: Map<String, String>? = null
    private val lock = Any()

    private fun mapping(context: Context): Map<String, String> = cache ?: synchronized(lock) {
        cache ?: MusicDatabase(context.applicationContext).covers().also { cache = it }
    }

    fun pathFor(context: Context, songUri: String): File? =
        mapping(context)[songUri]?.let(::File)?.takeIf { it.isFile && it.length() > 0 }

    /** 保存用户选的图片；返回是否成功。 */
    fun set(context: Context, songUri: String, image: Uri): Boolean {
        val app = context.applicationContext
        return try {
            val dir = File(app.filesDir, "covers").apply { mkdirs() }
            val target = File(dir, sha1(songUri) + ".img")
            app.contentResolver.openInputStream(image)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            // 立刻读一次尺寸，确认存下来的确实是张能解码的图片
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { target.delete(); return false }
            commit(app, songUri, target.absolutePath, refresh = true, source = CoverSource.USER)
            true
        } catch (_: Exception) { false }
    }

    /**
     * 刮削/下载得到的图片字节直接落盘（和用户手选封面走同一套存储与映射）。
     *
     * [refresh] 为 false 时**只更新内存映射、不触发 UI 重载**：批量刮削一批 30 张封面时，
     * 如果每存一张就让整墙封面失效重解码，界面会肉眼可见地卡；调用方攒一批后用 [refresh] 收尾。
     */
    fun setFromBytes(context: Context, songUri: String, bytes: ByteArray, refresh: Boolean = true): Boolean {
        val app = context.applicationContext
        return try {
            if (bytes.isEmpty()) return false
            val dir = File(app.filesDir, "covers").apply { mkdirs() }
            val target = File(dir, sha1(songUri) + ".img")
            target.writeBytes(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                target.delete()
                false
            } else {
                commit(app, songUri, target.absolutePath, refresh, source = CoverSource.SCRAPED)
                true
            }
        } catch (_: Exception) { false }
    }

    /** 批量场景收尾：一次性让封面 UI 重载。 */
    fun refresh() { _revision.value += 1 }

    /**
     * 撤掉"刮削下载的封面"、让歌曲**自带的封面**重新显示（用户自己设的 user 封面一律保留）。
     *
     * 判定"自带封面"用 [ArtworkProbe]（系统媒体库专辑封面 / 文件内嵌图）。
     * 返回清掉的数量。
     */
    fun restoreOriginals(context: Context, songs: List<Song>): Int {
        val app = context.applicationContext
        val sources = MusicDatabase(app).coverSources()
        var restored = 0
        for (song in songs) {
            val path = pathFor(app, song.uri) ?: continue
            if (sources[song.uri] == CoverSource.USER) continue
            if (!ArtworkProbe.hasOwnArtwork(app, song)) continue
            path.delete()
            MusicDatabase(app).setCover(song.uri, null)
            synchronized(lock) { cache = cache?.minus(song.uri) }
            restored++
        }
        if (restored > 0) _revision.value += 1
        return restored
    }

    private fun commit(app: Context, songUri: String, path: String, refresh: Boolean, source: String) {
        MusicDatabase(app).setCover(songUri, path, source)
        synchronized(lock) {
            // 缓存已加载过才增量更新；没加载过就继续保持 null，下次读会连带新行一起读出来
            cache?.let { cache = it + (songUri to path) }
        }
        if (refresh) _revision.value += 1
    }

    /**
     * 清掉**自动刮削下载的封面**（`source = scraped`），用户自己设的（`source = user`）一律保留。
     *
     * 用途：刮削逻辑修好之后，之前抓错的封面（例如网易云的默认占位剪影）不会自己消失——
     * 刮削只补"缺封面"的歌。清一遍再重刮，等于给整个曲库刷新一次封面。返回清掉的数量。
     */
    fun clearScraped(context: Context): Int {
        val app = context.applicationContext
        val sources = MusicDatabase(app).coverSources()
        var cleared = 0
        for ((uri, source) in sources) {
            if (source == CoverSource.USER) continue
            pathFor(app, uri)?.delete()
            MusicDatabase(app).setCover(uri, null)
            synchronized(lock) { cache = cache?.minus(uri) }
            cleared++
        }
        if (cleared > 0) _revision.value += 1
        return cleared
    }

    fun clear(context: Context, songUri: String) {
        val app = context.applicationContext
        pathFor(app, songUri)?.delete()
        MusicDatabase(app).setCover(songUri, null)
        synchronized(lock) { cache = null }
        _revision.value += 1
    }

    /** 供 Artwork 直接解码用（已经降采样到需要的大小）。 */
    fun decode(context: Context, songUri: String, requestPx: Int): Bitmap? {
        val file = pathFor(context, songUri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= requestPx) sample *= 2
        return try {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = if (requestPx <= 512) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            })
        } catch (_: Exception) { null }
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
