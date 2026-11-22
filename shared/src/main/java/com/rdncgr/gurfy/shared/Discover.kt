package com.rdncgr.gurfy.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun keyOf(s: Suggestion) = s.artist.lowercase() + "|" + s.title.lowercase()

// ------------------------------------------------------------- öneri satırları

@Composable
fun SuggestionRow(s: Suggestion, queued: Boolean, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (s.note.isNullOrBlank()) s.artist else "${s.artist} · ${s.note}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!s.inLibrary) PreviewButton(s)
        when {
            s.inLibrary -> Text(
                "Kütüphanede", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 12.dp),
            )
            queued -> Text(
                "Sıraya alındı", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp),
            )
            else -> IconButton(onClick = onDownload) {
                Icon(Icons.Filled.CloudDownload, contentDescription = Words.download)
            }
        }
    }
}

/** 30 saniyelik önizlemeyi başlatır/durdurur. */
@Composable
fun PreviewButton(s: Suggestion) {
    val active = Preview.currentKey == Preview.keyOf(s)
    IconButton(onClick = { Preview.toggle(s) }) {
        when {
            active && Preview.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            active -> Icon(Icons.Filled.Stop, contentDescription = "Önizlemeyi durdur", tint = MaterialTheme.colorScheme.primary)
            else -> Icon(Icons.Filled.PlayCircleOutline, contentDescription = "30 saniye dinle")
        }
    }
}

private fun LazyListScope.suggestionItems(list: List<Suggestion>, queued: MutableMap<String, Boolean>) {
    items(list) { s ->
        SuggestionRow(s, queued[keyOf(s)] == true) {
            queued[keyOf(s)] = true
            Graph.io { Graph.service.download(s) }
        }
    }
}

private fun statusText(j: DownloadJob): String = when (j.status) {
    "queued" -> "Sırada"
    "downloading" -> "İndiriliyor…"
    "done" -> "Kütüphaneye eklendi"
    "exists" -> "Zaten kütüphanede"
    "error" -> "İndirilemedi: " + j.message
    else -> j.status
}

/**
 * Sunucu indirme listesi. Uygulama açıkken arka planda izlenir (indirme varken 3 sn'de bir, yokken
 * 30 sn'de bir). Son hali telefonda da saklanır; servise ulaşılamasa ya da uygulama güncellense de görünür.
 */
object Jobs {
    val list = mutableStateListOf<DownloadJob>()
    val active: Int get() = list.count { it.status == "queued" || it.status == "downloading" }
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var started = false

    fun start() {
        if (started) return
        started = true
        list.addAll(decode(Graph.settings.jobsJson))
        Graph.scope.launch {
            while (true) {
                refresh()
                kotlinx.coroutines.withTimeoutOrNull(if (active > 0) 3_000L else 30_000L) { wake.receive() }
            }
        }
    }

    /** Yeni indirme istendi: listeyi hemen yenile. */
    fun poke() { wake.trySend(Unit) }

    private suspend fun refresh() {
        if (!Graph.settings.serviceConfigured) return
        val fresh = try {
            withContext(Dispatchers.IO) { Graph.service.jobs() }
        } catch (e: Exception) {
            return // servis kapalıysa eldeki listeyle devam
        }
        // Az önce sürmekte olup hatayla biten indirmeyi bildir
        val wasActive = list.filter { it.status == "queued" || it.status == "downloading" }.map { it.id }.toSet()
        fresh.filter { it.id in wasActive && it.status == "error" }.forEach { Graph.toast("İndirilemedi: ${it.title}") }
        if (fresh != list.toList()) {
            list.clear()
            list.addAll(fresh)
            Graph.settings.jobsJson = encode(fresh)
        }
    }

    private fun encode(l: List<DownloadJob>): String {
        val a = org.json.JSONArray()
        l.forEach {
            a.put(
                org.json.JSONObject().put("id", it.id).put("artist", it.artist).put("title", it.title)
                    .put("status", it.status).put("message", it.message),
            )
        }
        return a.toString()
    }

