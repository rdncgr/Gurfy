package com.rdncgr.gurfy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Shazam ses imzası. SongRec'in (Rust) algoritmasının Kotlin'e taşınmış hali; Gurfy Sync'te
 * aynı işi sunucudaki shazamio yapıyordu. Girdi: 16 kHz, tek kanal, 16 bit ses.
 * https://github.com/marin-m/SongRec/blob/master/src/core/fingerprinting/algorithm.rs
 */
object ShazamSignature {
    const val RATE = 16000
    private const val WINDOW = 12 * RATE // Shazam istemcileri 12 sn gönderir; 15 sn ve üstünde eşleşme gelmiyor

    class Signature(val bytes: ByteArray, val sampleCount: Int, val peakCount: Int) {
        val uri get() = "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(bytes)
        val sampleMs get() = sampleCount.toLong() * 1000 / RATE
    }

    private class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    // np.hanning(2050)[1:-1]: baştaki ve sondaki sıfırlar atılmış Hanning penceresi
    private val HANNING = DoubleArray(2048) { i -> 0.5 - 0.5 * cos(2 * PI * (i + 1) / 2049) }
    private val NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val OTHER_FFTS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

    /** 12 saniyelik imza. Uzun girdide ortadaki 12 sn, kısa girdide sonu sessizlikle tamamlanır. */
    fun create(input: ShortArray): Signature {
        val samples = when {
            input.size > WINDOW -> input.copyOfRange(input.size / 2 - WINDOW / 2, input.size / 2 + WINDOW / 2)
            input.size < WINDOW -> input.copyOf(WINDOW)
            else -> input
        }
        val g = Generator()
        var i = 0
        while (i + 128 <= samples.size) {
            g.step(samples, i)
            i += 128
        }
        return encode(samples.size, g.bands)
    }

    private class Generator {
        val ring = ShortArray(2048)
        var ringIdx = 0
        val fftOut = Array(256) { DoubleArray(1025) }
        var fftIdx = 0
        val spread = Array(256) { DoubleArray(1025) }
        var spreadIdx = 0
        var spreadDone = 0
        val bands = Array(4) { ArrayList<Peak>() }
        private val re = DoubleArray(2048)
        private val im = DoubleArray(2048)

        fun step(samples: ShortArray, offset: Int) {
            doFft(samples, offset)
            doSpreading()
            spreadDone++
            if (spreadDone >= 46) doRecognition()
        }

        private fun doFft(samples: ShortArray, offset: Int) {
            System.arraycopy(samples, offset, ring, ringIdx, 128)
            ringIdx = (ringIdx + 128) and 2047
            for (i in 0 until 2048) {
                re[i] = ring[(i + ringIdx) and 2047] * HANNING[i]
                im[i] = 0.0
            }
            Fft.transform(re, im)
            val out = fftOut[fftIdx]
            for (k in 0..1024) out[k] = max((re[k] * re[k] + im[k] * im[k]) / (1 shl 17), 0.0000000001)
            fftIdx = (fftIdx + 1) and 255
        }

        private fun doSpreading() {
            val real = fftOut[(fftIdx - 1) and 255]
            val s = spread[spreadIdx]
            System.arraycopy(real, 0, s, 0, 1025)
            // Frekans ekseninde yayma
            for (p in 0..1022) s[p] = max(max(s[p], s[p + 1]), s[p + 2])
            // Zaman ekseninde yayma: 1, 3 ve 6 önceki pencerelere
            for (former in intArrayOf(1, 3, 6)) {
                val o = spread[(spreadIdx - former) and 255]
                for (p in 0..1024) o[p] = max(o[p], s[p])
            }
            spreadIdx = (spreadIdx + 1) and 255
        }

        private fun level(v: Double) = max(ln(v), 1.0 / 64) * 1477.3 + 6144

        private fun doRecognition() {
            val f46 = fftOut[(fftIdx - 46) and 255]
            val f49 = spread[(spreadIdx - 49) and 255]
            for (bin in 10..1014) {
                val v = f46[bin]
                if (v < 1.0 / 64 || v < f49[bin - 1]) continue
                var maxNeighbor = 0.0
                for (o in NEIGHBORS) maxNeighbor = max(maxNeighbor, f49[bin + o])
                if (v <= maxNeighbor) continue
                var maxOther = maxNeighbor
                for (o in OTHER_FFTS) maxOther = max(maxOther, spread[(spreadIdx + o) and 255][bin - 1])
                if (v <= maxOther) continue

                val magnitude = level(v)
                val before = level(f46[bin - 1])
                val after = level(f46[bin + 1])
                val variation1 = magnitude * 2 - before - after
                val variation2 = (after - before) * 32 / variation1
                val corrected = (bin * 64 + variation2.toInt()) and 0xFFFF
                val hz = corrected * (16000.0 / 2 / 1024 / 64)
                val band = when (hz.toInt()) {
                    in 250..519 -> 0
                    in 520..1449 -> 1
                    in 1450..3499 -> 2
                    in 3500..5500 -> 3
                    else -> continue
                }
                bands[band].add(Peak(spreadDone - 46, magnitude.toInt() and 0xFFFF, corrected))
            }
        }
    }

