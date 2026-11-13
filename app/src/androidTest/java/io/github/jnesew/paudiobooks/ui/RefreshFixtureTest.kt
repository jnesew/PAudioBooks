package io.github.jnesew.paudiobooks.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Persistent original synthetic media for native comparison; never replaces user rows. */
@RunWith(AndroidJUnit4::class)
class RefreshFixtureTest {
    private val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val prefs get() = app.getSharedPreferences("synthetic_refresh_fixture", 0)
    private fun hostOnly() = org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")
    @Test fun seedNativeRefreshBook() = runBlocking {
        hostOnly()
        if (prefs.contains("book")) { assertNotNull(app.library.books().get(requireNotNull(prefs.getString("book", null)))); return@runBlocking }
        fun id() = UUID.randomUUID().toString()
        val book = id(); val asset = id(); val track = id(); val mark = id(); val cover = id()
        val length = 180 * 16000
        val data = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36+length).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(length)
        val file = File(app.noBackupFilesDir, "audiobooks/media/$asset").also { it.parentFile!!.mkdirs() }
        file.writeBytes(data.array())
        val hash = MessageDigest.getInstance("SHA-256").digest(data.array()).joinToString("") { "%02x".format(it) }
        val image = Bitmap.createBitmap(192,128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(Color.rgb(35,95,75))
        val paint = Paint().apply { color = Color.rgb(215,165,65) }
        canvas.drawRect(0f,0f,64f,128f,paint); canvas.drawCircle(128f,64f,36f,paint)
        val art = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray(); image.recycle()
        app.audioStore.writeCover(cover, art)
        try {
            app.library.withTransaction {
                app.library.books().insert(Book(book,"UI refresh acceptance",importedAuthor="Synthetic Author",importedNarrator="Synthetic Narrator",
                    importedSeries="Synthetic Series",importedTranslator="Synthetic Translator",importedSeriesNumber="2.5",coverPath="covers/$cover"))
                app.library.library().insertAssets(listOf(AudioAsset(asset,"media/$asset",file.length(),hash,180_000,"audio/raw")))
                app.library.library().insertTracks(listOf(BookTrack(track,book,asset,0)))
                app.library.library().insertChapters(listOf(Chapter(id(),track,0,0,90_000,"First chapter","synthetic"),Chapter(id(),track,1,90_000,180_000,"Second chapter","synthetic")))
                app.library.library().saveProgress(ReadingState(book,track,15_000,1.25f,false,123))
                app.library.library().insertBookmark(Bookmark(mark,book,track,20_000,"Retain refresh note",456))
            }
            assertTrue(prefs.edit().putString("book",book).putString("asset",asset).putString("track",track)
                .putString("mark",mark).putString("hash",hash).commit())
        } catch (e: Exception) { app.importer.removeBook(book); throw e }
    }
    @Test fun verifyNativeContributorEditsPreserveMediaAndNotes() = runBlocking<Unit> {
        hostOnly()
        val book = requireNotNull(prefs.getString("book",null)); val asset = requireNotNull(prefs.getString("asset",null))
        val row = requireNotNull(app.library.books().get(book))
        assertEquals("Synthetic Translator",row.importedTranslator); assertEquals("2.5",row.importedSeriesNumber)
        assertEquals("Edited refresh translator",row.translatorOverride); assertEquals("4.5",row.seriesNumberOverride)
        val track = requireNotNull(prefs.getString("track",null)); val mark = requireNotNull(prefs.getString("mark",null))
        assertEquals(listOf(Bookmark(mark,book,track,20_000,"Retain refresh note",456)),app.library.library().bookmarks(book))
        assertEquals(2,app.library.library().chapters(track).size)
        val audio = requireNotNull(app.library.library().asset(asset))
        assertTrue(app.audioStore.matches(audio.relativePath,audio.sizeBytes,requireNotNull(prefs.getString("hash",null))))
        val progress = requireNotNull(app.library.library().progress(book))
        InstrumentationRegistry.getArguments().getString("expectedOffset")?.toLong()?.let { assertEquals(it, progress.offsetMs) }
    }
}
