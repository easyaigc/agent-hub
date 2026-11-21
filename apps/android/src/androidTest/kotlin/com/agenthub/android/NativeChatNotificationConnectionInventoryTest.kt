package com.agenthub.android

import com.agenthub.core.TargetScope
import org.junit.Assert.*
import org.junit.Test

/** Pure production capture boundary. No Context, storage, Activity, Host, token provider or OS mutation. */
class NativeChatNotificationConnectionInventoryTest {
    private fun client(id: String) = MobileClient(id, "Inventory fixture", TargetScope("codex", null, null,
        id, "inventory-workspace", "desktop-runtime"), "https://inventory.example.invalid")

    @Test fun unusableLegacyAuthorityDoesNotHideAnotherComputer() {
        val old = client("unavailable-computer")
        val healthy = client("healthy-computer")
        val token = "healthy-fixture-" + "x".repeat(40)
        for (invalid in listOf("", "x".repeat(31), "x".repeat(513), "x".repeat(40) + " ", "x".repeat(40) + '\u0000')) {
            val credentials = mapOf(old.id to invalid, healthy.id to token)
            val before = credentials.toMap()
            val inventory = NativeChatNotificationSavedConnection.inventory(listOf(old, healthy), credentials::getValue)
            assertEquals(setOf(NativeChatNoticeTarget.from(old.scope!!)), inventory.unavailableTargets)
            assertEquals(listOf(healthy.id), inventory.connections.map { it.target.hostId })
            assertEquals(NativeChatNotificationSavedConnection.capture(healthy, token).snapshot.authorizationFingerprint,
                inventory.connections.single().snapshot.authorizationFingerprint)
            assertEquals(before, credentials)
        }
    }

    @Test fun directCaptureStillRejectsAnUnusableCredential() {
        try {
            NativeChatNotificationSavedConnection.capture(client("unavailable-computer"), "")
            fail("The immutable authority boundary must remain strict")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun corruptedCredentialStorageIsNotConvertedToMissingAuthority() {
        val failure = IllegalStateException("Synthetic credential storage failure")
        try {
            NativeChatNotificationSavedConnection.inventory(listOf(client("broken-store"))) { throw failure }
            fail("Storage failures must reach the existing recovery boundary")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }

    @Test fun missingAuthorityCannotRelaxTheMachineTargetBoundary() {
        val wrong = client("original-computer").copy(id = "different-computer")
        try {
            NativeChatNotificationSavedConnection.inventory(listOf(wrong)) { "" }
            fail("A target mismatch is not an unavailable credential")
        } catch (_: IllegalArgumentException) { }
    }
}
