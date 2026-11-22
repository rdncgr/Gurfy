package com.rdncgr.gurfy

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rdncgr.gurfy.shared.Graph
import com.rdncgr.gurfy.shared.InfoTitle
import com.rdncgr.gurfy.shared.errorText
import com.rdncgr.gurfy.shared.md5
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kullanıcı verisinin telefondaki JSON yedeği: Documents/Gurfy/gurfy-yedek.json.
 * Şarkı dosyaları zaten Music/Gurfy'de kalıyor; bu dosya veritabanındaki geri kalanı taşır:
 * beğeniler, dinleme sayıları, türler, çalma listeleri, radyolarım, "Radyoda duyduklarım" ve ayarlar.
 * Uygulama silinse de dosya kalır.
 *
 * Uygulama arka plana geçince (değişiklik varsa) kendiliğinden yazılır.
 * Android, yeniden kurulan uygulamanın eski kurulumun yazdığı belgeyi kendiliğinden okumasına izin
 * vermiyor: yeniden kurulumda bir kez "Yedeği seç" denir ve dosya seçilir. Seçilen dosya
 * bundan sonra yedeğin kendisi olur (üzerine yazılır).
 */
object Backup {
    const val FILE = "gurfy-yedek.json"
    const val FOLDER = "Documents/Gurfy/"
    private const val VERSION = 1

    /**
     * Yedeklenen ayarlar. Last.fm gizli anahtarı ve oturumu dosyaya yazılmaz (paylaşılan klasörde
     * duruyor): geri yüklemeden sonra bir kez "Last.fm'e bağlan" denir.
     */
    private val keys = setOf(
        "lastfmUser", "listenbrainzUser", "lfmApiKey", "crossfadeSec", "playMode", "repeatMode", "radioShuffle",
        "homeOrder", "homeHidden", "searchHistory", "heard", "skips",
    )

    private val prefs get() = Graph.appContext.getSharedPreferences("yedek", Context.MODE_PRIVATE)

    /** Yeniden kurulum sorusu gösterilsin mi (açılıştaki uzlaştırmadan sonra karar verilir). */
    var askRestore by mutableStateOf(false)
    var status by mutableStateOf("")
        private set

    fun lastOk(): Long? = prefs.getLong("savedAt", 0).takeIf { it > 0 }

    fun describe(): String = lastOk()?.let {
        "$FOLDER$FILE · son yedek " + SimpleDateFormat("d MMM HH:mm", Locale("tr")).format(Date(it))
    } ?: "Henüz yedek alınmadı"

    // ------------------------------------------------------------------ yazma

    private var pending: Job? = null

    /** Arka plana geçince: birkaç saniye bekleyip (art arda geçişleri birleştirir) değiştiyse yazar. */
    fun saveSoon() {
        pending?.cancel()
        pending = Local.io.launch {
            delay(3_000)
            save(force = false)
        }
    }

    @Synchronized
    fun save(force: Boolean) {
        // Yeni kurulumda boş veritabanı eski yedeğin üzerine yazılmasın: önce geri yükleme sorulur
        if (!prefs.getBoolean("decided", false)) return
        try {
            val data = export()
            val text = data.toString(1)
            val hash = md5(data.optJSONObject("content").toString())
            if (!force && hash == prefs.getString("hash", null)) return
            write(text)
            prefs.edit().putString("hash", hash).putLong("savedAt", System.currentTimeMillis()).apply()
            status = ""
        } catch (e: Exception) {
            status = errorText(e, "Yedek yazılamadı")
            android.util.Log.w("Gurfy", "Yedek yazılamadı", e)
        }
    }

