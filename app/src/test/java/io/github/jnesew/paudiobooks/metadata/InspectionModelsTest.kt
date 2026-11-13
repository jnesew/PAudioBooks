package io.github.jnesew.paudiobooks.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectionModelsTest {
    @Test fun validatesAndOrdersChapters() {
        val chapters = normalizeChapters(listOf(
            RawChapter(4000, 8000, " Second ", false), RawChapter(0, 4000, "First", false),
            RawChapter(4000, 5000, "duplicate", false), RawChapter(8000, 1000, "invalid", false),
            RawChapter(9000, null, "hidden", true), RawChapter(-1, null, "negative", false),
        ), 12_000)
        assertEquals(listOf(0L, 4000L), chapters.map { it.startMs })
        assertEquals("Second", chapters[1].title)
    }
    @Test fun overlappingEndsAreClampedToNextChapter() {
        val chapters = normalizeChapters(listOf(RawChapter(0, 9000, null, false), RawChapter(4000, null, null, false)), null)
        assertEquals(4000L, chapters.first().endMs)
    }
    @Test fun boundsTextAndChapterCount() {
        assertEquals(InspectionLimits.FIELD_CHARS, normalizeField("a".repeat(5000))!!.length)
        assertNull(normalizeField("  "))
        val chapters = normalizeChapters((0..10_100).map { RawChapter(it.toLong(), null, "x".repeat(100), false) }, null)
        assertEquals(10_000, chapters.size)
        assertEquals(InspectionLimits.CHAPTER_TEXT_CHARS, chapters.sumOf { it.title?.length ?: 0 })
    }
}
