package com.rdncgr.gurfysync

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rdncgr.gurfy.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Gurfy Sync: veri Navidrome'dan ve ev sunucusundaki Gurfy servisinden gelir. */
class SyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.backendFactory = { ctx, settings -> SyncBackend(ctx, settings) }
    }
}

class SyncBackend(ctx: Context, private val settings: Settings) : Backend {
    override val library: Library = Subsonic(settings, File(ctx.filesDir, "cache"))
    override val service: MusicService = ServiceApi(settings)
    override val libraryReady: Boolean get() = settings.navidromeUrl.isNotBlank() && settings.user.isNotBlank()
    override val serviceReady: Boolean get() = settings.serviceUrl.isNotBlank()
    override val hasRemoteLibrary: Boolean = true
    override val accent: Color = Color(0xFFF2B84B)
    override val releaseTag = "gurfy-sync-v"

    override fun start(ctx: Context) = SettingsBackup.start(settings.prefs)

    @Composable
    override fun ConnectionSettings() = SyncConnectionSettings()
}

// --------------------------------------------------------- ayarlar: bağlantılar

/** Navidrome, Nota servisi, Last.fm ve servis içi bağlantıları tek seferde sınar. */
internal fun checkAll(): List<Status> {
    val out = mutableListOf<Status>()
    try {
        Graph.subsonic.ping()
        out += Status("Navidrome", true, "Telefondan bağlanılıyor")
    } catch (e: Exception) {
        out += Status("Navidrome", false, errorText(e, "Ulaşılamadı"))
    }
    if (!Graph.settings.serviceConfigured) {
        out += Status("Gurfy servisi", null, "Adres girilmemiş")
        out += Status("Last.fm", null, "Servis olmadan kontrol edilemez")
        return out
    }
    try {
        Graph.service.jobs() // servis parolasını da doğrular
        val h = Graph.service.health()
        out += Status("Gurfy servisi", true, "yt-dlp " + h.optString("yt_dlp", "?"))
        if (h.has("po_token")) {
            out += Status(
                "YouTube PO token", h.optBoolean("po_token"),
                if (h.optBoolean("po_token")) "bgutil çalışıyor; bot engeli daha seyrek"
                else "bgutil-provider konteynerine ulaşılamıyor (${h.optString("po_token_url")})",
            )
            if (h.has("youtube_ipv6")) out += Status(
                "YouTube IPv6", h.optBoolean("youtube_ipv6"),
                if (h.optBoolean("youtube_ipv6")) "IPv6 ile çıkılıyor; bot engeli çok daha seyrek"
                else "Yalnızca IPv4: YAML'da nota-servis için network_mode: host kullanın",
            )
            out += Status(
                "YouTube JS çözücü", h.optBoolean("js_runtime"),
                if (h.optBoolean("js_runtime")) "Deno kurulu" else "Deno yok: YAML'daki pip satırına 'deno' ekleyin",
            )
        }
        out += Status(
            "Last.fm", h.optBoolean("lastfm"),
            if (h.optBoolean("lastfm")) "Anahtar tanımlı; benzer şarkı önerileri açık"
            else "Anahtar yok: sunucudaki YAML'da LASTFM_API_KEY tanımlayın",
        )
        val lfmUser = Graph.settings.lastfmUser
        if (lfmUser.isNotBlank()) {
            out += try {
                val u = Graph.service.lastfm("user?user=" + java.net.URLEncoder.encode(lfmUser, "UTF-8"))
                Status("Last.fm hesabı", true, "${u.optString("name")} · ${u.optInt("playcount")} dinleme")
            } catch (e: Exception) {
                Status("Last.fm hesabı", false, errorText(e, "Kullanıcı bulunamadı"))
            }
        }
        val lbUser = Graph.settings.listenbrainzUser
        if (lbUser.isNotBlank()) {
            out += try {
                val u = Graph.service.listenbrainzUser(lbUser)
                val n = u.optInt("listens")
                Status(
                    "ListenBrainz hesabı", true,
                    if (n > 0) "$lbUser · $n dinleme" else "$lbUser · henüz dinleme yok; öneriler birkaç hafta sonra gelir",
                )
            } catch (e: Exception) {
                Status("ListenBrainz hesabı", false, errorText(e, "Kullanıcı bulunamadı"))
            }
        }
        out += Status(
            "Servis → Navidrome", h.optBoolean("navidrome"),
            if (h.optBoolean("navidrome")) "İndirilenler kütüphaneye taranıyor"
            else h.optString("navidrome_error").ifBlank { "Servis Navidrome'a bağlanamıyor" },
        )
        out += Status(
            "Müzik klasörü", h.optBoolean("music_dir_writable"),
            if (h.optBoolean("music_dir_writable")) "Yazılabilir" else "Servis müzik klasörüne yazamıyor",
        )
        out += Status(
            "Spotify API", if (h.optBoolean("spotify_api")) true else null,
            if (h.optBoolean("spotify_api")) "Uzun listeler destekleniyor"
            else "İsteğe bağlı: yalnızca bağlantıyla aktarılan 100'den uzun listeler için",
        )
    } catch (e: Exception) {
        out += Status("Gurfy servisi", false, errorText(e, "Ulaşılamadı"))
    }
    return out
}

