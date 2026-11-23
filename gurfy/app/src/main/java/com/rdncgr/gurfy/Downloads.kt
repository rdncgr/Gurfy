package com.rdncgr.gurfy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rdncgr.gurfy.shared.DownloadJob
import com.rdncgr.gurfy.shared.Graph
import com.rdncgr.gurfy.shared.Jobs
import com.rdncgr.gurfy.shared.Suggestion
import com.rdncgr.gurfy.shared.onUnmeteredNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * İndirme kuyruğu: sunucudaki run_job / worker'ın telefondaki hali.
 * Kuyruk Room'da durur (uygulama kapansa da kaybolmaz). İndirme sürerken ön plan servisi
 * bildirimle çalışır; Android uygulamayı kapatmaz.
 */
object Downloads {
    // YouTube'un engeline takılmamak için indirmeler arasında 20-30 sn (sunucudaki DOWNLOAD_DELAY)
    private const val DELAY_MIN = 20_000L
    private const val DELAY_MAX = 30_000L
    private const val CHUNK = 2L * 1024 * 1024
    @Volatile private var lastYoutube = 0L

    fun enqueue(tracks: List<Suggestion>) {
        val now = System.currentTimeMillis()
        val rows = tracks.filter { it.artist.isNotBlank() && it.title.isNotBlank() }.mapIndexed { i, t ->
            JobRow(
                id = UUID.randomUUID().toString().replace("-", "").take(12), artist = t.artist.trim(), title = t.title.trim(),
                album = t.album?.takeIf { it.isNotBlank() }, url = t.url?.takeIf { it.isNotBlank() }, created = now + i,
            )
        }
        Local.db.jobs().upsertAll(rows)
        Local.db.jobs().prune()
        start()
        Jobs.poke()
    }

    fun start() {
        val ctx = Graph.appContext
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
    }

    fun jobs(): List<DownloadJob> = Local.db.jobs().recent().map { DownloadJob(it.id, it.artist, it.title, it.status, it.message) }

    private fun status(job: JobRow, status: String, message: String = "") {
        Local.db.jobs().setStatus(job.id, status, message)
        Jobs.poke()
    }

    /** Sıradaki işi yapar; iş yoksa false. */
    suspend fun runNext(): Boolean {
        val job = Local.db.jobs().nextQueued(System.currentTimeMillis()) ?: return false
        status(job, "downloading", "Hazırlanıyor…")
        try {
            run(job)
        } catch (e: Exception) {
            android.util.Log.w("Gurfy", "indirme hatası: ${job.artist} - ${job.title}", e)
            val bot = e is ReCaptchaException || "429" in e.message.orEmpty() || "Sign in to confirm" in e.message.orEmpty()
            if (bot && job.retries == 0) {
                // YouTube bot engeli: 10 dk sonra bir kez daha dene (sunucudaki requeue_later)
                Local.db.jobs().upsert(
                    job.copy(status = "queued", message = "YouTube geçici olarak engelledi; 10 dk sonra yeniden denenecek",
                        retries = 1, notBefore = System.currentTimeMillis() + 600_000),
                )
                Jobs.poke()
            } else {
                status(job, "error", errorMessage(e))
            }
        }
        return true
    }

    private suspend fun run(job: JobRow) {
        val lib = Local.library
        val existing = job.url?.let { YouTube.idOf(it) }?.let(lib::findByYoutube) ?: lib.find(job.artist, job.title)
        if (existing != null) return status(job, "exists", "Kütüphanede zaten var")

        // Kaynak: bağlantı varsa o, yoksa YouTube Music şarkılarında, o da yoksa genel aramada ilk sonuç
        val url = job.url ?: (YouTube.search("${job.artist} ${job.title}").firstOrNull()
            ?: YouTube.searchVideos("${job.artist} - ${job.title} audio").firstOrNull())?.url
            ?: throw IOException("YouTube'da bulunamadı")
        val videoId = YouTube.idOf(url)
        if (videoId != null) lib.findByYoutube(videoId)?.let { return status(job, "exists", "Kütüphanede zaten var") }

        // Son indirmeden bu yana 20-30 sn geçmediyse bekle
        val wait = lastYoutube + (DELAY_MIN..DELAY_MAX).random() - System.currentTimeMillis()
        if (wait > 0) {
            var left = wait
            while (left > 0) {
                status(job, "downloading", "YouTube engeline takılmamak için ${(left + 999) / 1000} sn bekleniyor")
                delay(minOf(left, 1000))
                left -= 1000
            }
        }

        // YouTube bazen verdiği adresi ilk istekte 403 ile reddediyor: adresi yeniden çözüp 3 kez dene
        var audio = YouTube.bestAudio(url)
        if (audio.durationSec > YouTube.MAX_DURATION) throw IOException("20 dakikadan uzun; şarkı değil gibi görünüyor")
        status(job, "downloading", "İndiriliyor…")
        var bytes: ByteArray? = null
        for (attempt in 1..3) {
            try {
                bytes = fetch(audio.stream.content) { p -> status(job, "downloading", "İndiriliyor… %${(p * 100).toInt()}") }
                break
            } catch (e: IOException) {
                if (attempt == 3 || "HTTP 403" !in e.message.orEmpty()) throw e
                delay(attempt * 3000L)
                audio = YouTube.bestAudio(url)
            } finally {
                lastYoutube = System.currentTimeMillis()
            }
        }
        val raw = bytes!!

        status(job, "downloading", "Etiketleniyor…")
        val album = job.album ?: Lfm.albumOf(job.artist, job.title)
        val genres = Lfm.genreFor(job.artist, job.title)
        val cover = audio.thumb?.let { fetchSmall(it) }
        val isM4a = audio.stream.format == MediaFormat.M4A
        val data = if (isM4a) {
            try {
                Mp4Tags.write(raw, Mp4Tags.Tags(job.title, job.artist, album, genres.joinToString("; ").ifBlank { null }, cover))
            } catch (e: Exception) {
                android.util.Log.w("Gurfy", "etiket yazılamadı", e)
                raw
            }
        } else raw

        val saved = MediaFiles.save(
            Graph.appContext, job.artist, album, job.title,
            if (isM4a) "m4a" else "webm", if (isM4a) "audio/mp4" else "audio/webm", data,
        )
        val songId = MediaFiles.songId(saved.id)
        val coverFile = cover?.let { c ->
            File(Graph.appContext.filesDir, "kapak").apply { mkdirs() }.resolve("${saved.id}.jpg").also { it.writeBytes(c) }
        }
        lib.add(
            SongRow(
                id = songId, title = job.title, artist = job.artist, album = album.orEmpty(),
                genre = genres.joinToString("; ").ifBlank { null }, duration = audio.durationSec.toInt(),
                mediaUri = saved.uri.toString(), relPath = saved.relPath,
                cover = coverFile?.let { "file://" + it.absolutePath }, youtubeId = videoId,
            ),
        )
        status(job, "done", saved.relPath.removePrefix(MediaFiles.ROOT) + safeName(job.title) + if (isM4a) ".m4a" else ".webm")
    }

