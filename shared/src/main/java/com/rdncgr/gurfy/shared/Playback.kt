package com.rdncgr.gurfy.shared

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Radyo yayınının gönderdiği "şu an çalan" bilgisi (ICY StreamTitle). */
object LiveMeta {
    var title by mutableStateOf<String?>(null)
        private set

    fun update(raw: String?) {
        val t = raw?.trim()?.trim('-', ' ')
        title = if (t.isNullOrBlank() || t.length < 3) null else t
    }
}

/** Arka planda çalmayı ve bildirim/kilit ekranı kontrollerini sağlayan servis. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var crossfader: Crossfader? = null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Graph.init(this) // sistem servisi uygulamadan önce başlatırsa diye
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Ağ tamponu: en az 60 sn ileriyi (ve sıradaki şarkının başını) önceden yükler; bağlantı anlık
        // koparsa 6 kez yeniden dener. Kesintileri azaltır, herkes için açık.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 180_000, 2_500, 10_000)
            .build()
        val sources = DefaultMediaSourceFactory(this)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        // Uygulama isterse ses çıkışına işlemci takılır (Gurfy: "Bu şarkı ne?" için çalan sesin kopyası)
        val processors = Graph.backend.audioProcessors()
        val builder = if (processors.isEmpty()) ExoPlayer.Builder(this) else ExoPlayer.Builder(
            this,
            object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): androidx.media3.exoplayer.audio.AudioSink =
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setAudioProcessors(processors.toTypedArray())
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
            },
        )
        val player = builder
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        player.addListener(object : Player.Listener {
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val e = metadata[i]
                    if (e is IcyInfo) LiveMeta.update(e.title)
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                LiveMeta.update(null)
                updateLayout()
            }
        })
        crossfader = Crossfader(this, player).also { it.start() }
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    val fixed = mediaItems.map { item ->
                        if (item.localConfiguration != null) item
                        else item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
                    }.toMutableList()
                    return Futures.immediateFuture(fixed)
                }

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(identifyCommand).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                // Bildirimdeki "indir" düğmesi: radyoda çalan şarkıyı tanır ve doğrudan indirmeye gönderir
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction != identifyCommand.customAction) {
                        return super.onCustomCommand(session, controller, customCommand, args)
                    }
                    val item = session.player.currentMediaItem
                    val url = item?.localConfiguration?.uri?.toString() ?: item?.requestMetadata?.mediaUri?.toString()
                    if (item != null && item.mediaId.startsWith("radio:") && url != null) {
                        SongId.identifyAndDownload(
                            Song(
                                id = item.mediaId, title = item.mediaMetadata.title?.toString() ?: "Radyo",
                                artist = "Canlı radyo", album = "Radyo", albumId = null, coverArt = null,
                                duration = 0, streamUrl = url,
                            ),
                        )
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        updateLayout()
    }

    private val identifyCommand = SessionCommand("gurfy.radyo.indir", Bundle.EMPTY)

    /** Bildirimde yalnızca radyo çalarken "indir" düğmesi görünür. */
    private fun updateLayout() {
        val s = session ?: return
        val radio = s.player.currentMediaItem?.mediaId?.startsWith("radio:") == true
        s.setCustomLayout(
            if (radio) listOf(
                CommandButton.Builder()
                    .setDisplayName("Çalan şarkıyı indir")
                    .setIconResId(R.drawable.ic_radyo_indir)
                    .setSessionCommand(identifyCommand)
                    .build(),
            ) else emptyList(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        crossfader?.release()
        crossfader = null
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}

/** İndirmeden önce 30 saniyelik dinleme. Ana çalardan ayrı, uygulama içinde çalışır. */
object Preview {
    private const val LENGTH_MS = 30_000L
    private var player: ExoPlayer? = null
    private var stopJob: kotlinx.coroutines.Job? = null

    /** Şu an önizlenen şarkının anahtarı (null = önizleme yok). */
    var currentKey by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    fun keyOf(s: Suggestion) = s.url ?: (s.artist.lowercase() + "|" + s.title.lowercase())

    // Servis önizlemeyi önce YouTube'dan indiriyor (ilk açılış 5-15 sn): uzun zaman aşımı gerekli
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun ensure(ctx: Context): ExoPlayer = player ?: ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(60_000),
            ),
        )
        .build().also { p ->
        player = p
        p.addListener(object : Player.Listener {
            private var sought = false
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { sought = false }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    loading = false
                    // Girişi atla: şarkının üçte birinden başla
                    if (!sought) {
                        sought = true
                        val d = p.duration
                        if (d != C.TIME_UNSET && d > LENGTH_MS * 2) p.seekTo(d / 3)
                    }
                } else if (state == Player.STATE_ENDED) stop()
            }
            override fun onPlayerError(error: PlaybackException) {
                Graph.toast("Önizleme açılamadı")
                stop()
            }
        })
    }

    fun toggle(s: Suggestion) {
        val key = keyOf(s)
        if (currentKey == key) { stop(); return }
        if (!Graph.settings.serviceConfigured) { Graph.toast("Önce Gurfy servis adresini girin"); return }
        Graph.player.pause()
        val p = ensure(Graph.appContext)
        currentKey = key
        loading = true
        // Adres ağdan çözülebilir (Gurfy: YouTube akışı): ana iş parçacığında değil
        Graph.scope.launch {
            val url = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Graph.service.previewUrl(s) }
            } catch (e: Exception) {
                if (currentKey == key) { Graph.toast("Önizleme açılamadı"); stop() }
                return@launch
            }
            if (currentKey != key) return@launch
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.play()
        }
        stopJob?.cancel()
        stopJob = Graph.scope.launch {
            // yüklenmeyi bekle, sonra 30 saniye çal
            while (loading && currentKey == key) delay(250)
            delay(LENGTH_MS)
            if (currentKey == key) stop()
        }
    }

    fun stop() {
        stopJob?.cancel()
        stopJob = null
        player?.stop()
        player?.clearMediaItems()
        currentKey = null
        loading = false
    }
}

