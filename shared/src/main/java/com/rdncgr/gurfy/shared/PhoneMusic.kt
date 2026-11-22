package com.rdncgr.gurfy.shared

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Telefonun kendi müzikleri (başka uygulamalarla indirilen ya da kopyalanan şarkılar).
// Android'in medya kütüphanesinden okunur. Gurfy'de bunlar kütüphanenin kendisidir; Gurfy Sync'te
// sunucudaki şarkılarla birlikte kütüphanede görünür. Silme de buradan yapılır.

object PhoneMusic {
    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Ses kaydedicilerin dosya türleri: müzik sayılmaz. */
    private val recordingMimes = listOf("audio/amr", "audio/amr-wb", "audio/3gpp", "audio/3gpp2")
    private val recordingExts = listOf(".amr", ".3gp", ".3ga", ".3gpp")

    /** Ses kayıtlarının durduğu klasörler (telefon markasına göre değişir). */
    private val recordingDirs = listOf(
        "Recordings/%", "%/Recordings/%", "Call/%", "%/Call/%", "%Call recordings%", "%CallRecordings%",
        "%Sound recorder%", "%Sound_recorder%", "%Voice Recorder%", "%VoiceRecorder%", "%Ses kaydedici%",
        "%Ses kayıtları%", "%Arama kayıtları%",
    )

