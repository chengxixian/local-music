// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backup bundle for the library database, preferences, lyrics and artwork caches. */
object BackupManager {
    fun export(context: Context, target: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(target)?.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                val files = mutableListOf<File>()
                File(context.dataDir, "databases/library.db").takeIf { it.isFile }?.let(files::add)
                File(context.dataDir, "shared_prefs/settings.xml").takeIf { it.isFile }?.let(files::add)
                File(context.filesDir, "lyrics").walkTopDown().filter { it.isFile }.forEach(files::add)
                File(context.filesDir, "covers").walkTopDown().filter { it.isFile }.forEach(files::add)
                files.forEach { file ->
                    val rel = when {
                        file.path.contains("shared_prefs") -> "shared_prefs/${file.name}"
                        file.path.contains("databases") -> "databases/${file.name}"
                        file.path.contains("\\lyrics\\") -> "lyrics/${file.name}"
                        else -> "covers/${file.name}"
                    }
                    zip.putNextEntry(ZipEntry(rel)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        } ?: error("无法打开备份目标")
    }.isSuccess

    fun restore(context: Context, source: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(source)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = when {
                        entry.name == "databases/library.db" -> File(context.dataDir, entry.name)
                        entry.name == "shared_prefs/settings.xml" -> File(context.dataDir, entry.name)
                        entry.name.startsWith("lyrics/") -> File(context.filesDir, entry.name)
                        entry.name.startsWith("covers/") -> File(context.filesDir, entry.name)
                        else -> null
                    } ?: continue
                    out.parentFile?.mkdirs(); FileOutputStream(out).use { zip.copyTo(it) }
                }
            }
        } ?: error("无法打开备份文件")
    }.isSuccess
}
