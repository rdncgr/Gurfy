package com.rdncgr.gurfy.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** Uygulamaların yayınlandığı GitHub deposu (sürümler). */
const val GITHUB_REPO = "rdncgr/Gurfy"

/**
 * Güncelleme denetimi. Uygulamalar Play Store'dan gelmediği için kendiliğinden güncellenmez:
 * açılışta GitHub'daki en yeni sürüme bakılır (Sync "gurfy-sync-v1.2", Gurfy "gurfy-v1.2" etiketleri) ve
 * kurulu sürümün adıyla (versionName, surum.txt) karşılaştırılır.
 * YouTube değişince düzeltme yeni sürümle geldiği için bu uyarı önemli.
 */
object Updates {
    class Release(val version: String, val name: String, val notes: String, val apkUrl: String)

    /** Gösterilecek yeni sürüm (diyalog). */
    var available by mutableStateOf<Release?>(null)
    var checking by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    private const val EVERY = 12 * 3600 * 1000L
    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    private val prefs get() = Graph.appContext.getSharedPreferences("guncelleme", Context.MODE_PRIVATE)

    /** "1.10.2" > "1.9": sayı sayı karşılaştırır. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun versionName(ctx: Context = Graph.appContext): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Açılışta: en çok 12 saatte bir, sessizce. "Sonra" denilen sürüm bir daha sorulmaz. */
    fun checkOnStart() {
        if (System.currentTimeMillis() - prefs.getLong("checkedAt", 0) < EVERY) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (checking) return
        checking = true
        Graph.scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { latest() }
                prefs.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
                val current = versionName()
                val isNew = r != null && newer(r.version, current)
                status = when {
                    r == null -> "Henüz yayınlanmış sürüm yok"
                    isNew -> "Yeni sürüm var: ${r!!.name}"
                    else -> "Güncel"
                }
                val skipped = prefs.getString("skippedVersion", "") ?: ""
                if (isNew && (manual || skipped.isEmpty() || newer(r!!.version, skipped))) available = r
            } catch (e: Exception) {
                status = errorText(e, "GitHub'a ulaşılamadı")
            } finally {
                checking = false
            }
        }
    }

    fun skip(r: Release) {
        prefs.edit().putString("skippedVersion", r.version).apply()
        available = null
    }

    private fun latest(): Release? {
        val prefix = Graph.backend.releaseTag
        val req = Request.Builder().url("https://api.github.com/repos/$GITHUB_REPO/releases?per_page=40")
            .header("Accept", "application/vnd.github+json").build()
        val arr = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("GitHub HTTP ${r.code}")
            JSONArray(r.body!!.string())
        }
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
            val tag = o.optString("tag_name")
            if (!tag.startsWith(prefix)) continue
            val v = tag.removePrefix(prefix)
            if (v.isEmpty() || !v[0].isDigit()) continue
            if (best != null && !newer(v, best.version)) continue
            val assets = o.optJSONArray("assets") ?: JSONArray()
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            best = Release(
                v, o.optString("name").ifBlank { tag }, if (o.isNull("body")) "" else o.optString("body").take(1500),
                apk.optString("browser_download_url"),
            )
        }
        return best
    }
}

/** Tarayıcıda açar (APK indirme). */
fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Graph.toast("Tarayıcı açılamadı")
    }
}

/** Açılışta denetler; yeni sürüm varsa sorar. GurfyRoot'ta bir kez çağrılır. */
@Composable
fun UpdateDialog() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Updates.checkOnStart() }
    val r = Updates.available ?: return
    AlertDialog(
        onDismissRequest = { Updates.available = null },
        title = { Text("Yeni sürüm var") },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${r.name} yayınlandı. Şu an ${Updates.versionName(ctx)} kurulu.")
                Text(
                    "İndirdikten sonra dosyayı aç; yeni sürüm eskisinin üzerine kurulur, şarkıların ve ayarların silinmez.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (r.notes.isNotBlank()) {
                    Text(r.notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = { Updates.available = null; openUrl(ctx, r.apkUrl) }) { Text("İndir") }
        },
        dismissButton = {
            TextButton(onClick = { Updates.skip(r) }) { Text("Sonra") }
        },
    )
}

/** Ayarlar > Bağlantılar'ın altındaki sürüm kartı (iki uygulamada da). */
@Composable
fun UpdateSettings() {
    val ctx = LocalContext.current
    SectionTitle("Sürüm")
    Text(
        "${ctx.applicationInfo.loadLabel(ctx.packageManager)} ${Updates.versionName(ctx)}" +
            (Updates.status.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !Updates.checking, onClick = { Updates.check(manual = true) }) {
            if (Updates.checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Güncellemeleri denetle")
        }
    }
}
