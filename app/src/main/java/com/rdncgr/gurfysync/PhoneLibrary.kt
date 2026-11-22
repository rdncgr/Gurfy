package com.rdncgr.gurfysync

import android.content.Context
import com.rdncgr.gurfy.shared.Album
import com.rdncgr.gurfy.shared.Artist
import com.rdncgr.gurfy.shared.Genre
import com.rdncgr.gurfy.shared.Library
import com.rdncgr.gurfy.shared.PhoneMusic
import com.rdncgr.gurfy.shared.SearchResult
import com.rdncgr.gurfy.shared.Song
import com.rdncgr.gurfy.shared.md5
import java.text.Collator
import java.util.Locale

/**
 * Navidrome kütüphanesine telefonun kendi müziklerini katar: Şarkılar, Sanatçılar, Türler, arama,
 * son eklenenler ve sana özel karışık telefon şarkılarını da gösterir.
 * Aynı şarkı (sanatçı + ad) sunucuda da varsa yalnızca sunucudaki görünür.
 * Beğeni, Navidrome listeleri ve Last.fm yalnızca sunucudaki şarkılar için çalışır.
 */
class PhoneLibrary(private val ctx: Context, private val base: Library) : Library by base {
    private val collator get() = Collator.getInstance(Locale("tr"))

    // Sunucudaki şarkıların anahtarları (yinelenenleri ayıklamak için); 10 dakika bellekte
    @Volatile private var serverKeys: Pair<Long, Set<String>>? = null

    private fun serverKeys(): Set<String> {
        serverKeys?.let { (t, keys) -> if (System.currentTimeMillis() - t < 10 * 60_000) return keys }
        val keys = try { base.allSongs().map(PhoneMusic::keyOf).toSet() } catch (e: Exception) { return emptySet() }
        serverKeys = System.currentTimeMillis() to keys
        return keys
    }

    /** Sunucuda karşılığı olmayan telefon şarkıları. */
    private fun phoneOnly(): List<PhoneMusic.Item> {
        val items = try { PhoneMusic.items(ctx) } catch (e: Exception) { emptyList() }
        if (items.isEmpty()) return items
        val server = serverKeys()
        return items.filter { PhoneMusic.keyOf(it.song) !in server }
    }

    private fun phoneSongs() = phoneOnly().map { it.song }

    private fun artistId(name: String) = "phone-artist:" + md5(PhoneMusic.norm(name)).take(16)

    private fun albumsOf(songs: List<Song>): List<Album> =
        songs.groupBy { it.albumId }.map { (id, list) ->
            val first = list.first()
            Album(id ?: "", first.album.ifBlank { "Telefondaki şarkılar" }, first.artist, first.coverArt, list.size)
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }

    // Sunucudaki sanatçının adı: sanatçı sayfasında o sanatçının telefon şarkılarını da göstermek için
    private val artistNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ------------------------------------------------------------------ kütüphane

    override fun allSongs(max: Int): List<Song> {
        val server = base.allSongs(max)
        val keys = server.map(PhoneMusic::keyOf).toSet()
        serverKeys = System.currentTimeMillis() to keys
        val phone = (try { PhoneMusic.songs(ctx) } catch (e: Exception) { emptyList() }).filter { PhoneMusic.keyOf(it) !in keys }
        if (phone.isEmpty()) return server
        return (server + phone).sortedWith { a, b -> collator.compare(a.title, b.title) }.take(max)
    }

    override fun search(query: String): SearchResult {
        val r = base.search(query)
        val q = query.trim().lowercase(Locale("tr"))
        if (q.isEmpty()) return r
        val have = r.songs.map(PhoneMusic::keyOf).toSet()
        val hits = phoneSongs().filter {
            PhoneMusic.keyOf(it) !in have &&
                (q in it.title.lowercase(Locale("tr")) || q in it.artist.lowercase(Locale("tr")) || q in it.album.lowercase(Locale("tr")))
        }
        return if (hits.isEmpty()) r else r.copy(songs = r.songs + hits.take(50))
    }

