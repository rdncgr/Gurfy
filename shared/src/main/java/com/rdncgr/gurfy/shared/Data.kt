package com.rdncgr.gurfy.shared

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
import kotlinx.coroutines.ensureActive
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

/** Ev sunucusunun adresleri: ilk açılışta ayarlarda hazır gelir, elle değiştirilebilir. */
const val DEFAULT_NAVIDROME_URL = "http://10.243.0.1:4533"
const val DEFAULT_SERVICE_URL = "http://10.243.0.1:8099"

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
    /** Telefonun kendi müzik kütüphanesindeki dosya (content:// adresi). */
    val localUri: String? = null,
) {
    val isRadio: Boolean get() = streamUrl != null
    val isLocal: Boolean get() = localUri != null
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
    val prefs: android.content.SharedPreferences = ctx.getSharedPreferences("gurfysync", Context.MODE_PRIVATE)

    fun export(keys: Set<String>): JSONObject = prefs.exportJson(keys)
    fun import(data: JSONObject, keys: Set<String>) = prefs.importJson(data, keys)

    /** Bu kurulumda sunucudaki ayar yedeğine bakıldı mı (bakılmadan yedek gönderilmez). */
    var backupChecked: Boolean
        get() = prefs.getBoolean("backupChecked", false)
        set(v) = prefs.edit().putBoolean("backupChecked", v).apply()

    var navidromeUrl: String
        get() = prefs.getString("navidromeUrl", DEFAULT_NAVIDROME_URL) ?: ""
        set(v) = prefs.edit().putString("navidromeUrl", v.trim().trimEnd('/')).apply()
    var user: String
        get() = prefs.getString("user", "") ?: ""
        set(v) = prefs.edit().putString("user", v.trim()).apply()
    var pass: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) = prefs.edit().putString("pass", v).apply()
    var serviceUrl: String
        get() = prefs.getString("serviceUrl", DEFAULT_SERVICE_URL) ?: ""
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
    /** Gurfy: YouTube'dan indirme kuyruğu yalnızca Wi-Fi (ücretsiz ağ) varken ilerler. */
    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean("downloadWifiOnly", false)
        set(v) = prefs.edit().putBoolean("downloadWifiOnly", v).apply()
    /** Sol el: üst başlık ve oynatıcı çubuğu aynalanır (düğmeler sola gelir). */
    val leftHandState = androidx.compose.runtime.mutableStateOf(prefs.getBoolean("leftHand", false))
    var leftHand: Boolean
        get() = leftHandState.value
        set(v) { leftHandState.value = v; prefs.edit().putBoolean("leftHand", v).apply() }
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

    /** Şarkılar arası geçiş (crossfade) süresi, saniye; 0 kapalı. */
    var crossfadeSec: Int
        get() = prefs.getInt("crossfadeSec", 0)
        set(v) = prefs.edit().putInt("crossfadeSec", v).apply()

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
    /** En son çalan radyo (JSON: name, url, favicon, fav). Ana sayfadaki radyo düğmesi bunu açar. */
    var lastRadio: String
        get() = prefs.getString("lastRadio", "") ?: ""
        set(v) = prefs.edit().putString("lastRadio", v).apply()

    /** Rastgele radyonun havuzu: "tr", "world" ya da "mix". */
    var radioPool: String
        get() = prefs.getString("radioPool", "mix") ?: "mix"
        set(v) = prefs.edit().putString("radioPool", v).apply()

    /** Sunucu indirme listesinin son hali (servise ulaşılamazsa da görünsün). */
    var jobsJson: String
        get() = prefs.getString("jobs", "[]") ?: "[]"
        set(v) = prefs.edit().putString("jobs", v).apply()

    /** Radyoda tanınan şarkılar (en yenisi başta), JSON dizi. */
    var heardJson: String
        get() = prefs.getString("heard", "[]") ?: "[]"
        set(v) = prefs.edit().putString("heard", v).apply()

    /** Kütüphane kullanılabilir mi (Sync: Navidrome bilgileri girilmiş mi; Gurfy: hep). */
    val configured: Boolean get() = Graph.backend.libraryReady
    /** Servis kullanılabilir mi (Sync: servis adresi girilmiş mi; Gurfy: hep). */
    val serviceConfigured: Boolean get() = Graph.backend.serviceReady
}

// ---------------------------------------------------------------- yardımcılar

