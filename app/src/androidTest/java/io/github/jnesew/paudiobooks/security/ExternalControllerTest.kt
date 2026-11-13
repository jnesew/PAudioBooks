package io.github.jnesew.paudiobooks.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.session.MediaSession
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
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
import io.github.jnesew.paudiobooks.playback.PlaybackService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExternalControllerTest {
    @get:org.junit.Rule
    val foregroundPlayback = io.github.jnesew.paudiobooks.playback.ForegroundPlaybackRule()

    // The public Media3 token bundle carries a framework Parcelable; search by type so the
    // test does not depend on Media3's private bundle-key values or reflection.
    private fun platformToken(bundle: Bundle): MediaSession.Token? {
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION") val value = bundle.get(key)
            if (value is MediaSession.Token) return value
            if (value is Bundle) platformToken(value)?.let { return it }
        }
        return null
    }

    @Test fun ordinaryDifferentUidCannotControlPlaybackOrImportUris() {
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = UUID.randomUUID().toString()
        val asset = UUID.randomUUID().toString()
        val track = UUID.randomUUID().toString()
        val media = File(app.noBackupFilesDir, "audiobooks/media/$asset")
        media.parentFile!!.mkdirs()
        val dataLength = 16000 * 30
        val bytes = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + dataLength).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(dataLength)
        media.writeBytes(bytes.array())
        runBlocking {
            app.library.books().insert(Book(id, "Security fixture"))
            app.library.library().insertAssets(listOf(AudioAsset(asset, "media/$asset", media.length(), "synthetic", 30000)))
            app.library.library().insertTracks(listOf(BookTrack(track, id, asset, 0)))
        }
        val serviceQueue = ArrayBlockingQueue<IBinder>(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { serviceQueue.offer(binder) }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        var bound = false
        var controller: MediaController? = null
        try {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            instrumentation.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val own = future.get(20, TimeUnit.SECONDS)
            controller = own
            lateinit var command: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync {
                command = own.sendCustomCommand(SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY), Bundle().apply { putString(PlaybackService.BOOK_ID, id) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, command.get(20, TimeUnit.SECONDS).resultCode)
            repeat(100) {
                var ready = false
                instrumentation.runOnMainSync { ready = own.playbackState == Player.STATE_READY }
                if (!ready) Thread.sleep(50)
            }
            instrumentation.runOnMainSync { assertEquals(Player.STATE_READY, own.playbackState); own.play() }
            var playing = false
            repeat(50) { instrumentation.runOnMainSync { playing = own.isPlaying }; if (!playing) Thread.sleep(100) }
            assertTrue("Same-UID controller must play", playing)
            instrumentation.runOnMainSync { own.pause(); own.seekTo(500) }
            Thread.sleep(400)
            instrumentation.runOnMainSync { command = own.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, command.get(20, TimeUnit.SECONDS).resultCode)
            val count = runBlocking { app.library.books().all().size }
            val bookBefore = runBlocking { app.library.books().get(id) }
            val tracksBefore = runBlocking { app.library.library().tracks(id) }
            val progressBefore = runBlocking { app.library.library().progress(id) }
            var token: MediaSession.Token? = null
            instrumentation.runOnMainSync { token = platformToken(requireNotNull(own.connectedToken).toBundle()) }
            assertNotNull("Connected token must contain the framework token", token)
            val testContext = instrumentation.context
            val intent = Intent().setComponent(ComponentName(testContext.packageName, JavaExternalControllerService::class.java.name))
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            assertTrue("Test-package service must bind", bound)
            val remote = Messenger(requireNotNull(serviceQueue.poll(10, TimeUnit.SECONDS)))
            val replies = ArrayBlockingQueue<Bundle>(1)
            val response = Messenger(Handler(Looper.getMainLooper()) { msg -> replies.offer(msg.data); true })
            val request = Message.obtain().apply { replyTo = response; data = Bundle().apply { putParcelable("token", token) } }
            remote.send(request)
            val result = requireNotNull(replies.poll(10, TimeUnit.SECONDS))
            assertNotEquals("Controller must have a distinct UID", Process.myUid(), result.getInt("uid"))
            assertTrue("Framework controller must dispatch or reject registration: ${result.getString("error")}",
                result.getBoolean("registrationRejected") || result.getInt("attempted") == 7)
            instrumentation.sendStatus(0, Bundle().apply {
                putInt("target_uid", Process.myUid())
                putInt("controller_uid", result.getInt("uid"))
                putInt("attempted", result.getInt("attempted"))
                putInt("security_exceptions", result.getInt("rejected"))
                putBoolean("registration_rejected", result.getBoolean("registrationRejected"))
            })
            Thread.sleep(1500)
            instrumentation.runOnMainSync {
                assertFalse("External play must be rejected", own.playWhenReady)
                assertEquals("External seek must be rejected", 500L, own.currentPosition)
                assertEquals(track, own.currentMediaItem?.mediaId)
            }
            assertEquals(count, runBlocking { app.library.books().all().size })
            assertEquals(bookBefore, runBlocking { app.library.books().get(id) })
            assertEquals(tracksBefore, runBlocking { app.library.library().tracks(id) })
            assertEquals(progressBefore, runBlocking { app.library.library().progress(id) })
        } finally {
            if (bound) app.unbindService(connection)
            controller?.let { own ->
                lateinit var unload: com.google.common.util.concurrent.ListenableFuture<SessionResult>
                instrumentation.runOnMainSync { unload = own.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
                runCatching { unload.get(20, TimeUnit.SECONDS) }
                instrumentation.runOnMainSync { own.release() }
            }
            runBlocking { app.importer.removeBook(id) }
        }
    }
}