/**
 * Karışık çalma modu. Tek bir düğme her basışta sıradakine geçer; seçilen mod kaydedilir.
 * - OFF: sıra olduğu gibi çalar.
 * - SHUFFLE: sıra karıştırılır; hiçbir şey çalmıyorsa kütüphaneden rastgele şarkılar başlar.
 * - SMART: sıra bitince "Sana özel karışık" (beğeniler, sık/son dinlenenler, son eklenenler) ile devam eder.
 */
enum class PlayMode(val label: String) {
    OFF("Karışık kapalı"),
    SHUFFLE("Rastgele"),
    SMART("Sana özel karışık");

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}

/** Arayüzün çaları yönettiği katman. */
class PlayerController(private val ctx: Context) {
    private var controller: MediaController? = null
    private var pending: (() -> Unit)? = null

    var queue by mutableStateOf<List<Song>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var mode by mutableStateOf(PlayMode.entries.getOrElse(Graph.settings.playMode) { PlayMode.OFF })
        private set
    /** Mod için şarkılar hazırlanıyor (rastgele/sana özel liste çekiliyor). */
    var preparing by mutableStateOf(false)
        private set

    val current: Song? get() = queue.getOrNull(index)

    fun cycleMode() = changeMode(mode.next())

    fun changeMode(m: PlayMode) {
        mode = m
        Graph.settings.playMode = m.ordinal
        Graph.info(m.label)
        val idle = queue.isEmpty()
        if (current?.isRadio == true) return // radyoda kendi rastgele düğmesi var
        when {
            // Hiçbir şey çalmıyorsa seçilen moda göre hemen başla
            idle && m == PlayMode.SHUFFLE -> startFrom { Graph.subsonic.randomSongs() }
            idle && m == PlayMode.SMART -> startFrom { Graph.subsonic.smartMix() }
            else -> withController { c ->
                c.shuffleModeEnabled = m == PlayMode.SHUFFLE
                if (m == PlayMode.SMART) extendSmart()
            }
        }
    }

    /** Hiçbir şey çalmıyorken oynatıcının çal düğmesi: karışık moduna göre rastgele ya da sana özel liste. */
    fun startMix() = startFrom {
        if (mode == PlayMode.SHUFFLE) Graph.subsonic.randomSongs() else Graph.subsonic.smartMix()
    }

    private fun startFrom(source: () -> List<Song>) {
        if (preparing) return
        preparing = true
        Graph.scope.launch {
            try {
                val songs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { source() }
                if (songs.isEmpty()) Graph.toast("Kütüphanede şarkı yok") else play(songs, 0, ordered = true)
            } catch (e: Exception) {
                Graph.toast(errorText(e, "Bağlantı kurulamadı"))
            } finally {
                preparing = false
            }
        }
    }

