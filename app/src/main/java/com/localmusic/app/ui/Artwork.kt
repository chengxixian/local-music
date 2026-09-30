// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localmusic.app.data.CoverStore
import com.localmusic.app.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 封面加载：**只decode需要的尺寸**，并做磁盘+内存两级缓存。
 *
 * 之前每行都直接解内嵌原图（可达 800px），列表滚动时要对每个可见行做一次
 * `MediaMetadataRetriever` + 全尺寸解码 —— 这是滑动卡顿的主因。现在：
 *
 *  1. 优先问系统要缩略图（`ContentResolver.loadThumbnail`）—— MediaProvider / DocumentsProvider
 *     直接返回小图，比解内嵌图快一个量级；
 *  2. 拿不到才回落到 `MediaMetadataRetriever.embeddedPicture`，并且**立刻按目标尺寸降采样**；
 *  3. 结果按「URI + 目标尺寸」写进 `cacheDir/artwork`，滚第二遍就是纯读盘；
 *  4. 列表用小尺寸（RGB_565，省一半内存），全屏播放器才要大图 —— 两者是不同的缓存条目。
 */
object ArtworkStore {
    // 曲库是 374 张封面的两列网格，48MB 只装得下 ~90 张 → 来回滚就一直在"淘汰-重解码"。
    // 缩略图本身不大（≤512px RGB_565 约 0.3~0.5MB），给到 160MB 才撑得住"滚第二遍纯读盘"。
    private const val DISK_BUDGET = 160L * 1024 * 1024

    // 内存缓存从 12MB 提到 64MB：滚过去再滚回来不该重新读盘/重上传位图（120Hz 屏上一帧只有 8.3ms）
    private val memory = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * 预取：把下一屏要显示的封面提前解到内存缓存里。
     *
     * 为什么有用：滚动时每张新图都要"读盘 → 解码 → 上传成纹理"，这三步的抖动正好卡在
     * 一帧里。提前在 IO 线程把位图准备好，滚到它时只剩上传，帧时间明显平（尤其 120Hz）。
     */
    fun prefetch(context: Context, songs: List<Song>, requestPx: Int) {
        for (song in songs) runCatching { load(context, song, requestPx) }
    }

    fun load(context: Context, song: Song, requestPx: Int): Bitmap? {
        val px = requestPx.coerceIn(48, 2048)
        val key = "${song.uri}@$px"
        memory.get(key)?.let { return it }

        val dir = File(context.cacheDir, "artwork")
        val file = File(dir, sha1(key) + ".img")
        if (file.isFile && file.length() > 0L) {
            decodeFile(file, px)?.let { memory.put(key, it); return it }
            file.delete()
        }

        val bitmap = fromProvider(context, song, px) ?: fromTags(context, song, px) ?: return null
        writeAtomic(dir, file, bitmap)
        memory.put(key, bitmap)
        return bitmap
    }

    /** 系统缩略图：MediaStore 音频 / SAF 文档都支持，返回的就是小图。 */
    private fun fromProvider(context: Context, song: Song, px: Int): Bitmap? = try {
        val uri = Uri.parse(song.uri)
        if (uri.scheme == "content") {
            context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        } else null
    } catch (_: Exception) { null }

    /** 内嵌封面兜底：先只读尺寸（inJustDecodeBounds），再按目标大小降采样解一次。 */
    private fun fromTags(context: Context, song: Song, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val bytes = try {
            retriever.setDataSource(context, Uri.parse(song.uri))
            retriever.embeddedPicture
        } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
            ?: return null
        return decodeBytes(bytes, px)
    }

    private fun decodeBytes(bytes: ByteArray, px: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, optionsFor(bounds, px))
    } catch (_: Exception) { null }

    private fun decodeFile(file: File, px: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        BitmapFactory.decodeFile(file.absolutePath, optionsFor(bounds, px))
    } catch (_: Exception) { null }

    private fun optionsFor(bounds: BitmapFactory.Options, px: Int): BitmapFactory.Options {
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= px) sample *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sample
            // 列表缩略图不需要 32 位色，RGB_565 内存直接减半（这类封面看不出差别）
            inPreferredConfig = if (px <= 512) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
    }

    private fun writeAtomic(dir: File, file: File, bitmap: Bitmap) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val temp = File.createTempFile("art", ".tmp", dir)
            FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!temp.renameTo(file)) temp.delete()
            trim(dir)
        } catch (_: Exception) { /* 缓存写失败不影响显示 */ }
    }

    /** 简单预算控制：超了按最后修改时间删旧的。 */
    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BUDGET) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= DISK_BUDGET) return
            val size = f.length(); if (f.delete()) total -= size
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * 封面。
 *
 * 取图顺序：**用户自选的封面** → 系统缩略图 → 内嵌封面。
 *
 * @param requestPx 需要的像素边长：列表传 160 左右（≈52dp×3），全屏播放器传 900。
 *   不同尺寸是不同缓存条目，互不干扰。
 */
@Composable
fun Artwork(song: Song?, modifier: Modifier = Modifier, radius: Int = 24, requestPx: Int = 160) {
    val context = LocalContext.current
    // 用户改过封面后，这个计数会 +1 → produceState 的 key 变化 → 重新取图
    val coverRevision by CoverStore.revision.collectAsState()
    val bitmap by produceState<Bitmap?>(null, song?.uri, requestPx, coverRevision) {
        value = null
        if (song != null) {
            value = withContext(Dispatchers.IO) {
                CoverStore.decode(context.applicationContext, song.uri, requestPx)
                    ?: ArtworkStore.load(context.applicationContext, song, requestPx)
            }
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val m = modifier.clip(RoundedCornerShape(radius.dp))
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), song?.let { "${it.album} 封面" }, m, contentScale = ContentScale.Crop)
    } else {
        Canvas(m.background(Brush.linearGradient(listOf(container, primary.copy(alpha = .15f))))) {
            val center = Offset(size.width * .5f, size.height * .5f)
            val r = size.minDimension * .35f
            drawCircle(primary.copy(alpha = .85f), r, center)
            for (i in 1..5) drawCircle(container.copy(alpha = .22f), r * (1 - i * .11f), center, style = Stroke(size.minDimension * .003f))
            drawCircle(container, r * .24f, center)
            drawCircle(primary, r * .055f, center)
        }
    }
}
