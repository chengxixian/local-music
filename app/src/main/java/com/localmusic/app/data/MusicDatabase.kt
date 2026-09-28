// SPDX-License-Identifier: GPL-3.0-or-later
package com.localmusic.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 曲库数据库。
 *
 * v1 → v2：新增 `covers` 表（用户自定义封面）。
 * v2 → v3：新增 `favorites` 表（我喜欢的音乐）。
 * 迁移都是**增量**的：用户数据（收藏、自选封面）升级时不能清。
 */
class MusicDatabase(context: Context) : SQLiteOpenHelper(context, "library.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE songs (uri TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, duration INTEGER NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, format TEXT NOT NULL, sampleRate INTEGER NOT NULL, bitDepth INTEGER NOT NULL, channels INTEGER NOT NULL, origin TEXT NOT NULL, artwork TEXT)")
        db.execSQL("CREATE INDEX song_origin ON songs(origin)")
        createCovers(db)
        createFavorites(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCovers(db)
        if (oldVersion < 3) createFavorites(db)
    }

    private fun createCovers(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS covers (uri TEXT PRIMARY KEY, path TEXT NOT NULL, updated INTEGER NOT NULL)")
    }

    private fun createFavorites(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites (uri TEXT PRIMARY KEY, added INTEGER NOT NULL)")
    }

    fun all(): List<Song> = readableDatabase.rawQuery("SELECT * FROM songs ORDER BY title COLLATE NOCASE", null).use { c ->
        buildList { while (c.moveToNext()) {
            fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name)).orEmpty()
            fun l(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
            add(Song(s("uri"), s("title"), s("artist"), s("album"), l("duration"), l("size"), l("modified"), s("format"),
                l("sampleRate").toInt(), l("bitDepth").toInt(), l("channels").toInt(), s("origin"), s("artwork").ifBlank { null }))
        } }
    }

    // Replace ONLY successfully traversed origins. Permission errors must never wipe the catalog.
    fun merge(songs: List<Song>, completeOrigins: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            completeOrigins.forEach { db.delete("songs", "origin = ?", arrayOf(it)) }
            songs.forEach { s -> db.insertWithOnConflict("songs", null, android.content.ContentValues().apply {
                put("uri", s.uri); put("title", s.title); put("artist", s.artist); put("album", s.album)
                put("duration", s.duration); put("size", s.size); put("modified", s.modified); put("format", s.format)
                put("sampleRate", s.sampleRate); put("bitDepth", s.bitDepth); put("channels", s.channels)
                put("origin", s.origin); put("artwork", s.artwork)
            }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 用户自选封面：song.uri → 本地图片文件路径。 */
    fun covers(): Map<String, String> = readableDatabase
        .rawQuery("SELECT uri, path FROM covers", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    fun setCover(uri: String, path: String?) {
        val db = writableDatabase
        if (path == null) db.delete("covers", "uri = ?", arrayOf(uri))
        else db.insertWithOnConflict("covers", null, android.content.ContentValues().apply {
            put("uri", uri); put("path", path); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 我喜欢的音乐（按加入时间倒序，界面直接按这个顺序展示）。 */
    fun favorites(): List<String> = readableDatabase
        .rawQuery("SELECT uri FROM favorites ORDER BY added DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun setFavorite(uri: String, favorite: Boolean) {
        val db = writableDatabase
        if (favorite) db.insertWithOnConflict("favorites", null, android.content.ContentValues().apply {
            put("uri", uri); put("added", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        else db.delete("favorites", "uri = ?", arrayOf(uri))
    }
}