    /** Sana özel modda sıranın sonuna yeni harman ekler. */
    private fun extendSmart() {
        if (preparing || mode != PlayMode.SMART) return
        preparing = true
        val have = queue.map { it.id }.toSet()
        Graph.scope.launch {
            try {
                val more = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Graph.subsonic.smartMix(30, exclude = have)
                }
                if (more.isNotEmpty() && mode == PlayMode.SMART) {
                    queue = queue + more
                    controller?.addMediaItems(more.map(::toItem))
                }
            } catch (e: Exception) {
                // çevrimdışıysa mevcut sırayla devam
            } finally {
                preparing = false
            }
        }
    }

    private fun maybeExtend(c: MediaController) {
        if (current?.isRadio == true) return
        if (mode == PlayMode.SMART && c.mediaItemCount - c.currentMediaItemIndex <= 3) extendSmart()
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        playing = player.isPlaying
                        if (player.mediaItemCount > 0) index = player.currentMediaItemIndex
                        rememberRadio()
                    }

                    // Bağlantı koptuysa susmak yerine sıradaki telefonda kayıtlı şarkıya geç
                    override fun onPlayerError(error: PlaybackException) {
                        val q = queue
                        val from = c.currentMediaItemIndex
                        // Ağ değişmiş olabilir (Wi-Fi ⇄ hücresel): şarkıyı bir kez güncel adresle yeniden dene
                        val cur = q.getOrNull(from)
                        if (cur != null && !cur.isRadio && !cur.isLocal && retriedId != cur.id && q.size == c.mediaItemCount) {
                            retriedId = cur.id
                            val pos = c.currentPosition
                            c.replaceMediaItem(from, toItem(cur))
                            c.seekTo(from, pos)
                            c.prepare()
                            c.play()
                            return
                        }
                        val nextLocal = ((from + 1 until q.size) + (0 until from))
                            .firstOrNull { Graph.offline.downloaded.containsKey(q[it].id) }
                        if (nextLocal != null) {
                            Graph.info("Bağlantı yok, telefondaki şarkılarla devam ediliyor")
                            c.seekTo(nextLocal, 0L)
                            c.prepare()
                            c.play()
                        } else {
                            Graph.toast("Çalınamadı: sunucuya ulaşılamıyor")
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        retriedId = null
                        maybeExtend(c)
                        Scrobbler.reset() // aynı şarkı tekrarlansa da yeni bir dinleme sayılır
                    }
                })
                // Dinleme süresini izle: "dinlendi" bildirimi şarkının yarısı / 4 dk dinlenince gider
                Graph.scope.launch {
                    while (true) {
                        Scrobbler.tick(current, c.isPlaying, duration())
                        kotlinx.coroutines.delay(1000)
                    }
                }
                pending?.invoke()
                pending = null
            } catch (e: Exception) {
                Graph.toast("Çalar başlatılamadı")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private var retriedId: String? = null

    /**
     * Ağ değişince (Gurfy Sync: Wi-Fi ⇄ hücresel adres) çalma sırasındaki sonraki şarkıların adresini yeniler.
     * Çalan şarkı kesilmez; bağlantısı koparsa onPlayerError güncel adresle yeniden dener.
     */
    fun refreshUrls() {
        val c = controller ?: return
        val q = queue
        if (current?.isRadio == true || q.size != c.mediaItemCount) return
        val i = c.currentMediaItemIndex
        if (i + 1 < c.mediaItemCount) c.replaceMediaItems(i + 1, c.mediaItemCount, q.drop(i + 1).map(::toItem))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending = { controller?.let(block) }
            connect()
        }
    }

    private fun toItem(song: Song): MediaItem {
        val local = Graph.offline.fileFor(song.id)
        val uri = when {
            song.streamUrl != null -> Uri.parse(song.streamUrl) // radyo: doğrudan akış
            song.localUri != null -> Uri.parse(song.localUri) // telefonun kendi müziği
            Graph.offline.downloaded.containsKey(song.id) && local.exists() -> Uri.fromFile(local)
            else -> Uri.parse(Graph.subsonic.streamUrl(song.id))
        }
        val cover = Graph.subsonic.coverUrl(song.coverArt, 600)
        val meta = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(cover?.let(Uri::parse))
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(meta)
            .build()
    }

    /**
     * [ordered]: "Çal" / "Karışık" düğmeleri gibi listenin tamamını isteyen çağrılar.
     * Sana özel karışık açıkken bir şarkıya dokunulursa (ordered = false) o şarkı çalar,
     * ardından listenin geri kalanı yerine sana özel karışık gelir.
     */
    fun play(songs: List<Song>, start: Int = 0, shuffled: Boolean = false, ordered: Boolean = false) {
        if (songs.isEmpty()) return
        Preview.stop()
        val from = start.coerceIn(0, songs.size - 1)
        val seed = mode == PlayMode.SMART && !ordered && !shuffled && !songs[0].isRadio && songs.size > 1
        if (seed) {
            queue = listOf(songs[from])
            index = 0
        } else {
            queue = songs
            index = from
        }
        val list = queue
        withController { c ->
            c.shuffleModeEnabled = if (list[0].isRadio) false else shuffled || mode == PlayMode.SHUFFLE
            c.repeatMode = if (list[0].isRadio) Player.REPEAT_MODE_OFF else repeat
            c.setMediaItems(list.map(::toItem), index, 0L)
            c.prepare()
            c.play()
            maybeExtend(c)
            if (list[0].isRadio && radioShuffle && !radioFromFavorites) rebuildRadioQueue()
        }
    }

    fun pause() { controller?.pause() }
    fun toggle() = withController { c -> if (c.isPlaying) c.pause() else c.play() }
    /** Tekrar modu: Player.REPEAT_MODE_OFF / ALL / ONE (kalıcı ayar). */
    var repeat by mutableIntStateOf(
        when (Graph.settings.repeatMode) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF },
    )
        private set

    /** Her basışta: kapalı → tümünü tekrarla → aynı şarkıyı tekrarla → kapalı */
    fun cycleRepeat() {
        repeat = when (repeat) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        Graph.settings.repeatMode = when (repeat) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 }
        if (current?.isRadio != true) controller?.repeatMode = repeat
    }

    /** Radyolar arasında rastgele geçiş (kalıcı ayar). */
    var radioShuffle by mutableStateOf(Graph.settings.radioShuffle)
        private set

    fun toggleRadioShuffle() {
        if (radioFromFavorites) return
        radioShuffle = !radioShuffle
        Graph.settings.radioShuffle = radioShuffle
        rebuildRadioQueue()
    }

    /** Rastgele radyonun havuzu: "tr" = Türk, "world" = yabancı, "mix" = ikisi birlikte (kalıcı ayar). */
    var radioPool by mutableStateOf(Graph.settings.radioPool)
        private set

    fun changeRadioPool(pool: String) {
        if (radioFromFavorites || pool == radioPool) return
        radioPool = pool
        Graph.settings.radioPool = pool
        if (radioShuffle) rebuildRadioQueue()
    }

    /** Radyo favorilerden açıldıysa rastgele kapalıdır; ileri / geri yalnızca favorilerde gezer. */
    var radioFromFavorites by mutableStateOf(false)
        private set

    /** Radyonun açıldığı liste: rastgele kapalıyken ileri / geri bu listede sırayla gezer. */
    private var radioList: List<Song> = emptyList()
    private var poolJob: kotlinx.coroutines.Job? = null

    private var lastRadioUrl: String? = null

    private fun rememberRadio() {
        val s = current ?: return
        val url = s.streamUrl ?: return
        if (url == lastRadioUrl) return
        lastRadioUrl = url
        Graph.settings.lastRadio = org.json.JSONObject()
            .put("name", s.title).put("url", url).put("favicon", s.logo ?: "").put("fav", radioFromFavorites)
            .toString()
    }

    /**
     * Ana sayfadaki radyo düğmesi: radyo çalıyorsa duraklatır / sürdürür; yoksa en son çalan radyoyu
     * açtığı listeyle birlikte başlatır. Hiç radyo çalınmadıysa favorilerin (yoksa Türk radyolarının) ilki çalar.
     */
    fun playLastRadio() {
        if (current?.isRadio == true) { toggle(); return }
        val last = try {
            org.json.JSONObject(Graph.settings.lastRadio).takeIf { it.optString("url").isNotBlank() }
        } catch (e: Exception) { null }
        Graph.scope.launch {
            try {
                val favs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { Graph.subsonic.radios() } catch (e: Exception) { emptyList() }
                }
                val wantFav = last?.optBoolean("fav") ?: true
                val useFav = wantFav && favs.isNotEmpty() || !Graph.settings.serviceConfigured
                val list = if (useFav) favs else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Graph.service.radios(scope = "tr")
                }
                var stations = list
                var station = last?.let { o -> list.firstOrNull { urlKey(it.url) == urlKey(o.optString("url")) } }
                if (station == null && last != null) {
                    station = RadioStation(null, last.optString("name"), last.optString("url"), favicon = last.optString("favicon"))
                    stations = listOf(station) + list
                }
                val start = station ?: stations.firstOrNull()
                if (start == null) {
                    Graph.toast("Henüz radyo yok. Kütüphane > Radyo'dan ekleyebilirsin.")
                    return@launch
                }
                playRadio(stations, start, favorites = useFav)
            } catch (e: Exception) {
                Graph.toast(errorText(e, "Radyo açılamadı"))
            }
        }
    }

    fun playRadios(list: List<Song>, start: Int, favorites: Boolean) {
        radioList = list
        radioFromFavorites = favorites
        play(list, start)
    }

    /**
     * Çalan radyoyu kesmeden sıranın geri kalanını değiştirir:
     * rastgele açıksa seçili havuzun karışık hali, kapalıysa radyonun açıldığı liste.
     */
    private fun rebuildRadioQueue() {
        val cur = current ?: return
        if (!cur.isRadio) return
        poolJob?.cancel()
        if (!radioShuffle || radioFromFavorites) {
            replaceAround(cur, radioList)
            return
        }
        val pool = radioPool
        poolJob = Graph.scope.launch {
            val songs = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadRadioPool(pool) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val now = current
            if (now?.isRadio != true || !radioShuffle || radioFromFavorites) return@launch
            if (songs.isEmpty()) {
                // Sunucu listesi gelmediyse radyonun açıldığı listede rastgele gez
                replaceAround(now, radioList)
                controller?.shuffleModeEnabled = true
            } else {
                replaceAround(now, songs.shuffled())
            }
        }
    }

    private fun loadRadioPool(pool: String): List<Song> {
        if (!Graph.settings.serviceConfigured) return emptyList()
        val list = when (pool) {
            "tr" -> Graph.service.radios(scope = "tr")
            "world" -> Graph.service.radios(scope = "world")
            else -> Graph.service.radios(scope = "tr") + Graph.service.radios(scope = "world")
        }
        return list.distinctBy { urlKey(it.url) }.map { it.asSong() }
    }

    private fun replaceAround(cur: Song, list: List<Song>) {
        val c = controller ?: return
        val key = urlKey(cur.streamUrl.orEmpty())
        val pos = list.indexOfFirst { urlKey(it.streamUrl.orEmpty()) == key }.coerceAtLeast(0)
        val others = list.filter { urlKey(it.streamUrl.orEmpty()) != key }
        val before = others.take(pos)
        val after = others.drop(pos)
        val i = c.currentMediaItemIndex
        c.shuffleModeEnabled = false
        if (i + 1 < c.mediaItemCount) c.removeMediaItems(i + 1, c.mediaItemCount)
        if (i > 0) c.removeMediaItems(0, i)
        if (after.isNotEmpty()) c.addMediaItems(after.map(::toItem))
        if (before.isNotEmpty()) c.addMediaItems(0, before.map(::toItem))
        queue = before + cur + after
        index = before.size
    }

    // Radyoda listenin sonundan başa (ve tersi) döner
    fun next() = withController {
        if (!it.hasNextMediaItem() && current?.isRadio == true && it.mediaItemCount > 1) it.seekTo(0, 0L)
        else it.seekToNextMediaItem()
    }
    fun previous() = withController {
        if (current?.isRadio == true) {
            if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem()
            else if (it.mediaItemCount > 1) it.seekTo(it.mediaItemCount - 1, 0L)
        } else it.seekToPreviousMediaItem()
    }

    /** Sunucudan silinen şarkıyı çalma sırasından çıkarır. */
    fun remove(songId: String) {
        val i = queue.indexOfFirst { it.id == songId }
        if (i < 0) return
        queue = queue.filterIndexed { j, _ -> j != i }
        controller?.let { c -> if (i < c.mediaItemCount) c.removeMediaItem(i) }
    }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }

    fun position(): Long = controller?.currentPosition ?: 0L
    fun duration(): Long = (controller?.duration ?: 0L).let { if (it == C.TIME_UNSET) 0L else it }
}

