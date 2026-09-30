// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.audio.ncm

import org.json.JSONObject

/**
 * 给导出的 FLAC 起个人看得懂的名字：优先「艺术家 - 歌名」，退化到原 ncm 文件名。
 *
 * 为什么不能只用哈希：导出物在用户自己的文件夹里，用户会拿文件管理器去看它。
 * 转码路径写出的 FLAC 不带 Vorbis 注释，文件名就是它唯一的"标签"。
 */
object NcmNaming {
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun sanitizeFileName(raw: String, extension: String = "flac"): String {
        val cleaned = illegal.replace(raw, "_").trim().trimEnd('.')
        val limited = if (cleaned.length > 120) cleaned.take(120) else cleaned
        val name = limited.ifBlank { "ncm" }
        // 回退产物可能是 mp3 / m4a，扩展名要跟着实际输出走
        val ext = extension.removePrefix(".").lowercase().ifBlank { "flac" }
        return if (name.endsWith(".$ext", true)) name else "$name.$ext"
    }

    /**
     * @param ncmMetadataJson NcmFileStore 写出的侧车里那份原始元数据（可能为 null）
     * @param fallback 原 ncm 的显示名
     * @param extension 实际输出格式的扩展名（flac / mp3 / m4a）
     */
    fun exportName(ncmMetadataJson: String?, fallback: String, extension: String = "flac"): String {
        val meta = ncmMetadataJson?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val title = meta?.optString("musicName")?.takeIf { it.isNotBlank() }
            ?: fallback.substringBeforeLast('.')
        val artist = meta?.optJSONArray("artist")?.let { array ->
            (0 until array.length()).mapNotNull { i -> array.optJSONArray(i)?.optString(0)?.takeIf { it.isNotBlank() } }
                .joinToString("/").takeIf { it.isNotBlank() }
        }
        val stem = if (artist.isNullOrBlank()) title else "$artist - $title"
        return sanitizeFileName(stem, extension)
    }
}
