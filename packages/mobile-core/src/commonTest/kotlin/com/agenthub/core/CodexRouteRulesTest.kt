package com.agenthub.core

import kotlin.test.*

class CodexRouteRulesTest {
    @Test fun serveTrustCannotBeAppliedToAnArbitraryOrigin() {
        assertEquals("https://office.tail.ts.net:8443", CodexRouteRules.systemEndpoint("HTTPS://Office.tail.ts.net:08443/"))
        assertEquals("https://office.tail.ts.net", CodexRouteRules.systemEndpoint("https://office.tail.ts.net:443"))
        for (bad in listOf("http://office.tail.ts.net", "https://office.tail.ts.net.evil.com", "https://user@office.tail.ts.net", "https://office.tail.ts.net/?x=1", "https://office.tail.ts.net/path", "https://office.tail.ts.net:0", "https://office.tail.ts.net:65536", "https://192.168.1.2:8796", "https://office.local:8796"))
            assertNull(CodexRouteRules.systemEndpoint(bad), bad)
    }
    @Test fun legacyAndNewServeRoutesSurviveBothDirectionChangesAndReopening() {
        val legacy="https://office.tail.ts.net:8443"
        val initial=CodexRouteRules.mergeSystemEndpoints(emptyList(), emptyList(), legacy)
        val switchedToLan=CodexRouteRules.mergeSystemEndpoints(initial, listOf("https://office.tail.ts.net"), "https://192.168.1.2:8796")
        val reopened=CodexRouteRules.mergeSystemEndpoints(switchedToLan, emptyList(), "https://192.168.1.2:8796")
        assertEquals(listOf(legacy,"https://office.tail.ts.net"),reopened)
        assertEquals(reopened,CodexRouteRules.mergeSystemEndpoints(reopened,listOf(legacy),legacy))
    }
    @Test fun separateContractIsStrictWithoutChangingOldHostPayloads() {
        val target="""{"agentId":"codex","hostId":"h","workspaceId":"w","runtimeId":"r"}"""
        val wire="""{"version":1,"target":$target,"httpsEndpoints":["https://office.tail.ts.net"]}"""
        assertNull(CodexDiscoveryContract.validateNamed(wire,"connectionRoutes"))
        assertNotNull(CodexDiscoveryContract.validateNamed(wire.dropLast(1)+",\"token\":\"secret\"}","connectionRoutes"))
        assertNotNull(CodexDiscoveryContract.validateNamed(wire.replace("[\"https://office.tail.ts.net\"]","[\"a\",\"a\"]"),"connectionRoutes"))
    }
}
