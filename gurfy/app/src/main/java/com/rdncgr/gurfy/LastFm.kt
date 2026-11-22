package com.rdncgr.gurfy

import com.rdncgr.gurfy.shared.Graph
import com.rdncgr.gurfy.shared.md5
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Last.fm ve ListenBrainz: Gurfy servisindeki (server/app.py) karşılıklarının telefondaki hali.
 * Anahtar Ayarlar > Bağlantılar'dan girilir. Dinlemeleri göndermek (scrobble) için ayrıca
 * paylaşılan gizli anahtar ve bir kez Last.fm girişi gerekir; parola saklanmaz, yalnızca oturum anahtarı.
 */
object Lfm {
    private val prefs get() = Graph.settings.prefs
    var apiKey: String
        get() = prefs.getString("lfmApiKey", "") ?: ""
        set(v) = prefs.edit().putString("lfmApiKey", v.trim()).apply()
    var secret: String
        get() = prefs.getString("lfmSecret", "") ?: ""
        set(v) = prefs.edit().putString("lfmSecret", v.trim()).apply()
    var session: String
        get() = prefs.getString("lfmSession", "") ?: ""
        set(v) = prefs.edit().putString("lfmSession", v).apply()

    val ready get() = apiKey.isNotBlank()
    private val user get() = Graph.settings.lastfmUser

    private const val API = "https://ws.audioscrobbler.com/2.0/"
    private val pool = Executors.newFixedThreadPool(6)

    // ----------------------------------------------------------------- önbellek

    private class Hit(val at: Long, val value: Any?)
    private val cache = ConcurrentHashMap<String, Hit>()

    @Suppress("UNCHECKED_CAST")
    fun <T> cached(key: String, ttlSec: Int, build: () -> T): T {
        cache[key]?.let { if (System.currentTimeMillis() - it.at < ttlSec * 1000L) return it.value as T }
        val v = build()
        cache[key] = Hit(System.currentTimeMillis(), v)
        if (cache.size > 300) cache.entries.sortedBy { it.value.at }.take(100).forEach { cache.remove(it.key) }
        return v
    }

    fun forget(key: String) { cache.remove(key) }

    // ------------------------------------------------------------------- istek