    private fun write(text: String) {
        val cr = Graph.appContext.contentResolver
        val bytes = text.toByteArray()
        prefs.getString("uri", null)?.let { saved ->
            try {
                cr.openOutputStream(Uri.parse(saved), "wt")?.use { it.write(bytes); return }
            } catch (e: Exception) {
                // dosya silinmiş ya da artık bizim değil: yenisi açılır
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        val uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw java.io.IOException("Documents klasörüne yazılamadı")
        cr.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        prefs.edit().putString("uri", uri.toString()).apply()
    }

    private fun export(): JSONObject {
        val db = Local.db
        val songs = JSONArray()
        db.songs().all().forEach { s ->
            // Yalnızca kullanıcı verisi olan şarkılar (geri kalanı dosyalardan zaten gelir)
            if (!s.starred && s.playCount == 0 && s.youtubeId == null && s.genre.isNullOrBlank()) return@forEach
            songs.put(
                JSONObject().put("id", s.id).put("artist", s.artist).put("title", s.title).put("album", s.album)
                    .put("genre", s.genre ?: "").put("youtubeId", s.youtubeId ?: "").put("starred", s.starred)
                    .put("starredAt", s.starredAt).put("playCount", s.playCount).put("lastPlayed", s.lastPlayed)
                    .put("addedAt", s.addedAt),
            )
        }
        val byId = db.songs().all().associateBy { it.id }
        val playlists = JSONArray()
        db.playlists().all().forEach { p ->
            val items = JSONArray()
            db.playlists().items(p.id).forEach { i ->
                val s = byId[i.songId] ?: return@forEach
                items.put(JSONObject().put("id", s.id).put("artist", s.artist).put("title", s.title))
            }
            playlists.put(JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("songs", items))
        }
        val radios = JSONArray()
        db.radios().all().forEach { r ->
            radios.put(JSONObject().put("name", r.name).put("url", r.url).put("homepage", r.homepage))
        }
        val content = JSONObject().put("songs", songs).put("playlists", playlists).put("radios", radios)
            .put("settings", Graph.settings.export(keys))
        return JSONObject().put("app", "Gurfy").put("version", VERSION)
            .put("savedAt", System.currentTimeMillis()).put("content", content)
    }

    // ------------------------------------------------------------------ geri yükleme

    /** Açılışta (kütüphane uzlaştırıldıktan sonra): ilk açılışta yedek sorulsun mu? */
    fun afterStart() {
        if (prefs.getBoolean("decided", false)) return
        // Güncellemeyle gelen (yeni kurulmamış) uygulamada veri zaten yerinde: sorulmaz
        val ctx = Graph.appContext
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (info.firstInstallTime != info.lastUpdateTime) { decide(); return }
        val db = Local.db
        val fresh = db.playlists().all().isEmpty() && db.songs().all().none { it.starred || it.playCount > 0 }
        // Music/Gurfy'de şarkı var ama veritabanı boş: büyük olasılıkla yeniden kurulum
        if (fresh && db.songs().all().isNotEmpty()) askRestore = true else decide()
    }

    /** Geri yükleme sorusu yanıtlandı (ya da gerekmedi): bundan sonra yedek yazılır. */
    fun decide() {
        prefs.edit().putBoolean("decided", true).apply()
        askRestore = false
        Local.io.launch { save(force = true) }
    }

    /** Seçilen yedek dosyasını okur ve veritabanına karıştırır. Dönen metin kullanıcıya gösterilir. */
    fun restore(uri: Uri): String {
        val cr = Graph.appContext.contentResolver
        val root = JSONObject(cr.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
        if (root.optString("app") != "Gurfy") throw java.io.IOException("Bu bir Gurfy yedeği değil")
        val c = root.getJSONObject("content")
        val db = Local.db
        val all = db.songs().all()
        val byId = all.associateBy { it.id }
        val byName = all.groupBy { norm(it.artist) + "|" + norm(it.title) }

        fun find(o: JSONObject): SongRow? {
            val name = norm(o.optString("artist")) + "|" + norm(o.optString("title"))
            byId[o.optString("id")]?.let { if (norm(it.artist) + "|" + norm(it.title) == name) return it }
            return byName[name]?.firstOrNull()
        }

        var songCount = 0
        val songs = c.optJSONArray("songs") ?: JSONArray()
        for (i in 0 until songs.length()) {
            val o = songs.getJSONObject(i)
            val s = find(o) ?: continue
            db.songs().upsert(
                s.copy(
                    starred = s.starred || o.optBoolean("starred"),
                    starredAt = maxOf(s.starredAt, o.optLong("starredAt")),
                    playCount = maxOf(s.playCount, o.optInt("playCount")),
                    lastPlayed = maxOf(s.lastPlayed, o.optLong("lastPlayed")),
                    genre = o.optString("genre").ifBlank { null } ?: s.genre,
                    youtubeId = s.youtubeId ?: o.optString("youtubeId").ifBlank { null },
                    addedAt = o.optLong("addedAt").takeIf { it > 0 }?.let { minOf(it, s.addedAt) } ?: s.addedAt,
                ),
            )
            songCount++
        }

        var listCount = 0
        val existing = db.playlists().all().map { it.name.lowercase() }.toSet()
        val lists = c.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until lists.length()) {
            val p = lists.getJSONObject(i)
            val name = p.optString("name")
            if (name.isBlank() || name.lowercase() in existing) continue
            val id = db.playlists().insert(PlaylistRow(name = name, createdAt = p.optLong("createdAt", System.currentTimeMillis())))
            val items = p.optJSONArray("songs") ?: JSONArray()
            val rows = (0 until items.length()).mapNotNull { find(items.getJSONObject(it)) }
                .mapIndexed { pos, s -> PlaylistItemRow(playlistId = id, position = pos, songId = s.id) }
            if (rows.isNotEmpty()) db.playlists().insertItems(rows)
            listCount++
        }

        val urls = db.radios().all().map { it.url }.toSet()
        val radios = c.optJSONArray("radios") ?: JSONArray()
        for (i in 0 until radios.length()) {
            val r = radios.getJSONObject(i)
            if (r.optString("url") in urls) continue
            db.radios().insert(RadioRow(name = r.optString("name"), url = r.optString("url"), homepage = r.optString("homepage")))
        }

        c.optJSONObject("settings")?.let { Graph.settings.import(it, keys) }

        // Seçilen dosya bundan sonra yedeğin kendisi (yazma izni alındıysa)
        try {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("uri", uri.toString()).apply()
        } catch (e: Exception) { /* yalnızca okuma izni: yeni dosya açılır */ }

        Local.library.invalidate()
        return "Yedek geri yüklendi: $songCount şarkının bilgisi, $listCount çalma listesi"
    }

    /** Dosya seçicide Documents/Gurfy açık gelsin. */
    fun initialFolder(): Uri = Uri.parse(
        "content://com.android.externalstorage.documents/document/primary%3A" +
            Uri.encode(Environment.DIRECTORY_DOCUMENTS + "/Gurfy"),
    )
}

/** Dosya seçiciyi açıp seçilen yedeği geri yükler. */
@Composable
private fun rememberRestorePicker(onCancel: () -> Unit = {}) =
    rememberLauncherForActivityResult(
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent =
                super.createIntent(context, input)
                    .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Backup.initialFolder())
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },
    ) { uri ->
        if (uri == null) { onCancel(); return@rememberLauncherForActivityResult }
        Local.io.launch {
            val result = runCatching { Backup.restore(uri) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Graph.toast(result.getOrElse { errorText(it, "Yedek okunamadı") })
                if (result.isSuccess) {
                    com.rdncgr.gurfy.shared.Favorites.refresh()
                    Backup.decide()
                } else onCancel()
            }
        }
    }

