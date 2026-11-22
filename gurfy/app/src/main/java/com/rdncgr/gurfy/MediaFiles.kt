package com.rdncgr.gurfy

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

/**
 * Şarkı dosyaları telefonun ortak Müzik klasöründe durur: Music/Gurfy/Sanatçı/Albüm/Şarkı.m4a.
 * Uygulama silinse de dosyalar kalır; yeniden kurulunca [scan] kütüphaneyi geri getirir.
 * Sunucudaki MUSIC_DIR + Navidrome taramasının karşılığı.
 */
object MediaFiles {
    const val ROOT = "Music/Gurfy/"
    private val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    class Saved(val id: Long, val uri: Uri, val relPath: String)

    fun songId(mediaId: Long) = "ms:$mediaId"
    fun uriOf(songId: String): Uri? =
        songId.removePrefix("ms:").toLongOrNull()?.let { ContentUris.withAppendedId(collection, it) }

    fun relPathFor(artist: String, album: String?) = ROOT + safeName(artist) + "/" + safeName(album ?: "Tekliler") + "/"

    /** Dosyayı Müzik/Gurfy altına yazar. Aynı adda dosya varsa MediaStore adın sonuna (1) ekler. */
    fun save(ctx: Context, artist: String, album: String?, title: String, ext: String, mime: String, bytes: ByteArray): Saved {
        val rel = relPathFor(artist, album)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, safeName(title) + "." + ext)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, rel)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(collection, values) ?: throw IOException("Müzik klasörüne yazılamadı")
        try {
            cr.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: throw IOException("Dosya açılamadı")
            cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return Saved(ContentUris.parseId(uri), uri, rel)
    }

    /** Dosyanın içeriğini değiştirir (etiket yazmak için). Gurfy'nin yazmadığı dosyada SecurityException. */
    fun rewrite(ctx: Context, uri: Uri, transform: (ByteArray) -> ByteArray) {
        val cr = ctx.contentResolver
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Dosya okunamadı")
        val out = transform(bytes)
        // Dosya kapanınca MediaStore etiketleri kendiliğinden yeniden okur
        cr.openOutputStream(uri, "wt")?.use { it.write(out) } ?: throw IOException("Dosyaya yazılamadı")
    }

    fun delete(ctx: Context, uri: Uri): Boolean = ctx.contentResolver.delete(uri, null, null) > 0

    class Scanned(
        val id: Long, val title: String, val artist: String, val album: String, val genre: String?,
        val duration: Int, val relPath: String, val albumId: Long, val added: Long,
    )

    /**
     * Müzik/Gurfy klasöründeki ve (izin varsa) telefondaki bütün şarkılar. Gurfy'nin kendi yazdıkları her zaman görünür;
     * yeniden kurulumdan önce yazılanlar ve bilgisayardan kopyalananlar için "Müzik ve ses" izni gerekir.
     */
    fun scan(ctx: Context): List<Scanned> {
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATE_ADDED,
        )
        if (Build.VERSION.SDK_INT >= 30) cols += MediaStore.Audio.Media.GENRE
        val out = mutableListOf<Scanned>()
        // Gurfy'nin kendi klasörü her zaman; izin varsa telefondaki bütün müzikler (ses kayıtları,
        // zil sesleri ve kısa kayıtlar hariç: PhoneMusic.selection)
        val (music, musicArgs) = com.rdncgr.gurfy.shared.PhoneMusic.selection()
        ctx.contentResolver.query(
            collection, cols.toTypedArray(),
            "${MediaStore.Audio.Media.IS_PENDING} = 0 AND (${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ($music))",
            arrayOf("$ROOT%") + musicArgs, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val unknown = MediaStore.UNKNOWN_STRING
                out += Scanned(
                    id = c.getLong(0),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "?",
                    artist = c.getString(2)?.takeIf { it.isNotBlank() && it != unknown } ?: "Bilinmeyen sanatçı",
                    album = c.getString(3)?.takeIf { it.isNotBlank() && it != unknown } ?: "",
                    genre = if (cols.size > 8) c.getString(8)?.takeIf { it.isNotBlank() } else null,
                    duration = (c.getLong(4) / 1000).toInt(),
                    relPath = c.getString(5) ?: ROOT,
                    albumId = c.getLong(6),
                    added = c.getLong(7) * 1000,
                )
            }
        }
        return out
    }

    fun albumArt(albumId: Long): String =
        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId).toString()
}
