package com.rdncgr.gurfy.shared

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

// Telefonun kendi müzikleri (başka uygulamalarla indirilen ya da kopyalanan şarkılar).
// Android'in medya kütüphanesinden okunur; sunucudan indirilenlerle birlikte
// "Telefondakiler" ekranında listelenir ve çalınır.

object PhoneMusic {
    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Telefondaki müzik dosyaları (zil sesi, bildirim sesi, sesli not gibi müzik olmayanlar hariç). */
    fun songs(ctx: Context): List<Song> {
        if (!allowed(ctx)) return emptyList()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
        )
        val out = mutableListOf<Song>()
        // Gurfy'de Music/Gurfy klasörü zaten kütüphane: burada ikinci kez görünmesin
        val skipLibrary = !Graph.backend.hasRemoteLibrary && Build.VERSION.SDK_INT >= 29
        val where = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000" +
            if (skipLibrary) " AND ${MediaStore.Audio.Media.RELATIVE_PATH} NOT LIKE ?" else ""
        ctx.contentResolver.query(
            uri, cols, where, if (skipLibrary) arrayOf("Music/Gurfy/%") else null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val artist = c.getString(2)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "Bilinmeyen sanatçı"
                out += Song(
                    id = "local:$id",
                    title = c.getString(1) ?: "?",
                    artist = artist,
                    album = c.getString(3) ?: "",
                    albumId = null,
                    coverArt = ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"), c.getLong(4),
                    ).toString(),
                    duration = (c.getLong(5) / 1000).toInt(),
                    localUri = ContentUris.withAppendedId(uri, id).toString(),
                )
            }
        }
        return out
    }
}

/** Telefondakiler: sunucudan indirilenler + telefonun kendi müzikleri, birlikte çalınabilir. */
@Composable
fun DownloadsScreen(nav: Nav) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(PhoneMusic.allowed(ctx)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 tümü, 1 indirilenler, 2 telefonun müzikleri
    val phone by load("phone-music", granted) { PhoneMusic.songs(ctx) }
    val downloaded = Graph.offline.downloaded.values.sortedBy { it.title.lowercase() }
    val local = (phone as? Load.Ok)?.value.orEmpty()
    val songs = when (filter) {
        1 -> downloaded
        2 -> local
        else -> (downloaded + local).sortedBy { it.title.lowercase() }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Telefondakiler", nav)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(filter == 0, { filter = 0 }, label = { Text("Tümü (${downloaded.size + local.size})") })
            if (Graph.backend.hasRemoteLibrary) {
                FilterChip(filter == 1, { filter = 1 }, label = { Text("Sunucudan indirilenler (${downloaded.size})") })
            }
            FilterChip(filter == 2, { filter = 2 }, label = { Text("Telefonun müzikleri (${local.size})") })
        }
        if (!granted) {
            EmptyNote(
                if (asked) "Telefonun kendi müziklerini göstermek için Ayarlar > Uygulamalar > " +
                    ctx.applicationInfo.loadLabel(ctx.packageManager) + " > İzinler'den " +
                    "\"Müzik ve ses\" iznini ver."
                else "Telefonda başka yollarla bulunan müzikleri de burada görmek için izin ver.",
            )
            if (!asked) Button(onClick = { ask.launch(PhoneMusic.permission) }, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text("Telefonun müziklerine izin ver")
            }
        }
        if (songs.isEmpty()) {
            EmptyNote("Burada henüz şarkı yok.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item { PlayButtons(songs) }
                itemsIndexed(songs, key = { i, s -> "$i" + s.id }) { i, s ->
                    SongRow(s, nav) { Graph.player.play(songs, i) }
                }
            }
        }
    }
}
