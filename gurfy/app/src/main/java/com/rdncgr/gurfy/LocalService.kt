package com.rdncgr.gurfy

import android.content.Context
import android.net.Uri
import com.rdncgr.gurfy.shared.DownloadJob
import com.rdncgr.gurfy.shared.Graph
import com.rdncgr.gurfy.shared.ImportedList
import com.rdncgr.gurfy.shared.Jobs
import com.rdncgr.gurfy.shared.LiveMeta
import com.rdncgr.gurfy.shared.MusicService
import com.rdncgr.gurfy.shared.RadioStation
import com.rdncgr.gurfy.shared.Recognition
import com.rdncgr.gurfy.shared.Suggestion
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/**
 * Gurfy servisinin (server/app.py) telefondaki karşılığı. Arayüz bunu sunucuyu kullandığı gibi kullanır
 * (Graph.service); dönen JSON'lar sunucununkiyle aynı biçimde.
 */
class LocalService(private val ctx: Context) : MusicService {
    private val lib get() = Local.library

    private fun suggestion(artist: String, title: String, album: String? = null, url: String? = null, note: String? = null) =
        Suggestion(artist, title, album, lib.find(artist, title) != null, url, note)

    override fun health(): JSONObject = JSONObject()
        .put("lastfm", Lfm.ready).put("lastfm_session", Lfm.session.isNotBlank())
        .put("songs", lib.all().size)

    // ------------------------------------------------------------- Last.fm geçmişi

    /** "stats?user=…&period=…" gibi sunucu yollarını çözer. */
    override fun lastfm(path: String): JSONObject {
        val name = path.substringBefore('?')
        val q = path.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val user = q["user"].orEmpty()
        return when (name) {
            "user" -> Lfm.userInfo(user)
            "stats" -> Lfm.stats(user, q["period"] ?: "1month")
            "recommend" -> Lfm.recommend(user, q["limit"]?.toIntOrNull() ?: 40, q["refresh"] == "true", q["lb_user"].orEmpty())
            "top-library" -> Lfm.topLibrary(user, q["period"] ?: "1month", q["limit"]?.toIntOrNull() ?: 50)
            "forgotten" -> Lfm.forgotten(user, q["limit"]?.toIntOrNull() ?: 40)
            else -> throw IOException("Bilinmeyen Last.fm isteği: $name")
        }
    }

    override fun listenbrainzUser(user: String): JSONObject = Lfm.lbListenCount(user)

    // ------------------------------------------------------------------ arama

    /** Sunucu Last.fm'de arıyordu; telefonda anahtar gerektirmeyen YouTube Music şarkı araması. */
    override fun search(q: String): List<Suggestion> =
        YouTube.search(q).take(25).map { suggestion(it.artist, it.title, url = it.url) }

    override fun similar(artist: String, title: String): List<Suggestion> =
        Lfm.similarTracks(artist, title, 30).map { (a, t) -> suggestion(a, t) }

    override fun charts(scope: String): List<Suggestion> =
        Lfm.charts(if (scope == "world") "world" else "tr").map { suggestion(it.optString("artist"), it.optString("title")) }

    // ---------------------------------------------------------------- indirme

    override fun download(t: Suggestion) = Downloads.enqueue(listOf(t))
    override fun downloadBatch(tracks: List<Suggestion>) = Downloads.enqueue(tracks)
    override fun jobs(): List<DownloadJob> = Downloads.jobs()

    override fun deleteJobs(ids: List<String>) {
        if (ids.isEmpty()) Local.db.jobs().deleteFinished() else Local.db.jobs().delete(ids)
        Jobs.poke()
    }

    override fun retryFailed() {
        Local.db.jobs().retryFailed()
        Downloads.start()
        Jobs.poke()
    }

    /** Önizleme: YouTube ses akışının adresi doğrudan (sunucu önce indiriyordu). */
    override fun previewUrl(t: Suggestion): String {
        val url = t.url ?: YouTube.search("${t.artist} ${t.title}").firstOrNull()?.url ?: throw IOException("Önizleme bulunamadı")
        return YouTube.bestAudio(url).stream.content
    }

    // ------------------------------------------------------------- şarkı dosyası

    override fun deleteSong(id: String) {
        val uri = MediaFiles.uriOf(id) ?: throw IOException("Şarkı bulunamadı")
        try {
            MediaFiles.delete(ctx, uri)
        } catch (e: SecurityException) {
            // Yeniden kurulumdan önce yazılmış dosya: Android yalnızca kendi yazdığımızı sormadan sildirir
            throw IOException("Bu dosyayı silme izni yok; telefonun Dosyalar uygulamasından Music/Gurfy klasöründen silebilirsin")
        }
        lib.remove(id)
    }

