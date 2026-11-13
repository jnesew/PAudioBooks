package io.github.jnesew.paudiobooks.ui

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.importing.BatchDocumentsProvider
import io.github.jnesew.paudiobooks.importing.DocumentInventory
import io.github.jnesew.paudiobooks.importing.DuplicateAction
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.Bookmark
import io.github.jnesew.paudiobooks.library.ReadingState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real VM/importer/isolated inspection/Room/files, plus native review actions. */
@RunWith(AndroidJUnit4::class)
class BatchImportAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation by lazy { instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    } }
    private val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val fixture = Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture")
    private val tree = DocumentsContract.buildTreeDocumentUri(BatchDocumentsProvider.AUTHORITY, "batch-root")
    private fun fixture(action: String, value: String? = null) { app.contentResolver.call(fixture, action, value, null) }
    private fun <T> main(action: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = action() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                val image=automation.takeScreenshot()
                if (image!=null) try { java.io.File(app.cacheDir,"batch-native-failure.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
                error(message)
            }
            Thread.sleep(25)
        }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var count = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (++count > 700 || depth > 30 || !node.refresh()) return null
            if (node.isVisibleToUser && predicate(node)) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        automation.rootInActiveWindow?.let { root ->
            try { visit(root,0)?.let { return it } } finally { root.recycle() }
        }
        val windows=automation.windows
        try { for (window in windows) if (window.isActive || window.isFocused) {
            val root=window.root ?: continue
            try { visit(root,0)?.let { return it } } finally { root.recycle() }
        } } finally { windows.forEach { it.recycle() } }
        return null
    }
    private fun click(text: String) {
        instrumentation.waitForIdleSync()
        var nextScroll=0L
        await("Visible native action accepted: $text") {
            var node=find { it.isEnabled && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
            if (node==null && SystemClock.elapsedRealtime()>=nextScroll) {
                find { it.isScrollable }?.let { scroll ->
                    try { scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { scroll.recycle() }
                }
                nextScroll=SystemClock.elapsedRealtime()+250
            }
            try {
                var depth=0
                while (node!=null && depth++<12) {
                    val current=requireNotNull(node)
                    if (current.isClickable) {
                        // A recreated dialog may invalidate an accessibility handle between
                        // discovery and dispatch. Retry a fresh handle only when dispatch
                        // explicitly failed; never repeat an accepted toggle/action.
                        return@await current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    node=current.parent; current.recycle()
                }
                false
            } finally { node?.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun title(value: String) {
        var field: AccessibilityNodeInfo? = null
        await("Native title field") { field = find { it.isEditable }; field != null }
        try { assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) } finally { field?.recycle() }
        instrumentation.waitForIdleSync()
        await("Native title applied before recreation") {
            find { it.isEditable && it.text?.toString()==value }?.let { it.recycle(); true } ?: false
        }
    }

    private fun checkboxChecked(text: String): Boolean? {
        var node = find { it.text?.toString()==text || it.contentDescription?.toString()==text }
        try {
            repeat(12) {
                val current = node ?: return null
                if (!current.refresh()) return null
                if (current.isCheckable) return current.isChecked
                node = current.parent; current.recycle()
            }
            return null
        } finally { node?.recycle() }
    }

    private data class Retained(val book: Book, val progress: ReadingState?, val marks: List<Bookmark>)

    private fun trial(action: (ActivityScenario<MainActivity>, LibraryViewModel, MutableSet<String>) -> Unit) {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")
        // Connect before attaching dialog windows. API26 can crash in ViewRootImpl
        // if accessibility is enabled while a just-detached window is still queued.
        automation
        instrumentation.waitForIdleSync()
        val retained = runBlocking { app.library.books().all().map {
            Retained(it, app.library.library().progress(it.id), app.library.library().bookmarks(it.id))
        } }
        val jobs = runBlocking { app.library.library().jobs().map { it.id }.toSet() }
        val owned = mutableSetOf<String>()
        fixture("batch-grant"); fixture("batch-fail-b", "false")
        try { ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
            var model: LibraryViewModel? = null
            scenario.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java] }
            try {
                await("Initial retained recovery state") { main { model!!.importing.value.interruptedJobs.toSet() == jobs } }
                if (jobs.isNotEmpty()) click("Later")
                action(scenario, requireNotNull(model), owned)
            } finally {
                main { model!!.cancelImport() }
                await("Cancellation cleanup") { !main { model!!.importing.value.busy } }
                fixture("batch-fail-b", "false")
                runBlocking {
                    for (id in owned) if (app.repository.bookExists(id)) assertTrue(app.importer.removeBook(id))
                    assertEquals(jobs, app.library.library().jobs().map { it.id }.toSet())
                    for (saved in retained) {
                        assertEquals(saved.book, app.library.books().get(saved.book.id))
                        assertEquals(saved.progress, app.library.library().progress(saved.book.id))
                        assertEquals(saved.marks, app.library.library().bookmarks(saved.book.id))
                    }
                }
            }
        } } finally {
            fixture("batch-fail-b", "false")
            fixture("batch-revoke")
        }
    }

    private fun select(model: LibraryViewModel, uri: Uri = tree): SourceSelection {
        main { model.select(emptyList(), uri) }
        await("Inventory ready") { !main { model.importing.value.busy } }
        return requireNotNull(main { model.importing.value.selection })
    }
    private fun reviewed(model: LibraryViewModel, owned: MutableSet<String>): List<LinkedBookReview> {
        await("Bulk metadata review") { main { !model.importing.value.busy && model.importing.value.review != null } }
        return requireNotNull(main { model.importing.value.review }).also { entries ->
            owned += entries.mapNotNull { it.prepared?.bookId }
            instrumentation.sendStatus(2,Bundle().apply { putString("review_errors",entries.mapNotNull { it.errorMessage?.let(app::getString) }.joinToString(" | ")) })
        }
    }
    private fun results(model: LibraryViewModel): ImportBatch {
        await("Batch results") { main { !model.importing.value.busy && model.importing.value.batch?.finished == true } }
        return requireNotNull(main { model.importing.value.batch })
    }
    private fun assertAdded(id: String, expectedTracks: Int) = runBlocking {
        assertNotNull(app.library.books().get(id))
        assertNull("Adding must not start playback or create progress", app.library.library().progress(id))
        val tracks = app.library.library().tracks(id)
        assertEquals(expectedTracks, tracks.size)
        assertEquals((0 until expectedTracks).toList(), tracks.map { it.position })
        for (track in tracks) {
            val asset = requireNotNull(app.library.library().asset(track.assetId))
            assertEquals("",asset.relativePath)
            assertFalse(app.audioStore.stagedFile(asset.id).exists())
            assertNotNull(app.library.sources().source(asset.id))
        }
    }

    @Test fun nativePendingMetadataConfirmationSurvivesRecreationAndReloadsOnlyItsBookPaused() = trial { scenario,model,owned -> runBlocking {
        val rootId="linked-test-metadata-${UUID.randomUUID()}-root"
        fixture("linked-grant",rootId)
        val root=DocumentsContract.buildTreeDocumentUri(BatchDocumentsProvider.AUTHORITY,rootId)
        select(model,root)
        main { model.reviewSelection() }
        val prepared = reviewed(model,owned).single().prepared!!
        val bookId = prepared.bookId
        assertTrue(prepared.tracks.any { it.pending })
        val uniqueTitle="Native metadata ${UUID.randomUUID()}"
        main { model.updateReviewTitle(bookId,uniqueTitle) }
        click("Add 1 book")
        results(model)
        click("Done")
        click(uniqueTitle)
        val track=app.library.library().tracks(bookId).first()
        app.library.library().saveProgress(ReadingState(bookId,track.id,700,1.25f,false,123))
        app.library.library().insertBookmark(Bookmark(UUID.randomUUID().toString(),bookId,track.id,400,"Keep native metadata note",456))
        val future=main { androidx.media3.session.MediaController.Builder(app,androidx.media3.session.SessionToken(app,
            android.content.ComponentName(app,io.github.jnesew.paudiobooks.playback.PlaybackService::class.java))).buildAsync() }
        val controller=future.get(15,java.util.concurrent.TimeUnit.SECONDS)
        fun command(action:String,args:Bundle=Bundle.EMPTY)=main { controller.sendCustomCommand(androidx.media3.session.SessionCommand(action,Bundle.EMPTY),args) }.get(15,java.util.concurrent.TimeUnit.SECONDS)
        try {
            assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS,command(io.github.jnesew.paudiobooks.playback.PlaybackService.LOAD_BOOK,Bundle().apply { putString(io.github.jnesew.paudiobooks.playback.PlaybackService.BOOK_ID,bookId) }).resultCode)
            await("Deferred file ready before metadata action") { main { controller.playbackState==androidx.media3.common.Player.STATE_READY } }
            click("Book information"); click("Audio files"); click(app.getString(R.string.verify_audio))
            await("Identity confirmation remains available in Audio files") {
                find { it.text?.toString()==app.getString(R.string.verify_audio_explanation) }?.let { it.recycle(); true } ?: false
            }
            click("Cancel"); click("Read metadata")
            fun promptVisible()=find { it.text?.toString()==app.getString(R.string.read_metadata_explanation) }?.let { it.recycle(); true } ?: false
            await("Confirmation visible before saving recreation state",::promptVisible)
            scenario.recreate()
            await("Metadata confirmation restored after recreation",::promptVisible)
            click("Continue")
            await("Explicit native metadata completion") { main { !model.sourceWork.value.busy && model.sourceWork.value.complete } }
            assertTrue(main { model.sourceWork.value.metadata }); assertEquals(1,main { model.sourceWork.value.pendingFiles })
            await("Pending count rendered in completion dialog") {
                find { it.text?.toString()==app.resources.getQuantityString(R.plurals.metadata_still_pending,1,1) }?.let { it.recycle(); true } ?: false
            }
            await("Updated metadata playlist ready paused") { main { controller.playbackState==androidx.media3.common.Player.STATE_READY && !controller.playWhenReady } }
            assertEquals(track.id,main { controller.currentMediaItem?.mediaId }); assertTrue(kotlin.math.abs(main { controller.currentPosition }-700)<=200)
            assertEquals(1.25f,main { controller.playbackParameters.speed })
            assertEquals("Keep native metadata note",app.library.library().bookmarks(bookId).single().note)
            click("OK")
            // Reading another book's pending metadata must leave the loaded book playing.
            val otherRootId="linked-test-playback-${UUID.randomUUID()}-root"; fixture("linked-grant",otherRootId)
            val otherRoot=DocumentsContract.buildTreeDocumentUri(BatchDocumentsProvider.AUTHORITY,otherRootId)
            val file=io.github.jnesew.paudiobooks.importing.DocumentInventory(app.contentResolver).describe(DocumentsContract.buildDocumentUriUsingTree(otherRoot,"linked-long-a"))
            val otherPreview=app.linker.prepare(listOf(file),listOf(otherRoot))
            val otherId=UUID.randomUUID().toString(); owned+=otherId; app.linker.commit(otherPreview,otherPreview.tracks,"Other metadata playback",otherId)
            assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS,command(io.github.jnesew.paudiobooks.playback.PlaybackService.LOAD_BOOK,Bundle().apply { putString(io.github.jnesew.paudiobooks.playback.PlaybackService.BOOK_ID,otherId) }).resultCode)
            main { controller.play() }
            await("Other book playing") { main { controller.isPlaying && controller.currentPosition>100 } }
            val before=main { controller.currentPosition }
            main { model.readLinkedMetadata(bookId,controller) }
            await("Other-book metadata pass finished") { main { !model.sourceWork.value.busy && model.sourceWork.value.complete } }
            assertTrue(main { controller.isPlaying }); assertEquals(otherId,main { controller.currentMediaItem?.mediaMetadata?.extras?.getString(io.github.jnesew.paudiobooks.playback.PlaybackService.BOOK_ID) })
            assertTrue(main { controller.currentPosition }>before)
            assertEquals("Keep native metadata note",app.library.library().bookmarks(bookId).single().note)
            main { model.dismissSourceComplete() }
        } finally { runCatching { command(io.github.jnesew.paudiobooks.playback.PlaybackService.UNLOAD_AND_SAVE) }; main { controller.release() } }
    } }

    @Test fun nativeRescanAppendSurvivesRecreationAndPostCommitCancellationWithoutDuplicatingTracks() = trial { scenario,model,owned -> runBlocking {
        val rootId="linked-test-rescan-${UUID.randomUUID()}-root"
        fixture("linked-grant",rootId)
        val root=DocumentsContract.buildTreeDocumentUri(BatchDocumentsProvider.AUTHORITY,rootId)
        val folder=DocumentsContract.buildDocumentUriUsingTree(root,"scan-book:$rootId")
        val selected=io.github.jnesew.paudiobooks.importing.DocumentInventory(app.contentResolver).describe(DocumentsContract.buildDocumentUriUsingTree(root,"scan-a:$rootId"))
        val preview=app.linker.prepare(listOf(selected),listOf(root),folder,root)
        val bookId=UUID.randomUUID().toString(); owned+=bookId
        val fixtureTitle="Keep native rescan title ${bookId.take(8)}"
        instrumentation.sendStatus(2,Bundle().apply { putInt("retained_fixed_rescan_titles",app.library.books().all().count { it.title=="Keep native rescan title" }) })
        app.linker.commit(preview,preview.tracks,fixtureTitle,bookId)
        val track=app.library.library().tracks(bookId).single()
        app.library.library().saveProgress(ReadingState(bookId,track.id,700,1.25f,false,123))
        app.library.library().insertBookmark(Bookmark(UUID.randomUUID().toString(),bookId,track.id,400,"Keep native rescan note",456))
        click(fixtureTitle)
        val future=main { androidx.media3.session.MediaController.Builder(app,androidx.media3.session.SessionToken(app,
            android.content.ComponentName(app,io.github.jnesew.paudiobooks.playback.PlaybackService::class.java))).buildAsync() }
        val controller=future.get(15,java.util.concurrent.TimeUnit.SECONDS)
        fun command(action:String,args:Bundle=Bundle.EMPTY)=main { controller.sendCustomCommand(androidx.media3.session.SessionCommand(action,Bundle.EMPTY),args) }.get(15,java.util.concurrent.TimeUnit.SECONDS)
        try {
            assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS,command(io.github.jnesew.paudiobooks.playback.PlaybackService.LOAD_BOOK,Bundle().apply { putString(io.github.jnesew.paudiobooks.playback.PlaybackService.BOOK_ID,bookId) }).resultCode)
            await("Old linked playlist ready") { main { controller.playbackState==androidx.media3.common.Player.STATE_READY } }
            assertEquals(1,main { controller.mediaItemCount })
            click("Book information"); click("Audio files"); click("Rescan folder")
            await("Native rescan result") { main { !model.importing.value.busy && model.importing.value.rescan!=null } }
            assertEquals(listOf("02.wav"),main { model.importing.value.rescan!!.newFiles.map { it.name } })
            click("Select none")
            await("Native rescan deselection applied") { main { model.importing.value.selectedNewUris.isEmpty() } }
            scenario.recreate()
            assertTrue(main { model.importing.value.selectedNewUris.isEmpty() })
            click("Select all")
            await("Native rescan selection applied") { main { model.importing.value.selectedNewUris.size==1 } }
            click("Review new files")
            await("Reviewed append") { main { !model.importing.value.busy && model.importing.value.linkedPrepared!=null } }
            val prepared=main { model.importing.value.linkedPrepared!! }
            assertEquals(bookId,prepared.appendToBookId)
            // Hold the real importer's mutex only after a genuine temporary cover copy.
            // Append can commit, but its noncancellable journal cleanup then waits here.
            val entered=kotlinx.coroutines.CompletableDeferred<Unit>(); val release=kotlinx.coroutines.CompletableDeferred<Unit>()
            val cover=async(kotlinx.coroutines.Dispatchers.IO) {
                app.importer.stageAndInspectCover(DocumentsContract.buildDocumentUriUsingTree(root,"cover-b")) {
                    entered.complete(Unit); release.await(); byteArrayOf(0)
                }
            }
            try {
                kotlinx.coroutines.withTimeout(5000) { entered.await() }
                click("Add files")
                await("Actual append transaction committed before cancellation") { runBlocking { app.library.library().tracks(bookId).size==2 } }
                assertTrue(main { model.importing.value.busy })
                main { model.cancelImport() }
            } finally { release.complete(Unit); cover.await() }
            val result=results(model)
            assertEquals(listOf(BookImportStatus.ADDED),result.results.map { it.status })
            assertEquals(bookId,result.results.single().bookId)
            assertTrue(result.unfinished.isEmpty())
            assertEquals(track,app.library.library().tracks(bookId).first())
            assertEquals(2,app.library.library().tracks(bookId).size)
            assertEquals(fixtureTitle,app.library.books().get(bookId)!!.title)
            assertEquals("Keep native rescan note",app.library.library().bookmarks(bookId).single().note)
            await("Updated single-service playlist reloaded paused") { main { controller.mediaItemCount==2 && controller.playbackState==androidx.media3.common.Player.STATE_READY && !controller.playWhenReady } }
            assertEquals(track.id,app.library.library().progress(bookId)!!.trackId)
            assertTrue(kotlin.math.abs(app.library.library().progress(bookId)!!.offsetMs-700)<=200)
            assertEquals(1.25f,app.library.library().progress(bookId)!!.speed)
        } finally { runCatching { command(io.github.jnesew.paudiobooks.playback.PlaybackService.UNLOAD_AND_SAVE) }; main { controller.release() } }
    } }

    @Test fun nativeBulkSelectionEditsAndOrderSurviveRecreationWithOneApproval() = trial { scenario,model,owned ->
        val selection=select(model)
        assertFalse(selection.combined)
        click("Select none"); assertTrue(main { model.importing.value.selection!!.books().isEmpty() })
        click("Select all"); click("Review 2 books")
        val review=reviewed(model,owned)
        assertEquals(2,review.size)
        assertTrue(review.all { it.prepared != null })
        val originalTheme=runBlocking { app.settings.theme.first() }
        try {
            for (theme in listOf(io.github.jnesew.paudiobooks.preferences.ThemeChoice.LIGHT,io.github.jnesew.paudiobooks.preferences.ThemeChoice.DARK)) {
                runBlocking { app.settings.setTheme(theme) }
                instrumentation.waitForIdleSync()
                Thread.sleep(250)
                val image=automation.takeScreenshot()
                assertNotNull(image)
                try { java.io.File(app.cacheDir,"bulk-review-${theme.name.lowercase()}.png").outputStream().use {
                    image!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                } } finally { image?.recycle() }
            }
        } finally { runBlocking { app.settings.setTheme(originalTheme) } }
        val first=review.first().prepared!!
        assertEquals(listOf("01.wav","02.wav"),first.tracks.map { it.selected.name })
        assertTrue(review.all { it.prepared!!.tracks.any { t -> t.metadata.artworkThumbnail != null } })
        click("Select none"); assertEquals(0,main { model.importing.value.review!!.count { it.selected } })
        click("Select all")
        click(app.getString(R.string.edit_import_book,first.title))
        val edited="Native bulk ${UUID.randomUUID()}"
        title(edited)
        main { model.reorderReviewFile(first.bookId,0,1) }
        scenario.recreate()
        assertEquals(edited,main { model.importing.value.review!!.first().prepared!!.title })
        click("Done")
        click("Add 2 books")
        val batch=results(model)
        assertEquals(listOf(BookImportStatus.ADDED,BookImportStatus.ADDED),batch.results.map { it.status })
        assertAdded(first.bookId,2); assertAdded(review.last().prepared!!.bookId,1)
        assertEquals(edited,runBlocking { app.library.books().get(first.bookId)!!.title })
        val names=runBlocking { app.library.library().tracks(first.bookId).map { app.library.sources().source(it.assetId)!!.documentUri.substringAfterLast('/') } }
        assertEquals(listOf("a-02","a-01"),names)
        assertNull(main { model.openedBook.value })
        click("Done"); assertNull(main { model.importing.value.batch })
    }

    @Test fun unavailableSecondSourceDoesNotBlockReviewAndRetriesOnlyFailedBook() = trial { _,model,owned ->
        fixture("batch-fail-b","true")
        select(model); main { model.reviewSelection() }
        val review=reviewed(model,owned)
        assertNotNull(review[0].prepared); assertNull(review[1].prepared)
        main { model.approveReview() }
        val batch=results(model)
        assertEquals(listOf(BookImportStatus.ADDED,BookImportStatus.FAILED),batch.results.map { it.status })
        val first=review[0].prepared!!.bookId
        assertAdded(first,2)
        fixture("batch-fail-b","false")
        main { model.retryUnfinishedBooks() }
        val retry=reviewed(model,owned)
        assertEquals(1,retry.size)
        main { model.approveReview() }
        assertEquals(2,results(model).results.count { it.status==BookImportStatus.ADDED })
        assertAdded(first,2); assertAdded(retry.single().prepared!!.bookId,1)
    }

    @Test fun cancellationBeforeApprovalCleansAllPreparedJobsAndCreatesNoBooks() = trial { _,model,owned ->
        val jobs=runBlocking { app.library.library().jobs().map { it.id }.toSet() }
        select(model); main { model.reviewSelection() }
        val review=reviewed(model,owned)
        assertEquals(2,review.size)
        assertTrue(review.all { it.prepared != null })
        main { model.cancelImport() }
        val stopped=results(model)
        assertEquals(listOf(BookImportStatus.PENDING,BookImportStatus.PENDING),stopped.results.map { it.status })
        assertEquals(jobs,runBlocking { app.library.library().jobs().map { it.id }.toSet() })
        assertTrue(review.none { runBlocking { app.repository.bookExists(it.prepared!!.bookId) } })
    }

    @Test fun realCommitAbortRetainsReviewedTitleAndOrderForRetryWithoutRepeatingSuccess() = trial { _,model,owned ->
        select(model); main { model.reviewSelection() }
        val review=reviewed(model,owned); val first=review[0].prepared!!
        main { model.updateReviewTitle(first.bookId,"Retained reviewed title"); model.reorderReviewFile(first.bookId,0,1) }
        SQLiteDatabase.openDatabase(app.getDatabasePath("library.db").path,null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { fault ->
            val trigger="synthetic_bulk_"+UUID.randomUUID().toString().replace("-","")
            fault.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON books WHEN NEW.id='${first.bookId}' BEGIN SELECT RAISE(ABORT,'synthetic bulk fault'); END")
            try {
                main { model.approveReview() }
                assertEquals(listOf(BookImportStatus.FAILED,BookImportStatus.ADDED),results(model).results.map { it.status })
            } finally { fault.execSQL("DROP TRIGGER $trigger") }
        }
        main { model.retryUnfinishedBooks() }
        val retry=reviewed(model,owned).single().prepared!!
        assertEquals("Retained reviewed title",retry.title)
        assertEquals(listOf("02.wav","01.wav"),retry.tracks.map { it.selected.name })
        main { model.approveReview() }
        assertEquals(2,results(model).results.count { it.status==BookImportStatus.ADDED })
        assertAdded(retry.bookId,2); assertAdded(review[1].prepared!!.bookId,1)
    }

    @Test fun uncheckedBookIsSkippedAndRepeatDocumentsAreNotSelectedByDefault() = trial { _,model,owned ->
        select(model); main { model.reviewSelection() }
        val review=reviewed(model,owned)
        main { model.selectReviewBook(review[1].id,false); model.approveReview() }
        assertEquals(listOf(BookImportStatus.ADDED,BookImportStatus.SKIPPED),results(model).results.map { it.status })
        main { model.dismissImportResults() }
        select(model); main { model.reviewSelection() }
        val again=reviewed(model,owned)
        assertTrue(again[0].alreadyLinked); assertFalse(again[0].selected)
        assertFalse(again[1].alreadyLinked); assertTrue(again[1].selected)
        main { model.approveReview() }
        assertEquals(listOf(BookImportStatus.SKIPPED,BookImportStatus.ADDED),results(model).results.map { it.status })
    }

    @Test fun discDefaultsAndRootAudioAreAllSelectableWithoutDroppingFiles() = trial { _, model, _ ->
        val mixed = DocumentsContract.buildTreeDocumentUri(BatchDocumentsProvider.AUTHORITY, "mixed-root")
        val selection = select(model, mixed)
        assertEquals(3, selection.files.size)
        assertEquals(selection.files.map { it.uri }.toSet(), selection.books().flatMap { it.files }.map { it.uri }.toSet())
        assertEquals(3, selection.books().size) // root audio is not silently lost beside CD1/CD2.
        main { model.selectGroup(selection.groups.first { it.audioFiles.any { file -> file.name == "Root.wav" } }.id, false) }
        main { model.setCombinedBooks(true) }
        val combined = main { model.importing.value.selection!!.books().single() }
        assertEquals(2, combined.files.size)
        assertTrue(combined.files.none { it.name == "Root.wav" })
        val discs = selection.copy(groups = selection.groups.filter { it.isDiscSubfolder },
            combined = true, selectedGroupIds = selection.groups.filter { it.isDiscSubfolder }.map { it.id }.toSet())
        assertEquals(2, discs.books().single().files.size)
    }

    @Test fun laterKeepsRealStagedWorkAcrossRecreationAndRecoveryCanBeReopened() = trial { scenario, model, _ ->
        val selected = runBlocking { DocumentInventory(app.contentResolver).describe(DocumentsContract.buildDocumentUriUsingTree(tree,"a-01")) }
        val preview = runBlocking { app.importer.prepare(listOf(selected)) }
        val copy = preview.files.single().copy
        val staged = app.audioStore.stagedFile(copy.assetId)
        val extra = io.github.jnesew.paudiobooks.library.ImportJob(UUID.randomUUID().toString(),"STAGED",456)
        fun assertRetained() = runBlocking {
            assertTrue(app.library.library().jobs().any { it.id == preview.jobId })
            assertEquals(copy.sizeBytes,staged.length())
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(staged.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(copy.sha256,digest)
        }
        try {
            main { model.refreshInterrupted() }
            await("Staged work offered for recovery") { main { preview.jobId in model.importing.value.interruptedJobs } }
            click("Later"); assertRetained()
            scenario.recreate()
            click("Add books"); click("Unfinished imports"); click("Later")
            assertRetained()
            // A new job must prompt again even though the earlier set was deferred.
            runBlocking { app.library.library().insertJob(extra) }
            main { model.refreshInterrupted() }
            await("New interrupted job visible") { main { extra.id in model.importing.value.interruptedJobs } }
            click("Later"); assertRetained()
            assertTrue(runBlocking { app.library.library().jobs().any { it.id == extra.id } })
        } finally {
            runBlocking { app.importer.cancel(preview.jobId); app.importer.cancel(extra.id) }
        }
    }

    @Test fun postCommitJournalFailureDoesNotLoseBooksOrOtherPreparedGrants() = trial { _,model,owned ->
        select(model); main { model.reviewSelection() }
        val review=reviewed(model,owned); val first=review[0].prepared!!
        SQLiteDatabase.openDatabase(app.getDatabasePath("library.db").path,null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { fault ->
            val trigger="synthetic_bulk_journal_"+UUID.randomUUID().toString().replace("-","")
            fault.execSQL("CREATE TRIGGER $trigger BEFORE DELETE ON import_jobs WHEN OLD.id='${first.preview.jobId}' BEGIN SELECT RAISE(ABORT,'synthetic journal fault'); END")
            try {
                main { model.approveReview() }
                assertEquals(listOf(BookImportStatus.ADDED,BookImportStatus.ADDED),results(model).results.map { it.status })
                assertTrue(main { first.preview.jobId in model.importing.value.interruptedJobs })
                assertAdded(first.bookId,2); assertAdded(review[1].prepared!!.bookId,1)
            } finally { fault.execSQL("DROP TRIGGER $trigger"); runBlocking { app.importer.cancel(first.preview.jobId) } }
        }
    }
}
