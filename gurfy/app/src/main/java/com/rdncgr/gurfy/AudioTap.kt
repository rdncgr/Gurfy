package com.rdncgr.gurfy

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Çalan sesin son 20 saniyesini 16 kHz tek kanal olarak tutar (oynatıcının çıkışından kopya).
 * "Bu şarkı ne?" bu kaydı kullanır: yayına ikinci kez bağlanmaya ya da mikrofona gerek kalmaz.
 * Gurfy Sync'te sunucu yayını ffmpeg ile ayrıca kaydediyordu.
 */
@OptIn(UnstableApi::class)
object AudioTap : TeeAudioProcessor.AudioBufferSink {
    private const val RATE = ShazamSignature.RATE
    private val ring = ShortArray(RATE * 20)
    private var pos = 0
    private var filled = 0

    private var inRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var acc = 0.0
    private var accCount = 0
    private var phase = 0.0

    @Synchronized
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        inRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        clear()
    }

    @Synchronized
    fun clear() {
        pos = 0
        filled = 0
        acc = 0.0
        accCount = 0
        phase = 0.0
    }

    @Synchronized
    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT || inRate <= 0 || channels <= 0) return
        val b = buffer.order(ByteOrder.nativeOrder())
        val step = RATE.toDouble() / inRate // giriş karesi başına çıkış örneği
        val frameBytes = 2 * channels
        while (b.remaining() >= frameBytes) {
            var sum = 0
            repeat(channels) { sum += b.short }
            // Kutu süzgeci: çıkış örneğine düşen giriş karelerinin ortalaması (basit örtüşme önleme)
            acc += sum.toDouble() / channels
            accCount++
            phase += step
            var emitted = false
            while (phase >= 1.0) { // 16 kHz'den düşük yayınlarda aynı örnek birden çok kez yazılır
                phase -= 1.0
                ring[pos] = (acc / accCount).toInt().coerceIn(-32768, 32767).toShort()
                pos = (pos + 1) % ring.size
                if (filled < ring.size) filled++
                emitted = true
            }
            if (emitted) { acc = 0.0; accCount = 0 }
        }
    }

    /** Biriken ses (saniye). */
    @Synchronized
    fun seconds(): Double = filled.toDouble() / RATE

    /** Son [count] örnek, eskiden yeniye. */
    @Synchronized
    fun last(count: Int): ShortArray {
        val n = minOf(count, filled)
        val out = ShortArray(n)
        val start = (pos - n + ring.size) % ring.size
        for (i in 0 until n) out[i] = ring[(start + i) % ring.size]
        return out
    }
}
