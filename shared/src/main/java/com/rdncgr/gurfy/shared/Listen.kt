package com.rdncgr.gurfy.shared

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

// Şarkı bulma topu: diğer uygulamaların üzerinde duran yuvarlak düğme. Dokununca telefonda çalan sesi
// (Instagram, YouTube…) 11 sn dinler, şarkıyı bulur ve indirmeyi önerir. Topa dokununca "Telefon sesi" ya da "Mikrofon" seçilir;
// telefon sesini Android yalnızca "ekran kaydı" izniyle verir, o yüzden onu seçince her seferinde izin sorulur.
// Top ekranın istenen yerine sürüklenir; sürüklerken altta çıkan X'in üzerine bırakılırsa kapanır.
// Gurfy tanımayı telefonda yapar, Gurfy Sync kaydı sunucuya gönderir (MusicService.recognizeAudio).

object ListenBubble {
    /** Top ekranda mı (Ana sayfadaki düğmenin rengi için). */
    var running by mutableStateOf(false)
        internal set

    fun start(ctx: Context) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, ListenService::class.java).setAction(ListenService.START))
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, ListenService::class.java).setAction(ListenService.STOP))
    }

    /** Uygulamanın kendi ekranı açık mı: açıkken top gizlenir. */
    var appShown = false
        private set
    internal var onAppShown: ((Boolean) -> Unit)? = null

    fun appVisible(shown: Boolean) {
        appShown = shown
        onAppShown?.invoke(shown)
    }

    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun micAllowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** WAV (PCM 16 bit, tek kanal) üretir. */
object Wav {
    fun encode(pcm: ShortArray, rate: Int): ByteArray {
        val data = pcm.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun i32(v: Int) { out.write(v and 255); out.write(v shr 8 and 255); out.write(v shr 16 and 255); out.write(v shr 24 and 255) }
        fun i16(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }
        out.write("RIFF".toByteArray()); i32(36 + data); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(data)
        val buf = ByteArray(data)
        for (i in pcm.indices) { buf[2 * i] = (pcm[i].toInt() and 255).toByte(); buf[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
        out.write(buf)
        return out.toByteArray()
    }
}

// ------------------------------------------------------------- Ana sayfa düğmesi

/**
 * Ana sayfadaki düğme: topu açar/kapatır. İlk açılışta "diğer uygulamaların üzerinde göster" ve
 * mikrofon izinleri istenir; ayarlardan dönünce kaldığı yerden devam eder.
 */
@Composable
fun ListenBubbleButton() {
    val ctx = LocalContext.current
    var explain by remember { mutableStateOf(false) }
    var waitingOverlay by remember { mutableStateOf(false) }
    var restricted by remember { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ListenBubble.micAllowed(ctx)) {
            ListenBubble.start(ctx)
            // Uygulama arka plana geçer, top hemen görünür (top uygulamanın kendi ekranında gizli)
            ctx.findActivity()?.moveTaskToBack(true)
        } else {
            Graph.toast("Mikrofon izni olmadan top sesi dinleyemez")
        }
    }
    fun askPerms() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(list.toTypedArray())
    }
    // Ayarlardan dönünce izin verildiyse devam et
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && waitingOverlay) {
                waitingOverlay = false
                // İzin verilmeden dönüldüyse büyük olasılıkla Android'in "kısıtlanmış ayar" kilidine takıldı
                if (ListenBubble.canOverlay(ctx)) askPerms() else restricted = true
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val on = ListenBubble.running
    IconButton(onClick = {
        when {
            on -> ListenBubble.stop(ctx)
            !ListenBubble.canOverlay(ctx) -> explain = true
            else -> askPerms()
        }
    }) {
        Icon(
            Icons.Filled.Hearing,
            contentDescription = if (on) "Şarkı bulma topunu kapat" else "Şarkı bulma topu",
            tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Şarkı bulma topu") },
            text = {
                Text(
                    "Diğer uygulamaların üzerinde yuvarlak bir top durur; istediğin yere sürükleyebilir, alttaki X'e " +
                        "bırakıp kapatabilirsin. Instagram ya da başka bir uygulamada müzik çalarken topa dokunursan sesi " +
                        "dinler, çalan şarkıyı bulur ve indirmeni sağlar.\n\n" +
                        "Bunun için açılacak ekranda uygulamayı bulup \"Diğer uygulamaların üzerinde göster\" iznini aç, " +
                        "sonra geri dön. Ardından mikrofon izni sorulacak.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    waitingOverlay = true
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("İzin ver") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Vazgeç") } },
        )
    }

    // Android 13+: mağaza dışından kurulan uygulamalarda bu izin kilitlidir; kilidi yalnızca kullanıcı açabilir
    if (restricted) {
        AlertDialog(
            onDismissRequest = { restricted = false },
            title = { Text("İzin verilemedi mi?") },
            text = {
                Text(
                    "Android, Play Store dışından kurulan uygulamalarda bu izni güvenlik için kilitler " +
                        "(\"kısıtlanmış ayar\"). Kilidi açmak için:\n\n" +
                        "1. Aşağıdaki \"Uygulama bilgisi\"ne dokun.\n" +
                        "2. Sağ üstteki üç noktaya (⋮) dokun, \"Kısıtlanmış ayarlara izin ver\"i seç ve ekran kilidini gir.\n" +
                        "3. Buraya dönüp kulak simgesine yeniden dokun; izin bu kez açılır.\n\n" +
                        "Üç noktada bu seçenek yoksa önce izni bir kez açmayı dene; Android engelledikten sonra görünür.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restricted = false
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Uygulama bilgisi") }
            },
            dismissButton = { TextButton(onClick = { restricted = false }) { Text("Kapat") } },
        )
    }
}

// ------------------------------------------------------- cihaz sesi izni (Android)

/** Görünmez ekran: Android'in "ses kaydına izin ver" penceresini açar, sonucu servise iletir. */
class ProjectionActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val i = Intent(this, ListenService::class.java)
        if (res.resultCode == RESULT_OK && res.data != null) {
            i.setAction(ListenService.CAPTURE).putExtra("code", res.resultCode).putExtra("data", res.data)
        } else {
            i.setAction(ListenService.CAPTURE_DENIED)
        }
        startService(i)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            // Android 14+: "tek uygulama / tüm ekran" seçimi sorulmasın, doğrudan tüm cihazın sesi
            ask.launch(
                if (Build.VERSION.SDK_INT >= 34) {
                    mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                } else mpm.createScreenCaptureIntent(),
            )
        } catch (e: Exception) {
            startService(Intent(this, ListenService::class.java).setAction(ListenService.CAPTURE_DENIED))
            finish()
        }
    }
}

