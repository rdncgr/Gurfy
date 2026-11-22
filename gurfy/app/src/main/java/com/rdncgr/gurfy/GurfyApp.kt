package com.rdncgr.gurfy

import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.room.Room
import com.rdncgr.gurfy.shared.Backend
import com.rdncgr.gurfy.shared.EmptyNote
import com.rdncgr.gurfy.shared.Graph
import com.rdncgr.gurfy.shared.Guide
import com.rdncgr.gurfy.shared.InfoTitle
import com.rdncgr.gurfy.shared.Library
import com.rdncgr.gurfy.shared.MusicService
import com.rdncgr.gurfy.shared.PhoneMusic
import com.rdncgr.gurfy.shared.SectionTitle
import com.rdncgr.gurfy.shared.SettingsCard
import com.rdncgr.gurfy.shared.Status
import com.rdncgr.gurfy.shared.StatusRow
import com.rdncgr.gurfy.shared.errorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

/** Gurfy: sunucu yok; kütüphane, indirme, öneri ve şarkı tanıma telefonda. */
class GurfyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.backendFactory = { ctx, _ -> GurfyBackend(ctx) }
        // Arka plana geçince kullanıcı verisinin JSON yedeği (Documents/Gurfy)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStopped(a: android.app.Activity) { if (Local.ready) Backup.saveSoon() }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
}

/** Gurfy'nin telefondaki "sunucu" parçaları. */
object Local {
    lateinit var http: OkHttpClient
        private set
    lateinit var db: GurfyDb
        private set
    lateinit var library: LocalLibrary
        private set
    lateinit var service: LocalService
        private set
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ready get() = ::db.isInitialized

    @Synchronized
    fun init(ctx: Context) {
        if (::db.isInitialized) return
        val app = ctx.applicationContext
        http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        db = Room.databaseBuilder(app, GurfyDb::class.java, "gurfy.db").build()
        library = LocalLibrary(app, db)
        service = LocalService(app)
        NewPipe.init(NewPipeHttp(http), Localization("tr", "TR"), ContentCountry("TR"))
    }
}

class GurfyBackend(ctx: Context) : Backend {
    init { Local.init(ctx) }

    override val library: Library get() = Local.library
    override val service: MusicService get() = Local.service
    override val libraryReady: Boolean = true
    override val serviceReady: Boolean = true
    override val hasRemoteLibrary: Boolean = false
    override val accent: Color = Color(0xFF4BC3B4)
    override val releaseTag = "gurfy-v"

    override fun start(ctx: Context) {
        Local.io.launch {
            Local.library.sync() // Müzik/Gurfy klasörüyle uzlaştır
            Backup.afterStart() // yeniden kurulduysa yedek sorulur
            if (Local.db.jobs().activeCount() > 0) Downloads.start() // yarım kalan indirmeler
        }
    }

    // Çalan sesin kopyası: "Bu şarkı ne?" yayına yeniden bağlanmadan son 12 sn'yi kullanır
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun audioProcessors(): List<AudioProcessor> = listOf(TeeAudioProcessor(AudioTap))

    @Composable
    override fun ConnectionSettings() = GurfyConnectionSettings()

    @Composable
    override fun Overlay() = RestoreDialog()
}

// --------------------------------------------------------- ayarlar: bağlantılar

