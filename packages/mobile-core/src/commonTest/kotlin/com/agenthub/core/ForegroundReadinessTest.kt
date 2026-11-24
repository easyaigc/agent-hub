package com.agenthub.core

import kotlin.test.*

class ForegroundReadinessTest {
    @Test fun cachedOnlineAndAnEmptyEventCannotCreateEitherProof() {
        val state = ForegroundReadiness()
        assertFalse(state.workspaceReady("target-a:credential-a"))
        assertFalse(state.threadReady("target-a:credential-a", "thread-a"))
    }

    @Test fun workspaceAndThreadEvidenceHaveIndependentScopes() {
        val state = ForegroundReadiness()
        val revision = state.revision
        assertTrue(state.confirmThread("target-a:credential-a", "thread-a", revision))
        assertTrue(state.threadReady("target-a:credential-a", "thread-a"))
        assertFalse(state.threadReady("target-a:credential-a", "thread-b"))
        assertFalse(state.workspaceReady("target-a:credential-a"))
        assertTrue(state.confirmWorkspace("target-b:credential-b", revision))
        assertFalse(state.workspaceReady("target-a:credential-a"))
        assertFalse(state.threadReady("target-b:credential-b", "thread-a"))
    }

    @Test fun backgroundAndColdRestoreRequireNewEvidence() {
        val original = ForegroundReadiness()
        val before = original.revision
        original.confirmWorkspace("target", before)
        original.confirmThread("target", "thread", before)
        original.invalidate()
        assertFalse(original.confirmWorkspace("target", before))
        assertFalse(original.confirmThread("target", "thread", before))
        assertFalse(original.workspaceReady("target"))
        assertTrue(original.confirmThread("target", "thread", original.revision))
        assertFalse(ForegroundReadiness().threadReady("target", "thread"))
    }

    @Test fun aFailureRevokesOnlyItsTargetAndRejectsLateResponses() {
        val state = ForegroundReadiness()
        val before = state.revision
        state.confirmWorkspace("a", before)
        state.confirmWorkspace("b", before)
        state.confirmThread("a", "thread", before)
        state.invalidateScope("a")
        assertFalse(state.workspaceReady("a"))
        assertFalse(state.threadReady("a", "thread"))
        assertTrue(state.workspaceReady("b"))
        assertFalse(state.confirmThread("a", "thread", before))
        assertTrue(state.confirmWorkspace("a", state.revision))
    }

    @Test fun repeatedConfirmationDoesNotRepublishAndEmptyScopesCannotAuthorize() {
        val state = ForegroundReadiness()
        assertFalse(state.confirmWorkspace("", state.revision))
        assertFalse(state.confirmThread("target", "", state.revision))
        assertTrue(state.confirmWorkspace("target", state.revision))
        assertFalse(state.confirmWorkspace("target", state.revision))
    }

    @Test fun anotherHostsFailureDoesNotDiscardAnInFlightValidatedResponse() {
        val state = ForegroundReadiness()
        val captured = state.revision
        state.invalidateScope("unselected-offline-host")
        assertTrue(state.confirmWorkspace("selected-healthy-host", captured))
        assertTrue(state.confirmThread("selected-healthy-host", "thread", captured))
        assertFalse(state.confirmWorkspace("unselected-offline-host", captured))
        state.invalidate()
        assertFalse(state.confirmWorkspace("selected-healthy-host", captured))
    }
}
