package com.rdncgr.gurfy

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

// Navidrome'un ve Gurfy servisinin tuttuğu bilgilerin telefondaki karşılığı.
// Şarkı dosyaları Müzik/Gurfy klasöründe (MediaStore); burada yalnızca bilgileri durur.

/** Kütüphanedeki şarkı. id = "ms:<MediaStore kimliği>". */
@Entity(tableName = "songs", indices = [Index("youtubeId")])
data class SongRow(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val duration: Int,
    val mediaUri: String,
    /** Müzik/Gurfy altındaki yolu (Sanatçı/Albüm/), bilgi için. */
    val relPath: String?,
    /** Kapak: file:// (indirirken kaydedilen) ya da content:// (MediaStore albüm kapağı). */
    val cover: String?,
    val youtubeId: String?,
    val starred: Boolean = false,
    val starredAt: Long = 0,
    val playCount: Int = 0,
    val lastPlayed: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlists")
data class PlaylistRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist_items", indices = [Index("playlistId")])
data class PlaylistItemRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val playlistId: Long,
    val position: Int,
    val songId: String,
)

/** "Radyolarım": kullanıcının favorilere eklediği radyolar (Navidrome'daki internet radyoları). */
@Entity(tableName = "radios")
data class RadioRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val homepage: String = "",
)

/** İndirme kuyruğu (sunucudaki indirmeler.json). status: queued, downloading, done, exists, error. */
@Entity(tableName = "jobs")
data class JobRow(
    @PrimaryKey val id: String,
    val artist: String,
    val title: String,
    val album: String?,
    val url: String?,
    val status: String = "queued",
    val message: String = "",
    val created: Long = System.currentTimeMillis(),
    val retries: Int = 0,
    /** Bot engelinde bekleme: bu zamandan önce denenmez. */
    val notBefore: Long = 0,
)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs")
    fun all(): List<SongRow>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun get(id: String): SongRow?

    @Upsert
    fun upsert(s: SongRow)

    @Query("DELETE FROM songs WHERE id = :id")
    fun delete(id: String)

    @Query("UPDATE songs SET starred = :on, starredAt = :at WHERE id = :id")
    fun star(id: String, on: Boolean, at: Long)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayed = :time WHERE id = :id AND lastPlayed != :time")
    fun played(id: String, time: Long)

    @Query("UPDATE songs SET genre = :genre WHERE id = :id")
    fun setGenre(id: String, genre: String?)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun all(): List<PlaylistRow>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position")
    fun items(id: Long): List<PlaylistItemRow>

    @Insert
    fun insert(p: PlaylistRow): Long

    @Insert
    fun insertItems(items: List<PlaylistItemRow>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    fun clearItems(id: Long)

    @Query("DELETE FROM playlist_items WHERE songId = :songId")
    fun removeSongEverywhere(songId: String)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    fun delete(id: Long)
}

@Dao
interface RadioDao {
    @Query("SELECT * FROM radios ORDER BY name COLLATE NOCASE")
    fun all(): List<RadioRow>

    @Insert
    fun insert(r: RadioRow): Long

    @Query("DELETE FROM radios WHERE id = :id")
    fun delete(id: Long)
}

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY created DESC LIMIT 100")
    fun recent(): List<JobRow>

    @Query("SELECT * FROM jobs WHERE status = 'queued' AND notBefore <= :now ORDER BY created LIMIT 1")
    fun nextQueued(now: Long): JobRow?

    @Query("SELECT COUNT(*) FROM jobs WHERE status = 'queued' OR status = 'downloading'")
    fun activeCount(): Int

    @Query("SELECT MIN(notBefore) FROM jobs WHERE status = 'queued'")
    fun earliestQueued(): Long?

    @Upsert
    fun upsert(j: JobRow)

    @Upsert
    fun upsertAll(j: List<JobRow>)

    @Query("UPDATE jobs SET status = :status, message = :message WHERE id = :id")
    fun setStatus(id: String, status: String, message: String)

    @Query("DELETE FROM jobs WHERE id IN (:ids) AND status != 'downloading'")
    fun delete(ids: List<String>)

    @Query("DELETE FROM jobs WHERE status IN ('done', 'exists', 'error')")
    fun deleteFinished()

    @Query("UPDATE jobs SET status = 'queued', message = '', retries = 0, notBefore = 0 WHERE status = 'error'")
    fun retryFailed()

    /** Uygulama kapanırken yarım kalanlar yeniden sıraya. */
    @Query("UPDATE jobs SET status = 'queued', message = '' WHERE status = 'downloading'")
    fun requeueInterrupted()

    @Query("DELETE FROM jobs WHERE status IN ('done', 'exists') AND id NOT IN (SELECT id FROM jobs ORDER BY created DESC LIMIT 300)")
    fun prune()
}

@Database(
    entities = [SongRow::class, PlaylistRow::class, PlaylistItemRow::class, RadioRow::class, JobRow::class],
    version = 1,
)
abstract class GurfyDb : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun playlists(): PlaylistDao
    abstract fun radios(): RadioDao
    abstract fun jobs(): JobDao
}
