package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Actual platform callback registration/baseline; not a physical Wi-Fi/VPN switch proof. */
@RunWith(AndroidJUnit4::class)
class NativeReadNetworkMonitorTest {
    @Test fun platformRegistrationEmitsOpaqueBaselineAndStopsCleanly()=runBlocking {
        val events=CopyOnWriteArrayList<Pair<String,Boolean>>()
        val monitor=NativeReadNetworkMonitor(InstrumentationRegistry.getInstrumentation().targetContext) { path,baseline -> events.add(path to baseline) }
        try {
            withContext(Dispatchers.Main) { monitor.start();assertTrue(monitor.observing) }
            assertTrue(events.any { it.second });assertTrue(events.all { it.first.matches(Regex("[a-f0-9]{64}")) })
            withContext(Dispatchers.Main) { monitor.stop();assertFalse(monitor.observing) }
        } finally { withContext(Dispatchers.Main) { monitor.stop() } }
    }
}