private fun checkAll(): List<Status> {
    val out = mutableListOf<Status>()
    out += try {
        val n = YouTube.search("Tarkan").size
        Status("YouTube", n > 0, if (n > 0) "Arama ve indirme çalışıyor" else "Arama sonuç vermedi")
    } catch (e: Exception) {
        Status("YouTube", false, errorText(e, "Ulaşılamadı"))
    }
    out += Status("Müzik klasörü", true, "Music/Gurfy · ${Local.library.all().size} şarkı")
    // Eşleşmeyecek bir imza gönderilir: yanıtın biçimi doğruysa servis çalışıyor demektir
    out += try {
        val noise = ShortArray(6 * ShazamSignature.RATE) { ((it * 7919L) % 2001 - 1000).toShort() }
        ShazamApi.recognize(ShazamSignature.create(noise), Local.http)
        Status("Şarkı tanıma (Shazam)", true, "Servis yanıt veriyor")
    } catch (e: ShazamChanged) {
        Status("Şarkı tanıma (Shazam)", false, "${e.message}. Yayın bilgisinden tanıma çalışır.")
    } catch (e: Exception) {
        Status("Şarkı tanıma (Shazam)", false, errorText(e, "Ulaşılamadı"))
    }
    out += Status("Yedek", if (Backup.lastOk() != null) true else null, Backup.describe())
    if (!Lfm.ready) {
        out += Status("Last.fm", null, "Anahtar girilmemiş: benzer şarkılar, öneriler, türler ve listeler kapalı")
    } else {
        out += try {
            val ok = Lfm.call("chart.getTopTracks", "limit" to 1).has("tracks")
            Status("Last.fm", ok, if (ok) "Anahtar geçerli; öneriler ve türler açık" else "Anahtar geçersiz")
        } catch (e: Exception) {
            Status("Last.fm", false, errorText(e, "Ulaşılamadı"))
        }
        val user = Graph.settings.lastfmUser
        if (user.isNotBlank()) out += try {
            val u = Lfm.userInfo(user)
            Status("Last.fm hesabı", true, "${u.optString("name")} · ${u.optInt("playcount")} dinleme")
        } catch (e: Exception) {
            Status("Last.fm hesabı", false, errorText(e, "Kullanıcı bulunamadı"))
        }
        out += Status(
            "Dinlemeleri gönderme", if (Lfm.session.isNotBlank()) true else null,
            if (Lfm.session.isNotBlank()) "Dinlediğin şarkılar Last.fm hesabına işleniyor"
            else "Kapalı: gizli anahtarı girip Last.fm'e bağlan",
        )
    }
    val lb = Graph.settings.listenbrainzUser
    if (lb.isNotBlank()) out += try {
        val n = Lfm.lbListenCount(lb).optInt("listens")
        Status("ListenBrainz hesabı", true, if (n > 0) "$lb · $n dinleme" else "$lb · henüz dinleme yok")
    } catch (e: Exception) {
        Status("ListenBrainz hesabı", false, errorText(e, "Kullanıcı bulunamadı"))
    }
    return out
}