@Composable
internal fun SyncConnectionSettings() {
    val s = Graph.settings
    var navidromeUrl by remember(SettingsBackup.restored) { mutableStateOf(s.navidromeUrl) }
    var user by remember(SettingsBackup.restored) { mutableStateOf(s.user) }
    var pass by remember(SettingsBackup.restored) { mutableStateOf(s.pass) }
    var serviceUrl by remember(SettingsBackup.restored) { mutableStateOf(s.serviceUrl) }
    var serviceKey by remember(SettingsBackup.restored) { mutableStateOf(s.serviceKey) }
    var lastfmUser by remember(SettingsBackup.restored) { mutableStateOf(s.lastfmUser) }
    var lbUser by remember(SettingsBackup.restored) { mutableStateOf(s.listenbrainzUser) }
    var statuses by remember { mutableStateOf<List<Status>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        s.navidromeUrl = navidromeUrl
        s.user = user
        s.pass = pass
        s.serviceUrl = serviceUrl
        s.serviceKey = serviceKey
        s.lastfmUser = lastfmUser
        s.listenbrainzUser = lbUser
        Graph.version++
        SettingsBackup.tryRestore() // yeni kurulumda servis parolası girilince yedek gelir
    }

    fun check() {
        save()
        checking = true
        scope.launch {
            statuses = withContext(Dispatchers.IO) { checkAll() }
            checking = false
        }
    }

    val field = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)

    Column {
        SettingsCard {
        InfoTitle(
            "Navidrome",
            "Müzik arşivinin sunucusu. Ev dışından dinlemek için sunucunun VPN adresini yaz (ZeroTier, Tailscale…).",
        )
        OutlinedTextField(
            navidromeUrl, { navidromeUrl = it }, singleLine = true, modifier = field,
            label = { Text("Adres") }, placeholder = { Text(DEFAULT_NAVIDROME_URL) },
        )
        OutlinedTextField(user, { user = it }, singleLine = true, modifier = field, label = { Text("Kullanıcı adı") })
        OutlinedTextField(
            pass, { pass = it }, singleLine = true, modifier = field, label = { Text("Parola") },
            visualTransformation = PasswordVisualTransformation(),
        )
        }

        SettingsCard {
        InfoTitle(
            "Gurfy servisi",
            "Öneri, indirme ve radyoda şarkı tanıma bu servisle çalışır. Servis parolası: kurulumda YAML'da " +
                "API_KEY olarak belirlediğin parola; başkalarının sunucuna şarkı indirmesini ya da silmesini engeller.",
        )
        OutlinedTextField(
            serviceUrl, { serviceUrl = it }, singleLine = true, modifier = field,
            label = { Text("Adres") }, placeholder = { Text(DEFAULT_SERVICE_URL) },
        )
        OutlinedTextField(
            serviceKey, { serviceKey = it }, singleLine = true, modifier = field, label = { Text("Servis parolası") },
            visualTransformation = PasswordVisualTransformation(),
        )
        }

        SettingsCard {
        InfoTitle(
            "Last.fm",
            "Navidrome'un dinlemeleri gönderdiği hesap. Geçmişin okunur (istatistikler, öneriler, unuttukların); parola gerekmez.",
        )
        OutlinedTextField(
            lastfmUser, { lastfmUser = it }, singleLine = true, modifier = field,
            label = { Text("Last.fm kullanıcı adı") }, placeholder = { Text("Navidrome'un scrobble ettiği hesap") },
        )
        }

        SettingsCard {
        InfoTitle(
            "ListenBrainz (isteğe bağlı)",
            "Haftalık keşif listen Sana özel önerilere karışır. Navidrome'a girdiğin anahtar gerekmez; kullanıcı adı yeterli.",
        )
        OutlinedTextField(
            lbUser, { lbUser = it }, singleLine = true, modifier = field,
            label = { Text("ListenBrainz kullanıcı adı") }, placeholder = { Text("listenbrainz.org'daki adın") },
        )
        }

        Button(
            enabled = !checking,
            onClick = { check() },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text("Kaydet ve bağlantıları sına")
        }

        SettingsCard {
            SectionTitle("Bağlantı durumu")
            val list = statuses
            if (list == null) {
                EmptyNote("Durumu görmek için yukarıdaki düğmeye dokun.")
            } else {
                list.forEach { StatusRow(it) }
            }
        }
    }
}
