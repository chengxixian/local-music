// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app

import android.app.Application
import com.localmusic.app.data.LibraryRepository

class LocalMusicApplication : Application() {
    val library by lazy { LibraryRepository(this) }
    /** 整个进程只连一次 MediaController；进程退出时由系统回收。 */
    val player by lazy { PlayerConnection(this) }
    override fun onCreate() {
        super.onCreate()
        // ⚠️ 顺序是硬要求：云备份恢复只是"解包到暂存"，真正落盘必须发生在
        // `library`（会打开 SQLite）与任何 `getSharedPreferences` 之前 ——
        // 一旦数据库连接已经开着，覆盖 library.db 会被连接缓存/回滚日志反写回去，等于没恢复。
        com.localmusic.app.data.BackupManager.applyPendingRestore(this)
        library.scan()
    }
}
