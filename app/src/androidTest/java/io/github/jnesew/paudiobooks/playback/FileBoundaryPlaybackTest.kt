package io.github.jnesew.paudiobooks.playback

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.library.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FileBoundaryPlaybackTest {
    @get:org.junit.Rule val foreground=ForegroundPlaybackRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(action:()->T):T { var value:T?=null; instrumentation.runOnMainSync { value=action() }; @Suppress("UNCHECKED_CAST") return value as T }
    private fun await(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+10000
        while (!condition()) { assertTrue(message,SystemClock.elapsedRealtime()<deadline); Thread.sleep(25) }
    }
    @Test fun legacyPrivateFilesHaveStableNavigableChaptersWithoutDatabaseOrNoteRewrites() = runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val book=UUID.randomUUID().toString()
        val tracks=(0..1).map { BookTrack(UUID.randomUUID().toString(),book,UUID.randomUUID().toString(),it) }
        val files=tracks.map { File(app.noBackupFilesDir,"audiobooks/media/${it.assetId}") }
        var controller:MediaController?=null
        fun command(action:String,args:Bundle=Bundle.EMPTY)=main { requireNotNull(controller).sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args) }.get(15,TimeUnit.SECONDS)
        try {
            val assets=files.mapIndexed { index,file ->
                val length=5*16000
                val bytes=ByteBuffer.allocate(44+length).order(ByteOrder.LITTLE_ENDIAN)
                bytes.put("RIFF".toByteArray()).putInt(36+length).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(length)
                bytes.put(44,index.toByte()); file.parentFile!!.mkdirs(); file.writeBytes(bytes.array())
                val hash=MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
                AudioAsset(tracks[index].assetId,"media/${tracks[index].assetId}",file.length(),hash,5000,"audio/raw")
            }
            app.library.books().insert(Book(book,"Synthetic legacy file boundaries",translatorOverride="Keep boundary translator"))
            app.library.library().insertAssets(assets); app.library.library().insertTracks(tracks)
            val saved=ReadingState(book,tracks.first().id,1000,1.25f,false,123)
            val note=Bookmark(UUID.randomUUID().toString(),book,tracks.first().id,500,"Keep legacy boundary note",456)
            app.library.library().saveProgress(saved); app.library.library().insertBookmark(note)
            val details=requireNotNull(app.repository.observeBookDetails(book).first { it?.tracks?.size==2 })
            val playable=requireNotNull(app.repository.playableBook(book))
            assertEquals(2,details.chapters.size)
            assertEquals(details.chapters.map { it.id },playable.tracks.flatMap { it.chapters }.map { it.id })
            assertEquals(details.chapters.map { it.id },app.repository.playableBook(book)!!.tracks.flatMap { it.chapters }.map { it.id })
            assertEquals(listOf(0L,0L),details.chapters.map { it.startMs })
            for (track in tracks) assertTrue(app.library.library().chapters(track.id).isEmpty())
            assertEquals(saved,app.library.library().progress(book)); assertEquals(listOf(note),app.library.library().bookmarks(book))
            val future=main { MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() }
            controller=future.get(15,TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.LOAD_BOOK,Bundle().apply { putString(PlaybackService.BOOK_ID,book) }).resultCode)
            await("Legacy player ready") { main { controller!!.playbackState==Player.STATE_READY } }
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.SEEK_CHAPTER,Bundle().apply { putString(PlaybackService.CHAPTER_ID,details.chapters.last().id) }).resultCode)
            await("Derived file chapter seek acknowledged") { main { controller!!.currentMediaItemIndex==1 && controller.playbackState==Player.STATE_READY } }
            assertFalse(main { controller!!.playWhenReady })
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackService.PAUSE_AND_SAVE).resultCode)
            assertEquals(tracks.last().id,app.library.library().progress(book)!!.trackId)
            assertEquals(listOf(note),app.library.library().bookmarks(book))
            assertEquals("Keep boundary translator",app.library.books().get(book)!!.translatorOverride)
            for (track in tracks) assertTrue(app.library.library().chapters(track.id).isEmpty())
        } finally {
            if (controller!=null) { runCatching { command(PlaybackService.UNLOAD_AND_SAVE) }; main { controller.release() } }
            app.importer.removeBook(book)
            files.forEach { if (it.exists()) it.delete() }
        }
    }
}
