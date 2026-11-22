package com.rdncgr.gurfy.shared

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ gezinme

sealed class Screen {
    object Home : Screen()
    object Library : Screen()
    object Search : Screen()
    object Discover : Screen()
    object SettingsScreen : Screen()
    object Downloads : Screen()
    object Starred : Screen()
    object NowPlaying : Screen()
    object Import : Screen()
    data class AlbumDetail(val id: String) : Screen()
    data class ArtistDetail(val id: String, val name: String) : Screen()
    data class PlaylistDetail(val id: String, val name: String) : Screen()
    data class Similar(val artist: String, val title: String) : Screen()
    data class GenreDetail(val name: String) : Screen()
    object Stats : Screen()
}

class Nav(start: Screen) {
    val stack = mutableStateListOf(start)
    val top: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.size - 1); true } else false
    fun tab(s: Screen) { stack.clear(); stack.add(s) }
}

// --------------------------------------------------------------- yükleme durumu

sealed interface Load<out T> {
    object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Ağ çağrısını arka planda yapar; sonucu ekrana durum olarak verir. */
@Composable
fun <T> load(vararg keys: Any?, block: () -> T): State<Load<T>> =
    produceState<Load<T>>(Load.Loading, *keys, Graph.version) {
        value = Load.Loading
        value = try {
            Load.Ok(withContext(Dispatchers.IO) { block() })
        } catch (e: Exception) {
            Load.Err(errorText(e, "Bağlantı kurulamadı"))
        }
    }

@Composable
fun <T> LoadBox(state: Load<T>, content: @Composable (T) -> Unit) {
    when (state) {
        is Load.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is Load.Err -> Text(
            state.message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(24.dp),
        )
        is Load.Ok -> content(state.value)
    }
}

// ------------------------------------------------------------ ortak parçalar

@Composable
fun Cover(coverArt: String?, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = Graph.subsonic.coverUrl(coverArt, 300),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun Header(title: String, nav: Nav, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nav.stack.size > 1) {
            IconButton(onClick = { nav.pop() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
            }
        } else {
            Box(Modifier.size(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        action()
    }
}

@Composable
fun SectionTitle(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

// --------------------------------------------------------------------- tema

/** Koyu tema; vurgu rengi uygulamaya göre (Sync sarı, Gurfy turkuaz). */
private fun appColors(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF231A04),
    secondary = Color(0xFF8FB8A8),
    background = Color(0xFF121417),
    surface = Color(0xFF121417),
    surfaceVariant = Color(0xFF242830),
    surfaceContainer = Color(0xFF1A1D22),
    onBackground = Color(0xFFECE9E2),
    onSurface = Color(0xFFECE9E2),
    onSurfaceVariant = Color(0xFFA5A8AE),
)

// ------------------------------------------------------------------ etkinlik

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        Graph.player.connect()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MaterialTheme(colorScheme = appColors(Graph.backend.accent)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GurfyRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Navidrome web arayüzünde ya da başka cihazda yapılan liste/beğeni değişikliklerini çek
        Graph.listsVersion++
        Favorites.refresh()
        // Ayar açıksa ve son eşitlemenin üzerinden yeterli süre geçtiyse sık dinlenenleri indir
        AutoOffline.run()
    }
}

private data class TabItem(val screen: Screen, val label: String, val icon: ImageVector)

@Composable
fun GurfyRoot() {
    val nav = remember { Nav(if (Graph.settings.configured) Screen.Home else Screen.SettingsScreen) }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    val tabs = remember {
        listOf(
            TabItem(Screen.Home, "Ana Sayfa", Icons.Filled.Home),
            TabItem(Screen.Library, "Kütüphane", Icons.Filled.LibraryMusic),
            TabItem(Screen.Discover, "Keşfet", Icons.Filled.Explore),
            TabItem(Screen.SettingsScreen, "Ayarlar", Icons.Filled.Settings),
        )
    }
    val top = nav.top
    UpdateDialog()
    Graph.backend.Overlay()
    PhoneDeleteHost()

    Scaffold(
        bottomBar = {
            if (top != Screen.NowPlaying) {
                Column {
                    MiniPlayer(nav)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = nav.stack.first() == t.screen,
                                onClick = { nav.tab(t.screen) },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = {
                                    Text(
                                        t.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (top) {
                Screen.Home -> HomeScreen(nav)
                Screen.Library -> LibraryScreen(nav)
                Screen.Search -> DiscoverScreen(nav)
                Screen.Discover -> DiscoverScreen(nav)
                Screen.SettingsScreen -> SettingsScreen(nav)
                Screen.Downloads -> DownloadsScreen(nav)
                Screen.Starred -> StarredScreen(nav)
                Screen.NowPlaying -> NowPlayingScreen(nav)
                Screen.Import -> ImportScreen(nav)
                is Screen.AlbumDetail -> AlbumScreen(nav, top.id)
                is Screen.ArtistDetail -> ArtistScreen(nav, top.id, top.name)
                is Screen.PlaylistDetail -> PlaylistScreen(nav, top.id, top.name)
                is Screen.Similar -> SimilarScreen(nav, top.artist, top.title)
                is Screen.GenreDetail -> GenreScreen(nav, top.name)
                Screen.Stats -> StatsScreen(nav)
            }
            DownloadProgressLine(Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
fun MiniPlayer(nav: Nav) {
    val p = Graph.player
    val song = p.current ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { nav.push(Screen.NowPlaying) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song.coverArt, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { p.toggle() }) {
            Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Çal / duraklat")
        }
        IconButton(onClick = { p.next() }) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Sonraki")
        }
    }
}
