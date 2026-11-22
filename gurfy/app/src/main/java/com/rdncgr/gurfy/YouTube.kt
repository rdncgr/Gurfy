package com.rdncgr.gurfy

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** YouTube'daki bir şarkı. */
data class Track(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumb: String?,
)

/** YouTube Music araması, ses akışı ve liste çözümü. Gurfy Sync'te bunu sunucudaki yt-dlp yapıyordu. */
object YouTube {
    private val service get() = ServiceList.YouTube

    /** Sunucudaki MAX_DURATION: 20 dakikadan uzun videolar (kayıt, mix) indirilmez. */
    const val MAX_DURATION = 1200L

    /** Yalnızca şarkılar (YouTube Music "Şarkılar" süzgeci): klip, kanal ve liste gelmez. */
    fun search(query: String): List<Track> {
        val ex = service.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack() }
    }

    /** Genel YouTube video araması (YouTube Music'te bulunmayanlar için yedek). */
    fun searchVideos(query: String): List<Track> {
        val ex = service.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack() }
    }

    fun idOf(url: String): String? = runCatching { service.streamLHFactory.getId(url) }.getOrNull()

    private fun StreamInfoItem.toTrack(): Track? {
        val id = idOf(url) ?: return null
        return Track(
            id = id,
            url = url,
            title = name,
            artist = uploaderName.orEmpty().removeSuffix(" - Topic"),
            durationSec = duration,
            thumb = thumbnails.maxByOrNull { it.height }?.url,
        )
    }

    class Audio(val stream: AudioStream, val thumb: String?, val durationSec: Long)

    /** En iyi ses akışı. M4A (AAC) tercih edilir: dönüştürme gerekmez, etiket yazılabilir, her telefonda çalar. */
    fun bestAudio(url: String): Audio {
        val info = StreamInfo.getInfo(service, url)
        val audios = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val best = audios.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: audios.maxByOrNull { it.averageBitrate }
            ?: error("Bu şarkı için ses akışı bulunamadı")
        return Audio(best, info.thumbnails.maxByOrNull { it.height }?.url, info.duration)
    }

    /** YouTube çalma listesindeki videolar (sunucudaki extract_flat), en çok 500. */
    fun playlist(url: String): Pair<String, List<StreamInfoItem>> {
        val info = PlaylistInfo.getInfo(service, url)
        val items = info.relatedItems.toMutableList()
        var page = info.nextPage
        while (page != null && items.size < 500) {
            val more = PlaylistInfo.getMoreItems(service, url, page)
            items += more.items
            page = more.nextPage
        }
        return info.name to items.take(500)
    }
}
