package io.github.jnesew.paudiobooks.metadata

import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EmbeddedMetadataTest {
    @Test fun explicitRolesWinRegardlessOfEntryOrder() {
        val entries = listOf(VorbisComment("COMPOSER", "Composer"), VorbisComment("PERFORMER", "Reader"),
            VorbisComment("NARRATOR", "Explicit narrator"), VorbisComment("ARTIST", "Artist"),
            VorbisComment("ALBUMARTIST", "Album artist"), VorbisComment("AUTHOR", "Explicit author"),
            TextInformationFrame("MVNM", null, listOf("Movement")), VorbisComment("SERIES", "Series"))
        for (ordered in listOf(entries, entries.reversed())) {
            val c = EmbeddedMetadataCollector(); ordered.forEach(c::accept)
            val book = c.build()
            assertEquals("Explicit narrator", book.narrator); assertEquals("narrator", book.narratorSource)
            assertEquals("Explicit author", book.author); assertEquals("author", book.authorSource)
            assertEquals("Series", book.series); assertEquals("series", book.seriesSource)
        }
    }
    @Test fun recommendedVorbisReaderAndConventionalFallbacksHaveProvenance() {
        val c = EmbeddedMetadataCollector()
        c.accept(VorbisComment("performer", "Reader")); c.accept(VorbisComment("ALBUM ARTIST", "Author"))
        c.accept(TextInformationFrame("MVNM", null, listOf("Series")))
        c.accept(TextInformationFrame("MVIN", null, listOf("2")))
        assertEquals("Reader", c.build().narrator); assertEquals("performer", c.build().narratorSource)
        assertEquals("Author", c.build().author); assertEquals("albumArtist", c.build().authorSource)
        assertEquals("Series", c.build().series); assertEquals("movement", c.build().seriesSource)
        assertEquals("2", c.build().seriesNumber)
        val composer = EmbeddedMetadataCollector()
        composer.accept(TextInformationFrame("TCOM", null, listOf("Reader")))
        assertEquals("Reader", composer.build().narrator); assertEquals("composer", composer.build().narratorSource)
    }
    @Test fun repeatedAndMultivalueContributorsAreDistinctAndDoNotSplitNames() {
        val c = EmbeddedMetadataCollector()
        c.accept(TextInformationFrame("TPE1", null, listOf("Zulu", "Doe, Jane", "Zulu")))
        c.accept(VorbisComment("ARTIST", "Ánne")); c.accept(VorbisComment("NARRATOR", "Two"))
        c.accept(VorbisComment("NARRATOR", "One")); c.accept(VorbisComment("NARRATOR", "Two"))
        c.accept(TextInformationFrame("TXXX", "TRANSLATOR", listOf("T2", "T1")))
        assertEquals("Doe, Jane, Zulu, Ánne", c.build().author)
        assertEquals("One, Two", c.build().narrator); assertEquals("T1, T2", c.build().translator)
    }
    @Test fun actualRolePairsAreRecognizedWithoutGuessingOtherRoles() {
        val c = EmbeddedMetadataCollector()
        c.accept(TextInformationFrame("TIPL", null, listOf("translator", "Translation", "narrator", "Reader", "producer", "Other", "unpaired")))
        c.accept(InternalFrame("untrusted.example", "NARRATOR", "Other"))
        c.accept(TextInformationFrame("TEXT", null, listOf("Adapted lyrics")))
        assertEquals("Reader", c.build().narrator); assertEquals("Translation", c.build().translator)
        assertNull(c.build().author)
    }
    @Test fun catalogValuesKeepRawDatesAndInertDescription() {
        val c = EmbeddedMetadataCollector()
        listOf("ALBUM" to "Book", "GENRE" to "Fiction", "PUBLISHER" to "Publisher",
            "DATE" to "2020-02", "RELEASEDATE" to "2021-06-10", "DESCRIPTION" to "<b>Text</b>")
            .forEach { (k,v) -> c.accept(VorbisComment(k,v)) }
        val b = c.build()
        assertEquals("Book", b.title); assertEquals("Fiction", b.genre); assertEquals("Publisher", b.publisher)
        assertEquals("2020-02", b.date); assertEquals("2021-06-10", b.releaseDate)
        assertEquals("<b>Text</b>", b.description)
    }
    @Test fun emptyTagsDoNotMaskFallbacksAndAllOutputIsBounded() {
        val c = EmbeddedMetadataCollector()
        c.accept(VorbisComment("NARRATOR", " ")); c.accept(VorbisComment("PERFORMER", "Reader"))
        c.accept(VorbisComment("DESCRIPTION", "D".repeat(20_000)))
        repeat(100) { c.accept(VorbisComment("AUTHOR", "Author $it")) }
        assertEquals("Reader", c.build().narrator)
        assertEquals(4096, c.build().description!!.length); assertTrue(c.build().isBounded())
        assertEquals(32, c.build().author!!.split(", ").size)
    }
    @Test fun bookFieldsAgreeIndependentlyWithMissingAndConflictingTracks() {
        val a = BookMetadata(author="Author", narrator="Reader A", publisher="Publisher")
        val b = BookMetadata(author="Author", narrator="Reader B", genre="Fiction")
        for (tracks in listOf(listOf(BookMetadata(), a, b), listOf(b, a, BookMetadata()))) {
            val agreed = agreedBookMetadata(tracks)
            assertEquals("Author", agreed.author); assertNull(agreed.narrator)
            assertEquals("Fiction", agreed.genre); assertEquals("Publisher", agreed.publisher)
        }
    }
    @Test fun consistentAlbumPrecedesTrackTitlesAndConflictsFallBack() {
        assertEquals("Book", suggestedBookTitle(listOf(BookMetadata(title="Book"),BookMetadata()),listOf("Chapter 1","Chapter 2"),"Folder"))
        assertEquals("Folder", suggestedBookTitle(listOf(BookMetadata(title="One"),BookMetadata(title="Two")),listOf("Chapter 1","Chapter 2"),"Folder"))
        assertEquals("Single title", suggestedBookTitle(listOf(BookMetadata()),listOf("Single title"),"File"))
    }
    @Test fun albumAndTrackTitleHaveSeparateRolesIncludingSingleFileBooks() {
        for (entries in listOf(
            listOf(TextInformationFrame("TIT2",null,listOf("Chapter One")), TextInformationFrame("TALB",null,listOf("The Book"))),
            listOf(VorbisComment("TITLE","Chapter One"), VorbisComment("ALBUM","The Book"))
        )) {
            val collector=EmbeddedMetadataCollector(); entries.forEach(collector::accept)
            assertEquals("The Book",collector.build().title)
            assertEquals("The Book",suggestedBookTitle(listOf(collector.build()),listOf("Chapter One"),"filename"))
        }
    }
    @Test fun missingAndConflictingBookTitlesUseOnlyAnUnambiguousTrackTitleOrSelection() {
        assertEquals("File title",suggestedBookTitle(listOf(BookMetadata()),listOf("File title"),"filename"))
        assertEquals("Folder",suggestedBookTitle(listOf(BookMetadata(),BookMetadata()),listOf("Chapter One","Chapter Two"),"Folder"))
        assertEquals("Folder",suggestedBookTitle(listOf(BookMetadata(title="Book A"),BookMetadata(title="Book B")),listOf(null,null),"Folder"))
    }
    @Test fun legacyBinaryRolePayloadsAreBoundedAndMalformedEncodingIsIgnored() {
        val c = EmbeddedMetadataCollector()
        c.accept(BinaryFrame("IPLS",byteArrayOf(1)+"translator\u0000Translator".toByteArray(Charsets.UTF_16)))
        c.accept(BinaryFrame("MVNM",byteArrayOf(3)+"Movement".toByteArray()))
        c.accept(BinaryFrame("MVIN",byteArrayOf(3)+"3".toByteArray()))
        assertEquals("Translator",c.build().translator); assertEquals("Movement",c.build().series)
        assertEquals("3",c.build().seriesNumber)
        c.accept(BinaryFrame("IPLS",byteArrayOf(99,1,2)))
        c.accept(BinaryFrame("IPLS",ByteArray(20_000)))
        assertEquals("Translator",c.build().translator)
    }
    @Test fun unrelatedMovementNumberDoesNotAttachToAnExplicitSeries() {
        val c = EmbeddedMetadataCollector()
        c.accept(TextInformationFrame("MVIN",null,listOf("3")))
        c.accept(VorbisComment("SERIES","Explicit series"))
        assertNull(c.build().seriesNumber)
    }
}
