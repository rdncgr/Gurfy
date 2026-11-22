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

/** Ev sunucusundaki Gurfy servisi (server/app.py) istemcisi. */
class ServiceApi(private val s: Settings) : MusicService {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun exec(req: Request.Builder): JSONObject {
        if (!s.serviceConfigured) throw IOException("Önce Ayarlar'dan Gurfy servis adresini girin")
        req.header("X-API-Key", s.serviceKey)
        http.newCall(req.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val detail = try { JSONObject(body).optString("detail") } catch (e: Exception) { "" }
                // FastAPI'nin kendi "Not Found" cevabı: bu özellik sunucudaki eski app.py'de yok
                if (resp.code == 404 && detail == "Not Found") {
                    throw IOException("Sunucudaki servis bu özelliği bilmiyor; güncel app.py'yi yükleyip servisi yeniden başlat")
                }
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

    override fun health(): JSONObject = get("/health")

    /** Last.fm dinleme geçmişi uç noktaları (/lastfm/...). */
    override fun lastfm(path: String): JSONObject = get("/lastfm/$path")

    override fun listenbrainzUser(user: String): JSONObject =
        get("/listenbrainz/user?user=" + java.net.URLEncoder.encode(user, "UTF-8"))

    /** Türü boş şarkılara Last.fm'den tür yazdırmayı başlatır / durumunu verir. */
    override fun startGenreBackfill(): JSONObject = post("/genres/backfill", JSONObject())
    override fun genreBackfillStatus(): JSONObject = get("/genres/backfill")

    override fun similar(artist: String, title: String): List<Suggestion> =
        suggestions(get("/similar?artist=${enc(artist)}&title=${enc(title)}"))

    override fun search(q: String): List<Suggestion> = suggestions(get("/search?q=${enc(q)}"))

    override fun download(t: Suggestion) { post("/download", trackJson(t)); Jobs.poke() }

    override fun downloadBatch(tracks: List<Suggestion>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(trackJson(it)) }
        post("/download/batch", JSONObject().put("tracks", arr))
        Jobs.poke()
    }

    /** Şarkının dosyasını sunucudaki müzik klasöründen siler (Navidrome taraması da başlar). */
    override fun deleteSong(id: String) { post("/song/delete", JSONObject().put("id", id)) }

    /** Ayar yedeği; sunucuda yedek yoksa null. */
    override fun loadSettings(): JSONObject? = get("/settings").optJSONObject("data")

    override fun saveSettings(data: JSONObject) { post("/settings", JSONObject().put("data", data)) }

    override fun setGenres(id: String, genres: List<String>) {
        post("/song/genre", JSONObject().put("id", id).put("genres", org.json.JSONArray(genres)))
    }

    /** İndirme listesinden siler; ids boşsa bitmiş/hatalı kayıtların hepsi. */
    override fun deleteJobs(ids: List<String>) {
        post("/jobs/delete", JSONObject().put("ids", JSONArray(ids)))
        Jobs.poke()
    }

    override fun retryFailed() { post("/jobs/retry", JSONObject()); Jobs.poke() }

    override fun jobs(): List<DownloadJob> = get("/jobs").list("jobs").map {
        DownloadJob(it.optString("id"), it.optString("artist"), it.optString("title"), it.optString("status"), it.optString("message"))
    }

    override fun spotify(url: String): ImportedList {
        val o = post("/spotify/playlist", JSONObject().put("url", url))
        return ImportedList(o.optString("name", "Spotify listesi"), suggestions(o))
    }

    /** Önizleme sesinin adresi; servis sesi YouTube'dan alıp aktarır. */
    override fun previewUrl(t: Suggestion): String {
        val sb = StringBuilder(s.serviceUrl).append("/preview?artist=").append(enc(t.artist))
            .append("&title=").append(enc(t.title)).append("&key=").append(enc(s.serviceKey))
        t.url?.takeIf { it.isNotBlank() }?.let { sb.append("&url=").append(enc(it)) }
        return sb.toString()
    }

    /** Sunucuda saklanan güncel Türkiye radyoları listesi. */
    override fun radios(refresh: Boolean, scope: String): List<RadioStation> =
        get("/radios?scope=$scope" + if (refresh) "&refresh=true" else "").list("stations").map {
            RadioStation(
                null, it.optString("name"), it.optString("url"), it.optString("homepage", ""),
                it.optString("favicon", ""), it.optString("tags", ""),
            )
        }

    /** Radyoda o an çalan şarkıyı bulur (önce yayın bilgisi, yoksa 10 sn kayıt + Shazam). */
    override fun recognize(url: String, station: String, forceAudio: Boolean, streamTitle: String?): Recognition {
        val req = JSONObject().put("url", url).put("name", station).put("force_audio", forceAudio)
        if (!streamTitle.isNullOrBlank()) req.put("stream_title", streamTitle) // çalar başlığı zaten okuduysa
        return recognition(post("/recognize", req), station)
    }

    /** Şarkı bulma topu: telefonda kaydedilen ses WAV olarak sunucuya gider, sunucu Shazam'a sorar. */
    override fun recognizeAudio(pcm: ShortArray, source: String): Recognition {
        val wav = Wav.encode(pcm, 16000)
        val o = exec(Request.Builder().url(s.serviceUrl + "/recognize/audio").post(wav.toRequestBody("audio/wav".toMediaType())))
        return recognition(o, source)
    }

    private fun recognition(o: JSONObject, station: String): Recognition {
        if (!o.optBoolean("found")) return Recognition(null, o.optString("source"), null, station,
            o.optString("message", "Şarkı tanınamadı"))
        val s = Suggestion(o.optString("artist"), o.optString("title"), o.strOrNull("album"), o.optBoolean("in_library"))
        return Recognition(s, o.optString("source"), o.strOrNull("cover"), station, null)
    }

    /** "tr" = Türkiye, "world" = dünya en çok dinlenenler (Last.fm). */
    override fun charts(scope: String): List<Suggestion> = suggestions(get("/charts?scope=$scope"))

    override fun youtube(url: String): ImportedList {
        val o = post("/youtube/playlist", JSONObject().put("url", url))
        return ImportedList(o.optString("name", "YouTube listesi"), suggestions(o))
    }
}
