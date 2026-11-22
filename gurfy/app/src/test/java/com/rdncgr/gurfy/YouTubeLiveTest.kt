package com.rdncgr.gurfy

import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/** YouTube'a canlı erişim (ağ gerekir): GURFY_YT_LIVE=1 ile çalışır. */
class YouTubeLiveTest {
    @Test
    fun searchAndResolve() {
        assumeTrue(System.getenv("GURFY_YT_LIVE") == "1")
        NewPipe.init(NewPipeHttp(OkHttpClient()), Localization("tr", "TR"), ContentCountry("TR"))
        for (q in listOf("Tarkan", "sezen aksu")) {
            val music = runCatching { YouTube.search(q) }
            println("muzik[$q]: " + music.fold({ "${it.size} sonuç, ilk=${it.firstOrNull()?.title}" }, { "HATA ${it.javaClass.name}: ${it.message}" }))
            music.exceptionOrNull()?.printStackTrace(System.out)
            val video = runCatching { YouTube.searchVideos(q) }
            println("video[$q]: " + video.fold({ "${it.size} sonuç" }, { "HATA ${it.javaClass.name}: ${it.message}" }))
        }
        // YouTube Music adresleri (music.youtube.com) de çözülebilmeli
        val fromMusic = runCatching { YouTube.bestAudio("https://music.youtube.com/watch?v=FUMreHQdTmg") }
        println("music-adres: " + fromMusic.fold({ "${it.stream.format}" }, { "HATA ${it.message}" }))
        check(YouTube.search("Tarkan").isNotEmpty()) { "YouTube araması boş" }
        val audio = runCatching { YouTube.bestAudio("https://www.youtube.com/watch?v=FUMreHQdTmg") }
        println("akis: " + audio.fold({ "${it.stream.format} ${it.stream.averageBitrate}" }, { "HATA ${it.javaClass.name}: ${it.message}" }))
    }

    /** Eşleşmeyen imza hata değil, boş sonuç vermeli (Bağlantılar'daki Shazam sınaması buna dayanıyor). */
    @Test
    fun shazamNoMatchIsNotAnError() {
        assumeTrue(System.getenv("GURFY_YT_LIVE") == "1")
        val noise = ShortArray(6 * ShazamSignature.RATE) { ((it * 7919L) % 2001 - 1000).toShort() }
        val r = ShazamApi.recognize(ShazamSignature.create(noise), OkHttpClient())
        println("shazam-gurultu: ${r ?: "eşleşme yok (beklenen)"}")
    }
}
