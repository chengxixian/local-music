// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app

import android.app.Application
import com.localmusic.app.data.LibraryRepository

class LocalMusicApplication : Application() {
    val library by lazy { LibraryRepository(this) }
    /** 整个进程只连一次 MediaController；进程退出时由系统回收。 */
    val player by lazy { PlayerConnection(this) }
    override fun onCreate() { super.onCreate(); library.scan() }
}
