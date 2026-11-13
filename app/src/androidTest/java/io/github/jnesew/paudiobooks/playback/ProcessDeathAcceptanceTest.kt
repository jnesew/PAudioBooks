package io.github.jnesew.paudiobooks.playback

import android.content.ComponentName
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.library.AudioAsset
import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.BookTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Host-driven phases. Only the host kills/reboots; no production hooks or raw DB export. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ProcessDeathAcceptanceTest {
    private val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val linked get() = args.getString("sourceMode", "private")=="linked"
    private val prefs get() = app.getSharedPreferences(if (linked) "synthetic_process_acceptance_linked" else "synthetic_process_acceptance", 0)

    private fun <T> main(action: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = action() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun connect(): MediaController = main {
        MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    }.get(20, TimeUnit.SECONDS)

    private fun command(c: MediaController, action: String, args: Bundle = Bundle.EMPTY) {
        val result = main { c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }.get(20, TimeUnit.SECONDS)
        assertEquals(action, SessionResult.RESULT_SUCCESS, result.resultCode)
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() - start < 15_000) { message }
            Thread.sleep(25)
        }
    }

    private fun seedBook(): String = runBlocking {
        val previous = prefs.getString("book", null)
        if (previous != null) {
            // Refuse to silently replace or delete a previous trial's data.
            checkNotNull(app.library.books().get(previous))
            check(app.library.library().tracks(previous).size == 2)
            return@runBlocking previous
        }
        val book = UUID.randomUUID().toString()
        if (linked) {
            val rootId="linked-test-life-${UUID.randomUUID()}-root"
            requireNotNull(app.contentResolver.call(android.net.Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",rootId,null))
            val root=android.provider.DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",rootId)
            val files=listOf("linked-life-a","linked-life-b").map { id ->
                io.github.jnesew.paudiobooks.importing.DocumentInventory(app.contentResolver).describe(android.provider.DocumentsContract.buildDocumentUriUsingTree(root,id))
            }
            val preview=app.linker.prepare(files,listOf(root),android.provider.DocumentsContract.buildDocumentUriUsingTree(root,"linked-life-book"),root)
            app.linker.commit(preview,preview.tracks,"Synthetic linked process acceptance",book)
            val track=app.library.library().tracks(book).first()
            app.repository.addBookmark(book,track.id,3000,"Retain linked process note")
            app.library.books().setOverrides(book,null,null,"Retain lifecycle narrator",null,"Retain lifecycle translator",null)
            check(prefs.edit().putString("book",book).commit())
            return@runBlocking book
        }
        app.library.books().insert(Book(book, "Synthetic process acceptance"))
        repeat(2) { index ->
            val asset = UUID.randomUUID().toString()
            val track = UUID.randomUUID().toString()
            val file = File(app.noBackupFilesDir, "audiobooks/media/$asset")
            file.parentFile!!.mkdirs()
            val size = 8_000 * 2 * 120
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size)
            file.outputStream().use { output ->
                output.write(header.array())
                val chunk = ByteArray(16_000)
                repeat(120) { output.write(chunk) }
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            app.library.library().insertAssets(listOf(AudioAsset(asset, "media/$asset", file.length(), sha, 120_000)))
            app.library.library().insertTracks(listOf(BookTrack(track, book, asset, index)))
        }
        check(prefs.edit().putString("book", book).commit())
        book
    }

    @Test fun playingUntilExternalTermination() {
        org.junit.Assume.assumeTrue(args.getString("hostDriven") == "true")
        // A real user Play action needs a visible Activity on API35+. The idle
        // phase deliberately launches no Activity/controller and has no such rule.
        val foreground = androidx.test.core.app.ActivityScenario.launch(io.github.jnesew.paudiobooks.ui.MainActivity::class.java)
        val speedStep = requireNotNull(args.getString("speedStep")).toInt()
        require(speedStep in 10..60)
        val speed = speedStep / 20f
        val index = speedStep % 2
        val book = seedBook()
        val c = connect()
        command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book) })
        waitUntil("Ready before seed") { main { c.playbackState == Player.STATE_READY } }
        command(c, PlaybackService.SET_SPEED, Bundle().apply { putFloat(PlaybackService.SPEED, speed) })
        command(c, PlaybackService.SEEK_BOOK_POSITION, Bundle().apply {
            putLong(PlaybackService.BOOK_POSITION_MS, index * 120_000L + 10_000L)
        })
        main { c.play() }
        waitUntil("Actual playback must advance") { main { c.isPlaying && c.currentMediaItemIndex == index && c.currentPosition > 10_200 } }
        // Vary the kill phase relative to periodic checkpoints across the speed matrix.
        Thread.sleep(4_100L + speedStep % 5 * 137L)
        val sample = main {
            check(c.isPlaying && c.currentMediaItemIndex == index)
            Bundle().apply {
                putLong("sampleElapsedMs", SystemClock.elapsedRealtime())
                putLong("samplePositionMs", c.currentPosition)
                putString("track", c.currentMediaItem!!.mediaId)
                putFloat("speed", c.playbackParameters.speed)
                putInt("pid", Process.myPid())
                putString("sourceMode",if (linked) "linked" else "private")
                if (linked) {
                    val opens=io.github.jnesew.paudiobooks.storage.BoundedDocumentAccess.sourceOpens.get()
                    val bytes=io.github.jnesew.paudiobooks.storage.BoundedDocumentAccess.sourceBytesRead.get()
                    assertTrue("Linked playback must actually open and read its source",opens>0 && bytes>0)
                    putLong("linkedSourceOpens",opens); putLong("linkedSourceBytes",bytes)
                }
            }
        }
        check(prefs.edit().putString("track", sample.getString("track"))
            .putFloat("speed", speed).putInt("pid", Process.myPid()).commit())
        instrumentation.sendStatus(2, sample.apply { putString("phase", "AWAITING_EXTERNAL_TERMINATION") })
        // Do not pause, release the controller, or run a successful teardown before the kill.
        Thread.sleep(30_000)
        main { c.pause(); c.release() }
        foreground.close()
        fail("Host failed to terminate the live playing process within 30 seconds")
    }

    @Test fun restoredPausedAfterExternalTermination() {
        org.junit.Assume.assumeTrue(args.getString("hostDriven") == "true")
        val book = requireNotNull(prefs.getString("book", null))
        val track = requireNotNull(prefs.getString("track", null))
        val expectedSpeed = prefs.getFloat("speed", Float.NaN)
        assertNotEquals("Must cross a real process boundary", prefs.getInt("pid", -1), Process.myPid())
        val saved = runBlocking { requireNotNull(app.library.library().progress(book)) }
        assertEquals(track, saved.trackId)
        assertEquals(expectedSpeed, saved.speed, 0.0001f)
        assertFalse(saved.finished)
        if (linked) runBlocking {
            val metadata=requireNotNull(app.library.books().get(book))
            assertEquals("Retain lifecycle narrator",metadata.narratorOverride)
            assertEquals("Retain lifecycle translator",metadata.translatorOverride)
            assertEquals("Retain linked process note",app.library.library().bookmarks(book).single().note)
            for (item in app.library.library().tracks(book)) {
                val asset=requireNotNull(app.library.library().asset(item.assetId))
                val source=requireNotNull(app.library.sources().source(item.assetId))
                assertEquals("",asset.relativePath); assertFalse(asset.identityVerified)
                assertFalse(File(app.noBackupFilesDir,"audiobooks/media/${asset.id}").exists())
                assertTrue(app.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString()==source.grantUri })
            }
        }
        val c = connect()
        try {
            command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book) })
            waitUntil("Ready after restart") { main { c.playbackState == Player.STATE_READY } }
            main {
                assertFalse("Cold restoration must be paused", c.playWhenReady)
                assertFalse(c.isPlaying)
                assertEquals(track, c.currentMediaItem!!.mediaId)
                assertEquals(expectedSpeed, c.playbackParameters.speed, 0.0001f)
                assertTrue("Restored position within 200 ms", abs(c.currentPosition - saved.offsetMs) <= 200)
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("phase", "RESTORED_PAUSED")
                putString("sourceMode",if (linked) "linked" else "private")
                putLong("durablePositionMs", saved.offsetMs)
                putFloat("speed", saved.speed)
                putInt("pid", Process.myPid())
            })
        } finally {
            command(c, PlaybackService.UNLOAD_AND_SAVE)
            main { c.release() }
        }
    }

    @Test fun measuredCheckpointCadenceAndPausedQuiescence() {
        org.junit.Assume.assumeTrue(args.getString("hostDriven") == "true")
        val foreground = androidx.test.core.app.ActivityScenario.launch(io.github.jnesew.paudiobooks.ui.MainActivity::class.java)
        val book = seedBook()
        val original = runBlocking { app.library.library().progress(book) }
        val c = connect()
        try {
            command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book) })
            waitUntil("Ready before cadence measurement") { main { c.playbackState == Player.STATE_READY } }
            for (speed in listOf(.5f, 3f)) {
                command(c, PlaybackService.SET_SPEED, Bundle().apply { putFloat(PlaybackService.SPEED, speed) })
                command(c, PlaybackService.SEEK_BOOK_POSITION, Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 10_000) })
                command(c, PlaybackService.PAUSE_AND_SAVE)
                Thread.sleep(500)
                var previous = runBlocking { requireNotNull(app.library.library().progress(book)) }
                val commits = mutableListOf<Pair<Long, Long>>()
                main { c.play() }
                waitUntil("Playback before cadence measurement") { main { c.isPlaying } }
                val started = SystemClock.elapsedRealtime()
                // Only the test polls Room. No database export or production observer/hook.
                while (SystemClock.elapsedRealtime() - started < 12_000) {
                    val current = runBlocking { requireNotNull(app.library.library().progress(book)) }
                    if (current != previous) {
                        assertEquals(previous.trackId, current.trackId)
                        assertTrue(current.offsetMs >= previous.offsetMs)
                        assertTrue("Healthy committed media gap <= five seconds", current.offsetMs - previous.offsetMs <= 5000)
                        commits += (SystemClock.elapsedRealtime() - started) to current.offsetMs
                        previous = current
                    }
                    Thread.sleep(100)
                }
                // At 3x, the 500ms player poll can yield 1.5s wall intervals;
                // seven complete intervals fit strictly before this 12s deadline.
                assertTrue("Measured durable commits at $speed: $commits", commits.size in if (speed < 1) 3..6 else 7..14)
                assertTrue("Healthy current-to-durable lag <= five media seconds", main { c.currentPosition } - previous.offsetMs in 0..5000)
                command(c, PlaybackService.PAUSE_AND_SAVE)
                Thread.sleep(500) // Let the public pause acknowledgement checkpoint drain.
                command(c, PlaybackService.PAUSE_AND_SAVE)
                val paused = runBlocking { requireNotNull(app.library.library().progress(book)) }
                val quietStart = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - quietStart < 6000) {
                    assertEquals("No durable progress rewrites during paused observation", paused,
                        runBlocking { app.library.library().progress(book) })
                    Thread.sleep(100)
                }
                instrumentation.sendStatus(2, Bundle().apply {
                    putFloat("cadenceSpeed", speed)
                    putString("committedElapsedAndPositionMs", commits.joinToString { "${it.first}:${it.second}" })
                    putLong("pausedObservationMs", SystemClock.elapsedRealtime() - quietStart)
                })
            }
        } finally {
            try { command(c, PlaybackService.UNLOAD_AND_SAVE) }
            finally {
                main { c.release() }
                runBlocking {
                    if (original == null) app.library.library().deleteProgress(book)
                    else app.library.library().saveProgress(original)
                }
                foreground.close()
            }
        }
    }

    @Test fun pausedIdleWithoutController() {
        org.junit.Assume.assumeTrue(args.getString("hostDriven") == "true")
        val book = seedBook()
        val c = connect()
        command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book) })
        waitUntil("Ready before idle") { main { c.playbackState == Player.STATE_READY } }
        command(c, PlaybackService.SEEK_BOOK_POSITION, Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 147_000L) })
        command(c, PlaybackService.PAUSE_AND_SAVE)
        main { c.release() }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("phase", "PAUSED_WITHOUT_CONTROLLER")
            putString("sourceMode",if (linked) "linked" else "private")
            putLong("elapsedMs", SystemClock.elapsedRealtime())
        })
        // Remain in this process without connecting a controller or polling the service.
        Thread.sleep(615_000)
        val saved = runBlocking { requireNotNull(app.library.library().progress(book)) }
        assertEquals(27_000L, saved.offsetMs)
        assertEquals(runBlocking { app.library.library().tracks(book) }[1].id, saved.trackId)
        if (linked) runBlocking {
            assertTrue("Expiry must close all document resources",io.github.jnesew.paudiobooks.storage.BoundedDocumentAccess.awaitIdle())
            assertEquals("Retain linked process note",app.library.library().bookmarks(book).single().note)
            for (track in app.library.library().tracks(book)) {
                val source=requireNotNull(app.library.sources().source(track.assetId))
                assertTrue(app.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString()==source.grantUri })
                assertFalse(File(app.noBackupFilesDir,"audiobooks/media/${track.assetId}").exists())
            }
            instrumentation.sendStatus(2,Bundle().apply { putString("phase","LINKED_IDLE_RETAINED"); putBoolean("documentResourcesClosed",true) })
        }
    }
}
