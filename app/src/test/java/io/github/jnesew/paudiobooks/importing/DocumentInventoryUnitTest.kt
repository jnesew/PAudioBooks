package io.github.jnesew.paudiobooks.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentInventoryUnitTest {
    @Test fun recognizesSupportedAudioExtensionsCaseInsensitively() {
        val supported = listOf(
            "track.mp3", "track.MP3", "track.m4a", "track.M4A",
            "track.m4b", "track.M4B", "track.aac", "track.AAC",
            "track.flac", "track.FLAC", "track.ogg", "track.OGG",
            "track.opus", "track.OPUS", "track.wav", "track.WAV",
            "track.wave", "track.wma", "track.aif", "track.aiff"
        )
        for (name in supported) {
            assertTrue("Expected $name to be recognized as audio", isAudioFile(name, null))
        }
    }

    @Test fun recognizesAudioMimeTypesEvenWithGenericOrUnknownExtensions() {
        assertTrue(isAudioFile("track.bin", "audio/mpeg"))
        assertTrue(isAudioFile("track.xyz", "audio/x-wav"))
        assertTrue(isAudioFile("track.custom", "audio/mp4"))
        assertTrue(isAudioFile("track.dat", "application/ogg"))
        assertTrue(isAudioFile("track.dat", "application/x-flac"))
    }

    @Test fun rejectsNonAudioFilesAndImages() {
        val nonAudio = listOf(
            "cover.jpg", "cover.png", "folder.jpg", "notes.txt",
            "booklet.pdf", "checksums.sfv", "album.nfo", "cue.cue",
            "playlist.m3u", "playlist.m3u8", "metadata.xml", "data.json",
            "image.webp", "scan.bmp", "archive.zip"
        )
        for (name in nonAudio) {
            assertFalse("Expected $name to NOT be recognized as audio", isAudioFile(name, null))
        }
    }

    @Test fun companionCoverRankMatchesApprovedNamesAndPrioritizesRoot() {
        // Root depth (0)
        assertEquals(0, companionCoverRank("cover.jpg", 0))
        assertEquals(0, companionCoverRank("COVER.JPG", 0))
        assertEquals(1, companionCoverRank("cover.jpeg", 0))
        assertEquals(2, companionCoverRank("cover.png", 0))
        assertEquals(3, companionCoverRank("folder.jpg", 0))
        assertEquals(4, companionCoverRank("folder.jpeg", 0))
        assertEquals(5, companionCoverRank("folder.png", 0))

        // Subfolder depth (1) ranks higher (lower priority than root)
        assertEquals(100, companionCoverRank("cover.jpg", 1))
        assertEquals(103, companionCoverRank("folder.jpg", 1))

        // Non-matching image or document names
        assertNull(companionCoverRank("back.jpg", 0))
        assertNull(companionCoverRank("disc.png", 0))
        assertNull(companionCoverRank("notes.txt", 0))
        assertNull(companionCoverRank("track.mp3", 0))
    }

    @Test fun recognizesDiscFolderPatterns() {
        val discFolders = listOf(
            "Disc 1", "disc 2", "DISC 03",
            "CD 1", "cd2", "CD 01",
            "Disk 1", "disk 2",
            "Part 1", "part 2", "PART 03",
            "Vol 1", "Volume 2",
            "1", "2", "01", "12"
        )
        for (name in discFolders) {
            assertTrue("Expected '$name' to be recognized as disc folder", isDiscFolderName(name))
        }

        val nonDiscFolders = listOf(
            "Book A", "Book B", "1984", "The Hobbit",
            "J.R.R. Tolkien", "Audiobooks", "Sci-Fi", "Chapter 1"
        )
        for (name in nonDiscFolders) {
            assertFalse("Expected '$name' to NOT be recognized as disc folder", isDiscFolderName(name))
        }
    }

    @Test fun treeGroupDistinguishesMultiDiscFromDistinctBooks() {
        val discGroups = listOf(
            TreeGroup("Disc 1", emptyList(), null, isDiscSubfolder = isDiscFolderName("Disc 1")),
            TreeGroup("Disc 2", emptyList(), null, isDiscSubfolder = isDiscFolderName("Disc 2"))
        )
        assertTrue("All disc groups must be identified as disc subfolders", discGroups.all { it.isDiscSubfolder })

        val bookGroups = listOf(
            TreeGroup("Book One", emptyList(), null, isDiscSubfolder = isDiscFolderName("Book One")),
            TreeGroup("Book Two", emptyList(), null, isDiscSubfolder = isDiscFolderName("Book Two"))
        )
        assertFalse("Book subfolders must not be identified as disc subfolders", bookGroups.any { it.isDiscSubfolder })
    }

    @Test fun computesTotalExpectedBytesOnlyWhenAllSizesKnownAndValid() {
        assertEquals(7168L, computeTotalExpectedBytes(listOf(1024L, 2048L, 4096L)))
        assertNull(computeTotalExpectedBytes(listOf(1024L, null, 4096L)))
        assertNull(computeTotalExpectedBytes(listOf(1024L, -1L, 4096L)))
    }

    @Test fun multiDiscSubfolderSortingPreservesDiscFirstOrderWithoutInterleavingTrackNumbers() {
        val input = listOf(
            "CD 2" to "02-track.mp3",
            "CD 1" to "01-track.mp3",
            "CD 1" to "02-track.mp3",
            "CD 2" to "01-track.mp3"
        )
        val sorted = input.sortedWith { a, b ->
            compareDocumentOrder(a.first, a.second, b.first, b.second)
        }
        val expected = listOf(
            "CD 1" to "01-track.mp3",
            "CD 1" to "02-track.mp3",
            "CD 2" to "01-track.mp3",
            "CD 2" to "02-track.mp3"
        )
        assertEquals(expected, sorted)
    }

    @Test fun computesTotalExpectedBytesDetectsLongSumOverflowAndFallsBackToNull() {
        assertNull("Long overflow must result in null total expected bytes", computeTotalExpectedBytes(listOf(Long.MAX_VALUE - 10, 20L)))
    }

    @Test fun multiLevelSubfolderSortingOrdersByPathHierarchy() {
        val input = listOf(
            "Series/Book B" to "01-track.mp3",
            "Series/Book A/CD 2" to "01-track.mp3",
            "Series/Book A/CD 1" to "02-track.mp3",
            "Series/Book A/CD 1" to "01-track.mp3"
        )
        val sorted = input.sortedWith { a, b ->
            compareDocumentOrder(a.first, a.second, b.first, b.second)
        }
        val expected = listOf(
            "Series/Book A/CD 1" to "01-track.mp3",
            "Series/Book A/CD 1" to "02-track.mp3",
            "Series/Book A/CD 2" to "01-track.mp3",
            "Series/Book B" to "01-track.mp3"
        )
        assertEquals(expected, sorted)
    }

    @Test fun distinctSiblingFoldersWithIdenticalNamesAreDifferentiatedByPath() {
        val input = listOf(
            "Author 2/Special Edition" to "01-track.mp3",
            "Author 1/Special Edition" to "01-track.mp3"
        )
        val sorted = input.sortedWith { a, b ->
            compareDocumentOrder(a.first, a.second, b.first, b.second)
        }
        assertEquals(listOf("Author 1/Special Edition", "Author 2/Special Edition"), sorted.map { it.first })
    }

    @Test fun sameParentIdenticalDisplayNamesDifferentiatedByOrderKey() {
        data class TestDoc(val subfolder: String, val name: String, val orderKey: String)
        val input = listOf(
            TestDoc("Part 1", "02-track.mp3", "0002"),
            TestDoc("Part 1", "01-track.mp3", "0002"),
            TestDoc("Part 1", "02-track.mp3", "0001"),
            TestDoc("Part 1", "01-track.mp3", "0001")
        )
        val sorted = input.sortedWith { a, b ->
            compareDocumentOrder(a.subfolder, a.name, b.subfolder, b.name, a.orderKey, b.orderKey)
        }
        val expected = listOf(
            TestDoc("Part 1", "01-track.mp3", "0001"),
            TestDoc("Part 1", "02-track.mp3", "0001"),
            TestDoc("Part 1", "01-track.mp3", "0002"),
            TestDoc("Part 1", "02-track.mp3", "0002")
        )
        assertEquals(expected, sorted)
    }
}