/**
 * Navidrome'a (oradan Last.fm'e) dinleme bildirimi. Last.fm kuralı:
 *  - şarkı başlayınca "şu an çalıyor" gider (sayılmaz),
 *  - şarkının yarısı ya da 4 dakikası (hangisi önce) gerçekten dinlenince "dinlendi" gider,
 *  - 30 saniyeden kısa şarkılar sayılmaz; atlanan şarkılar sayılmaz; radyo sayılmaz.
 * Çevrimdışıyken gönderilemeyen "dinlendi" bildirimleri saklanır, bağlantı gelince gönderilir.
 */
object Scrobbler {
    private const val MAX_WAIT_MS = 240_000L
    private const val MIN_LENGTH_MS = 30_000L

    private var id: String? = null
    private var startedAt = 0L
    private var listenedMs = 0L
    private var lastTick = 0L
    private var done = false

    private var needMs = 0L

    /** Şarkı değişti: önceki şarkı eşiğe varmadan bırakıldıysa "atlandı" olarak kaydedilir. */
    fun reset() {
        val prev = id
        if (prev != null && needMs > 0) Skips.record(prev, skipped = !done)
        id = null
    }

    fun tick(song: Song?, playing: Boolean, durationMs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        val delta = (now - lastTick).coerceIn(0L, 2_000L)
        lastTick = now
        if (song == null || song.isRadio || song.isLocal) { id = null; return }
        if (song.id != id) {
            id = song.id
            startedAt = System.currentTimeMillis()
            listenedMs = 0
            done = false
            val len = if (durationMs > 0) durationMs else song.duration * 1000L
            needMs = if (len in 1 until MIN_LENGTH_MS) 0L else if (len > 0) minOf(len / 2, MAX_WAIT_MS) else MAX_WAIT_MS
            send(song.id, false, startedAt)
            return
        }
        if (!playing || done) return
        listenedMs += delta
        val length = if (durationMs > 0) durationMs else song.duration * 1000L
        if (length in 1 until MIN_LENGTH_MS) return
        val need = if (length > 0) minOf(length / 2, MAX_WAIT_MS) else MAX_WAIT_MS
        if (listenedMs >= need) {
            done = true
            send(song.id, true, startedAt)
        }
    }

