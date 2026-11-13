package io.github.jnesew.paudiobooks.playback

import android.content.ComponentName
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ScreenOffTimerTest {
    @get:org.junit.Rule val foreground = ForegroundPlaybackRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(action: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = action() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    @Test fun countdownAt2xExpiresAndSavesWhileScreenOffInForcedDoze() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        val deepWasEnabled = shell("dumpsys deviceidle enabled deep").trim() == "1"
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val book = UUID.randomUUID().toString()
        val linked=InstrumentationRegistry.getArguments().getString("sourceMode", "private")=="linked"
        var asset = UUID.randomUUID().toString()
        var track = UUID.randomUUID().toString()
        val file = File(app.noBackupFilesDir, "audiobooks/media/$asset")
        if (linked) runBlocking {
            val rootId="linked-test-life-${UUID.randomUUID()}-root"
            requireNotNull(app.contentResolver.call(android.net.Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",rootId,null))
            val root=android.provider.DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",rootId)
            val selected=io.github.jnesew.paudiobooks.importing.DocumentInventory(app.contentResolver).describe(android.provider.DocumentsContract.buildDocumentUriUsingTree(root,"linked-life-a"))
            val preview=app.linker.prepare(listOf(selected),listOf(root))
            app.linker.commit(preview,preview.tracks,"Synthetic linked screen-off timer",book)
            val item=app.library.library().tracks(book).single(); asset=item.assetId; track=item.id
            app.repository.addBookmark(book,track,100,"Retain linked Doze note")
        } else {
            file.parentFile!!.mkdirs()
            val size = 16000 * 60
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size)
            file.outputStream().use { out -> out.write(header.array()); repeat(60) { out.write(ByteArray(16000)) } }
            runBlocking {
                app.library.books().insert(Book(book, "Synthetic screen-off timer"))
                app.library.library().insertAssets(listOf(AudioAsset(asset, "media/$asset", file.length(), "synthetic-timer", 60_000)))
                app.library.library().insertTracks(listOf(BookTrack(track, book, asset, 0)))
            }
        }
        val c = main { MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }.get(15, TimeUnit.SECONDS)
        fun command(action: String, args: Bundle = Bundle.EMPTY) {
            assertEquals(SessionResult.RESULT_SUCCESS, main { c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }.get(15, TimeUnit.SECONDS).resultCode)
        }
        try {
            command(PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book) })
            command(PlaybackService.SET_SPEED, Bundle().apply { putFloat(PlaybackService.SPEED, 2f) })
            main { c.play() }
            val readyDeadline = SystemClock.elapsedRealtime() + 10_000
            while (!main { c.isPlaying && c.playbackState == Player.STATE_READY && c.currentPosition > 200 }) {
                check(SystemClock.elapsedRealtime() < readyDeadline) { "Actual playback must start before screen-off" }
                Thread.sleep(25)
            }
            val baseline = main { c.currentPosition }
            val started = SystemClock.elapsedRealtime()
            command(PlaybackService.SET_SLEEP_TIMER, Bundle().apply { putLong(PlaybackService.SLEEP_DURATION_MS, 6000) })
            if (!deepWasEnabled) shell("dumpsys deviceidle enable deep")
            shell("dumpsys battery unplug")
            shell("input keyevent 223")
            val idleResult = shell("dumpsys deviceidle force-idle")
            assertTrue("Force-idle must actually succeed: $idleResult", idleResult.contains("forced", ignoreCase = true))
            assertEquals("IDLE", shell("dumpsys deviceidle get deep").trim())
            var power = shell("dumpsys power")
            val sleepDeadline = SystemClock.elapsedRealtime() + 2000
            while (!power.contains("mWakefulness=Asleep") && SystemClock.elapsedRealtime() < sleepDeadline) {
                Thread.sleep(50)
                power = shell("dumpsys power")
            }
            val powerSummary = power.lineSequence().filter { it.contains("Wakefulness") || it.contains("Display Power") }.joinToString("; ")
            assertTrue("Display must actually be asleep: $powerSummary", power.contains("mWakefulness=Asleep"))
            while (main { c.playWhenReady }) {
                check(SystemClock.elapsedRealtime() - started < 10_000) { "Screen-off timer did not pause" }
                Thread.sleep(25)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val projectedPaused = main { c.currentPosition }
            assertTrue("Six elapsed seconds, independent of 2x speed: $elapsed", elapsed in 5500..8500)
            assertTrue("Playback must advance while screen off: $baseline -> $projectedPaused", projectedPaused - baseline in 10_000..15_000)
            // A pre-existing controller can freeze its last extrapolated position
            // when the pause packet arrives (observed 227 ms beyond the service).
            // A fresh session snapshot observes the actual paused player position,
            // without sending any command that could force another progress save.
            val observer = main { MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }.get(15, TimeUnit.SECONDS)
            val paused = try {
                val snapshotDeadline = SystemClock.elapsedRealtime() + 3000
                while (!main { observer.playbackState == Player.STATE_READY && !observer.playWhenReady }) {
                    check(SystemClock.elapsedRealtime() < snapshotDeadline) { "Fresh observer must see the timer pause" }
                    Thread.sleep(25)
                }
                main { observer.currentPosition }
            } finally { main { observer.release() } }
            val deadline = SystemClock.elapsedRealtime() + 3000
            var saved = runBlocking { app.library.library().progress(book) }
            while (saved == null || abs(saved.offsetMs - paused) > 200) {
                check(SystemClock.elapsedRealtime() < deadline) { "Timer pause must persist: controller=$paused durable=${saved?.offsetMs} state=${main { c.playbackState }} playing=${main { c.isPlaying }} saveFailed=${main { c.sessionExtras.getBoolean(PlaybackService.SAVE_FAILED) }}" }
                Thread.sleep(25)
                saved = runBlocking { app.library.library().progress(book) }
            }
            assertEquals(track, saved.trackId)
            assertEquals(2f, saved.speed, 0.0001f)
            if (linked) runBlocking {
                assertEquals("Retain linked Doze note",app.library.library().bookmarks(book).single().note)
                assertNotNull(app.library.sources().source(asset))
                assertFalse(File(app.noBackupFilesDir,"audiobooks/media/$asset").exists())
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putLong("timerElapsedMs", elapsed); putLong("mediaAdvanceMs", paused - baseline)
                putLong("projectedPausedMs", projectedPaused); putLong("freshPausedMs", paused)
                putLong("durablePausedMs", saved.offsetMs)
                putBoolean("forcedDoze", true); putBoolean("screenOff", true)
                putString("sourceMode",if (linked) "linked" else "private")
                if (linked) {
                    val bytes=io.github.jnesew.paudiobooks.storage.BoundedDocumentAccess.sourceBytesRead.get()
                    assertTrue("Linked timer must actually read source audio",bytes>0)
                    putLong("linkedSourceBytes",bytes)
                }
            })
        } finally {
            shell("dumpsys deviceidle unforce")
            if (!deepWasEnabled) shell("dumpsys deviceidle disable deep")
            shell("dumpsys battery reset")
            shell("input keyevent 224")
            try { command(PlaybackService.UNLOAD_AND_SAVE) }
            finally { main { c.release() } }
            if (linked) runBlocking {
                app.importer.removeBook(book)
                assertTrue(io.github.jnesew.paudiobooks.storage.BoundedDocumentAccess.awaitIdle())
            } else {
                runBlocking { app.library.books().delete(book); app.library.library().deleteAsset(asset) }
                file.delete()
            }
        }
    }
}
