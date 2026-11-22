package com.rdncgr.gurfy.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONObject
import java.net.URLEncoder

// Last.fm dinleme geçmişi (Navidrome scrobble ediyor): öneriler, unuttukların, istatistikler.
// Last.fm anahtarı sunucuda; uygulama yalnızca kullanıcı adını gönderir.


private val lfmUser: String get() = Graph.settings.lastfmUser

private val lbUser: String get() = Graph.settings.listenbrainzUser

private fun lfmReady() = Graph.settings.serviceConfigured && lfmUser.isNotBlank()

/** Sana özel öneriler Last.fm ya da ListenBrainz hesaplarından biriyle çalışır. */
private fun recommendReady() = Graph.settings.serviceConfigured && (lfmUser.isNotBlank() || lbUser.isNotBlank())

data class LfmArtist(val name: String, val plays: Int)
data class LfmAlbum(val name: String, val artist: String, val plays: Int, val image: String?)
data class LfmTrack(val suggestion: Suggestion, val plays: Int, val song: Song?)
data class LfmStats(val total: Int, val artists: List<LfmArtist>, val tracks: List<LfmTrack>, val albums: List<LfmAlbum>)

private fun trackOf(o: JSONObject): LfmTrack {
    val song = o.optJSONObject("song")?.let(::songOf)
    val because = o.strOrNull("because")
    return LfmTrack(
        Suggestion(
            o.optString("artist"), o.optString("title"), null, o.optBoolean("in_library"),
            note = o.strOrNull("note") ?: because?.let { "$it dinlediğin için" },
        ),
        o.optInt("plays"), song,
    )
}

object LastFmApi {
    fun stats(period: String): LfmStats {
        val o = Graph.service.lastfm("stats?user=${enc(lfmUser)}&period=$period")
        return LfmStats(
            o.optInt("total"),
            o.list("artists").map { LfmArtist(it.optString("name"), it.optInt("plays")) },
            o.list("tracks").map(::trackOf),
            o.list("albums").map { LfmAlbum(it.optString("name"), it.optString("artist"), it.optInt("plays"), it.strOrNull("image")) },
        )
    }

    fun recommend(refresh: Boolean): List<Suggestion> =
        Graph.service.lastfm(
            "recommend?user=${enc(lfmUser)}&lb_user=${enc(lbUser)}" + if (refresh) "&refresh=true" else "",
        )
            .list("tracks").map { trackOf(it).suggestion }

    fun topLibrary(period: String, limit: Int): List<Song> =
        Graph.service.lastfm("top-library?user=${enc(lfmUser)}&period=$period&limit=$limit")
            .list("tracks").mapNotNull { trackOf(it).song }

    fun forgotten(): List<Song> =
        Graph.service.lastfm("forgotten?user=${enc(lfmUser)}").list("tracks").mapNotNull { trackOf(it).song }
}

@Composable
private fun NeedLastFm(nav: Nav) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        EmptyNote(
            if (!Graph.settings.serviceConfigured) "Bunun için önce Ayarlar > Bağlantılar'dan Gurfy servisini girin."
            else "Dinleme geçmişine göre öneri için Ayarlar > Bağlantılar'a Last.fm ya da ListenBrainz " +
                "kullanıcı adını yazın (Navidrome'un dinlemeleri gönderdiği hesap).",
        )
        Button(onClick = { nav.tab(Screen.SettingsScreen) }, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text("Ayarlara git")
        }
    }
}

// ------------------------------------------------------- Keşfet > Sana özel