    /** YouTube büyük tek parça isteklerde hızı düşürüyor: 2 MB'lık parçalarla. 403'te adres yeniden çözülür. */
    private fun fetch(url: String, onProgress: (Float) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0L
        var total = -1L
        var lastReport = 0f
        while (total < 0 || pos < total) {
            val req = Request.Builder().url(url)
                .header("User-Agent", NewPipeHttp.USER_AGENT)
                .header("Range", "bytes=$pos-${pos + CHUNK - 1}")
                .build()
            Local.http.newCall(req).execute().use { r ->
                if (r.code != 200 && r.code != 206) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("Boş yanıt")
                if (total < 0) total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: body.contentLength()
                val n = body.byteStream().copyTo(out)
                pos += n
                if (r.code == 200 || n == 0L) total = pos
            }
            if (total > 0) {
                val p = pos.toFloat() / total
                if (p - lastReport >= 0.1f) { onProgress(p); lastReport = p }
            }
        }
        return out.toByteArray()
    }

    /**
     * Kapak: YouTube küçük resimleri çoğu zaman WebP ve 16:9 gelir. MP4'e yalnızca JPEG/PNG gömülebildiği için
     * ortadan kare kırpılıp en çok 600 px JPEG'e çevrilir (sunucuda bunu ffmpeg yapıyordu).
     */
    private fun fetchSmall(url: String): ByteArray? = try {
        val raw = Local.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        } ?: return null
        val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val side = minOf(bmp.width, bmp.height)
        val square = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = if (side > 600) android.graphics.Bitmap.createScaledBitmap(square, 600, 600, true) else square
        ByteArrayOutputStream().also { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    } catch (e: Exception) { null }

    /** Sunucudaki tr_error'un karşılığı. */
    fun errorMessage(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName)
        val rules = listOf(
            "private video|video is private" to "Video gizli; indirilemiyor",
            "unavailable|no longer available|removed|terminated" to "Video YouTube'dan kaldırılmış ya da erişilemiyor",
            "country|geo" to "Video bu ülkede engelli",
            "age|inappropriate" to "Yaş doğrulaması isteyen video indirilemiyor",
            "members" to "Video yalnızca kanal üyelerine açık",
            "HTTP 403" to "YouTube erişimi reddetti (403)",
            "HTTP 404" to "Kaynak bulunamadı (404)",
            "429|too many" to "YouTube çok fazla istek nedeniyle geçici olarak engelledi (429)",
            "timeout|timed out" to "Bağlantı zaman aşımına uğradı",
            "Unable to resolve host|UnknownHost" to "İnternet bağlantısı yok",
        )
        if (e is java.net.UnknownHostException) return "İnternet bağlantısı yok"
        for ((rx, tr) in rules) if (Regex(rx, RegexOption.IGNORE_CASE).containsMatchIn(m)) return tr
        return m.take(160)
    }
}

/** Kuyrukta iş varken çalışan ön plan servisi. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification("İndirmeler hazırlanıyor")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        if (loop?.isActive != true) loop = scope.launch {
            Local.db.jobs().requeueInterrupted()
            while (true) {
                val active = Local.db.jobs().activeCount()
                if (active == 0) break
                // Ayarlar > Çalma > "Yalnızca Wi-Fi'da indir": mobil veride kuyruk bekler
                if (Graph.settings.downloadWifiOnly && !onUnmeteredNetwork(this@DownloadService)) {
                    updateNotification("Wi-Fi bekleniyor: $active şarkı sırada")
                    delay(15_000)
                    continue
                }
                updateNotification("$active şarkı indiriliyor")
                if (!Downloads.runNext()) {
                    // Sırada yalnızca beklemeye alınmışlar var (bot engeli): zamanı gelince dön
                    val next = Local.db.jobs().earliestQueued() ?: break
                    delay((next - System.currentTimeMillis()).coerceIn(1_000, 60_000))
                }
            }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 1, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Gurfy")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(text))
    }

    companion object {
        private const val CHANNEL = "indirmeler"
        private const val NOTIF_ID = 42
    }
}
