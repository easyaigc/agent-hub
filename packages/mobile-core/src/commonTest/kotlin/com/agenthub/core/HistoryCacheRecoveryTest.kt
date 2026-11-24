package com.agenthub.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class HistoryCacheRecoveryTest {
    @Test fun coldPlainPageNeedsAnAnchorButNoInventedRevision() {
        assertTrue(HistoryCacheRecovery.canKeepPrefix(false,false,-1,-1,true))
        assertFalse(HistoryCacheRecovery.canKeepPrefix(false,false,-1,-1,false))
    }
    @Test fun windowedHistoryRequiresItsOriginalRevision() {
        assertTrue(HistoryCacheRecovery.canKeepPrefix(true,true,7,7,true))
        assertFalse(HistoryCacheRecovery.canKeepPrefix(true,true,7,8,true))
        assertFalse(HistoryCacheRecovery.canKeepPrefix(true,true,-1,-1,true))
        assertFalse(HistoryCacheRecovery.canKeepPrefix(false,true,7,7,true))
    }
    @Test fun restoredWindowCannotExposeRowsOutsideTheCache() {
        assertEquals(24L,HistoryCacheRecovery.visibleLimit(80,24))
        assertEquals(20L,HistoryCacheRecovery.visibleLimit(-1,4))
    }
}
