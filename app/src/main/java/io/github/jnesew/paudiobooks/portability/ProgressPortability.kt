package io.github.jnesew.paudiobooks.portability

import androidx.room.withTransaction
import io.github.jnesew.paudiobooks.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

/** A match is only a suggestion. The caller must explicitly select each target. */
data class PortabilityEntry(val index: Int, val displayTitle: String, val fingerprint: String, val matchingBookIds: List<String>)
data class PortabilitySelection(val entryIndex: Int, val targetBookId: String, val replaceExisting: Boolean)
class PortabilityPreview internal constructor(val entries: List<PortabilityEntry>, internal val records: List<TransferBook>)
internal data class TransferMark(val track: Int, val offset: Long, val note: String?, val created: Long)
internal data class TransferProgress(val track: Int, val offset: Long, val speed: Float, val finished: Boolean, val updated: Long)
internal data class TransferBook(val title: String, val hashes: List<String>, val sizes: List<Long>, val titleOverride: String?, val author: String?, val narrator: String?, val series: String?, val progress: TransferProgress?, val marks: List<TransferMark>, val translator: String? = null, val seriesNumber: String? = null, val includesNewCredits: Boolean = true)

/** Version 2 adds optional contributor overrides; versions 1/2 carry no locations or grants. */
class ProgressPortability(private val db: LibraryDatabase) {
    companion object { const val MAX_BYTES = 10 * 1024 * 1024; private const val MAX_BOOKS = 1000; private const val MAX_TRACKS = 1000; private const val MAX_MARKS = 10000 }

    suspend fun export(output: OutputStream) = withContext(Dispatchers.IO) {
        // Encode one book at a time and enforce the byte limit before growing the buffer.
        val bytes = java.io.ByteArrayOutputStream()
        fun append(value: String) {
            val part = value.toByteArray(Charsets.UTF_8)
            require(part.size <= MAX_BYTES - bytes.size()) { "Export is too large" }
            bytes.write(part)
        }
        db.withTransaction {
            val books = db.books().limited(MAX_BOOKS + 1)
            require(books.size <= MAX_BOOKS) { "Too many books" }
            append("{\"version\":2,\"books\":[")
            books.forEachIndexed { index, book ->
                if (index != 0) append(",")
                val record = snapshot(book)
                // Keep each temporary JSON allocation small even for many large notes.
                append("{\"title\":" + JSONObject.quote(record.title) + ",\"tracks\":[")
                record.hashes.indices.forEach { i ->
                    if (i != 0) append(",")
                    append(JSONObject().put("sha256", record.hashes[i]).put("size", record.sizes[i]).toString())
                }
                append("],\"overrides\":" + JSONObject().put("title", record.titleOverride ?: JSONObject.NULL)
                    .put("author", record.author ?: JSONObject.NULL).put("narrator", record.narrator ?: JSONObject.NULL)
                    .put("series", record.series ?: JSONObject.NULL)
                    .put("translator", record.translator ?: JSONObject.NULL).put("seriesNumber", record.seriesNumber ?: JSONObject.NULL).toString())
                append(",\"progress\":" + (record.progress?.let { JSONObject().put("track", it.track).put("offset", it.offset)
                    .put("speed", it.speed.toDouble()).put("finished", it.finished).put("updated", it.updated).toString() } ?: "null"))
                append(",\"bookmarks\":[")
                record.marks.forEachIndexed { i, mark ->
                    if (i != 0) append(",")
                    append(JSONObject().put("track", mark.track).put("offset", mark.offset)
                        .put("note", mark.note ?: JSONObject.NULL).put("created", mark.created).toString())
                }
                append("]}")
            }
            append("]}")
        }
        bytes.writeTo(output)
        output.flush()
    }

