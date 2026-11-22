package com.rdncgr.gurfy

import android.content.Context
import com.rdncgr.gurfy.shared.Album
import com.rdncgr.gurfy.shared.Artist
import com.rdncgr.gurfy.shared.Genre
import com.rdncgr.gurfy.shared.Graph
import com.rdncgr.gurfy.shared.Library
import com.rdncgr.gurfy.shared.Playlist
import com.rdncgr.gurfy.shared.RadioStation
import com.rdncgr.gurfy.shared.SearchResult
import com.rdncgr.gurfy.shared.Song
import com.rdncgr.gurfy.shared.md5
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

/**
 * Navidrome'un telefondaki karşılığı: kütüphane Room'da, dosyalar Müzik/Gurfy klasöründe.
 * Arayüz bunu Navidrome'u kullandığı gibi kullanır (Graph.subsonic).
 */
class LocalLibrary(private val ctx: Context, private val db: GurfyDb) : Library {
    // Kütüphane küçük (en çok birkaç bin şarkı): bellekte tutulur, değişince yeniden okunur
    @Volatile private var cache: List<SongRow>? = null

    fun invalidate() { cache = null; Graph.listsVersion++ }

    private fun rows(): List<SongRow> = cache ?: db.songs().all().also { cache = it }

    private val trCollator get() = Collator.getInstance(Locale("tr"))

    private fun genres(r: SongRow): List<String> =
        r.genre.orEmpty().split(';', ',', '/').map { it.trim() }.filter { it.isNotEmpty() }

    fun albumKey(artist: String, album: String) = "al:" + md5(norm(artist) + "|" + norm(album)).take(16)
    private fun artistKey(artist: String) = "ar:" + md5(norm(artist)).take(16)

    fun toSong(r: SongRow) = Song(
        id = r.id, title = r.title, artist = r.artist, album = r.album,
        albumId = albumKey(r.artist, r.album), coverArt = r.cover, duration = r.duration,
        playCount = r.playCount, starred = r.starred, genre = r.genre,
    )

    private fun albumsOf(list: List<SongRow>): List<Pair<Album, List<SongRow>>> =
        list.groupBy { albumKey(it.artist, it.album) }.map { (id, songs) ->
            val first = songs.minByOrNull { it.addedAt }!!
            Album(id, first.album.ifBlank { first.title }, first.artist, songs.firstNotNullOfOrNull { it.cover }, songs.size) to songs
        }

    // ------------------------------------------------------------------ kütüphane

    override fun ping() {}

    override fun albums(type: String, size: Int): List<Album> {
        val all = albumsOf(rows())
        val sorted = when (type) {
            "newest" -> all.sortedByDescending { (_, s) -> s.maxOf { it.addedAt } }
            "recent" -> all.filter { (_, s) -> s.any { it.lastPlayed > 0 } }.sortedByDescending { (_, s) -> s.maxOf { it.lastPlayed } }
            "frequent" -> all.filter { (_, s) -> s.any { it.playCount > 0 } }.sortedByDescending { (_, s) -> s.sumOf { it.playCount } }
            "starred" -> all.filter { (_, s) -> s.any { it.starred } }
            "random" -> all.shuffled()
            else -> all.sortedWith { a, b -> trCollator.compare(a.first.name, b.first.name) }
        }
        return sorted.take(size).map { it.first }
    }

    override fun album(id: String): Pair<Album, List<Song>> {
        val (a, songs) = albumsOf(rows()).firstOrNull { it.first.id == id } ?: throw java.io.IOException("Albüm bulunamadı")
        return a to songs.sortedBy { it.addedAt }.map(::toSong)
    }

    override fun artists(): List<Artist> =
        rows().groupBy { artistKey(it.artist) }.map { (id, songs) ->
            Artist(id, songs.first().artist, songs.map { norm(it.album) }.distinct().size)
        }.sortedWith { a, b -> trCollator.compare(a.name, b.name) }

    override fun artistAlbums(id: String): List<Album> =
        albumsOf(rows().filter { artistKey(it.artist) == id }).map { it.first }

    override fun allSongs(max: Int): List<Song> =
        rows().sortedWith { a, b -> trCollator.compare(a.title, b.title) }.take(max).map(::toSong)

    override fun search(query: String): SearchResult {
        val q = norm(query).ifEmpty { return SearchResult(emptyList(), emptyList(), emptyList()) }
        val all = rows()
        val songs = all.filter { q in norm(it.title) || q in norm(it.artist) || q in norm(it.album) }
        val artists = all.filter { q in norm(it.artist) }.groupBy { artistKey(it.artist) }
            .map { (id, s) -> Artist(id, s.first().artist, s.map { norm(it.album) }.distinct().size) }
        val albums = albumsOf(all.filter { q in norm(it.album) || q in norm(it.artist) }).map { it.first }
        return SearchResult(artists.take(10), albums.take(15), songs.take(40).map(::toSong))
    }

