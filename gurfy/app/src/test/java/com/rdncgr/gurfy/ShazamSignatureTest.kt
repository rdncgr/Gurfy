package com.rdncgr.gurfy

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class ShazamSignatureTest {

    /** İmzanın başlığı ve CRC'si shazamio / SongRec'in okuyabileceği biçimde mi? */
    @Test
    fun headerIsValid() {
        // 440 Hz + 1000 Hz + 2500 Hz, araya sessizlik serpiştirilmiş 12 sn: tepe noktası üretir
        val pcm = ShortArray(12 * 16000) { i ->
            val t = i / 16000.0
            val on = (i / 4000) % 2 == 0
            if (!on) 0 else (4000 * (sin(2 * PI * 440 * t) + sin(2 * PI * 1000 * t) + sin(2 * PI * 2500 * t))).toInt().toShort()
        }
        val sig = ShazamSignature.create(pcm)
        val b = ByteBuffer.wrap(sig.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xCAFE2580.toInt(), b.getInt(0))
        assertEquals(sig.bytes.size - 48, b.getInt(8))
        assertEquals(0x94119C00.toInt(), b.getInt(12))
        assertEquals(3 shl 27, b.getInt(28))
        assertEquals(12 * 16000 + 3840, b.getInt(40))
        assertEquals(0x40000000, b.getInt(48))
        val crc = CRC32().apply { update(sig.bytes, 8, sig.bytes.size - 8) }.value.toInt()
        assertEquals(crc, b.getInt(4))
        assertTrue("tepe noktası bulunmalı", sig.peakCount > 0)
    }

    /**
     * Gerçek kayıtla uçtan uca deneme (ağ gerekir). GURFY_SHAZAM_DIR klasöründeki her
     * *.pcm (16 kHz, mono, s16le) dosyası için imza üretip Shazam'a sorar.
     */
    @Test
    fun recognizesRealClips() {
        val dir = System.getenv("GURFY_SHAZAM_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val http = OkHttpClient()
        var found = 0
        val files = dir!!.listFiles { f -> f.extension == "pcm" }!!.sorted()
        for (f in files) {
            val bytes = f.readBytes()
            val pcm = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
            val sig = ShazamSignature.create(pcm.copyOfRange(maxOf(0, pcm.size - 12 * 16000), pcm.size))
            File(f.path.removeSuffix(".pcm") + ".kt.sig").writeBytes(sig.bytes)
            val r = ShazamApi.recognize(sig, http)
            println("${f.name}: tepe=${sig.peakCount} -> ${r ?: "tanınmadı"}")
            if (r != null) found++
        }
        assertTrue("en az bir kayıt tanınmalı", found > 0)
    }
}
