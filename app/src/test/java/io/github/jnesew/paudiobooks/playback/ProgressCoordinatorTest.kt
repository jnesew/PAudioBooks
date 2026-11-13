package io.github.jnesew.paudiobooks.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProgressCoordinatorTest {

    @Test
    fun errorThenSuccessfulRetryClearsSaveFailedAndNotifiesStateChange() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val shouldFailBookA = AtomicBoolean(true)
        val saveAttempts = AtomicInteger(0)
        val retryLatch = CountDownLatch(1)
        val publishedStates = LinkedBlockingQueue<Boolean>()

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                val attempt = saveAttempts.incrementAndGet()
                if (snapshot.bookId == "BookA" && shouldFailBookA.get()) {
                    false
                } else {
                    if (attempt > 1) {
                        retryLatch.countDown()
                    }
                    true
                }
            },
            onStateChanged = { _, saveFailed ->
                publishedStates.offer(saveFailed)
            }
        )

        try {
            // Step 1: Checkpoint Book A and await save while sink is failing
            val snap1 = coordinator.checkpoint("BookA", "track1", 1000L, 1.0f, false)
            requireNotNull(snap1)
            val future1 = coordinator.awaitSave(snap1)

            // Verification 1: Must fail within 5 seconds, mark saveFailed, and notify state change
            val result1 = future1.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.ERROR_IO, result1)
            assertTrue("Coordinator must report saveFailed on storage failure", coordinator.isSaveFailed)
            assertTrue("BookA must be marked failed", coordinator.isBookFailed("BookA"))
            assertEquals("Must publish saveFailed = true", true, publishedStates.poll(5, TimeUnit.SECONDS))
            assertEquals("Must attempt write once in batch (no busy loop)", 1, saveAttempts.get())

            // Step 2: Storage recovers, controller issues retrySave
            shouldFailBookA.set(false)
            coordinator.retrySave()

            // Wait bounded time for actor to process retry and publish recovery notification
            assertTrue("Retry must execute and complete", retryLatch.await(5, TimeUnit.SECONDS))
            assertEquals("Must publish saveFailed = false on recovery", false, publishedStates.poll(5, TimeUnit.SECONDS))

            // Verification 2: Must succeed, clear saveFailed, and publish cleared state
            assertFalse("Coordinator must clear saveFailed after successful retry", coordinator.isSaveFailed)
            assertFalse("BookA must no longer be marked failed", coordinator.isBookFailed("BookA"))
            assertEquals("Committed sequence must match snapshot", snap1.sequence, coordinator.getCommitted("BookA"))
            assertEquals("Must attempt retry write exactly once more", 2, saveAttempts.get())
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun failureOnOneBookNeverStarvesOtherBooksOrHangsWaiters() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val bookAAttempts = AtomicInteger(0)
        val bookBAttempts = AtomicInteger(0)
        val bookAStarted = CountDownLatch(1)
        val allowBookAFinish = CountDownLatch(1)
        val bookBFinished = CountDownLatch(1)
        val publishedStates = LinkedBlockingQueue<Boolean>()

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                when (snapshot.bookId) {
                    "BookA" -> {
                        bookAAttempts.incrementAndGet()
                        bookAStarted.countDown()
                        allowBookAFinish.await(5, TimeUnit.SECONDS)
                        false // Disk error on Book A
                    }
                    "BookB" -> {
                        bookBAttempts.incrementAndGet()
                        bookBFinished.countDown()
                        true // Healthy storage for Book B
                    }
                    else -> true
                }
            },
            onStateChanged = { _, saveFailed ->
                publishedStates.offer(saveFailed)
            }
        )

        try {
            // Step 1: Checkpoint Book A so it enters saveSink and is in flight
            val snapA = coordinator.checkpoint("BookA", "trackA", 1000L, 1.0f, false)
            requireNotNull(snapA)
            val futureA = coordinator.awaitSave(snapA)

            assertTrue("Book A must enter saveSink", bookAStarted.await(5, TimeUnit.SECONDS))

            // Step 2: While Book A is demonstrably in flight, enqueue Book B
            val snapB = coordinator.checkpoint("BookB", "trackB", 2000L, 1.0f, false)
            requireNotNull(snapB)
            val futureB = coordinator.awaitSave(snapB)

            // Step 3: Release Book A to fail
            allowBookAFinish.countDown()

            // Verification:
            // Book B must succeed and commit within bounded timeout (not starved by Book A failure)
            val resultB = futureB.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.SUCCESS, resultB)
            assertTrue("Book B must complete save", bookBFinished.await(5, TimeUnit.SECONDS))
            assertEquals("Book B must be committed", snapB.sequence, coordinator.getCommitted("BookB"))

            // Book A waiter must receive ERROR_IO immediately (not hang)
            val resultA = futureA.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.ERROR_IO, resultA)
            assertTrue("Book A must remain pending for retry", coordinator.isBookFailed("BookA"))
            assertFalse("Book B must not be failed", coordinator.isBookFailed("BookB"))

            // Actor must not busy loop on failing Book A
            assertEquals("Book A must only be attempted once in batch", 1, bookAAttempts.get())
            assertEquals("Book B must be attempted once", 1, bookBAttempts.get())
            assertEquals("Must publish saveFailed = true", true, publishedStates.poll(5, TimeUnit.SECONDS))
        } finally {
            allowBookAFinish.countDown()
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun oldInFlightWriteFailsWhileNewerQueuedDropsStaleStateAndSavesNewerOnRetry() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val seq1InFlight = CountDownLatch(1)
        val allowSeq1ToComplete = CountDownLatch(1)
        val retryCompleted = CountDownLatch(1)
        val savedSnapshots = mutableListOf<ProgressSnapshot>()
        val publishedStates = LinkedBlockingQueue<Boolean>()

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                if (snapshot.sequence == 1L) {
                    seq1InFlight.countDown()
                    allowSeq1ToComplete.await(5, TimeUnit.SECONDS)
                    false // seq1 fails on I/O error
                } else {
                    savedSnapshots.add(snapshot)
                    retryCompleted.countDown()
                    true // newer snapshot succeeds on retry
                }
            },
            onStateChanged = { _, saveFailed ->
                publishedStates.offer(saveFailed)
            }
        )

        try {
            // Step 1: Enqueue sequence 1
            val snap1 = coordinator.checkpoint("BookA", "track1", 1000L, 1.0f, false)
            requireNotNull(snap1)
            assertEquals(1L, snap1.sequence)
            val future1 = coordinator.awaitSave(snap1)

            // Step 2: Wait until seq1 is demonstrably in flight inside saveSink
            assertTrue("seq1 must be in flight in saveSink", seq1InFlight.await(5, TimeUnit.SECONDS))

            // Step 3: While seq1 is in flight, enqueue seq2 for the same book (newer position)
            val snap2 = coordinator.checkpoint("BookA", "track1", 2000L, 1.0f, false)
            requireNotNull(snap2)
            assertEquals(2L, snap2.sequence)
            val future2 = coordinator.awaitSave(snap2)

            // Step 4: Allow seq1 to complete and fail
            allowSeq1ToComplete.countDown()

            // Both waiters for BookA receive ERROR_IO immediately (neither hangs)
            assertEquals(SaveResult.ERROR_IO, future1.get(5, TimeUnit.SECONDS))
            assertEquals(SaveResult.ERROR_IO, future2.get(5, TimeUnit.SECONDS))
            assertEquals("Must publish saveFailed = true", true, publishedStates.poll(5, TimeUnit.SECONDS))
            assertTrue(coordinator.isSaveFailed)

            // Step 5: Controller retries save. The coordinator must drop the stale failed seq1
            // and attempt the newer seq2 snapshot.
            coordinator.retrySave()
            assertTrue("Retry of newer snapshot must complete", retryCompleted.await(5, TimeUnit.SECONDS))

            // Await actual actor recovery notification (publishedStates false callback happens
            // in actor after commit and clearing saveFailed in synchronized block).
            assertEquals("Must publish saveFailed = false on recovery", false, publishedStates.poll(5, TimeUnit.SECONDS))

            // Assert actual sink and committed sequence
            assertEquals(1, savedSnapshots.size)
            assertEquals("Sink must receive newer snap2, not stale snap1", snap2.sequence, savedSnapshots[0].sequence)
            assertEquals("Sink must receive newer position", 2000L, savedSnapshots[0].position)
            assertEquals("Committed sequence must match snap2", snap2.sequence, coordinator.getCommitted("BookA"))
            assertFalse("Coordinator must clear saveFailed", coordinator.isSaveFailed)
        } finally {
            allowSeq1ToComplete.countDown()
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun closeWithInFlightAndPendingSnapshotsPerformsBestEffortDrainOffMain() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val snap1InFlight = CountDownLatch(1)
        val allowSnap1Finish = CountDownLatch(1)
        val savedSequences = ConcurrentHashMap<String, Long>()

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                if (snapshot.sequence == 1L) {
                    snap1InFlight.countDown()
                    allowSnap1Finish.await(5, TimeUnit.SECONDS)
                }
                savedSequences[snapshot.bookId] = snapshot.sequence
                true
            }
        )

        try {
            // Step 1: Enqueue snap1
            val snap1 = coordinator.checkpoint("Book1", "t1", 1000L, 1.0f, false)
            requireNotNull(snap1)
            val future1 = coordinator.awaitSave(snap1)

            // Wait until snap1 is demonstrably in flight
            assertTrue("snap1 must be in flight", snap1InFlight.await(5, TimeUnit.SECONDS))

            // Step 2: Enqueue snap2 for Book2 while snap1 is in flight
            val snap2 = coordinator.checkpoint("Book2", "t2", 2000L, 1.0f, false)
            requireNotNull(snap2)
            val future2 = coordinator.awaitSave(snap2)

            // Step 3: Trigger close while snap1 is in flight and snap2 is pending
            val closeFuture = coordinator.close()
            assertTrue("Coordinator must report closed", coordinator.isClosed)

            // Subsequent checkpoint after close must be rejected immediately
            val postCloseSnap = coordinator.checkpoint("Book3", "t3", 3000L, 1.0f, false)
            assertNull("Checkpoints after close must be rejected", postCloseSnap)

            // Step 4: Release snap1 to finish saving
            allowSnap1Finish.countDown()

            // Step 5: Bounded wait for close to finish draining off-main
            val closeResult = closeFuture.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.SUCCESS, closeResult)
            assertEquals(SaveResult.SUCCESS, future1.get(5, TimeUnit.SECONDS))
            assertEquals(SaveResult.SUCCESS, future2.get(5, TimeUnit.SECONDS))

            assertEquals("snap1 must be saved off-main", snap1.sequence, savedSequences["Book1"])
            assertEquals("snap2 must be saved off-main", snap2.sequence, savedSequences["Book2"])
            assertTrue("Coordinator must report terminated after drain completes", coordinator.isTerminated)
        } finally {
            allowSnap1Finish.countDown()
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun closeWithIoFailureReportsErrorAndDoesNotHang() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { false } // Disk write fails
        )

        try {
            val snap = coordinator.checkpoint("Book1", "t1", 1000L, 1.0f, false)
            requireNotNull(snap)
            val future = coordinator.awaitSave(snap)

            // Close coordinator during failure
            val closeFuture = coordinator.close()

            // Bounded failure semantics: reports ERROR_IO, never hangs indefinitely
            val closeResult = closeFuture.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.ERROR_IO, closeResult)
            assertEquals(SaveResult.ERROR_IO, future.get(5, TimeUnit.SECONDS))
            assertTrue(coordinator.isTerminated)
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun repeatedCloseWhileSinkIsGatedAwaitsActualDrainCompletion() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val sinkGated = CountDownLatch(1)
        val allowSinkToFinish = CountDownLatch(1)

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = {
                sinkGated.countDown()
                allowSinkToFinish.await(5, TimeUnit.SECONDS)
                true
            }
        )

        try {
            // Step 1: Enqueue write and await entry into saveSink
            val snap = coordinator.checkpoint("Book1", "t1", 1000L, 1.0f, false)
            requireNotNull(snap)
            assertTrue("Sink must be entered", sinkGated.await(5, TimeUnit.SECONDS))

            // Step 2: First close() call while sink is in-flight
            val closeFuture1 = coordinator.close()
            assertFalse("First close future must not be prematurely completed while sink is in-flight", closeFuture1.isDone)
            assertTrue(coordinator.isClosed)
            assertFalse(coordinator.isTerminated)

            // Step 3: Repeated close() call while sink is STILL in-flight
            val closeFuture2 = coordinator.close()
            assertFalse("Repeated close future must not report premature SUCCESS while sink is in-flight", closeFuture2.isDone)

            // Step 4: Third close() call while sink is STILL in-flight
            val closeFuture3 = coordinator.close()
            assertFalse("Third close future must not report premature SUCCESS while sink is in-flight", closeFuture3.isDone)
            assertEquals("All in-flight close callers must be registered as close waiters", 3, coordinator.getCloseWaitersCount())

            // Step 5: Release sink to complete saving
            allowSinkToFinish.countDown()

            // Step 6: All close futures must resolve to the actual drain result (SUCCESS)
            assertEquals(SaveResult.SUCCESS, closeFuture1.get(5, TimeUnit.SECONDS))
            assertEquals(SaveResult.SUCCESS, closeFuture2.get(5, TimeUnit.SECONDS))
            assertEquals(SaveResult.SUCCESS, closeFuture3.get(5, TimeUnit.SECONDS))
            assertTrue("Coordinator must be marked terminated after drain completion", coordinator.isTerminated)

            // Step 7: Subsequent close() call after completed termination returns actual recorded completion
            val closeFuture4 = coordinator.close()
            assertTrue("Subsequent close future must be immediately done after termination", closeFuture4.isDone)
            assertEquals(SaveResult.SUCCESS, closeFuture4.get())
        } finally {
            allowSinkToFinish.countDown()
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun repeatedCloseWhileSinkFailsReportsIoErrorToAllCloseWaiters() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val sinkGated = CountDownLatch(1)
        val allowSinkToFail = CountDownLatch(1)

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = {
                sinkGated.countDown()
                allowSinkToFail.await(5, TimeUnit.SECONDS)
                false // Storage fails
            }
        )

        try {
            val snap = coordinator.checkpoint("Book1", "t1", 1000L, 1.0f, false)
            requireNotNull(snap)
            assertTrue("Sink must be entered", sinkGated.await(5, TimeUnit.SECONDS))

            val closeFuture1 = coordinator.close()
            val closeFuture2 = coordinator.close()
            assertFalse("closeFuture1 must not be done while sink is in-flight", closeFuture1.isDone)
            assertFalse("closeFuture2 must not report premature SUCCESS while sink is in-flight", closeFuture2.isDone)

            allowSinkToFail.countDown()

            assertEquals(SaveResult.ERROR_IO, closeFuture1.get(5, TimeUnit.SECONDS))
            assertEquals(SaveResult.ERROR_IO, closeFuture2.get(5, TimeUnit.SECONDS))
            assertTrue(coordinator.isTerminated)

            // Subsequent close returns ERROR_IO
            val closeFuture3 = coordinator.close()
            assertTrue(closeFuture3.isDone)
            assertEquals(SaveResult.ERROR_IO, closeFuture3.get())
        } finally {
            allowSinkToFail.countDown()
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun lateAwaitSaveAfterCompletedShutdownResolvesDeterministicallyWithoutHanging() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                snapshot.bookId != "FailedBook"
            }
        )

        try {
            // Checkpoint committed book
            val committedSnap = coordinator.checkpoint("CommittedBook", "t1", 1000L, 1.0f, false)
            requireNotNull(committedSnap)

            // Trigger failure on FailedBook
            val failedSnap = coordinator.checkpoint("FailedBook", "t1", 1000L, 1.0f, false)
            requireNotNull(failedSnap)

            // Close and terminate coordinator
            coordinator.close().get(5, TimeUnit.SECONDS)
            assertTrue("Coordinator must be terminated", coordinator.isTerminated)

            // Scenario 1: Late awaitSave for an UNCOMMITTED snapshot after completed shutdown
            val uncommittedSnap = ProgressSnapshot(
                bookId = "CommittedBook",
                trackId = "t1",
                position = 9999L,
                speed = 1.0f,
                finished = false,
                sequence = 999L // Never committed
            )
            val lateFuture = coordinator.awaitSave(uncommittedSnap)
            assertTrue("Late awaitSave for uncommitted sequence must complete immediately", lateFuture.isDone)
            assertEquals(SaveResult.ERROR_UNKNOWN, lateFuture.get(5, TimeUnit.SECONDS))
            assertEquals("No dead waiter must ever be enqueued after termination", 0, coordinator.getWaitersCount())

            // Scenario 2: Late awaitSave for a COMMITTED snapshot after completed shutdown
            val committedFuture = coordinator.awaitSave(committedSnap)
            assertTrue("Late awaitSave for committed sequence must complete immediately", committedFuture.isDone)
            assertEquals(SaveResult.SUCCESS, committedFuture.get(5, TimeUnit.SECONDS))

            // Scenario 3: Late awaitSave for a FAILED snapshot after completed shutdown
            val failedFuture = coordinator.awaitSave(failedSnap)
            assertTrue("Late awaitSave for failed book must complete immediately", failedFuture.isDone)
            assertEquals(SaveResult.ERROR_IO, failedFuture.get(5, TimeUnit.SECONDS))

            assertEquals("No waiters must remain in coordinator", 0, coordinator.getWaitersCount())
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun unloadAndSaveDrainsPendingWritesCleanly() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val savedSequences = ConcurrentHashMap<String, Long>()

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                delay(10) // Small realistic I/O latency
                savedSequences[snapshot.bookId] = snapshot.sequence
                true
            }
        )

        try {
            val snap1 = coordinator.checkpoint("Book1", "t1", 5000L, 1.0f, false)
            val snap2 = coordinator.checkpoint("Book2", "t2", 8000L, 1.0f, false)
            requireNotNull(snap1)
            requireNotNull(snap2)

            val drainFuture = coordinator.unloadAndSave()

            // Wait with bounded timeout
            val drainResult = drainFuture.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.SUCCESS, drainResult)
            assertEquals(snap1.sequence, savedSequences["Book1"])
            assertEquals(snap2.sequence, savedSequences["Book2"])
            assertFalse("Unloading must reset to false after drain completes", coordinator.isUnloading)
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun canLoadBookGuardsAgainstUnloadingFailedCurrentBookAndClosed() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { false }
        )

        try {
            // Initially healthy
            assertTrue(coordinator.canLoadBook(null))
            assertTrue(coordinator.canLoadBook("Book1"))

            // Trigger failure on Book1
            val snap = coordinator.checkpoint("Book1", "t1", 1000L, 1.0f, false)
            requireNotNull(snap)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snap).get(5, TimeUnit.SECONDS))

            // Current failing book cannot be reloaded until resolved
            assertFalse("Cannot load if current loaded book has failed save", coordinator.canLoadBook("Book1"))
            // But a different book or initial load is permitted
            assertTrue(coordinator.canLoadBook("Book2"))
            assertTrue(coordinator.canLoadBook(null))

            coordinator.close().get(5, TimeUnit.SECONDS)
            assertFalse("Cannot load any book after coordinator is closed", coordinator.canLoadBook("Book2"))
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun concurrentBookSwitchesPreserveQueueOrder() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val committedOrder = mutableListOf<String>()

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                delay(5)
                committedOrder.add(snapshot.bookId)
                true
            }
        )

        try {
            val snap1 = coordinator.checkpoint("Book1", "t1", 1000L, 1.0f, false)
            val snap2 = coordinator.checkpoint("Book2", "t2", 2000L, 1.0f, false)
            val snap3 = coordinator.checkpoint("Book3", "t3", 3000L, 1.0f, false)
            requireNotNull(snap1)
            requireNotNull(snap2)
            requireNotNull(snap3)

            val future3 = coordinator.awaitSave(snap3)
            assertEquals(SaveResult.SUCCESS, future3.get(5, TimeUnit.SECONDS))

            assertEquals("All three books must be saved in FIFO queue order", listOf("Book1", "Book2", "Book3"), committedOrder)
            assertEquals(snap1.sequence, coordinator.getCommitted("Book1"))
            assertEquals(snap2.sequence, coordinator.getCommitted("Book2"))
            assertEquals(snap3.sequence, coordinator.getCommitted("Book3"))
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun regressionStaleQueuedWriteForSwitchedBookDeletedWhilePlayingCurrentBookDoesNotPauseCurrentBook() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Simulates library database state where BookA and BookB exist initially
        val activeLibraryBooks = ConcurrentHashMap.newKeySet<String>().apply {
            add("BookA")
            add("BookB")
        }

        val bookBInFlightLatch = CountDownLatch(1)
        val releaseBookBWriteLatch = CountDownLatch(1)
        val failureEvents = LinkedBlockingQueue<Pair<String?, Boolean>>()

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                if (snapshot.bookId == "BookB") {
                    bookBInFlightLatch.countDown()
                    releaseBookBWriteLatch.await(5, TimeUnit.SECONDS)
                }
                // Simulates LibraryRepository.saveProgress: returns false if book was deleted from library
                snapshot.bookId in activeLibraryBooks
            },
            onStateChanged = { failedBookId, saveFailed ->
                failureEvents.offer(failedBookId to saveFailed)
            }
        )

        try {
            // 1. User was playing Book B: checkpoint Book B
            val snapB = coordinator.checkpoint("BookB", "trackB", 5000L, 1.0f, false)
            requireNotNull(snapB)

            // Wait until Book B's write is in-flight at the sink
            assertTrue(bookBInFlightLatch.await(5, TimeUnit.SECONDS))

            // 2. User switches to Book A and Book A starts playing
            val currentBookId = "BookA"
            val snapA = coordinator.checkpoint("BookA", "trackA", 1000L, 1.0f, false)
            requireNotNull(snapA)

            // 3. User deletes Book B from the library while Book A is playing
            activeLibraryBooks.remove("BookB")

            // 4. Release Book B's write: saveSink runs and returns false because Book B was deleted
            releaseBookBWriteLatch.countDown()

            // Wait for coordinator to process Book B and then save Book A
            val futureA = coordinator.awaitSave(snapA)
            assertEquals(SaveResult.SUCCESS, futureA.get(5, TimeUnit.SECONDS))

            // The failure event reported to the service callback identifies Book B
            val failureEvent = failureEvents.poll(5, TimeUnit.SECONDS)
            assertNotNull("Failure event must be reported", failureEvent)
            assertEquals("BookB", failureEvent?.first)
            assertTrue(failureEvent?.second == true)

            // PlaybackPolicy verifies that failure on Book B must NOT pause current playing Book A
            val shouldPause = PlaybackPolicy.shouldPauseOnSaveFailure(currentBookId, failureEvent?.first)
            assertFalse("Stale write failure on deleted Book B must NEVER pause current playing Book A", shouldPause)
            assertFalse("Book A must not be marked as failed", coordinator.isBookFailed("BookA"))

            // Evict Book B cleans up any residual state for the deleted book
            coordinator.evictBook("BookB")
            assertFalse("Book B must be cleared from failed books after evict", coordinator.isBookFailed("BookB"))
            assertFalse("Coordinator must not report saveFailed when only evicted book had failure", coordinator.isSaveFailed)
        } finally {
            releaseBookBWriteLatch.countDown()
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun evictBookClearsQueuedWritesAndResolvesWaitersWithoutHanging() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val inFlightLatch = CountDownLatch(1)
        val releaseSinkLatch = CountDownLatch(1)

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                if (snapshot.bookId == "BookA") {
                    inFlightLatch.countDown()
                    releaseSinkLatch.await(5, TimeUnit.SECONDS)
                }
                true
            }
        )

        try {
            // Checkpoint Book A to hold the sink in flight
            val snapA = coordinator.checkpoint("BookA", "trackA", 1000L, 1.0f, false)
            requireNotNull(snapA)
            val futureA = coordinator.awaitSave(snapA)

            assertTrue(inFlightLatch.await(5, TimeUnit.SECONDS))

            // While Book A is in flight, checkpoint Book B (it stays queued in pendingWrites)
            val snapB = coordinator.checkpoint("BookB", "trackB", 2000L, 1.0f, false)
            requireNotNull(snapB)
            val futureB = coordinator.awaitSave(snapB)

            // Evict Book B while it is queued
            coordinator.evictBook("BookB")

            // Waiter for Book B resolves truthfully with ERROR_UNKNOWN (not fake SUCCESS)
            assertEquals(SaveResult.ERROR_UNKNOWN, futureB.get(5, TimeUnit.SECONDS))

            // Release Book A's write and verify it completes cleanly
            releaseSinkLatch.countDown()
            assertEquals(SaveResult.SUCCESS, futureA.get(5, TimeUnit.SECONDS))

            // Book B was evicted, never written, never failed
            assertFalse(coordinator.isBookFailed("BookB"))
            assertFalse(coordinator.isSaveFailed)
        } finally {
            releaseSinkLatch.countDown()
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun currentBookActualWriteFailurePausesCurrentPlayer() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val failureLatch = CountDownLatch(1)
        val currentBookId = "BookA"
        val isPlayerPaused = AtomicBoolean(false)

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                if (snapshot.bookId == "BookA") false else true
            },
            onStateChanged = { failedBookId, saveFailed ->
                if (saveFailed && PlaybackPolicy.shouldPauseOnSaveFailure(currentBookId, failedBookId)) {
                    isPlayerPaused.set(true)
                    failureLatch.countDown()
                }
            }
        )

        try {
            val snapA = coordinator.checkpoint("BookA", "trackA", 1000L, 1.0f, false)
            requireNotNull(snapA)

            val futureA = coordinator.awaitSave(snapA)
            assertEquals(SaveResult.ERROR_IO, futureA.get(5, TimeUnit.SECONDS))

            // Await the callback latch to ensure no race between future resolution and callback execution
            assertTrue("Player pause callback must be triggered for current-book write failure", failureLatch.await(5, TimeUnit.SECONDS))
            assertTrue("Actual write failure on the current active playing book MUST pause the player", isPlayerPaused.get())
            assertTrue(coordinator.isBookFailed("BookA"))
            assertTrue(coordinator.isSaveFailed)
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun secondBookFirstFailureNotifiedEvenWithOtherBookAlreadyFailed() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val publishedEvents = LinkedBlockingQueue<Pair<String?, Boolean>>()

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { false }, // Both fail
            onStateChanged = { failedBookId, saveFailed ->
                publishedEvents.offer(failedBookId to saveFailed)
            }
        )

        try {
            // Step 1: Book B fails first
            val snapB = coordinator.checkpoint("BookB", "trackB", 1000L, 1.0f, false)
            requireNotNull(snapB)
            val futureB = coordinator.awaitSave(snapB)
            assertEquals(SaveResult.ERROR_IO, futureB.get(5, TimeUnit.SECONDS))

            val eventB = publishedEvents.poll(5, TimeUnit.SECONDS)
            assertNotNull("Failure event for BookB must be received", eventB)
            assertEquals("BookB", eventB?.first)
            assertTrue(eventB?.second == true)
            assertTrue("BookB must be marked failed", coordinator.isBookFailed("BookB"))
            assertTrue("Coordinator global isSaveFailed must be true", coordinator.isSaveFailed)

            // PlaybackPolicy check: BookB failure while playing BookA must NOT pause player
            assertFalse(
                "BookB failure must not pause player playing BookA",
                PlaybackPolicy.shouldPauseOnSaveFailure("BookA", eventB?.first)
            )

            // Step 2: Book A write fails while Book B is ALREADY failed (global failedBooks is non-empty)
            val snapA = coordinator.checkpoint("BookA", "trackA", 2000L, 1.0f, false)
            requireNotNull(snapA)
            val futureA = coordinator.awaitSave(snapA)
            assertEquals(SaveResult.ERROR_IO, futureA.get(5, TimeUnit.SECONDS))

            // Book A must still be notified even though global failedBooks was already non-empty
            val eventA = publishedEvents.poll(5, TimeUnit.SECONDS)
            assertNotNull("Failure event for BookA must be received even with BookB already failed", eventA)
            assertEquals("BookA", eventA?.first)
            assertTrue(eventA?.second == true)
            assertTrue("BookA must be marked failed", coordinator.isBookFailed("BookA"))
            assertTrue("BookB must remain marked failed", coordinator.isBookFailed("BookB"))
            assertTrue("Coordinator global isSaveFailed must be true", coordinator.isSaveFailed)

            // PlaybackPolicy check: BookA failure while playing BookA MUST pause player
            assertTrue(
                "BookA failure while playing BookA MUST pause player",
                PlaybackPolicy.shouldPauseOnSaveFailure("BookA", eventA?.first)
            )
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun oneBookRecoversAndEmitsFalseWhileOtherBookRemainsFailed() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val publishedEvents = LinkedBlockingQueue<Pair<String?, Boolean>>()
        val shouldBookASucceed = AtomicBoolean(false)

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                if (snapshot.bookId == "BookA" && shouldBookASucceed.get()) {
                    true
                } else {
                    false
                }
            },
            onStateChanged = { failedBookId, saveFailed ->
                publishedEvents.offer(failedBookId to saveFailed)
            }
        )

        try {
            // Step 1: Book B and Book A both fail
            val snapB = coordinator.checkpoint("BookB", "trackB", 1000L, 1.0f, false)
            requireNotNull(snapB)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snapB).get(5, TimeUnit.SECONDS))

            val snapA = coordinator.checkpoint("BookA", "trackA", 2000L, 1.0f, false)
            requireNotNull(snapA)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snapA).get(5, TimeUnit.SECONDS))

            val eventB = publishedEvents.poll(5, TimeUnit.SECONDS)
            assertEquals("BookB", eventB?.first)
            assertTrue(eventB?.second == true)

            val eventA = publishedEvents.poll(5, TimeUnit.SECONDS)
            assertEquals("BookA", eventA?.first)
            assertTrue(eventA?.second == true)

            assertTrue(coordinator.isBookFailed("BookA"))
            assertTrue(coordinator.isBookFailed("BookB"))
            assertTrue(coordinator.isSaveFailed)

            // Step 2: Book A recovers (succeeds on retry), but Book B remains failed
            shouldBookASucceed.set(true)
            coordinator.retrySave()

            // Step 3: Must emit recovery event for Book A (failed = false) even though Book B remains failed
            val recoveryEvent = publishedEvents.poll(5, TimeUnit.SECONDS)
            assertNotNull("Recovery event for BookA must be received", recoveryEvent)
            assertEquals("BookA", recoveryEvent?.first)
            assertFalse("BookA recovery event must have failed = false", recoveryEvent?.second == true)

            assertFalse("BookA must no longer be marked failed", coordinator.isBookFailed("BookA"))
            assertTrue("BookB must remain marked failed", coordinator.isBookFailed("BookB"))
            assertTrue("Coordinator global isSaveFailed must remain true because BookB is still failed", coordinator.isSaveFailed)
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun evictBookClearsItsBookNotificationWithoutClearingOtherBookFailure() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val publishedEvents = LinkedBlockingQueue<Pair<String?, Boolean>>()

        val coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { false }, // Both fail initially
            onStateChanged = { failedBookId, saveFailed ->
                publishedEvents.offer(failedBookId to saveFailed)
            }
        )

        try {
            // Step 1: Both Book B and Book A fail
            val snapB = coordinator.checkpoint("BookB", "trackB", 1000L, 1.0f, false)
            requireNotNull(snapB)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snapB).get(5, TimeUnit.SECONDS))

            val snapA = coordinator.checkpoint("BookA", "trackA", 2000L, 1.0f, false)
            requireNotNull(snapA)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snapA).get(5, TimeUnit.SECONDS))

            val eventB = publishedEvents.poll(5, TimeUnit.SECONDS)
            assertEquals("BookB", eventB?.first)
            assertTrue(eventB?.second == true)

            val eventA = publishedEvents.poll(5, TimeUnit.SECONDS)
            assertEquals("BookA", eventA?.first)
            assertTrue(eventA?.second == true)

            assertTrue(coordinator.isBookFailed("BookA"))
            assertTrue(coordinator.isBookFailed("BookB"))
            assertTrue(coordinator.isSaveFailed)

            // Step 2: Evict Book B (e.g. deleted from library)
            coordinator.evictBook("BookB")

            // Must emit cleared event for Book B (failed = false)
            val evictEvent = publishedEvents.poll(5, TimeUnit.SECONDS)
            assertNotNull("Evict event for BookB must be received", evictEvent)
            assertEquals("BookB", evictEvent?.first)
            assertFalse("Evicted BookB must have failed = false", evictEvent?.second == true)

            assertFalse("BookB must no longer be marked failed", coordinator.isBookFailed("BookB"))
            assertTrue("BookA must remain marked failed", coordinator.isBookFailed("BookA"))
            assertTrue("Coordinator global isSaveFailed must remain true because BookA is still failed", coordinator.isSaveFailed)
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun unloadRetryRequestedWhilePriorBatchIsPublishingRecoveryIsNotCompletedWithStaleFailure() {
        val dispatcher=object: kotlinx.coroutines.CoroutineDispatcher() {
            val tasks=java.util.ArrayDeque<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
            fun runPending() { var count=0; while (tasks.isNotEmpty()) { check(++count<100); tasks.removeFirst().run() } }
        }
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        var saveA=false; var existsB=true; var attemptsB=0
        var drained: com.google.common.util.concurrent.ListenableFuture<SaveResult>?=null
        lateinit var coordinator: ProgressCoordinator
        coordinator=ProgressCoordinator(scope,saveSink={ snapshot ->
            if (snapshot.bookId=="BookB") {
                attemptsB++
                if (!existsB) { coordinator.evictBook("BookB"); true } else false
            } else saveA
        },onStateChanged={ book,failed ->
            // Recovery publication is outside the lock but still inside the batch
            // that already failed B. Request unload before that batch can finish.
            if (book=="BookA" && !failed) {
                assertEquals(2,attemptsB)
                existsB=false
                drained=coordinator.unloadAndSave()
                assertFalse(requireNotNull(drained).isDone)
            }
        })
        try {
            val b=requireNotNull(coordinator.checkpoint("BookB","trackB",1000,1f,false))
            val a=requireNotNull(coordinator.checkpoint("BookA","trackA",2000,1f,false))
            val failedB=coordinator.awaitSave(b); val failedA=coordinator.awaitSave(a)
            dispatcher.runPending()
            assertEquals(SaveResult.ERROR_IO,failedB.get(5,TimeUnit.SECONDS))
            assertEquals(SaveResult.ERROR_IO,failedA.get(5,TimeUnit.SECONDS))
            saveA=true; coordinator.retrySave(); dispatcher.runPending()
            assertEquals(SaveResult.SUCCESS,requireNotNull(drained).get(5,TimeUnit.SECONDS))
            assertEquals(3,attemptsB)
            assertFalse(coordinator.isSaveFailed); assertEquals(0,coordinator.getPendingCount())
        } finally { val closed=coordinator.close(); dispatcher.runPending(); closed.get(5,TimeUnit.SECONDS); scope.cancel() }
    }

    @Test
    fun preexistingFailureOnDeletedBookReconciledDuringUnloadDrainAndEvictedWithSuccess() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val existingBooks = ConcurrentHashMap<String, Boolean>()
        existingBooks["BookB"] = true
        existingBooks["BookA"] = true

        lateinit var coordinator: ProgressCoordinator
        coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                if (snapshot.bookId == "BookB") {
                    val exists = existingBooks[snapshot.bookId] == true
                    if (!exists) {
                        coordinator.evictBook(snapshot.bookId)
                        true
                    } else {
                        false
                    }
                } else {
                    true
                }
            }
        )

        try {
            // Step 1: Preexisting Book B failure while still existing in library
            val snapB = coordinator.checkpoint("BookB", "trackB", 1000L, 1.0f, false)
            requireNotNull(snapB)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snapB).get(5, TimeUnit.SECONDS))
            assertTrue(coordinator.isBookFailed("BookB"))
            assertTrue(coordinator.isSaveFailed)

            // Step 2: Book A plays and commits cleanly
            val snapA = coordinator.checkpoint("BookA", "trackA", 5000L, 1.0f, false)
            requireNotNull(snapA)
            assertEquals(SaveResult.SUCCESS, coordinator.awaitSave(snapA).get(5, TimeUnit.SECONDS))
            assertFalse(coordinator.isBookFailed("BookA"))

            // Step 3: Book B is deleted from library (row removed)
            existingBooks.remove("BookB")
            assertTrue("Book B still registered failed prior to unload retry", coordinator.isBookFailed("BookB"))

            // Step 4: Unload and drain Book A.
            // Bounded unload retry processes Book B, detects row absence via !exists, evicts Book B,
            // and completes cleanly with SUCCESS.
            val drainFuture = coordinator.unloadAndSave()
            val result = drainFuture.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.SUCCESS, result)

            assertFalse("Book B must be evicted and cleared from failedBooks", coordinator.isBookFailed("BookB"))
            assertFalse("Coordinator isSaveFailed must be cleared", coordinator.isSaveFailed)
            assertEquals(0, coordinator.getPendingCount())
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun preexistingFailureOnStillExistingBookDuringUnloadReportsErrorAndRetainsPendingState() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val existingBooks = ConcurrentHashMap<String, Boolean>()
        existingBooks["BookB"] = true
        existingBooks["BookA"] = true

        lateinit var coordinator: ProgressCoordinator
        coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                if (snapshot.bookId == "BookB") {
                    val exists = existingBooks[snapshot.bookId] == true
                    if (!exists) {
                        coordinator.evictBook(snapshot.bookId)
                        true
                    } else {
                        false
                    }
                } else {
                    true
                }
            }
        )

        try {
            // Step 1: Preexisting Book B failure
            val snapB = coordinator.checkpoint("BookB", "trackB", 1000L, 1.0f, false)
            requireNotNull(snapB)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snapB).get(5, TimeUnit.SECONDS))
            assertTrue(coordinator.isBookFailed("BookB"))

            // Step 2: Book A commits cleanly
            val snapA = coordinator.checkpoint("BookA", "trackA", 5000L, 1.0f, false)
            requireNotNull(snapA)
            assertEquals(SaveResult.SUCCESS, coordinator.awaitSave(snapA).get(5, TimeUnit.SECONDS))

            // Step 3: Book B still exists and continues failing
            assertTrue(existingBooks["BookB"] == true)

            // Step 4: Unload and drain.
            // Must report ERROR_IO because Book B still exists and write continues failing.
            // Truthful failure must be preserved, and live pending progress retained.
            val drainFuture = coordinator.unloadAndSave()
            val result = drainFuture.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.ERROR_IO, result)

            assertTrue("Book B must remain marked failed", coordinator.isBookFailed("BookB"))
            assertTrue("Coordinator global isSaveFailed must remain true", coordinator.isSaveFailed)
            assertTrue("Pending progress for existing failing book must be retained", coordinator.getPendingCount() > 0)
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun preexistingTransientFailureOnExistingBookRecoversDuringUnloadDrain() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val shouldFailBookB = AtomicBoolean(true)

        lateinit var coordinator: ProgressCoordinator
        coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                if (snapshot.bookId == "BookB") {
                    !shouldFailBookB.get()
                } else {
                    true
                }
            }
        )

        try {
            // Step 1: Preexisting transient Book B failure
            val snapB = coordinator.checkpoint("BookB", "trackB", 1000L, 1.0f, false)
            requireNotNull(snapB)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snapB).get(5, TimeUnit.SECONDS))
            assertTrue(coordinator.isBookFailed("BookB"))

            // Step 2: Transient condition clears (e.g. SQLite lock released) while Book B still exists
            shouldFailBookB.set(false)

            // Step 3: Unload and drain. Retry succeeds and clears failed state.
            val drainFuture = coordinator.unloadAndSave()
            val result = drainFuture.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.SUCCESS, result)

            assertFalse("Book B must recover and no longer be marked failed", coordinator.isBookFailed("BookB"))
            assertFalse("Coordinator global isSaveFailed must be cleared", coordinator.isSaveFailed)
            assertEquals(0, coordinator.getPendingCount())
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }

    @Test
    fun unloadDrainLimitsRetriesToAtMostOneAttemptPerBookWithoutHanging() = runBlocking<Unit> {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val attempts = ConcurrentHashMap<String, AtomicInteger>()

        lateinit var coordinator: ProgressCoordinator
        coordinator = ProgressCoordinator(
            scope = testScope,
            saveSink = { snapshot ->
                attempts.computeIfAbsent(snapshot.bookId) { AtomicInteger(0) }.incrementAndGet()
                false // Always fail
            }
        )

        try {
            // Step 1: Initial failed saves for two books
            val snapB = coordinator.checkpoint("BookB", "trackB", 1000L, 1.0f, false)
            requireNotNull(snapB)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snapB).get(5, TimeUnit.SECONDS))

            val snapC = coordinator.checkpoint("BookC", "trackC", 2000L, 1.0f, false)
            requireNotNull(snapC)
            assertEquals(SaveResult.ERROR_IO, coordinator.awaitSave(snapC).get(5, TimeUnit.SECONDS))

            assertEquals(1, attempts["BookB"]?.get())
            assertEquals(1, attempts["BookC"]?.get())

            // Step 2: Unload and drain.
            // Each failed book must be attempted at most once during the drain (bounded by failedInBatch).
            val drainFuture = coordinator.unloadAndSave()
            val result = drainFuture.get(5, TimeUnit.SECONDS)
            assertEquals(SaveResult.ERROR_IO, result)

            // Exactly 1 initial attempt + at most 1 retry attempt during unload = at most 2 total attempts
            assertTrue("Book B must have at most 2 attempts (no retry loop)", (attempts["BookB"]?.get() ?: 0) <= 2)
            assertTrue("Book C must have at most 2 attempts (no retry loop)", (attempts["BookC"]?.get() ?: 0) <= 2)
            assertFalse("Coordinator must reset unloading flag after bounded drain completes", coordinator.isUnloading)
        } finally {
            coordinator.close().get(5, TimeUnit.SECONDS)
            testScope.cancel()
        }
    }
}
