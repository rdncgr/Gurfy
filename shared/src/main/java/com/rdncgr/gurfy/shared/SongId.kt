package com.rdncgr.gurfy.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// "Bu şarkı ne?": radyoda çalan şarkıyı Gurfy servisi bulur (yayın bilgisi ya da 10 sn kayıt + Shazam).
// Tanınan şarkılar "Radyoda duyduklarım" listesinde saklanır; oradan tek tek ya da topluca indirilir.

object SongId {
    /** Son tanıma sonucu ve hangi radyo için yapıldığı. */
    var result by mutableStateOf<Recognition?>(null)
        private set
    var resultFor by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Kayıt yapılıyor mu (yalnızca bilgi metni için). */
    var listening by mutableStateOf(false)
        private set

    val heard = mutableStateListOf<Recognition>()
    /** Bu oturumda sunucuya indirilmek üzere sıraya alınanlar (sanatçı|şarkı). */
    val queued = mutableStateMapOf<String, Boolean>()
    private var loaded = false

    fun keyOf(s: Suggestion) = s.artist.lowercase() + "|" + s.title.lowercase()

    /**
     * Bildirimdeki kısayol: radyoda çalan şarkıyı tanır, sonucun doğruluğuna bakmadan indirmeye gönderir.
     */
    fun identifyAndDownload(song: Song) {
        if (song.streamUrl == null) return
        if (busy) { Graph.toast("Şarkı zaten tanınıyor, biraz bekle"); return }
        if (!Graph.settings.serviceConfigured) { Graph.toast("Önce Ayarlar'dan Gurfy servis adresini girin"); return }
        Graph.toast("Çalan şarkı tanınıyor…")
        identify(song) { r ->
            val s = r.song
            when {
                s == null -> Graph.toast(r.message ?: "Şarkı tanınamadı")
                s.inLibrary -> Graph.toast("Zaten arşivde: ${s.artist} – ${s.title}")
                queued[keyOf(s)] == true -> Graph.toast("Zaten sırada: ${s.artist} – ${s.title}")
                else -> {
                    download(listOf(s))
                    Graph.toast("İndiriliyor: ${s.artist} – ${s.title}")
                }
            }
        }
    }

    fun identify(song: Song, forceAudio: Boolean = false, onDone: ((Recognition) -> Unit)? = null) {
        val url = song.streamUrl ?: return
        if (busy) return
        if (!Graph.settings.serviceConfigured) { Graph.toast("Önce Ayarlar'dan Gurfy servis adresini girin"); return }
        // "Sesten tanı" ile düzeltiliyorsa yayın bilgisinden gelen (hatalı) sonuç listeden çıkarılacak
        val wrong = result?.takeIf { forceAudio && resultFor == song.id && it.source == "metadata" }
        busy = true
        listening = forceAudio
        resultFor = song.id
        result = null
        Graph.scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Graph.service.recognize(url, song.title, forceAudio, LiveMeta.title) }
                result = r
                if (r.song != null) {
                    load()
                    wrong?.let { w -> heard.removeAll { it.time == w.time }; save() }
                    addHeard(r)
                }
            } catch (e: Exception) {
                result = Recognition(null, "", null, song.title, errorText(e, "Tanıma başarısız"))
            } finally {
                busy = false
                listening = false
            }
            result?.let { onDone?.invoke(it) }
        }
    }

    // ---------------------------------------------------------- duyulanlar listesi

    fun load() {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(Graph.settings.heardJson)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                heard += Recognition(
                    Suggestion(o.optString("artist"), o.optString("title"), o.optString("album").ifBlank { null }, false),
                    o.optString("source"), o.optString("cover").ifBlank { null }, o.optString("station"), null,
                    o.optLong("time"),
                )
            }
        } catch (e: Exception) { /* bozuk kayıt: boş başla */ }
    }

    private fun save() {
        val a = JSONArray()
        heard.forEach { r ->
            val s = r.song ?: return@forEach
            a.put(
                JSONObject().put("artist", s.artist).put("title", s.title).put("album", s.album ?: "")
                    .put("source", r.source).put("cover", r.cover ?: "").put("station", r.station).put("time", r.time),
            )
        }
        Graph.settings.heardJson = a.toString()
    }

    fun addHeard(r: Recognition) {
        load()
        val s = r.song ?: return
        heard.removeAll { it.song?.let(::keyOf) == keyOf(s) }
        heard.add(0, r)
        while (heard.size > 200) heard.removeAt(heard.lastIndex)
        save()
    }

    fun remove(r: Recognition) { heard.remove(r); save() }

    fun clear() { heard.clear(); save() }

    fun download(list: List<Suggestion>) {
        if (list.isEmpty()) return
        list.forEach { queued[keyOf(it)] = true }
        Graph.io(if (list.size == 1) Words.downloading else "${list.size} şarkı sıraya alındı") {
            if (list.size == 1) Graph.service.download(list[0]) else Graph.service.downloadBatch(list)
        }
    }
}

