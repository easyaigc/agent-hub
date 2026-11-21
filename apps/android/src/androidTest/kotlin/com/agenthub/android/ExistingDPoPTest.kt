package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/** Only new, per-method test aliases. Never loads identity storage or a saved original alias. */
@RunWith(AndroidJUnit4::class)
class ExistingDPoPTest {
    private fun identity() = EasyWorkIdentity("existing-read-test-${UUID.randomUUID()}", "", "")
    private fun alias(identity: EasyWorkIdentity) = "agent-hub-easywork-dpop-" + easyHash(identity.deviceId.toByteArray(Charsets.UTF_8))
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Test fun absentIdentityReadNeverCreatesAnAlias() {
        val identity = identity(); val alias = alias(identity)
        assertFalse(store().containsAlias(alias))
        try {
            assertNotNull(runCatching { EasyWorkDPoP.loadExisting(identity) }.exceptionOrNull())
            assertFalse(store().containsAlias(alias))
        } finally {
            // Preserve a failed non-creation assertion, but do not leak an accidental test alias.
            if (store().containsAlias(alias)) store().deleteEntry(alias)
        }
    }

    @Test fun existingTestIdentityKeepsTheSameThumbprint() {
        val identity = identity(); val alias = alias(identity)
        assertFalse(store().containsAlias(alias))
        try {
            val original = EasyWorkDPoP.load(identity)
            val existing = EasyWorkDPoP.loadExisting(identity)
            assertEquals(original.thumbprint, existing.thumbprint)
            assertEquals(easyCanonical(original.jwk), easyCanonical(existing.jwk))
            assertTrue(store().containsAlias(alias))
        } finally {
            // This alias was absent before this method and contains no original identity/data.
            store().deleteEntry(alias)
            assertFalse(store().containsAlias(alias))
        }
    }
}
