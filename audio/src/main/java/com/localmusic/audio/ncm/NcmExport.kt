// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.audio.ncm

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 把转好的 FLAC 发布到**用户自己选的文件夹**（SAF）。
 *
 * 为什么不直接写到 filesDir：导出物应该留在用户能看见、能自己管理的地方，
 * 而且**卸载应用不会把它们一起清掉**。应用私有目录只作为「还没选文件夹」时的暂存兜底。
 *
 * 落盘方式：先写成 `.ncm-tmp-…` 再 `renameDocument` 改成正式名 —— 中途被杀掉时留下的是
 * 一个显式可识别的临时文件（下次会清），而不是一个看起来正常、其实播不了的半成品。
 */
object NcmExport {
    private const val TAG = "NcmExport"
    const val PREF_TREE = "exportTree"
    const val PREF_LABEL = "exportLabel"

    fun tree(context: Context): Uri? =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(PREF_TREE, null)?.let(Uri::parse)

    /** 同名文件已存在时直接复用，避免重复写盘。 */
    fun find(context: Context, name: String): Uri? {
        val tree = tree(context) ?: return null
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return try {
            context.contentResolver.query(
                parent,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询导出目录失败：${e.message}"); null
        }
    }

    /** 发布成功返回文档 Uri；未设置导出目录返回 null（调用方回落到私有目录）。 */
    fun publish(context: Context, source: File, displayName: String, mime: String = "audio/flac"): Uri? {
        val tree = tree(context) ?: return null
        val resolver = context.contentResolver
        val safeName = NcmNaming.sanitizeFileName(displayName, source.extension.ifBlank { "flac" })
        find(context, safeName)?.let { return it }

        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val tempName = ".ncm-tmp-${System.currentTimeMillis()}-$safeName"
        var document: Uri? = null
        try {
            document = DocumentsContract.createDocument(resolver, parent, mime, tempName)
                ?: throw IOException("导出目录不允许创建文件")
            resolver.openOutputStream(document, "w")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法写入导出文件")
            // 校验写入完整性（provider 会记录 size）
            val written = sizeOf(resolver, document)
            if (written in 1 until source.length()) {
                throw IOException("导出文件不完整：$written / ${source.length()}")
            }
            val renamed = try {
                DocumentsContract.renameDocument(resolver, document, safeName)
            } catch (e: Exception) {
                Log.w(TAG, "重命名导出文件失败（保留原名）：${e.message}"); null
            }
            return renamed ?: document
        } catch (e: Exception) {
            document?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            Log.w(TAG, "导出失败：${e.message}")
            return null
        }
    }

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }

    /** 清理上次中断留下的临时文件。 */
    fun cleanTemp(context: Context) {
        val tree = tree(context) ?: return
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        try {
            resolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                val stale = buildList {
                    while (c.moveToNext()) if (c.getString(1).startsWith(".ncm-tmp-")) add(c.getString(0))
                }
                stale.forEach { id ->
                    runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
                }
            }
        } catch (_: Exception) { }
    }
}
