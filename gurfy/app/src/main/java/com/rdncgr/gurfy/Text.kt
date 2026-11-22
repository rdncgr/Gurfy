package com.rdncgr.gurfy

import java.text.Normalizer
import java.util.Locale

// Gurfy servisindeki (server/app.py) metin yardımcılarının birebir karşılığı.
// Neredeyse bütün "kütüphanede var mı / aynı şarkı mı" karşılaştırmaları norm() ile yapılır.

private val BRACKETS = Regex("""\(.*?\)|\[.*?\]""")
private val VERSION_SUFFIX = Regex("""\s-\s.*(remaster|version|edit|mix|live|mono|stereo).*$""", RegexOption.IGNORE_CASE)
private val FEAT = Regex("""\b(feat|ft)\.?\s.*$""", RegexOption.IGNORE_CASE)
private val COMBINING = Regex("""\p{Mn}+""")
private val NON_ALNUM = Regex("""[^a-z0-9]+""")

/** Karşılaştırma için sadeleştir: küçük harf, aksansız, parantez/ek bilgi yok. */
fun norm(s: String?): String {
    var t = s.orEmpty()
    t = BRACKETS.replace(t, " ")
    t = VERSION_SUFFIX.replace(t, " ")
    t = FEAT.replace(t, " ")
    t = t.replace("ı", "i").replace("İ", "i")
    t = t.lowercase(Locale.ROOT).replace("ß", "ss") // Python casefold
    t = COMBINING.replace(Normalizer.normalize(t, Normalizer.Form.NFKD), "")
    return NON_ALNUM.replace(t, " ").trim()
}

private val UNSAFE = Regex("""[\\/:*?"<>|\x00-\x1f]""")
private val SPACES = Regex("""\s+""")

/** Dosya/klasör adı için güvenli hale getir. */
fun safeName(s: String?): String {
    val t = SPACES.replace(UNSAFE.replace(s.orEmpty(), " "), " ").trim(' ', '.')
    return t.take(120).ifEmpty { "Bilinmeyen" }
}

private val YT_NOISE = Regex(
    """\s*[\(\[][^\)\]]*(official|video|audio|lyric|lyrics|klip|clip|hd|4k|visualizer|music video|resmi|şarkı sözleri|sözleri)[^\)\]]*[\)\]]""",
    RegexOption.IGNORE_CASE,
)
private val PIPE_TAIL = Regex("""\s*\|\s*.*$""")
private val DASH = Regex("""\s+[-–—]\s+""")
private val CHANNEL_NOISE = Regex("""\s*-\s*Topic$|VEVO$|\s+(Official|Oficial|Resmi)$""", RegexOption.IGNORE_CASE)

/** "Sanatçı - Şarkı (Official Video)" gibi başlıkları sanatçı ve şarkı adına ayırır. */
fun splitYoutubeTitle(title: String?, channel: String?): Pair<String, String> {
    var clean = YT_NOISE.replace(title.orEmpty(), "").trim()
    clean = PIPE_TAIL.replace(clean, "").trim()
    val parts = clean.split(DASH, limit = 2)
    if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) return parts[0].trim() to parts[1].trim()
    val artist = CHANNEL_NOISE.replace(channel.orEmpty(), "").trim()
    return artist to clean
}

// ------------------------------------------------------------------------ türler
// Tür Last.fm etiketlerinden çıkarılır (önce şarkının, yoksa sanatçının), Türkçe ve tutarlı isimlere çevrilir.
// Sıra önemli: "metal" "rock"tan, "pop" en son denenir.

private val GENRE_RULES = listOf(
    "arabesk|arabesque" to "Arabesk",
    "fantezi" to "Fantezi",
    """t[üu]rk[üu]\b|halk m|turkish folk|anatolian folk|bozlak|zeybek""" to "Türk Halk Müziği",
    """sanat m|turkish classical|ottoman|\btsm\b""" to "Türk Sanat Müziği",
    "anatolian rock|anadolu rock" to "Anadolu Rock",
    """\brap\b|hip.?hop|\btrap\b|drill""" to "Rap",
    "metal" to "Metal",
    "punk" to "Punk",
    "rock|grunge" to "Rock",
    """electro|\bedm\b|techno|house|trance|dubstep|synth|drum and bass|dnb""" to "Elektronik",
    "dance|disco" to "Dans",
    "jazz|swing" to "Caz",
    "classical|orchestral|baroque|opera" to "Klasik",
    """r&b|\brnb\b|soul|funk""" to "R&B / Soul",
    "latin|reggaeton|salsa|bachata" to "Latin",
    """reggae|ska\b""" to "Reggae",
    """soundtrack|\bost\b|film|score""" to "Film Müziği",
    "blues" to "Blues",
    "country" to "Country",
    "indie" to "Indie",
    "alternative" to "Alternatif",
    "chill|lounge|ambient|lo.?fi|downtempo" to "Chill",
    "folk|singer.?songwriter" to "Folk",
    "acoustic" to "Akustik",
    "pop" to "Pop",
).map { (rx, g) -> Regex(rx, RegexOption.IGNORE_CASE) to g }

private val TURKISH_TAG = Regex("turk|türk|turkce|türkçe|turkey|türkiye|anatolia", RegexOption.IGNORE_CASE)
private val TURKISH_PREFIX = mapOf("Pop" to "Türkçe Pop", "Rock" to "Türkçe Rock", "Rap" to "Türkçe Rap", "Folk" to "Türk Halk Müziği")

/** Last.fm etiketleri (ad, kullanım sayısı) → en çok 2 Türkçe tür. */
fun genresFromTags(tags: List<Pair<String, Int>>): List<String> {
    val ok = tags.filter { it.second >= 10 && it.first.isNotBlank() }
    val turkish = ok.take(8).any { TURKISH_TAG.containsMatchIn(it.first) }
    val out = mutableListOf<String>()
    for ((tag, _) in ok.take(12)) {
        val name = tag.lowercase(Locale.ROOT)
        for ((rx, genre) in GENRE_RULES) {
            if (rx.containsMatchIn(name)) {
                val g = if (turkish) TURKISH_PREFIX[genre] ?: genre else genre
                if (g !in out) out += g
                break
            }
        }
        if (out.size >= 2) break
    }
    return out
}

// Reklam, radyo adı ve benzeri yayın başlıkları şarkı sayılmaz
private val ICY_JUNK = Regex(
    """(reklam|advert|jingle|kesintisiz|unknown|bilinmiyor|untitled|www\.|https?:|\.com|\.net|""" +
        """\bfm\b|radyo|radio|haber|news|canl[ıi] yay[ıi]n|on air|live)""",
    RegexOption.IGNORE_CASE,
)

/** "Sanatçı - Şarkı" biçimindeki radyo yayın başlığını ayırır; şarkı değilse null. */
fun splitStreamTitle(raw: String?, station: String?): Pair<String, String>? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.trim().split(DASH, limit = 2)
    if (parts.size != 2) return null
    val artist = parts[0].trim(' ', '\'', '"')
    val title = parts[1].trim(' ', '\'', '"')
    if (artist.length < 2 || title.length < 2 || ICY_JUNK.containsMatchIn(raw)) return null
    if (!station.isNullOrBlank() && norm(station) in setOf(norm(artist), norm(title), norm(raw))) return null
    if (raw.count { it.isDigit() } > raw.length / 2) return null
    return artist to title
}
