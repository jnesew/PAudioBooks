package io.github.jnesew.paudiobooks.storage

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.metadata.MetadataInspector
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class BoundedDocumentAccessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val root = Uri.parse("content://io.github.jnesew.paudiobooks.test.linkedfixture")
    private val bytes = ByteArray(150_000) { (it % 251).toByte() }
    private val released = AtomicInteger()
    private fun call(action: String, extras: Bundle? = null) = requireNotNull(resolver.call(root, action, null, extras)) { "Provider call failed: $action" }
    private fun lease() = AutoCloseable { released.incrementAndGet() }
    private suspend fun failure(reason: DocumentSourceException.Reason, block: () -> Unit) = withContext(Dispatchers.IO) {
        try { block(); fail("Expected $reason") } catch (e: DocumentSourceException) {
            assertEquals(reason, e.reason); assertNull(e.cause); assertFalse(e.toString().contains("content://"))
        }
    }
    @Before fun seed() = runBlocking<Unit> {
        assertTrue("Earlier document work still active", BoundedDocumentAccess.awaitIdle())
        call("seed", Bundle().apply { putByteArray("bytes", bytes) })
    }
    @After fun drain() = runBlocking<Unit> {
        call("release")
        assertTrue("Actual descriptor and worker cleanup did not finish", BoundedDocumentAccess.awaitIdle())
    }
    @Test fun exactDescriptorRangeAndRepeatedPositionalReadsNeverExposePadding() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val access = BoundedDocumentAccess(resolver)
            val before = BoundedDocumentAccess.sourceBytesRead.get()
            val handle = access.open(root.buildUpon().appendPath("range").build(), lease(), bytes.size.toLong(), 1000)
            assertEquals(17L, handle.start); assertEquals(bytes.size.toLong(), handle.length)
            handle.use {
                val first = it.read(0, bytes.size)
                assertEquals(65536, first.count)
                assertArrayEquals(bytes.copyOfRange(0,65536), first.bytes)
                val last = it.read(bytes.size - 7L, 1024)
                assertEquals(7, last.count); assertArrayEquals(bytes.takeLast(7).toByteArray(), last.bytes)
                assertEquals(-1, it.read(bytes.size.toLong(),1).count)
                assertEquals(0, it.read(0,0).count)
            }
            handle.close()
            assertEquals(65543L, BoundedDocumentAccess.sourceBytesRead.get() - before)
        }
        assertTrue(BoundedDocumentAccess.awaitIdle()); assertEquals(1, released.get())
    }
    @Test fun pipeOverflowChangedAndProviderErrorsReleaseWithoutPrivateCause() = runBlocking<Unit> {
        val access = BoundedDocumentAccess(resolver)
        failure(DocumentSourceException.Reason.NON_SEEKABLE) { access.open(root.buildUpon().appendPath("pipe").build(), lease()) }
        failure(DocumentSourceException.Reason.UNAVAILABLE) { access.open(root.buildUpon().appendPath("overflow").build(), lease()) }
        failure(DocumentSourceException.Reason.CHANGED) { access.open(root.buildUpon().appendPath("range").build(), lease(), 7) }
        failure(DocumentSourceException.Reason.CHANGED) { access.open(root.buildUpon().appendPath("range").build(), lease(), expectedModified = 2000) }
        failure(DocumentSourceException.Reason.UNAVAILABLE) { access.open(root.buildUpon().appendPath("denied").build(), lease()) }
        assertTrue(BoundedDocumentAccess.awaitIdle()); assertEquals(5, released.get())
    }
    @Test fun timedOutOpenRetainsBusyGuardAndLeaseUntilActualProviderRelease() = runBlocking<Unit> {
        call("arm")
        val access = BoundedDocumentAccess(resolver, 300)
        val started = SystemClock.elapsedRealtime()
        val task = async(Dispatchers.IO) {
            try { access.open(root.buildUpon().appendPath("blocked").build(), lease()); fail("Expected timeout") }
            catch (e: DocumentSourceException) { assertEquals(DocumentSourceException.Reason.TIMEOUT,e.reason) }
        }
        val limit = SystemClock.elapsedRealtime()+3000
        while (call("state")?.getBoolean("entered") != true && SystemClock.elapsedRealtime()<limit) delay(10)
        assertTrue(call("state")!!.getBoolean("entered"))
        // A main-looper barrier remains responsive while the actual remote open is blocked.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { assertTrue(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) }
        task.await(); assertTrue(SystemClock.elapsedRealtime()-started < 2500)
        assertFalse(BoundedDocumentAccess.awaitIdle(100)); assertEquals(0,released.get())
        failure(DocumentSourceException.Reason.BUSY) { access.open(root.buildUpon().appendPath("range").build(),lease()) }
        assertEquals(1,released.get())
        call("release")
        assertTrue(BoundedDocumentAccess.awaitIdle()); assertEquals(2,released.get())
        withContext(Dispatchers.IO) { access.open(root.buildUpon().appendPath("range").build(),lease()).use { assertEquals(bytes[23],it.read(23,1).bytes[0]) } }
        assertTrue(BoundedDocumentAccess.awaitIdle()); assertEquals(3,released.get())
    }
    @Test fun noMoreThanFourDescriptorLeasesCanAccumulate() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val access = BoundedDocumentAccess(resolver)
            val handles = (0 until 4).map { access.open(root.buildUpon().appendPath("range").build(),lease()) }
            try {
                try { access.open(root.buildUpon().appendPath("range").build(),lease()); fail("Expected bounded slots") }
                catch (e: DocumentSourceException) { assertEquals(DocumentSourceException.Reason.BUSY,e.reason) }
                assertEquals(1,released.get())
            } finally { handles.forEach { it.close() } }
        }
        assertTrue(BoundedDocumentAccess.awaitIdle()); assertEquals(5,released.get())
    }
    @Test fun blockedReadIsBoundedAndPrivatePlaybackRemainsUsable() = runBlocking<Unit> {
        call("arm")
        val access=BoundedDocumentAccess(resolver,300)
        val handle=withContext(Dispatchers.IO) { access.open(root.buildUpon().appendPath("blocked-read").build(),lease()) }
        try {
            val read=async(Dispatchers.IO) {
                try { handle.read(0,64); fail("Expected actual provider read timeout") }
                catch (e: DocumentSourceException) { assertEquals(DocumentSourceException.Reason.TIMEOUT,e.reason) }
            }
            val deadline=SystemClock.elapsedRealtime()+3000
            while (!call("state").getBoolean("entered") && SystemClock.elapsedRealtime()<deadline) delay(10)
            assertTrue(call("state").getBoolean("entered")); read.await()
            assertFalse(BoundedDocumentAccess.awaitIdle(100))
            withContext(Dispatchers.IO) {
                val file=File.createTempFile("synthetic-private-fallback-",".audio",context.cacheDir)
                try {
                    file.writeBytes(bytes)
                    val app=context.applicationContext as io.github.jnesew.paudiobooks.AudiobookApplication
                    val source=ApprovedAssetDataSource({ mapOf("private" to ApprovedAudioSource.PrivateFile(file)) },access,app.sourceGrants)
                    try {
                        source.open(androidx.media3.datasource.DataSpec(Uri.parse("paudio://asset/private")))
                        val output=ByteArray(64); assertEquals(64,source.read(output,0,64)); assertArrayEquals(bytes.copyOf(64),output)
                        assertEquals("paudio://asset/private",source.uri.toString())
                    } finally { source.close() }
                } finally { file.delete() }
            }
        } finally { handle.close(); call("release") }
        assertTrue(BoundedDocumentAccess.awaitIdle()); assertEquals(1,released.get())
        withContext(Dispatchers.IO) { access.open(root.buildUpon().appendPath("range").build(),lease()).use { assertEquals(64,it.read(0,64).count) } }
        assertTrue(BoundedDocumentAccess.awaitIdle()); assertEquals(2,released.get())
    }
    @Test fun closingDescriptorRetainsLeaseAndSlotUntilActualCloseReturns() = runBlocking<Unit> {
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val access=BoundedDocumentAccess(resolver,closeDescriptor={ descriptor ->
            entered.countDown()
            try { check(release.await(10,java.util.concurrent.TimeUnit.SECONDS)) } finally { descriptor.close() }
        })
        val handles=withContext(Dispatchers.IO) { (0 until 4).map { access.open(root.buildUpon().appendPath("range").build(),lease()) } }
        try {
            val start=SystemClock.elapsedRealtime()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { handles.first().close() }
            assertTrue(SystemClock.elapsedRealtime()-start<1000)
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(BoundedDocumentAccess.awaitIdle(100)); assertEquals(0,released.get())
            failure(DocumentSourceException.Reason.BUSY) { access.open(root.buildUpon().appendPath("range").build(),lease()) }
            assertEquals(1,released.get())
        } finally { release.countDown(); handles.forEach { it.close() } }
        assertTrue(BoundedDocumentAccess.awaitIdle()); assertEquals(5,released.get())
    }

    @Test fun isolatedInspectorUsesOffsetAndStopsAtByteBudgetThenRecovers() = runBlocking<Unit> {
        val pcmBytes = 12 * 16000
        val wave = java.nio.ByteBuffer.allocate(44+pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36+pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcmBytes)
        val audio = wave.array()
        call("seed",Bundle().apply { putByteArray("bytes",audio) })
        val inspector = MetadataInspector(context)
        val access = BoundedDocumentAccess(resolver)
        val handle = withContext(Dispatchers.IO) { access.open(root.buildUpon().appendPath("range").build(),lease()) }
        try {
            withContext(Dispatchers.IO) {
                assertEquals(17L,handle.start)
                assertArrayEquals("Bounded provider header",audio.copyOf(44),handle.read(0,44).bytes)
                val source = io.github.jnesew.paudiobooks.metadata.DescriptorDataSource(handle.descriptor.parcelFileDescriptor,handle.start,handle.length!!)
                source.open(androidx.media3.datasource.DataSpec(Uri.parse("local-inspection:///asset")))
                val header = ByteArray(44)
                try { assertEquals(44,source.read(header,0,44)); assertArrayEquals("Descriptor header, base=${handle.start}",audio.copyOf(44),header) } finally { source.close() }
            }
            val limited = try { inspector.inspectLinked(handle.descriptor,32) }
                catch (e: io.github.jnesew.paudiobooks.metadata.InspectionException) { throw AssertionError("Limited range failed: ${e.reason}, bytes=${e.sourceBytesRead}",e) }
            assertNull(limited.metadata); assertEquals(32L,limited.sourceBytesRead)
            val result = try { inspector.inspectLinked(handle.descriptor,2L*1024*1024) }
                catch (e: io.github.jnesew.paudiobooks.metadata.InspectionException) { throw AssertionError("Full range failed: ${e.reason}, bytes=${e.sourceBytesRead}",e) }
            assertNotNull(result.metadata)
            assertTrue(result.metadata!!.durationMs in 11_900L..12_100L)
            assertTrue(result.sourceBytesRead in 44..2L*1024*1024)
        } finally { handle.close() }
        assertTrue(BoundedDocumentAccess.awaitIdle()); assertEquals(1,released.get())
    }
}
