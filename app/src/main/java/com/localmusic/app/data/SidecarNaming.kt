// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import org.json.JSONObject
import java.io.File

/**
 * 读取 NCM 导入侧车 JSON（`ncm-<sha256>.json`，由 NcmFileStore 写出）。
 *
 * 为什么需要它：转码路径写出的 FLAC 不带 Vorbis 注释，文件名又是内容哈希，
 * 只靠文件本身只能得到 `ncm-3f9a…` 这种标题。侧车里有原始 ncm 元数据，
 * 用它把歌名/艺术家/专辑还原回来（优先用 ncm 元数据，退化到用户可见的原文件名）。
 */
object SidecarNaming {
    data class Named(val title: String, val artist: String?, val album: String?, val payloadFormat: String?)

    fun read(file: File): Named? = try {
        if (!file.isFile) null else JSONObject(file.readText(Charsets.UTF_8)).let { json ->
            val meta = json.optString("ncmMetadataJson").takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val display = json.optString("displayName").substringBeforeLast('.').takeIf { it.isNotBlank() }
            val title = meta?.optString("musicName")?.takeIf { it.isNotBlank() } ?: display ?: return null
            val artist = meta?.optJSONArray("artist")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONArray(i)?.optString(0)?.takeIf { it.isNotBlank() }
                }.joinToString("/").takeIf { it.isNotBlank() }
            }
            Named(title, artist, meta?.optString("album")?.takeIf { it.isNotBlank() }, json.optString("payloadFormat").takeIf { it.isNotBlank() })
        }
    } catch (_: Exception) { null }
}