    private fun ByteArrayOutputStream.u32(v: Long) {
        write((v and 0xFF).toInt()); write(((v shr 8) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt()); write(((v shr 24) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun encode(sampleCount: Int, bands: Array<ArrayList<Peak>>): Signature {
        val contents = ByteArrayOutputStream()
        bands.forEachIndexed { band, peaks ->
            if (peaks.isEmpty()) return@forEachIndexed
            val buf = ByteArrayOutputStream()
            var pass = 0
            for (p in peaks) {
                if (p.pass - pass >= 255) {
                    buf.write(0xFF)
                    buf.u32(p.pass.toLong())
                    pass = p.pass
                }
                buf.write(p.pass - pass)
                buf.u16(p.magnitude)
                buf.u16(p.bin)
                pass = p.pass
            }
            contents.u32(0x60030040L + band)
            contents.u32(buf.size().toLong())
            buf.writeTo(contents)
            repeat((4 - buf.size() % 4) % 4) { contents.write(0) }
        }

        val body = ByteArrayOutputStream()
        body.u32(contents.size() + 8L) // size_minus_header
        body.u32(0x94119C00L) // magic2
        repeat(3) { body.u32(0) }
        body.u32(3L shl 27) // 16000 Hz = 3
        repeat(2) { body.u32(0) }
        body.u32(sampleCount + (RATE * 0.24).toLong())
        body.u32((15L shl 19) + 0x40000)
        body.u32(0x40000000L)
        body.u32(contents.size() + 8L)
        contents.writeTo(body)
        val rest = body.toByteArray()

        val crc = CRC32().apply { update(rest) }.value
        val out = ByteArrayOutputStream(rest.size + 8)
        out.u32(0xCAFE2580L)
        out.u32(crc)
        out.write(rest)
        return Signature(out.toByteArray(), sampleCount, bands.sumOf { it.size })
    }
}

/** 2048 noktalı karmaşık FFT (yerinde, radix-2). */
private object Fft {
    private const val N = 2048
    private val cosT = DoubleArray(N / 2) { cos(-2 * PI * it / N) }
    private val sinT = DoubleArray(N / 2) { sin(-2 * PI * it / N) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= N) {
            val half = len / 2
            val step = N / len
            var i = 0
            while (i < N) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Tanınan şarkı. */
data class Recognized(val artist: String, val title: String, val album: String?, val cover: String?)

/**
 * Shazam'ın yanıtı beklenmedik: adres, başlıklar ya da JSON biçimi değişmiş olabilir.
 * Ağ hatasından (internet yok) ayrı tutulur; kullanıcıya "servis değişmiş olabilir" denir.
 */
class ShazamChanged(message: String) : java.io.IOException(message)

/** İmzayı Shazam'a sorar (shazamio'nun kullandığı adres ve başlıklar). */
object ShazamApi {
    private const val UA = "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)"

    fun recognize(sig: ShazamSignature.Signature, http: OkHttpClient): Recognized? {
        val u1 = UUID.randomUUID().toString().uppercase()
        val u2 = UUID.randomUUID().toString().uppercase()
        val url = "https://amp.shazam.com/discovery/v5/tr/TR/iphone/-/tag/$u1/$u2" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true" +
            "&hubv5minorversion=v5.1&hidelb=true&video=v3"
        val body = JSONObject()
            .put("timezone", TimeZone.getDefault().id)
            .put("signature", JSONObject().put("uri", sig.uri).put("samplems", sig.sampleMs))
            .put("timestamp", System.currentTimeMillis())
            .put("context", JSONObject())
            .put("geolocation", JSONObject())
        val req = Request.Builder().url(url)
            .header("X-Shazam-Platform", "IPHONE")
            .header("X-Shazam-AppVersion", "14.1.0")
            .header("Accept", "*/*")
            .header("Accept-Language", "tr")
            .header("User-Agent", UA)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ShazamChanged("Shazam HTTP ${r.code} yanıtı verdi")
            r.body?.string().orEmpty()
        }
        return parse(text)
    }

    fun parse(json: String): Recognized? {
        val root = try { JSONObject(json) } catch (e: Exception) { throw ShazamChanged("Shazam'ın yanıtı okunamadı") }
        // Eşleşme yoksa yanıtta boş "matches" olur; ikisi de yoksa biçim değişmiş demektir
        if (!root.has("matches") && !root.has("track")) throw ShazamChanged("Shazam'ın yanıt biçimi değişmiş")
        val track = root.optJSONObject("track") ?: return null
        val title = track.optString("title").takeIf { it.isNotBlank() } ?: return null
        var album: String? = null
        val sections = track.optJSONArray("sections")
        for (i in 0 until (sections?.length() ?: 0)) {
            val md = sections!!.optJSONObject(i)?.optJSONArray("metadata") ?: continue
            for (k in 0 until md.length()) {
                val m = md.optJSONObject(k) ?: continue
                if (m.optString("title").lowercase() in setOf("album", "albüm")) album = m.optString("text")
            }
        }
        return Recognized(
            artist = track.optString("subtitle"),
            title = title,
            album = album?.takeIf { it.isNotBlank() },
            cover = track.optJSONObject("images")?.optString("coverart")?.takeIf { it.isNotBlank() },
        )
    }
}
