package io.github.jnesew.paudiobooks.playback

/** Media positions use milliseconds. Unknown durations do not form a book timeline. */
internal data class TrackSpan(val id: String, val durationMs: Long?)
internal data class TrackPosition(val index: Int, val offsetMs: Long)
internal object Timeline {
    fun total(tracks: List<TrackSpan>): Long? {
        var sum = 0L
        for (track in tracks) {
            val duration = track.durationMs ?: return null
            if (duration < 0 || sum > Long.MAX_VALUE - duration) return null
            sum += duration
        }
        return sum.takeIf { tracks.isNotEmpty() }
    }
    fun toBook(tracks: List<TrackSpan>, position: TrackPosition): Long? {
        if (position.index !in tracks.indices || position.offsetMs < 0) return null
        val duration = tracks[position.index].durationMs ?: return null
        if (position.offsetMs > duration) return null
        var sum = 0L
        for (track in tracks.take(position.index)) {
            val d = track.durationMs ?: return null
            if (d < 0 || sum > Long.MAX_VALUE - d) return null
            sum += d
        }
        if (sum > Long.MAX_VALUE - position.offsetMs) return null
        return sum + position.offsetMs
    }
    fun fromBook(tracks: List<TrackSpan>, value: Long): TrackPosition? {
        val total = total(tracks) ?: return null
        if (value !in 0..total) return null
        var rest = value
        tracks.forEachIndexed { index, track ->
            val duration = track.durationMs!!
            if (rest < duration || index == tracks.lastIndex) return TrackPosition(index, rest)
            rest -= duration
        }
        return null
    }
}

/** Uses monotonic elapsed time and media time separately; seeks reset the baseline. */
// Leave room for one 500 ms polling interval at the maximum 3x speed.
internal class CheckpointGate(private val intervalMs: Long = 3_000) {
    private var media = 0L
    private var elapsed = 0L
    fun reset(mediaMs: Long, elapsedMs: Long) { media = mediaMs; elapsed = elapsedMs }
    fun due(mediaMs: Long, elapsedMs: Long): Boolean =
        mediaMs >= media && mediaMs - media >= intervalMs ||
            elapsedMs >= elapsed && elapsedMs - elapsed >= intervalMs
}

internal data class ChapterSpan(val trackIndex: Int, val startMs: Long, val endMs: Long?)
internal object Chapters {
    /** Reject malformed or overlapping input instead of treating chapter index as track index. */
    fun validate(chapters: List<ChapterSpan>, tracks: List<TrackSpan>): Boolean {
        var previous: ChapterSpan? = null
        for (chapter in chapters) {
            val track = tracks.getOrNull(chapter.trackIndex) ?: return false
            if (chapter.startMs < 0 || chapter.endMs?.let { it <= chapter.startMs } == true) return false
            val duration = track.durationMs
            if (duration != null && (chapter.startMs >= duration || (chapter.endMs ?: chapter.startMs) > duration)) return false
            previous?.let {
                if (chapter.trackIndex < it.trackIndex) return false
                if (chapter.trackIndex == it.trackIndex &&
                    (chapter.startMs <= it.startMs || it.endMs?.let { end -> chapter.startMs < end } == true)) return false
            }
            previous = chapter
        }
        return true
    }
    fun active(chapters: List<ChapterSpan>, position: TrackPosition): Int? =
        chapters.indexOfLast { it.trackIndex == position.index && position.offsetMs >= it.startMs &&
            (it.endMs == null || position.offsetMs < it.endMs) }.takeIf { it >= 0 }
}

/** A seek to the end alone is not evidence that the end was played. */
internal object Completion {
    fun playedAfterSeek(seek: TrackPosition?, end: TrackPosition, durationMs: Long? = null): Boolean {
        // The playback clock can overshoot duration slightly when ENDED is delivered.
        val endOffset = durationMs?.takeIf { it >= 0 }?.let { end.offsetMs.coerceAtMost(it) } ?: end.offsetMs
        return seek == null || end.index > seek.index || (end.index == seek.index && endOffset > seek.offsetMs)
    }
}
