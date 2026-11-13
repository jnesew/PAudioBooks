package io.github.jnesew.paudiobooks.metadata

/** Values from a local asset, never a provider URI or source filename. */
data class InspectedAudio(
    val title: String?,
    val bookMetadata: BookMetadata,
    val durationMs: Long?,
    val chapters: List<InspectedChapter>,
    val artworkThumbnail: ByteArray?,
    val provenance: MetadataProvenance = MetadataProvenance.EMBEDDED,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val mimeType: String? = null,
) {
    val author get() = bookMetadata.author
    val narrator get() = bookMetadata.narrator
    val translator get() = bookMetadata.translator
    val series get() = bookMetadata.series
    val seriesNumber get() = bookMetadata.seriesNumber
}
data class InspectedChapter(val startMs: Long, val endMs: Long?, val title: String?)
enum class MetadataProvenance { EMBEDDED }
data class LinkedInspection(val metadata: InspectedAudio?, val sourceBytesRead: Long)
class InspectionException(val reason: Reason, val sourceBytesRead: Long = 0) : Exception(reason.name) {
    enum class Reason { UNAVAILABLE, UNSUPPORTED_OR_CORRUPT, TIMEOUT, PROCESS_DIED }
}
internal object InspectionLimits {
    const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    const val FIELD_CHARS = 4096
    const val CHAPTERS = 10_000
    const val CHAPTER_TEXT_CHARS = 32_768
    const val ART_BYTES = 16 * 1024 * 1024
    const val ART_PIXELS = 16_000_000L
    const val THUMB_SIZE = 512
    const val THUMB_BYTES = 64 * 1024
    const val DEADLINE_MS = 30_000L
    const val DURATION_TOLERANCE_MS = 100L
}

internal fun normalizeField(value: CharSequence?): String? = value?.toString()
    ?.take(InspectionLimits.FIELD_CHARS)?.trim()?.takeIf { it.isNotEmpty() }

/** Reject invalid, hidden and duplicate ranges; file boundaries remain the caller's fallback. */
internal fun normalizeChapters(input: List<RawChapter>, durationMs: Long?): List<InspectedChapter> {
    var textBudget = InspectionLimits.CHAPTER_TEXT_CHARS
    return input.asSequence().filter { !it.hidden && it.startMs >= 0 &&
        (durationMs == null || it.startMs < durationMs) &&
        (it.endMs == null || it.endMs > it.startMs && (durationMs == null || it.endMs <= durationMs || it.endMs - durationMs <= InspectionLimits.DURATION_TOLERANCE_MS)) }
        .sortedBy { it.startMs }.distinctBy { it.startMs }.take(InspectionLimits.CHAPTERS)
        .map { raw ->
            val title = normalizeField(raw.title)?.take(textBudget)?.takeIf { it.isNotEmpty() }
            textBudget -= title?.length ?: 0
            InspectedChapter(raw.startMs, raw.endMs?.let { end -> durationMs?.let { minOf(it, end) } ?: end }, title)
        }.toList().let { chapters ->
            chapters.mapIndexed { index, chapter ->
                val nextStart = chapters.getOrNull(index + 1)?.startMs
                if (nextStart != null && chapter.endMs != null && chapter.endMs > nextStart)
                    chapter.copy(endMs = nextStart)
                else chapter
            }
        }
}
internal data class RawChapter(val startMs: Long, val endMs: Long?, val title: String?, val hidden: Boolean)