    private fun send(songId: String, submission: Boolean, time: Long) {
        Thread {
            try {
                Graph.subsonic.scrobble(songId, submission, time)
                if (submission) flushPending()
            } catch (e: Exception) {
                if (submission) addPending(songId, time) // "şu an çalıyor" kaybolabilir, sayım kaybolmasın
            }
        }.start()
    }

    @Synchronized
    private fun addPending(songId: String, time: Long) {
        val a = try { org.json.JSONArray(Graph.settings.pendingScrobbles) } catch (e: Exception) { org.json.JSONArray() }
        a.put(org.json.JSONObject().put("id", songId).put("time", time))
        // en fazla son 500 bildirim
        val keep = org.json.JSONArray()
        for (i in maxOf(0, a.length() - 500) until a.length()) keep.put(a.get(i))
        Graph.settings.pendingScrobbles = keep.toString()
    }

    @Synchronized
    private fun flushPending() {
        val a = try { org.json.JSONArray(Graph.settings.pendingScrobbles) } catch (e: Exception) { return }
        if (a.length() == 0) return
        val left = org.json.JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            try {
                Graph.subsonic.scrobble(o.getString("id"), true, o.getLong("time"))
            } catch (e: Exception) {
                left.put(o)
            }
        }
        Graph.settings.pendingScrobbles = left.toString()
    }
}

