package io.github.jnesew.paudiobooks.storage

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jnesew.paudiobooks.importing.*
import io.github.jnesew.paudiobooks.library.*
import io.github.jnesew.paudiobooks.metadata.MetadataInspector
import io.github.jnesew.paudiobooks.portability.ProgressPortability
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class LinkedStorageTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val resolver=context.contentResolver
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val rootId="linked-test-${UUID.randomUUID()}-root"
    private val root=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",rootId)
    private val sandbox=File(context.noBackupFilesDir,"synthetic-linked-test-${UUID.randomUUID()}")
    private val wrapped=object: ContextWrapper(context) { override fun getNoBackupFilesDir()=sandbox }
    private val store=PrivateAudioStore(wrapped)
    private val db=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build()
    private val grants=SourceGrantStore(db,resolver,scope)
    private val importer=ImportCoordinator(db,store,resolver) { grants.collectUnused() }
    private val access=BoundedDocumentAccess(resolver)
    private val linker=LinkedImportCoordinator(db,store,importer,grants,access,MetadataInspector(context),DocumentInventory(resolver))
    private fun uri(id:String)=DocumentsContract.buildDocumentUriUsingTree(root,id)
    private suspend fun selected(id:String)=DocumentInventory(resolver).describe(uri(id))
    private fun isPersisted()=resolver.persistedUriPermissions.any { it.uri==root && it.isReadPermission }
    private suspend fun prepare(id:String)=linker.prepare(listOf(selected(id)),listOf(root))
    private suspend fun add(id:String):String {
        val preview=prepare(id)
        val bookId=UUID.randomUUID().toString()
        return linker.commit(preview,preview.tracks,"Synthetic linked test",bookId)
    }
    @Before fun grant() = runBlocking<Unit> {
        assertTrue(BoundedDocumentAccess.awaitIdle())
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",rootId,null))
    }
    @After fun cleanup() = runBlocking<Unit> {
        db.books().all().forEach { importer.removeBook(it.id) }
        importer.reconcileInterrupted()
        assertTrue(BoundedDocumentAccess.awaitIdle())
        val limit=SystemClock.elapsedRealtime()+5000
        do { grants.collectUnused(); if (db.sources().grants().isEmpty()) break; delay(10) } while (SystemClock.elapsedRealtime()<limit)
        assertTrue("Owned source grant retained after all references and actual closes",db.sources().grants().isEmpty())
        assertFalse(isPersisted())
        scope.cancel(); db.close(); sandbox.deleteRecursively()
    }
    @Test fun sharedTreeSurvivesOtherBookCancellationRemovalAndActualDescriptorLease() = runBlocking<Unit> {
        val a=add("a-01"); val b=add("b-01")
        assertTrue(isPersisted()); assertEquals(1,db.sources().grants().size)
        val pending=prepare("a-02")
        importer.cancel(pending.jobId)
        assertTrue(isPersisted())
        val ta=db.library().tracks(a).single(); val tb=db.library().tracks(b).single()
        val aa=requireNotNull(db.library().asset(ta.assetId)); val ab=requireNotNull(db.library().asset(tb.assetId))
        assertFalse(aa.identityVerified); assertEquals("",aa.sha256); assertEquals("",aa.relativePath)
        assertFalse(ab.identityVerified)
        assertTrue("Link addition wrote permanent audio",File(sandbox,"audiobooks/media").listFiles().orEmpty().isEmpty())
        val source=requireNotNull(db.sources().source(aa.id))
        val handle=withContext(Dispatchers.IO) { access.open(Uri.parse(source.documentUri),grants.leaseForSource(source),source.sizeHint) }
        importer.removeBook(a); assertTrue(isPersisted())
        importer.removeBook(b); grants.collectUnused(); assertTrue("Live descriptor lost its tree grant",isPersisted())
        withContext(Dispatchers.IO) { assertEquals('R'.code.toByte(),handle.read(0,1).bytes.single()) }
        handle.close(); handle.close()
        assertTrue(BoundedDocumentAccess.awaitIdle())
        val limit=SystemClock.elapsedRealtime()+5000
        while (isPersisted() && SystemClock.elapsedRealtime()<limit) delay(10)
        assertFalse(isPersisted())
    }
    @Test fun explicitVerificationEnablesTransferAndKeepsEveryBookTrackNoteAndOverride() = runBlocking<Unit> {
        val b=add("a-01"); val track=db.library().tracks(b).single()
        val state=ReadingState(b,track.id,700,1.25f,false,123)
        val note=Bookmark(UUID.randomUUID().toString(),b,track.id,500,"Keep linked note",456)
        db.library().saveProgress(state); db.library().insertBookmark(note)
        db.books().setOverrides(b,"Keep linked title",null,null,null,"Keep translator","2.5")
        val book=db.books().get(b); val tracks=db.library().tracks(b); val chapters=db.library().chapters(track.id)
        val beforeBytes=BoundedDocumentAccess.sourceBytesRead.get()
        val output=ByteArrayOutputStream()
        try { ProgressPortability(db).export(output); fail("Unverified identity exported") } catch (_: IllegalArgumentException) { }
        assertEquals(0,output.size())
        linker.verifyBook(b)
        val asset=requireNotNull(db.library().asset(track.assetId))
        assertTrue(asset.identityVerified); assertEquals(64,asset.sha256.length)
        assertTrue(BoundedDocumentAccess.sourceBytesRead.get()-beforeBytes >= asset.sizeBytes)
        assertEquals(book,db.books().get(b)); assertEquals(tracks,db.library().tracks(b))
        assertEquals(chapters,db.library().chapters(track.id)); assertEquals(state,db.library().progress(b)); assertEquals(listOf(note),db.library().bookmarks(b))
        ProgressPortability(db).export(output)
        val preview=ProgressPortability(db).preview(output.toByteArray().inputStream())
        assertEquals(listOf(b),preview.entries.single().matchingBookIds)
        val text=output.toString("UTF-8")
        assertFalse(text.contains("content://")); assertFalse(text.contains(rootId)); assertTrue(text.contains("Keep linked note"))
    }
    @Test fun transactionAbortReleasesJobButNeverRevokesAnotherBookOrCopiesItsAudio() = runBlocking<Unit> {
        val retained=add("a-01"); val track=db.library().tracks(retained).single()
        db.library().insertBookmark(Bookmark("synthetic-retained",retained,track.id,400,"Keep failed link note",7))
        val before=db.books().get(retained)
        val preview=prepare("b-01")
        val rejected=UUID.randomUUID().toString()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER linked_commit_abort BEFORE INSERT ON books WHEN NEW.id='$rejected' BEGIN SELECT RAISE(ABORT,'synthetic link abort'); END")
        try {
            try { linker.commit(preview,preview.tracks,"Rejected synthetic link",rejected); fail("Expected transaction abort") } catch (_: android.database.sqlite.SQLiteException) { }
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER linked_commit_abort") }
        assertEquals(before,db.books().get(retained)); assertNull(db.books().get(rejected))
        assertEquals("Keep failed link note",db.library().bookmarks(retained).single().note)
        assertTrue(isPersisted()); assertTrue(db.library().jobs().isEmpty())
        assertTrue(File(sandbox,"audiobooks/media").listFiles().orEmpty().isEmpty())
        val later=add("b-01"); assertNotNull(db.books().get(later))
    }
    @Test fun reconnectRequiresFullVerifiedEqualityAndKeepsAllStableRows() = runBlocking<Unit> {
        val bookId=add("a-01"); val track=db.library().tracks(bookId).single()
        val state=ReadingState(bookId,track.id,700,1.25f,false,123)
        val mark=Bookmark("synthetic-reconnect-note",bookId,track.id,500,"Keep reconnect note",456)
        db.library().saveProgress(state); db.library().insertBookmark(mark)
        db.books().setOverrides(bookId,"Keep reconnect title",null,null,null,"Keep translator","3.5")
        try { linker.reconnect(bookId,listOf(selected("a-01"))); fail("Unverified location matched") } catch (_:UnverifiedLinkedAudioException) { }
        linker.verifyBook(bookId)
        val book=db.books().get(bookId); val asset=db.library().asset(track.assetId); val source=db.sources().source(track.assetId)
        val replacementId="linked-test-${UUID.randomUUID()}-root"
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",replacementId,null))
        val replacementRoot=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",replacementId)
        suspend fun replacement(id:String):SelectedDocument {
            val selected=DocumentInventory(resolver).describe(DocumentsContract.buildDocumentUriUsingTree(replacementRoot,id))
            requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-file-grant",selected.uri.toString(),null))
            return selected
        }
        try { linker.reconnect(bookId,listOf(replacement("b-01"))); fail("Different full audio identity matched") }
        catch (e:DocumentSourceException) { assertEquals(DocumentSourceException.Reason.CHANGED,e.reason) }
        assertEquals(source,db.sources().source(track.assetId)); assertEquals(asset,db.library().asset(track.assetId))
        val equal=replacement("a-01")
        assertEquals(1,linker.reconnect(bookId,listOf(equal)))
        assertEquals(equal.uri.toString(),db.sources().source(track.assetId)!!.documentUri)
        assertEquals(book,db.books().get(bookId)); assertEquals(listOf(track),db.library().tracks(bookId))
        assertEquals(asset,db.library().asset(track.assetId)); assertEquals(state,db.library().progress(bookId)); assertEquals(listOf(mark),db.library().bookmarks(bookId))
    }

    @Test fun completedRescanAndReviewedAppendRetainMissingTracksOrderAndEverySavedRow() = runBlocking<Unit> {
        val scanId="linked-test-rescan-${UUID.randomUUID()}-root"
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",scanId,null))
        val scanRoot=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",scanId)
        val folder=DocumentsContract.buildDocumentUriUsingTree(scanRoot,"scan-book:$scanId")
        val selected=DocumentInventory(resolver).treeInventory(scanRoot,rootDocument=folder).audioFiles
        assertEquals(2,selected.size)
        val initial=linker.prepare(selected,listOf(scanRoot),folder,scanRoot)
        val b=UUID.randomUUID().toString(); linker.commit(initial,initial.tracks,"Keep scanned book",b)
        val oldTracks=db.library().tracks(b)
        val oldAssets=oldTracks.map { db.library().asset(it.assetId)!! }
        val oldChapters=oldTracks.associate { it.id to db.library().chapters(it.id) }
        val state=ReadingState(b,oldTracks.first().id,700,1.25f,false,123)
        val mark=Bookmark("scan-retained-note",b,oldTracks.first().id,400,"Keep scan note",456)
        db.library().saveProgress(state); db.library().insertBookmark(mark)
        db.books().setOverrides(b,"Keep edited scan title",null,"Keep narrator","Keep series","Keep translator","3.5")
        val book=db.books().get(b)
        fun mode(value:Int) { requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-rescan","$scanId|$value",null)) }
        mode(1)
        val sources=oldAssets.associate { it.id to db.sources().source(it.id)!! }
        try { linker.rescan(b,maxEntries=1); fail("Expected partial inventory limit") } catch (_:java.io.IOException) { }
        assertEquals(sources,oldAssets.associate { it.id to db.sources().source(it.id)!! })
        mode(3)
        try { linker.rescan(b); fail("Expected provider failure") } catch (_:java.io.IOException) { }
        assertEquals(sources,oldAssets.associate { it.id to db.sources().source(it.id)!! })
        mode(2)
        val scan=linker.rescan(b)
        assertEquals(1,scan.missingTracks); assertEquals(1,scan.changedTracks)
        assertEquals(listOf("00-new.wav"),scan.newFiles.map { it.name })
        val preview=linker.prepare(scan.newFiles,listOf(scanRoot),folder,scanRoot)
        assertEquals(b,linker.append(preview,preview.tracks,b))
        val tracks=db.library().tracks(b)
        assertEquals(oldTracks,tracks.take(2)); assertEquals(2,tracks.last().position)
        assertEquals(book,db.books().get(b)); assertEquals(state,db.library().progress(b)); assertEquals(listOf(mark),db.library().bookmarks(b))
        assertEquals(oldAssets,oldTracks.map { db.library().asset(it.assetId)!! })
        assertEquals(oldChapters,oldTracks.associate { it.id to db.library().chapters(it.id) })
        assertEquals("MISSING",db.sources().source(oldTracks.first().assetId)!!.availability)
        assertEquals("CHANGED",db.sources().source(oldTracks.last().assetId)!!.availability)
        val stale=linker.prepare(scan.newFiles,listOf(scanRoot),folder,scanRoot)
        try { linker.append(stale,stale.tracks,b); fail("Stale review duplicated an existing URI") } catch (_:IllegalArgumentException) { }
        assertEquals(tracks,db.library().tracks(b)); assertEquals(listOf(mark),db.library().bookmarks(b))
        assertTrue(db.library().jobs().isEmpty()); assertTrue(File(sandbox,"audiobooks/media").listFiles().orEmpty().isEmpty())
    }

    @Test fun explicitMetadataScanCompletesBudgetDeferredFilesAndRetainsPendingLimitsAndEverySavedRow() = runBlocking<Unit> {
        val metadataId="linked-test-metadata-${UUID.randomUUID()}-root"
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",metadataId,null))
        val tree=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",metadataId)
        val folder=DocumentsContract.buildDocumentUriUsingTree(tree,"linked-metadata-book")
        val files=DocumentInventory(resolver).treeInventory(tree,rootDocument=folder).audioFiles
        val preview=linker.prepare(files,listOf(tree),folder,tree)
        assertEquals(2,preview.tracks.size)
        assertTrue("Real 2MiB addition budget must defer both large JUNK headers",preview.tracks.all { it.pending })
        val b=UUID.randomUUID().toString(); linker.commit(preview,preview.tracks,"Keep deferred title",b)
        val tracks=db.library().tracks(b)
        val state=ReadingState(b,tracks.first().id,700,1.25f,false,123)
        val mark=Bookmark("metadata-note",b,tracks.first().id,400,"Keep metadata note",456)
        db.library().saveProgress(state); db.library().insertBookmark(mark)
        db.books().setOverrides(b,"Keep metadata title","Keep author","Keep narrator","Keep series","Keep translator","3.5")
        val book=db.books().get(b)
        val assets=tracks.map { db.library().asset(it.assetId)!! }
        val chapters=tracks.associate { it.id to db.library().chapters(it.id) }
        // Cancellation after actual parsing must not publish a partial cache update.
        try { linker.readPendingMetadata(b) { done,_,_ -> if (done==1) throw CancellationException("Synthetic cancellation after metadata") }; fail("Expected cancellation") }
        catch (_:CancellationException) { }
        assertEquals(assets,tracks.map { db.library().asset(it.assetId)!! })
        assertTrue(tracks.all { db.sources().source(it.assetId)!!.metadataPending })
        // A real SQL abort must roll back the complete pass, not just its last source.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER metadata_abort BEFORE UPDATE OF metadataPending ON document_sources BEGIN SELECT RAISE(ABORT,'synthetic metadata abort'); END")
        try { try { linker.readPendingMetadata(b); fail("Expected SQL abort") } catch (_:android.database.sqlite.SQLiteException) { } }
        finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER metadata_abort") }
        assertEquals(assets,tracks.map { db.library().asset(it.assetId)!! })
        assertEquals(chapters,tracks.associate { it.id to db.library().chapters(it.id) })
        val result=linker.readPendingMetadata(b)
        assertEquals(1,result.completedFiles); assertEquals(1,result.pendingFiles)
        assertTrue(result.sourceBytesRead in (2L*1024*1024+1)..(2*LinkedImportCoordinator.METADATA_FILE_BYTES))
        assertEquals(2000L,db.library().asset(tracks.first().assetId)!!.durationMs)
        assertFalse(db.sources().source(tracks.first().assetId)!!.metadataPending)
        assertTrue(db.sources().source(tracks.last().assetId)!!.metadataPending)
        assertEquals(chapters[tracks.first().id]!!.single().copy(endMs=2000),db.library().chapters(tracks.first().id).single())
        assertEquals(chapters[tracks.last().id],db.library().chapters(tracks.last().id))
        assertEquals(book,db.books().get(b)); assertEquals(tracks,db.library().tracks(b))
        assertEquals(state,db.library().progress(b)); assertEquals(listOf(mark),db.library().bookmarks(b))
        assertEquals(assets.map { it.sha256 },tracks.map { db.library().asset(it.assetId)!!.sha256 })
        assertTrue(tracks.all { !db.library().asset(it.assetId)!!.identityVerified })
        val again=linker.readPendingMetadata(b)
        assertEquals(0,again.completedFiles); assertEquals(1,again.pendingFiles)
        assertEquals(book,db.books().get(b)); assertEquals(state,db.library().progress(b))
        assertTrue(db.library().jobs().isEmpty()); assertTrue(File(sandbox,"audiobooks/media").listFiles().orEmpty().isEmpty())
    }

    @Test fun reviewMetadataReadsLargeTagsAndMatchesPrivateCoversAndChaptersWithoutAudioCopy() = runBlocking<Unit> {
        val originals=linkedMapOf<String,io.github.jnesew.paudiobooks.metadata.InspectedAudio>()
        for ((kind,name) in listOf("chapters" to "chapters.mp3","credits" to "credits.mp3","cover" to "cover.mp3")) {
            val file=File(context.filesDir,"metadata-fixtures/$name")
            assertTrue("Generated fixture missing",file.isFile)
            originals[kind]=MetadataInspector(context).inspect(file)
            requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"metadata-seed","metadata-seeded-$kind",Bundle().apply { putByteArray("bytes",file.readBytes()) }))
        }
        val taggedId="linked-test-tagged-${UUID.randomUUID()}-root"
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",taggedId,null))
        val tagged=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",taggedId)
        val kinds=listOf("chapters","credits","cover")
        val files=kinds.map { DocumentInventory(resolver).describe(DocumentsContract.buildDocumentUriUsingTree(tagged,"metadata-seeded-$it")) }
        val preview=linker.prepare(files,listOf(tagged),metadataForReview=true)
        assertTrue(preview.sourceBytesRead>3L*1024*1024)
        assertTrue(preview.tracks.none { it.pending })
        for ((index,kind) in kinds.withIndex()) {
            val expected=originals.getValue(kind); val actual=preview.tracks[index].metadata
            assertEquals(expected.bookMetadata,actual.bookMetadata)
            assertEquals(expected.chapters.map { it.title },actual.chapters.map { it.title }.filterNotNull())
            if (kind=="cover") assertArrayEquals(expected.artworkThumbnail,actual.artworkThumbnail)
        }
        val id=UUID.randomUUID().toString(); linker.commit(preview,preview.tracks,"Linked review metadata",id)
        assertNotNull(db.books().get(id)!!.coverPath)
        assertEquals(listOf("First","Second","Third"),db.library().chapters(db.library().tracks(id).first().id).map { it.title })
        assertTrue(File(sandbox,"audiobooks/media").listFiles().orEmpty().isEmpty())
    }

    @Test fun reviewMetadataMatchesPrivateM4bChaptersAcrossOffsetProviderRanges() = runBlocking<Unit> {
        val provider=Uri.parse("content://io.github.jnesew.paudiobooks.test.linkedfixture")
        for (name in listOf("chapters-both.m4b","chapters-quicktime.m4b","chapters-nero.m4b")) {
            val file=File(context.filesDir,"metadata-fixtures/$name")
            assertTrue("Missing generated fixture",file.isFile)
            val expected=MetadataInspector(context).inspect(file)
            assertTrue(expected.chapters.isNotEmpty())
            val seed=UUID.randomUUID().toString()
            val source=Uri.withAppendedPath(provider,"format-$seed")
            try {
                android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    requireNotNull(resolver.call(provider,"seed-format",seed,Bundle().apply { putString("name",name); putParcelable("descriptor",fd) }))
                }
                val preview=linker.prepare(listOf(DocumentInventory(resolver).describe(source)),listOf(source),metadataForReview=true)
                try {
                    val actual=preview.tracks.single()
                    assertFalse(actual.pending)
                    assertEquals(expected.bookMetadata,actual.metadata.bookMetadata)
                    assertEquals(expected.chapters.map { Triple(it.startMs,it.endMs,it.title) },actual.metadata.chapters.map { Triple(it.startMs,it.endMs,it.title) })
                    assertArrayEquals(expected.artworkThumbnail,actual.metadata.artworkThumbnail)
                } finally { importer.cancel(preview.jobId) }
            } finally { resolver.call(provider,"discard-format",seed,null) }
        }
    }

    @Test fun longM4bIndexBeyondTwoGiBReadsLiveProgressAndCompletesCoverChaptersWithoutAudioCopy() = runBlocking<Unit> {
        val source=File(context.cacheDir,"synthetic-long-index-${UUID.randomUUID()}.m4b")
        val bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val png=java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray();bitmap.recycle()
        LongM4bFixture.write(source,File(context.filesDir,"metadata-fixtures/chapters-nero.m4b").readBytes(),png)
        val seed=UUID.randomUUID().toString();val provider=Uri.parse("content://io.github.jnesew.paudiobooks.test.linkedfixture")
        val uri=Uri.withAppendedPath(provider,"format-$seed")
        try {
            android.os.ParcelFileDescriptor.open(source,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                requireNotNull(resolver.call(provider,"seed-sparse-format",seed,Bundle().apply { putParcelable("descriptor",fd);putLong("moovOffset",LongM4bFixture.MOOV_OFFSET) }))
            }
            val selected=DocumentInventory(resolver).describe(uri)
            assertTrue(selected.sizeBytes!!>Int.MAX_VALUE)
            val old=linker.prepare(listOf(selected),listOf(uri));assertTrue(old.tracks.single().pending)
            val pendingBook=UUID.randomUUID().toString();linker.commit(old,old.tracks,"Keep long book",pendingBook)
            val retainedTrack=db.library().tracks(pendingBook).single()
            val saved=ReadingState(pendingBook,retainedTrack.id,123456L,1.25f,false,123)
            db.library().saveProgress(saved)
            val retainedSource=db.sources().source(retainedTrack.assetId)!!
            // Reproduce the exact previous 16MiB review/explicit-scan ceiling.
            val handle=withContext(Dispatchers.IO) { access.open(uri,grants.leaseForSource(retainedSource),selected.sizeBytes) }
            try {
                val oldScan=MetadataInspector(context).inspectLinked(handle.descriptor,16L*1024*1024)
                assertNull(oldScan.metadata);assertEquals(16L*1024*1024,oldScan.sourceBytesRead)
            } finally { handle.close();handle.awaitClosed() }
            val recovered=linker.readPendingMetadata(pendingBook)
            assertEquals(1,recovered.completedFiles);assertEquals(0,recovered.pendingFiles)
            assertNotNull(db.books().get(pendingBook)!!.coverPath)
            assertEquals(listOf("First","Second","Third"),db.library().chapters(retainedTrack.id).map { it.title })
            assertEquals(saved,db.library().progress(pendingBook));assertEquals(retainedTrack.id,db.library().tracks(pendingBook).single().id)
            val live=java.util.concurrent.CopyOnWriteArrayList<Long>()
            val preview=linker.prepare(listOf(selected),listOf(uri),metadataForReview=true) { done,_,bytes -> if(done==0 && bytes>0) live+=bytes }
            assertTrue(live.isNotEmpty());assertTrue(live.any { it<preview.sourceBytesRead })
            assertTrue(preview.sourceBytesRead>16L*1024*1024 && preview.sourceBytesRead<=LinkedImportCoordinator.METADATA_BOOK_BYTES)
            val track=preview.tracks.single();assertFalse(track.pending)
            assertTrue(kotlin.math.abs(track.metadata.durationMs!!-LongM4bFixture.DURATION_MS)<=1)
            assertEquals(listOf("First","Second","Third"),track.metadata.chapters.map { it.title });assertNotNull(track.metadata.artworkThumbnail)
            val book=UUID.randomUUID().toString();linker.commit(preview,preview.tracks,"Synthetic 34-hour index",book)
            assertNotNull(db.books().get(book)!!.coverPath)
            assertTrue(File(sandbox,"audiobooks/media").listFiles().orEmpty().isEmpty())

        } finally { resolver.call(provider,"discard-format",seed,null);source.delete() }
    }

    @Test fun deferredEmbeddedChaptersCreditsAndCoverFillWithoutReplacingOverridesOrBookmarks() = runBlocking<Unit> {
        for ((kind,name) in listOf("chapters" to "chapters.mp3","credits" to "credits.mp3","cover" to "cover.mp3")) {
            val file=File(context.filesDir,"metadata-fixtures/$name"); assertTrue("Generated fixture missing",file.isFile)
            requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"metadata-seed","metadata-seeded-$kind",Bundle().apply { putByteArray("bytes",file.readBytes()) }))
        }
        val taggedId="linked-test-tagged-${UUID.randomUUID()}-root"
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",taggedId,null))
        val tagged=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",taggedId)
        val files=listOf("credits","chapters","cover").map { DocumentInventory(resolver).describe(DocumentsContract.buildDocumentUriUsingTree(tagged,"metadata-seeded-$it")) }
        val preview=linker.prepare(files,listOf(tagged))
        assertTrue("Initial real 2MiB budget must defer padded ID3",preview.tracks.all { it.pending })
        val b=UUID.randomUUID().toString(); linker.commit(preview,preview.tracks,"Retain reviewed title",b)
        val tracks=db.library().tracks(b)
        db.books().setOverrides(b,"Keep override",null,"","Keep series","Keep translator","9")
        val before=db.books().get(b)!!
        val state=ReadingState(b,tracks[1].id,4500,1.35f,false,123)
        val mark=Bookmark("deferred-chapter-note",b,tracks[1].id,4200,"Keep chapter note",456)
        db.library().saveProgress(state); db.library().insertBookmark(mark)
        val fallback=db.library().chapters(tracks[1].id).single()
        val read=linker.readPendingMetadata(b)
        assertEquals(3,read.completedFiles); assertEquals(0,read.pendingFiles)
        val book=db.books().get(b)!!
        assertEquals(before.title,book.title); assertEquals(before.titleOverride,book.titleOverride)
        assertNull("Disagreeing authors must not become a book author",book.importedAuthor)
        assertEquals(setOf("Synthetic Author","Fixture Author"),db.library().tracks(b).mapNotNull { it.metadata?.author }.toSet())
        assertEquals("Synthetic Narrator",book.importedNarrator)
        assertEquals("Synthetic Translator",book.importedTranslator); assertEquals("Synthetic Series",book.importedSeries); assertEquals("2.5",book.importedSeriesNumber)
        assertEquals(before.narratorOverride,book.narratorOverride); assertEquals(before.translatorOverride,book.translatorOverride)
        assertEquals(before.seriesOverride,book.seriesOverride); assertEquals(before.seriesNumberOverride,book.seriesNumberOverride)
        assertNotNull(book.coverPath); assertTrue(store.resolveCover(requireNotNull(book.coverPath))!!.isFile)
        val chapters=db.library().chapters(tracks[1].id)
        assertEquals(3,chapters.size); assertEquals(fallback.id,chapters.first().id)
        assertEquals(listOf("First","Second","Third"),chapters.map { it.title }); assertTrue(chapters.all { it.origin=="embedded" })
        assertEquals(tracks.map { it.id },db.library().tracks(b).map { it.id }); assertEquals(state,db.library().progress(b)); assertEquals(listOf(mark),db.library().bookmarks(b))
        assertTrue(tracks.all { !db.sources().source(it.assetId)!!.metadataPending })
        val again=linker.readPendingMetadata(b)
        assertEquals(0,again.completedFiles); assertEquals(0L,again.sourceBytesRead)
        assertEquals(chapters,db.library().chapters(tracks[1].id)); assertEquals(book,db.books().get(b))
        assertTrue(db.library().jobs().isEmpty()); assertTrue(File(sandbox,"audiobooks/media").listFiles().orEmpty().isEmpty())
    }

    @Test fun encodedCopyAndLinkedImportsUseLaterFileBookFieldsAndPersistCatalogWithoutUi() = runBlocking<Unit> {
        val provider = Uri.parse("content://io.github.jnesew.paudiobooks.test.linkedfixture")
        val seeds = mutableListOf<String>()
        try {
            val files = listOf("no-chapters.wav", "metadata.m4b").map { name ->
                val seed = UUID.randomUUID().toString(); seeds.add(seed)
                android.os.ParcelFileDescriptor.open(File(context.filesDir,"metadata-fixtures/$name"),android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    requireNotNull(resolver.call(provider,"seed-format",seed,Bundle().apply { putString("name",name); putParcelable("descriptor",descriptor) }))
                }
                DocumentInventory(resolver).describe(Uri.withAppendedPath(provider,"format-$seed"))
            }
            val preview = linker.prepare(files,files.map { it.uri })
            val linked = UUID.randomUUID().toString()
            linker.commit(preview,preview.tracks,"Reviewed title",linked)
            val copy = importer.prepare(files)
            val inspector = MetadataInspector(context)
            val tracks = copy.files.map { file ->
                val meta = inspector.inspect(store.stagedFile(file.copy.assetId))
                ImportTrack(file,ImportMetadata(meta.title,meta.durationMs,emptyList(),meta.bookMetadata,mimeType=meta.mimeType))
            }
            val copied = importer.commit(copy,tracks,"Reviewed title",DuplicateAction.MAKE_COPY)
            val a = db.books().get(linked)!!; val b = db.books().get(copied)!!
            for (book in listOf(a,b)) {
                assertEquals("Reviewed title",book.title); assertEquals("Shared Book",book.importedTitle)
                assertEquals("Doe, Jane",book.importedAuthor); assertEquals("Reader One",book.importedNarrator)
                assertEquals("Fiction",book.importedGenre); assertEquals("Synthetic Press",book.importedPublisher)
                assertEquals("2020-02",book.importedDate); assertEquals("2021-06-10",book.importedReleaseDate)
                assertEquals("Synthetic description\nSecond line",book.importedDescription)
            }
            assertEquals(db.library().tracks(linked).map { it.metadata },db.library().tracks(copied).map { it.metadata })
            importer.removeBook(linked); importer.removeBook(copied)
            grants.collectUnused()
        } finally { seeds.forEach { resolver.call(provider,"discard-format",it,null) } }
    }

    @Test fun actualLaterOnlyDeferredCreditsFillMissingFirstTrackWithoutChangingSavedState() = runBlocking<Unit> {
        val bytes = File(context.filesDir,"metadata-fixtures/metadata-v24.mp3").readBytes()
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"metadata-seed","metadata-seeded-credits",Bundle().apply { putByteArray("bytes",bytes) }))
        val taggedId="linked-test-tagged-${UUID.randomUUID()}-root"
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",taggedId,null))
        val tree=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",taggedId)
        val files=listOf(selected("a-01"),DocumentInventory(resolver).describe(DocumentsContract.buildDocumentUriUsingTree(tree,"metadata-seeded-credits")))
        val preview=linker.prepare(files,listOf(root,tree))
        assertFalse(preview.tracks[0].pending); assertTrue(preview.tracks[1].pending)
        val b=UUID.randomUUID().toString(); linker.commit(preview,preview.tracks,"Keep title",b)
        val tracks=db.library().tracks(b)
        val state=ReadingState(b,tracks.first().id,700,1.25f,false,123)
        val note=Bookmark("later-credit-note",b,tracks.first().id,400,"Keep later note",456)
        db.library().saveProgress(state); db.library().insertBookmark(note)
        db.books().setOverrides(b,"Keep title override","Keep author override",null,null)
        assertNull(db.books().get(b)!!.importedAuthor)
        assertEquals(1,linker.readPendingMetadata(b).completedFiles)
        val book=db.books().get(b)!!
        assertEquals("Doe, Jane, Ánne",book.importedAuthor); assertEquals("Reader One, Reader Two",book.importedNarrator)
        assertEquals("Fiction",book.importedGenre); assertEquals("Keep author override",book.authorOverride)
        assertEquals(tracks.map { it.id },db.library().tracks(b).map { it.id })
        assertEquals(state,db.library().progress(b)); assertEquals(listOf(note),db.library().bookmarks(b))
    }

    @Test fun repeatedBoundedMetadataPassDoesNotStarveLaterFilesBehindOversizedHeaders() = runBlocking<Unit> {
        val fairId="linked-test-fair-${UUID.randomUUID()}-root"
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",fairId,null))
        val tree=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",fairId)
        val files=(0..3).map { "metadata-limit-$it" }.plus("metadata-small").map {
            DocumentInventory(resolver).describe(DocumentsContract.buildDocumentUriUsingTree(tree,it))
        }
        val preview=linker.prepare(files,listOf(tree)); assertTrue(preview.tracks.all { it.pending })
        val b=UUID.randomUUID().toString(); linker.commit(preview,preview.tracks,"Keep fair metadata",b)
        val tracks=db.library().tracks(b)
        val note=Bookmark("fair-note",b,tracks.last().id,400,"Keep later file note",123)
        db.library().insertBookmark(note)
        val first=linker.readPendingMetadata(b)
        assertEquals(0,first.completedFiles); assertEquals(5,first.pendingFiles)
        assertEquals(LinkedImportCoordinator.METADATA_BOOK_BYTES,first.sourceBytesRead)
        assertTrue(db.sources().source(tracks.last().assetId)!!.metadataPending)
        val later=(1..4).map { linker.readPendingMetadata(b) }
        assertTrue(later.take(3).all { it.completedFiles==0 && it.pendingFiles==5 })
        val second=later.last()
        assertEquals(1,second.completedFiles); assertEquals(4,second.pendingFiles)
        assertTrue(second.sourceBytesRead<=LinkedImportCoordinator.METADATA_BOOK_BYTES)
        assertFalse(db.sources().source(tracks.last().assetId)!!.metadataPending)
        assertEquals(2000L,db.library().asset(tracks.last().assetId)!!.durationMs)
        assertTrue(tracks.take(4).all { db.sources().source(it.assetId)!!.metadataPending })
        assertEquals(tracks,db.library().tracks(b)); assertEquals(listOf(note),db.library().bookmarks(b))
        assertTrue(File(sandbox,"audiobooks/media").listFiles().orEmpty().isEmpty())
    }

    @Test fun largeSourceAdditionMeasuresBoundedReadsAndZeroPermanentAudioAgainstCopies() = runBlocking<Unit> {
        val costId="linked-test-cost-${UUID.randomUUID()}-root"
        requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",costId,null))
        val costRoot=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",costId)
        val selected=DocumentInventory(resolver).describe(DocumentsContract.buildDocumentUriUsingTree(costRoot,"linked-large"))
        assertEquals(64L*1024*1024+44,selected.sizeBytes)
        val starts=mutableListOf<Long>(); val reads=mutableListOf<Long>()
        repeat(2) {
            val start=SystemClock.elapsedRealtime()
            val preview=linker.prepare(listOf(selected),listOf(costRoot))
            linker.commit(preview,preview.tracks,"Synthetic cost link",UUID.randomUUID().toString())
            starts.add(SystemClock.elapsedRealtime()-start); reads.add(preview.sourceBytesRead)
            assertTrue(preview.sourceBytesRead in 1..LinkedImportCoordinator.FILE_INSPECTION_BYTES)
            assertTrue(File(sandbox,"audiobooks/media").listFiles().orEmpty().isEmpty())
        }
        val copyStart=SystemClock.elapsedRealtime()
        val copied=importer.prepare(listOf(selected))
        val meta=MetadataInspector(context).inspect(store.stagedFile(copied.files.single().copy.assetId))
        val copyBook=importer.commit(copied,listOf(ImportTrack(copied.files.single(),ImportMetadata(meta.title,meta.author,meta.durationMs,emptyList()))),"Synthetic cost copy",DuplicateAction.MAKE_COPY)
        val copyMs=SystemClock.elapsedRealtime()-copyStart
        val asset=db.library().asset(db.library().tracks(copyBook).single().assetId)!!
        val written=store.resolve(asset.relativePath)!!.length()
        assertEquals(selected.sizeBytes,written); assertEquals(written,copied.files.single().copy.sizeBytes)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2,Bundle().apply {
            putString("linkedCost",org.json.JSONObject().put("sourceBytes",written).put("initialLinkMs",starts[0]).put("repeatLinkMs",starts[1])
                .put("initialInspectionBytes",reads[0]).put("repeatInspectionBytes",reads[1]).put("linkedPermanentAudioBytes",0)
                .put("copyMs",copyMs).put("copiedSourceBytes",copied.files.single().copy.sizeBytes).put("copiedPermanentAudioBytes",written).toString())
        })
    }

}