    override fun setGenres(id: String, genres: List<String>) {
        val clean = genres.map { it.replace(Regex("\\s+"), " ").trim().take(60) }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        val joined = clean.joinToString("; ").ifEmpty { null }
        val row = lib.get(id) ?: throw IOException("Şarkı bulunamadı")
        lib.setGenre(id, joined)
        writeTags(row.copy(genre = joined))
    }

    /** Dosyadaki etiketleri Room'daki bilgiyle günceller; yazma izni yoksa yalnızca uygulamada kalır. */
    private fun writeTags(r: SongRow) {
        val uri = Uri.parse(r.mediaUri)
        try {
            val cover = r.cover?.takeIf { it.startsWith("file://") }?.let { File(it.removePrefix("file://")).takeIf { f -> f.exists() }?.readBytes() }
            MediaFiles.rewrite(ctx, uri) { Mp4Tags.write(it, Mp4Tags.Tags(r.title, r.artist, r.album.ifBlank { null }, r.genre, cover)) }
        } catch (e: Exception) {
            android.util.Log.w("Gurfy", "etiket dosyaya yazılamadı: ${r.title}", e)
        }
    }

    // ----------------------------------------------------- türü boşlara tür yazma

    private val backfill = JSONObject().put("running", false)

    override fun startGenreBackfill(): JSONObject {
        if (!Lfm.ready) throw IOException("Last.fm anahtarı girilmemiş: Ayarlar > Bağlantılar")
        synchronized(backfill) {
            if (backfill.optBoolean("running")) return JSONObject(backfill.toString())
            backfill.keys().asSequence().toList().forEach { backfill.remove(it) }
            backfill.put("running", true)
        }
        Thread {
            try {
                val songs = lib.all()
                fun set(k: String, v: Any) = synchronized(backfill) { backfill.put(k, v) }
                fun inc(k: String) = synchronized(backfill) { backfill.put(k, backfill.optInt(k) + 1) }
                listOf("scanned", "updated", "had_genre", "not_found", "errors").forEach { set(it, 0) }
                set("total", songs.size)
                set("started_at", System.currentTimeMillis() / 1000.0)
                for (r in songs) {
                    inc("scanned")
                    set("current", "${r.artist} - ${r.title}")
                    try {
                        if (!r.genre.isNullOrBlank()) { inc("had_genre"); continue }
                        val g = Lfm.genreFor(r.artist, r.title)
                        if (g.isEmpty()) { inc("not_found"); continue }
                        val joined = g.joinToString("; ")
                        lib.setGenre(r.id, joined)
                        writeTags(r.copy(genre = joined))
                        inc("updated")
                    } catch (e: Exception) {
                        inc("errors")
                    }
                }
                set("current", "")
                set("finished_at", System.currentTimeMillis() / 1000.0)
            } catch (e: Exception) {
                synchronized(backfill) { backfill.put("error", e.message.orEmpty().take(200)) }
            } finally {
                synchronized(backfill) { backfill.put("running", false) }
            }
        }.start()
        return genreBackfillStatus()
    }

    override fun genreBackfillStatus(): JSONObject = synchronized(backfill) { JSONObject(backfill.toString()) }

    // --------------------------------------------------------------- liste aktarma

