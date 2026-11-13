package io.github.jnesew.paudiobooks.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
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
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Host-only, non-destructive emulator measurement. Reference-hardware budgets remain separate. */
@RunWith(AndroidJUnit4::class)
class LibraryScaleAcceptanceTest {
    // Compose virtual nodes on the API floor do not implement the provider's
    // findAccessibilityNodeInfosByText search. Traverse the public tree instead.
    private fun matchingNodes(root: AccessibilityNodeInfo?, label: String): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 500 || depth > 24) return
            val text = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
            if (text.contains(label)) found.add(node.copyNode())
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycleIfPooled() }
                if (visited >= 500) break
            }
        }
        if (root != null) visit(root, 0)
        return found
    }

    @Test fun thousandBookLibraryWithCoversProgressChaptersAndNotesBecomesInteractive() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val beforeBooks = app.library.books().observeBooks().first()
        val beforeProgress = app.library.library().observeAllProgress().first()
        val beforeSettings = app.settings.library.first()
        val retainedJobs = app.library.library().jobs().map { it.id }.toSet()
        val bitmap = Bitmap.createBitmap(128, 192, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(45, 93, 73))
        val art = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray() }
        bitmap.recycle()
        fun id() = UUID.randomUUID().toString()
        val asset = AudioAsset(id(), "media/unused", 0, "synthetic-scale", 120_000)
        val books = List(1000) { i -> Book(id(), "000 Scale %04d".format(i), importedAuthor = "Synthetic Author ${i % 100}", coverPath = "covers/${id()}") }
        val tracks = books.map { BookTrack(id(), it.id, asset.id, 0) }
        val chapters = tracks.flatMap { track -> List(12) { i -> Chapter(id(), track.id, i, i * 10_000L, (i + 1) * 10_000L, "Chapter ${i + 1}", "synthetic") } }
        val marks = tracks.flatMap { track -> List(5) { i -> Bookmark(id(), track.bookId, track.id, i * 20_000L, "Synthetic note $i — preserved locally", i.toLong()) } }
        var inserted = false
        try {
            // These are metadata/thumbnail scale fixtures. No playback readiness is claimed for
            // the deliberately absent shared audio asset, and no user media is copied.
            for (book in books) app.audioStore.writeCover(requireNotNull(book.coverPath).removePrefix("covers/"), art)
            app.library.withTransaction {
                app.library.library().insertAssets(listOf(asset))
                books.forEach { app.library.books().insert(it) }
                app.library.library().insertTracks(tracks)
                app.library.library().insertChapters(chapters)
                marks.forEach { app.library.library().insertBookmark(it) }
                tracks.forEachIndexed { i, track -> app.library.library().saveProgress(ReadingState(track.bookId, track.id, (i * 97L) % 120_000, 0.5f + (i % 51) * 0.05f, false, 1)) }
            }
            inserted = true
            app.settings.setLibrarySort("TITLE")
            app.settings.setLibraryFilter("ALL")
            app.settings.setLibraryGrid(false)
            val started = SystemClock.elapsedRealtime()
            ActivityScenario.launch<MainActivity>(android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                // Defer retained recovery through the real ViewModel; this scale trial
                // must not delete pre-existing partial work to reach the library.
                val deadline = started + 15_000
                var recoveryLoaded = false
                while (!recoveryLoaded && SystemClock.elapsedRealtime() < deadline) {
                    it.onActivity { activity ->
                        val model = androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
                        if (model.importing.value.interruptedJobs.toSet() == retainedJobs) {
                            model.deferInterrupted(); recoveryLoaded = true
                        }
                    }
                    if (!recoveryLoaded) Thread.sleep(25)
                }
                assertTrue("Retained recovery state must load without deleting work",recoveryLoaded)
                var clicked = false
                var interactiveMs = -1L
                while (SystemClock.elapsedRealtime() < deadline && !clicked) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    try {
                        val nodes = matchingNodes(root, "000 Scale 0000")
                        try {
                            for (node in nodes) {
                                if ((node.text?.toString() ?: node.contentDescription?.toString())?.contains("000 Scale 0000") != true) continue
                                var candidate: AccessibilityNodeInfo? = node.copyNode()
                                try {
                                    for (depth in 0 until 8) {
                                        val current = candidate ?: break
                                        if (current.isClickable && current.isVisibleToUser) {
                                            interactiveMs = SystemClock.elapsedRealtime() - started
                                            clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                            break
                                        }
                                        candidate = current.parent
                                        current.recycleIfPooled()
                                    }
                                } finally { candidate?.recycleIfPooled() }
                                if (clicked) break
                            }
                        } finally { nodes.forEach { node -> node.recycleIfPooled() } }
                    } finally { root?.recycleIfPooled() }
                    if (!clicked) Thread.sleep(50)
                }
                if (!clicked) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    try {
                        instrumentation.sendStatus(2, Bundle().apply {
                            putString("activeRootPackage", root?.packageName?.toString())
                            putString("activeRootText", root?.text?.toString())
                            val headings = matchingNodes(root, "Chapters")
                            val matches = matchingNodes(root, "000 Scale")
                            try {
                                putBoolean("detailsHeadingVisible", headings.any { it.isVisibleToUser })
                                putInt("scaleMatches", matches.size)
                            } finally {
                                headings.forEach { it.recycleIfPooled() }
                                matches.forEach { it.recycleIfPooled() }
                            }
                        })
                    } finally { root?.recycleIfPooled() }
                    instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                        try { java.io.File(app.cacheDir, "scale-failure.png").outputStream().use { output -> screenshot.compress(Bitmap.CompressFormat.PNG, 100, output) } }
                        finally { screenshot.recycle() }
                    }
                }
                assertTrue("Scale library must render an actionable first row", clicked)
                var detailsReached = false
                val detailsDeadline = SystemClock.elapsedRealtime() + 5000
                while (!detailsReached && SystemClock.elapsedRealtime() < detailsDeadline) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    try {
                        val nodes = matchingNodes(root, "Chapters")
                        detailsReached = nodes.any { node -> node.text?.toString() == "Chapters" && node.isVisibleToUser }
                        nodes.forEach { node -> node.recycleIfPooled() }
                    } finally { root?.recycleIfPooled() }
                    if (!detailsReached) Thread.sleep(25)
                }
                assertTrue("Row action must navigate to actual book details", detailsReached)
                instrumentation.sendStatus(2, Bundle().apply {
                    putInt("seededBooks", 1000); putInt("seededChapters", 12_000); putInt("seededBookmarks", 5000)
                    putLong("libraryInteractiveMs", interactiveMs)
                    putBoolean("rowOpenedDetails", true)
                })
            }
        } finally {
            app.settings.setLibrarySort(beforeSettings.sort)
            app.settings.setLibraryFilter(beforeSettings.filter)
            app.settings.setLibraryGrid(beforeSettings.grid)
            if (inserted) app.library.withTransaction {
                books.forEach { app.library.books().delete(it.id) }
                app.library.library().deleteAsset(asset.id)
            }
            books.forEach { app.audioStore.discard(requireNotNull(it.coverPath).removePrefix("covers/")) }
            assertEquals(beforeBooks.toSet(), app.library.books().observeBooks().first().toSet())
            assertEquals(beforeProgress.toSet(), app.library.library().observeAllProgress().first().toSet())
        }
    }
}
