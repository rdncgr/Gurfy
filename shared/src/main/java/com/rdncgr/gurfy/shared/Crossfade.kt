package com.rdncgr.gurfy.shared

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Şarkılar arası geçiş (crossfade): şarkının son N saniyesinde sıradaki şarkı başlar,
 * biten şarkı kısılarak çalmaya devam eder, yeni şarkı açılarak gelir.
 *
 * Ana çalar sıradaki şarkıya erkenden geçer; biten şarkının kalan kısmını ikinci, sessiz
 * kalan bir yardımcı çalar seslendirir. Yardımcı çalar şarkının sonuna birkaç saniye kala
 * hazırlanır ki geçişte sessizlik olmasın. Radyoda, "aynı şarkıyı tekrarla"da ve kısa
 * şarkılarda devreye girmez.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Crossfader(private val ctx: Context, private val main: ExoPlayer) {
    private val handler = Handler(Looper.getMainLooper())
    private var helper: ExoPlayer? = null
    private var preparedFor: Int = -1 // yardımcı çaların hazırlandığı şarkının sıradaki yeri
    private var fading = false
    private var fadeStart = 0L
    private var fadeMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, if (fading) 40L else 250L)
        }
    }

    fun start() {
        handler.post(tick)
        main.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Kullanıcı başka şarkıya geçtiyse ya da sıra değiştiyse yarım kalan geçişi bırak
                if (!fading) dropHelper()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && main.playbackState != Player.STATE_BUFFERING) finish()
            }
        })
    }

    fun release() {
        handler.removeCallbacks(tick)
        finish()
    }

    private fun seconds(): Int = Graph.settings.crossfadeSec

    private fun eligible(sec: Int): Boolean {
        val d = main.duration
        return sec > 0 && main.isPlaying && !main.isCurrentMediaItemLive &&
            d != C.TIME_UNSET && d > sec * 3_000L &&
            main.repeatMode != Player.REPEAT_MODE_ONE && main.hasNextMediaItem()
    }

    private fun step() {
        if (fading) {
            ramp()
            return
        }
        val sec = seconds()
        if (!eligible(sec)) {
            if (helper != null && preparedFor != main.currentMediaItemIndex) dropHelper()
            return
        }
        val remaining = main.duration - main.currentPosition
        val index = main.currentMediaItemIndex
        // Son N+4 saniyede yardımcı çaları hazırla (ağdan yükleme payı)
        if (remaining <= sec * 1_000L + 4_000L && preparedFor != index) prepareHelper(index)
        if (remaining <= sec * 1_000L && remaining > 400 && preparedFor == index) begin(sec)
    }

    private fun prepareHelper(index: Int) {
        dropHelper()
        val item = main.currentMediaItem ?: return
        val h = ExoPlayer.Builder(ctx)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false, // ses odağını ana çalar yönetir
            )
            .build()
        h.volume = 0f
        h.setMediaItem(item, main.currentPosition + 2_000L)
        h.prepare()
        helper = h
        preparedFor = index
    }

    private fun begin(sec: Int) {
        val h = helper ?: return
        if (h.playbackState != Player.STATE_READY) return // henüz hazır değil: bir sonraki adımda dene
        h.seekTo(main.currentPosition)
        h.volume = main.volume
        h.play()
        fading = true
        fadeMs = sec * 1_000L
        fadeStart = android.os.SystemClock.elapsedRealtime()
        main.volume = 0f
        main.seekToNextMediaItem()
    }

    private fun ramp() {
        val t = ((android.os.SystemClock.elapsedRealtime() - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
        // Eşit güçlü eğri: geçiş ortasında ses düşmesin
        main.volume = kotlin.math.sin(t * Math.PI / 2).toFloat()
        helper?.volume = kotlin.math.cos(t * Math.PI / 2).toFloat()
        if (t >= 1f) finish()
    }

    private fun finish() {
        if (fading) main.volume = 1f
        fading = false
        dropHelper()
    }

    private fun dropHelper() {
        helper?.let {
            it.stop()
            it.release()
        }
        helper = null
        preparedFor = -1
        if (!fading) main.volume = 1f
    }
}