@Composable
fun RecommendTab(nav: Nav) {
    if (!recommendReady()) { NeedLastFm(nav); return }
    var refresh by remember { mutableIntStateOf(0) }
    val state by load("lfm-recommend", lfmUser + "|" + lbUser, refresh) { LastFmApi.recommend(refresh > 0) }
    val queued = remember { mutableStateMapOf<String, Boolean>() }
    fun key(s: Suggestion) = s.artist.lowercase() + "|" + s.title.lowercase()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Son bir ay ve bir yılda en çok dinlediğin sanatçılara benzeyen, henüz pek dinlemediğin sanatçıların " +
                    "sevilen şarkıları; ListenBrainz hesabın varsa haftalık keşif listen de karışır. " +
                    "Kütüphanende olmayanlar listelenir.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when (val st = state) {
            is Load.Loading -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Geçmişin inceleniyor…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            is Load.Err -> item { EmptyNote(st.message) }
            is Load.Ok -> {
                val missing = st.value.filter { queued[key(it)] != true }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = missing.isNotEmpty(), onClick = {
                            missing.forEach { queued[key(it)] = true }
                            Graph.io { Graph.service.downloadBatch(missing) }
                        }) {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Hepsini indir (${missing.size})")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Yeni öneriler") }
                    }
                }
                if (st.value.isEmpty()) item { EmptyNote("Henüz öneri çıkacak kadar dinleme geçmişi yok.") }
                itemsIndexed(st.value, key = { i, s -> "$i" + key(s) }) { _, s ->
                    SuggestionRow(s, queued[key(s)] == true) {
                        queued[key(s)] = true
                        Graph.io { Graph.service.download(s) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ----------------------------------------------------- Ana sayfa: Unuttukların

/** Ana sayfa bölümü. Last.fm ayarlı değilse hiç görünmez. */
@Composable
fun ForgottenSection(title: String, refresh: Int) {
    if (!lfmReady()) return
    SectionTitle(title)
    val songs by load("lfm-forgotten", lfmUser, refresh) { LastFmApi.forgotten() }
    SongStrip(songs, "Bir zamanlar sık dinleyip son 3 aydır açmadığın şarkılar burada çıkacak. Last.fm geçmişin büyüdükçe dolar.")
}

// ------------------------------------------------------ Dinleme istatistikleri

@Composable
fun StatsScreen(nav: Nav) {
    var period by rememberSaveable { mutableStateOf("1month") }
    val periods = listOf("7day" to "7 gün", "1month" to "1 ay", "12month" to "1 yıl", "overall" to "Tümü")

    Column(Modifier.fillMaxSize()) {
        Header("Dinleme istatistiklerim", nav)
        if (!lfmReady()) { NeedLastFm(nav); return@Column }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            periods.forEachIndexed { i, (k, label) ->
                SegmentedButton(
                    selected = period == k, onClick = { period = k },
                    shape = SegmentedButtonDefaults.itemShape(i, periods.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        val state by load("lfm-stats", lfmUser, period) { LastFmApi.stats(period) }
        LoadBox(state) { st -> StatsContent(st, period, nav) }
    }
}

@Composable
private fun StatsContent(st: LfmStats, period: String, nav: Nav) {
    val periodPlays = st.tracks.sumOf { it.plays }
    val librarySongs = st.tracks.mapNotNull { it.song }
    val queued = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Toplam dinleme", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${st.total}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(lfmUser + " · Last.fm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (st.artists.isNotEmpty()) Column(horizontalAlignment = Alignment.End) {
                        Text("En çok", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            st.artists[0].name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
        if (st.artists.isEmpty() && st.tracks.isEmpty()) {
            item { EmptyNote("Bu dönemde dinleme yok.") }
            return@LazyColumn
        }

        // Sanatçılar: çubuk grafikle
        if (st.artists.isNotEmpty()) item { SectionTitle("Sanatçılar") }
        val maxPlays = st.artists.maxOfOrNull { it.plays }?.coerceAtLeast(1) ?: 1
        itemsIndexed(st.artists) { i, a ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                    Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${a.plays}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier.padding(start = 24.dp, top = 4.dp).fillMaxWidth(a.plays.toFloat() / maxPlays).height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                )
            }
        }

        // Albümler: kapaklı şerit
        if (st.albums.isNotEmpty()) {
            item { SectionTitle("Albümler") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(st.albums) { al ->
                        Column(Modifier.width(112.dp)) {
                            Box(
                                Modifier.size(112.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Album, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (al.image != null) AsyncImage(al.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Text(al.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            Text(
                                "${al.artist} · ${al.plays}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Şarkılar: kütüphanedekiler çalar, olmayanlar indirilebilir
        if (st.tracks.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("Şarkılar") }
                if (librarySongs.isNotEmpty()) TextButton(onClick = { Graph.player.play(librarySongs, 0, ordered = true) }) {
                    Text("Hepsini çal")
                }
            }
        }
        itemsIndexed(st.tracks) { i, t ->
            val s = t.suggestion
            val song = t.song
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 1}", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp),
                )
                Box(Modifier.weight(1f)) {
                    if (song != null) {
                        SongRow(song, nav) { Graph.player.play(librarySongs, librarySongs.indexOf(song).coerceAtLeast(0)) }
                    } else {
                        val k = s.artist.lowercase() + "|" + s.title.lowercase()
                        SuggestionRow(s.copy(note = "${t.plays} kez"), queued[k] == true) {
                            queued[k] = true
                            Graph.io { Graph.service.download(s) }
                        }
                    }
                }
            }
        }
        if (periodPlays > 0) item {
            Text(
                "İlk ${st.tracks.size} şarkı bu dönemde toplam $periodPlays kez dinlendi.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
