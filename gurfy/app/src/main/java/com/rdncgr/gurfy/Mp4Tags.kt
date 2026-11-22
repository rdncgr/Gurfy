package com.rdncgr.gurfy

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * M4A (MP4) dosyasına iTunes biçiminde etiket yazar: şarkı adı, sanatçı, albüm, tür ve kapak.
 * Sunucuda bu işi mutagen + ffmpeg yapıyordu; telefonda ek kütüphane olmadan doğrudan kutular yazılır.
 *
 * moov kutusundaki eski udta atılır, yenisi eklenir. moov büyüyünce ondan sonra gelen veriye işaret eden
 * mutlak konumlar (stco/co64 ve parçalı dosyalarda tfhd base-data-offset) aynı miktar kaydırılır.
 * YouTube'un M4A sesleri parçalı (fragmented) MP4'tür; ikisi de desteklenir.
 */
object Mp4Tags {
    class Tags(
        val title: String,
        val artist: String,
        val album: String?,
        val genre: String?,
        val cover: ByteArray?,
    )

    private class Box(val type: String, val start: Int, val headerSize: Int, val end: Int)

    private fun readBoxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = ByteBuffer.wrap(b, p + 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) break
            out += Box(type, p, header, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun put32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte(); b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte()
    }

    private fun box(type: ByteArray, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        val out = ByteArrayOutputStream(size)
        out.write(ByteBuffer.allocate(4).putInt(size).array())
        out.write(type)
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun box(type: String, vararg parts: ByteArray) = box(type.toByteArray(Charsets.ISO_8859_1), *parts)

    private fun atom(name: String): ByteArray {
        // "©nam" gibi adlar: © tek bayt (0xA9)
        return if (name.startsWith("©")) byteArrayOf(0xA9.toByte()) + name.substring(1).toByteArray(Charsets.ISO_8859_1)
        else name.toByteArray(Charsets.ISO_8859_1)
    }

    private fun dataItem(name: String, typeCode: Int, payload: ByteArray): ByteArray =
        box(atom(name), box("data", ByteBuffer.allocate(8).putInt(typeCode).putInt(0).array(), payload))

    private fun text(name: String, value: String?) =
        value?.takeIf { it.isNotBlank() }?.let { dataItem(name, 1, it.toByteArray(Charsets.UTF_8)) } ?: ByteArray(0)

    private fun udta(t: Tags): ByteArray {
        val cover = t.cover?.let { c ->
            val code = when {
                c.size > 3 && c[0] == 0xFF.toByte() && c[1] == 0xD8.toByte() -> 13 // JPEG
                c.size > 4 && c[0] == 0x89.toByte() && c[1] == 'P'.code.toByte() -> 14 // PNG
                else -> null
            }
            code?.let { dataItem("covr", it, c) }
        } ?: ByteArray(0)
        val ilst = box(
            "ilst",
            text("©nam", t.title), text("©ART", t.artist), text("aART", t.artist),
            text("©alb", t.album ?: t.title), text("©gen", t.genre), cover,
        )
        // hdlr: tam kutu başlığı + pre_defined + "mdir" + "appl" + 8 bayt ayrılmış + boş ad
        val hdlr = box(
            "hdlr",
            ByteArray(8), "mdirappl".toByteArray(Charsets.ISO_8859_1), ByteArray(8), ByteArray(1),
        )
        val meta = box("meta", ByteArray(4), hdlr, ilst)
        return box("udta", meta)
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf", "mfra")

    /** [from, to) aralığındaki kutularda [after] ve sonrasını gösteren mutlak konumları [delta] kaydırır. */
    private fun shiftOffsets(b: ByteArray, from: Int, to: Int, after: Long, delta: Long) {
        for (bx in readBoxes(b, from, to)) {
            val body = bx.start + bx.headerSize
            when (bx.type) {
                in CONTAINERS -> shiftOffsets(b, body, bx.end, after, delta)
                "stco" -> {
                    val n = u32(b, body + 4).toInt()
                    for (i in 0 until n) {
                        val p = body + 8 + i * 4
                        val v = u32(b, p)
                        if (v >= after) put32(b, p, v + delta)
                    }
                }
                "co64" -> {
                    val n = u32(b, body + 4).toInt()
                    val bb = ByteBuffer.wrap(b)
                    for (i in 0 until n) {
                        val p = body + 8 + i * 8
                        val v = bb.getLong(p)
                        if (v >= after) bb.putLong(p, v + delta)
                    }
                }
                "tfhd" -> {
                    val flags = u32(b, body) and 0xFFFFFF
                    if (flags and 1L != 0L) { // base-data-offset-present
                        val bb = ByteBuffer.wrap(b)
                        val p = body + 8
                        val v = bb.getLong(p)
                        if (v >= after) bb.putLong(p, v + delta)
                    }
                }
                "tfra" -> {
                    // mfra içindeki rastgele erişim tablosu: moof konumları mutlak
                    val version = b[body].toInt()
                    val sizes = u32(b, body + 8)
                    val trafBytes = ((sizes shr 4) and 3).toInt() + 1
                    val trunBytes = ((sizes shr 2) and 3).toInt() + 1
                    val sampleBytes = (sizes and 3).toInt() + 1
                    val n = u32(b, body + 12).toInt()
                    var p = body + 16
                    val bb = ByteBuffer.wrap(b)
                    repeat(n) {
                        p += if (version == 1) 8 else 4 // time
                        if (version == 1) {
                            val v = bb.getLong(p); if (v >= after) bb.putLong(p, v + delta); p += 8
                        } else {
                            val v = u32(b, p); if (v >= after) put32(b, p, v + delta); p += 4
                        }
                        p += trafBytes + trunBytes + sampleBytes
                    }
                }
            }
        }
    }

    /** Etiketleri yazılmış yeni dosya içeriği. MP4 değilse hata verir. */
    fun write(src: ByteArray, t: Tags): ByteArray {
        val top = readBoxes(src, 0, src.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: throw IllegalArgumentException("MP4 dosyası değil (moov yok)")
        val children = readBoxes(src, moov.start + moov.headerSize, moov.end).filter { it.type != "udta" }
        val body = ByteArrayOutputStream()
        children.forEach { body.write(src, it.start, it.end - it.start) }
        body.write(udta(t))
        val newMoov = box("moov", body.toByteArray())
        val delta = (newMoov.size - (moov.end - moov.start)).toLong()

        val out = ByteArray(src.size + delta.toInt())
        System.arraycopy(src, 0, out, 0, moov.start)
        System.arraycopy(newMoov, 0, out, moov.start, newMoov.size)
        System.arraycopy(src, moov.end, out, moov.start + newMoov.size, src.size - moov.end)
        if (delta != 0L) shiftOffsets(out, 0, out.size, moov.end.toLong(), delta)
        return out
    }
}
