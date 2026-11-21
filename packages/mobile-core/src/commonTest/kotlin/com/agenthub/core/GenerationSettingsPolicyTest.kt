package com.agenthub.core

import kotlin.test.*

class GenerationSettingsPolicyTest {
    @Test fun currentCatalogueGatesRetainedSpeedCallbacksWithoutInventingStandard() {
        assertFalse(SessionPolicies.generationSpeedAllowed("standard", listOf("fast")))
        assertFalse(SessionPolicies.generationSpeedAllowed("fast", listOf("standard")))
        assertFalse(SessionPolicies.generationSpeedAllowed("ultrafast", listOf("standard", "fast")))
        assertFalse(SessionPolicies.generationSpeedAllowed("fast", emptyList()))
        assertTrue(SessionPolicies.generationSpeedAllowed("fast", listOf("fast")))
        assertTrue(SessionPolicies.generationSpeedAllowed("standard", listOf("standard", "fast")))
        assertTrue(SessionPolicies.generationSpeedAllowed("ultrafast", listOf("fast", "ultrafast")))
        assertTrue(SessionPolicies.generationSpeedAllowed(null, emptyList()))
        assertTrue(SessionPolicies.generationSpeedAllowed(null, listOf("fast")))
    }

    @Test fun modelChangeReconcilesOnlyExplicitSpeedAndPreservesFollowComputer() {
        for (speeds in listOf(emptyList(), listOf("fast"), listOf("standard", "fast"), listOf("fast", "ultrafast"))) {
            assertNull(SessionPolicies.changeModel("high", null, listOf("low"), "low", speeds).speed)
        }
        val fastOnly = SessionPolicies.changeModel("high", "ultrafast", listOf("low"), "low", listOf("fast"))
        assertEquals("low", fastOnly.effort)
        assertNull(fastOnly.speed)
        assertEquals("fast", SessionPolicies.changeModel("high", "fast", listOf("high"), "low", listOf("fast")).speed)
        assertEquals("standard", SessionPolicies.changeModel("high", "ultrafast", listOf("low"), "low", listOf("standard", "fast")).speed)
    }
}