    /**
     * Müzik sayılan dosyalar: zil / bildirim / alarm sesleri, 30 saniyeden kısa kayıtlar ve ses kayıtları
     * (Android'in işareti, dosya türü ve kayıt klasörü) dışarıda kalır. Gurfy'nin taraması da bunu kullanır.
     */
    fun selection(): Pair<String, Array<String>> {
        val where = StringBuilder(
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000" +
                " AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0" +
                " AND ${MediaStore.Audio.Media.IS_ALARM} = 0 AND ${MediaStore.Audio.Media.IS_PODCAST} = 0",
        )
        val args = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) where.append(" AND ${MediaStore.Audio.Media.IS_RECORDING} = 0")
        where.append(" AND (${MediaStore.Audio.Media.MIME_TYPE} IS NULL OR ${MediaStore.Audio.Media.MIME_TYPE} NOT IN (")
        where.append(recordingMimes.joinToString(",") { "?" }).append("))")
        args += recordingMimes
        recordingExts.forEach {
            where.append(" AND ${MediaStore.Audio.Media.DISPLAY_NAME} NOT LIKE ?")
            args += "%$it"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            recordingDirs.forEach {
                where.append(" AND IFNULL(${MediaStore.Audio.Media.RELATIVE_PATH}, '') NOT LIKE ?")
                args += it
            }
        }
        return where.toString() to args.toTypedArray()
    }

    /** Telefon şarkısı ve eklendiği an (ms). */
    class Item(val song: Song, val added: Long)

    @Volatile private var cache: Pair<Long, List<Item>>? = null

    /** Silme ya da izin değişikliğinden sonra listeyi yeniden okut. */
    fun invalidate() { cache = null }

    /**
     * Telefondaki müzikler. Gurfy'de Music/Gurfy klasörü zaten kütüphane olduğu için burada atlanır.
     * Sonuç 30 saniye bellekte tutulur (kütüphane ekranları art arda çağırır).
     */
    fun items(ctx: Context): List<Item> {
        if (!allowed(ctx)) return emptyList()
        cache?.let { (t, list) -> if (System.currentTimeMillis() - t < 30_000) return list }
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        if (Build.VERSION.SDK_INT >= 30) cols += MediaStore.Audio.Media.GENRE
        val out = mutableListOf<Item>()
        val (base, baseArgs) = selection()
        val skipLibrary = !Graph.backend.hasRemoteLibrary && Build.VERSION.SDK_INT >= 29
        val where = base + if (skipLibrary) " AND IFNULL(${MediaStore.Audio.Media.RELATIVE_PATH}, '') NOT LIKE ?" else ""
        val args = if (skipLibrary) baseArgs + "Music/Gurfy/%" else baseArgs
        ctx.contentResolver.query(
            uri, cols.toTypedArray(), where, args, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        )?.use { c ->
            val unknown = MediaStore.UNKNOWN_STRING
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val artist = c.getString(2)?.takeIf { it.isNotBlank() && it != unknown } ?: "Bilinmeyen sanatçı"
                val album = c.getString(3)?.takeIf { it.isNotBlank() && it != unknown } ?: ""
                out += Item(
                    Song(
                        id = "local:$id",
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "?",
                        artist = artist,
                        album = album,
                        albumId = "phone-album:" + md5(norm(artist) + "|" + norm(album)).take(16),
                        coverArt = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"), c.getLong(4),
                        ).toString(),
                        duration = (c.getLong(5) / 1000).toInt(),
                        genre = if (cols.size > 7) c.getString(7)?.takeIf { it.isNotBlank() } else null,
                        localUri = ContentUris.withAppendedId(uri, id).toString(),
                    ),
                    added = c.getLong(6) * 1000,
                )
            }
        }
        cache = System.currentTimeMillis() to out
        return out
    }

    fun songs(ctx: Context): List<Song> = items(ctx).map { it.song }

    /** Aynı şarkıyı iki yerde göstermemek için: sanatçı + şarkı adı, harf ve rakam dışı karakterler atılarak. */
    fun norm(s: String) = s.lowercase(java.util.Locale("tr")).replace(Regex("[^\\p{L}\\p{N}]"), "")
    fun keyOf(s: Song) = norm(s.artist) + "|" + norm(s.title)

    // ------------------------------------------------------------------ silme

    /** Android ≤ 9'da başka uygulamanın dosyasını silmek için yazma izni gerekir. */
    private val needsWritePermission get() = Build.VERSION.SDK_INT <= 28

    private class Attempt(val deleted: List<Song>, val ask: List<Song>, val sender: IntentSender?, val failed: Int)

    private fun tryDelete(ctx: Context, songs: List<Song>): Attempt {
        val cr = ctx.contentResolver
        val deleted = mutableListOf<Song>()
        val ask = mutableListOf<Pair<Song, Uri>>()
        var recoverable: IntentSender? = null
        var failed = 0
        for (s in songs) {
            val uri = Graph.subsonic.phoneUri(s)?.let(Uri::parse)
            if (uri == null) { failed++; continue }
            try {
                if (cr.delete(uri, null, null) > 0) deleted += s else failed++
            } catch (e: SecurityException) {
                // Başka uygulamanın koyduğu dosya: Android kullanıcıya sormadan sildirmez
                ask += s to uri
                if (Build.VERSION.SDK_INT == 29 && recoverable == null && e is RecoverableSecurityException) {
                    recoverable = e.userAction.actionIntent.intentSender
                }
            }
        }
        val sender = when {
            ask.isEmpty() -> null
            Build.VERSION.SDK_INT >= 30 -> MediaStore.createDeleteRequest(cr, ask.map { it.second }).intentSender
            else -> recoverable
        }
        // Android 10'da sistem tek dosya için izin sorar; kalanlar ayrıca silinmeli
        val askNow = if (sender == null) emptyList() else if (Build.VERSION.SDK_INT >= 30) ask.map { it.first } else ask.take(1).map { it.first }
        failed += ask.size - askNow.size
        return Attempt(deleted, askNow, sender, failed)
    }

    /** Silinen şarkıları uygulamanın her yerinden kaldırır. */
    internal fun finishDelete(songs: List<Song>, failed: Int) {
        if (songs.isEmpty()) {
            if (failed > 0) Graph.toast("Silinemedi")
            return
        }
        Graph.scope.launch {
            try {
                withContext(Dispatchers.IO) { Graph.subsonic.phoneDeleted(songs.map { it.id }) }
            } catch (e: Exception) { /* liste bir sonraki taramada düzelir */ }
            invalidate()
            songs.forEach {
                Deleted.ids[it.id] = true
                Graph.player.remove(it.id)
            }
            Graph.listsVersion++
            val msg = if (songs.size == 1) "\"${songs[0].title}\" telefondan silindi" else "${songs.size} şarkı telefondan silindi"
            Graph.toast(if (failed > 0) "$msg, $failed şarkı silinemedi" else msg)
        }
    }

    /** Sorusuz silinebilenleri siler; Android'in onayı gerekenler için [ask] çağrılır. */
    internal fun runDelete(
        ctx: Context, songs: List<Song>,
        ask: (sender: IntentSender, ask: List<Song>, failed: Int, deleted: List<Song>) -> Unit,
        done: (deleted: List<Song>, failed: Int) -> Unit = ::finishDelete,
    ) {
        Graph.scope.launch {
            val a = withContext(Dispatchers.IO) { tryDelete(ctx, songs) }
            if (a.sender != null) ask(a.sender, a.ask, a.failed, a.deleted) else done(a.deleted, a.failed)
        }
    }
}

