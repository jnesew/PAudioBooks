package io.github.jnesew.paudiobooks.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class NativeGroupingAcceptanceTest {
    @Test fun nativeFolderImportsKeepExplicitGroupsDiscOrderAndSharedDuplicates() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        fun sha(name: String): String = MessageDigest.getInstance("SHA-256")
            .digest(File(app.filesDir, "metadata-fixtures/$name").readBytes()).joinToString("") { "%02x".format(it) }
        val first = sha("chapters.mp3")
        val second = sha("chapters-vbr.mp3")
        val books = app.library.books().observeBooks().first()
        suspend fun tracks(title: String, expected: List<String>): List<String> {
            val book = books.single { it.title == title }
            val tracks = app.library.library().tracks(book.id)
            assertEquals(expected.size, tracks.size)
            assertEquals(expected.indices.toList(), tracks.map { it.position })
            assertNull("Imports must not create progress or autoplay", app.library.library().progress(book.id))
            val assets = tracks.map { requireNotNull(app.library.library().asset(it.assetId)) }
            assertEquals(expected, assets.map { it.sha256 })
            for (asset in assets) assertTrue(app.audioStore.matches(asset.relativePath, asset.sizeBytes, asset.sha256))
            return tracks.map { it.assetId }
        }
        tracks("Native Parent B", listOf(first, second))
        val disc = tracks("Native Disc Book", listOf(first, second, first, second))
        assertEquals("Reuse must not force physical copies", 2, disc.distinct().size)
        assertEquals(disc[0], disc[2])
        assertEquals(disc[1], disc[3])
        assertTrue(app.library.library().jobs().isEmpty())
    }
}
