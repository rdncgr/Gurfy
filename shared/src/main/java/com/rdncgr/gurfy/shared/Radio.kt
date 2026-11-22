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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

// Radyolar: Navidrome'daki internet radyoları ("Radyolarım") + sunucuda saklanan güncel Türkiye radyoları.

/**
 * Radyoyu, içinde bulunduğu listeyle birlikte çalar: ileri/geri düğmeleri bu listede gezinir.
 * [favorites]: favoriler (Radyolarım) listesinden açıldı; rastgele radyo bu sırada kapalıdır.
 */
fun playRadio(list: List<RadioStation>, r: RadioStation, favorites: Boolean = false) {
    val i = list.indexOf(r).coerceAtLeast(0)
    Graph.player.playRadios(list.map { it.asSong() }, i, favorites)
}

@Composable
fun RadioLogo(r: RadioStation, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.favicon.isNotBlank()) {
            AsyncImage(model = r.favicon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Ana sayfadaki "Radyolarım" şeridi; dokununca hemen çalar. */
@Composable
fun RadioStrip(nav: Nav) {
    val radios by load("radios", Graph.listsVersion) { Graph.subsonic.radios() }
    LoadBox(radios) { list ->
        if (list.isEmpty()) {
            EmptyNote("Henüz radyo yok. Kütüphane > Radyo'dan Türkiye radyolarını ekleyebilirsin.")
            return@LoadBox
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list) { r ->
                Column(Modifier.width(96.dp).clickable { playRadio(list, r, favorites = true) }) {
                    RadioLogo(r, 96.dp)
                    Text(
                        r.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun RadioRow(r: RadioStation, onPlay: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val playing = Graph.player.current?.streamUrl == r.url
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioLogo(r, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                r.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val sub = r.tags.split(',').filter { it.isNotBlank() }.take(3).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

internal fun urlKey(u: String) = u.trim().lowercase().removeSuffix("/")

/** Kütüphane > Radyo sekmesi: Türk / yabancı radyolar tek listede; + favorilere ekler, − çıkarır. */
@Composable
fun RadioTab() {
    var refresh by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf("tr") }
    val service = Graph.settings.serviceConfigured
    val mine by load("radios", Graph.listsVersion) { Graph.subsonic.radios() }
    val trLoad by load("radios-server", "tr", refresh) {
        if (service) Graph.service.radios(refresh = refresh > 0, scope = "tr") else emptyList()
    }
    val worldLoad by load("radios-server", "world", refresh) {
        if (service) Graph.service.radios(refresh = refresh > 0, scope = "world") else emptyList()
    }
    val trList = (trLoad as? Load.Ok)?.value.orEmpty()
    val worldList = (worldLoad as? Load.Ok)?.value.orEmpty()
    val favorites = (mine as? Load.Ok)?.value.orEmpty()

    fun match(r: RadioStation, list: List<RadioStation>) =
        list.firstOrNull { urlKey(it.url) == urlKey(r.url) || it.name.equals(r.name, ignoreCase = true) }
    // Navidrome'a elle eklenmiş, sunucu listelerinde olmayan favoriler Türk radyolarının başında görünür
    val extras = if (scope == "tr") favorites.filter { match(it, trList) == null && match(it, worldList) == null } else emptyList()
    val base = if (scope == "world") worldList else trList
    val q = filter.trim().lowercase()
    val all = (extras + base).filter { q.isBlank() || q in it.name.lowercase() || q in it.tags.lowercase() }
    val state = if (scope == "world") worldLoad else trLoad

    Column(Modifier.fillMaxSize()) {
        // Üstte sabit: Türk / yabancı seçimi ve arama
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            SegmentedButton(
                selected = scope == "tr", onClick = { scope = "tr" },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Türk radyoları") }
            SegmentedButton(
                selected = scope == "world", onClick = { scope = "world" },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Yabancı radyolar") }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                filter, { filter = it }, singleLine = true,
                placeholder = { Text("Radyo ara") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Temizle")
                    }
                },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Listeyi güncelle") }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            when {
                !service && favorites.isEmpty() -> item {
                    EmptyNote("Güncel radyo listeleri için Ayarlar > Bağlantılar'dan Gurfy servisini girin.")
                }
                state is Load.Loading && all.isEmpty() -> item { LoadBox(state) {} }
                state is Load.Err && all.isEmpty() -> item { EmptyNote(state.message) }
                all.isEmpty() -> item { EmptyNote("Radyo bulunamadı.") }
            }
            itemsIndexed(all, key = { i, it -> "r$i" + it.url }) { _, r ->
                RadioRow(r, onPlay = { playRadio(all, r) }) { FavoriteToggle(r, match(r, favorites)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** + favorilere ekle (Navidrome'a kaydeder), − favorilerden çıkar. fav: Navidrome'daki karşılığı. */
@Composable
private fun FavoriteToggle(r: RadioStation, fav: RadioStation?) {
    val id = fav?.id
    if (id != null) {
        IconButton(onClick = {
            Graph.io("${r.name} favorilerden çıkarıldı", then = { Graph.listsVersion++ }) { Graph.subsonic.deleteRadio(id) }
        }) {
            Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Favorilerden çıkar", tint = MaterialTheme.colorScheme.primary)
        }
    } else {
        IconButton(onClick = {
            Graph.io("${r.name} favorilere eklendi", then = { Graph.listsVersion++ }) { Graph.subsonic.createRadio(r) }
        }) { Icon(Icons.Filled.AddCircleOutline, contentDescription = "Favorilere ekle") }
    }
}

/** Radyo çalarken "Şimdi çalıyor" ekranındaki beğen düğmesi: radyoyu favorilere (Navidrome) ekler / çıkarır. */
@Composable
fun RadioFavoriteButton(song: Song) {
    val url = song.streamUrl ?: return
    val favs by load("radios", Graph.listsVersion) { Graph.subsonic.radios() }
    val fav = (favs as? Load.Ok)?.value?.firstOrNull {
        urlKey(it.url) == urlKey(url) || it.name.equals(song.title, ignoreCase = true)
    }
    var busy by remember { mutableStateOf(false) }
    IconButton(enabled = !busy && favs is Load.Ok, onClick = {
        busy = true
        val id = fav?.id
        Graph.io(then = { Graph.listsVersion++; busy = false }) {
            try {
                if (id != null) Graph.subsonic.deleteRadio(id)
                else Graph.subsonic.createRadio(RadioStation(null, song.title, url, favicon = song.logo ?: ""))
            } finally {
                busy = false
            }
        }
    }) {
        Icon(
            if (fav != null) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (fav != null) "Favorilerden çıkar" else "Favorilere ekle",
            tint = if (fav != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