    private fun decode(json: String): List<DownloadJob> = try {
        val a = org.json.JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            DownloadJob(o.optString("id"), o.optString("artist"), o.optString("title"), o.optString("status"), o.optString("message"))
        }
    } catch (e: Exception) { emptyList() }
}

/** İndirme sürerken (sunucuya ya da telefona) ekranın üstünde akan ince çizgi. */
@Composable
fun DownloadProgressLine(modifier: Modifier = Modifier) {
    val busy = Jobs.active > 0 || Graph.offline.inProgress.isNotEmpty()
    if (busy) {
        LinearProgressIndicator(
            // sağdan sola aksın
            modifier = modifier.fillMaxWidth().height(3.dp).graphicsLayer(scaleX = -1f),
            trackColor = androidx.compose.ui.graphics.Color.Transparent,
        )
    }
}

@Composable
private fun JobRow(j: DownloadJob) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text("${j.artist} – ${j.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                statusText(j), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (j.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Sürmekte olan indirme silinemez; sıradaki silinirse hiç indirilmez
        if (j.status != "downloading") IconButton(onClick = {
            Jobs.list.removeAll { it.id == j.id }
            Graph.io { Graph.service.deleteJobs(listOf(j.id)) }
        }) {
            Icon(
                Icons.Filled.Close, contentDescription = if (j.status == "queued") "Sıradan çıkar" else "Listeden sil",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------- keşfet

/** Keşfet'te açık olan sekme (başka ekranlardan bir sekmeye geçebilmek için dışarıda tutulur). */
object DiscoverTab {
    var selected by mutableIntStateOf(0)
}

@Composable
fun DiscoverScreen(nav: Nav) {
    val jobs = Jobs.list
    val active = Jobs.active
    val tabs = listOf(
        Triple("Ara", Icons.Filled.Search, 0),
        Triple("Sana özel", Icons.Filled.AutoAwesome, 0),
        Triple("Popüler", Icons.AutoMirrored.Filled.TrendingUp, 0),
        Triple("Radyodan", Icons.Filled.GraphicEq, 0),
        Triple("İndirmeler", Icons.Filled.CloudDownload, active),
    )

    Column(Modifier.fillMaxSize()) {
        Header("Keşfet", nav) {
            // Kütüphane'deki istatistik simgesiyle aynı yerde
            IconButton(onClick = { nav.push(Screen.Import) }) {
                Icon(Icons.Filled.AddLink, contentDescription = "Liste aktar (Spotify / YouTube)")
            }
        }
        PrimaryScrollableTabRow(selectedTabIndex = DiscoverTab.selected.coerceIn(0, tabs.size - 1), edgePadding = 8.dp) {
            tabs.forEachIndexed { i, (label, icon, badge) ->
                Tab(
                    selected = DiscoverTab.selected == i,
                    onClick = { DiscoverTab.selected = i },
                    text = { Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) },
                    icon = {
                        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
                            Icon(icon, contentDescription = null)
                        }
                    },
                )
            }
        }
        val service = Graph.settings.serviceConfigured
        SwipePages(DiscoverTab.selected, tabs.size, { DiscoverTab.selected = it }) { page ->
            when {
                page == 0 -> SearchContent(nav)
                !service -> Column(Modifier.fillMaxSize()) {
                    EmptyNote("Öneriler, popüler listeler ve indirme için önce Ayarlar > Bağlantılar'dan Gurfy servis adresini girin.")
                    Button(onClick = { nav.tab(Screen.SettingsScreen) }, modifier = Modifier.padding(horizontal = 24.dp)) {
                        Text("Ayarlara git")
                    }
                }
                page == 1 -> RecommendTab(nav)
                page == 2 -> ChartsTab()
                page == 3 -> HeardTab()
                else -> JobsTab(jobs)
            }
        }
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** "Ne dinleniyor?": Last.fm'e göre Türkiye'de / dünyada en çok dinlenen şarkılar. */
@Composable
private fun ChartsTab() {
    var chartScope by rememberSaveable { mutableStateOf("tr") }
    val queued = remember { mutableStateMapOf<String, Boolean>() }
    val chart by load("charts", chartScope) { Graph.service.charts(chartScope) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = chartScope == "tr", onClick = { chartScope = "tr" }, label = { Text("Türkiye") })
                FilterChip(selected = chartScope == "world", onClick = { chartScope = "world" }, label = { Text("Dünya") })
                Spacer(Modifier.weight(1f))
                Text("Last.fm · ilk 30", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (val c = chart) {
            is Load.Loading -> item { Spinner() }
            is Load.Err -> item { EmptyNote(c.message) }
            is Load.Ok -> {
                if (c.value.isEmpty()) item { EmptyNote("Liste şu an alınamadı.") }
                itemsIndexed(c.value.take(30)) { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp),
                        )
                        Box(Modifier.weight(1f)) {
                            SuggestionRow(s, queued[keyOf(s)] == true) {
                                queued[keyOf(s)] = true
                                Graph.io { Graph.service.download(s) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobsTab(jobs: List<DownloadJob>) {
    val failed = jobs.count { it.status == "error" }
    val finished = jobs.count { it.status == "done" || it.status == "exists" || it.status == "error" }
    LazyColumn(Modifier.fillMaxSize()) {
        if (failed > 0 || finished > 0) item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (failed > 0) OutlinedButton(onClick = { Graph.io { Graph.service.retryFailed() } }) {
                    Text("Hatalıları tekrar dene ($failed)", maxLines = 1)
                }
                if (finished > 0) TextButton(onClick = {
                    Jobs.list.removeAll { it.status == "done" || it.status == "exists" || it.status == "error" }
                    Graph.io { Graph.service.deleteJobs() }
                }) { Text("Bitenleri temizle", maxLines = 1) }
            }
        }
        if (jobs.isEmpty()) {
            item { EmptyNote("Henüz indirme yok. Bir şarkı arayın, popüler listeye bakın ya da radyoda duyduğunuzu tanıtın.") }
        }
        items(jobs) { JobRow(it) }
    }
}

@Composable
fun SimilarScreen(nav: Nav, artist: String, title: String) {
    val state by load("similar", artist, title) { Graph.service.similar(artist, title) }
    val queued = remember { mutableStateMapOf<String, Boolean>() }

    Column(Modifier.fillMaxSize()) {
        Header("Benzer şarkılar", nav)
        Text(
            "$artist – $title", maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
        )
        LoadBox(state) { list ->
            if (list.isEmpty()) {
                EmptyNote("Bu şarkı için öneri bulunamadı.")
            } else {
                val missing = list.filter { !it.inLibrary && queued[keyOf(it)] != true }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (missing.isNotEmpty()) {
                        item {
                            OutlinedButton(
                                onClick = {
                                    missing.forEach { queued[keyOf(it)] = true }
                                    Graph.io("${missing.size} şarkı sıraya alındı") { Graph.service.downloadBatch(missing) }
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            ) { Text("Eksiklerin hepsini indir (${missing.size})") }
                        }
                    }
                    suggestionItems(list, queued)
                }
            }
        }
    }
}

// ------------------------------------------------- liste aktar (Spotify/YouTube)

/** Liste aktar: Spotify / YouTube liste bağlantısını yapıştır, eksikleri indir. */
@Composable
fun ImportScreen(nav: Nav) {
    Column(Modifier.fillMaxSize()) {
        Header("Liste aktar", nav)
        LinkImport(nav)
    }
}

@Composable
private fun LinkImport(nav: Nav) {
    var url by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<Load<ImportedList>?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            label = { Text("Spotify veya YouTube liste bağlantısı") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            "Spotify, YouTube ya da YouTube Music'te listeyi açın, Paylaş > Bağlantıyı kopyala deyip buraya " +
                "yapıştırın. Liste herkese açık (veya liste bağlantısına sahip olanlara açık) olmalı.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            enabled = url.isNotBlank() && state !is Load.Loading,
            onClick = {
                val link = url.trim()
                state = Load.Loading
                scope.launch {
                    state = try {
                        Load.Ok(withContext(Dispatchers.IO) { Graph.service.importList(link) })
                    } catch (e: Exception) {
                        Load.Err(errorText(e, "Liste alınamadı"))
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("Listeyi getir") }

        state?.let { current -> LoadBox(current) { list -> ImportedListView(list, nav) } }
    }
}

/** Liste içeriği: kütüphanede olmayanlar işaretli gelir, seçilenler sunucuya indirilir. */
@Composable
fun ImportedListView(list: ImportedList, nav: Nav, header: @Composable () -> Unit = {}) {
    val selected = remember(list) {
        mutableStateMapOf<Int, Boolean>().apply { list.tracks.forEachIndexed { i, t -> if (!t.inLibrary) put(i, true) } }
    }
    val picked = list.tracks.filterIndexed { i, _ -> selected[i] == true }
    val missing = list.tracks.count { !it.inLibrary }
    Column(Modifier.fillMaxSize()) {
        header()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(list.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${list.tracks.size} şarkı · $missing tanesi kütüphanede yok",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                enabled = picked.isNotEmpty(),
                onClick = {
                    Graph.io("${picked.size} şarkı sıraya alındı") { Graph.service.downloadBatch(picked) }
                    DiscoverTab.selected = 4
                    nav.tab(Screen.Discover)
                },
            ) { Text("İndir (${picked.size})") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list.tracks) { i, t ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !t.inLibrary) { selected[i] = selected[i] != true }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selected[i] == true,
                        enabled = !t.inLibrary,
                        onCheckedChange = { selected[i] = it },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (t.inLibrary) "${t.artist} · kütüphanede" else t.artist,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!t.inLibrary) PreviewButton(t)
                }
            }
        }
    }
}

// --------------------------------------------------------------- şimdi çalıyor

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
fun NowPlayingScreen(nav: Nav) {
    val p = Graph.player
    val song = p.current
    if (song == null) {
        Column(Modifier.fillMaxSize()) {
            Header("Şimdi çalıyor", nav)
            EmptyNote("Şu an çalan bir şarkı yok.")
        }
        return
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            position = p.position()
            duration = p.duration()
            delay(500)
        }
    }
    val saved = Graph.offline.downloaded.containsKey(song.id)
    val radio = song.isRadio
    var addToList by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (addToList) AddToPlaylistDialog(listOf(song)) { addToList = false }
    if (confirmDelete) {
        if (song.isLocal) PhoneDeleteDialog("Şarkı silinsin mi?", 1, 0, { confirmDelete = false }) { PhoneDelete.delete(listOf(song)) }
        else ServerDeleteDialog(song) { confirmDelete = false }
    }
    var editGenre by remember { mutableStateOf(false) }
    if (editGenre) GenreEditDialog(song) { editGenre = false }

    // Üst kısım kayar; oynatma paneli ekranın altında sabit kalır (uzun isimler onu aşağı itmez)
    Column(Modifier.fillMaxSize()) {
        Header("Şimdi çalıyor", nav) {
            if (!radio && song.isLocal) Box {
                // Yalnızca telefonda duran şarkı
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Seçenekler") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Telefondan sil") },
                        leadingIcon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
            if (!radio && !song.isLocal) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Seçenekler") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Listeye ekle") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                        onClick = { menu = false; addToList = true },
                    )
                    if (saved) DropdownMenuItem(
                        text = { Text("Telefondan kaldır") },
                        leadingIcon = { Icon(Icons.Filled.MobileOff, contentDescription = null) },
                        onClick = { menu = false; Graph.offline.delete(song.id) },
                    )
                    if (Graph.settings.serviceConfigured) DropdownMenuItem(
                        text = { Text("Türü düzenle") },
                        leadingIcon = { Icon(Icons.Filled.Category, contentDescription = null) },
                        onClick = { menu = false; editGenre = true },
                    )
                    DropdownMenuItem(
                        text = { Text(Words.delete) },
                        leadingIcon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                    song.albumId?.let { albumId ->
                        DropdownMenuItem(
                            text = { Text("Albüme git") },
                            leadingIcon = { Icon(Icons.Filled.Album, contentDescription = null) },
                            onClick = { menu = false; nav.push(Screen.AlbumDetail(albumId)) },
                        )
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        if (song.isRadio) {
            // Çoğu radyonun logosu yok: küçük bir alan yeter
            Box(
                Modifier.padding(vertical = 24.dp).size(128.dp).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp),
                )
                if (song.logo != null) {
                    AsyncImage(song.logo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        } else AsyncImage(
            model = Graph.subsonic.coverUrl(song.coverArt, 800),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 16.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        val liked = Favorites.isStarred(song)
        val live = LiveMeta.title?.takeIf { radio && !it.equals(song.title, ignoreCase = true) }
        // Başlık solda, şarkıya ait işlemler sağda simge olarak
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (live != null) {
                    // Yayının gönderdiği şarkı adı
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            live, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                } else {
                    Text(
                        song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (radio) {
                RadioFavoriteButton(song)
                IconButton(onClick = { nav.tab(Screen.Library); LibraryTab.selected = 4 }) {
                    Icon(Icons.Filled.Radio, contentDescription = "Kütüphane > Radyo")
                }
            }
            if (!radio) {
                IconButton(onClick = { nav.push(Screen.Similar(song.artist, song.title)) }) {
                    Icon(Icons.Filled.TravelExplore, contentDescription = "Benzerlerini bul")
                }
                if (!song.isLocal && Graph.backend.hasRemoteLibrary) IconButton(enabled = !saved, onClick = { Graph.saveOffline(song) }) {
                    Icon(
                        if (saved) Icons.Filled.DownloadDone else Icons.Filled.Download,
                        contentDescription = if (saved) "Telefonda" else "Telefona indir",
                        tint = if (saved) MaterialTheme.colorScheme.secondary else LocalContentColor.current,
                    )
                }
                if (!song.isLocal) IconButton(onClick = { Favorites.toggle(song) }) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (liked) "Beğeniyi kaldır" else "Beğen",
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (radio) SongIdPanel(song)
        Spacer(Modifier.height(8.dp))
        }

        // ------------------------------------------------ sabit oynatma paneli
        Column(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        if (radio) {
            Text(
                "● Canlı yayın", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp),
            )
        }
        val fraction = drag ?: if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        if (!radio) Slider(
            value = fraction,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { p.seekTo((it * duration).toLong()) }
                drag = null
            },
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (!radio) Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            Text(clock(position), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(clock(duration), style = MaterialTheme.typography.labelMedium)
        }
        if (!radio) Text(
            p.mode.label, style = MaterialTheme.typography.labelSmall,
            color = if (p.mode == PlayMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )

        val playButton: @Composable () -> Unit = {
            FilledIconButton(onClick = { p.toggle() }, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Çal / duraklat", modifier = Modifier.size(40.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Şarkıda: solda karışık modu, sağda tekrar; ortada önceki / çal / sonraki
            if (radio) {
                val shuffleOn = p.radioShuffle && !p.radioFromFavorites
                IconButton(enabled = !p.radioFromFavorites, onClick = { p.toggleRadioShuffle() }) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = if (shuffleOn) "Rastgele radyo açık" else "Rastgele radyo kapalı",
                        tint = if (shuffleOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (p.radioFromFavorites) 0.38f else 1f),
                    )
                }
            } else IconButton(onClick = { p.cycleMode() }) { PlayModeIcon(p.mode) }
            IconButton(onClick = { p.previous() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = if (radio) "Önceki radyo" else "Önceki", modifier = Modifier.size(36.dp))
            }
            playButton()
            IconButton(onClick = { p.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = if (radio) "Sonraki radyo" else "Sonraki", modifier = Modifier.size(36.dp))
            }
            // Radyoda tekrar düğmesi yok; çal düğmesi ortada kalsın
            if (radio) Spacer(Modifier.size(48.dp))
            if (!radio) IconButton(onClick = { p.cycleRepeat() }) {
                val r = p.repeat
                Icon(
                    if (r == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = when (r) {
                        androidx.media3.common.Player.REPEAT_MODE_ONE -> "Aynı şarkıyı tekrarla"
                        androidx.media3.common.Player.REPEAT_MODE_ALL -> "Tümünü tekrarla"
                        else -> "Tekrar kapalı"
                    },
                    tint = if (r == androidx.media3.common.Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (radio && p.radioShuffle) {
            // Rastgele açıkken hangi radyolar arasında gezileceği
            val pools = listOf("tr" to "Türk", "world" to "Yabancı", "mix" to "Karışık")
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 32.dp)) {
                pools.forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = p.radioPool == key,
                        onClick = { p.changeRadioPool(key) },
                        enabled = !p.radioFromFavorites,
                        shape = SegmentedButtonDefaults.itemShape(i, pools.size),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        if (radio && p.radioFromFavorites) {
            Text(
                "Favorilerden çalıyor", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
        }
    }
}

// --------------------------------------------------------- çevrimdışı ayarları

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun OfflineSettings() {
    val s = Graph.settings
    var auto by remember { mutableStateOf(s.autoOffline) }
    var count by remember { mutableIntStateOf(s.autoOfflineCount) }
    var wifi by remember { mutableStateOf(s.wifiOnly) }
    val savedCount = Graph.offline.downloaded.size
    val usedMb = remember(savedCount) { Graph.offline.usedBytes() / (1024 * 1024) }

    InfoTitle(
        "Telefona indirme",
        "En çok dinlediğin şarkılar telefona kaydedilir; internet yokken de çalmaya devam eder. " +
            "Telefondan sildiğin şarkılar yeniden indirilmez.",
    )
    SwitchRow(
        "Sık dinlenenleri otomatik indir",
        "İnternetsiz de çalsın",
        auto,
    ) {
        auto = it
        s.autoOffline = it
        if (it) AutoOffline.run(force = true) else AutoOffline.cancel()
    }
    if (auto) {
        Text(
            "Kaç şarkı tutulsun?", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(10, 25, 50, 100, 200).forEach { n ->
                FilterChip(
                    selected = count == n,
                    onClick = {
                        count = n
                        s.autoOfflineCount = n
                    },
                    label = { Text("$n") },
                )
            }
        }
        SwitchRow("Sadece Wi-Fi'da indir", "Mobil veri harcamaz", wifi) {
            wifi = it
            s.wifiOnly = it
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(enabled = !AutoOffline.running, onClick = { AutoOffline.run(force = true) }) {
            if (AutoOffline.running) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Şimdi eşitle")
        }
        Text(
            "Telefonda $savedCount şarkı · $usedMb MB",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
    if (AutoOffline.status.isNotBlank()) {
        Text(
            AutoOffline.status, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

// --------------------------------------------------------------------- ayarlar

// Gurfy'de kütüphane zaten telefonda: "Çevrimdışı" sekmesi yok
private val settingsTabs get() =
    if (Graph.backend.hasRemoteLibrary) listOf("Ana sayfa", "Çalma", "Çevrimdışı", "Bağlantılar")
    else listOf("Ana sayfa", "Çalma", "Bağlantılar")

@Composable
fun SettingsScreen(nav: Nav) {
    // İlk kurulumda (bağlantı yokken) doğrudan Bağlantılar sekmesi açık
    val tabs = settingsTabs
    var tab by rememberSaveable { mutableIntStateOf(if (Graph.settings.configured) 0 else tabs.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Header("Ayarlar", nav)
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = tab == i, onClick = { tab = i },
                    text = { Text(t, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        SwipePages(tab, tabs.size, { tab = it }) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(6.dp))
                when (tabs[page]) {
                    "Ana sayfa" -> SettingsCard { HomeSettings() }
                    "Çalma" -> {
                        SettingsCard { PlaybackSettings() }
                        SettingsCard { CrossfadeSettings() }
                    }
                    "Çevrimdışı" -> SettingsCard { OfflineSettings() }
                    else -> {
                        Graph.backend.ConnectionSettings()
                        SettingsCard { UpdateSettings() }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ----------------------------------------------------------- ayarlar: ana sayfa

@Composable
private fun HomeSettings() {
    InfoTitle("Ana sayfa bölümleri", "Oklarla sırala, anahtarla gizle ya da göster.")
    val sections = HomeLayout.sections
    sections.forEachIndexed { i, sec ->
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                sec.title, modifier = Modifier.weight(1f),
                color = if (sec.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(enabled = i > 0, onClick = { HomeLayout.move(i, -1) }) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Yukarı taşı")
            }
            IconButton(enabled = i < sections.size - 1, onClick = { HomeLayout.move(i, 1) }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Aşağı taşı")
            }
            Switch(checked = sec.visible, onCheckedChange = { HomeLayout.setVisible(sec.key, it) })
        }
    }
    TextButton(onClick = { HomeLayout.reset() }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text("Varsayılan düzene dön")
    }
}

// ------------------------------------------------------------- ayarlar: çalma

@Composable
private fun PlaybackSettings() {
    val p = Graph.player
    InfoTitle(
        "Karışık çalma modu",
        "Ana sayfadaki ve çalardaki karışık düğmesi her basışta bu modlar arasında geçer; seçim kalıcıdır. " +
            "Sana özel karışık: beğenilerin, sevdiğin türler, en çok dinlediklerin, unuttukların ve yeni eklenenler " +
            "harmanlanır; hep atladığın şarkılar girmez.",
    )
    val descriptions = mapOf(
        PlayMode.OFF to "Liste sırasıyla çalar",
        PlayMode.SHUFFLE to "Aynı liste karışık sırayla",
        PlayMode.SMART to "Şarkıdan sonra sana göre seçilenler",
    )
    PlayMode.entries.forEach { m ->
        Row(
            Modifier.fillMaxWidth().clickable { if (p.mode != m) p.changeMode(m) }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = p.mode == m, onClick = { if (p.mode != m) p.changeMode(m) })
            PlayModeIcon(m, Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(m.label)
                Text(
                    descriptions.getValue(m), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CrossfadeSettings() {
    var sec by remember { mutableIntStateOf(Graph.settings.crossfadeSec) }
    InfoTitle(
        "Şarkılar arası geçiş",
        "Şarkı biterken sıradaki şarkı başlar: biten şarkı yavaşça kısılır, yenisi yavaşça açılır. " +
            "Radyoda ve \"aynı şarkıyı tekrarla\" açıkken devreye girmez.",
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(0, 3, 5, 8, 12).forEach { n ->
            FilterChip(
                selected = sec == n,
                onClick = { sec = n; Graph.settings.crossfadeSec = n },
                label = { Text(if (n == 0) "Kapalı" else "$n sn") },
            )
        }
    }
}

// --------------------------------------------------------- ayarlar: bağlantılar

data class Status(val label: String, val ok: Boolean?, val detail: String)

@Composable
fun StatusRow(st: Status) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (st.ok) {
                true -> Icons.Filled.CheckCircle
                false -> Icons.Filled.Error
                null -> Icons.AutoMirrored.Filled.HelpOutline
            },
            contentDescription = null,
            tint = when (st.ok) {
                true -> MaterialTheme.colorScheme.secondary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(st.label)
            if (st.detail.isNotBlank()) {
                Text(st.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
