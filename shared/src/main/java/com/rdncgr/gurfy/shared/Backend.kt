package com.rdncgr.gurfy.shared

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.media3.common.audio.AudioProcessor
import org.json.JSONObject

/**
 * Arayüzün veri kaynağı. Ekranlar yalnızca bunu bilir:
 *  - Gurfy Sync (:app): Navidrome + ev sunucusundaki Gurfy servisi
 *  - Gurfy (:gurfy): her şey telefonda (Room, MediaStore, NewPipe, Last.fm, Shazam)
 * Her uygulama kendi Application sınıfında [Graph.backendFactory]'yi doldurur.
 */
interface Backend {
    /** Navidrome'un yaptığı işler: kütüphane, listeler, beğeniler, akış. */
    val library: Library

    /** Gurfy servisinin yaptığı işler: arama, indirme, öneriler, radyo listesi, şarkı tanıma. */
    val service: MusicService

    /** Kütüphane kullanılabilir mi? (Sync: Navidrome bilgileri girilmiş mi) */
    val libraryReady: Boolean

    /** Servis kullanılabilir mi? (Sync: servis adresi girilmiş mi) */
    val serviceReady: Boolean

    /**
     * Kütüphane telefon dışında mı? true ise "telefona indir" ve "sık dinlenenleri otomatik indir"
     * gösterilir. Gurfy'de kütüphane zaten telefonda olduğu için false.
     */
    val hasRemoteLibrary: Boolean

    /** Arayüzün vurgu rengi (Sync sarı, Gurfy turkuaz). */
    val accent: Color

    /** GitHub sürüm etiketinin öneki (Sync "surum-", Gurfy "gurfy-"); güncelleme denetimi için. */
    val releaseTag: String

    /** Graph hazır olunca bir kez çağrılır (Sync: ayar yedeği). */
    fun start(ctx: Context) {}

    /** Oynatıcının ses çıkışına takılacak işlemciler (Gurfy: "Bu şarkı ne?" için ses kopyası). */
    fun audioProcessors(): List<AudioProcessor> = emptyList()

    /** Ayarlar > Bağlantılar sekmesinin içeriği. */
    @Composable
    fun ConnectionSettings()

    /** Her ekranın üstünde gösterilebilecek pencereler (Gurfy: yeniden kurulumda yedek sorusu). */
    @Composable
    fun Overlay() {}
}

/** Navidrome'un (Subsonic API) karşılığı. Sync'te ağdan, Gurfy'de telefondaki veritabanından. */
interface Library {
    fun ping()
    fun albums(type: String, size: Int = 30): List<Album>
    fun album(id: String): Pair<Album, List<Song>>
    fun artists(): List<Artist>
    fun artistAlbums(id: String): List<Album>

    /** Kütüphanedeki bütün şarkılar, ada göre. */
    fun allSongs(max: Int = 10000): List<Song>
    fun search(query: String): SearchResult

    fun playlists(): List<Playlist>
    fun playlistSongs(id: String): List<Song>
    fun createPlaylist(name: String, songIds: List<String> = emptyList())
    fun addToPlaylist(playlistId: String, songIds: List<String>)
    fun removeFromPlaylist(playlistId: String, index: Int)
    fun renamePlaylist(playlistId: String, name: String)
    fun deletePlaylist(playlistId: String)

    fun genres(): List<Genre>
    fun songsByGenre(genre: String, count: Int = 500): List<Song>

    fun radios(): List<RadioStation>
    fun createRadio(r: RadioStation)
    fun deleteRadio(id: String)

    /** Şarkı düzeyinde listeler: "recent" son dinlenenler, "frequent" en çok dinlenenler, "newest" son eklenenler. */
    fun songList(kind: String, size: Int = 30): List<Song>
    fun randomSongs(size: Int = 60): List<Song>
    fun starred(): List<Song>
    fun star(id: String, on: Boolean)

    /**
     * submission=false: "şu an çalıyor" (Last.fm profilinde canlı görünür, sayılmaz).
     * submission=true: dinlendi olarak sayılır; time = şarkının başladığı an (ms).
     */
    fun scrobble(id: String, submission: Boolean, time: Long)

    fun streamUrl(id: String): String
    fun coverUrl(coverArt: String?, size: Int = 300): String?

    /** Şarkının dosyasını telefona kopyalamak için adres (yalnızca [Backend.hasRemoteLibrary] iken). */
    fun downloadUrl(id: String): String = streamUrl(id)

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
    fun songsFromAlbums(type: String, albumCount: Int, perAlbum: Int): List<Song> =
        albums(type, albumCount * 2).take(albumCount * 2).shuffled().take(albumCount).flatMap { a ->
            val songs = try { album(a.id).second } catch (e: Exception) { emptyList() }
            if (type == "frequent") songs.sortedByDescending { it.playCount }.take(perAlbum)
            else songs.shuffled().take(perAlbum)
        }