    /** Last.fm hata verirse boş nesne döner (sunucudaki gibi). Anahtar yoksa açıklayıcı hata. */
    fun call(method: String, vararg params: Pair<String, Any>): JSONObject {
        if (!ready) throw IOException("Last.fm anahtarı girilmemiş: Ayarlar > Bağlantılar")
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("method", method).addQueryParameter("api_key", apiKey)
            .addQueryParameter("format", "json").addQueryParameter("autocorrect", "1")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v.toString()) } }
            .build()
        Local.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val o = try { JSONObject(r.body?.string().orEmpty()) } catch (e: Exception) { JSONObject() }
            return if (o.has("error")) JSONObject() else o
        }
    }

    private var gateLast = 0L

    /** Toplu işlerde saniyede en fazla ~4 istek. */
    fun callSlow(method: String, vararg params: Pair<String, Any>): JSONObject {
        synchronized(this) {
            val wait = 250 - (System.currentTimeMillis() - gateLast)
            if (wait > 0) Thread.sleep(wait)
            gateLast = System.currentTimeMillis()
        }
        return call(method, *params)
    }

    /** İmzalı POST (oturum, scrobble). */
    private fun signed(method: String, params: Map<String, String>): JSONObject {
        if (!ready || secret.isBlank()) throw IOException("Last.fm anahtarı ve gizli anahtarı gerekli")
        val all = (params + ("method" to method) + ("api_key" to apiKey)).toSortedMap()
        val sig = md5(all.entries.joinToString("") { it.key + it.value } + secret)
        val form = FormBody.Builder().apply {
            all.forEach { (k, v) -> add(k, v) }
            add("api_sig", sig)
            add("format", "json")
        }.build()
        Local.http.newCall(Request.Builder().url(API).post(form).build()).execute().use { r ->
            val o = try { JSONObject(r.body?.string().orEmpty()) } catch (e: Exception) { JSONObject() }
            if (o.has("error")) throw IOException("Last.fm: " + o.optString("message", "hata ${o.optInt("error")}"))
            if (!r.isSuccessful) throw IOException("Last.fm hatası: ${r.code}")
            return o
        }
    }

    /** Kullanıcı adı + parolayla oturum anahtarı alır (parola saklanmaz). */
    fun login(username: String, password: String) {
        val o = signed("auth.getMobileSession", mapOf("username" to username, "password" to password))
        session = o.getJSONObject("session").getString("key")
    }

    fun nowPlaying(artist: String, title: String, album: String?, duration: Int) {
        if (session.isBlank()) return
        val p = mutableMapOf("artist" to artist, "track" to title, "sk" to session)
        if (!album.isNullOrBlank()) p["album"] = album
        if (duration > 0) p["duration"] = duration.toString()
        signed("track.updateNowPlaying", p)
    }

    fun scrobble(artist: String, title: String, album: String?, startedAtMs: Long) {
        if (session.isBlank()) return
        val p = mutableMapOf("artist" to artist, "track" to title, "timestamp" to (startedAtMs / 1000).toString(), "sk" to session)
        if (!album.isNullOrBlank()) p["album"] = album
        signed("track.scrobble", p)
    }

    // ------------------------------------------------------------------ yardımcı

    fun list(x: Any?): List<JSONObject> = when (x) {
        is JSONArray -> (0 until x.length()).mapNotNull { x.optJSONObject(it) }
        is JSONObject -> listOf(x)
        else -> emptyList()
    }

    fun image(o: JSONObject?): String? {
        val imgs = list(o?.opt("image")).mapNotNull { it.optString("#text").takeIf { s -> s.isNotBlank() } }
        val url = imgs.lastOrNull()
        // Last.fm sanatçı görsellerini kaldırdı; hepsi aynı yıldız resmi
        return if (url != null && "2a96cbd8b46e442fc41c2b86b821562f" in url) null else url
    }

    private fun <A, B> parallel(items: List<A>, fn: (A) -> B): List<B> =
        items.map { pool.submit(Callable { fn(it) }) }.map { it.get() }

    private fun requireUser(u: String): String = u.ifBlank { throw IOException("Last.fm kullanıcı adı girilmemiş") }

    // --------------------------------------------------------- kullanıcı geçmişi

    fun userInfo(u: String): JSONObject {
        val x = call("user.getInfo", "user" to requireUser(u)).optJSONObject("user")
            ?: throw IOException("Last.fm kullanıcısı bulunamadı")
        return JSONObject().put("name", x.optString("name")).put("realname", x.optString("realname"))
            .put("playcount", x.optString("playcount").toIntOrNull() ?: 0).put("image", image(x) ?: JSONObject.NULL)
            .put("registered", x.optJSONObject("registered")?.optString("unixtime")?.toIntOrNull() ?: 0)
            .put("url", x.optString("url"))
    }

    fun topTracks(u: String, period: String, limit: Int): List<JSONObject> =
        list(call("user.getTopTracks", "user" to u, "period" to period, "limit" to limit).optJSONObject("toptracks")?.opt("track"))
            .filter { it.optString("name").isNotBlank() }
            .map {
                JSONObject().put("artist", it.optJSONObject("artist")?.optString("name").orEmpty())
                    .put("title", it.optString("name")).put("plays", it.optString("playcount").toIntOrNull() ?: 0)
            }

    fun topArtists(u: String, period: String, limit: Int): List<JSONObject> =
        list(call("user.getTopArtists", "user" to u, "period" to period, "limit" to limit).optJSONObject("topartists")?.opt("artist"))
            .filter { it.optString("name").isNotBlank() }
            .map {
                JSONObject().put("name", it.optString("name")).put("plays", it.optString("playcount").toIntOrNull() ?: 0)
                    .put("image", image(it) ?: JSONObject.NULL)
            }

    private fun topAlbums(u: String, period: String, limit: Int): List<JSONObject> =
        list(call("user.getTopAlbums", "user" to u, "period" to period, "limit" to limit).optJSONObject("topalbums")?.opt("album"))
            .filter { it.optString("name").isNotBlank() }
            .map {
                JSONObject().put("name", it.optString("name")).put("artist", it.optJSONObject("artist")?.optString("name").orEmpty())
                    .put("plays", it.optString("playcount").toIntOrNull() ?: 0).put("image", image(it) ?: JSONObject.NULL)
            }

    private val PERIODS = setOf("7day", "1month", "3month", "6month", "12month", "overall")

    fun stats(u: String, periodIn: String): JSONObject {
        val period = periodIn.takeIf { it in PERIODS } ?: "1month"
        requireUser(u)
        return cached("stats|${norm(u)}|$period", 600) {
            val fu = pool.submit(Callable { call("user.getInfo", "user" to u).optJSONObject("user") })
            val fa = pool.submit(Callable { topArtists(u, period, 10) })
            val ft = pool.submit(Callable { topTracks(u, period, 25) })
            val fb = pool.submit(Callable { topAlbums(u, period, 10) })
            JSONObject().put("user", u).put("period", period)
                .put("total", fu.get()?.optString("playcount")?.toIntOrNull() ?: 0)
                .put("artists", JSONArray(fa.get())).put("tracks", JSONArray(Local.library.withLibrary(ft.get())))
                .put("albums", JSONArray(fb.get()))
        }
    }

    fun topLibrary(u: String, periodIn: String, limitIn: Int): JSONObject {
        val period = periodIn.takeIf { it in PERIODS } ?: "1month"
        val limit = limitIn.coerceIn(5, 100)
        val tracks = cached("top-library|${norm(u)}|$period|$limit", 3600) {
            Local.library.withLibrary(topTracks(requireUser(u), period, limit)).filter { it.optBoolean("in_library") }
        }
        return JSONObject().put("tracks", JSONArray(tracks))
    }

    fun forgotten(u: String, limitIn: Int): JSONObject {
        val limit = limitIn.coerceIn(5, 100)
        val tracks = cached("forgotten|${norm(u)}|$limit", 6 * 3600) {
            requireUser(u)
            val fo = pool.submit(Callable { topTracks(u, "overall", 300) })
            val fr = pool.submit(Callable { topTracks(u, "3month", 500) })
            val heard = fr.get().map { norm(it.optString("artist")) to norm(it.optString("title")) }.toSet()
            val cands = fo.get().filter {
                it.optInt("plays") >= 3 && (norm(it.optString("artist")) to norm(it.optString("title"))) !in heard
            }
            Local.library.withLibrary(cands.take(limit * 2)).filter { it.optBoolean("in_library") }.take(limit)
        }
        return JSONObject().put("tracks", JSONArray(tracks))
    }

    /** Son 1 ay ve 1 yılda en çok dinlenen sanatçılara benzeyen, az dinlenmiş sanatçıların sevilen şarkıları. */
    fun recommend(u: String, limitIn: Int, refresh: Boolean, lbUser: String): JSONObject {
        val limit = limitIn.coerceIn(5, 80)
        val key = "recommend|${norm(u)}|$limit"
        if (refresh) {
            forget(key)
            forget("lb-explore|${norm(lbUser)}")
        }
        var tracks: List<JSONObject> = if (u.isNotBlank() && ready) cached(key, 6 * 3600) { buildRecommend(u, limit) } else emptyList()
        if (lbUser.isNotBlank()) {
            val lb = try {
                Local.library.withLibrary(lbExploration(lbUser).take(limit)).filter { !it.optBoolean("in_library") }
            } catch (e: Exception) { emptyList() }
            // İki kaynağı sırayla karıştır, aynı şarkıyı bir kez göster
            val seen = HashSet<Pair<String, String>>()
            val mixed = mutableListOf<JSONObject>()
            for (i in 0 until maxOf(tracks.size, lb.size)) {
                for (t in listOfNotNull(tracks.getOrNull(i), lb.getOrNull(i))) {
                    if (seen.add(norm(t.optString("artist")) to norm(t.optString("title")))) mixed += t
                }
            }
            tracks = mixed.take(limit)
        }
        return JSONObject().put("tracks", JSONArray(tracks))
    }

    private fun buildRecommend(u: String, limit: Int): List<JSONObject> {
        val recent = topArtists(u, "1month", 10)
        val year = topArtists(u, "12month", 15)
        val known = topArtists(u, "overall", 150).map { norm(it.optString("name")) }.toSet()
        // Ağırlık: son ay daha önemli
        val seeds = LinkedHashMap<String, Double>()
        recent.forEachIndexed { i, a -> seeds.merge(a.optString("name"), 2.0 - i * 0.1, Double::plus) }
        year.forEachIndexed { i, a -> seeds.merge(a.optString("name"), 1.0 - i * 0.05, Double::plus) }
        val seedList = seeds.entries.sortedByDescending { it.value }.take(15).map { it.key }
        val sims = parallel(seedList) { similarArtists(it, 12) }
        val score = LinkedHashMap<String, Double>()
        val because = HashMap<String, String>()
        seedList.zip(sims).forEach { (seed, lst) ->
            for ((name, match) in lst) {
                if (name.isBlank() || norm(name) in known) continue
                score.merge(name, match * seeds.getValue(seed), Double::plus)
                because.putIfAbsent(name, seed)
            }
        }
        val picks = score.entries.sortedByDescending { it.value }.take(maxOf(8, limit / 3)).map { it.key }
        val perArtist = parallel(picks) { name ->
            list(call("artist.getTopTracks", "artist" to name, "limit" to 5).optJSONObject("toptracks")?.opt("track"))
                .filter { it.optString("name").isNotBlank() }
                .map {
                    JSONObject().put("artist", it.optJSONObject("artist")?.optString("name")?.ifBlank { name } ?: name)
                        .put("title", it.optString("name")).put("because", because[name].orEmpty())
                }
        }
        // Her sanatçıdan sırayla birer şarkı (çeşitlilik)
        val out = mutableListOf<JSONObject>()
        var i = 0
        while (out.size < limit * 2 && perArtist.any { i < it.size }) {
            perArtist.forEach { if (i < it.size) out += it[i] }
            i++
        }
        return Local.library.withLibrary(out).filter { !it.optBoolean("in_library") }.take(limit)
    }

    // ---------------------------------------------------------- listeler, benzerler

    fun charts(scope: String, limit: Int = 50): List<JSONObject> = cached("charts|$scope|$limit", 3600) {
        val data = if (scope == "world") call("chart.getTopTracks", "limit" to limit)
        else call("geo.getTopTracks", "country" to "Turkey", "limit" to limit)
        list(data.optJSONObject("tracks")?.opt("track")).filter { it.optString("name").isNotBlank() }.map {
            JSONObject().put("artist", it.optJSONObject("artist")?.optString("name").orEmpty())
                .put("title", it.optString("name")).put("listeners", it.optString("listeners").toIntOrNull() ?: 0)
        }
    }.map { JSONObject(it.toString()) }

    private fun lfmSimilarArtists(name: String, limit: Int): List<Pair<String, Double>> {
        if (!ready) return emptyList()
        return list(call("artist.getSimilar", "artist" to name, "limit" to limit).optJSONObject("similarartists")?.opt("artist"))
            .filter { it.optString("name").isNotBlank() }
            .map { it.optString("name") to (it.optString("match").toDoubleOrNull() ?: 0.0) }
    }

    /** Last.fm ve ListenBrainz birleşik: ikisinin de benzer dediği sanatçılar öne çıkar. */
    fun similarArtists(name: String, limit: Int = 12): List<Pair<String, Double>> {
        val fl = pool.submit(Callable { lfmSimilarArtists(name, 25) })
        val fb = pool.submit(Callable { try { lbSimilarArtists(name) } catch (e: Exception) { emptyList() } })
        val lists = listOf(fl.get(), fb.get())
        val weights = listOf(1.0, 0.6)
        val score = LinkedHashMap<String, Double>()
        val label = HashMap<String, String>()
        val per = listOf(HashMap<String, Double>(), HashMap())
        val self = norm(name)
        lists.forEachIndexed { i, lst ->
            for ((n, m) in lst) {
                val k = norm(n)
                if (k.isEmpty() || k == self) continue
                per[i][k] = maxOf(per[i][k] ?: 0.0, m)
                score.merge(k, m * weights[i], Double::plus)
                label.putIfAbsent(k, n)
            }
        }
        for (k in per[0].keys intersect per[1].keys) score[k] = score.getValue(k) + 0.5 * minOf(per[0].getValue(k), per[1].getValue(k))
        return score.entries.sortedByDescending { it.value }.take(limit).map { label.getValue(it.key) to it.value }
    }

    fun similarTracks(artist: String, title: String, limit: Int): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val seen = HashSet<Pair<String, String>>()
        val selfKey = norm(artist) to norm(title)
        fun add(a: String, t: String) {
            val k = norm(a) to norm(t)
            if (a.isNotBlank() && t.isNotBlank() && k != selfKey && seen.add(k)) out += a to t
        }
        if (title.isNotBlank()) {
            list(call("track.getSimilar", "artist" to artist, "track" to title, "limit" to limit).optJSONObject("similartracks")?.opt("track"))
                .forEach { add(it.optJSONObject("artist")?.optString("name").orEmpty(), it.optString("name")) }
        }
        // Şarkıya göre sonuç azsa sanatçıya göre tamamla
        if (out.size < limit / 2 && artist.isNotBlank()) {
            for (name in listOf(artist) + similarArtists(artist, 8).map { it.first }) {
                if (out.size >= limit) break
                list(call("artist.getTopTracks", "artist" to name, "limit" to 3).optJSONObject("toptracks")?.opt("track"))
                    .forEach { add(it.optJSONObject("artist")?.optString("name")?.ifBlank { name } ?: name, it.optString("name")) }
            }
        }
        return out.take(limit)
    }

    fun albumOf(artist: String, title: String): String? = try {
        call("track.getInfo", "artist" to artist, "track" to title).optJSONObject("track")
            ?.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    private val artistGenres = ConcurrentHashMap<String, List<String>>()

    private fun tagsOf(o: JSONObject): List<Pair<String, Int>> =
        list(o.optJSONObject("toptags")?.opt("tag")).map { it.optString("name") to (it.optString("count").toIntOrNull() ?: it.optInt("count")) }

    /** Şarkının türü: önce şarkının, yoksa sanatçının Last.fm etiketlerinden (en çok 2). */
    fun genreFor(artist: String, title: String): List<String> {
        if (!ready || artist.isBlank()) return emptyList()
        return try {
            if (title.isNotBlank()) {
                val found = genresFromTags(tagsOf(callSlow("track.getTopTags", "artist" to artist, "track" to title)))
                if (found.isNotEmpty()) return found
            }
            val key = norm(artist)
            artistGenres[key] ?: genresFromTags(tagsOf(callSlow("artist.getTopTags", "artist" to artist))).also {
                if (artistGenres.size > 5000) artistGenres.clear()
                artistGenres[key] = it
            }
        } catch (e: Exception) { emptyList() }
    }

    // --------------------------------------------------------------- ListenBrainz

    private const val LB_UA = "Gurfy/1.0 ( https://github.com/rdncgr/Nota )"
    private const val LB_ALGO = "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"
    private val mbidFile get() = File(Graph.appContext.filesDir, "mbid.json")
    private var mbids: JSONObject? = null
    private var mbLast = 0L

    private fun lbGet(url: String): JSONObject {
        val req = Request.Builder().url(url).header("User-Agent", LB_UA).header("Accept", "application/json").build()
        Local.http.newCall(req).execute().use { r ->
            if (r.code == 404) throw IOException("ListenBrainz kullanıcısı bulunamadı")
            if (!r.isSuccessful) throw IOException("ListenBrainz cevap vermedi (${r.code})")
            return JSONObject(r.body?.string().orEmpty())
        }
    }

    /** Sanatçının MusicBrainz kimliği; bulunamayan da kaydedilir. MusicBrainz saniyede bir istek ister. */
    @Synchronized
    private fun artistMbid(name: String): String? {
        val key = norm(name).ifEmpty { return null }
        val cache = mbids ?: (try { JSONObject(mbidFile.readText()) } catch (e: Exception) { JSONObject() }).also { mbids = it }
        if (cache.has(key)) return cache.optString(key).ifEmpty { null }
        var found: String? = null
        for (attempt in 0 until 3) {
            val wait = 1100 - (System.currentTimeMillis() - mbLast)
            if (wait > 0) Thread.sleep(wait)
            mbLast = System.currentTimeMillis()
            val url = "https://musicbrainz.org/ws/2/artist/".toHttpUrl().newBuilder()
                .addQueryParameter("query", "artist:\"$name\"").addQueryParameter("fmt", "json").addQueryParameter("limit", "3").build()
            val code: Int
            val body: String
            try {
                Local.http.newCall(Request.Builder().url(url).header("User-Agent", LB_UA).build()).execute().use { r ->
                    code = r.code
                    body = r.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                return null // ağ hatası: kaydetme, sonra tekrar denensin
            }
            if (code == 503) { Thread.sleep(2000L + attempt * 2000L); continue }
            if (code != 200) return null
            for (a in list(JSONObject(body).optJSONArray("artists"))) {
                val names = listOf(a.optString("name")) + list(a.optJSONArray("aliases")).map { it.optString("name") }
                if (a.optInt("score") >= 90 && names.any { norm(it) == key }) { found = a.optString("id"); break }
            }
            cache.put(key, found ?: "")
            try { mbidFile.writeText(cache.toString()) } catch (e: Exception) { }
            return found
        }
        return null
    }

    private fun lbSimilarArtists(name: String): List<Pair<String, Double>> = cached("lb-similar|${norm(name)}", 7 * 24 * 3600) {
        val mbid = artistMbid(name) ?: return@cached emptyList<Pair<String, Double>>()
        val url = "https://labs.api.listenbrainz.org/similar-artists/json".toHttpUrl().newBuilder()
            .addQueryParameter("artist_mbids", mbid).addQueryParameter("algorithm", LB_ALGO).build()
        val rows = try {
            Local.http.newCall(Request.Builder().url(url).header("User-Agent", LB_UA).build()).execute().use { r ->
                if (r.code == 200) list(JSONArray(r.body?.string().orEmpty())) else emptyList()
            }
        } catch (e: Exception) { emptyList() }
        val ok = rows.filter { it.optString("name").isNotBlank() }
        val top = ok.maxOfOrNull { it.optDouble("score", 0.0) }?.takeIf { it > 0 } ?: 1.0
        ok.take(40).map { it.optString("name") to it.optDouble("score", 0.0) / top }
    }

    fun lbListenCount(user: String): JSONObject {
        val o = lbGet("https://api.listenbrainz.org/1/user/${java.net.URLEncoder.encode(user, "UTF-8")}/listen-count")
        return JSONObject().put("name", user).put("listens", o.optJSONObject("payload")?.optInt("count") ?: 0)
    }

    /** ListenBrainz'in kullanıcıya her hafta hazırladığı keşif listesindeki şarkılar. */
    private fun lbExploration(user: String): List<JSONObject> = cached("lb-explore|${norm(user)}", 6 * 3600) {
        val enc = java.net.URLEncoder.encode(user, "UTF-8")
        val lists = list(lbGet("https://api.listenbrainz.org/1/user/$enc/playlists/createdfor?count=25").optJSONArray("playlists"))
        val pick = lists.map { it.optJSONObject("playlist") }.firstOrNull { "exploration" in it?.optString("title").orEmpty().lowercase() }
            ?: return@cached emptyList<JSONObject>()
        val pid = pick.optString("identifier").trimEnd('/').substringAfterLast('/')
        list(lbGet("https://api.listenbrainz.org/1/playlist/$pid").optJSONObject("playlist")?.optJSONArray("track"))
            .filter { it.optString("title").isNotBlank() && it.optString("creator").isNotBlank() }
            .map { JSONObject().put("artist", it.optString("creator")).put("title", it.optString("title")).put("note", "ListenBrainz haftalık keşif") }
    }.map { JSONObject(it.toString()) }
}
