package io.github.jnesew.paudiobooks.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only assertions for the separately executed real DocumentsUI journey. */
@RunWith(AndroidJUnit4::class)
class NativeLinkedStateProbe {
    @Test fun checkNativePickerStateAcrossColdRecovery() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase") in listOf("record","verify"))
        val app=ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val books=listOf("Native linked A","Native linked B").map { title -> app.library.books().all().single { it.title==title } }
        val snapshot=StringBuilder()
        for (book in books) {
            val tracks=app.library.library().tracks(book.id)
            assertEquals(1,tracks.size)
            val asset=app.library.library().asset(tracks.single().assetId)!!
            val source=app.library.sources().source(asset.id)!!
            val root=app.library.sources().root(book.id)!!
            assertEquals("",asset.relativePath); assertEquals("",asset.sha256); assertFalse(asset.identityVerified)
            assertEquals(480044L,asset.sizeBytes); assertEquals(30000L,asset.durationMs)
            assertFalse(File(app.noBackupFilesDir,"audiobooks/media/${asset.id}").exists())
            assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri.toString()==root.grantUri && it.isReadPermission })
            assertEquals(root.grantUri,source.grantUri)
            snapshot.append(book).append(tracks).append(asset).append(source).append(root)
                .append(app.library.library().chapters(tracks.single().id))
                .append(app.library.library().progress(book.id)).append(app.library.library().bookmarks(book.id))
        }
        val first=books.first()
        val state=app.library.library().progress(first.id)!!
        assertTrue(state.offsetMs in 10000L..14000L)
        assertEquals("Retain native linked note",app.library.library().bookmarks(first.id).single().note)
        assertNull(app.library.library().progress(books.last().id))
        val digest=MessageDigest.getInstance("SHA-256").digest(snapshot.toString().toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val prefs=app.getSharedPreferences("synthetic_native_link_probe",0)
        if (InstrumentationRegistry.getArguments().getString("phase")=="record")
            assertTrue(prefs.edit().putString("snapshot",digest).putLong("offset",state.offsetMs).commit())
        else assertEquals("Native books, IDs, chapters, progress, notes or source records changed",prefs.getString("snapshot",null),digest)
        InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {
            putString("nativeLinkedState","two books; shared persisted read access; no private audio; retained note; offset=${state.offsetMs}")
        })
    }
}