    override fun artists(): List<Artist> {
        val server = base.artists()
        server.forEach { artistNames[it.id] = it.name }
        val known = server.map { PhoneMusic.norm(it.name) }.toSet()
        val phone = phoneSongs().groupBy { PhoneMusic.norm(it.artist) }
            .filterKeys { it !in known }
            .map { (_, songs) -> Artist(artistId(songs.first().artist), songs.first().artist, songs.map { it.albumId }.distinct().size) }
        if (phone.isEmpty()) return server
        return (server + phone).sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    override fun artistAlbums(id: String): List<Album> {
        if (id.startsWith("phone-artist:")) return albumsOf(phoneSongs().filter { artistId(it.artist) == id })
        val server = base.artistAlbums(id)
        val name = artistNames[id] ?: return server
        val phone = phoneSongs().filter { PhoneMusic.norm(it.artist) == PhoneMusic.norm(name) }
        return server + albumsOf(phone)
    }

    override fun album(id: String): Pair<Album, List<Song>> {
        if (!id.startsWith("phone-album:")) return base.album(id)
        val songs = phoneSongs().filter { it.albumId == id }
        val a = albumsOf(songs).firstOrNull() ?: throw java.io.IOException("Albüm bulunamadı")
        return a to songs
    }

    private fun genresOf(s: Song) = s.genre.orEmpty().split(';', ',', '/').map { it.trim() }.filter { it.isNotEmpty() }

    override fun genres(): List<Genre> {
        val server = base.genres()
        val phone = phoneSongs().flatMap { s -> genresOf(s).map { it to s } }.groupBy({ it.first.lowercase(Locale("tr")) }, { it })
        if (phone.isEmpty()) return server
        val byName = server.associateBy { it.name.lowercase(Locale("tr")) }.toMutableMap()
        for ((key, pairs) in phone) {
            val add = pairs.size
            val albums = pairs.map { it.second.albumId }.distinct().size
            val g = byName[key]
            byName[key] = if (g != null) g.copy(songCount = g.songCount + add, albumCount = g.albumCount + albums)
            else Genre(pairs.first().first, add, albums)
        }
        return byName.values.sortedByDescending { it.songCount }
    }

    override fun songsByGenre(genre: String, count: Int): List<Song> {
        val g = genre.lowercase(Locale("tr"))
        val phone = phoneSongs().filter { s -> genresOf(s).any { it.lowercase(Locale("tr")) == g } }
        return (base.songsByGenre(genre, count) + phone).take(count)
    }

    override fun songList(kind: String, size: Int): List<Song> {
        val server = base.songList(kind, size)
        if (kind != "newest") return server
        // Son 30 günde telefona gelen şarkılar sunucudaki son eklenenlerle sırayla karışır
        val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val phone = phoneOnly().filter { it.added >= since }.sortedByDescending { it.added }.map { it.song }
        if (phone.isEmpty()) return server
        val out = ArrayList<Song>(size)
        val a = server.iterator()
        val b = phone.iterator()
        while (out.size < size && (a.hasNext() || b.hasNext())) {
            if (a.hasNext()) out += a.next()
            if (b.hasNext() && out.size < size) out += b.next()
        }
        return out
    }

    override fun randomSongs(size: Int): List<Song> {
        val phone = phoneSongs()
        if (phone.isEmpty()) return base.randomSongs(size)
        // Telefon şarkıları kütüphanedeki paylarına göre, en çok dörtte bir
        val total = (serverKeys?.second?.size ?: 0) + phone.size
        val share = (size * phone.size / total.coerceAtLeast(1)).coerceIn(1, size / 4 + 1)
        return (base.randomSongs(size - share) + phone.shuffled().take(share)).shuffled()
    }

    // Arayüz varsayılanı; kendi randomSongs / songsByGenre / songList'imizi kullansın diye
    override fun smartMix(size: Int, exclude: Set<String>): List<Song> = super.smartMix(size, exclude)

    override fun phoneDeleted(ids: List<String>) {
        PhoneMusic.invalidate()
    }

    override fun phoneAccessChanged() {
        PhoneMusic.invalidate()
    }
}
