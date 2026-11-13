package io.github.jnesew.paudiobooks.metadata

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.util.Locale

/** Audiobook policy on upstream entries; no container parsing and no inferred name separators. */
@UnstableApi
internal class EmbeddedMetadataCollector {
    private class Values(private val multiple: Boolean = false) {
        private var priority = Int.MAX_VALUE
        private val values = sortedSetOf<String>()
        private val sources = sortedSetOf<String>()
        private var length = 0
        fun add(value: String?, rank: Int = 0, source: String = "explicit") {
            val text = normalizeField(value) ?: return
            if (rank > priority) return
            if (rank < priority) { values.clear(); sources.clear(); length = 0; priority = rank }
            if (text in values) return
            if (values.size >= 32 || length + text.length + values.size * 2 > InspectionLimits.FIELD_CHARS) return
            values.add(text); sources.add(source); length += text.length
        }
        fun value(): String? = if (multiple) values.takeIf { it.isNotEmpty() }?.joinToString(", ") else values.singleOrNull()
        fun source(): String? = sources.takeIf { value() != null }?.joinToString(", ")
    }
    private val title = Values()
    private val author = Values(true)
    private val narrator = Values(true)
    private val translator = Values(true)
    private val series = Values()
    private val seriesNumber = Values()
    private val genre = Values(true)
    private val publisher = Values()
    private val date = Values()
    private val releaseDate = Values()
    private val description = Values()
    private var inspected = 0

    fun accept(entry: Metadata.Entry) {
        if (++inspected > 4096) return
        when (entry) {
            is VorbisComment -> accept(entry.key, listOf(entry.value))
            is BinaryFrame -> if (entry.id in listOf("IPLS", "IPL", "MVNM", "MVIN")) {
                // Media3 leaves these legacy/unofficial text frames as already bounded payloads.
                val values = legacyText(entry.data)
                if (entry.id == "IPLS" || entry.id == "IPL") values.chunked(2).filter { it.size == 2 }.forEach { role(it[0],it[1]) }
                else accept(entry.id, values)
            }
            is InternalFrame -> if (entry.domain == "com.apple.iTunes") accept(entry.description, listOf(entry.text))
            is TextInformationFrame -> when (entry.id) {
                "TXXX", "TXX" -> accept(entry.description, entry.values)
                "TALB", "TAL" -> entry.values.take(32).forEach { title.add(it) }
                "TPE1", "TP1" -> entry.values.take(32).forEach { author.add(it, 10, "artist") }
                "TPE2", "TP2" -> entry.values.take(32).forEach { author.add(it, 20, "albumArtist") }
                "TCOM", "TCM" -> entry.values.take(32).forEach { narrator.add(it, 20, "composer") }
                "TIPL", "IPLS", "IPL" -> entry.values.take(64).chunked(2).filter { it.size == 2 }.forEach { role(it[0], it[1]) }
                "MVNM" -> entry.values.take(32).forEach { series.add(it, 20, "movement") }
                "MVIN" -> entry.values.take(32).forEach { seriesNumber.add(it, 20, "movement") }
                "TPUB", "TPB" -> entry.values.take(32).forEach { publisher.add(it) }
                "TDRC", "TYER", "TYE" -> entry.values.take(32).forEach { date.add(it, if (entry.id == "TDRC") 0 else 10) }
                "TDRL" -> entry.values.take(32).forEach { releaseDate.add(it) }
                // Keep Media3's numeric-ID3-genre decoding while retaining repeated free-text genres.
                "TCON", "TCO" -> entry.values.take(32).forEach { value ->
                    val builder = androidx.media3.common.MediaMetadata.Builder()
                    TextInformationFrame("TCON", null, listOf(value)).populateMediaMetadata(builder)
                    genre.add(builder.build().genre?.toString())
                }
            }
            is CommentFrame -> if (entry.description.isBlank() || entry.description.equals("description", true) || entry.description == entry.text)
                description.add(entry.text, 10)
            is MdtaMetadataEntry -> if (entry.typeIndicator == 1 && entry.value.size <= InspectionLimits.FIELD_CHARS * 4) {
                val key = entry.key.take(128)
                accept(key.removePrefix("com.apple.quicktime."), listOf(entry.value.toString(Charsets.UTF_8)))
            }
        }
    }

    private fun role(role: String, value: String) {
        when (role.trim().lowercase(Locale.ROOT)) {
            "author", "written by" -> author.add(value, 5, "involvedPeople")
            "narrator", "reader", "read by" -> narrator.add(value, 5, "involvedPeople")
            "translator", "translated by" -> translator.add(value, 5, "involvedPeople")
        }
    }

    private fun legacyText(bytes: ByteArray): List<String> {
        if (bytes.size !in 2..(InspectionLimits.FIELD_CHARS * 4 + 1)) return emptyList()
        val encoding = bytes[0].toInt()
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return emptyList()
        }
        if (encoding in 1..2 && (bytes.size - 1) % 2 != 0) return emptyList()
        return String(bytes,1,bytes.size-1,charset).split('\u0000',limit=65).take(64).map { it.removePrefix("\uFEFF") }
    }

    private fun accept(key: String?, values: List<String>) {
        val tag = key?.take(64)?.trim()?.uppercase(Locale.ROOT) ?: return
        for (value in values.take(32)) when (tag) {
            "ALBUM" -> title.add(value)
            "AUTHOR" -> author.add(value, 0, "author")
            "ARTIST" -> author.add(value, 10, "artist")
            "ALBUMARTIST", "ALBUM_ARTIST", "ALBUM ARTIST" -> author.add(value, 20, "albumArtist")
            "NARRATOR" -> narrator.add(value, 0, "narrator")
            "PERFORMER" -> narrator.add(value, 10, "performer")
            "COMPOSER" -> narrator.add(value, 20, "composer")
            "TRANSLATOR" -> translator.add(value, 0, "translator")
            "SERIES" -> series.add(value, 0, "series")
            "SERIESNUMBER", "SERIES-PART", "SERIES_PART", "SERIES_INDEX" -> seriesNumber.add(value)
            "MVNM", "MOVEMENTNAME" -> series.add(value, 20, "movement")
            "MVIN", "MOVEMENTNUMBER" -> seriesNumber.add(value, 20, "movement")
            "GENRE" -> genre.add(value)
            "PUBLISHER", "ORGANIZATION" -> publisher.add(value, if (tag == "PUBLISHER") 0 else 10)
            "DATE", "CREATIONDATE" -> date.add(value)
            "YEAR" -> date.add(value, 10)
            "RELEASEDATE", "RELEASE_DATE" -> releaseDate.add(value)
            "DESCRIPTION" -> description.add(value)
            "COMMENT" -> description.add(value, 10)
        }
    }

    fun build() = BookMetadata(title.value(), author.value(), narrator.value(), translator.value(),
        series.value(), seriesNumber.value().takeUnless { seriesNumber.source() == "movement" && series.source() != "movement" }, genre.value(), publisher.value(), date.value(), releaseDate.value(),
        description.value(), author.source(), narrator.source(), translator.source(), series.source())
}
