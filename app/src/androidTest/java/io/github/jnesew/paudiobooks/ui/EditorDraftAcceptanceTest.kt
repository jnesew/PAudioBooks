package io.github.jnesew.paudiobooks.ui

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.InputDevice
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.library.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Real native actions and recreation; no fake editor or production fault hook. */
@RunWith(AndroidJUnit4::class)
class EditorDraftAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation by lazy { instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    } }
    @org.junit.Before fun connectAccessibilityBeforeWindows() { automation; instrumentation.waitForIdleSync() }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (++visited > 500 || depth > 24) return null
            // A loading screen initially disables bookmark/chapter nodes. Read
            // their current server state rather than a cached disabled copy.
            if (!node.refresh()) return null
            if (node.isVisibleToUser && predicate(node)) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        // API26 can retain a dismissed window as a non-null cached root.
        automation.rootInActiveWindow?.let { root ->
            try { visit(root, 0)?.let { return it } } finally { root.recycle() }
        }
        val windows = automation.windows
        try {
            windows.forEach { window ->
                if (window.isActive || window.isFocused) window.root?.let { root ->
                    try { visit(root, 0)?.let { return it } } finally { root.recycle() }
                }
            }
        } finally { windows.forEach { it.recycle() } }
        return null
    }

    private fun label(text: String): (AccessibilityNodeInfo) -> Boolean = {
        it.text?.toString() == text || it.contentDescription?.toString() == text
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 7000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(predicate)?.let { return it }
            Thread.sleep(50)
        }
        val labels = mutableListOf<String>()
        find { node ->
            if (labels.size < 100) (node.text ?: node.contentDescription)?.let { labels.add("$it [enabled=${node.isEnabled}, clickable=${node.isClickable}]") }
            false
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val identity = "package=${root?.packageName} class=${root?.className} children=${root?.childCount}"
        root?.recycle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(instrumentation.targetContext.cacheDir, "editor-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
        error("Required visible native node was not found; $identity current labels=$labels")
    }

    private fun click(text: String) {
        if (text != "Close player" && text != "Search books") {
            reveal(text, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        val deadline = SystemClock.elapsedRealtime() + 7000
        while (SystemClock.elapsedRealtime() < deadline) {
            var node = find { label(text)(it) && it.isEnabled }
            try {
                repeat(10) {
                    val current = node ?: return@repeat
                    current.refresh()
                    if (current.isClickable && current.isEnabled) {
                        if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    }
                    node = current.parent
                    current.recycle()
                }
            } finally { node?.recycle() }
            // A fading dialog heading can share the underlying action's label.
            // Wait for a real clickable ancestor instead of treating it as a button.
            if (text != "Close player" && text != "Search books") {
                val scroll = find { it.isScrollable }
                try { scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { scroll?.recycle() }
            }
            Thread.sleep(50)
        }
        awaitNode { label(text)(it) && it.isEnabled && it.isClickable }.recycle()
        error("No enabled native action for $text")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // Accessibility callbacks precede the final rendered animation frame.
        Thread.sleep(600)
        val image = requireNotNull(automation.takeScreenshot())
        try {
            File(instrumentation.targetContext.cacheDir, "presentation-$name.png").outputStream().use {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }

    private fun sliderSpeed(speed: Float) {
        // Material may put the name and adjustable range on separate native nodes.
        // The modal sheet exposes one adjustable range; the player behind it is hidden.
        val node = awaitNode { it.rangeInfo != null }
        try {
            assertTrue(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, speed)
            }))
        } finally { node.recycle() }
    }

    private fun assertDisabledAction(text: String) {
        // A committed speed write can precede Compose's native semantics update.
        // Wait for the actual disabled control, not just its already-visible label.
        awaitNode { candidate ->
            if (!label(text)(candidate)) return@awaitNode false
            var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(candidate)
            try {
                repeat(10) {
                    val current = node ?: return@awaitNode false
                    if (!current.refresh()) return@awaitNode false
                    if (!current.isEnabled) return@awaitNode true
                    node = current.parent
                    current.recycle()
                }
                false
            } finally { node?.recycle() }
        }.recycle()
    }

    private fun setText(text: String) {
        val node = awaitNode { it.isEditable }
        try {
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
        } finally { node.recycle() }
    }

    private fun assertDraft(text: String) {
        val node = awaitNode { it.isEditable && it.text?.toString() == text }
        node.recycle()
    }

    private fun outsideTap() {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        instrumentation.waitForIdleSync()
        Thread.sleep(600)
        if (instrumentation.targetContext.resources.configuration.fontScale > 1.5f && find { it.isEditable } == null) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            return
        }
        val handle = find(label("Drag handle"))
        if (handle != null) {
            val bounds = Rect()
            try { handle.getBoundsInScreen(bounds) } finally { handle.recycle() }
            if (bounds.top < 88 * metrics.density) {
                // A large-font sheet can occupy the whole app window, with no
                // exposed scrim to tap. Its equivalent Back exit remains available.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                return
            }
        }
        val now = SystemClock.uptimeMillis()
        var x = 8f
        var y = 64 * metrics.density
        // Keep the gesture inside the app window, away from system edge input.
        if (instrumentation.targetContext.resources.configuration.fontScale <= 1.5f) {
            val title = find { it.text?.toString() in setOf("Add bookmark", "Edit note", "Sleep timer", "Book information") }
            if (title != null) {
                val bounds = Rect()
                try { title.getBoundsInScreen(bounds) } finally { title.recycle() }
                val rootBounds = Rect()
                val root = automation.rootInActiveWindow
                try { root?.getBoundsInScreen(rootBounds) } finally { root?.recycle() }
                // RTL titles align right. Use the smaller title-to-window inset so
                // the tap stays outside the dialog surface in either direction.
                val inset = minOf(bounds.left - rootBounds.left, rootBounds.right - bounds.right)
                x = rootBounds.left + inset / 4f
                y = bounds.exactCenterY()
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("outsideTapGeometry", "root=$rootBounds title=$bounds x=$x y=$y density=${metrics.density}")
                })
            }
        }
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun bookmarkActions() {
        val node = awaitNode { it.contentDescription?.toString()?.let { label -> label.startsWith("Bookmark actions,") && label.endsWith("0:01") } == true }
        val label = node.contentDescription.toString()
        node.recycle()
        click(label)
    }

    private fun reveal(text: String, direction: Int) {
        repeat(16) {
            val visible = find(label(text))
            if (visible != null) { visible.recycle(); return }
            val scroll = find { it.isScrollable }
            try { scroll?.performAction(direction) } finally { scroll?.recycle() }
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
        }
    }

    @Test fun notesAndTimerDraftsSurviveRecreationIncludingBlockedNoteSave() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        if (InstrumentationRegistry.getArguments().getString("requireRtl") == "true") {
            assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, app.resources.configuration.layoutDirection)
        }
        fun id() = UUID.randomUUID().toString()
        val book = id(); val asset = id(); val track = id(); val mark = id()
        val settingsBefore = app.settings.library.first()
        val defaultSpeedBefore = app.settings.playback.first().defaultSpeed
        suspend fun cover(width: Int, height: Int, title: String): String {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(35, 95, 75))
            val paint = Paint().apply { color = Color.WHITE; textSize = 10f }
            canvas.drawText("L", 1f, height / 2f, paint)
            canvas.drawText("R", width - 9f, height / 2f, paint)
            canvas.drawText(title, 12f, height / 2f + 18f, paint)
            canvas.drawRect(0f, 3f, width.toFloat(), 6f, paint)
            canvas.drawRect(0f, height - 6f, width.toFloat(), height - 3f, paint)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            return app.audioStore.writeCover(id(), bytes)
        }
        val squareCover = cover(128, 128, "SQUARE")
        val portraitCover = cover(96, 144, "PORTRAIT")
        val retainedJobs = app.library.library().jobs().map { it.id }.toSet()
        val name = "000 Native editor ${book.take(8)}"
        val file = File(app.noBackupFilesDir, "audiobooks/media/$asset")
        file.parentFile!!.mkdirs()
        val dataSize = 16000 * 120
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(dataSize + 36).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(dataSize)
        file.outputStream().use { out -> out.write(header.array()); repeat(120) { out.write(ByteArray(16000)) } }
        try {
            app.library.books().insert(Book(book, name, coverPath = squareCover))
            app.library.library().insertAssets(listOf(AudioAsset(asset, "media/$asset", file.length(), "synthetic-editor", 120_000)))
            app.library.library().insertTracks(listOf(BookTrack(track, book, asset, 0)))
            app.library.library().insertChapters(listOf(
                Chapter(id(), track, 0, 0, 60_000, "First", "embedded"),
                Chapter(id(), track, 1, 60_000, 120_000, "Second", "embedded")
            ))
            app.library.library().insertBookmark(Bookmark(mark, book, track, 1000, "Original retained note", 123))
            app.settings.setLibrarySort("TITLE"); app.settings.setLibraryFilter("ALL"); app.settings.setLibraryGrid(false)
            app.settings.setDefaultSpeed(1f)
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                if (retainedJobs.isNotEmpty()) click("Later")
                // Search is stable while the saved sort/filter flow is still arriving,
                // and works with a retained library larger than a few screenfuls.
                reveal("Search books", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                click("Search books")
                setText(book.take(8))
                reveal(name, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (app.resources.configuration.fontScale <= 1.5f) {
                    capture("square-list")
                    click("Grid view")
                    capture("square-grid")
                    assertEquals(1, app.library.books().setCoverOverride(book, portraitCover))
                    capture("portrait-grid")
                    click("List view")
                    capture("portrait-list")
                    assertEquals(1, app.library.books().setCoverOverride(book, null))
                }
                click(name)
                scenario.recreate()
                // Closing an editor can retain the list's scrolled position.
                // Return to its real header before activating the selected book.
                repeat(2) {
                    val list = find { it.isScrollable }
                    try { list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { list?.recycle() }
                    Thread.sleep(100)
                }
                val resume = find(label("Resume"))
                if (resume != null) { resume.recycle(); click("Resume") } else {
                    reveal("Play", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    click("Play")
                }
                reveal("Pause", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                click("Pause")
                // Player content is scrollable; accessibility actions reach the control
                // without relying on a clipped parent's physical center coordinate.
                val scroll = find { it.isScrollable }
                try { scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { scroll?.recycle() }
                reveal("Add bookmark", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                click("Add bookmark")
                setText("New bookmark draft")
                capture("new-note")
                var anchor = -1L
                scenario.onActivity { activity ->
                    anchor = ViewModelProvider(activity)[LibraryViewModel::class.java].bookmarkDrafts.newBookmarks.value.getValue(book).positionMs
                }
                outsideTap()
                click("Forward 30 seconds")
                click("Add bookmark")
                assertDraft("New bookmark draft")
                assertEquals(1, app.library.library().bookmarks(book).size)
                scenario.recreate()
                assertDraft("New bookmark draft")
                SQLiteDatabase.openDatabase(app.getDatabasePath("library.db").path, null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { blocker ->
                    blocker.beginTransactionNonExclusive()
                    try {
                        click("Save")
                        Thread.sleep(150)
                        assertEquals(1, app.library.library().bookmarks(book).size)
                        scenario.recreate()
                        instrumentation.runOnMainSync { }
                    } finally { blocker.endTransaction() }
                }
                assertDraft("New bookmark draft")
                click("Save")
                val newSaveDeadline = SystemClock.elapsedRealtime() + 5000
                while (app.library.library().bookmarks(book).count { it.note == "New bookmark draft" } != 1) {
                    check(SystemClock.elapsedRealtime() < newSaveDeadline)
                    Thread.sleep(25)
                }
                assertEquals(2, app.library.library().bookmarks(book).size)
                assertEquals(anchor, app.library.library().bookmarks(book).single { it.note == "New bookmark draft" }.offsetMs)
                click("Add bookmark")
                setText("Discard deliberately")
                click("Cancel")
                click("Add bookmark")
                assertDraft("")
                click("Cancel")
                click("Speed")
                click("Increase speed by 0.05×")
                awaitNode { it.text?.toString() == "1.05×" }.recycle()
                suspend fun awaitSpeed(value: Float) {
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    while (kotlin.math.abs((app.library.library().progress(book)?.speed ?: 0f) - value) > 0.001f) {
                        check(SystemClock.elapsedRealtime() < deadline)
                        Thread.sleep(25)
                    }
                }
                sliderSpeed(3f)
                awaitSpeed(3f)
                assertDisabledAction("Increase speed by 0.05×")
                sliderSpeed(0.5f)
                awaitSpeed(0.5f)
                assertDisabledAction("Decrease speed by 0.05×")
                sliderSpeed(1.05f)
                awaitSpeed(1.05f)
                capture("speed")
                click("Done")
                click("Chapters")
                capture("chapters")
                click("Jump to chapter 2, Second")
                val seekDeadline = SystemClock.elapsedRealtime() + 5000
                while (app.library.library().progress(book)?.offsetMs != 60_000L) {
                    check(SystemClock.elapsedRealtime() < seekDeadline)
                    Thread.sleep(25)
                }
                awaitNode { label("Play")(it) && it.isEnabled }.recycle()
                click("Speed")
                awaitNode { it.text?.toString() == "1.05×" }.recycle()
                outsideTap()
                reveal("Sleep timer", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                click("Sleep timer")
                reveal("Minutes", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                awaitNode { it.isEditable }.recycle()
                outsideTap()
                click("Sleep timer")
                setText("17")
                scenario.recreate()
                assertDraft("17")
                click("Cancel")
                reveal("Close player", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                click("Close player")
                capture("details")
                reveal("Bookmarks", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                click("Bookmarks")
                capture("bookmarks")
                val progressBeforeEdit = app.library.library().progress(book)
                bookmarkActions()
                reveal("Edit note", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                click("Edit note")
                setText("Draft survives Room re-emission")
                outsideTap()
                bookmarkActions()
                click("Edit note")
                assertDraft("Draft survives Room re-emission")
                scenario.recreate()
                assertDraft("Draft survives Room re-emission")
                assertEquals("Original retained note", app.library.library().bookmarks(book).single { it.id == mark }.note)
                // A real second SQLite writer delays Room. Rotate while the Save coroutine
                // is waiting; cancellation may abort or commit, but must not lose the draft.
                SQLiteDatabase.openDatabase(app.getDatabasePath("library.db").path, null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { blocker ->
                    blocker.beginTransactionNonExclusive()
                    try {
                        click("Save")
                        Thread.sleep(150)
                        assertEquals("Original retained note", app.library.library().bookmarks(book).single { it.id == mark }.note)
                        scenario.recreate()
                        // Room may withhold details while its connection waits for this
                        // real writer. The main thread must remain runnable; inspect
                        // the restored draft after storage is available again.
                        instrumentation.runOnMainSync { }
                    } finally { blocker.endTransaction() }
                }
                assertDraft("Draft survives Room re-emission")
                click("Save")
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (app.library.library().bookmarks(book).single { it.id == mark }.note != "Draft survives Room re-emission") {
                    check(SystemClock.elapsedRealtime() < deadline)
                    Thread.sleep(25)
                }
                val saved = app.library.library().bookmarks(book).single { it.id == mark }
                assertEquals(mark, saved.id); assertEquals(1000L, saved.offsetMs); assertEquals(123L, saved.createdAt)
                assertEquals(progressBeforeEdit, app.library.library().progress(book))
                instrumentation.sendStatus(2, Bundle().apply { putBoolean("nativeDraftsRestored", true); putBoolean("realSqliteWriterDelay", true); putBoolean("blockedNewBookmarkSaveRecreated", true) })
            }
        } finally {
            // The dedicated trial can have loaded this fixture. Drain before deleting it.
            val controller = io.github.jnesew.paudiobooks.playback.PlaybackService::class.java
            var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
            instrumentation.runOnMainSync {
                future = androidx.media3.session.MediaController.Builder(app, androidx.media3.session.SessionToken(app, android.content.ComponentName(app, controller))).buildAsync()
            }
            val connected = requireNotNull(future).get(10, java.util.concurrent.TimeUnit.SECONDS)
            var unload: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>? = null
            instrumentation.runOnMainSync { unload = connected.sendCustomCommand(androidx.media3.session.SessionCommand(io.github.jnesew.paudiobooks.playback.PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            try { assertEquals(0, requireNotNull(unload).get(10, java.util.concurrent.TimeUnit.SECONDS).resultCode) }
            finally { instrumentation.runOnMainSync { connected.release() } }
            app.library.books().delete(book); app.library.library().deleteAsset(asset); file.delete()
            app.audioStore.resolveCover(squareCover)?.delete()
            app.audioStore.resolveCover(portraitCover)?.delete()
            assertEquals(retainedJobs,app.library.library().jobs().map { it.id }.toSet())
            app.settings.setLibrarySort(settingsBefore.sort); app.settings.setLibraryFilter(settingsBefore.filter); app.settings.setLibraryGrid(settingsBefore.grid)
            app.settings.setDefaultSpeed(defaultSpeedBefore)
        }
    }
}
