// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import com.localmusic.app.R
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查 GitHub 上的新版本。
 *
 * 版本信息读的是仓库里的 **`version.json`**（raw.githubusercontent.com），**不用 GitHub REST API**：
 * 未认证的 API 每小时只有 60 次、按 IP 计（真机实测直接 403 rate limit），
 * 而 raw 文件不吃这个配额，且内容完全由我们自己控制。
 *
 * APK 走 Release 附件（`apkUrl`），下载是普通 HTTPS，同样不受 API 配额影响。
 */
object UpdateChecker {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/chengxixian/local-music/main/version.json"
    private const val TIMEOUT_MS = 8000

    data class Update(
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    data class Status(
        val checking: Boolean = false,
        val message: String = "",
        val update: Update? = null,
        val downloading: Boolean = false,
        /** 下载进度 0f~1f（总大小未知时按已下载字节 + 期望大小估算）。 */
        val progress: Float = 0f,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 本机版本（去空格），用来和 tag 比对。 */
    private fun localVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 逐段比数字：远端 > 本地 → true。 */
    internal fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        val r = parts(remote)
        val l = parts(local)
        if (r.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 用户点了"以后再说"：只收起提示，下次启动还会再查（不记住"已忽略"，避免永远收不到更新）。 */
    fun dismiss() { _status.value = _status.value.copy(update = null) }

    suspend fun check(context: Context, silent: Boolean = false) = withContext(Dispatchers.IO) {
        if (_status.value.checking) return@withContext
        _status.value = _status.value.copy(checking = true, message = context.getString(R.string.update_checking), error = null)
        try {
            val body = get(MANIFEST_URL) ?: throw IllegalStateException(context.getString(R.string.update_net_fail))
            val json = JSONObject(body)
            val remote = json.optString("version")
            val apkUrl = json.optString("apkUrl")
            val size = json.optLong("size")
            val local = localVersion(context)
            val update = if (apkUrl.isNotBlank() && remote.isNotBlank() && isNewer(remote, local)) {
                Update("v${remote.removePrefix("v")}", json.optString("notes").take(4000), apkUrl, size)
            } else null
            _status.value = Status(
                checking = false,
                message = if (update != null) context.getString(R.string.update_found_long, update.tag, local) else context.getString(R.string.update_up_to_date, local),
                update = update,
            )
        } catch (e: Exception) {
            _status.value = Status(checking = false, message = if (silent) "" else context.getString(R.string.update_check_failed, e.message), error = e.message)
        }
    }

    /**
     * 用系统 DownloadManager 把 APK 下到应用私有目录，完成后拉起系统安装器。
     * 走 DownloadManager 而不是自己写流：它有断点续传、通知栏进度，也不占我们的线程。
     */
    fun startDownload(context: Context, update: Update, onDone: (File) -> Unit) {
        _status.value = _status.value.copy(downloading = true, progress = 0f, downloadedBytes = 0L, totalBytes = update.sizeBytes, error = null)
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "local-music-${update.tag}.apk")
        if (target.isFile && target.length() > 0) {
            _status.value = _status.value.copy(downloading = false)
            onDone(target)
            return
        }
        runCatching {
            val request = DownloadManager.Request(Uri.parse(update.apkUrl)).apply {
                setTitle("local music ${update.tag}")
                setDescription(context.getString(R.string.update_downloading_new))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setDestinationInExternalFilesDir(context, null, "updates/${target.name}")
            }
            val manager = context.getSystemService(DownloadManager::class.java)
            val id = manager?.enqueue(request) ?: throw IllegalStateException(context.getString(R.string.update_download_service_unavailable))
            // 轮询：既判断完成，也把**进度**报给界面（500ms 一次，够顺滑而且几乎不耗电）
            val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
            scope.launch {
                repeat(2400) {
                    kotlinx.coroutines.delay(500)
                    val snapshot = runCatching {
                        manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                            if (!c.moveToFirst()) return@use null
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            Triple(status, done, total)
                        }
                    }.getOrElse {
                        _status.value = _status.value.copy(downloading = false, error = it.message)
                        return@launch
                    } ?: return@repeat
                    val (downloadStatus, done, total) = snapshot
                    val expected = if (total > 0) total else update.sizeBytes
                    _status.value = _status.value.copy(
                        downloading = downloadStatus != DownloadManager.STATUS_SUCCESSFUL &&
                            downloadStatus != DownloadManager.STATUS_FAILED,
                        downloadedBytes = done,
                        totalBytes = expected,
                        progress = if (expected > 0) (done.toFloat() / expected).coerceIn(0f, 1f) else 0f,
                    )
                    when (downloadStatus) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            _status.value = _status.value.copy(downloading = false, progress = 1f, message = context.getString(R.string.update_download_done))
                            withContext(Dispatchers.Main) { onDone(File(dir, target.name)) }
                            return@launch
                        }
                        DownloadManager.STATUS_FAILED -> {
                            _status.value = _status.value.copy(downloading = false, error = "下载失败（可稍后重试）")
                            return@launch
                        }
                    }
                }
            }
        }.onFailure {
            _status.value = _status.value.copy(downloading = false, error = it.message ?: "下载失败")
        }
    }

    /** 拉起系统安装界面（需要用户允许"安装未知应用"）。 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "local-music-update-checker")
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
