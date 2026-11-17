package com.rdncgr.nota

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// ------------------------------------------------------------------ modeller

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String?,
    val coverArt: String?,
    val duration: Int,
    val playCount: Int = 0,
    /** Navidrome'da beğenilmiş mi (yanıttaki "starred" alanı). */
    val starred: Boolean = false,
    /** Doğrudan akış adresi (radyolar için); boşsa Navidrome'dan akıtılır. */
    val streamUrl: String? = null,
    /** Radyo logosu (varsa). */
    val logo: String? = null,
    /** Tür (dosyadaki etiketten, Navidrome okur). */
    val genre: String? = null,
) {
    val isRadio: Boolean get() = streamUrl != null
}

/** İnternet radyosu. id: Navidrome'daki kaydın kimliği (sunucu listesindekilerde null). */
data class RadioStation(
    val id: String?,
    val name: String,
    val url: String,
    val homepage: String = "",
    val favicon: String = "",
    val tags: String = "",
) {
    fun asSong() = Song(
        id = "radio:" + (id ?: url), title = name, artist = "Canlı radyo", album = "Radyo",
        albumId = null, coverArt = null, duration = 0, streamUrl = url, logo = favicon.ifBlank { null },
    )
}

data class Genre(val name: String, val songCount: Int, val albumCount: Int)
data class Album(val id: String, val name: String, val artist: String, val coverArt: String?, val songCount: Int)
data class Artist(val id: String, val name: String, val albumCount: Int)
data class Playlist(val id: String, val name: String, val songCount: Int, val coverArt: String?)
data class SearchResult(val artists: List<Artist>, val albums: List<Album>, val songs: List<Song>)

/** Radyoda tanınan şarkı. song null ise tanınamadı (message açıklar). source: "metadata" | "shazam". */
data class Recognition(
    val song: Suggestion?,
    val source: String,
    val cover: String?,
    val station: String,
    val message: String?,
    val time: Long = System.currentTimeMillis(),
)

data class Suggestion(
    val artist: String,
    val title: String,
    val album: String?,
    val inLibrary: Boolean,
    /** YouTube'dan gelen şarkılarda doğrudan video adresi; servis aramak yerine bunu indirir. */
    val url: String? = null,
    /** Satırda sanatçının yanında gösterilecek kısa not (ör. "Bilal Sonses dinlediğin için"). */
    val note: String? = null,
)
data class DownloadJob(val id: String, val artist: String, val title: String, val status: String, val message: String)
data class ImportedList(val name: String, val tracks: List<Suggestion>)

// ------------------------------------------------------------------- ayarlar

