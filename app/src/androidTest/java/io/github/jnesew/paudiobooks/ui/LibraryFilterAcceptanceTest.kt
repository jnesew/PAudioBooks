package io.github.jnesew.paudiobooks.ui

import android.content.Intent
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
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
class LibraryFilterAcceptanceTest {
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
        try { File(app.cacheDir,"library-filters-$name.png").outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } }
        finally { image.recycle() }
    }

    private fun <T> main(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun field(text: String) {
        var found: AccessibilityNodeInfo? = null
        var direction=AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        await("Filter search field ready after sheet/tab transition") {
            found=find { it.isEditable }
            if (found!=null) true else {
                if (!scroll(direction)) direction=AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                false
            }
        }
        val node=requireNotNull(found)
        try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)
        })) } finally { node.recycle() }
        instrumentation.waitForIdleSync()
    }
    private fun openFilters() {
        await("Library header before opening Filters") {
            if (has(app.getString(R.string.search_books))) true
            else { scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); false }
        }
        click(app.getString(R.string.library_filters))
        await("Filter tabs visible after opening") { has("Genres") && has("Authors") }
    }
    private fun tab(label: String) {
        instrumentation.waitForIdleSync()
        var found: AccessibilityNodeInfo?=null
        await("Visible native tab: $label") {
            found=find { it.className?.toString()=="android.widget.TextView" && it.text?.toString()==label }
            found!=null
        }
        val bounds=Rect()
        try { requireNotNull(found).getBoundsInScreen(bounds) } finally { found?.recycle() }
        // Exercise the same touch target for active and inactive tabs. Android omits
        // ACTION_CLICK for the selected tab; coordinates come from its native tree.
        val now=SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event,true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun choice(label: String) {
        fun checkable(source: AccessibilityNodeInfo): Boolean {
            var node: AccessibilityNodeInfo?=AccessibilityNodeInfo.obtain(source)
            try { repeat(10) {
                val current=node ?: return false
                if (current.isCheckable && current.isClickable && current.isEnabled) return true
                node=current.parent; current.recycle()
            } } finally { node?.recycle() }
            return false
        }
        await("Native checkbox row ready: $label") {
            var node=find { it.text?.toString()?.contains(label)==true && checkable(it) }
            if (node==null) { scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); return@await false }
            try {
                repeat(10) {
                    val current=node ?: return@repeat
                    if (current.isCheckable && current.isClickable) return@await current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node=current.parent; current.recycle()
                }
                false
            } finally { node?.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun fixture(extra: Int = 0, trial: (ActivityScenario<MainActivity>, List<String>, String) -> Unit) = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven")=="true")
        automation; instrumentation.waitForIdleSync()
        val settings=app.settings.library.first(); val theme=app.settings.theme.first()
        val retained=app.library.books().all().map { Triple(it, app.library.library().progress(it.id), app.library.library().bookmarks(it.id)) }
        val prefix="Facet-${UUID.randomUUID().toString().take(8)}"
        val owned=mutableListOf<String>(); val asset=UUID.randomUUID().toString()
        val media=File(app.noBackupFilesDir,"audiobooks/media/$asset"); media.parentFile!!.mkdirs()
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(1920036)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(1920000)
        java.io.RandomAccessFile(media,"rw").use { it.write(header.array()); it.setLength(1920044) }
        try {
            app.library.library().insertAssets(listOf(AudioAsset(asset,"media/$asset",media.length(),"synthetic-facets",120000,"audio/wav")))
            suspend fun add(index: Int, genre: String?, author: String) {
                val id=UUID.randomUUID().toString(); owned+=id
                app.library.books().insert(Book(id,"000 $prefix $index",importedGenre=genre,importedAuthor=author))
                app.library.library().insertTracks(listOf(BookTrack(UUID.randomUUID().toString(),id,asset,0)))
            }
            add(0,"$prefix Fantasy","$prefix Ada"); add(1,"$prefix Mystery","$prefix Ada")
            add(2,"$prefix History","$prefix Bea"); add(3,null,"$prefix Bea")
            repeat(extra) { add(it+4,"$prefix Genre %03d".format(it),"$prefix Bea") }
            app.settings.setLibraryFacets(emptySet(),emptySet()); app.settings.setLibrarySort("TITLE")
            app.settings.setLibraryFilter("ALL"); app.settings.setLibraryGrid(false)
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                scenario.onActivity { activity ->
                    if (InstrumentationRegistry.getArguments().getString("requireLargeLandscape")=="true") {
                        assertEquals(2f,activity.resources.configuration.fontScale,.01f)
                        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,activity.resources.configuration.orientation)
                    }
                }
                if (app.library.library().jobs().isNotEmpty()) click("Later")
                openFilters()
                trial(scenario,owned,prefix)
            }
            for ((book,progress,marks) in retained) {
                assertEquals(book,app.library.books().get(book.id)); assertEquals(progress,app.library.library().progress(book.id))
                assertEquals(marks,app.library.library().bookmarks(book.id))
            }
        } finally {
            for (id in owned) if (app.repository.bookExists(id)) app.importer.removeBook(id)
            media.delete()
            app.settings.setLibraryFacets(settings.genres,settings.authors)
            app.settings.setLibrarySort(settings.sort); app.settings.setLibraryFilter(settings.filter); app.settings.setLibraryGrid(settings.grid)
            app.settings.setTheme(theme)
        }
    }

    @Test fun liveSelectionsPersistThroughTabsCloseBackRecreationAndBothThemes() = fixture { scenario,_,prefix ->
        for (theme in listOf(ThemeChoice.LIGHT,ThemeChoice.DARK)) {
            runBlocking { app.settings.setTheme(theme) }
            field(prefix)
            choice("$prefix Fantasy")
            await("Genre persisted without Apply") { runBlocking { app.settings.library.first().genres==setOf(facetKey("$prefix Fantasy")) } }
            choice("$prefix Mystery")
            await("OR genre result count") { has("2 books") }
            assertFalse(has("Show 2 books"))
            tab("Authors")
            field(prefix)
            choice("$prefix Ada")
            await("Author persisted without dismissing") { runBlocking { app.settings.library.first().authors==setOf(facetKey("$prefix Ada")) } }
            capture("authors-${theme.name.lowercase()}")
            scenario.recreate()
            await("Authors tab/search restored") { has("Find an author") && has("$prefix Ada") }
            click("Close")
            await("Live filters retain two matching books") { has("000 $prefix 0") && has("000 $prefix 1") }
            assertFalse(has("000 $prefix 2"))
            openFilters(); tab("Genres")
            capture("genres-${theme.name.lowercase()}")
            fun keyboardVisible(): Boolean {
                val windows=automation.windows
                return try { windows.any { it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                finally { windows.forEach { it.recycle() } }
            }
            if (keyboardVisible()) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back hides the search keyboard first") { !keyboardVisible() }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back keeps current filters") { has("000 $prefix 0") }
            assertEquals(2,runBlocking { app.settings.library.first().genres.size })
            openFilters(); click("Clear all")
            await("Clear immediately resets both fields") { runBlocking { app.settings.library.first().let { it.genres.isEmpty() && it.authors.isEmpty() } } }
            tab("Genres")
        }
    }

    @Test fun longGenreListKeepsAuthorsReachableAndNewImportedLabelsAppearLive() = fixture(160) { scenario,owned,prefix ->
        field(prefix)
        repeat(5) { scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
        // No scrolling back through genres is needed to reach Authors.
        tab("Authors"); field("$prefix Ada")
        choice("$prefix Ada")
        tab("Genres")
        await("Genre search survives tab switch") { has(prefix) }
        field("$prefix Fresh")
        await("No matching choice before metadata arrives") { has(app.getString(R.string.no_matches)) }
        runBlocking { val old=app.library.books().get(owned[0])!!; app.library.books().updateMetadata(old.copy(importedGenre="$prefix Fresh")) }
        await("New metadata creates a genre choice without rebuilding a taxonomy") { has("$prefix Fresh") }
        choice("$prefix Fresh")
        await("Result count updates live") { has("1 book") }
        scenario.recreate()
        await("Long-list selection/search retained") { has("$prefix Fresh") && has("1 book") }
        click("Close")
        await("Newly tagged book visible") { has("000 $prefix 0") }
        assertFalse(has("000 $prefix 1"))
    }

    @Test fun filteringDoesNotPauseOrSwitchTheServicePlayer() = fixture { _,owned,prefix ->
        click("Close")
        val future=main { androidx.media3.session.MediaController.Builder(app,androidx.media3.session.SessionToken(app,
            android.content.ComponentName(app,io.github.jnesew.paudiobooks.playback.PlaybackService::class.java))).buildAsync() }
        val controller=future.get(15,java.util.concurrent.TimeUnit.SECONDS)
        fun command(action: String, args: android.os.Bundle = android.os.Bundle.EMPTY) = main {
            controller.sendCustomCommand(androidx.media3.session.SessionCommand(action,android.os.Bundle.EMPTY),args)
        }.get(15,java.util.concurrent.TimeUnit.SECONDS)
        try {
            assertEquals(0,command(io.github.jnesew.paudiobooks.playback.PlaybackService.LOAD_BOOK,android.os.Bundle().apply { putString(io.github.jnesew.paudiobooks.playback.PlaybackService.BOOK_ID,owned[0]) }).resultCode)
            await("Synthetic PCM ready") { main { controller.playbackState==androidx.media3.common.Player.STATE_READY } }
            val track=main { controller.currentMediaItem!!.mediaId }
            main { controller.play() }
            await("PCM playing") { main { controller.isPlaying } }
            val position=main { controller.currentPosition }
            openFilters(); field("$prefix History"); choice("$prefix History")
            await("Playing book excluded from filtered library") { has("1 book") }
            assertEquals(track,main { controller.currentMediaItem!!.mediaId })
            assertTrue(main { controller.isPlaying })
            click("Close")
            assertTrue(main { controller.currentPosition }>=position)
            assertEquals(track,main { controller.currentMediaItem!!.mediaId })
            main { controller.pause() }
        } finally {
            command(io.github.jnesew.paudiobooks.playback.PlaybackService.UNLOAD_AND_SAVE)
            main { controller.release() }
        }
    }
    @Test fun tabsSearchAndCloseRemainReachableInConstrainedWindows() = fixture { scenario,_,prefix ->
        field(prefix); choice("$prefix Fantasy")
        tab("Authors"); field(prefix); choice("$prefix Ada")
        await("Both live selections stored") { runBlocking { app.settings.library.first().let {
            it.genres==setOf(facetKey("$prefix Fantasy")) && it.authors==setOf(facetKey("$prefix Ada"))
        } } }
        for (theme in listOf(ThemeChoice.LIGHT,ThemeChoice.DARK)) {
            runBlocking { app.settings.setTheme(theme) }
            tab("Genres"); field(prefix)
            tab("Authors"); field(prefix)
            capture("adaptive-${theme.name.lowercase()}")
        }
        scenario.recreate()
        await("Tabs survive recreation in constrained window") { has("Genres") && has("Authors") }
        click("Close")
        assertEquals(1,runBlocking { app.settings.library.first().genres.size })
        assertEquals(1,runBlocking { app.settings.library.first().authors.size })
    }

    @Test fun commaSeparatedGenresUseSeparateChoicesWithoutSplittingAuthorNamesOrPeriods() = fixture { _,owned,prefix ->
        val tags=listOf("$prefix Action & Adventure","$prefix Epic","$prefix Dark Fantasy","$prefix Historical","$prefix Sci.Fi.")
        val author="$prefix Doe, Jane"
        runBlocking {
            val first=app.library.books().get(owned[0])!!
            app.library.books().updateMetadata(first.copy(importedGenre=tags.joinToString(", "),importedAuthor=author))
            val second=app.library.books().get(owned[1])!!
            app.library.books().updateMetadata(second.copy(importedGenre=tags[1]))
        }
        field(prefix)
        await("Separate genre choices after metadata update") { has(tags[0]) && has(tags[1]) }
        assertFalse(has(tags.joinToString(", ")))
        choice(tags[1]); choice(tags[2])
        await("Overlapping genres do not duplicate books") { has("2 books") }
        await("Both overlapping genre choices are stored") { runBlocking {
            app.settings.library.first().genres==setOf(facetKey(tags[1]),facetKey(tags[2]))
        } }
        field(tags.last()); choice(tags.last())
        tab("Authors"); field(author); choice(author)
        await("Comma in author is retained, narrowing the union to one book") { has("1 book") }
        assertEquals(setOf(facetKey(author)),runBlocking { app.settings.library.first().authors })
        click("Close")
        await("The matching book is shown once") { has("000 $prefix 0") }
        assertFalse(has("000 $prefix 1"))
    }

    @Test fun commaSeparatedFullAuthorNamesFilterSharedBooksWithoutDuplicates() = fixture { _,owned,prefix ->
        val jane="$prefix Jane Doe"; val john="$prefix John Smith"
        runBlocking {
            val first=app.library.books().get(owned[0])!!
            app.library.books().updateMetadata(first.copy(importedAuthor="$jane, $john"))
            val second=app.library.books().get(owned[1])!!
            app.library.books().updateMetadata(second.copy(importedAuthor=jane))
        }
        tab("Authors"); field(prefix)
        await("Separate author choices from a comma list") { has(jane) && has(john) }
        assertFalse(has("$jane, $john"))
        choice(jane)
        await("First author includes both books") { has("2 books") }
        choice(john)
        await("Shared book remains counted once") { has("2 books") }
        await("Both author choices are stored even when the book count stays unchanged") { runBlocking {
            app.settings.library.first().authors==setOf(facetKey(jane),facetKey(john))
        } }
        click("Close")
        await("Both distinct matching books are visible") { has("000 $prefix 0") && has("000 $prefix 1") }
        assertFalse(has("000 $prefix 2"))
    }

}
