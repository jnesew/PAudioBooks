package io.github.jnesew.paudiobooks.portability

import android.util.JsonReader
import android.util.JsonToken
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction

/** Streaming schema validation: limits apply before retaining each record, including unknown values. */
internal object TransferJson {
    const val MAX_BOOKS = 1000
    const val MAX_TRACKS = 1000
    const val MAX_MARKS = 10000
    private const val MAX_TOKENS = 250_000
    private const val MAX_DEPTH = 32
    // A 4096-character note can use six JSON characters per escaped UTF-16 code unit.
    private const val MAX_STRING_TOKEN = 6 * 4096 + 2

    fun read(input: InputStream): List<TransferBook> {
        val limited = object : FilterInputStream(input) {
            var remaining = ProgressPortability.MAX_BYTES
            override fun read(): Int {
                val value = super.read()
                if (value >= 0) { require(remaining > 0); remaining-- }
                return value
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, minOf(length, remaining + 1))
                require(count != 0 || length == 0) { "Transfer read made no progress" }
                require(count <= remaining) { "Transfer is too large" }
                if (count > 0) remaining -= count
                return count
            }
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = JsonReader(GuardedText(InputStreamReader(limited, decoder)))
        // The caller owns the provider stream and closes it on the bounded transfer worker.
        var version: Long? = null
        var books: List<TransferBook>? = null
        reader.objectFields { name ->
            when (name) {
                "version" -> version = reader.nonnegative().also { require(it == 1L || it == 2L) }
                "books" -> books = reader.array(MAX_BOOKS) { reader.book() }
                else -> reader.skipValue()
            }
        }
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected trailing transfer data" }
        val newCredits = requireNotNull(version) == 2L
        return requireNotNull(books).map { book ->
            if (newCredits) book else book.copy(translator = null, seriesNumber = null, includesNewCredits = false)
        }
    }

    private fun JsonReader.book(): TransferBook {
        var title: String? = null
        var tracks: List<Pair<String, Long>>? = null
        var overrides: Map<String, String?>? = null
        var progress: TransferProgress? = null
        var marks: List<TransferMark>? = null
        objectFields { name ->
            when (name) {
                "title" -> title = text(512).also { require(!it.isNullOrBlank()) }
                "tracks" -> tracks = array(MAX_TRACKS) {
                    var hash: String? = null
                    var size: Long? = null
                    objectFields { field -> when (field) {
                        "sha256" -> hash = text(64)?.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                        "size" -> size = nonnegative()
                        else -> skipValue()
                    } }
                    requireNotNull(hash) to requireNotNull(size)
                }.also { require(it.isNotEmpty()) }
                "overrides" -> {
                    val values = mutableMapOf<String, String?>()
                    objectFields { field ->
                        if (field in setOf("title", "author", "narrator", "series", "translator", "seriesNumber"))
                            values[field] = text(512)
                        else skipValue()
                    }
                    require(values["title"] == null || !values["title"].isNullOrBlank())
                    overrides = values
                }
                "progress" -> progress = if (peek() == JsonToken.NULL) { nextNull(); null } else progress()
                "bookmarks" -> marks = array(MAX_MARKS) { mark() }
                else -> skipValue()
            }
        }
        val audio = requireNotNull(tracks)
        val edits = requireNotNull(overrides)
        fun validTrack(track: Int) { require(track in audio.indices) }
        progress?.let { validTrack(it.track) }
        val bookmarks = requireNotNull(marks).also { list -> list.forEach { validTrack(it.track) } }
        return TransferBook(requireNotNull(title), audio.map { it.first }, audio.map { it.second },
            edits["title"], edits["author"], edits["narrator"], edits["series"], progress, bookmarks,
            edits["translator"], edits["seriesNumber"])
    }

    private fun JsonReader.progress(): TransferProgress {
        var track: Int? = null; var offset: Long? = null; var speed: Float? = null
        var finished: Boolean? = null; var updated: Long? = null
        objectFields { field -> when (field) {
            "track" -> track = trackIndex()
            "offset" -> offset = nonnegative()
            "speed" -> {
                require(peek() == JsonToken.NUMBER)
                speed = nextString().toFloatOrNull()?.also { require(it.isFinite() && it in 0.5f..3f) }
                require(speed != null)
            }
            "finished" -> { require(peek() == JsonToken.BOOLEAN); finished = nextBoolean() }
            "updated" -> updated = nonnegative()
            else -> skipValue()
        } }
        return TransferProgress(requireNotNull(track), requireNotNull(offset), requireNotNull(speed),
            requireNotNull(finished), requireNotNull(updated))
    }

    private fun JsonReader.mark(): TransferMark {
        var track: Int? = null; var offset: Long? = null; var note: String? = null; var created: Long? = null
        objectFields { field -> when (field) {
            "track" -> track = trackIndex()
            "offset" -> offset = nonnegative()
            "note" -> note = text(4096)
            "created" -> created = nonnegative()
            else -> skipValue()
        } }
        return TransferMark(requireNotNull(track), requireNotNull(offset), note, requireNotNull(created))
    }

    private fun JsonReader.trackIndex(): Int = nonnegative().also { require(it < MAX_TRACKS) }.toInt()
    private fun JsonReader.nonnegative(): Long {
        require(peek() == JsonToken.NUMBER)
        val raw = nextString()
        require(raw.matches(Regex("0|[1-9][0-9]*")))
        return requireNotNull(raw.toLongOrNull())
    }
    private fun JsonReader.text(max: Int): String? {
        if (peek() == JsonToken.NULL) { nextNull(); return null }
        require(peek() == JsonToken.STRING)
        return nextString().also { require(it.length <= max) }
    }
    private inline fun JsonReader.objectFields(field: (String) -> Unit) {
        beginObject()
        val seen = mutableSetOf<String>()
        while (hasNext()) {
            val name = nextName()
            require(name.length <= 128 && seen.size < 64 && seen.add(name)) { "Duplicate or excessive fields" }
            field(name)
        }
        endObject()
    }
    private inline fun <T> JsonReader.array(max: Int, value: () -> T): List<T> {
        beginArray()
        val values = ArrayList<T>()
        while (hasNext()) {
            require(values.size < max) { "Too many transfer records" }
            values.add(value())
        }
        endArray()
        return values
    }

    /** Bounds JsonReader's own token buffers and nesting stack, even inside skipped unknown fields. */
    private class GuardedText(private val source: Reader) : Reader() {
        private var depth = 0
        private var tokens = 0
        private var quoted = false
        private var escaped = false
        private var tokenLength = 0
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val count = source.read(buffer, offset, length)
            require(count != 0 || length == 0)
            for (index in offset until offset + count.coerceAtLeast(0)) {
                val ch = buffer[index]
                if (quoted) {
                    require(++tokenLength <= MAX_STRING_TOKEN) { "Transfer string is too long" }
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') { quoted = false; tokenLength = 0 }
                } else when (ch) {
                    '"' -> { quoted = true; tokenLength = 0; tokens++ }
                    '{', '[' -> { require(++depth <= MAX_DEPTH); tokens++; tokenLength = 0 }
                    '}', ']' -> { require(--depth >= 0); tokenLength = 0 }
                    ':', ',' -> { tokens++; tokenLength = 0 }
                    ' ', '\t', '\r', '\n' -> tokenLength = 0
                    else -> { if (tokenLength == 0) tokens++; require(++tokenLength <= 128) }
                }
                require(tokens <= MAX_TOKENS) { "Transfer is too complex" }
            }
            return count
        }
        override fun close() = source.close()
    }
}
