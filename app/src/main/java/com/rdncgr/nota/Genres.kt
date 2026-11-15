package com.rdncgr.nota

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Türler: Navidrome dosyalardaki tür etiketini okur. Türü olmayan şarkılara Nota servisi
// Last.fm etiketlerinden tür yazabilir ("Tür ekle").

private val tilePalette = listOf(
    Color(0xFF8C5A2B), Color(0xFF2F6B5E), Color(0xFF5B4B8A), Color(0xFF8A3B4F),
    Color(0xFF34618F), Color(0xFF6E7A2E), Color(0xFF7A4E7A), Color(0xFF3F6F3A),
)

private fun tileColor(name: String) = tilePalette[Math.floorMod(name.lowercase().hashCode(), tilePalette.size)]

@Composable
private fun GenreTile(g: Genre, width: Dp?, onClick: () -> Unit) {
    val base = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    Column(
        base.height(72.dp).clip(RoundedCornerShape(12.dp)).background(tileColor(g.name))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(g.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${g.songCount} şarkı", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
    }
}

/** Ana sayfadaki "Türler" şeridi. */
@Composable
fun GenreStrip(nav: Nav, refresh: Int) {
    val genres by load("genres", refresh) { Graph.subsonic.genres() }
    LoadBox(genres) { list ->
        if (list.isEmpty()) {
            EmptyNote("Henüz türü olan şarkı yok. Kütüphane > Türler'den türleri otomatik ekleyebilirsin.")
            return@LoadBox
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list.take(15)) { g -> GenreTile(g, 132.dp) { nav.push(Screen.GenreDetail(g.name)) } }
        }
    }
}

/** Kütüphane > Türler sekmesi. */
@Composable
fun GenresTab(nav: Nav) {
    var refresh by remember { mutableIntStateOf(0) }
    val genres by load("genres", refresh) { Graph.subsonic.genres() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (Graph.settings.serviceConfigured) {
            item(span = { GridItemSpan(maxLineSpan) }) { BackfillCard { refresh++ } }
        }
        when (val g = genres) {
            is Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            is Load.Err -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyNote(g.message) }
            is Load.Ok -> {
                if (g.value.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyNote("Kütüphanedeki şarkılarda tür bilgisi yok.")
                }
                items(g.value, key = { it.name }) { genre ->
                    GenreTile(genre, null) { nav.push(Screen.GenreDetail(genre.name)) }
                }
            }
        }
    }
}

/** Sunucudaki türsüz şarkılara tür yazdırma kartı (ilerlemeyle birlikte). */
@Composable
private fun BackfillCard(onFinished: () -> Unit) {
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var starting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val running = status?.optBoolean("running") == true

    // Açılışta ve çalışırken 2 sn'de bir durumu sor
    LaunchedEffect(running) {
        while (true) {
            val st = try { withContext(Dispatchers.IO) { Graph.service.genreBackfillStatus() } } catch (e: Exception) { null }
            val wasRunning = status?.optBoolean("running") == true
            status = st
            if (wasRunning && st?.optBoolean("running") == false) onFinished()
            if (st?.optBoolean("running") != true) break
            delay(2000)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Otomatik tür ekle", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                if (!running) {
                    FilledTonalButton(enabled = !starting, onClick = {
                        starting = true
                        scope.launch {
                            status = try {
                                withContext(Dispatchers.IO) { Graph.service.startGenreBackfill() }
                            } catch (e: Exception) {
                                Graph.toast(e.message ?: "Başlatılamadı"); status
                            }
                            starting = false
                        }
                    }) { Text("Başlat") }
                }
            }
            val st = status
            when {
                running && st != null -> {
                    val total = st.optInt("total").coerceAtLeast(1)
                    val done = st.optInt("scanned")
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Text(
                        "$done / ${st.optInt("total")} dosya · ${st.optInt("updated")} şarkıya tür eklendi",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                st != null && st.has("finished_at") && !st.isNull("finished_at") -> Text(
                    "Son tarama: ${st.optInt("updated")} şarkıya tür eklendi, ${st.optInt("had_genre")} şarkının türü zaten vardı, " +
                        "${st.optInt("not_found")} şarkı için tür bulunamadı.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                else -> Text(
                    "Türü boş olan şarkılara Last.fm'den tür yazılır; türü olanlara dokunulmaz. " +
                        "Bitince Navidrome yeniden taranır. Yeni indirilen şarkılara tür kendiliğinden eklenir.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Bir türün şarkıları. */
@Composable
fun GenreScreen(nav: Nav, name: String) {
    val songs by load("genre-songs", name) { Graph.subsonic.songsByGenre(name) }
    SongListScreen(name, nav, songs)
}