class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("nota", Context.MODE_PRIVATE)

    var navidromeUrl: String
        get() = prefs.getString("navidromeUrl", "") ?: ""
        set(v) = prefs.edit().putString("navidromeUrl", v.trim().trimEnd('/')).apply()
    var user: String
        get() = prefs.getString("user", "") ?: ""
        set(v) = prefs.edit().putString("user", v.trim()).apply()
    var pass: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) = prefs.edit().putString("pass", v).apply()
    var serviceUrl: String
        get() = prefs.getString("serviceUrl", "") ?: ""
        set(v) = prefs.edit().putString("serviceUrl", v.trim().trimEnd('/')).apply()
    var serviceKey: String
        get() = prefs.getString("serviceKey", "") ?: ""
        set(v) = prefs.edit().putString("serviceKey", v.trim()).apply()

    /** Navidrome'un scrobble ettiği Last.fm hesabının kullanıcı adı (geçmişi okumak için). */
    var lastfmUser: String
        get() = prefs.getString("lastfmUser", "") ?: ""
        set(v) = prefs.edit().putString("lastfmUser", v.trim()).apply()

    /** ListenBrainz kullanıcı adı: haftalık keşif listesi Sana özel önerilere karışır. */
    var listenbrainzUser: String
        get() = prefs.getString("listenbrainzUser", "") ?: ""
        set(v) = prefs.edit().putString("listenbrainzUser", v.trim()).apply()

    /** Sık dinlenen şarkıları otomatik olarak telefona indir. */
    var autoOffline: Boolean
        get() = prefs.getBoolean("autoOffline", false)
        set(v) = prefs.edit().putBoolean("autoOffline", v).apply()
    var autoOfflineCount: Int
        get() = prefs.getInt("autoOfflineCount", 50)
        set(v) = prefs.edit().putInt("autoOfflineCount", v).apply()
    /** Otomatik indirmeyi yalnızca Wi-Fi (ücretsiz ağ) varken yap. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) = prefs.edit().putBoolean("wifiOnly", v).apply()
    var lastAutoSync: Long
        get() = prefs.getLong("lastAutoSync", 0L)
        set(v) = prefs.edit().putLong("lastAutoSync", v).apply()

    /** Karışık çalma modu: 0 = kapalı, 1 = rastgele, 2 = sana özel karışık. */
    var playMode: Int
        get() = prefs.getInt("playMode", 0)
        set(v) = prefs.edit().putInt("playMode", v).apply()

    /** Ana sayfa bölümlerinin sırası ve gizlenenler (anahtarlar virgülle). */
    var homeOrder: String
        get() = prefs.getString("homeOrder", "") ?: ""
        set(v) = prefs.edit().putString("homeOrder", v).apply()
    var homeHidden: String
        get() = prefs.getString("homeHidden", "") ?: ""
        set(v) = prefs.edit().putString("homeHidden", v).apply()

    /** Başarılı işlem bildirimleri ("Beğenilere eklendi" vb.) gösterilsin mi? Hatalar her zaman gösterilir. */
    var showInfo: Boolean
        get() = prefs.getBoolean("showInfo", false)
        set(v) = prefs.edit().putBoolean("showInfo", v).apply()

    /** Son aramalar (en yenisi başta). */
    var searchHistory: List<String>
        get() = try {
            val a = JSONArray(prefs.getString("searchHistory", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { emptyList() }
        set(v) = prefs.edit().putString("searchHistory", JSONArray(v).toString()).apply()

    /** Radyolar arasında rastgele geçiş açık mı? */
    /** Tekrar: 0 kapalı, 1 tümünü tekrarla, 2 aynı şarkıyı tekrarla. */
    var repeatMode: Int
        get() = prefs.getInt("repeatMode", 0)
        set(v) = prefs.edit().putInt("repeatMode", v).apply()

    /** İşitme cihazı uyumluluk modu: 0 kapalı, 1 otomatik (cihaz bağlanınca), 2 her zaman. */
    var hearingMode: Int
        get() = prefs.getInt("hearingMode", 1)
        set(v) = prefs.edit().putInt("hearingMode", v).apply()

    var hearingMono: Boolean
        get() = prefs.getBoolean("hearingMono", false)
        set(v) = prefs.edit().putBoolean("hearingMono", v).apply()

    /** Şarkı başına son çalış sonuçları (atlandı/dinlendi), JSON nesne. */
    var skipsJson: String
        get() = prefs.getString("skips", "{}") ?: "{}"
        set(v) = prefs.edit().putString("skips", v).apply()

    /** Telefondan elle silinen şarkılar: sık dinlenenleri otomatik indirme bunları tekrar indirmez. */
    var offlineExcluded: Set<String>
        get() = (prefs.getString("offlineExcluded", "") ?: "").split(',').filter { it.isNotBlank() }.toSet()
        set(v) = prefs.edit().putString("offlineExcluded", v.joinToString(",")).apply()

    /** Gönderilemeyen "dinlendi" bildirimleri (çevrimdışıyken), JSON dizi. */
    var pendingScrobbles: String
        get() = prefs.getString("pendingScrobbles", "[]") ?: "[]"
        set(v) = prefs.edit().putString("pendingScrobbles", v).apply()

    var radioShuffle: Boolean
        get() = prefs.getBoolean("radioShuffle", false)
        set(v) = prefs.edit().putBoolean("radioShuffle", v).apply()

    /** Sunucu indirme listesinin son hali (servise ulaşılamazsa da görünsün). */
    var jobsJson: String
        get() = prefs.getString("jobs", "[]") ?: "[]"
        set(v) = prefs.edit().putString("jobs", v).apply()

    /** Radyoda tanınan şarkılar (en yenisi başta), JSON dizi. */
    var heardJson: String
        get() = prefs.getString("heard", "[]") ?: "[]"
        set(v) = prefs.edit().putString("heard", v).apply()

    val configured: Boolean get() = navidromeUrl.isNotBlank() && user.isNotBlank()
    val serviceConfigured: Boolean get() = serviceUrl.isNotBlank()
}

// ---------------------------------------------------------------- yardımcılar

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun JSONObject.list(name: String): List<JSONObject> {
    val arr: JSONArray = optJSONArray(name) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

internal fun JSONObject.strOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

internal fun songOf(o: JSONObject) = Song(
    id = o.optString("id"),
    title = o.optString("title", "?"),
    artist = o.optString("artist", ""),
    album = o.optString("album", ""),
    albumId = o.strOrNull("albumId"),
    coverArt = o.strOrNull("coverArt"),
    duration = o.optInt("duration", 0),
    playCount = o.optInt("playCount", 0),
    starred = o.has("starred") && !o.isNull("starred"),
    genre = o.strOrNull("genre"),
)

private fun albumOf(o: JSONObject) = Album(
    id = o.optString("id"),
    name = o.optString("name", o.optString("title", "?")),
    artist = o.optString("artist", ""),
    coverArt = o.strOrNull("coverArt"),
    songCount = o.optInt("songCount", 0),
)

private fun artistOf(o: JSONObject) = Artist(o.optString("id"), o.optString("name", "?"), o.optInt("albumCount", 0))

// ------------------------------------------------------- Navidrome (Subsonic)

class Subsonic(private val s: Settings, private val cacheDir: File) {
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
        sb.append("&v=1.16.1&c=nota&f=json")
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

    fun ping() { call("ping") }

    fun albums(type: String, size: Int = 30): List<Album> =
        call("getAlbumList2", "type" to type, "size" to size.toString())
            .optJSONObject("albumList2")?.list("album")?.map(::albumOf) ?: emptyList()

    fun album(id: String): Pair<Album, List<Song>> {
        val a = call("getAlbum", "id" to id).getJSONObject("album")
        return albumOf(a) to a.list("song").map(::songOf)
    }

    fun artists(): List<Artist> =
        call("getArtists").optJSONObject("artists")?.list("index")
            ?.flatMap { it.list("artist") }?.map(::artistOf) ?: emptyList()

    fun artistAlbums(id: String): List<Album> =
        call("getArtist", "id" to id).optJSONObject("artist")?.list("album")?.map(::albumOf) ?: emptyList()

    /** Kütüphanedeki bütün şarkılar, ada göre (Navidrome boş aramada hepsini döndürür). */
    fun allSongs(max: Int = 10000): List<Song> {
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

    fun search(query: String): SearchResult {
        val r = call("search3", "query" to query, "songCount" to "40", "albumCount" to "15", "artistCount" to "10")
            .optJSONObject("searchResult3") ?: return SearchResult(emptyList(), emptyList(), emptyList())
        return SearchResult(r.list("artist").map(::artistOf), r.list("album").map(::albumOf), r.list("song").map(::songOf))
    }

    fun playlists(): List<Playlist> =
        call("getPlaylists").optJSONObject("playlists")?.list("playlist")?.map {
            Playlist(it.optString("id"), it.optString("name", "?"), it.optInt("songCount", 0), it.strOrNull("coverArt"))
        } ?: emptyList()

    fun playlistSongs(id: String): List<Song> =
        call("getPlaylist", "id" to id).optJSONObject("playlist")?.list("entry")?.map(::songOf) ?: emptyList()

    fun createPlaylist(name: String, songIds: List<String> = emptyList()) {
        val params = mutableListOf("name" to name)
        songIds.forEach { params.add("songId" to it) }
        call("createPlaylist", *params.toTypedArray())
    }

    fun addToPlaylist(playlistId: String, songIds: List<String>) {
        val params = mutableListOf("playlistId" to playlistId)
        songIds.forEach { params.add("songIdToAdd" to it) }
        call("updatePlaylist", *params.toTypedArray())
    }

    fun removeFromPlaylist(playlistId: String, index: Int) {
        call("updatePlaylist", "playlistId" to playlistId, "songIndexToRemove" to index.toString())
    }

    fun renamePlaylist(playlistId: String, name: String) {
        call("updatePlaylist", "playlistId" to playlistId, "name" to name)
    }

    fun deletePlaylist(playlistId: String) { call("deletePlaylist", "id" to playlistId) }

    /** En çok dinlenen şarkılar: sık dinlenen albümlerdeki şarkılar dinlenme sayısına göre sıralanır. */
    fun frequentSongs(limit: Int): List<Song> {
        val albums = albums("frequent", 30)
        val songs = albums.flatMap { a -> try { album(a.id).second } catch (e: Exception) { emptyList() } }
        return songs.filter { it.playCount > 0 }
            .sortedByDescending { it.playCount }
            .distinctBy { it.id }
            .take(limit)
    }

    /** Bir albüm listesinden (sık, son, yüksek puanlı…) birkaç albüm seçip içlerinden şarkı alır. */
    private fun songsFromAlbums(type: String, albumCount: Int, perAlbum: Int): List<Song> =
        albums(type, albumCount * 2).take(albumCount * 2).shuffled().take(albumCount).flatMap { a ->
            val songs = try { album(a.id).second } catch (e: Exception) { emptyList() }
            if (type == "frequent") songs.sortedByDescending { it.playCount }.take(perAlbum)
            else songs.shuffled().take(perAlbum)
        }

    /**
     * "Sana özel karışık": Navidrome verilerinden harmanlanmış çalma sırası.
     * Beğenilenler + en çok dinlenenler + son dinlenenler + son eklenenler + rastgele,
     * sırayla birer birer dizilir ki tek bir türden art arda çok şarkı gelmesin.
     */
    // ------------------------------------------------------------------- türler

    fun genres(): List<Genre> =
        call("getGenres").optJSONObject("genres")?.list("genre")?.map {
            Genre(it.optString("value"), it.optInt("songCount"), it.optInt("albumCount"))
        }?.filter { it.name.isNotBlank() && it.songCount > 0 }?.sortedByDescending { it.songCount } ?: emptyList()

    fun songsByGenre(genre: String, count: Int = 500): List<Song> =
        call("getSongsByGenre", "genre" to genre, "count" to count.toString())
            .optJSONObject("songsByGenre")?.list("song")?.map(::songOf) ?: emptyList()

    fun smartMix(size: Int = 60, exclude: Set<String> = emptySet()): List<Song> {
        fun safe(block: () -> List<Song>) = try { block() } catch (e: Exception) { emptyList() }
        val fav = safe { starred() }
        // Beğenilerde en çok geçen iki tür: o türlerden de şarkı katılır
        val favGenres = fav.mapNotNull { it.genre }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(2).map { it.key }
        val lastfm = Graph.settings.serviceConfigured && Graph.settings.lastfmUser.isNotBlank()
        // Son ayın en çokları (şarkı bazında): Last.fm varsa oradan, yoksa Navidrome'un sayılarından.
        // Çok dinlenenler daha olası: listenin ilk yarısı iki kez şansa girer.
        val top = safe {
            val list = if (lastfm) LastFmApi.topLibrary("1month", 50) else songList("frequent", 50)
            (list.take(list.size / 2) + list).distinctBy { it.id }.shuffled().take(size / 4)
        }
        val pools = listOf(
            fav.shuffled().take(size / 4),
            top,
            safe { favGenres.flatMap { g -> songsByGenre(g, 150).shuffled().take(size / 8) } },
            safe { songList("recent", 40).shuffled().take(size / 6) },
            // Unuttukların: eskiden sevip son 3 aydır dinlenmeyenler (az miktarda)
            if (lastfm) safe { LastFmApi.forgotten().shuffled().take(size / 10) } else emptyList(),
            safe { songsFromAlbums("newest", 4, 2) },
            safe { randomSongs(size / 3) },
        ).map { it.shuffled().toMutableList() }
        val skip = exclude + Skips.disliked() // son çalışlarında hep atlananlar girmez
        val out = LinkedHashMap<String, Song>()
        while (out.size < size && pools.any { it.isNotEmpty() }) {
            for (p in pools) if (p.isNotEmpty()) p.removeAt(0).let { if (it.id !in skip) out.putIfAbsent(it.id, it) }
        }
        return out.values.take(size)
    }

    // ----------------------------------------------------------- internet radyoları

    fun radios(): List<RadioStation> =
        call("getInternetRadioStations").optJSONObject("internetRadioStations")?.list("internetRadioStation")?.map {
            RadioStation(it.optString("id"), it.optString("name", "?"), it.optString("streamUrl"), it.optString("homepageUrl", ""))
        } ?: emptyList()

    fun createRadio(r: RadioStation) {
        val params = mutableListOf("streamUrl" to r.url, "name" to r.name)
        if (r.homepage.isNotBlank()) params.add("homepageUrl" to r.homepage)
        call("createInternetRadioStation", *params.toTypedArray())
    }

    fun deleteRadio(id: String) { call("deleteInternetRadioStation", "id" to id) }

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
    fun songList(kind: String, size: Int = 30): List<Song> {
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

    fun randomSongs(size: Int = 60): List<Song> =
        call("getRandomSongs", "size" to size.toString())
            .optJSONObject("randomSongs")?.list("song")?.map(::songOf) ?: emptyList()

    fun starred(): List<Song> =
        call("getStarred2").optJSONObject("starred2")?.list("song")?.map(::songOf) ?: emptyList()

    fun star(id: String, on: Boolean) { call(if (on) "star" else "unstar", "id" to id) }

    /**
     * submission=false: "şu an çalıyor" (Last.fm profilinde canlı görünür, sayılmaz).
     * submission=true: dinlendi olarak sayılır; time = şarkının başladığı an (ms).
     */
    fun scrobble(id: String, submission: Boolean, time: Long) {
        call("scrobble", "id" to id, "submission" to submission.toString(), "time" to time.toString())
    }

    fun streamUrl(id: String): String = url("stream", "id" to id)

    fun coverUrl(coverArt: String?, size: Int = 300): String? =
        if (coverArt.isNullOrBlank() || !s.configured) null else url("getCoverArt", "id" to coverArt, "size" to size.toString())
}

// ------------------------------------------------- Nota servisi (öneri/indirme)

class ServiceApi(private val s: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun exec(req: Request.Builder): JSONObject {
        if (!s.serviceConfigured) throw IOException("Önce Ayarlar'dan Nota servis adresini girin")
        req.header("X-API-Key", s.serviceKey)
        http.newCall(req.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val detail = try { JSONObject(body).optString("detail") } catch (e: Exception) { "" }
                throw IOException(detail.ifBlank { "Servis hatası: ${resp.code}" })
            }
            return JSONObject(body)
        }
    }

    private fun get(path: String) = exec(Request.Builder().url(s.serviceUrl + path))
    private fun post(path: String, body: JSONObject) =
        exec(Request.Builder().url(s.serviceUrl + path).post(body.toString().toRequestBody(jsonType)))

    private fun suggestions(o: JSONObject): List<Suggestion> = o.list("tracks").map {
        Suggestion(
            it.optString("artist"), it.optString("title"), it.strOrNull("album"),
            it.optBoolean("in_library", false), it.strOrNull("url"),
        )
    }

    private fun trackJson(t: Suggestion): JSONObject {
        val o = JSONObject().put("artist", t.artist).put("title", t.title)
        if (!t.album.isNullOrBlank()) o.put("album", t.album)
        if (!t.url.isNullOrBlank()) o.put("url", t.url)
        return o
    }

    fun health(): JSONObject = get("/health")

    /** Last.fm dinleme geçmişi uç noktaları (/lastfm/...). */
    fun lastfm(path: String): JSONObject = get("/lastfm/$path")

    fun listenbrainzUser(user: String): JSONObject =
        get("/listenbrainz/user?user=" + java.net.URLEncoder.encode(user, "UTF-8"))

    /** Türü boş şarkılara Last.fm'den tür yazdırmayı başlatır / durumunu verir. */
    fun startGenreBackfill(): JSONObject = post("/genres/backfill", JSONObject())
    fun genreBackfillStatus(): JSONObject = get("/genres/backfill")

    fun similar(artist: String, title: String): List<Suggestion> =
        suggestions(get("/similar?artist=${enc(artist)}&title=${enc(title)}"))

    fun search(q: String): List<Suggestion> = suggestions(get("/search?q=${enc(q)}"))

    fun download(t: Suggestion) { post("/download", trackJson(t)); Jobs.poke() }

    fun downloadBatch(tracks: List<Suggestion>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(trackJson(it)) }
        post("/download/batch", JSONObject().put("tracks", arr))
        Jobs.poke()
    }

    /** Şarkının dosyasını sunucudaki müzik klasöründen siler (Navidrome taraması da başlar). */
    fun deleteSong(id: String) { post("/song/delete", JSONObject().put("id", id)) }

    /** İndirme listesinden siler; ids boşsa bitmiş/hatalı kayıtların hepsi. */
    fun deleteJobs(ids: List<String> = emptyList()) {
        post("/jobs/delete", JSONObject().put("ids", JSONArray(ids)))
        Jobs.poke()
    }

    fun retryFailed() { post("/jobs/retry", JSONObject()); Jobs.poke() }

    fun jobs(): List<DownloadJob> = get("/jobs").list("jobs").map {
        DownloadJob(it.optString("id"), it.optString("artist"), it.optString("title"), it.optString("status"), it.optString("message"))
    }

    fun spotify(url: String): ImportedList {
        val o = post("/spotify/playlist", JSONObject().put("url", url))
        return ImportedList(o.optString("name", "Spotify listesi"), suggestions(o))
    }

    /** Önizleme sesinin adresi; servis sesi YouTube'dan alıp aktarır. */
    fun previewUrl(t: Suggestion): String {
        val sb = StringBuilder(s.serviceUrl).append("/preview?artist=").append(enc(t.artist))
            .append("&title=").append(enc(t.title)).append("&key=").append(enc(s.serviceKey))
        if (!t.url.isNullOrBlank()) sb.append("&url=").append(enc(t.url))
        return sb.toString()
    }

    /** Sunucuda saklanan güncel Türkiye radyoları listesi. */
    fun radios(refresh: Boolean = false, scope: String = "tr"): List<RadioStation> =
        get("/radios?scope=$scope" + if (refresh) "&refresh=true" else "").list("stations").map {
            RadioStation(
                null, it.optString("name"), it.optString("url"), it.optString("homepage", ""),
                it.optString("favicon", ""), it.optString("tags", ""),
            )
        }

    /** Radyoda o an çalan şarkıyı bulur (önce yayın bilgisi, yoksa 10 sn kayıt + Shazam). */
    fun recognize(url: String, station: String, forceAudio: Boolean, streamTitle: String? = null): Recognition {
        val req = JSONObject().put("url", url).put("name", station).put("force_audio", forceAudio)
        if (!streamTitle.isNullOrBlank()) req.put("stream_title", streamTitle) // çalar başlığı zaten okuduysa
        val o = post("/recognize", req)
        if (!o.optBoolean("found")) return Recognition(null, o.optString("source"), null, station,
            o.optString("message", "Şarkı tanınamadı"))
        val s = Suggestion(o.optString("artist"), o.optString("title"), o.strOrNull("album"), o.optBoolean("in_library"))
        return Recognition(s, o.optString("source"), o.strOrNull("cover"), station, null)
    }

    /** "tr" = Türkiye, "world" = dünya en çok dinlenenler (Last.fm). */
    fun charts(scope: String): List<Suggestion> = suggestions(get("/charts?scope=$scope"))

    fun youtube(url: String): ImportedList {
        val o = post("/youtube/playlist", JSONObject().put("url", url))
        return ImportedList(o.optString("name", "YouTube listesi"), suggestions(o))
    }

    /** Bağlantıya bakıp doğru kaynaktan listeyi getirir. */
    fun importList(url: String): ImportedList {
        val u = url.lowercase()
        return when {
            "spotify" in u -> spotify(url)
            "youtube" in u || "youtu.be" in u -> youtube(url)
            else -> throw IOException("Spotify veya YouTube çalma listesi bağlantısı girin")
        }
    }
}

// ---------------------------------------------------------- çevrimdışı dosyalar

class Offline(ctx: Context, private val subsonic: Subsonic) {
    private val dir = File(ctx.filesDir, "offline").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    val downloaded = mutableStateMapOf<String, Song>()
    val inProgress = mutableStateMapOf<String, Boolean>()

    init {
        try {
            if (indexFile.exists()) {
                val arr = JSONArray(indexFile.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val song = songOf(o)
                    if (fileFor(song.id).exists()) downloaded[song.id] = song
                }
            }
        } catch (e: Exception) {
            // bozuk dizin: boş başla
        }
    }

    fun fileFor(id: String): File = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".audio")

    private fun saveIndex() {
        val arr = JSONArray()
        downloaded.values.forEach { s ->
            arr.put(
                JSONObject().put("id", s.id).put("title", s.title).put("artist", s.artist).put("album", s.album)
                    .put("albumId", s.albumId ?: JSONObject.NULL).put("coverArt", s.coverArt ?: JSONObject.NULL)
                    .put("duration", s.duration)
            )
        }
        indexFile.writeText(arr.toString())
    }

    suspend fun download(song: Song) {
        if (downloaded.containsKey(song.id) || inProgress[song.id] == true) return
        inProgress[song.id] = true
        try {
            withContext(Dispatchers.IO) {
                val tmp = File(dir, fileFor(song.id).name + ".part")
                val req = Request.Builder().url(subsonic.url("download", "id" to song.id)).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("İndirilemedi: ${resp.code}")
                    val body = resp.body ?: throw IOException("Boş yanıt")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                if (!tmp.renameTo(fileFor(song.id))) throw IOException("Dosya kaydedilemedi")
            }
            downloaded[song.id] = song
            withContext(Dispatchers.IO) { saveIndex() }
        } finally {
            inProgress.remove(song.id)
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
        downloaded.remove(id)
        saveIndex()
        // Elle silinen şarkıyı otomatik indirme bir daha getirmesin
        Graph.settings.offlineExcluded = Graph.settings.offlineExcluded + id
    }

    fun usedBytes(): Long = dir.listFiles()?.filter { it.name.endsWith(".audio") }?.sumOf { it.length() } ?: 0L
}

// ------------------------------------------- sık dinlenenleri otomatik indirme

object AutoOffline {
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    private const val INTERVAL_MS = 6 * 60 * 60 * 1000L // en fazla 6 saatte bir kendiliğinden

    private fun onUnmeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** force = kullanıcı "Şimdi eşitle" dedi: zaman ve Wi-Fi sınırını yok say. */
    fun run(force: Boolean = false) {
        val s = Graph.settings
        if (running || !s.configured) return
        if (!force) {
            if (!s.autoOffline) return
            if (System.currentTimeMillis() - s.lastAutoSync < INTERVAL_MS) return
            if (s.wifiOnly && !onUnmeteredNetwork(Graph.appContext)) return
        }
        running = true
        status = "Sık dinlenenler bulunuyor…"
        Graph.scope.launch {
            try {
                // Elle silinenler atlanır; yerlerini sıradaki sık dinlenenler doldurur
                val excluded = s.offlineExcluded
                val songs = withContext(Dispatchers.IO) {
                    Graph.subsonic.frequentSongs(s.autoOfflineCount + excluded.size)
                }.filter { it.id !in excluded }.take(s.autoOfflineCount)
                val missing = songs.filter { !Graph.offline.downloaded.containsKey(it.id) }
                var done = 0
                var failed = 0
                missing.forEachIndexed { i, song ->
                    status = "İndiriliyor ${i + 1}/${missing.size}: ${song.title}"
                    try {
                        Graph.offline.download(song)
                        done++
                    } catch (e: Exception) {
                        failed++
                    }
                }
                s.lastAutoSync = System.currentTimeMillis()
                status = when {
                    songs.isEmpty() -> "Henüz dinlenme geçmişi yok"
                    missing.isEmpty() -> "Sık dinlenen ${songs.size} şarkının hepsi telefonda"
                    failed == 0 -> "$done yeni şarkı telefona indirildi"
                    else -> "$done şarkı indirildi, $failed şarkı indirilemedi"
                }
            } catch (e: Exception) {
                status = "Eşitlenemedi: " + (errorText(e, "bağlantı yok"))
            } finally {
                running = false
            }
        }
    }
}

// ------------------------------------------------------------------- beğeniler

/**
 * Hangi şarkıların beğenildiğini tek yerde tutar. Önceden kalp simgesi şarkının durumuna hiç bakmıyor,
 * hep boş görünüyordu. Navidrome'dan gelen "starred" bilgisi + bu oturumdaki değişiklikler birleştirilir.
 */
object Favorites {
    private val known = mutableStateMapOf<String, Boolean>()

    fun isStarred(song: Song): Boolean = known[song.id] ?: song.starred

    /** Navidrome'daki güncel beğeni listesini çeker (uygulamaya dönüşte ve açılışta). */
    fun refresh() {
        if (!Graph.settings.configured) return
        Graph.scope.launch {
            try {
                val ids = withContext(Dispatchers.IO) { Graph.subsonic.starred().map { it.id }.toSet() }
                known.keys.filter { it !in ids }.forEach { known[it] = false }
                ids.forEach { known[it] = true }
            } catch (e: Exception) {
                // çevrimdışı: bildiklerimizle devam
            }
        }
    }

    fun toggle(song: Song) {
        val now = !isStarred(song)
        known[song.id] = now
        Graph.scope.launch {
            try {
                withContext(Dispatchers.IO) { Graph.subsonic.star(song.id, now) }
                Graph.info(if (now) "Beğenilere eklendi" else "Beğenilerden çıkarıldı")
                Graph.listsVersion++
            } catch (e: Exception) {
                known[song.id] = !now // geri al
                Graph.toast(errorText(e, "Beğeni kaydedilemedi"))
            }
        }
    }
}

// --------------------------------------------------------------- ana sayfa düzeni

data class HomeSection(val key: String, val title: String, val visible: Boolean)

/** Ana sayfadaki bölümlerin sırası ve görünürlüğü; Ayarlar > Ana sayfa'dan değiştirilir. */
object HomeLayout {
    val titles = linkedMapOf(
        "playlists" to "Listelerim",
        "starred" to "Favoriler",
        "recent" to "Son dinlenenler",
        "frequent" to "En çok dinlenenler",
        "newest" to "Son eklenenler",
        "forgotten" to "Unuttukların",
        "radio" to "Radyolarım",
        "genres" to "Türler",
        "random" to "Rastgele albümler",
    )

    var sections by mutableStateOf<List<HomeSection>>(emptyList())
        private set

    fun load() {
        val s = Graph.settings
        val hidden = s.homeHidden.split(',').filter { it.isNotBlank() }.toSet()
        // Kayıtlı sıradaki bilinen anahtarlar, ardından yeni eklenmiş bölümler (kaldırılanlar düşer)
        val saved = s.homeOrder.split(',').filter { it in titles }
        val order = saved + titles.keys.filter { it !in saved }
        sections = order.map { HomeSection(it, titles.getValue(it), it !in hidden) }
    }

    private fun save(list: List<HomeSection>) {
        sections = list
        Graph.settings.homeOrder = list.joinToString(",") { it.key }
        Graph.settings.homeHidden = list.filter { !it.visible }.joinToString(",") { it.key }
    }

    fun move(index: Int, delta: Int) {
        val to = index + delta
        if (to !in sections.indices) return
        val list = sections.toMutableList()
        list.add(to, list.removeAt(index))
        save(list)
    }

    fun setVisible(key: String, visible: Boolean) = save(sections.map { if (it.key == key) it.copy(visible = visible) else it })

    fun reset() {
        Graph.settings.homeOrder = ""
        Graph.settings.homeHidden = ""
        load()
    }
}

// ------------------------------------------------------------ ortak nesneler

object Graph {
    lateinit var appContext: Context
    lateinit var settings: Settings
    lateinit var subsonic: Subsonic
    lateinit var service: ServiceApi
    lateinit var offline: Offline
    lateinit var player: PlayerController
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Ayarlar değişince ekranların yeniden yüklenmesi için artırılır. */
    var version by mutableIntStateOf(0)

    /** Çalma listeleri değişince liste ekranlarının yenilenmesi için artırılır. */
    var listsVersion by mutableIntStateOf(0)

    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        appContext = ctx.applicationContext
        settings = Settings(appContext)
        subsonic = Subsonic(settings, File(appContext.filesDir, "cache"))
        service = ServiceApi(settings)
        offline = Offline(appContext, subsonic)
        player = PlayerController(appContext)
        HomeLayout.load()
        ready = true
        HearingMode.start(appContext)
        Jobs.start()
    }

    /** Hata ve uyarılar için: her zaman gösterilir. */
    fun toast(msg: String) {
        Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
    }

    /** Başarı/bilgi mesajları: yalnızca Ayarlar'da açıksa gösterilir (varsayılan kapalı). */
    fun info(msg: String) {
        if (settings.showInfo) toast(msg)
    }

    /** Arka planda çalıştır; hata olursa kısa mesaj göster. */
    fun io(okMessage: String? = null, then: () -> Unit = {}, block: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                if (okMessage != null) info(okMessage)
                then()
            } catch (e: Exception) {
                toast(errorText(e, "Bir hata oluştu"))
            }
        }
    }

    fun saveOffline(song: Song) {
        // Kullanıcı kendisi indirdiyse otomatik indirme dışlamasından çıkar
        settings.offlineExcluded = settings.offlineExcluded - song.id
        scope.launch {
            try {
                offline.download(song)
            } catch (e: Exception) {
                toast(errorText(e, "İndirilemedi"))
            }
        }
    }
}

