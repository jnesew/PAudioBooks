package io.github.jnesew.paudiobooks.importing

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test fun sortsNumberRunsWithoutOverflow() {
        val input = listOf("Part 10", "part 2", "part 999999999999999999999", "Part 1")
        assertEquals(listOf("Part 1", "part 2", "Part 10", "part 999999999999999999999"), input.sortedWith(NaturalOrder))
    }
    @Test fun trackTagsWinOnlyWhenCompleteAndUnique() {
        val tagged = listOf(TrackOrderHint("chapter 2", 2, 1), TrackOrderHint("chapter 10", 1, 2), TrackOrderHint("chapter 1", 1, 1))
        assertEquals(listOf(2, 1, 0), orderTrackIndices(tagged))
        val ambiguous = tagged.map { it.copy(trackNumber = 1) }
        assertEquals(listOf(2, 0, 1), orderTrackIndices(ambiguous))
    }
    @Test fun equalNumericRunsPreserveInputOrder() {
        val input = listOf("chapter 002", "Chapter 2", "Chapter 02")
        assertEquals(input, input.sortedWith(NaturalOrder))
    }
    @Test fun comparesEqualLengthNumbersBeyondLongRange() {
        val input = listOf("part10000000000000000000000000000002.mp3", "part10000000000000000000000000000001.mp3")
        assertEquals(input.reversed(), input.sortedWith(NaturalOrder))
    }
    @Test fun comparesEachNumericRunFromLeftToRight() {
        val input = listOf("disc2-track0.mp3", "disc1-track10.mp3", "disc1-track2.mp3")
        assertEquals(listOf("disc1-track2.mp3", "disc1-track10.mp3", "disc2-track0.mp3"), input.sortedWith(NaturalOrder))
    }
    @Test fun comparesSuffixesAfterEquivalentNumericRuns() {
        val input = listOf("chapter10.m4b", "chapter01.mp3", "chapter1.m4b")
        assertEquals(listOf("chapter1.m4b", "chapter01.mp3", "chapter10.m4b"), input.sortedWith(NaturalOrder))
    }
    @Test fun trackTagsWinWhenDiscTagsAbsentUsingImplicitDiscOne() {
        val hints = listOf(
            TrackOrderHint("03-outro.mp3", null, 1),
            TrackOrderHint("01-intro.mp3", null, 2),
            TrackOrderHint("02-body.mp3", null, 3)
        )
        assertEquals(listOf(0, 1, 2), orderTrackIndices(hints))
    }
    @Test fun unusableOrMissingTrackHintFallsBackEntireSelectionToNaturalOrder() {
        val unusableCases = listOf(
            1 to null,
            1 to 0,
            0 to 2,
            1 to -1,
            -1 to 2,
            1 to 100_001,
            100_001 to 2
        )
        for ((disc, track) in unusableCases) {
            val hints = listOf(
                TrackOrderHint("03-track.mp3", 1, 1),
                TrackOrderHint("01-track.mp3", 1, 2),
                TrackOrderHint("02-track.mp3", disc, track)
            )
            assertEquals("Failed for disc=$disc, track=$track", listOf(1, 2, 0), orderTrackIndices(hints))
        }
    }
    @Test fun duplicateTagsFallBackToNaturalOrderPreservingPickerOrderForTies() {
        val tiedFirst = listOf(
            TrackOrderHint("Part 02.mp3", 1, 1),
            TrackOrderHint("part 2.mp3", 1, 1),
            TrackOrderHint("Part 01.mp3", 1, 2)
        )
        assertEquals(listOf(2, 0, 1), orderTrackIndices(tiedFirst))

        val tiedSecond = listOf(
            TrackOrderHint("part 2.mp3", 1, 1),
            TrackOrderHint("Part 02.mp3", 1, 1),
            TrackOrderHint("Part 01.mp3", 1, 2)
        )
        assertEquals(listOf(2, 0, 1), orderTrackIndices(tiedSecond))
    }

    @Test fun orderTrackIndicesWithoutTagsUsesSubfolderFirstToPreserveDiscOrderWithoutInterleaving() {
        val hints = listOf(
            TrackOrderHint("01-track.mp3", null, null, subfolder = "CD 2"), // idx 0
            TrackOrderHint("01-track.mp3", null, null, subfolder = "CD 1"), // idx 1
            TrackOrderHint("02-track.mp3", null, null, subfolder = "CD 2"), // idx 2
            TrackOrderHint("02-track.mp3", null, null, subfolder = "CD 1")  // idx 3
        )
        // Must sort CD 1/01 (1), CD 1/02 (3), CD 2/01 (0), CD 2/02 (2)
        assertEquals(listOf(1, 3, 0, 2), orderTrackIndices(hints))
    }

    @Test fun orderTrackIndicesWithCompleteUniqueTagsPrefersTagsOverSubfolderOrder() {
        val hints = listOf(
            TrackOrderHint("track.mp3", 2, 1, subfolder = "CD 1"), // disc 2, track 1 -> idx 0
            TrackOrderHint("track.mp3", 1, 1, subfolder = "CD 2")  // disc 1, track 1 -> idx 1
        )
        // Tags indicate idx 1 is Disc 1 and idx 0 is Disc 2, so tag priority wins over folder name
        assertEquals(listOf(1, 0), orderTrackIndices(hints))
    }

    @Test fun orderTrackIndicesWithIncompleteTagsFallsBackToSubfolderFirst() {
        val hints = listOf(
            TrackOrderHint("02-end.mp3", 1, null, subfolder = "CD 1"),      // missing track -> idx 0
            TrackOrderHint("01-start.mp3", 1, 1, subfolder = "CD 1"),       // idx 1
            TrackOrderHint("01-start.mp3", 2, 1, subfolder = "CD 2")        // idx 2
        )
        // Missing track on idx 0 forces fallback to subfolder then filename:
        // CD 1/01-start (1), CD 1/02-end (0), CD 2/01-start (2)
        assertEquals(listOf(1, 0, 2), orderTrackIndices(hints))
    }

    @Test fun orderTrackIndicesWithIdenticalSubfolderDisplayNamesUsesOrderKeyTiebreak() {
        val hints = listOf(
            TrackOrderHint("01-track.mp3", null, null, subfolder = "Part 1", orderKey = "0002"), // idx 0
            TrackOrderHint("01-track.mp3", null, null, subfolder = "Part 1", orderKey = "0001"), // idx 1
            TrackOrderHint("02-track.mp3", null, null, subfolder = "Part 1", orderKey = "0002"), // idx 2
            TrackOrderHint("02-track.mp3", null, null, subfolder = "Part 1", orderKey = "0001")  // idx 3
        )
        // Must sort folder 0001 tracks (1, 3) before folder 0002 tracks (0, 2) without interleaving
        assertEquals(listOf(1, 3, 0, 2), orderTrackIndices(hints))
    }
}
