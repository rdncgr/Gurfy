package com.rdncgr.gurfy.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Çalma listeleri Navidrome'da tutulur; böylece web arayüzünde ve diğer cihazlarda da görünür.

private fun listsChanged() { Graph.listsVersion++ }

fun createPlaylist(name: String, songs: List<Song> = emptyList()) {
    val msg = if (songs.isEmpty()) "\"$name\" listesi oluşturuldu" else "\"$name\" listesine ${songs.size} şarkı eklendi"
    Graph.io(msg, then = ::listsChanged) { Graph.subsonic.createPlaylist(name, songs.map { it.id }) }
}

fun addToPlaylist(p: Playlist, songs: List<Song>) {
    Graph.io("\"${p.name}\" listesine ${songs.size} şarkı eklendi", then = ::listsChanged) {
        Graph.subsonic.addToPlaylist(p.id, songs.map { it.id })
    }
}

// -------------------------------------------------------------------- pencereler

@Composable
fun NameDialog(title: String, initial: String = "", confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Liste adı") })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

/** Şarkı(ları) mevcut bir listeye ya da yeni bir listeye ekler. */
@Composable
fun AddToPlaylistDialog(songs: List<Song>, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("Yeni liste", confirm = "Oluştur", onDismiss = onDismiss) { name ->
            createPlaylist(name, songs)
            onDismiss()
        }
        return
    }
    val lists by load("playlists", Graph.listsVersion) { Graph.subsonic.playlists() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listeye ekle") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Yeni liste oluştur", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
                }
                LoadBox(lists) { list ->
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(list) { p ->
                            Text(
                                "${p.name}  ·  ${p.songCount} şarkı", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    addToPlaylist(p, songs)
                                    onDismiss()
                                }.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

// ------------------------------------------------------------- ana sayfa şeridi

@Composable
fun PlaylistStrip(nav: Nav) {
    val lists by load("playlists", Graph.listsVersion) { Graph.subsonic.playlists() }
    LoadBox(lists) { list ->
        if (list.isEmpty()) {
            EmptyNote("Henüz liste yok. Sağ üstteki liste simgesiyle yeni bir liste oluşturabilirsin.")
            return@LoadBox
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list) { p ->
                Column(Modifier.width(112.dp).clickable { nav.push(Screen.PlaylistDetail(p.id, p.name)) }) {
                    Cover(p.coverArt, 112.dp)
                    Text(
                        p.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "${p.songCount} şarkı", maxLines = 1,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- liste ekranı

@Composable
fun PlaylistScreen(nav: Nav, id: String, name: String) {
    var title by remember { mutableStateOf(name) }
    val songs by load("playlist", id, Graph.listsVersion) { Graph.subsonic.playlistSongs(id) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (renaming) {
        NameDialog("Listeyi yeniden adlandır", title, "Kaydet", { renaming = false }) { newName ->
            renaming = false
            title = newName
            Graph.io("Liste adı değişti", then = ::listsChanged) { Graph.subsonic.renamePlaylist(id, newName) }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Liste silinsin mi?") },
            text = { Text("\"$title\" listesi silinir. Şarkılar kütüphanede kalır.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    Graph.io("Liste silindi", then = ::listsChanged) { Graph.subsonic.deletePlaylist(id) }
                    nav.pop()
                }) { Text("Sil", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Vazgeç") } },
        )
    }

    SongListScreen(
        title, nav, songs,
        action = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Liste seçenekleri") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Yeniden adlandır") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Listeyi sil") }, onClick = { menu = false; deleting = true })
                }
            }
        },
        onRemove = { index ->
            Graph.io("Listeden çıkarıldı", then = ::listsChanged) { Graph.subsonic.removeFromPlaylist(id, index) }
        },
    )
}
