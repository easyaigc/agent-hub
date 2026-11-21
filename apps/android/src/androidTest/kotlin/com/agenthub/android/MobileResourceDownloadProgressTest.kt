package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

class MobileResourceDownloadProgressTest {
    @Test fun sampledRateUsesRecentActualBytesAndExpiresWhenTheReadStalls() {
        var clock=0L;val values=mutableListOf<ResourceDownloadProgress>()
        val reporter=ResourceDownloadReporter(1000,{values.add(it)},{clock})
        reporter.downloading(0,force=true)
        clock=100_000_000;reporter.downloading(600)
        clock=200_000_000;reporter.downloading(1000)
        val latest=values.last()
        assertEquals(6000.0,values[1].bytesPerSecond(100_000_000)!!,0.0)
        assertEquals(4000.0,latest.bytesPerSecond(clock)!!,0.0)
        assertEquals(4000.0,latest.bytesPerSecond(1_699_999_999)!!,0.0)
        assertEquals(0.0,latest.bytesPerSecond(1_700_000_000)!!,0.0)
        assertEquals(1000L,latest.receivedBytes);assertEquals(1f,latest.fraction)
        reporter.verifying(1000)
        assertNull(values.last().bytesPerSecond(clock))
        assertEquals(ResourceDownloadPhase.Verifying,values.last().phase)
    }
    @Test fun throttlingRetainsTheLastPublishedByteIntervalWithoutInventingBytes() {
        var clock=0L;val values=mutableListOf<ResourceDownloadProgress>()
        val reporter=ResourceDownloadReporter(1000,{values.add(it)},{clock})
        reporter.downloading(0,force=true)
        clock=40_000_000;reporter.downloading(100)
        clock=80_000_000;reporter.downloading(200)
        assertEquals(1,values.size)
        clock=100_000_000;reporter.downloading(300)
        assertEquals(300L,values.last().receivedBytes)
        assertEquals(3000.0,values.last().bytesPerSecond(clock)!!,0.0)
        assertTrue(runCatching { reporter.downloading(299) }.isFailure)
        assertTrue(runCatching { reporter.downloading(1001) }.isFailure)
    }
    @Test fun cacheAndUnknownTotalsNeverPretendToBeNetworkThroughput() {
        assertNull(ResourceDownloadProgress().fraction)
        assertNull(ResourceDownloadProgress(ResourceDownloadPhase.Downloading,0,0).fraction)
        val cached=ResourceDownloadProgress(ResourceDownloadPhase.Cached,0,0)
        assertEquals(1f,cached.fraction);assertNull(cached.bytesPerSecond(10_000_000_000))
        assertTrue(runCatching { ResourceDownloadProgress(receivedBytes=2,totalBytes=1) }.isFailure)
        assertTrue(runCatching { ResourceDownloadProgress(sampledBytesPerSecond=Double.NaN) }.isFailure)
    }
}
