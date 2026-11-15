package com.rdncgr.nota

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------- ortak satırlar

/** Bu oturumda sunucudan silinen şarkılar (Navidrome taraması bitene kadar listelerde gizlenir). */
object Deleted {
    val ids = mutableStateMapOf<String, Boolean>()
}

/** Şarkının dosyasını sunucudan siler; telefondaki kopyası ve çalma sırasındaki yeri de kalkar. */
fun deleteFromServer(song: Song) {
    Graph.io(then = {
        Deleted.ids[song.id] = true
        if (Graph.offline.downloaded.containsKey(song.id)) Graph.offline.delete(song.id)
        Graph.player.remove(song.id)
        Graph.listsVersion++
        Graph.toast("${song.title} sunucudan silindi")
    }) { Graph.service.deleteSong(song.id) }
}

@Composable
fun ServerDeleteDialog(song: Song, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
        title = { Text("Sunucudan silinsin mi?") },
        text = {
            Text(
                "\"${song.artist} – ${song.title}\" dosyası sunucudaki müzik klasöründen kalıcı olarak silinecek. " +
                    "Bu işlem geri alınamaz.",
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); deleteFromServer(song) }) {
                Text("Sil", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
fun SongRow(song: Song, nav: Nav, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    if (Deleted.ids[song.id] == true) return
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) ServerDeleteDialog(song) { confirmDelete = false }
    var addToList by remember { mutableStateOf(false) }
    if (addToList) AddToPlaylistDialog(listOf(song)) { addToList = false }
    val offline = Graph.offline
    val saved = offline.downloaded.containsKey(song.id)
    val busy = offline.inProgress[song.id] == true
    val isCurrent = Graph.player.current?.id == song.id

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song.coverArt, 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val liked = Favorites.isStarred(song)
        if (liked) {
            Icon(
                Icons.Filled.Favorite, contentDescription = "Beğenildi",
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(end = 2.dp),
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else if (saved) {
            Icon(
                Icons.Filled.DownloadDone, contentDescription = "Telefonda kayıtlı",
                tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Seçenekler")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (saved) "Telefondan sil" else "Telefona indir") },
                    onClick = {
                        menu = false
                        if (saved) offline.delete(song.id) else Graph.saveOffline(song)
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (liked) "Beğeniyi kaldır" else "Beğen") },
                    onClick = {
                        menu = false
                        Favorites.toggle(song)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Listeye ekle") },
                    onClick = {
                        menu = false
                        addToList = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Sunucudan sil") },
                    onClick = {
                        menu = false
                        confirmDelete = true
                    },
                )
                if (onRemove != null) {
                    DropdownMenuItem(
                        text = { Text("Listeden çıkar") },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Benzerlerini bul") },
                    onClick = {
                        menu = false
                        nav.push(Screen.Similar(song.artist, song.title))
                    },
                )
                val albumId = song.albumId
                if (albumId != null) {
                    DropdownMenuItem(
                        text = { Text("Albüme git") },
                        onClick = {
                            menu = false
                            nav.push(Screen.AlbumDetail(albumId))
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumRow(album: Album, nav: Nav) {
    Row(
        Modifier.fillMaxWidth().clickable { nav.push(Screen.AlbumDetail(album.id)) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(album.coverArt, 56.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SimpleRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
}

@Composable
fun PlayButtons(songs: List<Song>) {
    var addToList by remember { mutableStateOf(false) }
    if (addToList) AddToPlaylistDialog(songs) { addToList = false }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { Graph.player.play(songs, 0) }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Çal")
        }
        OutlinedButton(onClick = { Graph.player.play(songs.shuffled(), 0) }) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Karışık")
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { addToList = true }) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Hepsini listeye ekle")
        }
        IconButton(onClick = {
            songs.forEach { Graph.saveOffline(it) }
            Graph.info("Telefona indiriliyor")
        }) {
            Icon(Icons.Filled.Download, contentDescription = "Hepsini telefona indir")
        }
    }
}

/** Başlık + çal düğmeleri + şarkı listesi. Albüm, liste, indirilenler ekranlarının ortak gövdesi. */
@Composable
fun SongListScreen(
    title: String,
    nav: Nav,
    state: Load<List<Song>>,
    action: @Composable () -> Unit = {},
    onRemove: ((Int) -> Unit)? = null,
    top: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Header(title, nav, action)
        LoadBox(state) { songs ->
            if (songs.isEmpty()) {
                EmptyNote("Burada henüz şarkı yok.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { top() }
                    item { PlayButtons(songs) }
                    itemsIndexed(songs) { i, s ->
                        SongRow(s, nav, onRemove = onRemove?.let { remove -> { remove(i) } }) {
                            Graph.player.play(songs, i)
                        }
                    }
                }
            }
        }
    }
}

/** Karışık modunun simgesi: kapalı (gri), rastgele, sana özel. */
@Composable
fun PlayModeIcon(mode: PlayMode, modifier: Modifier = Modifier) {
    Icon(
        when (mode) {
            PlayMode.SMART -> Icons.Filled.AutoAwesome
            else -> Icons.Filled.Shuffle
        },
        contentDescription = mode.label,
        tint = if (mode == PlayMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

// ------------------------------------------------------------------ ana sayfa

@Composable
fun HomeScreen(nav: Nav) {
    var refresh by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val p = Graph.player
    if (creating) {
        NameDialog("Yeni liste", confirm = "Oluştur", onDismiss = { creating = false }) { name ->
            createPlaylist(name)
            creating = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Nota", nav) {
            // Tek düğme: her basışta Kapalı → Rastgele → Sana özel karışık (etkinse dolu arka plan)
            val active = p.mode != PlayMode.OFF
            if (active) FilledTonalIconButton(onClick = { p.cycleMode() }) {
                if (p.preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else PlayModeIcon(p.mode)
            } else IconButton(onClick = { p.cycleMode() }) {
                if (p.preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else PlayModeIcon(p.mode)
            }
            IconButton(onClick = { nav.push(Screen.Downloads) }) {
                Icon(Icons.Filled.Smartphone, contentDescription = "Telefondakiler")
            }
            IconButton(onClick = { creating = true }) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Yeni liste")
            }
            IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Yenile") }
        }
        // Mod etkinken altında kısa bir not: hangi modda olduğun anlaşılsın
        if (p.mode != PlayMode.OFF) {
            Text(
                p.mode.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        HomeLayout.sections.filter { it.visible }.forEach { sec ->
            key(sec.key) { HomeSectionView(sec, nav, refresh) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeSectionView(sec: HomeSection, nav: Nav, refresh: Int) {
    when (sec.key) {
        "playlists" -> {
            SectionTitle(sec.title)
            PlaylistStrip(nav)
        }
        "starred" -> {
            SectionTitle(sec.title, actionLabel = "Tümü") { nav.push(Screen.Starred) }
            val songs by load("home-starred", refresh, Graph.listsVersion) { Graph.subsonic.starred() }
            SongStrip(songs, "Henüz beğendiğin şarkı yok. Çalan şarkıda kalp simgesine dokun.")
        }
        "forgotten" -> ForgottenSection(sec.title, refresh)
        "genres" -> {
            SectionTitle(sec.title, actionLabel = "Tümü") { nav.tab(Screen.Library); LibraryTab.selected = 2 }
            GenreStrip(nav, refresh)
        }
        "radio" -> {
            SectionTitle(sec.title, actionLabel = "Tümü") { nav.tab(Screen.Library); LibraryTab.selected = 4 }
            RadioStrip(nav)
        }
        // Şarkı listeleri: dokununca albüm açılmaz, şarkı doğrudan çalar
        "recent", "frequent", "newest" -> {
            SectionTitle(sec.title)
            val songs by load("home-songs", sec.key, refresh) { Graph.subsonic.songList(sec.key, 30) }
            SongStrip(songs, if (sec.key == "newest") "Henüz bir şey yok." else "Henüz dinlenme geçmişi yok.")
        }
        else -> {
            SectionTitle(sec.title)
            val albums by load("home", sec.key, refresh) { Graph.subsonic.albums(sec.key, 20) }
            AlbumStrip(albums, nav)
        }
    }
}

/** Kütüphane'de açık olan sekme (ana sayfadan "Radyo"ya geçebilmek için dışarıda tutulur). */
object LibraryTab {
    var selected by mutableIntStateOf(0)
}

/** Yatay şarkı şeridi; dokununca şeridin tamamı o şarkıdan başlayarak çalar. */
@Composable
fun SongStrip(state: Load<List<Song>>, emptyText: String) {
    LoadBox(state) { all ->
        val songs = all.take(30)
        if (songs.isEmpty()) {
            EmptyNote(emptyText)
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(songs) { i, s ->
                    Column(Modifier.width(112.dp).clickable { Graph.player.play(songs, i) }) {
                        Cover(s.coverArt, 112.dp)
                        Text(
                            s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumStrip(state: Load<List<Album>>, nav: Nav) {
    LoadBox(state) { albums ->
        if (albums.isEmpty()) {
            EmptyNote("Henüz bir şey yok.")
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(albums) { a ->
                    Column(Modifier.width(132.dp).clickable { nav.push(Screen.AlbumDetail(a.id)) }) {
                        Cover(a.coverArt, 132.dp)
                        Text(
                            a.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            a.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ kütüphane

@Composable
fun LibraryScreen(nav: Nav) {
    val tab = LibraryTab.selected
    val titles = listOf("Albümler", "Sanatçılar", "Türler", "Listeler", "Radyo")

    Column(Modifier.fillMaxSize()) {
        Header("Kütüphane", nav) {
            IconButton(onClick = { nav.push(Screen.Stats) }) {
                Icon(Icons.Filled.Insights, contentDescription = "Dinleme istatistiklerim")
            }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
            titles.forEachIndexed { i, t ->
                Tab(
                    selected = tab == i, onClick = { LibraryTab.selected = i },
                    text = { Text(t, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        when (tab) {
            4 -> RadioTab()
            2 -> GenresTab(nav)
            0 -> {
                val albums by load("albums") { Graph.subsonic.albums("alphabeticalByName", 500) }
                LoadBox(albums) { list ->
                    if (list.isEmpty()) EmptyNote("Kütüphanede albüm yok.")
                    LazyColumn(Modifier.fillMaxSize()) { items(list) { AlbumRow(it, nav) } }
                }
            }
            1 -> {
                val artists by load("artists") { Graph.subsonic.artists() }
                LoadBox(artists) { list ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list) { a ->
                            SimpleRow(a.name, "${a.albumCount} albüm") { nav.push(Screen.ArtistDetail(a.id, a.name)) }
                        }
                    }
                }
            }
            else -> {
                val playlists by load("playlists", Graph.listsVersion) { Graph.subsonic.playlists() }
                var creating by remember { mutableStateOf(false) }
                if (creating) {
                    NameDialog("Yeni liste", confirm = "Oluştur", onDismiss = { creating = false }) { name ->
                        createPlaylist(name)
                        creating = false
                    }
                }
                LoadBox(playlists) { list ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            OutlinedButton(
                                onClick = { creating = true },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Yeni liste")
                            }
                        }
                        if (list.isEmpty()) item { EmptyNote("Henüz çalma listesi yok.") }
                        items(list) { p ->
                            SimpleRow(p.name, "${p.songCount} şarkı") { nav.push(Screen.PlaylistDetail(p.id, p.name)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(nav: Nav, id: String) {
    val state by load("album", id) { Graph.subsonic.album(id) }
    val current = state
    val title = if (current is Load.Ok) current.value.first.name else "Albüm"
    val songs: Load<List<Song>> = when (current) {
        is Load.Ok -> Load.Ok(current.value.second)
        is Load.Err -> current
        is Load.Loading -> Load.Loading
    }
    SongListScreen(title, nav, songs) {
        if (current is Load.Ok) {
            val album = current.value.first
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(album.coverArt, 112.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(album.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(album.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(nav: Nav, id: String, name: String) {
    val albums by load("artist", id) { Graph.subsonic.artistAlbums(id) }
    Column(Modifier.fillMaxSize()) {
        Header(name, nav)
        LoadBox(albums) { list ->
            LazyColumn(Modifier.fillMaxSize()) { items(list) { AlbumRow(it, nav) } }
        }
    }
}

@Composable
fun StarredScreen(nav: Nav) {
    val songs by load("starred", Graph.listsVersion) { Graph.subsonic.starred() }
    SongListScreen("Beğeniler", nav, songs)
}

@Composable
fun DownloadsScreen(nav: Nav) {
    val songs = Graph.offline.downloaded.values.sortedBy { it.title.lowercase() }
    SongListScreen("Telefondakiler", nav, Load.Ok(songs))
}

// ----------------------------------------------------------------------- arama

@Composable
/** Keşfet > Ara sekmesi: kütüphane, radyolar ve sunucuya indirilebilecek şarkılar. */
fun SearchContent(nav: Nav) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var submitted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(Graph.settings.searchHistory) }

    fun saveHistory(q: String) {
        val text = q.trim()
        if (text.isBlank()) return
        // En yeni başta, tekrarsız, en fazla 20 kayıt
        history = (listOf(text) + history.filter { !it.equals(text, ignoreCase = true) }).take(20)
        Graph.settings.searchHistory = history
    }

    fun run(q: String) {
        val text = q.trim()
        query = text
        submitted = text
        saveHistory(text)
    }

    // Yazarken arama: 2 harften sonra, yazmaya 400 ms ara verilince (her tuşta sunucuya gitmez)
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        submitted = text
    }
    val radios by load("radios", Graph.listsVersion) { Graph.subsonic.radios() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) submitted = "" // kutu temizlenince son aramalar geri gelir
            },
            singleLine = true,
            label = { Text("Şarkı, albüm, sanatçı ya da radyo") },
            trailingIcon = {
                IconButton(onClick = { run(query) }) {
                    Icon(Icons.Filled.Search, contentDescription = "Ara")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run(query) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (submitted.isBlank()) {
            if (history.isEmpty()) {
                EmptyNote("Şarkı, albüm ya da sanatçı adı yazın.")
            } else {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("Son aramalar") }
                    TextButton(onClick = {
                        history = emptyList()
                        Graph.settings.searchHistory = emptyList()
                    }) { Text("Temizle") }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(history, key = { it }) { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { run(h) }.padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(h, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                            IconButton(onClick = {
                                history = history - h
                                Graph.settings.searchHistory = history
                            }) { Icon(Icons.Filled.Close, contentDescription = "Bu aramayı sil") }
                        }
                    }
                }
            }
        } else {
            val result by load("search", submitted) { Graph.subsonic.search(submitted) }
            // Kütüphanede olmayan, sunucuya indirilebilecek şarkılar (Nota servisi / Last.fm)
            val online by load("search-online", submitted) {
                if (Graph.settings.serviceConfigured) Graph.service.search(submitted).filter { !it.inLibrary } else emptyList()
            }
            val queued = remember { mutableStateMapOf<String, Boolean>() }
            val q = submitted.lowercase()
            val radioHits = (radios as? Load.Ok)?.value?.filter { q in it.name.lowercase() || q in it.tags.lowercase() }.orEmpty()
            LoadBox(result) { r ->
                LazyColumn(Modifier.fillMaxSize()) {
                    if (radioHits.isNotEmpty()) item { SectionTitle("Radyolar") }
                    items(radioHits, key = { "r" + (it.id ?: it.url) }) { st ->
                        RadioRow(st, onPlay = { saveHistory(submitted); playRadio(radioHits, st) })
                    }
                    if (r.artists.isEmpty() && r.albums.isEmpty() && r.songs.isEmpty() && radioHits.isEmpty()) {
                        item { EmptyNote("Kütüphanede bulunamadı.") }
                    }
                    if (r.artists.isNotEmpty()) item { SectionTitle("Sanatçılar") }
                    items(r.artists) { a ->
                        SimpleRow(a.name, "") { saveHistory(submitted); nav.push(Screen.ArtistDetail(a.id, a.name)) }
                    }
                    if (r.albums.isNotEmpty()) item { SectionTitle("Albümler") }
                    items(r.albums) { AlbumRow(it, nav) }
                    if (r.songs.isNotEmpty()) item { SectionTitle("Şarkılar") }
                    itemsIndexed(r.songs) { i, s ->
                        SongRow(s, nav) { saveHistory(submitted); Graph.player.play(r.songs, i) }
                    }
                    if (Graph.settings.serviceConfigured) {
                        item { SectionTitle("İndirilebilir") }
                        when (val o = online) {
                            is Load.Loading -> item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                            }
                            is Load.Err -> item { EmptyNote(o.message) }
                            is Load.Ok -> {
                                if (o.value.isEmpty()) item { EmptyNote("İndirilecek yeni şarkı bulunamadı.") }
                                itemsIndexed(o.value, key = { i, it -> "o$i" + it.artist + "|" + it.title }) { _, sug ->
                                    val k = sug.artist.lowercase() + "|" + sug.title.lowercase()
                                    SuggestionRow(sug, queued[k] == true) {
                                        saveHistory(submitted)
                                        queued[k] = true
                                        Graph.io { Graph.service.download(sug) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