/** Yeniden kurulumdan sonra bir kez: "Önceki yedeği geri yükle". */
@Composable
fun RestoreDialog() {
    // Seçici, soru kapandıktan sonra da sonucu alabilsin diye koşulsuz kaydedilir
    var picking by androidx.compose.runtime.remember { mutableStateOf(false) }
    val pick = rememberRestorePicker(onCancel = { picking = false })
    if (!Backup.askRestore || picking) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Yedeğin var mı?") },
        text = {
            Text(
                "Music/Gurfy'de şarkıların var ama beğeniler, çalma listeleri ve dinleme sayıları boş. " +
                    "Uygulamayı yeniden kurduysan ${Backup.FOLDER}${Backup.FILE} dosyasını seçerek geri getirebilirsin.",
            )
        },
        confirmButton = {
            Button(onClick = { picking = true; pick.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
                Text("Yedeği seç")
            }
        },
        dismissButton = { TextButton(onClick = { Backup.decide() }) { Text("Yedeğim yok") } },
    )
}

/** Ayarlar > Bağlantılar'daki yedek kartı. */
@Composable
fun BackupSettings() {
    val pick = rememberRestorePicker()
    InfoTitle(
        "Yedek",
        "Beğeniler, dinleme sayıları, türler, çalma listeleri, radyoların ve ayarlar ${Backup.FOLDER}${Backup.FILE} " +
            "dosyasına kendiliğinden yazılır. Uygulama silinse de kalır. Last.fm gizli anahtarı yedeğe yazılmaz.",
    )
    Text(
        Backup.status.ifBlank { Backup.describe() }, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
    )
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            Local.io.launch {
                Backup.decide()
                Backup.save(force = true)
                val msg = Backup.status.ifBlank { "Yedeklendi: ${Backup.FOLDER}${Backup.FILE}" }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Graph.toast(msg) }
            }
        }) { Text("Şimdi yedekle") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { pick.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
            Text("Yedekten geri yükle")
        }
    }
}