/** Wi-Fi ya da başka ücretsiz ağ (mobil veri değil). */
fun onUnmeteredNetwork(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

fun JSONObject.list(name: String): List<JSONObject> {
    val arr: JSONArray = optJSONArray(name) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

fun JSONObject.strOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

fun songOf(o: JSONObject) = Song(
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

fun albumOf(o: JSONObject) = Album(
    id = o.optString("id"),
    name = o.optString("name", o.optString("title", "?")),
    artist = o.optString("artist", ""),
    coverArt = o.strOrNull("coverArt"),
    songCount = o.optInt("songCount", 0),
)

fun artistOf(o: JSONObject) = Artist(o.optString("id"), o.optString("name", "?"), o.optInt("albumCount", 0))

// ---------------------------------------------------------- çevrimdışı dosyalar

class Offline(ctx: Context, private val subsonic: Library) {
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
                val req = Request.Builder().url(subsonic.downloadUrl(song.id)).build()
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
    private var job: kotlinx.coroutines.Job? = null

    /** Kullanıcı otomatik indirmeyi kapattı: süren eşitlemeyi durdur (inen şarkı biter, sonrakiler inmez). */
    fun cancel() {
        val j = job ?: return
        if (j.isActive) {
            j.cancel()
            status = "Otomatik indirme durduruldu"
        }
    }


    /** force = kullanıcı "Şimdi eşitle" dedi: zaman ve Wi-Fi sınırını yok say. */
    fun run(force: Boolean = false) {
        val s = Graph.settings
        if (running || !s.configured || !Graph.backend.hasRemoteLibrary) return
        if (!force) {
            if (!s.autoOffline) return
            if (System.currentTimeMillis() - s.lastAutoSync < INTERVAL_MS) return
            if (s.wifiOnly && !onUnmeteredNetwork(Graph.appContext)) return
        }
        running = true
        status = "Sık dinlenenler bulunuyor…"
        job = Graph.scope.launch {
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
                    ensureActive() // kapatıldıysa sıradakine geçme
                    status = "İndiriliyor ${i + 1}/${missing.size}: ${song.title}"
                    try {
                        Graph.offline.download(song)
                        done++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                status = "Otomatik indirme durduruldu"
            } catch (e: Exception) {
                status = "Eşitlenemedi: " + (errorText(e, "bağlantı yok"))
            } finally {
                running = false
                job = null
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

/** Ana sayfadaki bölümlerin sırası ve görünürlüğü; Ayarlar > Genel'den değiştirilir. */
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

    /** Uygulamanın veri kaynağı; her uygulama Application.onCreate'te doldurur. */
    var backendFactory: ((Context, Settings) -> Backend)? = null
    lateinit var backend: Backend
    /** Kütüphane (Navidrome ya da telefondaki veritabanı). Adı eski kodla uyum için korunur. */
    lateinit var subsonic: Library
    /** Servis (Gurfy servisi ya da telefondaki karşılığı). */
    lateinit var service: MusicService
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
        val factory = backendFactory ?: error("Graph.backendFactory tanımlı değil (Application.onCreate)")
        backend = factory(appContext, settings)
        subsonic = backend.library
        service = backend.service
        offline = Offline(appContext, subsonic)
        player = PlayerController(appContext)
        HomeLayout.load()
        ready = true
        backend.start(appContext)
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

/** SharedPreferences ↔ JSON (türüyle birlikte). */
fun android.content.SharedPreferences.exportJson(keys: Set<String>): JSONObject {
    val o = JSONObject()
    all.filterKeys { it in keys }.forEach { (k, v) ->
        val (t, value) = when (v) {
            is Boolean -> "b" to v
            is Int -> "i" to v
            is Long -> "l" to v
            is Float -> "f" to v.toDouble()
            is Set<*> -> "s" to JSONArray(v.map { it.toString() })
            else -> "t" to v.toString()
        }
        o.put(k, JSONObject().put("t", t).put("v", value))
    }
    return o
}

fun android.content.SharedPreferences.importJson(data: JSONObject, keys: Set<String>) {
    val e = edit()
    data.keys().forEach { k ->
        if (k !in keys) return@forEach
        val item = data.optJSONObject(k) ?: return@forEach
        when (item.optString("t")) {
            "b" -> e.putBoolean(k, item.optBoolean("v"))
            "i" -> e.putInt(k, item.optInt("v"))
            "l" -> e.putLong(k, item.optLong("v"))
            "f" -> e.putFloat(k, item.optDouble("v").toFloat())
            "s" -> e.putStringSet(k, item.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            else -> e.putString(k, item.optString("v"))
        }
    }
    e.commit()
}