// --------------------------------------------------------------------- servis

class ListenService : Service() {
    companion object {
        const val START = "basla"
        const val STOP = "kapat"
        const val CAPTURE = "cihaz-sesi"
        const val CAPTURE_MIC = "mikrofon"
        const val CAPTURE_DENIED = "cihaz-sesi-yok"
        private const val CHANNEL = "sarki-bulma"
        private const val NOTIF_ID = 7301
        private const val RATE = 16000
        private const val SECONDS = 11
        private const val SOURCE = "Şarkı bulma topu"
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var ball: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var card: View? = null
    private var pulse: ValueAnimator? = null
    private var job: Job? = null
    private val hideCard = Runnable { removeCard() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Graph.init(applicationContext)
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                if (!goForeground(false)) return START_NOT_STICKY
                if (!ListenBubble.canOverlay(this)) { stopAll(); return START_NOT_STICKY }
                showBall()
                ListenBubble.running = true
            }
            STOP -> stopAll()
            CAPTURE -> {
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra("data")
                val projection = if (data != null && goForeground(true)) {
                    try {
                        getSystemService(MediaProjectionManager::class.java)
                            .getMediaProjection(intent.getIntExtra("code", 0), data)
                    } catch (e: Exception) { null }
                } else null
                listen(projection)
            }
            CAPTURE_MIC -> listen(null)
            CAPTURE_DENIED -> showMessage("Telefon sesi için izin verilmedi. Topa dokunup yeniden dene ya da Mikrofon'u seç.")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeBall()
        removeCard()
        scope.cancel()
        ListenBubble.running = false
        super.onDestroy()
    }

