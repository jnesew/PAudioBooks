package io.github.jnesew.paudiobooks.importing

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.library.*
import io.github.jnesew.paudiobooks.storage.PrivateAudioStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit-only: exhaust unprivileged storage on the new AVD; report ENOSPC and EDQUOT separately. */
@RunWith(AndroidJUnit4::class)
class FilesystemExhaustionAcceptanceTest {
    @Test fun realKernelStorageFailureDuringStagedCopyPreservesRetainedDataAndAllowsRetry() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("fullFilesystem") == "true")
        check(args.getString("ownedAvd") == "paudio_codex_fullspace26")
        check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        val app = ApplicationProvider.getApplicationContext<Context>()
        fun id() = UUID.randomUUID().toString()
        val root = File(app.noBackupFilesDir, "fullspace-trial-${id()}")
        check(root.mkdir())
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = root }
        val databaseName = "fullspace-${id()}.db"
        val db = Room.databaseBuilder(app, LibraryDatabase::class.java, databaseName).build()
        val filler = File(root, "bounded-filler")
        val allocated = AtomicBoolean(false)
        val destinationErrno = java.util.concurrent.atomic.AtomicInteger(0)
        var partialBytes = 0L
        var allocatedBytes = 0L
        var remainingBytes = -1L
        val store = PrivateAudioStore(context, destinationStreamFactory = { destination ->
            object : FileOutputStream(destination) {
                private var written = 0L
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (written > 0 && allocated.compareAndSet(false, true)) {
                        partialBytes = destination.length()
                        val available = StatFs(root.path).availableBytes
                        // This stock AVD has a 10 GiB /data image. Never fill an unknown
                        // larger volume, loop allocating, or touch another AVD/host volume.
                        check(available in 512_000L..(11L * 1024 * 1024 * 1024))
                        check(filler.createNewFile())
                        FileOutputStream(filler).use { space ->
                            var chunk = 128L * 1024 * 1024
                            var attempts = 0
                            val started = android.os.SystemClock.elapsedRealtime()
                            while (true) {
                                check(++attempts <= 256 && android.os.SystemClock.elapsedRealtime() - started < 20_000)
                                check(allocatedBytes <= 11L * 1024 * 1024 * 1024 - chunk)
                                try {
                                    Os.posix_fallocate(space.fd, allocatedBytes, chunk)
                                    allocatedBytes += chunk
                                } catch (error: ErrnoException) {
                                    if (error.errno != OsConstants.ENOSPC && error.errno != OsConstants.EDQUOT) throw error
                                    Os.ftruncate(space.fd, allocatedBytes)
                                    if (chunk == 4096L) break
                                    chunk = maxOf(4096L, chunk / 16)
                                }
                            }
                        }
                        remainingBytes = StatFs(root.path).availableBytes

                    }
                    try {
                        super.write(buffer, offset, length)
                        written += length
                    } catch (error: IOException) {
                        var cause: Throwable? = error
                        while (cause != null) {
                            if (cause is ErrnoException && cause.errno in listOf(OsConstants.ENOSPC, OsConstants.EDQUOT)) destinationErrno.set(cause.errno)
                            cause = cause.cause
                        }
                        throw error
                    }
                }
            }
        })
        val coordinator = ImportCoordinator(db, store, app.contentResolver)
        val retainedBook = id(); val retainedAsset = id(); val retainedTrack = id(); val mark = id()
        val bytes = "Retained synthetic audio bytes".toByteArray()
        val retained = File(root, "audiobooks/media/$retainedAsset")
        retained.parentFile!!.mkdirs(); retained.writeBytes(bytes)
        val source = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture/track2.wav")
        try {
            // Create/close the finite read-only provider source before storage is full.
            val sourceBefore = app.contentResolver.openInputStream(source)!!.use { it.readBytes() }
            assertEquals(300_000, sourceBefore.size)
            db.books().insert(Book(retainedBook, "Retained title", titleOverride = "Retained edit"))
            db.library().insertAssets(listOf(AudioAsset(retainedAsset, "media/$retainedAsset", bytes.size.toLong(), "synthetic", 10_000)))
            db.library().insertTracks(listOf(BookTrack(retainedTrack, retainedBook, retainedAsset, 0)))
            val state = ReadingState(retainedBook, retainedTrack, 6000, 1.25f, false, 42)
            val note = Bookmark(mark, retainedBook, retainedTrack, 4000, "Retained full-space note", 43)
            db.library().saveProgress(state); db.library().insertBookmark(note)
            val selected = SelectedDocument(source, "Synthetic source.wav", null)
            var failed = false
            try { coordinator.prepare(listOf(selected)) }
            catch (_: IOException) { failed = true }
            finally {
                // Always release our own preallocated extent, even if journal cleanup
                // itself could not write while full. Never remove another file to free space.
                if (filler.exists()) check(filler.delete())
            }
            assertTrue("An actual destination write must receive kernel ENOSPC or EDQUOT", failed && destinationErrno.get() != 0)
            assertTrue("A real partial staged copy existed before exhaustion", partialBytes > 0)
            assertTrue(PrivateAudioStore.awaitReaderIdle(5000))
            coordinator.reconcileInterrupted()
            assertTrue(db.library().jobs().isEmpty())
            assertEquals("Retained edit", db.books().get(retainedBook)!!.titleOverride)
            assertEquals(state, db.library().progress(retainedBook))
            assertEquals(listOf(note), db.library().bookmarks(retainedBook))
            assertArrayEquals(bytes, retained.readBytes())
            assertArrayEquals(sourceBefore, app.contentResolver.openInputStream(source)!!.use { it.readBytes() })
            val retry = coordinator.prepare(listOf(selected))
            assertEquals(300_000L, retry.files.single().copy.sizeBytes)
            coordinator.cancel(retry.jobId)
            assertTrue(db.library().jobs().isEmpty())
            instrumentation.sendStatus(2, Bundle().apply {
                putBoolean("kernelDestinationEnospc", destinationErrno.get() == OsConstants.ENOSPC)
                putBoolean("kernelDestinationEdquot", destinationErrno.get() == OsConstants.EDQUOT)
                putInt("kernelDestinationErrno", destinationErrno.get()); putLong("partialBytesBeforeFault", partialBytes)
                putLong("fillerAllocatedBytes", allocatedBytes); putLong("availableBytesAtFault", remainingBytes)
                putBoolean("retainedBytesNotesAndProgressUnchanged", true); putBoolean("subsequentCopySucceeded", true)
            })
        } finally {
            if (filler.exists()) check(filler.delete())
            check(PrivateAudioStore.awaitReaderIdle(5000))
            db.close(); app.deleteDatabase(databaseName)
            check(root.deleteRecursively())
        }
    }
}
