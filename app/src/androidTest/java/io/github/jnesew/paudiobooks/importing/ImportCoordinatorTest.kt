package io.github.jnesew.paudiobooks.importing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jnesew.paudiobooks.library.LibraryDatabase
import io.github.jnesew.paudiobooks.library.ImportFile
import io.github.jnesew.paudiobooks.library.ImportJob
import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.AudioAsset
import io.github.jnesew.paudiobooks.library.BookTrack
import java.util.UUID
import io.github.jnesew.paudiobooks.library.LibraryRepository
import io.github.jnesew.paudiobooks.storage.PrivateAudioStore
import io.github.jnesew.paudiobooks.storage.SourceReadTimeoutException
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportCoordinatorTest {
    @Test fun blockingProviderCancellationDoesNotBlockMainThread() = runBlocking<Unit> {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                DocumentInventory(resolver, 250).describe(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/blocking-cancel"))
            }
            fail("Provider should time out")
        } catch (_: java.io.IOException) { }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue("Main thread blocked during provider cancellation: $elapsed ms", elapsed < 1500)
        delay(2600) // The finite synthetic provider finishes; the shared worker is reusable.
    }

    @Test fun blockingOpenCancellationDoesNotBlockMainThread() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivateAudioStore(context, 250)
        val id = UUID.randomUUID().toString()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                store.copy(context.contentResolver, Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/blocking-open-cancel"), id)
            }
            fail("Source should time out")
        } catch (_: SourceReadTimeoutException) { }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue("Main thread blocked during source cancellation: $elapsed ms", elapsed < 1500)
        assertFalse(store.stagedFile(id).exists())
        assertTrue("Reader worker must return to idle via bounded barrier", PrivateAudioStore.awaitReaderIdle(5000L))
    }

    @Test fun folderInventoryTimesOutAndRecovers() = runBlocking<Unit> {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val root = "content://io.github.jnesew.paudiobooks.test.fixture/tree/"
        try {
            DocumentInventory(resolver, 200).tree(Uri.parse(root + "slow"))
            fail("Slow inventory must time out")
        } catch (_: java.io.IOException) { }
        val files = withTimeout(3000) { DocumentInventory(resolver).tree(Uri.parse(root + "normal")) }
        assertEquals("Book.mp3", files.single().name)
        try {
            DocumentInventory(resolver, 200).describe(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/slow-file"))
            fail("Slow description must time out")
        } catch (_: java.io.IOException) { }
        val description = withTimeout(3000) { DocumentInventory(resolver).describe(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book")) }
        assertEquals("Book.mp3", description.name)
        assertEquals(150_000L, description.sizeBytes)
    }

    @Test fun coverOverridesReplaceResetAndPreserveCommittedReferences() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)
            val source = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book")
            val preview = coordinator.prepare(listOf(SelectedDocument(source, "book", null)))
            val id = coordinator.commit(preview, listOf(ImportTrack(preview.files.single(), ImportMetadata(null, null, 1000, emptyList()))), "Book")
            val oversized = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/oversized")
            try { coordinator.setCoverOverride(id, oversized) { fail("Oversized source was decoded"); byteArrayOf(1) }; fail("Oversized source accepted") }
            catch (_: java.io.IOException) { }
            assertTrue(coordinator.interruptedJobs().isEmpty())
            assertTrue(coordinator.setCoverOverride(id, source) { byteArrayOf(1, 2, 3) })
            val firstPath = db.books().get(id)!!.coverOverridePath!!
            val first = store.resolveCover(firstPath)!!
            assertTrue(coordinator.setCoverOverride(id, source) { byteArrayOf(4, 5) })
            assertFalse(first.exists())
            val second = store.resolveCover(db.books().get(id)!!.coverOverridePath!!)!!
            try { coordinator.setCoverOverride(id, source) { throw java.io.IOException("Invalid image") }; fail("Invalid image accepted") }
            catch (_: java.io.IOException) { }
            assertTrue(second.exists())
            assertTrue(coordinator.interruptedJobs().isEmpty())
            assertTrue(coordinator.resetCoverOverride(id))
            assertNull(db.books().get(id)!!.coverOverridePath)
            assertFalse(second.exists())
            assertTrue(coordinator.setCoverOverride(id, source) { byteArrayOf(6) })
            val last = store.resolveCover(db.books().get(id)!!.coverOverridePath!!)!!
            assertTrue(coordinator.removeBook(id))
            assertFalse(last.exists())
            assertNotNull(context.contentResolver.openInputStream(source)?.use { it.read() })
        } finally { db.close() }
    }

    @Test fun blockedProviderTimesOutAndCleansJournal() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val coordinator = ImportCoordinator(db, PrivateAudioStore(context, 300), context.contentResolver)
            try {
                coordinator.prepare(listOf(SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/slow"), "slow", null)))
                fail("Expected source timeout")
            } catch (_: SourceReadTimeoutException) { }
            assertTrue(coordinator.interruptedJobs().isEmpty())
        } finally {
            db.close()
            assertTrue("Reader worker must return to idle via bounded barrier", PrivateAudioStore.awaitReaderIdle(5000L))
        }
    }

    @Test fun cancellationOfBlockedProviderReturnsAndAllowsCleanup() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val coordinator = ImportCoordinator(db, PrivateAudioStore(context), context.contentResolver)
            val task = launch { coordinator.prepare(listOf(SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/slow"), "slow", null))) }
            delay(500)
            withTimeout(3000) { task.cancelAndJoin() }
            assertTrue(coordinator.interruptedJobs().isEmpty())
        } finally {
            db.close()
            assertTrue("Reader worker must return to idle via bounded barrier", PrivateAudioStore.awaitReaderIdle(5000L))
        }
    }

    @Test fun deniedUnknownSizeAndInsufficientSpaceRecoverCleanly() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val coordinator = ImportCoordinator(db, PrivateAudioStore(context), context.contentResolver)
            val root = "content://io.github.jnesew.paudiobooks.test.fixture/"
            try {
                coordinator.prepare(listOf(SelectedDocument(Uri.parse(root + "denied"), "denied", null)))
                fail("Denied source must fail")
            } catch (_: SecurityException) { }
            assertTrue(coordinator.interruptedJobs().isEmpty())
            try {
                coordinator.prepare(listOf(SelectedDocument(Uri.parse(root + "book"), "book", Long.MAX_VALUE)))
                fail("Insufficient space must fail")
            } catch (_: java.io.IOException) { }
            assertTrue(coordinator.interruptedJobs().isEmpty())
            val preview = coordinator.prepare(listOf(SelectedDocument(Uri.parse(root + "book"), "book", null)))
            assertEquals(150_000L, preview.files.single().copy.sizeBytes)
            coordinator.cancel(preview.jobId)
            assertTrue(coordinator.interruptedJobs().isEmpty())
        } finally { db.close() }
    }

    @Test fun interruptedJournalCleanupRemovesUncommittedFiles() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)
            assertNull(store.resolve("media/../outside"))
            assertNull(store.resolve("file:///etc/passwd"))
            val jobId = UUID.randomUUID().toString()
            val assetId = UUID.randomUUID().toString()
            db.library().insertJob(ImportJob(jobId, "COPYING", 0))
            db.library().insertImportFile(ImportFile(assetId, jobId, "STAGED"))
            val staged = store.stagedFile(assetId)
            staged.parentFile!!.mkdirs()
            staged.writeText("unfinished")
            assertEquals(listOf(jobId), coordinator.interruptedJobs().map { it.id })
            coordinator.cancel(jobId)
            assertFalse(staged.exists())
            assertTrue(coordinator.interruptedJobs().isEmpty())
        } finally { db.close() }
    }

    @Test fun copiesWithoutChangingSourceRejectsDuplicateAndRemovesOnlyOwnedCopy() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = ByteArray(150_000) { (it % 251).toByte() }
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, resolver)
            val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book", expected.size.toLong())
            val preview = coordinator.prepare(listOf(document))
            assertEquals(expected.size.toLong(), preview.files.single().copy.sizeBytes)
            val bookId = coordinator.commit(preview, listOf(ImportTrack(preview.files.single(), ImportMetadata("Book", null, 1000, emptyList()))), "Book")
            val repo = LibraryRepository(db)
            assertEquals(150_000L, repo.audioStorageBytes())
            val playable = repo.playableBook(bookId)!!
            val file = store.resolve(playable.tracks.single().relativePath)!!
            assertArrayEquals(expected, file.readBytes())
            assertTrue(repo.saveProgress(bookId, playable.tracks.single().id, 500, 1f))
            assertFalse(repo.saveProgress(bookId, "wrong", 500, 1f))
            val bookmark = repo.addBookmark(bookId, playable.tracks.single().id, 450, "note")
            assertNotNull(bookmark)
            assertTrue(repo.updateOverrides(bookId, "Edited", "Writer", null, null))
            val edited = repo.playableBook(bookId)!!
            assertEquals("Edited", edited.title)
            assertEquals("Writer", edited.author)
            assertEquals(500L, edited.progress!!.offsetMs)
            val summary = repo.summaries.first().single()
            assertEquals(1000L, summary.durationMs)
            assertEquals(500L, summary.positionMs)
            assertEquals(1, db.library().bookmarks(bookId).size)
            assertTrue(repo.setFinished(bookId, true))
            assertEquals(1000L, db.library().progress(bookId)!!.offsetMs)
            assertTrue(db.library().progress(bookId)!!.finished)
            assertTrue(repo.setFinished(bookId, false))
            assertEquals(0L, db.library().progress(bookId)!!.offsetMs)
            assertFalse(db.library().progress(bookId)!!.finished)
            assertEquals(1, db.library().bookmarks(bookId).size)
            assertEquals("Edited", db.books().get(bookId)!!.titleOverride)
            val second = coordinator.prepare(listOf(document))
            assertEquals(1, second.files.single().duplicateAssetIds.size)
            try {
                coordinator.commit(second, listOf(ImportTrack(second.files.single(), ImportMetadata(null, null, null, emptyList()))), "Duplicate")
                fail("Duplicate must require explicit confirmation")
            } catch (_: DuplicateImportException) { }
            coordinator.cancel(second.jobId)
            assertTrue(coordinator.removeBook(bookId))
            assertNull(repo.playableBook(bookId))
            assertEquals(0L, repo.audioStorageBytes())
            assertFalse(file.exists())
            assertArrayEquals(expected, resolver.openInputStream(document.uri)!!.use { it.readBytes() })
        } finally { db.close() }
    }

    @Test fun midWriteFailureCleansStagingAndPreservesCommittedReferences() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)
            // Establish an existing committed book with real audio asset.
            val existingDoc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book", 150_000L)
            val existingPreview = coordinator.prepare(listOf(existingDoc))
            val existingBookId = coordinator.commit(existingPreview, listOf(ImportTrack(existingPreview.files.single(), ImportMetadata("Existing Book", null, 1000, emptyList()))), "Existing Book")
            val existingAssetId = existingPreview.files.single().copy.assetId
            val existingFile = store.resolve(store.relativePath(existingAssetId))
            assertNotNull(existingFile)
            assertTrue(existingFile!!.isFile)

            // Now attempt an import that encounters a mid-write stream fault.
            val faultUri = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/mid-write-fault")
            val faultDoc = SelectedDocument(faultUri, "fault.mp3", 150_000L)
            try {
                coordinator.prepare(listOf(faultDoc))
                fail("Expected mid-write fault to fail")
            } catch (_: java.io.IOException) { }

            // Verify journal is clean, no uncommitted jobs remain.
            assertTrue(coordinator.interruptedJobs().isEmpty())
            // Existing committed file and book remain completely intact.
            assertTrue(existingFile.isFile)
            val repo = LibraryRepository(db)
            assertNotNull(repo.playableBook(existingBookId))
            assertEquals(150_000L, repo.audioStorageBytes())

            // Clean up existing book
            assertTrue(coordinator.removeBook(existingBookId))
            assertFalse(existingFile.exists())
        } finally { db.close() }
    }

    @Test fun interruptedCommitReconcilesMovedAssetsWithoutLosingCommittedRefs() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)

            // Existing committed book.
            val healthyDoc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "healthy", 150_000L)
            val healthyPreview = coordinator.prepare(listOf(healthyDoc))
            val healthyBookId = coordinator.commit(healthyPreview, listOf(ImportTrack(healthyPreview.files.single(), ImportMetadata("Healthy Book", null, 1000, emptyList()))), "Healthy Book")
            val healthyAssetId = healthyPreview.files.single().copy.assetId
            val healthyFile = store.resolve(store.relativePath(healthyAssetId))!!
            assertTrue(healthyFile.isFile)

            // Simulate interrupted commit from process death:
            // An import job whose first file was moved to media/, second file still in staging/,
            // and an orphaned file in staging/ with no job.
            val interruptedJobId = UUID.randomUUID().toString()
            val movedAssetId = UUID.randomUUID().toString()
            val stagedAssetId = UUID.randomUUID().toString()
            val orphanAssetId = UUID.randomUUID().toString()

            db.library().insertJob(ImportJob(interruptedJobId, "COPYING", System.currentTimeMillis()))
            db.library().insertImportFile(ImportFile(movedAssetId, interruptedJobId, "STAGED"))
            db.library().insertImportFile(ImportFile(stagedAssetId, interruptedJobId, "COPYING"))

            val movedFile = File(healthyFile.parentFile, movedAssetId)
            movedFile.writeText("uncommitted moved audio")
            val stagedFile = store.stagedFile(stagedAssetId)
            stagedFile.parentFile!!.mkdirs()
            stagedFile.writeText("uncommitted staged audio")
            val orphanFile = store.stagedFile(orphanAssetId)
            orphanFile.writeText("orphaned staged audio")

            assertTrue(movedFile.exists())
            assertTrue(stagedFile.exists())
            assertTrue(orphanFile.exists())

            // Reconcile
            val reconciled = coordinator.reconcileInterrupted()
            assertEquals(listOf(interruptedJobId), reconciled)
            assertTrue(coordinator.interruptedJobs().isEmpty())

            // Uncommitted and orphan files are cleaned
            assertFalse(movedFile.exists())
            assertFalse(stagedFile.exists())
            assertFalse(orphanFile.exists())

            // Healthy committed asset and book remain untouched
            assertTrue(healthyFile.exists())
            val repo = LibraryRepository(db)
            assertNotNull(repo.playableBook(healthyBookId))
            assertEquals(150_000L, repo.audioStorageBytes())

            assertTrue(coordinator.removeBook(healthyBookId))
            assertFalse(healthyFile.exists())
        } finally { db.close() }
    }

    @Test fun mislabeledMimeAndEjectedSourceRecoverCleanly() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)

            // Mislabeled MIME (application/octet-stream) describes and copies successfully
            val inventory = DocumentInventory(context.contentResolver)
            val mislabeledUri = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/mislabeled")
            val description = inventory.describe(mislabeledUri)
            assertEquals("Book.mp3", description.name)
            val preview = coordinator.prepare(listOf(description))
            assertEquals(150_000L, preview.files.single().copy.sizeBytes)
            coordinator.cancel(preview.jobId)
            assertTrue(coordinator.interruptedJobs().isEmpty())

            // Ejected source fails and cleans up
            val ejectedUri = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/ejected")
            try {
                coordinator.prepare(listOf(SelectedDocument(ejectedUri, "ejected.mp3", null)))
                fail("Ejected source must fail")
            } catch (_: java.io.IOException) { }
            assertTrue(coordinator.interruptedJobs().isEmpty())
        } finally { db.close() }
    }

    @Test fun destinationEnospcWriteFailureCleansStagingAndPreservesCommittedReferences() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)

            // Establish an existing healthy committed book and asset
            val existingDoc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "existing.mp3", 150_000L)
            val existingPreview = coordinator.prepare(listOf(existingDoc))
            val existingBookId = coordinator.commit(existingPreview, listOf(ImportTrack(existingPreview.files.single(), ImportMetadata("Existing", null, 1000, emptyList()))), "Existing")
            val existingAssetId = existingPreview.files.single().copy.assetId
            val existingFile = store.resolve(store.relativePath(existingAssetId))!!
            assertTrue(existingFile.isFile)

            // Inject destination ENOSPC write failure after 8192 bytes prefix
            val faultStore = PrivateAudioStore(context, destinationStreamFactory = { file -> FaultInjectingFileOutputStream(file, 8192L) })
            val faultCoordinator = ImportCoordinator(db, faultStore, context.contentResolver)
            val incomingDoc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "incoming.mp3", 150_000L)
            try {
                faultCoordinator.prepare(listOf(incomingDoc))
                fail("Expected destination ENOSPC write failure")
            } catch (enospc: java.io.IOException) {
                assertTrue("Expected ENOSPC message, got: ${enospc.message}", enospc.message?.contains("ENOSPC") == true)
            }

            // Verify journal and staging cleaned up: no open jobs, no leftover staging files
            assertTrue(coordinator.interruptedJobs().isEmpty())
            val stagingDir = File(context.noBackupFilesDir, "audiobooks/staging")
            val stagingFiles = stagingDir.listFiles()?.filter { it.isFile } ?: emptyList()
            assertTrue("Staging must have no leftover files, found: $stagingFiles", stagingFiles.isEmpty())

            // Existing committed book and asset remain intact and untouched
            assertTrue(existingFile.isFile)
            val repo = LibraryRepository(db)
            assertNotNull(repo.playableBook(existingBookId))
            assertEquals(150_000L, repo.audioStorageBytes())

            // Verify subsequent import with healthy store succeeds cleanly
            val retryPreview = coordinator.prepare(listOf(incomingDoc))
            assertEquals(150_000L, retryPreview.files.single().copy.sizeBytes)
            coordinator.cancel(retryPreview.jobId)
            assertTrue(coordinator.interruptedJobs().isEmpty())

            assertTrue(coordinator.removeBook(existingBookId))
            assertFalse(existingFile.exists())
        } finally { db.close() }
    }

    @Test fun explicitDiscardInterruptedReconcilesOrphansAndStaleJobs() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)

            // Healthy committed book
            val healthyDoc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "healthy", 150_000L)
            val healthyPreview = coordinator.prepare(listOf(healthyDoc))
            val healthyBookId = coordinator.commit(healthyPreview, listOf(ImportTrack(healthyPreview.files.single(), ImportMetadata("Healthy", null, 1000, emptyList()))), "Healthy")
            val healthyAssetId = healthyPreview.files.single().copy.assetId
            val healthyFile = store.resolve(store.relativePath(healthyAssetId))!!
            assertTrue(healthyFile.isFile)

            // Seed interrupted job with stale staged file and rogue orphaned staging file
            val jobId = UUID.randomUUID().toString()
            val stagedId = UUID.randomUUID().toString()
            val orphanId = UUID.randomUUID().toString()
            db.library().insertJob(ImportJob(jobId, "COPYING", System.currentTimeMillis()))
            db.library().insertImportFile(ImportFile(stagedId, jobId, "STAGED"))

            val staged = store.stagedFile(stagedId)
            staged.parentFile?.mkdirs()
            staged.writeText("stale staged audio")
            val orphan = store.stagedFile(orphanId)
            orphan.writeText("rogue orphan audio")
            assertTrue(staged.exists())
            assertTrue(orphan.exists())

            // Reconcile via coordinator (same method invoked by LibraryViewModel.discardInterrupted)
            val reconciled = coordinator.reconcileInterrupted(listOf(jobId))
            assertEquals(listOf(jobId), reconciled)
            assertTrue(coordinator.interruptedJobs().isEmpty())
            assertFalse(staged.exists())
            assertFalse(orphan.exists())

            // Healthy file and book untouched
            assertTrue(healthyFile.exists())
            val repo = LibraryRepository(db)
            assertNotNull(repo.playableBook(healthyBookId))

            assertTrue(coordinator.removeBook(healthyBookId))
            assertFalse(healthyFile.exists())
        } finally { db.close() }
    }

    @Test fun destinationEnospcDuringCoverOverrideRecoversCleanly() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)

            val doc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book", 150_000L)
            val preview = coordinator.prepare(listOf(doc))
            val bookId = coordinator.commit(preview, listOf(ImportTrack(preview.files.single(), ImportMetadata("Book", null, 1000, emptyList()))), "Book")

            // First set a valid cover
            assertTrue(coordinator.setCoverOverride(bookId, doc.uri) { byteArrayOf(1, 2, 3) })
            val originalCoverPath = db.books().get(bookId)!!.coverOverridePath!!
            val originalCoverFile = store.resolveCover(originalCoverPath)!!
            assertTrue(originalCoverFile.isFile)

            // Attempt to replace cover with injected ENOSPC write failure specifically during cover writing
            // (copying the image staging file succeeds, but writing the cover destination fails via write(ByteArray))
            val faultStore = PrivateAudioStore(context, destinationStreamFactory = { file ->
                if (file.parentFile?.name == "covers") FaultInjectingFileOutputStream(file, 2L)
                else java.io.FileOutputStream(file)
            })
            val faultCoordinator = ImportCoordinator(db, faultStore, context.contentResolver)
            try {
                faultCoordinator.setCoverOverride(bookId, doc.uri) { byteArrayOf(4, 5, 6, 7) }
                fail("Expected ENOSPC during cover override")
            } catch (e: java.io.IOException) {
                assertTrue("Expected ENOSPC message, got: ${e.message}", e.message?.contains("ENOSPC") == true)
            }

            // Original cover override and committed book remain completely intact
            assertTrue(originalCoverFile.isFile)
            assertEquals(originalCoverPath, db.books().get(bookId)!!.coverOverridePath)
            assertTrue(coordinator.interruptedJobs().isEmpty())

            // Clean up with healthy coordinator
            assertTrue(coordinator.removeBook(bookId))
            assertFalse(originalCoverFile.exists())
        } finally { db.close() }
    }

    @Test fun newerActiveJobSurvivesStaleJobReconciliation() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)

            // Seed an older stale job
            val staleJobId = UUID.randomUUID().toString()
            val staleAssetId = UUID.randomUUID().toString()
            db.library().insertJob(ImportJob(staleJobId, "COPYING", System.currentTimeMillis() - 10_000))
            db.library().insertImportFile(ImportFile(staleAssetId, staleJobId, "STAGED"))
            val staleStaged = store.stagedFile(staleAssetId)
            stagedParentCheck(staleStaged)
            staleStaged.writeText("stale staged content")

            // Now a newer import prepares
            val activeDoc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "active.mp3", 150_000L)
            val activePreview = coordinator.prepare(listOf(activeDoc))
            val activeAssetId = activePreview.files.single().copy.assetId
            val activeStaged = store.stagedFile(activeAssetId)
            assertTrue("Active staged file must exist", activeStaged.isFile)

            // Explicitly discard only the stale job snapshot
            val reconciled = coordinator.reconcileInterrupted(listOf(staleJobId))
            assertEquals(listOf(staleJobId), reconciled)

            // Stale file is cleaned, but active job and its staged file remain intact
            assertFalse("Stale staged file must be removed", staleStaged.exists())
            assertTrue("Active staged file must survive stale job reconciliation", activeStaged.isFile)
            assertEquals("Active job must remain in database", listOf(activePreview.jobId), coordinator.interruptedJobs().map { it.id })

            // Active job successfully commits
            val bookId = coordinator.commit(activePreview, listOf(ImportTrack(activePreview.files.single(), ImportMetadata("Active Book", null, 1000, emptyList()))), "Active Book")
            val repo = LibraryRepository(db)
            assertNotNull(repo.playableBook(bookId))
            assertTrue(coordinator.interruptedJobs().isEmpty())

            assertTrue(coordinator.removeBook(bookId))
        } finally { db.close() }
    }

    @Test fun folderImportWithCompanionCoverAndNonAudioFilesPreservesOrderAndOriginals() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)
            val inventory = DocumentInventory(context.contentResolver)

            val treeUri = DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.fixture", "companion-folder")

            // 1. Inventory enumeration: treeInventory separates audio from companion cover and ignores non-audio companion (notes.txt)
            val tree = inventory.treeInventory(treeUri)
            assertEquals(2, tree.audioFiles.size)
            // Verify natural ordering: "2-track.wav" comes before "10-track.mp3"
            assertEquals("2-track.wav", tree.audioFiles[0].name)
            assertEquals("10-track.mp3", tree.audioFiles[1].name)
            // Verify companion cover recognized
            assertNotNull(tree.companionCover)
            assertEquals("cover.jpg", tree.companionCover!!.name)

            // Verify tree() convenience helper returns only audio tracks
            val audioOnly = inventory.tree(treeUri)
            assertEquals(listOf("2-track.wav", "10-track.mp3"), audioOnly.map { it.name })

            // 2. Prepare audio import with progress tracking
            var progressCount = 0
            val preview = coordinator.prepare(tree.audioFiles) { progress ->
                progressCount++
                assertTrue(progress.completedFiles in 0..2)
            }
            assertTrue("Progress callbacks must have fired", progressCount > 0)
            assertEquals(2, preview.files.size)

            // Verify distinct SHA-256 fingerprints
            val hash0 = preview.files[0].copy.sha256
            val hash1 = preview.files[1].copy.sha256
            assertNotEquals("Differently generated MP3 and WAV files must have different fingerprints", hash0, hash1)
            assertEquals(300000L, preview.files[0].copy.sizeBytes)
            assertEquals(200000L, preview.files[1].copy.sizeBytes)

            // 3. Companion cover staging, inspection and sanitization via production helper
            val sanitizedThumb = coordinator.stageAndInspectCover(tree.companionCover!!.uri) { stagedCoverFile ->
                assertTrue("Staged companion cover must exist during inspection", stagedCoverFile.isFile)
                val rawBytes = stagedCoverFile.readBytes()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                assertNotNull("Companion cover must be decodable", bmp)
                val thumb = java.io.ByteArrayOutputStream().use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                    out.toByteArray()
                }
                bmp.recycle()
                assertTrue("Sanitized thumbnail must be within 64KB", thumb.size <= 64 * 1024)
                thumb
            }
            assertNotNull("Sanitized thumbnail must be returned", sanitizedThumb)

            // 4. Construct tracks: attach companion thumbnail to first track
            val tracks = listOf(
                ImportTrack(preview.files[0], ImportMetadata("Track 2", "Artist", 2000L, emptyList(), artworkThumbnail = sanitizedThumb)),
                ImportTrack(preview.files[1], ImportMetadata("Track 10", "Artist", 3000L, emptyList()))
            )

            // 5. Commit book
            val bookId = coordinator.commit(preview, tracks, "Multi-part Companion Book")
            assertNotNull(bookId)
            val book = db.books().get(bookId)
            assertNotNull(book)
            assertEquals("Multi-part Companion Book", book!!.title)
            assertNotNull("Book must have coverPath populated from companion thumbnail", book.coverPath)
            val committedCover = store.resolveCover(book.coverPath!!)
            assertNotNull(committedCover)
            assertTrue("Committed cover file must exist on disk", committedCover!!.isFile)

            // Verify tracks and assets in database
            val dbTracks = db.library().tracks(bookId)
            assertEquals(2, dbTracks.size)
            assertEquals("Track 2", dbTracks[0].importedTitle)
            assertEquals("Track 10", dbTracks[1].importedTitle)

            // 6. Test cover override and reference cleanup
            val overrideSource = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book")
            assertTrue(coordinator.setCoverOverride(bookId, overrideSource) { byteArrayOf(10, 20, 30) })
            val updatedBook = db.books().get(bookId)!!
            assertNotNull(updatedBook.coverOverridePath)
            val overrideCover = store.resolveCover(updatedBook.coverOverridePath!!)!!
            assertTrue("Override cover must exist", overrideCover.isFile)
            assertTrue("Original committed cover must still exist when override is active", committedCover.isFile)

            // Reset cover override
            assertTrue(coordinator.resetCoverOverride(bookId))
            val resetBook = db.books().get(bookId)!!
            assertNull(resetBook.coverOverridePath)
            assertFalse("Overridden cover file must be cleaned up after reset", overrideCover.exists())
            assertTrue("Original committed cover must still be present after override reset", committedCover.isFile)

            // 7. Verify originals in provider remain completely unaffected and accessible
            val notesUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "notes.txt")
            val stream10 = context.contentResolver.openInputStream(tree.audioFiles[1].uri)
            assertNotNull("Original track 10 must remain readable", stream10)
            assertEquals(200000, stream10!!.use { it.readBytes() }.size)

            val stream2 = context.contentResolver.openInputStream(tree.audioFiles[0].uri)
            assertNotNull("Original track 2 must remain readable", stream2)
            assertEquals(300000, stream2!!.use { it.readBytes() }.size)

            val streamCover = context.contentResolver.openInputStream(tree.companionCover!!.uri)
            assertNotNull("Original cover must remain readable", streamCover)
            assertTrue(streamCover!!.use { it.readBytes() }.isNotEmpty())

            val streamNotes = context.contentResolver.openInputStream(notesUri)
            assertNotNull("Original non-audio notes must remain readable", streamNotes)
            assertEquals("Audiobook notes and description", String(streamNotes!!.use { it.readBytes() }, Charsets.UTF_8))

            // 8. Remove book and verify private store cleanup
            assertTrue(coordinator.removeBook(bookId))
            assertNull(db.books().get(bookId))
            assertFalse("Committed cover must be deleted on book removal", committedCover.exists())
            for (file in preview.files) {
                val committedMedia = store.resolve(store.relativePath(file.copy.assetId))
                assertNull("Committed media file must be deleted on book removal", committedMedia)
            }

            // Verify originals STILL completely unaffected and accessible after book removal
            val postRemove10 = context.contentResolver.openInputStream(tree.audioFiles[1].uri)
            assertNotNull("Original track 10 must survive book removal", postRemove10)
            assertEquals(200000, postRemove10!!.use { it.readBytes() }.size)

            val postRemove2 = context.contentResolver.openInputStream(tree.audioFiles[0].uri)
            assertNotNull("Original track 2 must survive book removal", postRemove2)
            assertEquals(300000, postRemove2!!.use { it.readBytes() }.size)

            val postRemoveCover = context.contentResolver.openInputStream(tree.companionCover!!.uri)
            assertNotNull("Original cover must survive book removal", postRemoveCover)
            assertTrue(postRemoveCover!!.use { it.readBytes() }.isNotEmpty())

            val postRemoveNotes = context.contentResolver.openInputStream(notesUri)
            assertNotNull("Original notes must survive book removal", postRemoveNotes)
            assertEquals("Audiobook notes and description", String(postRemoveNotes!!.use { it.readBytes() }, Charsets.UTF_8))
        } finally { db.close() }
    }

    @Test fun cancellationAfterCoverCopyCleansStagedFileAndPropagatesCancellation() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)
            val coverUri = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/tree/companion-folder/document/cover.jpg")

            val enteredDecode = CompletableDeferred<Unit>()
            val allowDecodeToFinish = CompletableDeferred<Unit>()
            var inspectedFile: File? = null
            var propagatedCancellation: Throwable? = null

            // Launch child task on Dispatchers.Default so it suspends in decode without blocking the runBlocking event loop
            val deferred = async(Dispatchers.Default) {
                try {
                    coordinator.stageAndInspectCover(coverUri) { file ->
                        inspectedFile = file
                        assertTrue("Cover file must exist after copy before decode", file.isFile)
                        enteredDecode.complete(Unit)
                        // Suspend here until cancelled by parent
                        allowDecodeToFinish.await()
                        byteArrayOf(1, 2, 3)
                    }
                } catch (e: CancellationException) {
                    propagatedCancellation = e
                    throw e
                }
            }

            // Bounded wait until child reaches staged-cover decode barrier
            withTimeout(5_000) { enteredDecode.await() }
            assertNotNull("Inspected file must be captured", inspectedFile)
            assertTrue("Staged cover file must exist on disk while suspended in decode", inspectedFile!!.isFile)

            // Parent cancels the child job and waits for its NonCancellable cleanup to finish
            deferred.cancel()
            val cancellationError = try {
                deferred.await()
                null
            } catch (e: CancellationException) {
                e
            }

            assertNotNull("CancellationException must be propagated out of stageAndInspectCover", propagatedCancellation)
            assertNotNull("CancellationException must be thrown by deferred task on cancellation", cancellationError)
            assertTrue("Deferred task must be cancelled", deferred.isCancelled)

            // Assert that the staged cover file was removed and no orphaned jobs remain
            assertFalse("Staged cover file must be cleaned up despite cancellation", inspectedFile!!.exists())
            assertTrue("No orphaned import jobs should remain in database", coordinator.interruptedJobs().isEmpty())
        } finally { db.close() }
    }

    @Test fun boundedOptionalCoverTimeoutFallsBackToNullWithoutBlockingOrMaskingCancellation() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)
            val slowUri = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/slow")

            // 1. Slow provider with short bounded timeout (200ms) times out and safely returns null
            val startMs = System.currentTimeMillis()
            val result = coordinator.stageAndInspectCover(slowUri, timeoutMs = 200L) {
                byteArrayOf(1, 2, 3)
            }
            val elapsedMs = System.currentTimeMillis() - startMs
            assertNull("Timed out optional cover must fall back to null placeholder", result)
            assertTrue("Timeout must be bounded and not wait 30 minutes (elapsed: ${elapsedMs}ms)", elapsedMs < 2000L)
            assertTrue("No open jobs remain after cover timeout", coordinator.interruptedJobs().isEmpty())
            assertTrue("Reader worker must return to idle after step 1 timeout", PrivateAudioStore.awaitReaderIdle(5000L))

            // 2. Parent cancellation must NOT be masked as null fallback
            val task = launch {
                coordinator.stageAndInspectCover(slowUri, timeoutMs = 5000L) {
                    byteArrayOf(1, 2, 3)
                }
            }
            delay(100)
            task.cancelAndJoin()
            assertTrue("Task was canceled", task.isCancelled)
            assertTrue("No open jobs remain after cancellation", coordinator.interruptedJobs().isEmpty())
        } finally {
            db.close()
            assertTrue("Reader worker must return to idle after cover cancellation", PrivateAudioStore.awaitReaderIdle(5000L))
        }
    }

    @Test fun duplicateReusePreservesOriginalStorageAndSurvivesPartialRemoval() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = ByteArray(150_000) { (it % 251).toByte() }
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, resolver)
            val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", expected.size.toLong())

            // 1. Prepare and commit first book
            val firstPreview = coordinator.prepare(listOf(document))
            val firstBookId = coordinator.commit(
                firstPreview,
                listOf(ImportTrack(firstPreview.files.single(), ImportMetadata("Book 1", "Author 1", 5000, emptyList()))),
                "Book 1"
            )
            val repo = LibraryRepository(db)
            val initialStorage = repo.audioStorageBytes()
            assertEquals(150_000L, initialStorage)
            assertEquals(firstPreview.files.single().copy.sizeBytes, initialStorage)

            // Save progress on first book
            val firstPlayable = repo.playableBook(firstBookId)!!
            val sharedAssetId = firstPlayable.tracks.single().assetId
            assertTrue(repo.saveProgress(firstBookId, firstPlayable.tracks.single().id, 2500, 1.25f))

            // 2. Prepare second book using duplicate content
            val secondPreview = coordinator.prepare(listOf(document))
            assertEquals(1, secondPreview.files.single().duplicateAssetIds.size)
            assertEquals(sharedAssetId, secondPreview.files.single().duplicateAssetIds.single())
            assertEquals(firstPreview.files.single().copy.sha256, secondPreview.files.single().copy.sha256)

            // Commit second book with DuplicateAction.REUSE
            val secondBookId = coordinator.commit(
                secondPreview,
                listOf(ImportTrack(secondPreview.files.single(), ImportMetadata("Book 2", "Author 2", 5000, emptyList()))),
                "Book 2",
                duplicateAction = DuplicateAction.REUSE
            )

            // Storage must not increase because physical asset is reused
            assertEquals(initialStorage, repo.audioStorageBytes())

            // First book's progress and metadata must be preserved
            val firstAfter = repo.playableBook(firstBookId)!!
            assertEquals("Book 1", firstAfter.title)
            assertEquals(2500L, firstAfter.progress!!.offsetMs)
            assertEquals(1.25f, firstAfter.progress!!.speed)

            // Second book resolves to the same shared asset
            val secondPlayable = repo.playableBook(secondBookId)!!
            assertEquals("Book 2", secondPlayable.title)
            assertEquals(sharedAssetId, secondPlayable.tracks.single().assetId)
            val file = store.resolve(secondPlayable.tracks.single().relativePath)!!
            assertTrue("Audio file must exist", file.exists())

            // 3. Remove first book: shared asset must survive because book 2 still references it
            assertTrue(coordinator.removeBook(firstBookId))
            assertNull(repo.playableBook(firstBookId))
            assertTrue("Audio file must still exist after removing first book", file.exists())
            assertEquals(initialStorage, repo.audioStorageBytes())

            // Book 2 is still fully playable
            val secondAfterRemoval = repo.playableBook(secondBookId)!!
            assertNotNull(secondAfterRemoval)
            assertEquals(sharedAssetId, secondAfterRemoval.tracks.single().assetId)

            // 4. Remove second book: now reference count drops to 0, so audio asset is deleted
            assertTrue(coordinator.removeBook(secondBookId))
            assertNull(repo.playableBook(secondBookId))
            assertFalse("Audio file must be deleted once all referencing books are removed", file.exists())
            assertEquals(0L, repo.audioStorageBytes())
        } finally {
            db.close()
        }
    }

    @Test fun importProgressTracksDeterminateBytesWhenSizesKnownAndIndeterminateWhenUnknown() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, resolver)

            // Known size document
            val knownDoc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", 150_000L)
            val progressUpdates = mutableListOf<ImportProgress>()
            val previewKnown = coordinator.prepare(listOf(knownDoc)) { progress ->
                progressUpdates.add(progress)
            }
            assertTrue("Must receive progress updates", progressUpdates.isNotEmpty())
            val lastKnown = progressUpdates.last()
            assertEquals(150_000L, lastKnown.totalExpectedBytes)
            assertEquals(150_000L, lastKnown.totalCopiedBytes)
            coordinator.cancel(previewKnown.jobId)

            // Unknown size document
            val unknownDoc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "unknown.mp3", null)
            val unknownProgressUpdates = mutableListOf<ImportProgress>()
            val previewUnknown = coordinator.prepare(listOf(unknownDoc)) { progress ->
                unknownProgressUpdates.add(progress)
            }
            assertTrue("Must receive progress updates", unknownProgressUpdates.isNotEmpty())
            val lastUnknown = unknownProgressUpdates.last()
            assertNull("totalExpectedBytes must be null when document size is unknown", lastUnknown.totalExpectedBytes)
            assertTrue("totalCopiedBytes must still track copied bytes", lastUnknown.totalCopiedBytes > 0)
            coordinator.cancel(previewUnknown.jobId)
        } finally {
            db.close()
        }
    }

    @Test fun commitEarlyValidationFailureDiscardsStagedFilesAndJobWithoutOrphans() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, resolver)
            val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", 1000L)
            val preview = coordinator.prepare(listOf(document))
            val stagedFile = File(context.noBackupFilesDir, "audiobooks/staging/${preview.files.single().copy.assetId}")
            assertTrue("Staged file must physically exist before commit", stagedFile.exists())

            // Pass invalid chapter range (endMs <= startMs) to force early validation failure
            val invalidMetadata = ImportMetadata(
                title = "Failing Book",
                author = "Author",
                durationMs = 5000L,
                chapters = listOf(ImportChapter(1000L, 500L, "Backwards Chapter")),
                artworkThumbnail = null
            )
            try {
                coordinator.commit(preview, listOf(ImportTrack(preview.files.single(), invalidMetadata)), "Failing Book")
                fail("Commit must throw on invalid chapter metadata")
            } catch (expected: IllegalArgumentException) {
                // Expected validation failure
            }

            // Staged files and open import job must have been discarded truthfully
            assertTrue("No open import jobs must remain after failed commit", coordinator.interruptedJobs().isEmpty())
            assertFalse("Staged file must be cleaned up on validation discard", stagedFile.exists())
        } finally {
            db.close()
        }
    }

    @Test fun commitTransactionFailureAfterCoverWriteRollsBackAndCleansCandidateCover() = runBlocking<Unit> {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(baseContext.cacheDir, "test-cover-fault-" + UUID.randomUUID()).apply { mkdirs() }
        val wrappedContext = object : android.content.ContextWrapper(baseContext) {
            override fun getNoBackupFilesDir(): File = testDir
        }
        val resolver = baseContext.contentResolver
        val db = Room.inMemoryDatabaseBuilder(baseContext, LibraryDatabase::class.java).build()
        try {
            var capturedCoverDest: File? = null
            var capturedCoverBytes: ByteArray? = null
            val store = PrivateAudioStore(wrappedContext, destinationStreamFactory = { dest ->
                if (dest.parentFile?.name == "covers") {
                    capturedCoverDest = dest
                    object : java.io.FileOutputStream(dest) {
                        private val buffer = java.io.ByteArrayOutputStream()
                        override fun write(b: Int) {
                            buffer.write(b)
                            super.write(b)
                        }
                        override fun write(b: ByteArray) {
                            write(b, 0, b.size)
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            buffer.write(b, off, len)
                            super.write(b, off, len)
                        }
                        override fun close() {
                            capturedCoverBytes = buffer.toByteArray()
                            super.close()
                        }
                    }
                } else {
                    java.io.FileOutputStream(dest)
                }
            })
            val coordinator = ImportCoordinator(db, store, resolver)

            // Seed a referenced pre-existing asset with valid UUIDs to verify retained referenced assets are preserved
            val existingBookId = UUID.randomUUID().toString()
            val existingAssetId = UUID.randomUUID().toString()
            val existingTrackId = UUID.randomUUID().toString()
            val existingMedia = File(testDir, "audiobooks/media/$existingAssetId").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(1024) { 42 })
            }
            db.books().insert(Book(existingBookId, "Existing Book", addedAt = 1000L))
            db.library().insertAssets(listOf(AudioAsset(existingAssetId, "media/$existingAssetId", 1024L, "sha-pre-existing", 5000L)))
            db.library().insertTracks(listOf(BookTrack(existingTrackId, existingBookId, existingAssetId, 0)))

            val coversDir = File(testDir, "audiobooks/covers").apply { mkdirs() }
            val coversBefore = coversDir.listFiles()?.toList() ?: emptyList()

            val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", 1000L)
            val preview = coordinator.prepare(listOf(document))

            val validMetadataWithCover = ImportMetadata(
                title = "Candidate Cover Book",
                author = "Author",
                durationMs = 5000L,
                chapters = listOf(ImportChapter(0L, 5000L, "Chapter 1")),
                artworkThumbnail = byteArrayOf(1, 2, 3, 4, 5)
            )

            // Inject SQLite trigger to abort transaction AFTER cover has been written and journaled
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_book_insert BEFORE INSERT ON books BEGIN SELECT RAISE(ABORT, 'Simulated commit failure after cover write'); END;"
            )
            try {
                var observedAbort = false
                try {
                    coordinator.commit(preview, listOf(ImportTrack(preview.files.single(), validMetadataWithCover)), "Candidate Cover Book")
                    fail("Commit must throw on transaction failure")
                } catch (failure: Throwable) {
                    if (failure is AssertionError) throw failure
                    val msg = failure.message ?: failure.cause?.message ?: ""
                    observedAbort = msg.contains("Simulated commit failure after cover write")
                }
                assertTrue("Commit must fail with the specific SQLite abort trigger", observedAbort)

                // destinationStreamFactory must have observed writeCover invocation
                assertNotNull("writeCover must have been reached before transaction abort", capturedCoverDest)
                assertArrayEquals("Captured cover bytes must match written thumbnail", byteArrayOf(1, 2, 3, 4, 5), capturedCoverBytes)
                assertFalse("Candidate cover file must have been discarded by discardJob after rollback", capturedCoverDest!!.exists())

                // Candidate cover file must have been discarded by discardJob, leaving no orphaned covers
                val coversAfter = coversDir.listFiles()?.toList() ?: emptyList()
                assertEquals("No orphaned cover files must remain after transaction rollback", coversBefore.size, coversAfter.size)
                assertTrue("No open import jobs must remain after rollback", coordinator.interruptedJobs().isEmpty())

                // Room rollback: only existing book and asset remain
                val allBooks = db.books().all()
                assertEquals("Only the pre-existing book must remain in DB", 1, allBooks.size)
                assertEquals("Remaining book must be the existing book", existingBookId, allBooks.single().id)
                assertEquals(1, db.library().assetCount(existingAssetId))
                assertTrue("Existing asset file must remain intact", existingMedia.exists())
                val resolvedAsset = store.resolve("media/$existingAssetId")
                assertNotNull("store.resolve must successfully resolve pre-existing asset", resolvedAsset)
                assertTrue("Resolved asset file must exist", resolvedAsset!!.isFile)
                assertArrayEquals("Existing asset bytes must remain unchanged", ByteArray(1024) { 42 }, resolvedAsset.readBytes())
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_book_insert;")
            }
        } finally {
            db.close()
            testDir.deleteRecursively()
        }
    }

    @Test fun duplicateReuseSkipsMissingUnderlyingFileAndCommitsHealthyStagedCopy() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, resolver)
            val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", 1000L)

            // Prepare a fresh copy
            val preview = coordinator.prepare(listOf(document))
            val sha = preview.files.single().copy.sha256
            val size = preview.files.single().copy.sizeBytes

            // Simulate broken DB entry: record exists in DB, but underlying file in media/ is missing
            val missingAssetId = UUID.randomUUID().toString()
            db.library().insertAssets(listOf(io.github.jnesew.paudiobooks.library.AudioAsset(missingAssetId, "media/$missingAssetId", size, sha, 1000L)))
            val ghostFile = File(context.noBackupFilesDir, "audiobooks/media/$missingAssetId")
            if (ghostFile.exists()) ghostFile.delete()

            // Commit with DuplicateAction.REUSE
            val bookId = coordinator.commit(
                preview,
                listOf(ImportTrack(preview.files.single(), ImportMetadata("Repair Book", null, 1000L, emptyList()))),
                "Repair Book",
                duplicateAction = DuplicateAction.REUSE
            )

            // Must NOT reuse the missing asset ID; must commit and reference the healthy staged copy
            val repo = LibraryRepository(db)
            val playable = repo.playableBook(bookId)!!
            val assignedAssetId = playable.tracks.single().assetId
            assertNotEquals("Must not reference broken missing asset", missingAssetId, assignedAssetId)
            val file = store.resolve(playable.tracks.single().relativePath)!!
            assertTrue("Assigned audio file must exist and be healthy", file.isFile)
            assertTrue(coordinator.removeBook(bookId))
        } finally {
            db.close()
        }
    }

    @Test fun duplicateSkipAllTracksCreatesNoBookAndCleansStaging() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = ByteArray(50_000) { (it % 251).toByte() }
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, resolver)
            val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", expected.size.toLong())

            // Commit initial book
            val firstPreview = coordinator.prepare(listOf(document))
            val firstBookId = coordinator.commit(
                firstPreview,
                listOf(ImportTrack(firstPreview.files.single(), ImportMetadata("First Book", "Author", 5000, emptyList()))),
                "First Book"
            )
            val repo = LibraryRepository(db)
            assertEquals(1, db.books().all().size)
            assertTrue(repo.saveProgress(firstBookId, repo.playableBook(firstBookId)!!.tracks.single().id, 2000L, 1.0f))

            // Prepare duplicate
            val secondPreview = coordinator.prepare(listOf(document))
            try {
                coordinator.commit(
                    secondPreview,
                    listOf(ImportTrack(secondPreview.files.single(), ImportMetadata("Duplicate Book", "Author", 5000, emptyList()))),
                    "Duplicate Book",
                    duplicateAction = DuplicateAction.SKIP
                )
                fail("Must throw AllTracksSkippedException when all tracks are duplicates and SKIP selected")
            } catch (_: AllTracksSkippedException) { }

            // Verify no new empty book created and no data corrupted
            assertEquals(1, db.books().all().size)
            assertEquals("First Book", db.books().get(firstBookId)!!.title)
            assertEquals(2000L, db.library().progress(firstBookId)!!.offsetMs)
            assertTrue("No open import jobs must remain after skip cleanup", coordinator.interruptedJobs().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun duplicateSkipMixedTracksImportsOnlyNonDuplicatesAndPreservesOriginalProgress() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, resolver)
            val doc1 = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "track1.mp3", 150_000L)
            val doc2 = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/track2.wav"), "track2.wav", 300_000L)

            // Commit first book with doc1
            val firstPreview = coordinator.prepare(listOf(doc1))
            val firstBookId = coordinator.commit(
                firstPreview,
                listOf(ImportTrack(firstPreview.files.single(), ImportMetadata("Book 1", null, 1000L, emptyList()))),
                "Book 1"
            )
            val repo = LibraryRepository(db)
            assertTrue(repo.saveProgress(firstBookId, repo.playableBook(firstBookId)!!.tracks.single().id, 500L, 1.0f))

            // Prepare mixed selection: doc1 (duplicate) and doc2 (new unique)
            val mixedPreview = coordinator.prepare(listOf(doc1, doc2))
            val book2Id = coordinator.commit(
                mixedPreview,
                mixedPreview.files.mapIndexed { i,file -> ImportTrack(file, ImportMetadata(file.sourceName, if (i==0) "Skipped author" else "Retained author", 1000L, emptyList())) },
                "Book 2",
                duplicateAction = DuplicateAction.SKIP
            )

            // Second book must contain only doc2 (the non-duplicate)
            val book2 = repo.playableBook(book2Id)!!
            assertEquals(1, book2.tracks.size)
            assertEquals("Retained author",db.books().get(book2Id)!!.importedAuthor)
            val asset2 = db.library().asset(book2.tracks.single().assetId)!!
            assertEquals(300_000L, asset2.sizeBytes)
            assertEquals(store.relativePath(asset2.id), book2.tracks.single().relativePath)

            // First book's progress and tracks remain completely intact
            val book1 = repo.playableBook(firstBookId)!!
            assertEquals(1, book1.tracks.size)
            val asset1 = db.library().asset(book1.tracks.single().assetId)!!
            assertEquals(150_000L, asset1.sizeBytes)
            assertEquals(500L, book1.progress!!.offsetMs)

            // Clean up
            assertTrue(coordinator.removeBook(book2Id))
            assertTrue(coordinator.removeBook(firstBookId))
        } finally {
            db.close()
        }
    }

    @Test fun postCommitStagingRemovalFailurePreservesJobAndBookThenReconciles() = runBlocking<Unit> {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(baseContext.cacheDir, "test-staging-fault-" + UUID.randomUUID()).apply { mkdirs() }
        val wrappedContext = object : android.content.ContextWrapper(baseContext) {
            override fun getNoBackupFilesDir(): File = testDir
        }
        val db = Room.inMemoryDatabaseBuilder(baseContext, LibraryDatabase::class.java).build()
        val store = PrivateAudioStore(wrappedContext)
        val resolver = baseContext.contentResolver
        val coordinator = ImportCoordinator(db, store, resolver)
        val repo = LibraryRepository(db)

        val stagingDir = File(File(testDir, "audiobooks"), "staging")
        stagingDir.mkdirs()

        try {
            val doc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/track10.mp3"), "10-track.mp3", 200_000L)
            val preview1 = coordinator.prepare(listOf(doc))
            val tracks1 = preview1.files.map { ImportTrack(it, ImportMetadata("Book 1", "Author", 10_000L, emptyList())) }
            val book1Id = coordinator.commit(preview1, tracks1, "Book 1")

            // Verify Book 1 is committed and asset exists in media
            val book1 = repo.playableBook(book1Id)
            assertNotNull(book1)
            val assetId = book1!!.tracks.single().assetId
            val mediaFile = store.resolve(store.relativePath(assetId))
            assertNotNull(mediaFile)
            assertTrue(mediaFile!!.isFile)

            // Prepare a second import with the duplicate track using REUSE
            val preview2 = coordinator.prepare(listOf(doc))
            val tracks2 = preview2.files.map { ImportTrack(it, ImportMetadata("Book 2", "Author", 10_000L, emptyList())) }
            val unusedStagedId = preview2.files.single().copy.assetId
            val stagedFile = store.stagedFile(unusedStagedId)
            assertTrue(stagedFile.exists())

            // Revoke write permission on dedicated synthetic staging directory so removeStaged fails
            assertTrue(stagingDir.setWritable(false, false))

            // Commit Book 2 with REUSE. The post-commit staging deletion must fail without throwing an outer failure
            val book2Id = coordinator.commit(preview2, tracks2, "Book 2", duplicateAction = DuplicateAction.REUSE)
            assertNotNull(book2Id)

            // 1. Book 2 is successfully committed in DB
            val book2 = repo.playableBook(book2Id)
            assertNotNull(book2)
            assertEquals(assetId, book2!!.tracks.single().assetId)

            // 2. Original Book 1 and its physical audio asset remain intact and untouched
            val book1Check = repo.playableBook(book1Id)
            assertNotNull(book1Check)
            assertTrue(mediaFile.isFile)

            // 3. Staging file still exists on disk because removeStaged failed
            assertTrue(stagedFile.exists())

            // 4. Job is PRESERVED in import_jobs (NOT deleted)
            val jobs = db.library().jobs()
            assertTrue("Job ${preview2.jobId} must be preserved in DB on cleanup failure", jobs.any { it.id == preview2.jobId })

            // 5. Unused staged file is recorded as REMOVING in import_files
            val importFiles = db.library().importFiles(preview2.jobId)
            val removingFile = importFiles.find { it.assetId == unusedStagedId }
            assertNotNull(removingFile)
            assertEquals("REMOVING", removingFile!!.state)

            // Restore write permissions on dedicated synthetic staging directory
            assertTrue(stagingDir.setWritable(true, true))

            // Now reconcile interrupted jobs
            val reconciled = coordinator.reconcileInterrupted()
            assertTrue(reconciled.contains(preview2.jobId))

            // 6. Recovery removed unused staged copy and cleared journal
            assertFalse(stagedFile.exists())
            val remainingJobs = db.library().jobs()
            assertFalse(remainingJobs.any { it.id == preview2.jobId })

            // 7. Referenced book, track, and media file STILL completely intact after recovery
            val book1After = repo.playableBook(book1Id)
            assertNotNull(book1After)
            val book2After = repo.playableBook(book2Id)
            assertNotNull(book2After)
            assertTrue(mediaFile.isFile)
        } finally {
            stagingDir.setWritable(true, true)
            testDir.deleteRecursively()
            db.close()
        }
    }

    @Test fun postCommitJournalDeletionFailurePreservesJobAndBookThenReconciles() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, resolver)
            val repo = LibraryRepository(db)

            val doc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/track10.mp3"), "10-track.mp3", 200_000L)
            val preview = coordinator.prepare(listOf(doc))
            val tracks = preview.files.map { ImportTrack(it, ImportMetadata("Trigger Book", "Author", 10_000L, emptyList())) }

            // Inject temporary SQLite trigger to abort DELETE on import_jobs for this specific synthetic jobId
            val triggerName = "fail_delete_job_${preview.jobId.replace('-', '_')}"
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER $triggerName BEFORE DELETE ON import_jobs " +
                "FOR EACH ROW WHEN OLD.id = '${preview.jobId}' " +
                "BEGIN SELECT RAISE(ABORT, 'Simulated deleteJob failure'); END;"
            )

            var committedBookId: String? = null
            try {
                // Commit must succeed and return bookId without throwing an outer failure despite deleteJob failing
                val bookId = coordinator.commit(preview, tracks, "Trigger Book")
                committedBookId = bookId
                assertNotNull(bookId)

                // 1. Book is committed and playable in database
                val book = repo.playableBook(bookId)
                assertNotNull(book)
                val assetId = book!!.tracks.single().assetId
                val mediaFile = store.resolve(store.relativePath(assetId))
                assertNotNull(mediaFile)
                assertTrue(mediaFile!!.isFile)

                // 2. Job is PRESERVED in import_jobs because deleteJob aborted
                val jobs = db.library().jobs()
                assertTrue("Job ${preview.jobId} must be preserved in DB when deleteJob fails", jobs.any { it.id == preview.jobId })
            } finally {
                // Drop the temporary SQLite trigger
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $triggerName")
            }

            // Now reconcile interrupted jobs: with trigger removed, deleteJob succeeds
            val reconciled = coordinator.reconcileInterrupted()
            assertTrue(reconciled.contains(preview.jobId))

            // 3. Recovery cleared the journal
            val remainingJobs = db.library().jobs()
            assertFalse(remainingJobs.any { it.id == preview.jobId })

            // 4. Committed book and physical media file remain completely intact
            val bookAfter = repo.playableBook(committedBookId!!)
            assertNotNull(bookAfter)
            val assetAfter = db.library().asset(bookAfter!!.tracks.single().assetId)
            assertNotNull(assetAfter)
            val fileAfter = store.resolve(store.relativePath(assetAfter!!.id))
            assertNotNull(fileAfter)
            assertTrue(fileAfter!!.isFile)
            assertTrue(coordinator.removeBook(committedBookId))
        } finally {
            db.close()
        }
    }

    @Test fun documentsProviderHierarchyPreservesMultiDiscOrderAndDistinctSiblingGroupsWithoutCollapse() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val inventory = DocumentInventory(resolver)

        val treeUri = DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.fixture", "hierarchy-folder")
        val tree = inventory.treeInventory(treeUri)

        // 1. Root companion cover boundary: depth 0 cover.jpg recognized and not polluted by subdirectories
        assertNotNull(tree.companionCover)
        assertEquals("cover.jpg", tree.companionCover!!.name)

        // 2. All audio files in the tree: total 8 files across all categories
        // MultiDisc (4), Series/Book A (1), Series/Book B (1), Author 1/Special Edition (1), Author 2/Special Edition (1)
        assertEquals(8, tree.audioFiles.size)

        // 3. Tree groups: MultiDisc combined book, Series/Book A, Series/Book B, Author 1/Special Edition, Author 2/Special Edition
        // Sibling directories with identical display names ("Special Edition") under Author 1 and Author 2 must NOT collapse
        val groupNames = tree.groups.map { it.name }
        assertTrue("Author 1/Special Edition must exist", groupNames.contains("Author 1/Special Edition"))
        assertTrue("Author 2/Special Edition must exist", groupNames.contains("Author 2/Special Edition"))
        assertTrue("Series/Book A must exist", groupNames.contains("Series/Book A"))
        assertTrue("Series/Book B must exist", groupNames.contains("Series/Book B"))
        assertTrue("MultiDisc must exist", groupNames.contains("MultiDisc"))
        assertEquals("Exactly 5 distinct groups must be formed without collapsing same-named sibling directories", 5, tree.groups.size)

        // 4. MultiDisc grouping: all CD 1 tracks must strictly precede CD 2 tracks
        val multiDiscGroup = tree.groups.first { it.name == "MultiDisc" }
        assertEquals(4, multiDiscGroup.audioFiles.size)
        assertEquals("MultiDisc/CD 1", multiDiscGroup.audioFiles[0].subfolder)
        assertEquals("01-start.mp3", multiDiscGroup.audioFiles[0].name)
        assertEquals("MultiDisc/CD 1", multiDiscGroup.audioFiles[1].subfolder)
        assertEquals("02-middle.wav", multiDiscGroup.audioFiles[1].name)
        assertEquals("MultiDisc/CD 2", multiDiscGroup.audioFiles[2].subfolder)
        assertEquals("01-continue.mp3", multiDiscGroup.audioFiles[2].name)
        assertEquals("MultiDisc/CD 2", multiDiscGroup.audioFiles[3].subfolder)
        assertEquals("02-end.wav", multiDiscGroup.audioFiles[3].name)

        // 5. Production orderTrackIndices pipeline test:
        // Even when ID3 tags are missing, subfolder ordering prevents interleaving tracks across discs
        val hints = multiDiscGroup.audioFiles.map { doc ->
            TrackOrderHint(doc.name, null, null, doc.subfolder, doc.orderKey)
        }
        val orderedIndices = orderTrackIndices(hints)
        assertEquals(listOf(0, 1, 2, 3), orderedIndices)

        // 6. Test prepare and preview pipeline retains subfolder metadata
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, resolver)
            val preview = coordinator.prepare(multiDiscGroup.audioFiles)
            assertEquals(4, preview.files.size)
            assertEquals("MultiDisc/CD 1", preview.files[0].subfolder)
            assertEquals("01-start.mp3", preview.files[0].sourceName)
            assertEquals("MultiDisc/CD 1", preview.files[1].subfolder)
            assertEquals("02-middle.wav", preview.files[1].sourceName)
            assertEquals("MultiDisc/CD 2", preview.files[2].subfolder)
            assertEquals("01-continue.mp3", preview.files[2].sourceName)
            assertEquals("MultiDisc/CD 2", preview.files[3].subfolder)
            assertEquals("02-end.wav", preview.files[3].sourceName)

            coordinator.cancel(preview.jobId)
        } finally {
            db.close()
        }

        // 7. Bounded deduplication: repeated rows with identical document IDs from provider do not duplicate
        val repeatedUri = DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.fixture", "repeated-folder")
        val repeatedTree = inventory.treeInventory(repeatedUri)
        assertEquals("Repeated identical document IDs must be bounded to 1 file", 1, repeatedTree.audioFiles.size)

        // 8. Same-parent identical display name folders retain opaque identity without appending raw doc IDs to human label
        val spUri = DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.fixture", "same-parent-duplicates")
        val spTree = inventory.treeInventory(spUri)
        assertEquals("Both sibling folders with identical display names contribute their file", 2, spTree.audioFiles.size)
        assertEquals("Part 1", spTree.groups[0].name)
        assertEquals("Part 1", spTree.groups[1].name)
        assertTrue("Groups must have distinct opaque identity IDs", spTree.groups[0].id != spTree.groups[1].id)
        val spHints = spTree.audioFiles.map { TrackOrderHint(it.name, null, null, it.subfolder, it.orderKey) }
        val spOrder = orderTrackIndices(spHints)
        assertEquals(listOf(0, 1), spOrder)
    }

    @Test fun removeBookPostCommitCleanupFailurePreservesJobInJournal() = runBlocking<Unit> {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(baseContext.cacheDir, "test-remove-fault-" + UUID.randomUUID()).apply { mkdirs() }
        val wrappedContext = object : android.content.ContextWrapper(baseContext) {
            override fun getNoBackupFilesDir(): File = testDir
        }
        val db = Room.inMemoryDatabaseBuilder(baseContext, LibraryDatabase::class.java).build()
        val store = PrivateAudioStore(wrappedContext)
        val resolver = baseContext.contentResolver
        val coordinator = ImportCoordinator(db, store, resolver)

        val mediaDir = File(File(testDir, "audiobooks"), "media").apply { mkdirs() }

        try {
            val doc = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/track10.mp3"), "10-track.mp3", 200_000L)
            val preview = coordinator.prepare(listOf(doc))
            val tracks = preview.files.map { ImportTrack(it, ImportMetadata("Book To Remove", "Author", 10_000L, emptyList())) }
            val bookId = coordinator.commit(preview, tracks, "Book To Remove")
            assertNotNull(bookId)
            assertEquals(1, db.books().all().size)

            val assetId = db.library().tracks(bookId).single().assetId
            val mediaFile = store.resolve(store.relativePath(assetId))!!
            assertTrue("Physical media file must exist", mediaFile.isFile)

            // Revoke write permission on media directory so file deletion fails during discardJob
            assertTrue(mediaDir.setWritable(false, false))

            // removeBook must succeed in deleting the book from the DB without throwing an outer exception
            val removed = coordinator.removeBook(bookId)
            assertTrue("removeBook must return true as book is deleted from DB", removed)
            assertEquals("Book must be deleted from DB", 0, db.books().all().size)

            // Physical file still exists because deletion failed
            assertTrue(mediaFile.exists())

            // Job must be PRESERVED in import_jobs with REMOVING status
            val jobs = db.library().jobs()
            assertEquals("Job must be preserved in Room on cleanup failure", 1, jobs.size)
            assertEquals("REMOVING", jobs.single().state)

            // Restore write permissions
            assertTrue(mediaDir.setWritable(true, true))

            // reconcileInterrupted clears the remaining physical file and job journal
            val reconciled = coordinator.reconcileInterrupted()
            assertEquals(1, reconciled.size)
            assertFalse("Physical file must now be deleted by reconcileInterrupted", mediaFile.exists())
            assertEquals("Journal must now be empty", 0, db.library().jobs().size)
        } finally {
            mediaDir.setWritable(true, true)
            testDir.deleteRecursively()
            db.close()
        }
    }

    @Test fun bookWideChapterCountAndTextLimitsRejectBeforeCommitAndCleanRealStaging() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val store = PrivateAudioStore(context)
        val coordinator = ImportCoordinator(db, store, context.contentResolver)
        val source = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", null)
        try {
            for (textLimit in listOf(false, true)) {
                val preview = coordinator.prepare(listOf(source, source.copy(name = "second.mp3")))
                preview.files.forEach { assertTrue(store.stagedFile(it.copy.assetId).isFile) }
                val tracks = preview.files.mapIndexed { index, file ->
                    val chapters = if (textLimit) List(5) { i -> ImportChapter(i.toLong(), i + 1L, "x".repeat(4096)) }
                        else List(if (index == 0) 6000 else 4001) { i -> ImportChapter(i.toLong(), i + 1L, null) }
                    ImportTrack(file, ImportMetadata(null, null, 20_000, chapters))
                }
                try { coordinator.commit(preview, tracks, "Too much metadata", DuplicateAction.MAKE_COPY); fail("Book-wide limit must reject") }
                catch (_: ImportMetadataLimitException) { }
                assertTrue(db.library().jobs().isEmpty())
                preview.files.forEach {
                    assertFalse(store.stagedFile(it.copy.assetId).exists())
                    assertNull(store.resolve(store.relativePath(it.copy.assetId)))
                }
            }
            val retry = coordinator.prepare(listOf(source))
            val book = coordinator.commit(retry, listOf(ImportTrack(retry.files.single(), ImportMetadata(null, null, 1000, emptyList()))), "Recovery")
            assertTrue(coordinator.removeBook(book))
        } finally { db.close() }
    }

    @Test fun repairMissingAndCorruptSharedAudioPreservesBooksProgressNotesAndEdits() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (missing in listOf(true, false)) {
            val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
            val store = PrivateAudioStore(context)
            val coordinator = ImportCoordinator(db, store, context.contentResolver)
            val repo = LibraryRepository(db)
            val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", null)
            var originalBook: String? = null
            var sharedBook: String? = null
            try {
                suspend fun importBook(): String {
                    val preview = coordinator.prepare(listOf(document))
                    return coordinator.commit(preview, listOf(ImportTrack(preview.files.single(),
                        ImportMetadata("Original", "Author", 100_000, listOf(ImportChapter(0, 100_000, "Chapter"))))),
                        "Book", DuplicateAction.REUSE)
                }
                originalBook = importBook()
                sharedBook = importBook()
                val before = requireNotNull(repo.playableBook(originalBook))
                val track = before.tracks.single()
                assertEquals(track.assetId, requireNotNull(repo.playableBook(sharedBook)).tracks.single().assetId)
                assertTrue(repo.saveProgress(originalBook, track.id, 42_000, 1.75f))
                val bookmark = requireNotNull(repo.addBookmark(originalBook, track.id, 39_000, "Keep this note"))
                assertTrue(repo.updateOverrides(originalBook, "User title", "User author", "Narrator", "Series"))
                val savedProgress = db.library().progress(originalBook)
                val savedBook = db.books().get(originalBook)
                val savedMarks = db.library().bookmarks(originalBook)
                assertEquals(bookmark, savedMarks.single().id)
                val owned = requireNotNull(store.resolve(track.relativePath))
                val bytes = owned.readBytes()
                if (missing) assertTrue(owned.delete()) else owned.writeBytes(bytes.copyOf().apply { this[0] = (this[0].toInt() xor 255).toByte() })
                val preview = coordinator.prepare(listOf(document))
                assertTrue(preview.files.single().duplicateAssetIds.contains(track.assetId))
                assertEquals(1, coordinator.repair(originalBook, preview))
                assertArrayEquals(bytes, requireNotNull(store.resolve(track.relativePath)).readBytes())
                assertEquals(savedBook, db.books().get(originalBook))
                assertEquals(savedProgress, db.library().progress(originalBook))
                assertEquals(savedMarks, db.library().bookmarks(originalBook))
                assertEquals(before.tracks, requireNotNull(repo.playableBook(originalBook)).tracks)
                assertEquals(track.assetId, requireNotNull(repo.playableBook(sharedBook)).tracks.single().assetId)
                assertTrue(db.library().jobs().isEmpty())
                assertFalse(store.stagedFile(preview.files.single().copy.assetId).exists())
            } finally {
                originalBook?.let { coordinator.removeBook(it) }
                sharedBook?.let { coordinator.removeBook(it) }
                db.close()
            }
        }
    }

    @Test fun repairRejectsUnrelatedFilesBeforeChangingAnyOwnedBytes() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val store = PrivateAudioStore(context)
        val coordinator = ImportCoordinator(db, store, context.contentResolver)
        val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", null)
        var book: String? = null
        try {
            val first = coordinator.prepare(listOf(document))
            book = coordinator.commit(first, listOf(ImportTrack(first.files.single(), ImportMetadata(null, null, 100_000, emptyList()))), "Keep")
            val track = requireNotNull(LibraryRepository(db).playableBook(book)).tracks.single()
            val bytes = requireNotNull(store.resolve(track.relativePath)).readBytes()
            val preview = coordinator.prepare(listOf(document, SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/track2.wav"), "other.wav", null)))
            try { coordinator.repair(book, preview); fail("Unrelated selection must be rejected") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(bytes, requireNotNull(store.resolve(track.relativePath)).readBytes())
            assertTrue(db.library().jobs().isEmpty())
            preview.files.forEach { assertFalse(store.stagedFile(it.copy.assetId).exists()) }
        } finally { book?.let { coordinator.removeBook(it) }; db.close() }
    }

    @Test fun repairMidWriteFailurePreservesOldBytesAndDurableNotesThenAllowsRetry() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val store = PrivateAudioStore(context)
        val coordinator = ImportCoordinator(db, store, context.contentResolver)
        val repo = LibraryRepository(db)
        val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", null)
        var book: String? = null
        try {
            val initial = coordinator.prepare(listOf(document))
            book = coordinator.commit(initial, listOf(ImportTrack(initial.files.single(), ImportMetadata(null, null, 100_000, emptyList()))), "Keep")
            val track = requireNotNull(repo.playableBook(book)).tracks.single()
            assertTrue(repo.saveProgress(book, track.id, 35_000, 2f))
            repo.addBookmark(book, track.id, 30_000, "Keep after failure")
            val state = db.library().progress(book)
            val notes = db.library().bookmarks(book)
            val file = requireNotNull(store.resolve(track.relativePath))
            val oldBytes = file.readBytes()
            val preview = coordinator.prepare(listOf(document))
            assertTrue(store.stagedFile(preview.files.single().copy.assetId).isFile)
            val failing = PrivateAudioStore(context, destinationStreamFactory = { target -> FaultInjectingFileOutputStream(target, 8192) })
            try { ImportCoordinator(db, failing, context.contentResolver).repair(book, preview); fail("Injected ENOSPC must fail") }
            catch (_: java.io.IOException) { }
            assertArrayEquals(oldBytes, file.readBytes())
            assertEquals(state, db.library().progress(book))
            assertEquals(notes, db.library().bookmarks(book))
            assertTrue(db.library().jobs().isEmpty())
            assertFalse(store.stagedFile(preview.files.single().copy.assetId).exists())
            val retry = coordinator.prepare(listOf(document))
            assertEquals(1, coordinator.repair(book, retry))
            assertArrayEquals(oldBytes, file.readBytes())
        } finally { book?.let { coordinator.removeBook(it) }; db.close() }
    }

    @Test fun duplicateReuseNeverDiscardsHealthySelectionForCorruptPresentAudio() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val store = PrivateAudioStore(context)
        val coordinator = ImportCoordinator(db, store, context.contentResolver)
        val repo = LibraryRepository(db)
        val document = SelectedDocument(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), "book.mp3", null)
        val books = mutableListOf<String>()
        try {
            suspend fun importBook(action: DuplicateAction): String {
                val preview = coordinator.prepare(listOf(document))
                return coordinator.commit(preview, listOf(ImportTrack(preview.files.single(), ImportMetadata(null, null, 1000, emptyList()))), "Book", action)
            }
            val original = importBook(DuplicateAction.REUSE).also { books.add(it) }
            val track = requireNotNull(repo.playableBook(original)).tracks.single()
            val file = requireNotNull(store.resolve(track.relativePath))
            val bytes = file.readBytes()
            file.writeBytes(bytes.copyOf().apply { this[0] = (this[0].toInt() xor 255).toByte() })
            val healthy = importBook(DuplicateAction.REUSE).also { books.add(it) }
            val replacement = requireNotNull(repo.playableBook(healthy)).tracks.single()
            assertNotEquals(track.assetId, replacement.assetId)
            assertArrayEquals(bytes, requireNotNull(store.resolve(replacement.relativePath)).readBytes())
        } finally { books.forEach { coordinator.removeBook(it) }; db.close() }
    }

    @Test fun uncooperativeProviderMaintainsBusyGuardWhileAliveAndRecoversAfterUnblocking() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val store = PrivateAudioStore(context)
        val authority = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture")
        val normalUri = Uri.withAppendedPath(authority, "book")
        val slowId = UUID.randomUUID().toString()
        val normalId = UUID.randomUUID().toString()
        val retainedId = UUID.randomUUID().toString()
        store.copy(resolver, normalUri, retainedId)
        store.commit(retainedId)
        val retained = requireNotNull(store.resolve(store.relativePath(retainedId)))
        val retainedBytes = retained.readBytes()
        resolver.call(authority, "arm-open", null, null)
        val job = launch(Dispatchers.IO) { store.copy(resolver, Uri.withAppendedPath(authority, "controlled-open"), slowId) }
        try {
            withTimeout(3000) {
                while (resolver.call(authority, "open-state", null, null)?.getBoolean("entered") != true) delay(20)
            }
            assertTrue("Real staged file must exist before cleanup", store.stagedFile(slowId).isFile)
            val started = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.withContext(Dispatchers.Main) { job.cancel() }
            job.join()
            assertTrue("Cancellation must return promptly", android.os.SystemClock.elapsedRealtime() - started < 1500)
            assertFalse(store.stagedFile(slowId).exists())
            assertFalse("Live uncooperative open must keep the guard", PrivateAudioStore.awaitReaderIdle(150))
            try {
                store.copy(resolver, normalUri, normalId)
                fail("An uncooperative live reader must reject a second copy")
            } catch (_: io.github.jnesew.paudiobooks.storage.SourceReaderBusyException) { }
            resolver.call(authority, "release-open", null, null)
            assertTrue("Actual provider return must release the guard", PrivateAudioStore.awaitReaderIdle(5000))
            assertFalse("Late provider return must not recreate staging", store.stagedFile(slowId).exists())
            val copied = store.copy(resolver, normalUri, normalId)
            assertEquals(150_000L, copied.sizeBytes)
            assertArrayEquals(retainedBytes, store.stagedFile(normalId).readBytes())
            assertArrayEquals("Retained committed media must be unchanged", retainedBytes, retained.readBytes())
        } finally {
            resolver.call(authority, "release-open", null, null)
            job.cancelAndJoin()
            assertTrue("Worker and closer must really return", PrivateAudioStore.awaitReaderIdle(5000))
            listOf(slowId, normalId, retainedId).forEach { store.discard(it) }
        }
    }

    @Test fun failedCopyNeverDeletesAnotherOperationsStagedFile() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivateAudioStore(context)
        val id = UUID.randomUUID().toString()
        val uri = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book")
        try {
            store.copy(context.contentResolver, uri, id)
            val bytes = store.stagedFile(id).readBytes()
            try { store.copy(context.contentResolver, uri, id); fail("Existing staging must be rejected") }
            catch (_: java.io.IOException) { }
            assertArrayEquals("A failed attempt must not delete another copy", bytes, store.stagedFile(id).readBytes())
        } finally { store.discard(id) }
    }

    @Test fun blockedCloseDoesNotHoldCancellationLockAndReleasesExactlyOnce() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closeCount = java.util.concurrent.atomic.AtomicInteger()
        val id = UUID.randomUUID().toString()
        val store = PrivateAudioStore(context, destinationStreamFactory = { file ->
            object : java.io.FileOutputStream(file) {
                override fun close() {
                    closeCount.incrementAndGet()
                    entered.countDown()
                    try {
                        check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    } finally { super.close() }
                    throw java.io.IOException("Synthetic close failure after release")
                }
            }
        })
        val job = launch(Dispatchers.IO) {
            store.copy(context.contentResolver, Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), id)
        }
        try {
            withTimeout(3000) { while (entered.count != 0L) delay(20) }
            assertTrue(store.stagedFile(id).isFile)
            val started = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.withContext(Dispatchers.Main) { job.cancel() }
            job.join()
            assertTrue("Blocked close must not block main cancellation", android.os.SystemClock.elapsedRealtime() - started < 1500)
            assertFalse("Blocked close remains active", PrivateAudioStore.awaitReaderIdle(150))
            release.countDown()
            assertTrue(PrivateAudioStore.awaitReaderIdle(5000))
            assertEquals("Worker and canceller must close each handle once", 1, closeCount.get())
            assertFalse(store.stagedFile(id).exists())
            // A thrown close must not leak admission, even with the same store.
            store.copy(context.contentResolver, Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book"), id)
            assertEquals(150_000L, store.stagedFile(id).length())
            assertEquals(2, closeCount.get())
        } finally {
            release.countDown()
            job.cancelAndJoin()
            assertTrue(PrivateAudioStore.awaitReaderIdle(5000))
            store.discard(id)
        }
    }

    @Test fun cancellationDuringDestinationConstructionCleansLateStagingAndRecovers() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = PrivateAudioStore(context, destinationStreamFactory = { file ->
            entered.countDown()
            check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
            java.io.FileOutputStream(file)
        })
        val id = UUID.randomUUID().toString()
        val uri = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/book")
        val job = launch(Dispatchers.IO) { store.copy(context.contentResolver, uri, id) }
        try {
            withTimeout(3000) { while (entered.count != 0L) delay(20) }
            kotlinx.coroutines.withContext(Dispatchers.Main) { job.cancel() }
            job.join()
            assertFalse(store.stagedFile(id).exists())
            release.countDown()
            assertTrue(PrivateAudioStore.awaitReaderIdle(5000))
            assertFalse("A late constructor must not leave an orphan", store.stagedFile(id).exists())
            assertEquals(150_000L, store.copy(context.contentResolver, uri, id).sizeBytes)
        } finally {
            release.countDown()
            job.cancelAndJoin()
            assertTrue(PrivateAudioStore.awaitReaderIdle(5000))
            store.discard(id)
        }
    }

}

private fun stagedParentCheck(file: File) { file.parentFile?.mkdirs() }

private class FaultInjectingFileOutputStream(file: File, private val maxBytes: Long) : java.io.FileOutputStream(file) {
    private var written = 0L

    override fun write(b: Int) {
        if (written + 1 > maxBytes) {
            throw java.io.IOException("ENOSPC: No space left on device")
        }
        super.write(b)
        written += 1
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (written + len > maxBytes) {
            val allowed = (maxBytes - written).coerceAtLeast(0).toInt()
            if (allowed > 0) {
                super.write(b, off, allowed)
                written += allowed
            }
            throw java.io.IOException("ENOSPC: No space left on device")
        }
        super.write(b, off, len)
        written += len
    }
}