/** Ağ ve sunucu hatalarını Türkçe, anlaşılır bir cümleye çevirir. */
fun errorText(e: Throwable, fallback: String): String {
    var t: Throwable? = e
    while (t != null) {
        val m = t.message.orEmpty()
        when {
            t is java.net.UnknownHostException -> return "Sunucu adresi bulunamadı; adresi ve internet bağlantını kontrol et"
            t is java.net.NoRouteToHostException -> return "Sunucuya ulaşılamıyor; ağ ya da VPN (ZeroTier, Tailscale) bağlantını kontrol et"
            t is java.net.ConnectException || m.startsWith("Failed to connect") ->
                return "Sunucuya bağlanılamadı; sunucu kapalı olabilir ya da adres yanlış"
            t is java.net.SocketTimeoutException || m.equals("timeout", true) -> return "Sunucu zamanında cevap vermedi"
            t is javax.net.ssl.SSLException -> return "Güvenli bağlantı kurulamadı (https ayarını kontrol et)"
            t is java.net.SocketException -> return "Bağlantı koptu"
            t is org.json.JSONException -> return "Sunucudan beklenmeyen bir cevap geldi; adres doğru mu?"
            t is java.io.FileNotFoundException -> return "Dosya bulunamadı"
        }
        val code = Regex("""(?:hatası|başarısız|İndirilemedi): (\d{3})""").find(m)?.groupValues?.get(1)?.toInt()
        if (code != null) return httpText(code)
        t = t.cause
    }
    return e.message?.takeIf { it.isNotBlank() } ?: fallback
}

private fun httpText(code: Int) = when (code) {
    400 -> "İstek hatalı (400)"
    401, 403 -> "Erişim reddedildi; kullanıcı adı, parola ya da servis parolası yanlış ($code)"
    404 -> "Sunucuda bulunamadı; adres doğru mu? (404)"
    408, 504 -> "Sunucu zamanında cevap vermedi ($code)"
    429 -> "Çok fazla istek; biraz sonra tekrar dene (429)"
    in 500..599 -> "Sunucuda bir hata oluştu ($code)"
    else -> "Sunucu hatası ($code)"
}

/** Navidrome'un İngilizce hata mesajları (Subsonic hata kodlarına göre). */
fun navidromeError(err: org.json.JSONObject?): String = when (err?.optInt("code", -1)) {
    10 -> "Navidrome: eksik bilgi gönderildi"
    20, 30 -> "Navidrome sürümü uyumsuz"
    40 -> "Navidrome: kullanıcı adı ya da parola yanlış"
    41 -> "Navidrome: bu giriş yöntemi desteklenmiyor"
    50 -> "Navidrome: bu işlem için yetkin yok"
    70 -> "Navidrome'da bulunamadı"
    else -> "Navidrome hatası" + (err?.optString("message")?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
}