    override fun spotify(url: String): ImportedList {
        val pid = Regex("playlist[/:]([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            ?: throw IOException("Bu bir Spotify çalma listesi bağlantısı değil")
        val req = Request.Builder().url("https://open.spotify.com/embed/playlist/$pid")
            .header("User-Agent", NewPipeHttp.USER_AGENT).build()
        val page = Local.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Spotify listesi açılamadı (liste herkese açık mı?)")
            r.body?.string().orEmpty()
        }
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1)
            ?: throw IOException("Spotify sayfası okunamadı (liste herkese açık mı?)")
        val entity = findKey(JSONObject(json), "trackList") ?: throw IOException("Spotify listesinde şarkı bulunamadı")
        val tracks = Lfm.list(entity.optJSONArray("trackList")).mapNotNull { t ->
            val artist = t.optString("subtitle").replace(' ', ' ').split(Regex(""",\s*""")).first()
            t.optString("title").takeIf { it.isNotBlank() && artist.isNotBlank() }?.let { suggestion(artist, it) }
        }
        return ImportedList(entity.optString("name").ifBlank { entity.optString("title") }.ifBlank { "Spotify listesi" }, tracks)
    }

    private fun findKey(o: Any?, key: String): JSONObject? = when (o) {
        is JSONObject -> if (o.has(key)) o else o.keys().asSequence().firstNotNullOfOrNull { findKey(o.opt(it), key) }
        is JSONArray -> (0 until o.length()).firstNotNullOfOrNull { findKey(o.opt(it), key) }
        else -> null
    }

    override fun youtube(url: String): ImportedList {
        val list = Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: throw IOException("Bu bir YouTube çalma listesi bağlantısı değil (bağlantıda 'list=' olmalı)")
        val (name, items) = try {
            YouTube.playlist("https://www.youtube.com/playlist?list=$list")
        } catch (e: Exception) {
            throw IOException("YouTube listesi açılamadı (liste gizli olabilir)")
        }
        val tracks = items.mapNotNull { e ->
            if (e.name in setOf("[Deleted video]", "[Private video]")) return@mapNotNull null
            val (artist, song) = splitYoutubeTitle(e.name, e.uploaderName)
            song.takeIf { it.isNotBlank() }?.let { suggestion(artist.ifBlank { "Bilinmeyen" }, it, url = e.url) }
        }
        return ImportedList(name.ifBlank { "YouTube listesi" }, tracks)
    }

    // ------------------------------------------------------------------- radyolar

    override fun radios(refresh: Boolean, scope: String): List<RadioStation> = Radios.load(scope == "world", refresh)

    /**
     * Radyoda çalan şarkı: önce yayının gönderdiği başlık, olmazsa (ya da forceAudio) çalan sesin son 12 sn'si
     * Shazam'a sorulur. Ses oynatıcının çıkışından alınır (AudioTap), yayına ikinci kez bağlanılmaz.
     */
    override fun recognize(url: String, station: String, forceAudio: Boolean, streamTitle: String?): Recognition {
        if (!forceAudio) {
            splitStreamTitle(streamTitle ?: LiveMeta.title, station)?.let { (a, t) ->
                return Recognition(suggestion(a, t), "metadata", null, station, null)
            }
        }
        // Yayın yeni açıldıysa en az 10 sn ses birikmesini bekle (en çok 15 sn)
        val until = System.currentTimeMillis() + 15_000
        while (AudioTap.seconds() < 10 && System.currentTimeMillis() < until) Thread.sleep(250)
        if (AudioTap.seconds() < 4) {
            return Recognition(null, "shazam", null, station, "Ses alınamadı. Radyo çalıyor mu?")
        }
        val hit = try {
            ShazamApi.recognize(ShazamSignature.create(AudioTap.last(12 * ShazamSignature.RATE)), Local.http)
        } catch (e: ShazamChanged) {
            return Recognition(
                null, "shazam", null, station,
                "Şarkı tanıma servisi (Shazam) değişmiş olabilir: ${e.message}. Yayın bilgisinden tanıma çalışmaya devam eder.",
            )
        } catch (e: Exception) {
            throw IOException("Tanıma servisine ulaşılamadı", e)
        } ?: return Recognition(
            null, "shazam", null, station,
            "Şarkı tanınamadı. Konuşma ya da reklam olabilir; biraz sonra tekrar deneyin.",
        )
        return Recognition(suggestion(hit.artist, hit.title, hit.album), "shazam", hit.cover, station, null)
    }
}

/** radio-browser.info'dan telefondan doğrudan alınan radyo listesi; günde bir yenilenir. */
object Radios {
    private val HOSTS = listOf("de1", "fi1", "nl1", "at1", "de2")
    private const val MAX_AGE = 24 * 3600 * 1000L
    private const val WORLD_COUNT = 50

    private fun cacheFile(world: Boolean) =
        File(Graph.appContext.filesDir, if (world) "radyolar-dunya.json" else "radyolar-tr.json")

    fun load(world: Boolean, refresh: Boolean = false): List<RadioStation> {
        val f = cacheFile(world)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE
        if (!refresh && fresh) parse(f.readText(), world).let { if (it.isNotEmpty()) return it }
        val text = try {
            fetch(world).also { f.writeText(it) }
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw e // ağ yoksa eski listeyle devam
        }
        return parse(text, world)
    }

    private fun fetch(world: Boolean): String {
        val path = if (world) "/json/stations/topclick/150?hidebroken=true"
        else "/json/stations/bycountrycodeexact/TR?order=clickcount&reverse=true&hidebroken=true&limit=200"
        var last: Exception? = null
        for (h in HOSTS) {
            try {
                val req = Request.Builder().url("https://$h.api.radio-browser.info$path").header("User-Agent", "Gurfy/1.0").build()
                Local.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    return r.body!!.string()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Radyo listesi alınamadı", last)
    }

    private fun parse(text: String, world: Boolean): List<RadioStation> {
        val arr = JSONArray(text)
        val seen = HashSet<String>()
        val out = ArrayList<RadioStation>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").replace(Regex("\\s+"), " ").trim()
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            if (name.isEmpty() || url.isEmpty() || !seen.add(norm(name))) continue
            // Türk radyoları kendi listesinde; dünya listesine karışmasın
            if (world && o.optString("countrycode").equals("TR", ignoreCase = true)) continue
            out += RadioStation(null, name, url, o.optString("homepage"), o.optString("favicon"), o.optString("tags"))
        }
        return if (world) out.take(WORLD_COUNT) else out
    }
}