    private fun stopAll() {
        removeBall()
        removeCard()
        job?.cancel()
        ListenBubble.running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------- bildirim

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Şarkı bulma topu", NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val close = PendingIntent.getService(
            this, 1, Intent(this, ListenService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Şarkı bulma topu açık")
            .setContentText("Müzik çalarken topa dokun, şarkıyı bulsun")
            .setContentIntent(open)
            .addAction(0, "Topu kapat", close)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** Mikrofon her zaman; cihaz sesi yalnızca kullanıcı o an izin verdiyse (Android 14 bunu şart koşar). */
    private fun goForeground(projection: Boolean): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (projection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        true
    } catch (e: Exception) {
        Graph.toast(errorText(e, "Şarkı bulma topu başlatılamadı"))
        false
    }

    // -------------------------------------------------------------------- top

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    private val accent get() = Graph.backend.accent.toArgb()

    private val prefs get() = Graph.settings.prefs

    @SuppressLint("ClickableViewAccessibility")
    private fun showBall() {
        if (ball != null) return
        val size = dp(60)
        val v = TextView(this).apply {
            text = "♪"
            setTextColor(0xFF101418.toInt())
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent); setStroke(dp(2), 0x33FFFFFF) }
            elevation = dp(6).toFloat()
            contentDescription = "Şarkıyı bul"
        }
        val dm = resources.displayMetrics
        // Son bırakıldığı yer hatırlanır; ilk açılışta sağ alt
        val p = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("topX", dm.widthPixels - size - dp(16)).coerceIn(0, dm.widthPixels - size)
            y = prefs.getInt("topY", dm.heightPixels - size - dp(140)).coerceIn(0, dm.heightPixels - size)
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (dx * dx + dy * dy) > slop * slop) {
                        moved = true
                        // Alttaki sonuç/hata kartı X'in üstünü örter; sürüklerken kaldırılır
                        if (job?.isActive != true) removeCard()
                        removeChoice()
                        showTrash()
                    }
                    if (moved) {
                        p.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        p.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        wm.updateViewLayout(v, p)
                        highlightTrash(overTrash(e.rawX, e.rawY))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val drop = moved && overTrash(e.rawX, e.rawY)
                    hideTrash()
                    when {
                        drop -> stopAll() // X'in üzerine bırakıldı: top kapanır
                        moved -> prefs.edit().putInt("topX", p.x).putInt("topY", p.y).apply()
                        e.actionMasked == MotionEvent.ACTION_UP -> onTap()
                    }
                }
            }
            true
        }
        v.visibility = if (ListenBubble.appShown) View.GONE else View.VISIBLE
        addTrash() // topun altında kalsın diye önce eklenir
        wm.addView(v, p)
        ball = v
        ballParams = p
        ListenBubble.onAppShown = { shown -> main.post { ball?.visibility = if (shown) View.GONE else View.VISIBLE } }
    }

    private fun removeBall() {
        ListenBubble.onAppShown = null
        pulse?.cancel(); pulse = null
        removeChoice()
        removeTrash()
        ball?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        ball = null
    }

    // ------------------------------------------- sürüklerken alttaki kapatma alanı

    private var trashBox: android.widget.FrameLayout? = null
    private var trash: TextView? = null
    private var trashHot = false

    /** Gizli olarak eklenir, sürükleme başlayınca görünür. Pencere büyütme payı bırakacak kadar geniştir. */
    private fun addTrash() {
        if (trashBox != null) return
        val size = dp(64)
        val t = TextView(this).apply {
            text = "✕"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC202329.toInt()); setStroke(dp(2), 0x88FFFFFF.toInt()) }
        }
        val box = android.widget.FrameLayout(this).apply {
            addView(t, android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            visibility = View.GONE
        }
        val p = overlayParams(dp(100), dp(100)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(40)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.addView(box, p) } catch (e: Exception) { return }
        trashBox = box
        trash = t
    }

    private fun showTrash() {
        val box = trashBox ?: return
        highlightTrash(false)
        card?.visibility = View.INVISIBLE // dinleme sürerken kart kalır ama X'i örtmez
        box.alpha = 0f
        box.visibility = View.VISIBLE
        box.animate().alpha(1f).setDuration(150).start()
    }

    private fun hideTrash() {
        trashBox?.visibility = View.GONE
        highlightTrash(false)
        card?.visibility = View.VISIBLE
    }

    private fun removeTrash() {
        trashBox?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        trashBox = null
        trash = null
    }

    /** Parmak X alanının üzerinde mi (alanın biraz dışı da sayılır, bırakması kolay olsun). */
    private fun overTrash(rawX: Float, rawY: Float): Boolean {
        val t = trash ?: return false
        if (trashBox?.visibility != View.VISIBLE || t.width == 0) return false
        val loc = IntArray(2)
        t.getLocationOnScreen(loc)
        val cx = loc[0] + t.width / 2f
        val cy = loc[1] + t.height / 2f
        val dx = rawX - cx; val dy = rawY - cy
        val r = dp(72).toFloat()
        return dx * dx + dy * dy < r * r
    }

    private fun highlightTrash(hot: Boolean) {
        val t = trash ?: return
        if (hot == trashHot) return
        trashHot = hot
        (t.background as? GradientDrawable)?.setColor(if (hot) 0xEED93F3F.toInt() else 0xCC202329.toInt())
        t.animate().scaleX(if (hot) 1.25f else 1f).scaleY(if (hot) 1.25f else 1f).setDuration(120).start()
        if (hot) t.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun setListening(on: Boolean) {
        val v = ball ?: return
        pulse?.cancel(); pulse = null
        v.scaleX = 1f; v.scaleY = 1f
        if (on) {
            pulse = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.18f).apply {
                duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { v.scaleY = v.scaleX }
                start()
            }
        }
    }

    /** Dokununca topun yanında iki seçenek açılır: telefonun kendi sesi ya da mikrofon. */
    private fun onTap() {
        if (job?.isActive == true) return
        if (choice != null) { removeChoice(); return }
        removeCard()
        showChoice()
    }

    /** Telefonun çaldığı ses. Android bunu yalnızca "ekran kaydı/paylaşımı" izniyle verir; pencere her seferinde çıkar. */
    private fun listenPhone() {
        if (Build.VERSION.SDK_INT < 29) {
            showMessage("Telefon sesini dinlemek için Android 10 ya da üstü gerekir. Mikrofonu kullanabilirsin.")
            return
        }
        try {
            startActivity(
                Intent(this, ProjectionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (e: Exception) {
            showMessage("Telefon sesi izni açılamadı. Mikrofonu deneyebilirsin.")
        }
    }

    // ---------------------------------------------------- Telefon / Mikrofon seçimi

    private var choice: View? = null
    private val hideChoice = Runnable { removeChoice() }

    private fun removeChoice() {
        main.removeCallbacks(hideChoice)
        choice?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        choice = null
    }

    private fun choiceRow(icon: String, label: String, onClick: () -> Unit) = TextView(this).apply {
        text = "$icon  $label"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(20), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x22FFFFFF) }
        setOnClickListener { removeChoice(); onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showChoice() {
        removeChoice()
        val b = ballParams ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xF2202329.toInt()) }
            elevation = dp(8).toFloat()
            val lp = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top } }
            addView(choiceRow("📱", "Telefon sesi") { listenPhone() }, lp(0))
            addView(choiceRow("🎤", "Mikrofon") { listen(null) }, lp(dp(6)))
            // Seçeneklerin dışına dokununca kapanır
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) removeChoice(); false }
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val dm = resources.displayMetrics
        val ballSize = dp(60)
        val w = box.measuredWidth
        val h = box.measuredHeight
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            // Top ekranın sağ yarısındaysa seçenekler soluna, sol yarısındaysa sağına açılır
            x = if (b.x + ballSize / 2 > dm.widthPixels / 2) b.x - w - dp(8) else b.x + ballSize + dp(8)
            x = x.coerceIn(0, maxOf(0, dm.widthPixels - w))
            y = (b.y + ballSize / 2 - h / 2).coerceIn(0, maxOf(0, dm.heightPixels - h))
        }
        try { wm.addView(box, p) } catch (e: Exception) { listen(null); return }
        choice = box
        main.postDelayed(hideChoice, 8_000)
    }

    // ------------------------------------------------------------- dinleme

    private fun listen(projection: MediaProjection?) {
        if (job?.isActive == true) { projection?.stop(); return }
        if (!ListenBubble.micAllowed(this)) {
            projection?.stop()
            showMessage("Mikrofon izni yok. Uygulamayı açıp Ana sayfadaki düğmeyle yeniden izin ver.")
            return
        }
        setListening(true)
        showMessage(if (projection != null) "Dinleniyor… (telefon sesi)" else "Dinleniyor… (mikrofon)", buttons = false)
        job = scope.launch {
            try {
                val (pcm, how) = withContext(Dispatchers.IO) { capture(projection) }
                projection?.stop()
                if (projection != null) goForeground(false)
                showMessage("Şarkı aranıyor…", buttons = false)
                val r = withContext(Dispatchers.IO) { Graph.service.recognizeAudio(pcm, SOURCE) }
                setListening(false)
                if (r.song != null) {
                    SongId.addHeard(r)
                    showResult(r, how)
                } else {
                    showMessage(r.message ?: "Şarkı tanınamadı", retry = true)
                }
            } catch (e: Exception) {
                projection?.stop()
                setListening(false)
                showMessage(errorText(e, "Şarkı bulunamadı"), retry = true)
            }
        }
    }

    /**
     * 11 sn ses: önce cihazın çaldığı ses; ilk 3 sn sessizse (uygulama sesini paylaşmıyor, ör. bazı
     * uygulamalar engeller) mikrofona geçilir.
     */
    @SuppressLint("MissingPermission")
    private fun capture(projection: MediaProjection?): Pair<ShortArray, String> {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val bufSize = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE)
        if (projection != null && Build.VERSION.SDK_INT >= 29) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val rec = try {
                AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(bufSize).setAudioPlaybackCaptureConfig(cfg).build()
            } catch (e: Exception) { null }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    rec.startRecording()
                    val first = read(rec, 3 * RATE)
                    if (rms(first) > 120) return (first + read(rec, (SECONDS - 3) * RATE)) to "cihaz sesinden"
                } finally {
                    try { rec.stop() } catch (e: Exception) { }
                    rec.release()
                }
            }
            main.post { showMessage("Uygulama sesini paylaşmıyor, mikrofondan dinleniyor…", buttons = false) }
        }
        val mic = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (mic.state != AudioRecord.STATE_INITIALIZED) { mic.release(); throw IllegalStateException("Mikrofon açılamadı") }
        try {
            mic.startRecording()
            val pcm = read(mic, SECONDS * RATE)
            if (rms(pcm) < 30) throw IllegalStateException("Ses duyulmadı. Müzik telefonun hoparlöründen çalıyor mu, ses açık mı?")
            return pcm to "mikrofondan"
        } finally {
            try { mic.stop() } catch (e: Exception) { }
            mic.release()
        }
    }

    private fun read(rec: AudioRecord, n: Int): ShortArray {
        val out = ShortArray(n)
        var at = 0
        while (at < n && job?.isCancelled != true) {
            val got = rec.read(out, at, minOf(4096, n - at))
            if (got <= 0) break
            at += got
        }
        return if (at == n) out else out.copyOf(at)
    }

    private fun rms(a: ShortArray): Double {
        if (a.isEmpty()) return 0.0
        var s = 0.0
        for (x in a) s += x.toDouble() * x
        return sqrt(s / a.size)
    }

    // --------------------------------------------------------------- kart

    private fun removeCard() {
        main.removeCallbacks(hideCard)
        card?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        card = null
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
    }

    private fun action(label: String, enabled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (enabled) accent else 0x88FFFFFF.toInt())
        setPadding(dp(14), dp(10), dp(14), dp(10))
        if (enabled) setOnClickListener { onClick() }
    }

    private fun showCard(content: LinearLayout, autoHideMs: Long?) {
        removeCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(8), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xF2202329.toInt()) }
            elevation = dp(8).toFloat()
            addView(content)
        }
        val dm = resources.displayMetrics
        val p = overlayParams(dm.widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Topun üstünde dursun
            val b = ballParams
            y = if (b != null) (dm.heightPixels - b.y + dp(12)).coerceIn(dp(24), dm.heightPixels / 2) else dp(160)
        }
        wm.addView(box, p)
        card = box
        if (autoHideMs != null) main.postDelayed(hideCard, autoHideMs)
    }

    private fun showMessage(msg: String, buttons: Boolean = true, retry: Boolean = false) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(msg, 15f, 0xFFFFFFFF.toInt()).apply { maxLines = 5; setPadding(0, 0, dp(8), dp(4)) })
            if (buttons) addView(LinearLayout(this@ListenService).apply {
                gravity = Gravity.END
                if (retry) addView(action("Tekrar dene") { onTap() })
                addView(action("Kapat") { removeCard() })
            })
        }
        showCard(col, if (buttons) 20_000 else null)
    }

    private fun showResult(r: Recognition, how: String) {
        val s = r.song ?: return
        val white = 0xFFFFFFFF.toInt()
        val grey = 0xFFB8BCC4.toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@ListenService).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF33363D.toInt()) }
                clipToOutline = true
                if (r.cover != null) load(r.cover)
            }, LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(LinearLayout(this@ListenService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(text(s.title, 17f, white, bold = true))
                addView(text(s.artist, 14f, grey))
                addView(text("Bulundu ($how)", 12f, grey))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val key = SongId.keyOf(s)
        val queued = SongId.queued[key] == true
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.END
            when {
                s.inLibrary -> addView(action("Arşivde var", enabled = false) {})
                queued -> addView(action("Sırada", enabled = false) {})
                else -> addView(action("İndir") {
                    SongId.download(listOf(s))
                    Graph.toast("İndiriliyor: ${s.artist} – ${s.title}")
                    removeCard()
                })
            }
            addView(action("Kapat") { removeCard() })
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(buttons)
        }
        showCard(col, 30_000)
    }
}