    // -------------------------------------------------------------------- listeler

    override fun playlists(): List<Playlist> {
        val byId = rows().associateBy { it.id }
        return db.playlists().all().map { p ->
            val items = db.playlists().items(p.id).mapNotNull { byId[it.songId] }
            Playlist(p.id.toString(), p.name, items.size, items.firstOrNull()?.cover)
        }
    }

    override fun playlistSongs(id: String): List<Song> {
        val byId = rows().associateBy { it.id }
        return db.playlists().items(id.toLong()).mapNotNull { byId[it.songId] }.map(::toSong)
    }

    override fun createPlaylist(name: String, songIds: List<String>) {
        val pid = db.playlists().insert(PlaylistRow(name = name.trim().ifEmpty { "Yeni liste" }))
        db.playlists().insertItems(songIds.mapIndexed { i, s -> PlaylistItemRow(playlistId = pid, position = i, songId = s) })
        Graph.listsVersion++
    }

    @Synchronized
    override fun addToPlaylist(playlistId: String, songIds: List<String>) {
        val pid = playlistId.toLong()
        val start = db.playlists().items(pid).size
        db.playlists().insertItems(songIds.mapIndexed { i, s -> PlaylistItemRow(playlistId = pid, position = start + i, songId = s) })
    }

    @Synchronized
    override fun removeFromPlaylist(playlistId: String, index: Int) {
        val pid = playlistId.toLong()
        val items = db.playlists().items(pid).toMutableList()
        if (index !in items.indices) return
        items.removeAt(index)
        db.playlists().clearItems(pid)
        db.playlists().insertItems(items.mapIndexed { i, it -> PlaylistItemRow(playlistId = pid, position = i, songId = it.songId) })
    }

    override fun renamePlaylist(playlistId: String, name: String) = db.playlists().rename(playlistId.toLong(), name.trim())

    override fun deletePlaylist(playlistId: String) {
        db.playlists().clearItems(playlistId.toLong())
        db.playlists().delete(playlistId.toLong())
    }

    // ----------------------------------------------------------------------- türler

    override fun genres(): List<Genre> {
        val count = HashMap<String, MutableList<SongRow>>()
        val label = HashMap<String, String>()
        rows().forEach { r -> genres(r).forEach { g -> count.getOrPut(g.lowercase()) { mutableListOf() } += r; label.putIfAbsent(g.lowercase(), g) } }
        return count.map { (k, s) -> Genre(label.getValue(k), s.size, s.map { albumKey(it.artist, it.album) }.distinct().size) }
            .sortedByDescending { it.songCount }
    }

    override fun songsByGenre(genre: String, count: Int): List<Song> =
        rows().filter { r -> genres(r).any { it.equals(genre, ignoreCase = true) } }.take(count).map(::toSong)

    // ----------------------------------------------------------------- radyolarım

    override fun radios(): List<RadioStation> = db.radios().all().map { RadioStation(it.id.toString(), it.name, it.url, it.homepage) }

    override fun createRadio(r: RadioStation) { db.radios().insert(RadioRow(name = r.name, url = r.url, homepage = r.homepage)) }

    override fun deleteRadio(id: String) = db.radios().delete(id.toLong())

    // ------------------------------------------------------------- şarkı listeleri

