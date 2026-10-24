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
 * v3 → v4：`covers` 加 `source`（区分用户自选 / 自动刮削）。
 * v4 → v5：新增 `playlists` 与 `playlist_songs`（乐单）。
 * 迁移都是**增量**的：用户数据（收藏、自选封面、乐单）升级时不能清。
 */
/**
 * 封面来源。区分它是因为：**用户自己设的封面永远不能被自动逻辑清掉**，
 * 而刮削下载的封面在"这首歌本来就有封面"时应该被撤掉、让原图重新显示。
 */
object CoverSource {
    const val USER = "user"
    const val SCRAPED = "scraped"
}

/** 一个乐单。`cover` 是本地图片路径（用户自选的封面），为空则用第一首歌的封面。 */
data class Playlist(val id: Long, val name: String, val cover: String?, val count: Int)

class MusicDatabase(context: Context) : SQLiteOpenHelper(context, "library.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE songs (uri TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, duration INTEGER NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, format TEXT NOT NULL, sampleRate INTEGER NOT NULL, bitDepth INTEGER NOT NULL, channels INTEGER NOT NULL, origin TEXT NOT NULL, artwork TEXT, replayGainDb REAL)")
        db.execSQL("CREATE INDEX song_origin ON songs(origin)")
        createCovers(db)
        createFavorites(db)
        createPlaylists(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCovers(db)
        if (oldVersion < 3) createFavorites(db)
        // v4：区分"用户自己设的封面"和"自动刮削下载的封面"，前者永远不许被自动清理
        if (oldVersion < 4) runCatching { db.execSQL("ALTER TABLE covers ADD COLUMN source TEXT NOT NULL DEFAULT 'scraped'") }
        if (oldVersion < 5) createPlaylists(db)
        if (oldVersion < 6) runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN replayGainDb REAL") }
    }

    private fun createCovers(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS covers (uri TEXT PRIMARY KEY, path TEXT NOT NULL, updated INTEGER NOT NULL, source TEXT NOT NULL DEFAULT 'scraped')")
    }

    private fun createFavorites(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites (uri TEXT PRIMARY KEY, added INTEGER NOT NULL)")
    }

    private fun createPlaylists(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, cover TEXT, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS playlist_songs (playlist INTEGER NOT NULL, uri TEXT NOT NULL, added INTEGER NOT NULL, PRIMARY KEY(playlist, uri))")
    }

    /**
     * 曲库里的歌（界面用这份）。
     *
     * **排除 `media:%` 来源**：那是旧版"整机媒体库"扫描留下的条目，现在已经不再扫描它。
     * 这些行会短暂留在库里当"迁移素材"（收藏/乐单/封面还按老 URI 记着），但对用户**不可见** ——
     * 等用户选定文件夹、扫描能匹配上之后再改键并删除（见 [LibraryRepository.doScan]）。
     */
    fun all(): List<Song> = readSongs(
        "SELECT * FROM songs WHERE origin NOT LIKE 'media:%' ORDER BY title COLLATE NOCASE", null,
    )

    /**
     * 某个来源前缀下的全部行。
     *
     * 用途：曲库来源从「整机媒体库」改成「用户选定的文件夹」后，老的 `media:*` 行要
     * 一次性对账/迁移（把收藏、乐单、自选封面改键到新的文件夹条目上），迁完就删掉。
     */
    fun songsByOriginPrefix(prefix: String): List<Song> =
        readSongs("SELECT * FROM songs WHERE origin LIKE ?", arrayOf("$prefix%"))

    private fun readSongs(sql: String, args: Array<String>?): List<Song> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) {
                fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name)).orEmpty()
                fun l(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
                add(Song(s("uri"), s("title"), s("artist"), s("album"), l("duration"), l("size"), l("modified"), s("format"),
                    l("sampleRate").toInt(), l("bitDepth").toInt(), l("channels").toInt(), s("origin"), s("artwork").ifBlank { null },
                    c.getString(c.getColumnIndexOrThrow("replayGainDb"))?.toFloatOrNull()))
            } }
        }

    /** 删除某个来源前缀的所有行（例如已经不再使用的 `media:` 整机媒体库条目）。 */
    fun deleteOriginsLike(prefix: String) {
        writableDatabase.delete("songs", "origin LIKE ?", arrayOf("$prefix%"))
    }

    /**
     * 把「用户数据」里按 uri 记录的三张表从 [oldUri] 改键到 [newUri]。
     *
     * 只影响收藏 / 自选封面 / 乐单成员；`playlist_songs` 逐乐单处理，避免把新 uri
     * 从"本来就有它"的乐单里误删。
     */
    fun rekey(oldUri: String, newUri: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("favorites", "uri = ?", arrayOf(newUri))
            db.execSQL("UPDATE favorites SET uri = ? WHERE uri = ?", arrayOf(newUri, oldUri))

            db.delete("covers", "uri = ?", arrayOf(newUri))
            db.execSQL("UPDATE covers SET uri = ? WHERE uri = ?", arrayOf(newUri, oldUri))

            // 只在"老 uri 所在的那几个乐单"里改键，避免波及同名的其它乐单
            db.delete(
                "playlist_songs",
                "uri = ? AND playlist IN (SELECT playlist FROM playlist_songs WHERE uri = ?)",
                arrayOf(newUri, oldUri),
            )
            db.execSQL("UPDATE playlist_songs SET uri = ? WHERE uri = ?", arrayOf(newUri, oldUri))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
                if (s.replayGainDb == null) putNull("replayGainDb") else put("replayGainDb", s.replayGainDb)
            }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 用户自选封面：song.uri → 本地图片文件路径。 */
    fun covers(): Map<String, String> = readableDatabase
        .rawQuery("SELECT uri, path FROM covers", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    fun setCover(uri: String, path: String?, source: String = CoverSource.SCRAPED) {
        val db = writableDatabase
        if (path == null) db.delete("covers", "uri = ?", arrayOf(uri))
        else db.insertWithOnConflict("covers", null, android.content.ContentValues().apply {
            put("uri", uri); put("path", path); put("updated", System.currentTimeMillis()); put("source", source)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** uri → 封面来源（`user` 是用户自己设的，`scraped` 是自动刮削来的）。 */
    fun coverSources(): Map<String, String> = readableDatabase
        .rawQuery("SELECT uri, source FROM covers", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) ?: CoverSource.SCRAPED) }
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

    // ── 乐单 ──

    /** 全部乐单，按创建时间倒序（最新建的排前面，和"喜欢"的排序直觉一致）。 */
    fun playlists(): List<Playlist> = readableDatabase.rawQuery(
        "SELECT p.id, p.name, p.cover, (SELECT COUNT(*) FROM playlist_songs s WHERE s.playlist = p.id) AS n " +
            "FROM playlists p ORDER BY p.created DESC",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(Playlist(c.getLong(0), c.getString(1).orEmpty(), c.getString(2), c.getInt(3)))
            }
        }
    }

    fun createPlaylist(name: String): Long = writableDatabase.insert("playlists", null, android.content.ContentValues().apply {
        put("name", name); put("created", System.currentTimeMillis())
    })

    fun renamePlaylist(id: Long, name: String) {
        writableDatabase.update("playlists", android.content.ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))
    }

    fun setPlaylistCover(id: Long, path: String?) {
        writableDatabase.update("playlists", android.content.ContentValues().apply { put("cover", path) }, "id = ?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("playlist_songs", "playlist = ?", arrayOf(id.toString()))
            db.delete("playlists", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun playlistSongIds(id: Long): List<String> = readableDatabase
        .rawQuery("SELECT uri FROM playlist_songs WHERE playlist = ? ORDER BY added ASC", arrayOf(id.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** 这首歌被加进了哪些乐单（"加入乐单"面板里用来自动勾选）。 */
    fun playlistsOf(uri: String): Set<Long> = readableDatabase
        .rawQuery("SELECT playlist FROM playlist_songs WHERE uri = ?", arrayOf(uri)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    fun setPlaylistSong(playlist: Long, uri: String, member: Boolean) {
        val db = writableDatabase
        if (member) db.insertWithOnConflict("playlist_songs", null, android.content.ContentValues().apply {
            put("playlist", playlist); put("uri", uri); put("added", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        else db.delete("playlist_songs", "playlist = ? AND uri = ?", arrayOf(playlist.toString(), uri))
    }
}
