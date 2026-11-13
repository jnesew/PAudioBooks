package io.github.jnesew.paudiobooks.playback

import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    @Test fun boundaryMapsToNextTrackAndEndToLast() {
        val spans = listOf(TrackSpan("a", 4000), TrackSpan("b", 8000))
        assertEquals(TrackPosition(1, 0), Timeline.fromBook(spans, 4000))
        assertEquals(TrackPosition(1, 8000), Timeline.fromBook(spans, 12000))
        assertEquals(4000L, Timeline.toBook(spans, TrackPosition(1, 0)))
        assertNull(Timeline.fromBook(spans, 12001))
    }
    @Test fun unknownAndOverflowHaveNoBookTimeline() {
        assertNull(Timeline.total(listOf(TrackSpan("a", null))))
        assertNull(Timeline.total(listOf(TrackSpan("a", Long.MAX_VALUE), TrackSpan("b", 1))))
    }
    @Test fun multiFileOrderAndDifferingDurations() {
        val tracks = listOf(
            TrackSpan("t1", 5_000L),
            TrackSpan("t2", 10_000L),
            TrackSpan("t3", 3_000L)
        )
        assertEquals(18_000L, Timeline.total(tracks))
        assertEquals(TrackPosition(0, 0L), Timeline.fromBook(tracks, 0L))
        assertEquals(TrackPosition(0, 4_999L), Timeline.fromBook(tracks, 4_999L))
        assertEquals(TrackPosition(1, 0L), Timeline.fromBook(tracks, 5_000L))
        assertEquals(TrackPosition(1, 9_999L), Timeline.fromBook(tracks, 14_999L))
        assertEquals(TrackPosition(2, 0L), Timeline.fromBook(tracks, 15_000L))
        assertEquals(TrackPosition(2, 3_000L), Timeline.fromBook(tracks, 18_000L))
        assertNull(Timeline.fromBook(tracks, 18_001L))

        assertEquals(0L, Timeline.toBook(tracks, TrackPosition(0, 0L)))
        assertEquals(4_999L, Timeline.toBook(tracks, TrackPosition(0, 4_999L)))
        assertEquals(5_000L, Timeline.toBook(tracks, TrackPosition(1, 0L)))
        assertEquals(15_000L, Timeline.toBook(tracks, TrackPosition(2, 0L)))
        assertEquals(18_000L, Timeline.toBook(tracks, TrackPosition(2, 3_000L)))
        assertNull(Timeline.toBook(tracks, TrackPosition(2, 3_001L)))
    }
    @Test fun largeDurationExceeding24Hours() {
        // 5 tracks of 6 hours (21,600,000 ms each) = 30 hours = 108,000,000 ms
        val sixHoursMs = 6L * 3600L * 1000L
        val tracks = (1..5).map { TrackSpan("part$it", sixHoursMs) }
        val thirtyHoursMs = 30L * 3600L * 1000L
        assertEquals(thirtyHoursMs, Timeline.total(tracks))

        // Query at 25 hours (which is part 5, offset 1 hour)
        val twentyFiveHoursMs = 25L * 3600L * 1000L
        val oneHourMs = 1L * 3600L * 1000L
        val pos = Timeline.fromBook(tracks, twentyFiveHoursMs)
        assertNotNull(pos)
        assertEquals(TrackPosition(4, oneHourMs), pos)
        assertEquals(twentyFiveHoursMs, Timeline.toBook(tracks, pos!!))
    }
    @Test fun unknownDurationInAnyTrackReturnsNull() {
        val tracks = listOf(
            TrackSpan("t1", 5_000L),
            TrackSpan("t2", null),
            TrackSpan("t3", 3_000L)
        )
        assertNull(Timeline.total(tracks))
        assertEquals(1000L, Timeline.toBook(tracks, TrackPosition(0, 1000L)))
        assertNull(Timeline.toBook(tracks, TrackPosition(1, 1000L)))
        assertNull(Timeline.toBook(tracks, TrackPosition(2, 1000L)))
        assertNull(Timeline.fromBook(tracks, 1000L))
    }
    @Test fun completionRequiresAdvancementAfterSeek() {
        assertFalse(Completion.playedAfterSeek(TrackPosition(0, 10_000), TrackPosition(0, 10_000)))
        assertFalse(Completion.playedAfterSeek(TrackPosition(0, 10_000), TrackPosition(0, 10_010), 10_000))
        assertTrue(Completion.playedAfterSeek(TrackPosition(0, 9_500), TrackPosition(0, 10_010), 10_000))
        assertTrue(Completion.playedAfterSeek(TrackPosition(0, 9_999), TrackPosition(0, 10_000)))
        assertTrue(Completion.playedAfterSeek(TrackPosition(0, 10_000), TrackPosition(1, 0)))
        assertTrue(Completion.playedAfterSeek(null, TrackPosition(0, 10_000)))
        // Seek in track 1 must not be satisfied by earlier track 0 end
        assertFalse(Completion.playedAfterSeek(TrackPosition(1, 5_000), TrackPosition(0, 10_000)))
    }
    @Test fun checkpointUsesEitherClock() {
        val gate = CheckpointGate(); gate.reset(1000, 100)
        assertFalse(gate.due(3999, 3099))
        assertTrue(gate.due(4000, 1000))
        assertTrue(gate.due(2000, 3100))
    }
}

class ChapterTest {
    @Test fun chapterAndTrackIndicesStayDistinctAtBoundaries() {
        val tracks = listOf(TrackSpan("a", 8000), TrackSpan("b", 4000))
        val chapters = listOf(ChapterSpan(0, 0, 4000), ChapterSpan(0, 4000, 8000), ChapterSpan(1, 0, 4000))
        assertTrue(Chapters.validate(chapters, tracks))
        assertEquals(1, Chapters.active(chapters, TrackPosition(0, 4000)))
        assertEquals(2, Chapters.active(chapters, TrackPosition(1, 0)))
    }
    @Test fun rejectsReversedOverlappingAndOutOfRangeChapters() {
        val tracks = listOf(TrackSpan("a", 8000))
        assertFalse(Chapters.validate(listOf(ChapterSpan(0, 4000, 1000)), tracks))
        assertFalse(Chapters.validate(listOf(ChapterSpan(0, 0, 5000), ChapterSpan(0, 4000, 8000)), tracks))
        assertFalse(Chapters.validate(listOf(ChapterSpan(1, 0, 1)), tracks))
    }
    @Test fun exactTrackBoundaryChapterSpans() {
        val tracks = listOf(TrackSpan("t1", 5000), TrackSpan("t2", 10000))
        val chapters = listOf(
            ChapterSpan(0, 0, 5000),
            ChapterSpan(1, 0, 5000),
            ChapterSpan(1, 5000, 10000)
        )
        assertTrue(Chapters.validate(chapters, tracks))
        assertEquals(0, Chapters.active(chapters, TrackPosition(0, 0)))
        assertEquals(0, Chapters.active(chapters, TrackPosition(0, 4999)))
        // Exactly at track 0 duration: chapter 0 has endMs 5000 (exclusive), so active returns null for track 0 at 5000
        assertNull(Chapters.active(chapters, TrackPosition(0, 5000)))
        // At track 1 offset 0: chapter 1 is active
        assertEquals(1, Chapters.active(chapters, TrackPosition(1, 0)))
        assertEquals(2, Chapters.active(chapters, TrackPosition(1, 5000)))
    }
}
