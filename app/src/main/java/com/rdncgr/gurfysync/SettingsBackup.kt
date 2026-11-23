package com.rdncgr.gurfysync

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rdncgr.gurfy.shared.Graph

/**
 * Ayarların sunucuda yedeği. Uygulama silinip yeniden kurulunca yalnızca servis parolasını
 * girmek yeter: kalan bütün ayarlar (Navidrome bilgileri, Last.fm, ana sayfa düzeni,
 * çalma tercihleri, arama geçmişi…) Gurfy servisinden geri gelir.
 *
 * Her ayar değişikliğinden birkaç saniye sonra yedek sunucuya gönderilir. Yeni kurulan
 * uygulama önce sunucudaki yedeğe bakar; yedeği almadan kendi boş ayarlarını göndermez.
 */
object SettingsBackup {
    /** Yedeklenen ayarlar (önbellek ve cihaza özel durumlar hariç). */
    val keys = setOf(
        "navidromeUrl", "user", "pass", "serviceUrl", "serviceKey", "lastfmUser", "listenbrainzUser",
        "autoOffline", "autoOfflineCount", "wifiOnly", "offlineExcluded", "crossfadeSec", "playMode",
        "repeatMode", "radioShuffle", "radioPool", "lastRadio", "leftHand", "homeOrder", "homeHidden", "searchHistory", "heard", "skips",
    )

    /** Geri yükleme olunca artar; ayar ekranları alanlarını yeniden okusun. */
    var restored by mutableIntStateOf(0)
        private set

    private var upload: Job? = null
    private var restoring = false

    // Dinleyici zayıf referansla tutulduğu için alan olarak saklanır
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in keys && !restoring) scheduleUpload()
    }

    fun start(prefs: SharedPreferences) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        tryRestore()
    }

    private fun scheduleUpload() {
        val s = Graph.settings
        if (!s.backupChecked) return // önce sunucudaki yedeğe bakılmalı
        upload?.cancel()
        upload = Graph.scope.launch {
            delay(4_000)
            if (!s.serviceConfigured || s.serviceKey.isBlank()) return@launch
            try {
                withContext(Dispatchers.IO) { Graph.service.saveSettings(s.export(keys)) }
            } catch (e: Exception) {
                // sunucuya ulaşılamadı: bir sonraki değişiklikte yeniden denenir
            }
        }
    }

    /** Yeni kurulumda sunucudaki yedeği uygular; yalnızca bir kez (başarıyla) bakılır. */
    fun tryRestore() {
        val s = Graph.settings
        if (s.backupChecked || !s.serviceConfigured || s.serviceKey.isBlank()) return
        Graph.scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { Graph.service.loadSettings() }
                // Uygulama zaten kurulmuşsa (Navidrome kullanıcısı girilmiş) yedeğin üzerine yazılmaz
                if (data != null && s.user.isBlank()) {
                    restoring = true
                    try { s.import(data, keys) } finally { restoring = false }
                    restored++
                    Graph.version++
                    Graph.toast("Ayarların sunucudaki yedekten geri yüklendi")
                }
                s.backupChecked = true
                scheduleUpload()
            } catch (e: Exception) {
                // sunucu eski sürümse ya da ulaşılamıyorsa sonra yeniden denenir
            }
        }
    }
}