/**
 * Uygulamanın her yerinden telefon dosyası silme isteği. Android'in onay penceresinin sonucu,
 * isteyen satır ekrandan kalksa da kaybolmasın diye işi ekranın kökündeki [PhoneDeleteHost] yapar.
 */
object PhoneDelete {
    var request by mutableStateOf<List<Song>?>(null)
        internal set

    fun delete(songs: List<Song>) {
        if (songs.isNotEmpty()) request = songs
    }
}

@Composable
fun PhoneDeleteHost() {
    val deleter = rememberPhoneDeleter()
    val req = PhoneDelete.request
    androidx.compose.runtime.LaunchedEffect(req) {
        if (req != null) {
            PhoneDelete.request = null
            deleter(req)
        }
    }
}

/**
 * Telefondaki dosyaları kalıcı olarak silen işlev. Gerekirse Android'in kendi onay penceresini açar
 * (Android 11+: başka uygulamanın koyduğu dosyalar; Android ≤ 9: depolama yazma izni).
 */
@Composable
fun rememberPhoneDeleter(): (List<Song>) -> Unit {
    val ctx = LocalContext.current
    var waiting by remember { mutableStateOf<List<Song>>(emptyList()) }
    var already by remember { mutableStateOf<List<Song>>(emptyList()) }
    var failed by remember { mutableStateOf(0) }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val ok = res.resultCode == Activity.RESULT_OK
        val asked = waiting
        val done = already
        val fails = failed
        waiting = emptyList()
        already = emptyList()
        when {
            !ok -> PhoneMusic.finishDelete(done, fails)
            // Android 11+: sistem onayla birlikte dosyaları da siler
            Build.VERSION.SDK_INT >= 30 -> PhoneMusic.finishDelete(done + asked, fails)
            // Android 10: izin verildi, şimdi silinebilir
            else -> PhoneMusic.runDelete(
                ctx, asked,
                ask = { _, a, f, d -> PhoneMusic.finishDelete(done + d, fails + f + a.size) },
                done = { d, f -> PhoneMusic.finishDelete(done + d, fails + f) },
            )
        }
    }
    fun start(songs: List<Song>) {
        PhoneMusic.runDelete(ctx, songs, { sender, ask, f, deleted ->
            waiting = ask
            already = deleted
            failed = f
            confirm.launch(IntentSenderRequest.Builder(sender).build())
        })
    }
    var pendingWrite by remember { mutableStateOf<List<Song>>(emptyList()) }
    val write = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val list = pendingWrite
        pendingWrite = emptyList()
        if (granted) start(list) else Graph.toast("Silmek için depolama izni gerekiyor")
    }
    return { songs ->
        if (songs.isNotEmpty()) {
            val needWrite = Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
            if (needWrite) {
                pendingWrite = songs
                write.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else start(songs)
        }
    }
}

