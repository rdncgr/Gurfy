package com.rdncgr.gurfysync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rdncgr.gurfy.shared.Graph
import com.rdncgr.gurfy.shared.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Evde / dışarıda adres seçimi. Ayarlardaki Navidrome ve servis adresleri "dışarıdaki" adreslerdir
 * (ZimaOS'un ZeroTier adresi gibi). [homeHost] girilmişse ağ her değiştiğinde o adrese kısa bir yoklama
 * yapılır: cevap gelirse iki adresin de sunucu kısmı ev adresiyle değiştirilir (port ve yol aynı kalır).
 * Wi-Fi adına bakılmaz, konum izni gerekmez.
 */
object NetworkRoute {
    private const val KEY = "homeHost"
    private const val PROBE_MS = 1500

    /** Ev ağındaki sunucu adresi (ör. 192.168.1.101); boşsa her yerde ayarlardaki adresler kullanılır. */
    var homeHost: String
        get() = Graph.settings.prefs.getString(KEY, "") ?: ""
        set(v) {
            val clean = v.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
            Graph.settings.prefs.edit().putString(KEY, clean).apply()
            check()
        }

    /** Şu an ev adresi mi kullanılıyor (arayüz için). */
    var atHome by mutableStateOf(false)
        private set

    /** İlk yoklama bitti mi. */
    var checked by mutableStateOf(false)
        private set

    @Volatile private var home = false
    private var job: Job? = null
    private var appCtx: Context? = null

    fun navidrome(s: Settings): String = swap(s.navidromeUrl)
    fun service(s: Settings): String = swap(s.serviceUrl)

    private fun swap(url: String): String {
        val h = homeHost
        if (!home || h.isBlank() || url.isBlank()) return url
        return try {
            val u = URI(url)
            URI(u.scheme, u.userInfo, h, u.port, u.path, u.query, u.fragment).toString().trimEnd('/')
        } catch (e: Exception) { url }
    }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        // Yoklama bitene kadar tahmin: Wi-Fi'deysek büyük olasılıkla evdeyiz
        home = homeHost.isNotBlank() && onWifi(cm)
        atHome = home
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = check()
                override fun onLost(network: Network) = check()
            })
        } catch (e: Exception) { /* bazı cihazlarda kısıtlı: açılıştaki yoklama yeter */ }
        check()
    }

    private fun onWifi(cm: ConnectivityManager): Boolean = try {
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) { false }

    /** Ağ değişti ya da ayar değişti: kısa bir beklemeden sonra ev adresini yokla. */
    fun check() {
        job?.cancel()
        job = Graph.scope.launch {
            delay(700) // ağ değişirken art arda gelen olayları birleştir
            val ok = withContext(Dispatchers.IO) { probe() }
            apply(ok)
        }
    }

    /** "Bağlantıları sına" için: hemen yoklar ve sonucu uygular (IO iş parçacığından çağrılır). */
    fun probeNow(): Boolean {
        val ok = probe()
        Graph.scope.launch { apply(ok) }
        home = ok
        return ok
    }

    private fun apply(ok: Boolean) {
        val changed = ok != home || ok != atHome
        home = ok
        atHome = ok
        checked = true
        if (changed) {
            // Sıradaki şarkıların adresleri yeni ağa göre yenilensin; çalan şarkı kesilmez
            Graph.player.refreshUrls()
            Graph.version++
        }
    }

    private fun probe(): Boolean {
        val h = homeHost
        if (h.isBlank()) return false
        val port = try {
            URI(Graph.settings.navidromeUrl).port.takeIf { it > 0 } ?: 4533
        } catch (e: Exception) { 4533 }
        return try {
            Socket().use { it.connect(InetSocketAddress(h, port), PROBE_MS) }
            true
        } catch (e: Exception) { false }
    }

    /** Kullanılan adreslerin açıklaması (Bağlantı durumu için). */
    fun describe(): String {
        val h = homeHost
        if (h.isBlank()) return "Ev adresi girilmemiş; her yerde ayarlardaki adresler kullanılıyor"
        return if (home) "Evdesin: $h kullanılıyor"
        else "Dışarıdasın: " + (try { URI(Graph.settings.navidromeUrl).host } catch (e: Exception) { null } ?: "dış adres") + " kullanılıyor"
    }
}
