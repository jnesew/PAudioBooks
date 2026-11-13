package io.github.jnesew.paudiobooks.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.*
import io.github.jnesew.paudiobooks.preferences.ThemeChoice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native production windows, not a test-only dialog. Fixtures and settings are restored. */
@RunWith(AndroidJUnit4::class)
class BookInformationAcceptanceTest {
    private val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation by lazy { instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    } }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var count = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (++count > 700 || depth > 30 || !node.refresh()) return null
            if (node.isVisibleToUser && predicate(node)) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child,depth+1)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val windows = automation.windows
        try { for (window in windows) if (window.isActive || window.isFocused) {
            val root = window.root ?: continue
            try { visit(root,0)?.let { return it } } finally { root.recycle() }
        } } finally { windows.forEach { it.recycle() } }
        return null
    }
    private fun has(text: String): Boolean = find { it.text?.toString()?.contains(text) == true || it.contentDescription?.toString() == text }
        ?.let { it.recycle(); true } ?: false
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime()+8000
        while (SystemClock.elapsedRealtime()<end) { if (condition()) return; Thread.sleep(50) }
        capture("failure"); error(message)
    }
    private fun scroll(direction: Int): Boolean {
        val node = find { it.isScrollable } ?: return false
        return try { node.performAction(direction) } finally { node.recycle() }
    }
    private fun reveal(text: String) {
        val end=SystemClock.elapsedRealtime()+15000
        while (SystemClock.elapsedRealtime()<end) {
            if (has(text)) return
            scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(200)
        }
        capture("failure")
        error("Cannot reveal synthetic test control: $text")
    }
    private fun hasEnabledAction(source: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo?=AccessibilityNodeInfo.obtain(source)
        try { repeat(10) {
            val current=node ?: return false
            if (current.isClickable && current.isEnabled) return true
            node=current.parent; current.recycle()
        } } finally { node?.recycle() }
        return false
    }
    private fun click(text: String) {
        reveal(text)
        val end=SystemClock.elapsedRealtime()+8000
        while (SystemClock.elapsedRealtime()<end) {
            var node=find { (it.text?.toString()==text || it.contentDescription?.toString()==text) && hasEnabledAction(it) }
            try { repeat(10) {
                val current=node ?: return@repeat
                if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                node=current.parent; current.recycle()
            } } finally { node?.recycle() }
            Thread.sleep(50)
        }
        capture("failure")
        error("No enabled native action for $text")
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(600)
        val image=requireNotNull(automation.takeScreenshot())
        try { File(app.cacheDir,"book-information-$name.png").outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } }
        finally { image.recycle() }
    }
    private fun outside(title: String) {
        val node=find { it.text?.toString()==title && !it.isClickable } ?: error("Missing dialog heading")
        val bounds=Rect(); try { node.getBoundsInScreen(bounds) } finally { node.recycle() }
        instrumentation.waitForIdleSync()
        // InputDialog has a full-screen scrim and a 24 dp side margin.
        val x=1f; val y=bounds.exactCenterY(); val now=SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event,true)) } finally { event.recycle() }
        }
    }
    private fun pane(title: String) = find { AccessibilityNodeInfoCompat.wrap(it).paneTitle?.toString()==title }?.let { it.recycle(); true } ?: false

    private fun fixture(populated: Boolean, trial: (ActivityScenario<MainActivity>,String) -> Unit) = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven")=="true")
        automation; instrumentation.waitForIdleSync()
        val settings=app.settings.library.first(); val theme=app.settings.theme.first()
        fun id()=UUID.randomUUID().toString()
        val book=id(); val asset=id(); val track=id(); val title="000 Information ${book.take(8)}"
        val media=File(app.noBackupFilesDir,"audiobooks/media/$asset"); media.parentFile!!.mkdirs()
        if (populated) {
            val size=16000*120
            val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(size+36)
                .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
            media.outputStream().use { it.write(header.array()); repeat(120) { _ -> it.write(ByteArray(16000)) } }
        }
        val description="A traveller returns to a coastal village and finds a lantern burning in an empty house.\n\nAs the winter closes in, old friendships and a forgotten promise lead her to the person who kept it alight."
        val row=Book(book,title,importedGenre=if (populated) "Fiction" else " ",importedPublisher=if (populated) "Harbour Press" else null,
            importedDate="1999",importedReleaseDate=if (populated) "2021" else "2021-02-29",importedDescription=if (populated) description else null)
        val progress=ReadingState(book,track,13000,1.25f,false,123)
        val note=Bookmark(id(),book,track,9000,"Retain information note",456)
        try {
            app.library.books().insert(row)
            app.library.library().insertAssets(listOf(AudioAsset(asset,"media/$asset",media.length(),"synthetic-information",if (populated) 120000 else null,"audio/raw")))
            app.library.library().insertTracks(listOf(BookTrack(track,book,asset,0)))
            app.library.library().saveProgress(progress); app.library.library().insertBookmark(note)
            app.settings.setLibrarySort("TITLE"); app.settings.setLibraryFilter("ALL"); app.settings.setLibraryGrid(false)
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                scenario.onActivity { activity ->
                    val configuration=activity.resources.configuration
                    if (InstrumentationRegistry.getArguments().getString("requireRtl")=="true")
                        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL,configuration.layoutDirection)
                    if (InstrumentationRegistry.getArguments().getString("requireLargeLandscape")=="true") {
                        assertEquals(2f,configuration.fontScale,0.01f)
                        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,configuration.orientation)
                    }
                }
                if (app.library.library().jobs().isNotEmpty()) click("Later")
                await("Library inventory ready at its header") {
                    if (has(app.getString(R.string.search_books))) true
                    else { scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); false }
                }
                click(title)
                trial(scenario,description)
            }
            assertEquals(row,app.library.books().get(book)); assertEquals(progress,app.library.library().progress(book))
            assertEquals(listOf(note),app.library.library().bookmarks(book))
        } finally {
            if (app.repository.bookExists(book)) app.importer.removeBook(book)
            media.delete()
            app.settings.setTheme(theme); app.settings.setLibrarySort(settings.sort); app.settings.setLibraryFilter(settings.filter); app.settings.setLibraryGrid(settings.grid)
        }
    }

    @Test fun catalogAndAudioWindowsNavigateRecreateAndDismissWithoutChangingListeningState() = fixture(true) { scenario,description ->
        for (theme in listOf(ThemeChoice.LIGHT,ThemeChoice.DARK)) {
            runBlocking { app.settings.setTheme(theme) }
            click("Book information"); await("Book information pane") { pane("Book information") }
            await("Description rendered in Book information") { has(description.substringBefore("\n")) }; assertFalse(has("Private copy")); assertFalse(has("Verify audio")); assertFalse(has("audio/raw")); assertFalse(has("1999"))
            reveal("Genre"); assertTrue(has("Genre")); reveal("Harbour Press"); assertTrue(has("Publisher")); reveal("2021")
            scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); capture(theme.name.lowercase())
            click("Audio files"); await("Audio files pane") { pane("Audio files") }
            reveal("Private copy"); reveal("audio/raw"); assertTrue(has("audio/raw")); assertFalse(has("Publisher"))
            capture("audio-${theme.name.lowercase()}")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); await("Back returns to book information") { pane("Book information") }
            click("Audio files"); await("Audio files before outside tap") { pane("Audio files") }; outside("Audio files"); await("Outside returns to book information") { pane("Book information") }
            outside("Book information"); await("Outside closes book information") { !pane("Book information") }
        }
        // Capture both themes before ActivityScenario's separate recreation trial.
        click("Book information"); click("Audio files")
        scenario.recreate(); await("Audio files restored") { pane("Audio files") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("Recreated Audio files returns to Book information") { pane("Book information") }
        click("Done")
    }
    @Test fun missingCatalogFieldsAndAmbiguousDateAreOmittedWhileRecoveryRemainsProminent() = fixture(false) { _,_ ->
        reveal("Repair audio"); assertTrue(has("Repair audio"))
        click("Book information"); await("Sparse information pane") { pane("Book information") }
        assertFalse(has("Genre")); assertFalse(has("Publisher")); assertFalse(has("Released")); assertFalse(has("1999")); assertFalse(has("2021-02-29"))
        await("Unknown duration rendered") { has(app.getString(R.string.unknown_duration)) }; capture("sparse")
        click("Audio files"); await("Missing audio shown in Audio files") { pane("Audio files") && has("Audio unavailable") }; click("Back"); click("Done")
        reveal("Repair audio"); assertTrue(has("Repair audio"))
    }
}
