package io.github.jnesew.paudiobooks.metadata

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Host debugger holds only the inspection worker; production main/watchdog stays runnable. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class InspectionWatchdogTest {
    @Test fun watchdogTerminatesHeldWorkerAndNextInspectionSucceeds() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connected = CountDownLatch(1)
        val died = CountDownLatch(1)
        val replied = AtomicBoolean(false)
        var binder: IBinder? = null
        val marker = File(context.filesDir, "watchdog-continue")
        marker.delete()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                service.linkToDeath({ died.countDown() }, 0)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        val file = File(context.filesDir, "metadata-fixtures/chapters.mp3")
        assertTrue("Required generated synthetic fixture", file.isFile)
        val bound = context.bindService(Intent(context, InspectionService::class.java), connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(bound)
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            assertTrue(requireNotNull(binder).isBinderAlive)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("phase", "WATCHDOG_ATTACH_READY") })
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!marker.isFile) {
                check(SystemClock.elapsedRealtime() < deadline) { "Host did not attach debugger" }
                Thread.sleep(25)
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val start = SystemClock.elapsedRealtime()
                Messenger(requireNotNull(binder)).send(Message.obtain(null, REQUEST).apply {
                    replyTo = Messenger(Handler(Looper.getMainLooper()) { replied.set(true); true })
                    data.putParcelable(FD, descriptor)
                    data.putInt(MODE, MODE_AUDIO)
                })
                assertTrue("Production watchdog must kill the held worker process", died.await(36, TimeUnit.SECONDS))
                val elapsed = SystemClock.elapsedRealtime() - start
                assertTrue("Real 30-second watchdog, got $elapsed", elapsed in 29_000..36_000)
                assertFalse("Held worker must not complete inspection", replied.get())
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putLong("watchdogElapsedMs", elapsed)
                    putBoolean("isolatedBinderDied", true)
                })
            }
        } finally {
            marker.delete()
            if (bound) context.unbindService(connection)
        }
        assertEquals(3, MetadataInspector(context).inspect(file).chapters.size)
    }
}