    override fun songList(kind: String, size: Int): List<Song> {
        val all = rows()
        val list = when (kind) {
            "recent" -> all.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }
            "frequent" -> all.filter { it.playCount > 0 }.sortedByDescending { it.playCount }
            else -> all.sortedByDescending { it.addedAt }
        }
        return list.take(size).map(::toSong)
    }

    override fun randomSongs(size: Int): List<Song> = rows().shuffled().take(size).map(::toSong)

    override fun starred(): List<Song> = rows().filter { it.starred }.sortedByDescending { it.starredAt }.map(::toSong)

    override fun star(id: String, on: Boolean) {
        db.songs().star(id, on, System.currentTimeMillis())
        cache = null
    }

    /** Dinleme sayısı telefonda tutulur; Last.fm oturumu varsa oraya da gönderilir. */
    override fun scrobble(id: String, submission: Boolean, time: Long) {
        val r = db.songs().get(id) ?: return
        if (submission) {
            db.songs().played(id, time) // aynı dinleme iki kez sayılmaz (yeniden denemede)
            cache = null
            Lfm.scrobble(r.artist, r.title, r.album.ifBlank { null }, time)
        } else {
            Lfm.nowPlaying(r.artist, r.title, r.album.ifBlank { null }, r.duration)
        }
    }

    override fun streamUrl(id: String): String =
        MediaFiles.uriOf(id)?.toString() ?: throw java.io.IOException("Şarkı dosyası bulunamadı")

    override fun coverUrl(coverArt: String?, size: Int): String? = coverArt?.takeIf { it.isNotBlank() }

    // ---------------------------------------------------- servis için yardımcılar

    /** Sunucudaki library_song: başlık aynı, sanatçılardan biri ötekini içeriyorsa aynı şarkı. */
    fun find(artist: String, title: String): SongRow? {
        val t = norm(title)
        val a = norm(artist)
        if (t.isEmpty()) return null
        return rows().firstOrNull { r ->
            norm(r.title) == t && norm(r.artist).let { ra -> a.isEmpty() || ra.contains(a) || a.contains(ra) }
        }
    }

    fun findByYoutube(videoId: String): SongRow? = rows().firstOrNull { it.youtubeId == videoId }

    /** Her şarkıya kütüphanedeki karşılığını ekler: in_library ve song (sunucudaki with_library). */
    fun withLibrary(tracks: List<JSONObject>): List<JSONObject> {
        tracks.forEach { t ->
            val f = find(t.optString("artist"), t.optString("title"))
            t.put("in_library", f != null)
            t.put("song", f?.let(::brief) ?: JSONObject.NULL)
        }
        return tracks
    }

    private fun brief(r: SongRow): JSONObject = JSONObject().put("id", r.id).put("title", r.title).put("artist", r.artist)
        .put("album", r.album).put("albumId", albumKey(r.artist, r.album)).put("coverArt", r.cover ?: JSONObject.NULL)
        .put("duration", r.duration).put("genre", r.genre ?: JSONObject.NULL)

    fun add(r: SongRow) {
        db.songs().upsert(r)
        invalidate()
    }

    override fun phoneUri(song: Song): String? = MediaFiles.uriOf(song.id)?.toString()

    override fun phoneDeleted(ids: List<String>) {
        ids.forEach { db.songs().delete(it); db.playlists().removeSongEverywhere(it) }
        invalidate()
    }

    override fun phoneAccessChanged() = sync()

    fun remove(id: String) {
        db.songs().delete(id)
        db.playlists().removeSongEverywhere(id)
        invalidate()
    }

    fun setGenre(id: String, genre: String?) {
        db.songs().setGenre(id, genre)
        invalidate()
    }

    fun get(id: String): SongRow? = db.songs().get(id)
    fun all(): List<SongRow> = rows()

    /**
     * Telefonun müzikleriyle (Müzik/Gurfy ve izin varsa diğerleri) veritabanını uzlaştırır: klasörde olup veritabanında olmayanlar eklenir
     * (yeniden kurulum, bilgisayardan kopyalananlar), dosyası silinmiş olanlar çıkarılır.
     */
    fun sync() {
        val scanned = try { MediaFiles.scan(ctx) } catch (e: Exception) { return }
        val known = db.songs().all().associateBy { it.id }
        val onDisk = HashSet<String>()
        var changed = false
        for (s in scanned) {
            val id = MediaFiles.songId(s.id)
            onDisk += id
            if (id !in known) {
                db.songs().upsert(
                    SongRow(
                        id = id, title = s.title, artist = s.artist, album = s.album, genre = s.genre,
                        duration = s.duration, mediaUri = MediaFiles.uriOf(id).toString(), relPath = s.relPath,
                        cover = MediaFiles.albumArt(s.albumId), youtubeId = null, addedAt = s.added,
                    ),
                )
                changed = true
            }
        }
        // Yalnızca izin varken (bütün klasör görünürken) eksikleri sil; yoksa yeniden kurulumdan önceki
        // dosyalar görünmez ve yanlışlıkla silinirdi.
        val fullView = com.rdncgr.gurfy.shared.PhoneMusic.allowed(ctx)
        for ((id, r) in known) {
            if (id in onDisk) continue
            if (fullView || r.youtubeId != null && !exists(id)) {
                db.songs().delete(id)
                db.playlists().removeSongEverywhere(id)
                changed = true
            }
        }
        if (changed) invalidate()
    }

    private fun exists(id: String): Boolean = try {
        MediaFiles.uriOf(id)?.let { ctx.contentResolver.openFileDescriptor(it, "r")?.use { true } } ?: false
    } catch (e: Exception) { false }
}