    suspend fun preview(input: InputStream): PortabilityPreview = withContext(Dispatchers.IO) {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            require(bytes.size() <= MAX_BYTES - n) { "Import is too large" }
            bytes.write(buffer, 0, n)
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        // Reject pathological nesting before handing untrusted input to the recursive JSON parser.
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "Import is too deeply nested" } }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
        val tokener = JSONTokener(text)
        val json = JSONObject(tokener)
        // Check exhaustion separately: next() also returns NUL for an actual NUL byte.
        while (tokener.more()) {
            val character = tokener.next()
            require(character == ' ' || character == '\t' || character == '\r' || character == '\n') {
                "Unexpected data after transfer document"
            }
        }
        val version = json.get("version")
        require(version == 1 || version == 2) { "Unsupported version" }
        val array = json.getJSONArray("books")
        require(array.length() <= MAX_BOOKS)
        val records = (0 until array.length()).map { decode(array.getJSONObject(it), version == 2) }
        val candidates = db.withTransaction { db.books().limited(MAX_BOOKS + 1).also { require(it.size <= MAX_BOOKS) }.map { it.id to fingerprintSnapshot(it) } }
        PortabilityPreview(records.mapIndexed { index, item ->
            val fp = fingerprint(item)
            PortabilityEntry(index, item.title, fp, candidates.filter { it.second == fp }.map { it.first })
        }, records)
    }

    /** Rechecks fingerprints under the transaction, preventing stale previews from applying to changed books. */
    suspend fun apply(preview: PortabilityPreview, selections: List<PortabilitySelection>): Int = db.withTransaction {
        require(selections.map { it.entryIndex }.toSet().size == selections.size)
        require(selections.map { it.targetBookId }.toSet().size == selections.size)
        selections.forEach { selection ->
            val record = preview.records.getOrNull(selection.entryIndex) ?: throw IllegalArgumentException("Unknown entry")
            val book = db.books().get(selection.targetBookId) ?: throw IllegalArgumentException("Book no longer exists")
            require(fingerprintSnapshot(book) == fingerprint(record)) { "Book no longer matches" }
            if (selection.replaceExisting) {
                val tracks = db.library().tracks(book.id)
                val assets = tracks.map { requireNotNull(db.library().asset(it.assetId)) }
                fun validateOffset(track: Int, offset: Long) {
                    assets[track].durationMs?.let { require(offset <= it) { "Position exceeds track duration" } }
                }
                record.progress?.let { validateOffset(it.track, it.offset) }
                record.marks.forEach { validateOffset(it.track, it.offset) }
                db.books().setOverrides(book.id, record.titleOverride, record.author, record.narrator, record.series,
                    if (record.includesNewCredits) record.translator else book.translatorOverride,
                    if (record.includesNewCredits) record.seriesNumber else book.seriesNumberOverride)
                db.library().deleteProgress(book.id)
                record.progress?.let { p -> db.library().saveProgress(ReadingState(book.id, tracks[p.track].id, p.offset, p.speed, p.finished, p.updated)) }
                db.library().deleteBookmarks(book.id)
                record.marks.forEach { m -> db.library().insertBookmark(Bookmark(UUID.randomUUID().toString(), book.id, tracks[m.track].id, m.offset, m.note, m.created)) }
            }
        }
        selections.count { it.replaceExisting }
    }

    private suspend fun snapshot(book: Book): TransferBook {
        val tracks = db.library().limitedTracks(book.id, MAX_TRACKS + 1)
        require(tracks.isNotEmpty() && tracks.size <= MAX_TRACKS)
        val assets = tracks.map { requireNotNull(db.library().asset(it.assetId)) }
        require(assets.all { it.identityVerified }) { "Verify linked audio before transfer" }
        val indices = tracks.mapIndexed { index, track -> track.id to index }.toMap()
        val progress = db.library().progress(book.id)?.let { p -> TransferProgress(requireNotNull(indices[p.trackId]), p.offsetMs, p.speed, p.finished, p.updatedAt) }
        require(db.library().bookmarkTextLength(book.id) <= MAX_BYTES) { "Export is too large" }
        val marks = db.library().limitedBookmarks(book.id, MAX_MARKS + 1).map { m -> TransferMark(requireNotNull(indices[m.trackId]), m.offsetMs, m.note, m.createdAt) }
        require(marks.size <= MAX_MARKS)
        return TransferBook(book.titleOverride ?: book.title, assets.map { it.sha256 }, assets.map { it.sizeBytes }, book.titleOverride, book.authorOverride, book.narratorOverride, book.seriesOverride, progress, marks, book.translatorOverride, book.seriesNumberOverride)
    }
    private suspend fun fingerprintSnapshot(book: Book): String? {
        val tracks = db.library().limitedTracks(book.id, MAX_TRACKS + 1)
        require(tracks.isNotEmpty() && tracks.size <= MAX_TRACKS)
        val assets = tracks.map { requireNotNull(db.library().asset(it.assetId)) }
        if (assets.any { !it.identityVerified }) return null
        return fingerprint(TransferBook("", assets.map { it.sha256 }, assets.map { it.sizeBytes }, null, null, null, null, null, emptyList()))
    }
    private fun fingerprint(book: TransferBook): String {
        val digest = MessageDigest.getInstance("SHA-256")
        book.hashes.indices.forEach { digest.update("${book.hashes[it]}:${book.sizes[it]}\n".toByteArray(Charsets.US_ASCII)) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun decode(o: JSONObject, newCredits: Boolean): TransferBook {
        fun boundedString(obj: JSONObject, key: String, max: Int): String? {
            if (obj.isNull(key)) return null
            val value = obj.get(key); require(value is String && value.length <= max); return value
        }
        fun nonnegative(obj: JSONObject, key: String): Long {
            val value = obj.get(key); require(value is Int || value is Long); return (value as Number).toLong().also { require(it >= 0) }
        }
        val title = requireNotNull(boundedString(o, "title", 512)); require(title.isNotBlank())
        val tracks = o.getJSONArray("tracks"); require(tracks.length() in 1..MAX_TRACKS)
        val hashes = mutableListOf<String>(); val sizes = mutableListOf<Long>()
        repeat(tracks.length()) { val t = tracks.getJSONObject(it); val h = requireNotNull(boundedString(t, "sha256", 64)); require(h.matches(Regex("[0-9a-f]{64}"))); hashes.add(h); sizes.add(nonnegative(t, "size")) }
        val overrides = o.getJSONObject("overrides")
        val titleOverride = boundedString(overrides, "title", 512); require(titleOverride == null || titleOverride.isNotBlank())
        fun track(obj: JSONObject): Int = nonnegative(obj, "track").also { require(it < tracks.length()) }.toInt()
        val progress = if (o.isNull("progress")) null else o.getJSONObject("progress").let { p ->
            val rawSpeed = p.get("speed"); require(rawSpeed is Number)
            val speed = rawSpeed.toFloat(); require(speed.isFinite() && speed >= 0.5f && speed <= 3f)
            val finished = p.get("finished"); require(finished is Boolean)
            TransferProgress(track(p), nonnegative(p, "offset"), speed, finished, nonnegative(p, "updated"))
        }
        val marks = o.getJSONArray("bookmarks"); require(marks.length() <= MAX_MARKS)
        return TransferBook(title, hashes, sizes, titleOverride, boundedString(overrides, "author", 512), boundedString(overrides, "narrator", 512), boundedString(overrides, "series", 512), progress,
            (0 until marks.length()).map { marks.getJSONObject(it).let { m -> TransferMark(track(m), nonnegative(m, "offset"), boundedString(m, "note", 4096), nonnegative(m, "created")) } },
            if (newCredits) boundedString(overrides, "translator", 512) else null,
            if (newCredits) boundedString(overrides, "seriesNumber", 512) else null, newCredits)
    }
}
