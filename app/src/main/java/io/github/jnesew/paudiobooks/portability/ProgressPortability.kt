package io.github.jnesew.paudiobooks.portability

import androidx.room.withTransaction
import io.github.jnesew.paudiobooks.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
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
    companion object { const val MAX_BYTES = 10 * 1024 * 1024; private const val MAX_BOOKS = TransferJson.MAX_BOOKS; private const val MAX_TRACKS = TransferJson.MAX_TRACKS; private const val MAX_MARKS = TransferJson.MAX_MARKS }

    suspend fun export(output: OutputStream) {
        val bytes = encode()
        withContext(Dispatchers.IO) { output.write(bytes); output.flush() }
    }

    internal suspend fun encode(): ByteArray = withContext(Dispatchers.IO) {
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
        bytes.toByteArray().also { encoded ->
            // Never emit a document that exceeds the reader's aggregate complexity limits.
            TransferJson.read(java.io.ByteArrayInputStream(encoded))
        }
    }

    suspend fun preview(input: InputStream): PortabilityPreview = withContext(Dispatchers.IO) {
        val records = TransferJson.read(input)
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
}
