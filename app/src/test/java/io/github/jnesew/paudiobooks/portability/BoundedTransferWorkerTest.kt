package io.github.jnesew.paudiobooks.portability

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BoundedTransferWorkerTest {
    private suspend fun awaitIdle(worker: BoundedTransferWorker) = withTimeout(3_000) {
        while (worker.busy) delay(5)
    }

    @Test fun blockedOpenTimesOutRejectsRetriesAndClosesLateResourceOnce() = runBlocking {
        val worker = BoundedTransferWorker(100)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        try {
            try { worker.run { resources -> release.await(); resources.own(Closeable { closed.incrementAndGet() }); "late" }; fail() }
            catch (error: TransferIoException) { assertEquals(TransferIoException.Reason.TIMEOUT, error.reason) }
            repeat(20) {
                try { worker.run { "retry" }; fail() }
                catch (error: TransferIoException) { assertEquals(TransferIoException.Reason.BUSY, error.reason) }
            }
        } finally { release.countDown() }
        awaitIdle(worker)
        assertEquals(1, closed.get())
        assertEquals("recovered", worker.run { "recovered" })
    }

    @Test fun cancellationReturnsWhileReadAndCancellationCallbackIgnoreIt() = runBlocking {
        val worker = BoundedTransferWorker(3_000)
        val started = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val closed = AtomicInteger()
        val job = async {
            worker.run(onCancel = { releaseCancel.await() }) { resources ->
                resources.own(Closeable { closed.incrementAndGet() })
                started.countDown(); releaseRead.await(); "late"
            }
        }
        try {
            withTimeout(1_000) { while (started.count > 0) delay(5) }
            withTimeout(1_000) { job.cancelAndJoin() }
            withTimeout(1_000) { while (closed.get() == 0) delay(5) }
            assertTrue(worker.busy)
            releaseRead.countDown()
            delay(30)
            assertTrue("Stuck cancellation must retain admission", worker.busy)
        } finally { releaseRead.countDown(); releaseCancel.countDown() }
        awaitIdle(worker)
        assertEquals(1, closed.get())
    }

    @Test fun blockedWriteAndCloseKeepAdmissionUntilBothReturn() = runBlocking {
        val worker = BoundedTransferWorker(100)
        val releaseWrite = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = AtomicInteger()
        try {
            try {
                worker.run { resources ->
                    resources.own(Closeable { closed.incrementAndGet(); closing.countDown(); releaseClose.await() })
                    releaseWrite.await(); Unit
                }
                fail()
            } catch (error: TransferIoException) { assertEquals(TransferIoException.Reason.TIMEOUT, error.reason) }
            assertTrue(closing.await(1, TimeUnit.SECONDS))
            releaseWrite.countDown()
            delay(30)
            assertTrue(worker.busy)
        } finally { releaseWrite.countDown(); releaseClose.countDown() }
        awaitIdle(worker)
        assertEquals(1, closed.get())
        assertEquals(2, worker.run { 2 })
    }

    @Test fun closeFailureNeverReportsSuccessAndProviderMessagesStayOpaque() = runBlocking {
        val worker = BoundedTransferWorker(1_000)
        try { worker.run { it.own(Closeable { error("content://private/source") }); "done" }; fail() }
        catch (error: TransferIoException) {
            assertEquals(TransferIoException.Reason.UNAVAILABLE, error.reason)
            assertNull(error.cause)
            assertFalse(error.message.orEmpty().contains("private"))
        }
        awaitIdle(worker)
    }

    @Test fun repeatedFastCompletionAndCancellationDoNotReopenAnActiveWorker() = runBlocking {
        val worker = BoundedTransferWorker(1_000)
        repeat(100) {
            val job = async { worker.run { "done" } }
            if (it % 2 == 0) job.cancelAndJoin() else assertEquals("done", job.await())
            awaitIdle(worker)
        }
    }

    @Test fun successfulCompletionReleasesAdmissionBeforeResumingImmediateCaller() = runBlocking {
        val worker = BoundedTransferWorker(1_000)
        // Unconfined resumes inline on the worker thread, deterministically exposing
        // a result delivered before admission is released (also observed on API 26).
        withContext(Dispatchers.Unconfined) {
            repeat(100) { index ->
                assertEquals(index, worker.run { index })
                assertFalse("Successful result must permit the next operation", worker.busy)
            }
        }
    }
}
