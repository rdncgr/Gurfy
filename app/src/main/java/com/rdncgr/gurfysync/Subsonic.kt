package com.rdncgr.gurfysync

import com.rdncgr.gurfy.shared.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Navidrome (Subsonic API) istemcisi: Gurfy Sync'in kütüphanesi. */
class Subsonic(private val s: Settings, private val cacheDir: File) : Library {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Bu çağrıların son yanıtı telefonda saklanır; sunucuya ulaşılamazsa son bilinen hali gösterilir.
     * Böylece listeler ve ana sayfa internet yokken de açılır.
     */
    private val cacheable = setOf("getAlbumList2", "getAlbum", "getPlaylists", "getPlaylist", "getStarred2", "getArtists", "getArtist", "getGenres", "getSongsByGenre")

    private fun cacheFile(endpoint: String, params: Array<out Pair<String, String>>): File {
        cacheDir.mkdirs()
        val key = endpoint + params.joinToString("&") { "${it.first}=${it.second}" } + "|" + s.navidromeUrl + "|" + s.user
        return File(cacheDir, md5(key) + ".json")
    }

    private fun fetch(endpoint: String, params: Array<out Pair<String, String>>): String {
        val req = Request.Builder().url(url(endpoint, *params)).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IOException("Sunucu hatası: ${resp.code}")
            return body
        }
    }

    // Sabit "salt": kapak adresleri değişmesin, önbellek çalışsın
    private val salt = "n0ta" + (System.currentTimeMillis() % 100000)

    fun url(endpoint: String, vararg params: Pair<String, String>): String {
        val sb = StringBuilder(s.navidromeUrl).append("/rest/").append(endpoint)
        sb.append("?u=").append(enc(s.user))
        sb.append("&t=").append(md5(s.pass + salt)).append("&s=").append(salt)
        sb.append("&v=1.16.1&c=gurfysync&f=json")
        for ((k, v) in params) sb.append('&').append(k).append('=').append(enc(v))
        return sb.toString()
    }

    private fun call(endpoint: String, vararg params: Pair<String, String>): JSONObject {
        if (!s.configured) throw IOException("Önce Ayarlar'dan Navidrome bilgilerini girin")
        val cache = if (endpoint in cacheable) cacheFile(endpoint, params) else null
        var fromNetwork = true
        val body = try {
            fetch(endpoint, params)
        } catch (e: IOException) {
            val saved = cache?.takeIf { it.exists() } ?: throw e
            fromNetwork = false
            saved.readText()
        }
        val r = JSONObject(body).getJSONObject("subsonic-response")
        if (r.optString("status") != "ok") {
            throw IOException(navidromeError(r.optJSONObject("error")))
        }
        if (fromNetwork && cache != null) {
            try { cache.writeText(body) } catch (e: Exception) { }
        }
        return r
    }

    override fun ping() { call("ping") }

    override fun albums(type: String, size: Int): List<Album> =
        call("getAlbumList2", "type" to type, "size" to size.toString())
            .optJSONObject("albumList2")?.list("album")?.map(::albumOf) ?: emptyList()

    override fun album(id: String): Pair<Album, List<Song>> {
        val a = call("getAlbum", "id" to id).getJSONObject("album")
        return albumOf(a) to a.list("song").map(::songOf)
    }

    override fun artists(): List<Artist> =
        call("getArtists").optJSONObject("artists")?.list("index")
            ?.flatMap { it.list("artist") }?.map(::artistOf) ?: emptyList()

    override fun artistAlbums(id: String): List<Album> =
        call("getArtist", "id" to id).optJSONObject("artist")?.list("album")?.map(::albumOf) ?: emptyList()

    /** Kütüphanedeki bütün şarkılar, ada göre (Navidrome boş aramada hepsini döndürür). */
    override fun allSongs(max: Int): List<Song> {
        val out = mutableListOf<Song>()
        var offset = 0
        while (out.size < max) {
            val page = call(
                "search3", "query" to "", "songCount" to "500", "songOffset" to offset.toString(),
                "albumCount" to "0", "artistCount" to "0",
            ).optJSONObject("searchResult3")?.list("song")?.map(::songOf) ?: emptyList()
            out += page
            if (page.size < 500) break
            offset += 500
        }
        val tr = java.text.Collator.getInstance(java.util.Locale("tr"))
        return out.sortedWith { a, b -> tr.compare(a.title, b.title) }
    }

    override fun search(query: String): SearchResult {
        val r = call("search3", "query" to query, "songCount" to "40", "albumCount" to "15", "artistCount" to "10")
            .optJSONObject("searchResult3") ?: return SearchResult(emptyList(), emptyList(), emptyList())
        return SearchResult(r.list("artist").map(::artistOf), r.list("album").map(::albumOf), r.list("song").map(::songOf))
    }

    override fun playlists(): List<Playlist> {
        val list = call("getPlaylists").optJSONObject("playlists")?.list("playlist")?.map {
            Playlist(it.optString("id"), it.optString("name", "?"), it.optInt("songCount", 0), it.strOrNull("coverArt"))
        } ?: emptyList()
        // Kapak: Navidrome'un karma görseli yerine listeye ilk eklenen şarkının kapağı
        return list.map { p -> if (p.songCount == 0) p else p.copy(coverArt = firstCover(p) ?: p.coverArt) }
    }

    /** Liste kimliği + şarkı sayısı → ilk şarkının kapağı ("" = yok). Liste değişince yeniden bakılır. */
    private val firstCovers = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun firstCover(p: Playlist): String? {
        val key = p.id + "|" + p.songCount
        firstCovers[key]?.let { return it.ifEmpty { null } }
        val cover = try {
            call("getPlaylist", "id" to p.id).optJSONObject("playlist")?.list("entry")?.firstOrNull()?.strOrNull("coverArt")
        } catch (e: Exception) {
            return null
        }
        firstCovers[key] = cover ?: ""
        return cover
    }

    override fun playlistSongs(id: String): List<Song> =
        call("getPlaylist", "id" to id).optJSONObject("playlist")?.list("entry")?.map(::songOf) ?: emptyList()

    override fun createPlaylist(name: String, songIds: List<String>) {
        val params = mutableListOf("name" to name)
        songIds.forEach { params.add("songId" to it) }
        call("createPlaylist", *params.toTypedArray())
    }

    override fun addToPlaylist(playlistId: String, songIds: List<String>) {
        val params = mutableListOf("playlistId" to playlistId)
        songIds.forEach { params.add("songIdToAdd" to it) }
        call("updatePlaylist", *params.toTypedArray())
    }

    override fun removeFromPlaylist(playlistId: String, index: Int) {
        call("updatePlaylist", "playlistId" to playlistId, "songIndexToRemove" to index.toString())
    }

    override fun renamePlaylist(playlistId: String, name: String) {
        call("updatePlaylist", "playlistId" to playlistId, "name" to name)
    }

    override fun deletePlaylist(playlistId: String) { call("deletePlaylist", "id" to playlistId) }

    // ------------------------------------------------------------------- türler

    override fun genres(): List<Genre> =
        call("getGenres").optJSONObject("genres")?.list("genre")?.map {
            Genre(it.optString("value"), it.optInt("songCount"), it.optInt("albumCount"))
        }?.filter { it.name.isNotBlank() && it.songCount > 0 }?.sortedByDescending { it.songCount } ?: emptyList()

    override fun songsByGenre(genre: String, count: Int): List<Song> =
        call("getSongsByGenre", "genre" to genre, "count" to count.toString())
            .optJSONObject("songsByGenre")?.list("song")?.map(::songOf) ?: emptyList()

    // ----------------------------------------------------------- internet radyoları

    override fun radios(): List<RadioStation> =
        call("getInternetRadioStations").optJSONObject("internetRadioStations")?.list("internetRadioStation")?.map {
            RadioStation(it.optString("id"), it.optString("name", "?"), it.optString("streamUrl"), it.optString("homepageUrl", ""))
        } ?: emptyList()

    override fun createRadio(r: RadioStation) {
        val params = mutableListOf("streamUrl" to r.url, "name" to r.name)
        if (r.homepage.isNotBlank()) params.add("homepageUrl" to r.homepage)
        call("createInternetRadioStation", *params.toTypedArray())
    }

    override fun deleteRadio(id: String) { call("deleteInternetRadioStation", "id" to id) }

    // ------------------------------------- şarkı listeleri (Navidrome kendi API'si)

    private var ndToken: String? = null
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun nativeLogin(): String {
        val body = JSONObject().put("username", s.user).put("password", s.pass).toString().toRequestBody(jsonType)
        http.newCall(Request.Builder().url(s.navidromeUrl + "/auth/login").post(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Navidrome girişi başarısız: ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}").getString("token").also { ndToken = it }
        }
    }

    private fun nativeGet(path: String, retry: Boolean = true): String {
        val token = ndToken ?: nativeLogin()
        val req = Request.Builder().url(s.navidromeUrl + path).header("x-nd-authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401 && retry) {
                ndToken = null
                return nativeGet(path, false)
            }
            if (!resp.isSuccessful) throw IOException("Navidrome hatası: ${resp.code}")
            return resp.body?.string() ?: "[]"
        }
    }

    private fun nativeSong(o: JSONObject) = Song(
        id = o.optString("id"),
        title = o.optString("title", "?"),
        artist = o.optString("artist", ""),
        album = o.optString("album", ""),
        albumId = o.strOrNull("albumId"),
        coverArt = "mf-" + o.optString("id"),
        duration = o.optDouble("duration", 0.0).toInt(),
        playCount = o.optInt("playCount", 0),
        starred = o.optBoolean("starred", false),
    )

    /**
     * Şarkı düzeyinde listeler: "recent" son dinlenenler, "frequent" en çok dinlenenler, "newest" son eklenenler.
     * Navidrome'un kendi API'sinden gelir; o olmazsa son kayıtlı hali, o da yoksa albüm listelerinden türetilir.
     */
    override fun songList(kind: String, size: Int): List<Song> {
        val sort = when (kind) {
            "recent" -> "play_date"
            "frequent" -> "play_count"
            else -> "recently_added"
        }
        val path = "/api/song?_start=0&_end=$size&_order=DESC&_sort=$sort"
        val cache = File(cacheDir.apply { mkdirs() }, md5("native|$path|${s.navidromeUrl}|${s.user}") + ".json")
        val body = try {
            nativeGet(path).also { b -> try { cache.writeText(b) } catch (e: Exception) { } }
        } catch (e: Exception) {
            if (cache.exists()) cache.readText() else null
        }
        if (body != null) {
            try {
                val arr = JSONArray(body)
                val songs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::nativeSong)
                return if (kind == "newest") songs else songs.filter { it.playCount > 0 }
            } catch (e: Exception) { /* biçim beklenmedik: yedek yola düş */ }
        }
        return when (kind) {
            "frequent" -> frequentSongs(size)
            else -> albums(kind, 8).flatMap { a -> try { album(a.id).second } catch (e: Exception) { emptyList() } }.take(size)
        }
    }

    override fun randomSongs(size: Int): List<Song> =
        call("getRandomSongs", "size" to size.toString())
            .optJSONObject("randomSongs")?.list("song")?.map(::songOf) ?: emptyList()

    override fun starred(): List<Song> =
        call("getStarred2").optJSONObject("starred2")?.list("song")?.map(::songOf) ?: emptyList()

    override fun star(id: String, on: Boolean) { call(if (on) "star" else "unstar", "id" to id) }

    /**
     * submission=false: "şu an çalıyor" (Last.fm profilinde canlı görünür, sayılmaz).
     * submission=true: dinlendi olarak sayılır; time = şarkının başladığı an (ms).
     */
    override fun scrobble(id: String, submission: Boolean, time: Long) {
        call("scrobble", "id" to id, "submission" to submission.toString(), "time" to time.toString())
    }

    override fun streamUrl(id: String): String = url("stream", "id" to id)

    override fun coverUrl(coverArt: String?, size: Int): String? =
        if (coverArt?.startsWith("content://") == true) coverArt // telefondaki şarkının albüm kapağı
        else if (coverArt.isNullOrBlank() || !s.configured) null else url("getCoverArt", "id" to coverArt, "size" to size.toString())

    override fun downloadUrl(id: String): String = url("download", "id" to id)
}
