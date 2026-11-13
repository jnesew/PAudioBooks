package io.github.jnesew.paudiobooks.metadata

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class MetadataInspectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun fixture(name: String) = File(context.filesDir, "metadata-fixtures/$name").also { assertTrue("Missing fixture: $name", it.isFile) }

    @Test fun encodedBookFieldsRolesAndContributorsSurviveIsolation() = runBlocking {
        val inspector = MetadataInspector(context)
        for (name in listOf("metadata-html.mp3", "metadata-mdta-moov.m4b", "metadata-v23.mp3", "metadata-v24.mp3", "metadata.flac", "metadata.m4b", "metadata-vorbis.ogg", "metadata-opus.ogg")) {
            val result = inspector.inspect(fixture(name))
            val b = result.bookMetadata
            assertEquals(name, "Shared Book", b.title)
            if (name in listOf("metadata-html.mp3", "metadata-v23.mp3", "metadata-v24.mp3", "metadata.m4b"))
                assertEquals(name, "Chapter One", result.title)
            assertEquals(name, if (name in listOf("metadata-v24.mp3", "metadata-html.mp3", "metadata.flac")) "Doe, Jane, Ánne" else "Doe, Jane", b.author)
            assertEquals(name, if (name in listOf("metadata-v24.mp3", "metadata-html.mp3", "metadata.flac")) "Reader One, Reader Two" else "Reader One", b.narrator)
            assertEquals(name, "Synthetic Press", b.publisher); assertEquals(name, "Fiction", b.genre)
            assertEquals(name, if (name == "metadata-v23.mp3") "2020" else "2020-02", b.date)
            assertEquals(name, "Synthetic description\nSecond line", b.description)
            if (!name.endsWith(".ogg")) {
                assertEquals(name, "Translator One", b.translator)
                assertEquals(name, "Explicit Series", b.series); assertEquals(name, "2.5", b.seriesNumber)
            }
            if (name in listOf("metadata-v24.mp3", "metadata.flac", "metadata.m4b", "metadata-mdta-moov.m4b", "metadata-html.mp3")) assertEquals(name, "2021-06-10", b.releaseDate)
        }
        for (name in listOf("metadata-fallback.mp3", "metadata-movement.m4b")) {
            val b = inspector.inspect(fixture(name)).bookMetadata
            assertEquals(name, "Composer reader", b.narrator); assertEquals(name, "composer", b.narratorSource)
            assertEquals(name, "Movement Series", b.series); assertEquals(name, "7", b.seriesNumber)
            assertEquals(name, "movement", b.seriesSource)
            if (name == "metadata-fallback.mp3") assertEquals("Album fallback",b.author)
        }
    }

    @Test fun unexposedNestedMdtaKeepsUnknownFieldsInsteadOfInventingCredits() = runBlocking {
        val result = MetadataInspector(context).inspect(fixture("metadata-mdta.m4b"))
        assertEquals(BookMetadata(),result.bookMetadata)
        assertTrue(result.durationMs != null && result.durationMs > 11000)
    }

    @Test fun explicitContributorTagsSurviveTheIsolatedDescriptorProtocol() = runBlocking {
        val inspector = MetadataInspector(context)
        for (name in listOf("credits.mp3", "credits.flac")) {
            val result = inspector.inspect(fixture(name))
            assertEquals(name, "Synthetic Author", result.author)
            assertEquals(name, "Synthetic Narrator", result.narrator)
            assertEquals(name, "Synthetic Translator", result.translator)
            assertEquals(name, "Synthetic Series", result.series)
            assertEquals(name, "2.5", result.seriesNumber)
        }
    }

    @Test fun chaptersFromId3QuickTimeAndNero() = runBlocking {
        val inspector = MetadataInspector(context)
        for (name in listOf("chapters.mp3", "chapters-vbr.mp3", "chapters-quicktime.m4b", "chapters-nero.m4b", "chapters-both.m4b", "chapters-conflict.m4b")) {
            val result = inspector.inspect(fixture(name))
            assertEquals(name, listOf(0L, 4000L, 8000L), result.chapters.map { it.startMs })
            assertEquals(name, listOf("First", "Second", "Third"), result.chapters.map { it.title })
            assertTrue(name, result.durationMs != null && result.durationMs in 11_900L..12_100L)
        }
    }

    @Test fun formatsWithoutChaptersExtractDurationAndSucceed() = runBlocking {
        val inspector = MetadataInspector(context)
        for (name in listOf("no-chapters.wav", "no-chapters.flac", "no-chapters.ogg", "no-chapters-opus.ogg", "no-chapters.m4a")) {
            val result = inspector.inspect(fixture(name))
            assertTrue(name, result.chapters.isEmpty())
            assertTrue(name, result.durationMs != null && result.durationMs in 11_900L..12_100L)
        }
    }

    @Test fun unsupportedEncodingRecoversAndAllowsSubsequentInspection() = runBlocking {
        val inspector = MetadataInspector(context)
        try {
            inspector.inspect(fixture("unsupported.xyz"))
            fail("Expected unsupported encoding to fail")
        } catch (e: InspectionException) {
            assertEquals(InspectionException.Reason.UNSUPPORTED_OR_CORRUPT, e.reason)
        }
        val valid = inspector.inspect(fixture("chapters.mp3"))
        assertEquals(3, valid.chapters.size)
    }
    @Test fun embeddedArtworkAndCorruptInput() = runBlocking {
        val inspector = MetadataInspector(context)
        assertNotNull(inspector.inspect(fixture("cover.mp3")).artworkThumbnail)
        assertNull(inspector.inspect(fixture("cover-large.mp3")).artworkThumbnail)
        val malformed = inspector.inspect(fixture("chapters-invalid.mp3"))
        assertEquals(listOf(0L, 8000L), malformed.chapters.map { it.startMs })
        try { inspector.inspect(fixture("truncated.m4b")); fail("Expected corrupt input to fail") }
        catch (e: InspectionException) { assertEquals(InspectionException.Reason.UNSUPPORTED_OR_CORRUPT, e.reason) }
    }
    @Test fun hostileLeafDoesNotKillCallerAndLaterInspectionWorks() = runBlocking {
        val source = fixture("chapters-quicktime.m4b").readBytes()
        val marker = byteArrayOf('s'.code.toByte(), 't'.code.toByte(), 's'.code.toByte(), 'z'.code.toByte())
        val index = (4 until source.size - 4).firstOrNull { i -> marker.indices.all { source[i + it] == marker[it] } }
        assertNotNull("Fixture must contain an stsz leaf", index)
        val i = index!!
        source[i - 4] = 0x7f; source[i - 3] = 0xff.toByte(); source[i - 2] = 0xff.toByte(); source[i - 1] = 0xf0.toByte()
        val hostile = File(context.filesDir, "metadata-fixtures/hostile.m4b")
        hostile.writeBytes(source)
        try {
            val inspector = MetadataInspector(context)
            val started = SystemClock.elapsedRealtime()
            try { inspector.inspect(hostile); fail("Hostile leaf must not be accepted") }
            catch (_: InspectionException) { }
            assertTrue(SystemClock.elapsedRealtime() - started < 40_000)
            assertEquals(3, inspector.inspect(fixture("chapters.mp3")).chapters.size)
        } finally { hostile.delete() }
    }

    @Test fun cancellationAllowsNextInspection() = runBlocking {
        val inspector = MetadataInspector(context)
        repeat(3) {
            val job = launch { inspector.inspect(fixture("chapters-quicktime.m4b")) }
            delay(25)
            job.cancelAndJoin()
            assertEquals(3, inspector.inspect(fixture("chapters.mp3")).chapters.size)
        }
    }

    @Test fun selectedArtworkIsSanitizedAndInvalidImagesRecover() = runBlocking {
        val input = File(context.filesDir, "selected-cover.png")
        val tooLarge = File(context.filesDir, "oversized-cover.bin")
        try {
            val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            try { input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            val inspector = MetadataInspector(context)
            val result = inspector.inspectArtwork(input)
            assertTrue(result.size in 1..InspectionLimits.THUMB_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(result, 0, result.size, bounds)
            assertTrue(bounds.outWidth in 1..InspectionLimits.THUMB_SIZE)
            assertTrue(bounds.outHeight in 1..InspectionLimits.THUMB_SIZE)
            assertEquals("image/jpeg", bounds.outMimeType)

            input.writeBytes(byteArrayOf(1, 2, 3, 4))
            try { inspector.inspectArtwork(input); fail("Corrupt image must fail") }
            catch (failure: InspectionException) { assertEquals(InspectionException.Reason.UNSUPPORTED_OR_CORRUPT, failure.reason) }
            RandomAccessFile(tooLarge, "rw").use { it.setLength(InspectionLimits.ART_BYTES.toLong() + 1) }
            try { inspector.inspectArtwork(tooLarge); fail("Oversized image must fail before parsing") }
            catch (failure: InspectionException) { assertEquals(InspectionException.Reason.UNSUPPORTED_OR_CORRUPT, failure.reason) }
            assertEquals(3, inspector.inspect(fixture("chapters.mp3")).chapters.size)
        } finally { input.delete(); tooLarge.delete() }
    }

}