    /**
     * "Sana özel karışık": kütüphane verilerinden harmanlanmış çalma sırası.
     * Beğenilenler + en çok dinlenenler + son dinlenenler + son eklenenler + rastgele,
     * sırayla birer birer dizilir ki tek bir türden art arda çok şarkı gelmesin.
     */
    fun smartMix(size: Int = 60, exclude: Set<String> = emptySet()): List<Song> {
        fun safe(block: () -> List<Song>) = try { block() } catch (e: Exception) { emptyList() }
        val fav = safe { starred() }
        // Beğenilerde en çok geçen iki tür: o türlerden de şarkı katılır
        val favGenres = fav.mapNotNull { it.genre }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(2).map { it.key }
        val lastfm = Graph.settings.serviceConfigured && Graph.settings.lastfmUser.isNotBlank()
        // Son ayın en çokları (şarkı bazında): Last.fm varsa oradan, yoksa kütüphanenin sayılarından.
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
}

/** Gurfy servisinin (server/app.py) karşılığı. Sync'te ağdan, Gurfy'de telefonda. */
interface MusicService {
    fun health(): JSONObject

    /** Last.fm dinleme geçmişi uç noktaları: "stats?…", "recommend?…", "top-library?…", "forgotten?…", "user?…". */
    fun lastfm(path: String): JSONObject
    fun listenbrainzUser(user: String): JSONObject

    /** Türü boş şarkılara Last.fm'den tür yazdırmayı başlatır / durumunu verir. */
    fun startGenreBackfill(): JSONObject
    fun genreBackfillStatus(): JSONObject

    fun similar(artist: String, title: String): List<Suggestion>
    fun search(q: String): List<Suggestion>
    fun download(t: Suggestion)
    fun downloadBatch(tracks: List<Suggestion>)

    /** Şarkının dosyasını kütüphaneden siler. */
    fun deleteSong(id: String)

    /** Ayar yedeği (yalnızca Sync); yedek yoksa null. */
    fun loadSettings(): JSONObject? = null
    fun saveSettings(data: JSONObject) {}

    fun setGenres(id: String, genres: List<String>)

    /** İndirme listesinden siler; ids boşsa bitmiş/hatalı kayıtların hepsi. */
    fun deleteJobs(ids: List<String> = emptyList())
    fun retryFailed()
    fun jobs(): List<DownloadJob>

    fun spotify(url: String): ImportedList
    fun youtube(url: String): ImportedList

    /** Önizleme sesinin adresi (30 sn dinleme). */
    fun previewUrl(t: Suggestion): String

    /** "tr" = Türkiye radyoları, "world" = dünyada en çok dinlenenler. */
    fun radios(refresh: Boolean = false, scope: String = "tr"): List<RadioStation>

    /** Radyoda o an çalan şarkıyı bulur (önce yayın bilgisi, yoksa ses kaydı + Shazam). */
    fun recognize(url: String, station: String, forceAudio: Boolean, streamTitle: String? = null): Recognition

    /** "tr" = Türkiye, "world" = dünya en çok dinlenenler (Last.fm). */
    fun charts(scope: String): List<Suggestion>

    /** Bağlantıya bakıp doğru kaynaktan listeyi getirir. */
    fun importList(url: String): ImportedList {
        val u = url.lowercase()
        return when {
            "spotify" in u -> spotify(url)
            "youtube" in u || "youtu.be" in u -> youtube(url)
            else -> throw java.io.IOException("Spotify veya YouTube çalma listesi bağlantısı girin")
        }
    }
}

/** Arayüzde uygulamaya göre değişen sözcükler (Sync'te kütüphane sunucuda, Gurfy'de telefonda). */
object Words {
    private val remote get() = Graph.backend.hasRemoteLibrary
    val download get() = if (remote) "Sunucuya indir" else "İndir"
    val downloading get() = if (remote) "Sunucuya indiriliyor" else "İndiriliyor"
    val delete get() = if (remote) "Sunucudan sil" else "Kalıcı olarak sil"
    val deleteQuestion get() = if (remote) "Sunucudan silinsin mi?" else "Şarkı silinsin mi?"
    val deleteWhere get() = if (remote) "sunucudaki müzik klasöründen" else "telefonun Music/Gurfy klasöründen"
    val deleted get() = if (remote) "sunucudan silindi" else "silindi"
}
