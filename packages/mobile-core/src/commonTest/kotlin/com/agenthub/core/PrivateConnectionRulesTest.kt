package com.agenthub.core

import kotlin.test.*

class PrivateConnectionRulesTest {
    private val target = TargetScope("codex", "a😀", "s", "h", "w", "r")
    @Test fun versionOneContextMatchesHostUTF16UnicodeVector() {
        assertEquals("37:agent-hub-codex-private-connection-v15:codex1:h1:w1:r3:a😀1:s43:KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK",
            PrivateConnectionRules.context(target, "K".repeat(43)))
        val otherAccount = TargetScope("codex", "a😀", "other", "h", "w", "r")
        assertTrue(RuntimeIdentity.matches(target, otherAccount))
        assertFalse(PrivateConnectionRules.capturedTargetMatches(target, otherAccount))
        assertNotEquals(PrivateConnectionRules.context(target, "K".repeat(43)), PrivateConnectionRules.context(otherAccount, "K".repeat(43)))
    }
    @Test fun vpnHintsRejectPublicLanAndMalformedLiterals() {
        for (address in listOf("100.64.0.0", "100.127.255.255", "fd7a:115c:a1e0::1", "FD7A:115C:A1E0:0:0:0:0:2")) assertTrue(PrivateConnectionRules.isVPNAddress(address), address)
        for (address in listOf("100.63.255.255", "100.128.0.0", "192.168.1.1", "8.8.8.8", "fd00::1", "fd7a:115c:a1e0:::1", "fd7a:115c:a1e0::1:", "fd7a:115c:a1e0::1%en0", "host.ts.net", "100.64.0.256")) assertFalse(PrivateConnectionRules.isVPNAddress(address), address)
    }
    @Test fun strictClaimRejectsExtraTokensAndWrongProvider() {
        val target = """{"agentId":"codex","hostId":"h","workspaceId":"w","runtimeId":"r"}"""
        val body = """{"version":1,"target":$target,"publicKey":"${"K".repeat(43)}"}"""
        assertNull(CodexDiscoveryContract.validateNamed(body, "privateClaim"))
        assertNotNull(CodexDiscoveryContract.validateNamed(body.dropLast(1) + ",\"token\":\"never-export\"}", "privateClaim"))
        assertNotNull(CodexDiscoveryContract.validateNamed(body.replace("codex", "other"), "privateClaim"))
        assertNotNull(CodexDiscoveryContract.validateNamed(body.replace("\"version\":1", "\"version\":\"1\""), "privateClaim"))
    }
    @Test fun peerDirectoryCannotExceedBoundedInputs() {
        val target = """{"agentId":"codex","hostId":"h","workspaceId":"w","runtimeId":"r"}"""
        val peer = """{"name":"desktop","addresses":["100.64.0.1"]}"""
        fun directory(count: Int) = """{"version":1,"target":$target,"peers":[${List(count) { peer }.joinToString(",") }]}"""
        assertNull(CodexDiscoveryContract.validateNamed(directory(32), "vpnSnapshot"))
        assertNotNull(CodexDiscoveryContract.validateNamed(directory(33), "vpnSnapshot"))
        assertNotNull(CodexDiscoveryContract.validateNamed(directory(1).replace("[\"100.64.0.1\"]", "[\"100.64.0.1\",\"100.64.0.1\"]"), "vpnSnapshot"))
    }
}
