package com.rdncgr.gurfy

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File

class TextAndTagsTest {

    /** Beklenen değerler sunucudaki Python norm()/safe_name()/split_youtube_title() çıktıları. */
    @Test
    fun matchesServerText() {
        val cases = File(System.getenv("GURFY_TEXT_CASES") ?: return).readLines().filter { it.isNotBlank() }
        for (line in cases) {
            val (fn, input, expected) = line.split('\t')
            val actual = when (fn) {
                "norm" -> norm(input)
                "safe" -> safeName(input)
                "yt" -> splitYoutubeTitle(input.substringBefore("||"), input.substringAfter("||")).let { "${it.first}||${it.second}" }
                "genre" -> genresFromTags(input.split(";").map { it.substringBefore(":") to it.substringAfter(":").toInt() }).joinToString(",")
                else -> error(fn)
            }
            assertEquals("$fn($input)", expected, actual)
        }
    }

    /** Gerçek YouTube M4A'sına etiket yazar; sonuç GURFY_TAG_DIR'e kaydedilir, ffprobe ile okunur. */
    @Test
    fun writesTagsIntoYoutubeM4a() {
        val dir = System.getenv("GURFY_TAG_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val http = OkHttpClient()
        NewPipe.init(NewPipeHttp(http))
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=FUMreHQdTmg")
        val a = info.audioStreams.first { it.format == MediaFormat.M4A }
        val src = http.newCall(Request.Builder().url(a.content).header("User-Agent", NewPipeHttp.USER_AGENT).build())
            .execute().use { it.body!!.bytes() }
        val cover = http.newCall(Request.Builder().url("https://i.ytimg.com/vi/FUMreHQdTmg/hqdefault.jpg").build())
            .execute().use { it.body!!.bytes() }
        File(dir, "kaynak.m4a").writeBytes(src)
        val out = Mp4Tags.write(src, Mp4Tags.Tags("Dedikodu", "Tarkan", "Metamorfoz", "Türkçe Pop; Pop", cover))
        File(dir, "etiketli.m4a").writeBytes(out)
        // İkinci kez yazmak eski etiketin yerine geçmeli (tür düzenleme)
        File(dir, "etiketli2.m4a").writeBytes(Mp4Tags.write(out, Mp4Tags.Tags("Dedikodu", "Tarkan", "Metamorfoz", "Rock", null)))
    }
}