/**
 * Hangi şarkıların atlandığı (telefonda tutulur). Her şarkı için son 4 çalışın sonucu saklanır:
 * "s" = yarısına gelmeden geçildi, "f" = dinlendi. Son 3 çalışta hep atlanan şarkılar sana özel
 * karışığa alınmaz; bir kez sonuna kadar dinlenirse tekrar girer.
 */
object Skips {
    private const val KEEP = 4
    private var map: MutableMap<String, String>? = null

    @Synchronized
    private fun data(): MutableMap<String, String> = map ?: run {
        val m = mutableMapOf<String, String>()
        try {
            val o = org.json.JSONObject(Graph.settings.skipsJson)
            o.keys().forEach { k -> m[k] = o.getString(k) }
        } catch (e: Exception) { }
        map = m
        m
    }

    @Synchronized
    fun record(songId: String, skipped: Boolean) {
        val m = data()
        m[songId] = ((m[songId] ?: "") + if (skipped) "s" else "f").takeLast(KEEP)
        // en fazla 3000 şarkı
        if (m.size > 3000) m.keys.take(m.size - 3000).forEach { m.remove(it) }
        Graph.settings.skipsJson = org.json.JSONObject(m as Map<*, *>).toString()
    }

    /** Karışığa alınmayacak şarkılar. */
    @Synchronized
    fun disliked(): Set<String> = data().filterValues { it.length >= 3 && it.takeLast(3) == "sss" }.keys.toSet()
}