/** Radyo çalarken "Şimdi çalıyor" ekranında gösterilen tanıma alanı. */
@Composable
fun SongIdPanel(song: Song) {
    val r = SongId.result.takeIf { SongId.resultFor == song.id }
    val busy = SongId.busy && SongId.resultFor == song.id

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledTonalButton(enabled = !SongId.busy, onClick = { SongId.identify(song) }) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Dinleniyor…" else "Bu şarkı ne?")
        }
        if (busy) {
            Text(
                "Önce yayın bilgisine bakılır, yoksa 10 saniye dinlenir.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (r != null && !busy) {
            val s = r.song
            if (s == null) {
                Text(
                    r.message ?: "Şarkı tanınamadı", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else {
                Card(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    RecognitionRow(r, showStation = false)
                    if (r.source == "metadata") {
                        OutlinedButton(
                            onClick = { SongId.identify(song, forceAudio = true) },
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Yanlışsa sesten tanı")
                        }
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("d MMM HH:mm", Locale("tr"))

/** Kapak · Sanatçı – Şarkı · durum · ▶ önizleme · ⬇ indir */
@Composable
fun RecognitionRow(r: Recognition, showStation: Boolean, onRemove: (() -> Unit)? = null) {
    val s = r.song ?: return
    val queued = SongId.queued[SongId.keyOf(s)] == true
    HandRow {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (r.cover != null) {
                    AsyncImage(r.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("${s.artist} – ${s.title}", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val note = buildList {
                    when {
                        s.inLibrary -> add("Kütüphanede")
                        queued -> add("Sıraya alındı")
                    }
                    if (showStation) add(r.station + " · " + timeFormat.format(Date(r.time)))
                    else add(if (r.source == "shazam") "Sesten tanındı" else "Yayın bilgisinden")
                }.joinToString(" · ")
                Text(
                    note, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = if (s.inLibrary) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!s.inLibrary) {
                PreviewButton(s)
                IconButton(enabled = !queued, onClick = { SongId.download(listOf(s)) }) {
                    Icon(
                        if (queued) Icons.Filled.CloudDone else Icons.Filled.CloudDownload,
                        contentDescription = if (queued) "Sıraya alındı" else Words.download,
                    )
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Listeden kaldır", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Keşfet > Duyduklarım: radyoda, telefonda ya da dışarıda duyulup tanınan şarkılar. */
@Composable
fun HeardTab() {
    LaunchedEffect(Unit) { SongId.load() }
    val list = SongId.heard
    val missing = list.mapNotNull { it.song }.filter { !it.inLibrary && SongId.queued[SongId.keyOf(it)] != true }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Radyo çalarken Şimdi çalıyor ekranındaki \"Bu şarkı ne?\" düğmesiyle tanınan şarkılar burada birikir.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (list.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(enabled = missing.isNotEmpty(), onClick = { SongId.download(missing) }) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Hepsini indir (${missing.size})")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { SongId.clear() }) { Text("Temizle") }
            }
        }
        if (list.isEmpty()) item { EmptyNote("Henüz tanınan şarkı yok.") }
        items(list, key = { it.time }) { r -> RecognitionRow(r, showStation = true) { SongId.remove(r) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
