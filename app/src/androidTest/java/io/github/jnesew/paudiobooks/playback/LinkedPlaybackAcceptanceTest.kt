package io.github.jnesew.paudiobooks.playback

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.media3.common.Player
import androidx.media3.session.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.importing.*
import io.github.jnesew.paudiobooks.library.*
import io.github.jnesew.paudiobooks.storage.BoundedDocumentAccess
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LinkedPlaybackAcceptanceTest {
    @get:org.junit.Rule val foreground=ForegroundPlaybackRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private fun <T> main(action:()->T):T {
        var result:T?=null
        instrumentation.runOnMainSync { result=action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(message:String,check:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+10000
        while (!check()) { assertTrue(message,SystemClock.elapsedRealtime()<deadline); Thread.sleep(25) }
    }
    private fun configure(method:String,root:String) {
        requireNotNull(app.contentResolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),method,root,null))
    }
    @Test fun pendingLinkedDurationLoadsSeeksAndSavesThroughTheSingleServiceWithoutAudioCopy() = runBlocking<Unit> {
        val rootId="linked-test-playback-${UUID.randomUUID()}-root"
        configure("linked-grant",rootId)
        val root=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",rootId)
        val selected=DocumentInventory(app.contentResolver).describe(DocumentsContract.buildDocumentUriUsingTree(root,"linked-long-a"))
        var preview=app.linker.prepare(listOf(selected),listOf(root),DocumentsContract.buildDocumentUriUsingTree(root,"linked-book-a"),root)
        preview=preview.copy(tracks=preview.tracks.map { it.copy(metadata=it.metadata.copy(durationMs=null,chapters=listOf(ImportChapter(0,null,"Pending file"))),pending=true) })
        val bookId=UUID.randomUUID().toString()
        var controller:MediaController?=null
        fun command(action:String,args:Bundle=Bundle.EMPTY):SessionResult = main { requireNotNull(controller).sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args) }.get(15,TimeUnit.SECONDS)
        val media=File(app.noBackupFilesDir,"audiobooks/media")
        val before=media.listFiles().orEmpty().map { it.name }.toSet()
        try {
            app.linker.commit(preview,preview.tracks,"Synthetic linked playback",bookId)
            val track=app.library.library().tracks(bookId).single()
            app.library.library().insertBookmark(Bookmark(UUID.randomUUID().toString(),bookId,track.id,700,"Keep playback link note",123))
            val future=main { MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() }
            controller=future.get(15,TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.LOAD_BOOK,Bundle().apply { putString(PlaybackService.BOOK_ID,bookId) }).resultCode)
            await("Linked source did not become ready") { main { controller!!.playbackState==Player.STATE_READY } }
            assertEquals(track.id,main { controller!!.currentMediaItem?.mediaId })
            main { controller!!.play() }
            await("Linked audio did not play") { main { controller!!.isPlaying && controller!!.currentPosition>100 } }
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.SET_SPEED,Bundle().apply { putFloat(PlaybackService.SPEED,1.25f) }).resultCode)
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.SEEK_TRACK_POSITION,Bundle().apply {
                putString(PlaybackService.BOOK_ID,bookId); putString(PlaybackService.TRACK_ID,track.id); putLong(PlaybackService.TRACK_POSITION_MS,4000)
            }).resultCode)
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.PAUSE_AND_SAVE).resultCode)
            val saved=app.library.library().progress(bookId)!!
            assertEquals(track.id,saved.trackId); assertTrue(saved.offsetMs in 4000..4500); assertEquals(1.25f,saved.speed)
            val timeout=SystemClock.elapsedRealtime()+5000
            while (app.library.library().asset(track.assetId)!!.durationMs==null && SystemClock.elapsedRealtime()<timeout) kotlinx.coroutines.delay(10)
            assertTrue(app.library.library().asset(track.assetId)!!.durationMs in 29900L..30100L)
            assertEquals("Keep playback link note",app.library.library().bookmarks(bookId).single().note)
            assertEquals(before,media.listFiles().orEmpty().map { it.name }.toSet())
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.UNLOAD_AND_SAVE).resultCode)
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.LOAD_BOOK,Bundle().apply { putString(PlaybackService.BOOK_ID,bookId) }).resultCode)
            await("Linked cold load failed") { main { controller!!.playbackState==Player.STATE_READY && !controller!!.playWhenReady } }
            assertTrue(kotlin.math.abs(main { controller!!.currentPosition }-saved.offsetMs)<=200)
        } finally {
            if (controller!=null) { runCatching { command(PlaybackService.UNLOAD_AND_SAVE) }; main { controller!!.release() } }
            app.importer.removeBook(bookId)
            app.importer.cancel(preview.jobId)
            assertTrue(BoundedDocumentAccess.awaitIdle())
        }
    }
    @Test fun revokedTreeFailsPlaybackWithoutErasingHistoryOrNotes() = runBlocking<Unit> {
        val rootId="linked-test-playback-${UUID.randomUUID()}-root"
        configure("linked-grant",rootId)
        val root=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",rootId)
        val selected=DocumentInventory(app.contentResolver).describe(DocumentsContract.buildDocumentUriUsingTree(root,"linked-long-b"))
        val preview=app.linker.prepare(listOf(selected),listOf(root),DocumentsContract.buildDocumentUriUsingTree(root,"linked-book-b"),root)
        val bookId=UUID.randomUUID().toString()
        var controller:MediaController?=null
        fun command(action:String,args:Bundle=Bundle.EMPTY):SessionResult = main { requireNotNull(controller).sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args) }.get(15,TimeUnit.SECONDS)
        try {
            app.linker.commit(preview,preview.tracks,"Synthetic revoked linked playback",bookId)
            val track=app.library.library().tracks(bookId).single()
            app.library.library().saveProgress(ReadingState(bookId,track.id,7000,1.25f,false,123))
            app.library.library().insertBookmark(Bookmark(UUID.randomUUID().toString(),bookId,track.id,6000,"Keep revoked link note",456))
            configure("linked-revoke",rootId)
            val future=main { MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() }
            controller=future.get(15,TimeUnit.SECONDS)
            command(PlaybackService.LOAD_BOOK,Bundle().apply { putString(PlaybackService.BOOK_ID,bookId) })
            await("Revocation did not produce source failure") { main { controller!!.playerError!=null } }
            assertEquals(track.id,app.library.library().progress(bookId)!!.trackId)
            assertEquals(7000L,app.library.library().progress(bookId)!!.offsetMs)
            assertEquals("Keep revoked link note",app.library.library().bookmarks(bookId).single().note)
            assertNotNull(app.library.sources().source(track.assetId)); assertNotNull(app.library.books().get(bookId))
            command(PlaybackService.UNLOAD_AND_SAVE)
            configure("linked-grant",rootId)
            assertEquals(1,app.linker.reauthorize(bookId,root))
            assertFalse(app.library.library().asset(track.assetId)!!.identityVerified)
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.LOAD_BOOK,Bundle().apply { putString(PlaybackService.BOOK_ID,bookId) }).resultCode)
            await("Same-tree access renewal did not recover paused playback") { main { controller!!.playbackState==Player.STATE_READY && !controller!!.playWhenReady } }
            assertEquals("Keep revoked link note",app.library.library().bookmarks(bookId).single().note)
        } finally {
            if (controller!=null) { runCatching { command(PlaybackService.UNLOAD_AND_SAVE) }; main { controller!!.release() } }
            app.importer.removeBook(bookId); app.importer.cancel(preview.jobId)
            assertTrue(BoundedDocumentAccess.awaitIdle())
        }
    }
}