@Composable
private fun GurfyConnectionSettings() {
    val s = Graph.settings
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Lfm.apiKey) }
    var secret by remember { mutableStateOf(Lfm.secret) }
    var lfmUser by remember { mutableStateOf(s.lastfmUser) }
    var password by remember { mutableStateOf("") }
    var lbUser by remember { mutableStateOf(s.listenbrainzUser) }
    var connected by remember { mutableStateOf(Lfm.session.isNotBlank()) }
    var busy by remember { mutableStateOf(false) }
    var statuses by remember { mutableStateOf<List<Status>?>(null) }
    var granted by remember { mutableStateOf(PhoneMusic.allowed(ctx)) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) Local.io.launch { Local.library.sync() }
    }

    fun save() {
        Lfm.apiKey = key
        Lfm.secret = secret
        s.lastfmUser = lfmUser
        s.listenbrainzUser = lbUser
        Graph.version++
    }

    val field = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)

    Column {
        SettingsCard {
            InfoTitle(
                "Last.fm",
                "Benzer şarkılar, sana özel öneriler, türler, listeler ve istatistikler Last.fm ile çalışır. " +
                    "Anahtar ücretsizdir; nasıl alınacağı aşağıda adım adım anlatılıyor. Dinlemeleri hesabına göndermek için " +
                    "gizli anahtarı da gir ve bir kez bağlan; parolan saklanmaz.",
            )
            Guide(
                "Last.fm anahtarı nasıl alınır?",
                listOf(
                    "Last.fm hesabın yoksa [last.fm/join](https://www.last.fm/join) sayfasından ücretsiz hesap aç " +
                        "(e-posta adresine gelen onay bağlantısına dokunmayı unutma). Hesabın varsa " +
                        "[last.fm/login](https://www.last.fm/login) sayfasından giriş yap.",
                    "Giriş yaptıktan sonra [last.fm/api/account/create](https://www.last.fm/api/account/create) sayfasını aç. " +
                        "Bu sayfa bir form gösterir; İngilizcedir ama doldurması kolaydır.",
                    "Formu şöyle doldur: **Contact email** e-posta adresin, **Application name** \"Gurfy\", " +
                        "**Application description** \"Kişisel müzik uygulaması\". **Callback URL** ve " +
                        "**Application homepage** boş kalabilir. Sonra **Submit** düğmesine dokun.",
                    "Açılan sayfada iki uzun kod görürsün: **API key** ve **Shared secret**. API key'i uzun basıp kopyala, " +
                        "aşağıdaki \"API anahtarı\" kutusuna yapıştır. Shared secret'ı da \"Gizli anahtar\" kutusuna yapıştır.",
                    "\"Last.fm kullanıcı adı\" kutusuna Last.fm'deki kullanıcı adını yaz (e-posta değil; profil sayfanda, " +
                        "last.fm/user/... adresinin sonunda yazan ad).",
                    "Dinlediğin şarkıların Last.fm hesabına işlenmesini istiyorsan parolanı yazıp **Last.fm'e bağlan**'a dokun. " +
                        "Parola yalnızca bir kez kullanılır, telefonda saklanmaz. Öneriler bu adım olmadan da çalışır.",
                    "En alttaki **Kaydet ve sına**'ya dokun. Durum bölümünde Last.fm satırında yeşil işaret görüyorsan tamamdır.",
                ),
                footer = "Kodları kaybedersen [last.fm/api/accounts](https://www.last.fm/api/accounts) sayfasından yeniden görebilirsin. " +
                    "Öneriler dinleme geçmişinden çıkar; hesabın yeniyse birkaç gün dinledikten sonra dolmaya başlar.",
            )
            OutlinedTextField(key, { key = it }, singleLine = true, modifier = field, label = { Text("API anahtarı") })
            OutlinedTextField(
                secret, { secret = it }, singleLine = true, modifier = field, label = { Text("Gizli anahtar (shared secret)") },
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(lfmUser, { lfmUser = it }, singleLine = true, modifier = field, label = { Text("Last.fm kullanıcı adı") })
            if (!connected) {
                OutlinedTextField(
                    password, { password = it }, singleLine = true, modifier = field, label = { Text("Last.fm parolası (yalnızca bağlanırken)") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (connected) {
                    Text("Bağlı: dinlemeler gönderiliyor", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = { Lfm.session = ""; connected = false }) { Text("Bağlantıyı kes") }
                } else {
                    OutlinedButton(
                        enabled = !busy && key.isNotBlank() && secret.isNotBlank() && lfmUser.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            save()
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { Lfm.login(lfmUser, password) }
                                    connected = true
                                    password = ""
                                    Graph.toast("Last.fm'e bağlanıldı")
                                } catch (e: Exception) {
                                    Graph.toast(errorText(e, "Last.fm'e bağlanılamadı"))
                                }
                                busy = false
                            }
                        },
                    ) { Text("Last.fm'e bağlan") }
                }
            }
        }

        SettingsCard {
            InfoTitle(
                "ListenBrainz (isteğe bağlı)",
                "Haftalık keşif listen Sana özel önerilere karışır; anahtar gerekmez, kullanıcı adı yeterli.",
            )
            Guide(
                "ListenBrainz hesabı nasıl açılır?",
                listOf(
                    "[listenbrainz.org](https://listenbrainz.org) sitesini aç ve sağ üstteki **Sign in**'e dokun. Hesabın yoksa " +
                        "açılan MetaBrainz sayfasında **Create account** ile ücretsiz hesap aç.",
                    "ListenBrainz keşif listesini senin dinlediklerinden hazırlar. Last.fm kullanıyorsan geçmişini aktarmak için " +
                        "[Music services](https://listenbrainz.org/settings/music-services/details/) sayfasında Last.fm'i bağla.",
                    "Aşağıdaki kutuya ListenBrainz kullanıcı adını yaz ve **Kaydet ve sına**'ya dokun.",
                ),
                footer = "Haftalık keşif listesi yeterince dinleme biriktikten sonra, genellikle pazartesileri hazırlanır.",
            )
            OutlinedTextField(
                lbUser, { lbUser = it }, singleLine = true, modifier = field,
                label = { Text("ListenBrainz kullanıcı adı") }, placeholder = { Text("listenbrainz.org'daki adın") },
            )
        }

        SettingsCard {
            InfoTitle(
                "Müzik klasörü",
                "İndirilen şarkılar telefonun Music/Gurfy klasörüne kaydedilir; uygulama silinse de kalır. " +
                    "Yeniden kurduğunda ya da bilgisayardan şarkı kopyaladığında kütüphanenin görmesi için izin ver.",
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!granted) {
                    Button(onClick = { ask.launch(PhoneMusic.permission) }) { Text("Müzik klasörüne izin ver") }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(enabled = !scanning, onClick = {
                    scanning = true
                    scope.launch {
                        withContext(Dispatchers.IO) { Local.library.sync() }
                        scanning = false
                        Graph.toast("Kütüphanede ${withContext(Dispatchers.IO) { Local.library.all().size }} şarkı")
                    }
                }) { Text("Yeniden tara") }
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                save()
                busy = true
                scope.launch {
                    statuses = withContext(Dispatchers.IO) { checkAll() }
                    busy = false
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text("Kaydet ve sına")
        }

        SettingsCard {
            SectionTitle("Durum")
            val list = statuses
            if (list == null) EmptyNote("Durumu görmek için yukarıdaki düğmeye dokun.") else list.forEach { StatusRow(it) }
        }
        SettingsCard { BackupSettings() }
    }
}
