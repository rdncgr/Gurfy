package com.rdncgr.gurfysync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Şarkının türünü elle ekleme / değiştirme. Tür, Nota servisi tarafından dosyanın etiketine yazılır
// ve Navidrome taratılır; yeni tür tarama bitince kütüphanede görünür.

/** Navidrome bazen birden fazla türü "Pop; Rock" gibi birleştirir. */
private fun splitGenres(text: String?): List<String> =
    text.orEmpty().split(';', ',', '/').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

fun saveGenres(song: Song, genres: List<String>) {
    Graph.io(then = {
        Graph.toast(
            if (genres.isEmpty()) "${song.title}: tür silindi"
            else "${song.title}: ${genres.joinToString(", ")} olarak kaydedildi",
        )
        // Navidrome taraması birkaç saniye sürer; ardından listeleri yenile
        Graph.scope.launch {
            delay(8_000)
            Graph.listsVersion++
        }
    }) { Graph.service.setGenres(song.id, genres) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenreEditDialog(song: Song, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(splitGenres(song.genre).joinToString(", ")) }
    val known by load("genre-names") { Graph.subsonic.genres().map { it.name } }
    val chosen = splitGenres(text)

    fun toggle(name: String) {
        val now = splitGenres(text)
        text = (if (now.any { it.equals(name, true) }) now.filterNot { it.equals(name, true) } else now + name)
            .joinToString(", ")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Category, contentDescription = null) },
        title = { Text("Türü düzenle") },
        text = {
            Column {
                Text(
                    "${song.artist} – ${song.title}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    text, { text = it }, singleLine = true,
                    label = { Text("Türler") }, placeholder = { Text("Örn. Türkçe Pop, Arabesk") },
                    supportingText = { Text("Birden fazla türü virgülle ayır") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                val names = (known as? Load.Ok)?.value.orEmpty()
                if (names.isNotEmpty()) {
                    Text(
                        "Kütüphanedeki türler", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                    FlowRow(
                        Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        names.forEach { name ->
                            FilterChip(
                                selected = chosen.any { it.equals(name, true) },
                                onClick = { toggle(name) },
                                label = { Text(name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); saveGenres(song, chosen) }) { Text("Kaydet") }
        },
        dismissButton = {
            Row2 {
                if (!song.genre.isNullOrBlank()) TextButton(onClick = { onDismiss(); saveGenres(song, emptyList()) }) {
                    Text("Türü sil", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
            }
        },
    )
}

@Composable
private fun Row2(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Row { content() }
