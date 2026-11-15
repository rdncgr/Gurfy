package com.rdncgr.nota

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import java.nio.ByteBuffer

// İşitme cihazı uyumluluk modu
// ------------------------------
// İşitme cihazları (ASHA / LE Audio) düşük enerjili Bluetooth kullanır; telefondan gelen akış kısa bir
// süre aksarsa sesi keser, akış tamamen durursa uykuya geçer ve yeniden uyanması 1-2 saniye sürer.
// Mod açıkken:
//  1. Ses kartına giden tampon (AudioTrack) 0,25 sn yerine 0,5–1 sn tutulur: işlemci ya da ağ anlık
//     takılsa da cihaza giden akış kesilmez. Bedeli: çal/duraklat yarım saniye kadar geç tepki verir.
//  2. Tüm şarkılar uygulama içinde 48 kHz'e çevrilir: farklı örnekleme hızındaki şarkılar arasında
//     geçerken ses kanalı yeniden kurulmaz (kurulurken oluşan kısa sessizlik/kopma olmaz).
//  3. Duraklatınca ya da internet yavaşlayıp ara bellek dolarken, duyulmayan sessizlik gönderilerek
//     Bluetooth hattı açık tutulur (en fazla 10 dk): devam edince ses hemen gelir, ilk saniye kaybolmaz.
// Ayrıca isteğe bağlı "Mono ses": iki kanal karıştırılıp iki kulağa aynısı verilir. Bir kulakta kopma
// olursa diğer kulak şarkının tamamını duymaya devam eder; tek tarafı daha az duyanlar için de faydalı.
// Ağ tamponu (sıradaki şarkıyı önceden yükleme) ve yeniden deneme her durumda açıktır, bkz. PlaybackService.

object HearingMode {
    const val OFF = 0
    const val AUTO = 1
    const val ALWAYS = 2

    /** Bağlı işitme cihazının adı (yoksa null). Ses çıkışları değiştikçe güncellenir. */
    var device by mutableStateOf<String?>(null)
        private set

    /** Mod şu an etkin mi? Ses iş parçacığından da okunur. */
    @Volatile var active = false
        private set
    var activeState by mutableStateOf(false)
        private set
    @Volatile var mono = false
        private set

    private var started = false

    fun setting(): Int = Graph.settings.hearingMode

    fun setMode(m: Int) {
        Graph.settings.hearingMode = m
        update()
    }

    fun setMono(on: Boolean) {
        Graph.settings.hearingMono = on
        update()
    }

    private fun update() {
        val s = Graph.settings
        active = when (s.hearingMode) {
            ALWAYS -> true
            AUTO -> device != null
            else -> false
        }
        mono = s.hearingMono
        activeState = active
        if (!active) KeepAlive.stop()
    }

    /** Ses çıkışlarını izlemeye başlar (uygulama/servis açılırken bir kez). */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun scan() {
            device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull(::isHearingDevice)?.let { d ->
                val name = d.productName?.toString()?.takeIf { it.isNotBlank() }
                name ?: if (d.type == AudioDeviceInfo.TYPE_HEARING_AID) "İşitme cihazı" else "LE Audio cihazı"
            }
            update()
        }
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = scan()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = scan()
        }, Handler(Looper.getMainLooper()))
        scan()
    }

    private fun isHearingDevice(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_HEARING_AID ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
}

// ------------------------------------------------------------ ses işleme zinciri

/** Mod açıkken çıkışı 48 kHz'e sabitler (her şarkı başında yeniden karar verilir). */
@UnstableApi
private class FixedRateProcessor : AudioProcessor {
    private val sonic = SonicAudioProcessor()

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sonic.setOutputSampleRateHz(if (HearingMode.active) 48_000 else SonicAudioProcessor.SAMPLE_RATE_NO_CHANGE)
        return sonic.configure(inputAudioFormat)
    }

    override fun isActive() = sonic.isActive
    override fun queueInput(inputBuffer: ByteBuffer) = sonic.queueInput(inputBuffer)
    override fun queueEndOfStream() = sonic.queueEndOfStream()
    override fun getOutput(): ByteBuffer = sonic.output
    override fun isEnded() = sonic.isEnded
    override fun flush() = sonic.flush()
    override fun reset() = sonic.reset()
    override fun getDurationAfterProcessorApplied(durationUs: Long) = sonic.getDurationAfterProcessorApplied(durationUs)
}

/** Mono ses: stereo kanalların ortalamasını iki kulağa da verir (16 bit PCM). */
@UnstableApi
private class MonoProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (HearingMode.mono && inputAudioFormat.channelCount == 2 && inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET // etkisiz
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        while (inputBuffer.remaining() >= 4) {
            val l = inputBuffer.short.toInt()
            val r = inputBuffer.short.toInt()
            val m = ((l + r) / 2).toShort()
            out.putShort(m)
            out.putShort(m)
        }
        out.flip()
    }
}

/** Mod açıkken ses kartı tamponu 0,5–1 sn; kapalıyken Media3 varsayılanı. */
@UnstableApi
private object HearingBufferSize : DefaultAudioSink.AudioTrackBufferSizeProvider {
    private val large = DefaultAudioTrackBufferSizeProvider.Builder()
        .setMinPcmBufferDurationUs(500_000)
        .setMaxPcmBufferDurationUs(1_000_000)
        .build()

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int, encoding: Int, outputMode: Int, pcmFrameSize: Int,
        sampleRate: Int, bitrate: Int, maxAudioTrackPlaybackSpeed: Double,
    ): Int = (if (HearingMode.active) large else DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT)
        .getBufferSizeInBytes(minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxAudioTrackPlaybackSpeed)
}

@UnstableApi
class HearingRenderersFactory(ctx: Context) : DefaultRenderersFactory(ctx) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(MonoProcessor(), FixedRateProcessor()))
            .setAudioTrackBufferSizeProvider(HearingBufferSize)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
}

// ------------------------------------------------------------ hattı açık tutma

/** Duraklatıldığında / ara bellek dolarken duyulmayan sessizlik çalarak Bluetooth hattını açık tutar. */
object KeepAlive {
    private const val MAX_MS = 10 * 60 * 1000L
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (!HearingMode.active || thread != null) return
        thread = Thread({
            val rate = 48_000
            val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    )
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                null
            }
            if (track == null) { synchronized(this) { thread = null }; return@Thread }
            val zeros = ByteArray(size)
            val until = System.currentTimeMillis() + MAX_MS
            try {
                track.play()
                while (!Thread.currentThread().isInterrupted && System.currentTimeMillis() < until) {
                    if (track.write(zeros, 0, zeros.size) < 0) break
                }
            } catch (e: Exception) {
                // sessizce bırak
            } finally {
                try { track.stop() } catch (e: Exception) { }
                track.release()
                synchronized(this) { if (thread === Thread.currentThread()) thread = null }
            }
        }, "nota-keepalive").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }
}