/** Kalıcı silme sorusu. [keep]: sunucuda da bulunan, yalnızca telefondaki kopyası kalkacak şarkı sayısı. */
@Composable
fun PhoneDeleteDialog(title: String, permanent: Int, keep: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
        title = { Text(title) },
        text = {
            val parts = buildList {
                if (permanent > 0) add(
                    (if (permanent == 1 && keep == 0) "Şarkı" else "$permanent şarkı") +
                        " telefondan kalıcı olarak silinecek. Bu işlem geri alınamaz.",
                )
                if (keep > 0) add("$keep şarkının yalnızca telefondaki kopyası kalkacak; sunucuda durmaya devam eder.")
            }
            Text(parts.joinToString("\n\n"))
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Sil", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

/**
 * Telefondakiler: telefonda duran bütün şarkılar tek listede.
 * Gurfy Sync'te sunucudan indirilen kopyalar ve telefonun kendi müzikleri; Gurfy'de kütüphanenin tamamı.
 * Seçim modunda birden çok şarkı birlikte silinir.
 */
@Composable
fun DownloadsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val remote = Graph.backend.hasRemoteLibrary
    var granted by remember { mutableStateOf(PhoneMusic.allowed(ctx)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
        if (it) {
            PhoneMusic.invalidate()
            Graph.io(then = { Graph.listsVersion++ }) { Graph.subsonic.phoneAccessChanged() }
        }
    }
    val phone by load("phone-music", granted, Graph.listsVersion) {
        if (remote) PhoneMusic.songs(ctx) else Graph.subsonic.allSongs()
    }
    val downloaded = if (remote) Graph.offline.downloaded.values.toList() else emptyList()
    val savedKeys = downloaded.map(PhoneMusic::keyOf).toSet()
    val local = (phone as? Load.Ok)?.value.orEmpty().filter { PhoneMusic.keyOf(it) !in savedKeys }
    val collator = remember { java.text.Collator.getInstance(java.util.Locale("tr")) }
    val songs = (downloaded + local).filter { Deleted.ids[it.id] != true }
        .sortedWith { a, b -> collator.compare(a.title, b.title) }

    var selecting by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf(false) }

    fun deleteSelected() {
        val chosen = songs.filter { it.id in selected }
        // Sunucudan indirilen kopyalar sorusuz kalkar; telefonun kendi dosyaları Android'e sorularak silinir
        val copies = chosen.filter { remote && !it.isLocal }
        copies.forEach { Graph.offline.delete(it.id) }
        val files = chosen - copies.toSet()
        if (files.isNotEmpty()) PhoneDelete.delete(files)
        else if (copies.isNotEmpty()) Graph.toast("${copies.size} şarkı telefondan kaldırıldı")
        selected.clear()
        selecting = false
    }
    if (confirm) {
        val chosen = songs.filter { it.id in selected }
        val keep = chosen.count { remote && !it.isLocal }
        PhoneDeleteDialog(
            if (chosen.size == 1) "Şarkı silinsin mi?" else "${chosen.size} şarkı silinsin mi?",
            permanent = chosen.size - keep, keep = keep, onDismiss = { confirm = false },
        ) { deleteSelected() }
    }

    Column(Modifier.fillMaxSize()) {
        Header(if (selecting) "${selected.size} şarkı seçildi" else "Telefondakiler", nav) {
            if (selecting) {
                IconButton(enabled = selected.isNotEmpty(), onClick = { confirm = true }) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = "Seçilenleri sil", tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { selecting = false; selected.clear() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Seçimi bitir")
                }
            } else if (songs.isNotEmpty()) {
                IconButton(onClick = { selecting = true }) {
                    Icon(Icons.Filled.Checklist, contentDescription = "Seç ve sil")
                }
            }
        }
        if (!granted) {
            EmptyNote(
                if (asked) "Telefonun kendi müziklerini göstermek için Ayarlar > Uygulamalar > " +
                    ctx.applicationInfo.loadLabel(ctx.packageManager) + " > İzinler'den " +
                    "\"Müzik ve ses\" iznini ver."
                else "Telefonda başka yollarla bulunan müzikleri de görmek için izin ver.",
            )
            if (!asked) Button(onClick = { ask.launch(PhoneMusic.permission) }, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text("Telefonun müziklerine izin ver")
            }
        }
        when {
            phone is Load.Loading && songs.isEmpty() -> LoadBox(phone) {}
            songs.isEmpty() -> EmptyNote("Burada henüz şarkı yok.")
            selecting -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (selected.size == songs.size) selected.clear()
                        else { selected.clear(); selected.addAll(songs.map { it.id }) }
                    }) { Text(if (selected.size == songs.size) "Seçimi kaldır" else "Tümünü seç (${songs.size})") }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { i, s -> "$i" + s.id }) { _, s ->
                        val on = s.id in selected
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) selected.remove(s.id) else selected.add(s.id) }
                                .padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, { if (it) selected.add(s.id) else selected.remove(s.id) })
                            Cover(s.coverArt, 44.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SourceIcon(s)
                        }
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item { PlayButtons(songs) }
                itemsIndexed(songs, key = { i, s -> "$i" + s.id }) { i, s ->
                    SongRow(s, nav) { Graph.player.play(songs, i) }
                }
            }
        }
    }
}

/** Şarkının nerede durduğunu gösteren küçük simge (yalnızca Gurfy Sync'te anlamlı). */
@Composable
fun SourceIcon(song: Song) {
    if (!Graph.backend.hasRemoteLibrary) return
    when {
        song.isLocal -> Icon(
            Icons.Filled.Smartphone, contentDescription = "Yalnızca telefonda",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
        )
        Graph.offline.downloaded.containsKey(song.id) -> Icon(
            Icons.Filled.DownloadDone, contentDescription = "Sunucuda, telefona da indirildi",
            tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp),
        )
    }
}
