package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileEasyWorkAuthenticatedCandidatesTest {
    private val target=EasyWorkTarget("https://issuer.example","subject","desktop","workspace","runtime")
    private val pin="a".repeat(64)
    private val old=EasyWorkPairing.directCandidates(listOf("https://192.168.2.2:44319"),fingerprint=pin).single()
    private val relay=AgentCandidate("relay","relay-http","https://relay.example")
    private val original=AgentConnection(target.scope(),listOf(old,relay),"original-credential","relay-only")
    private fun route(endpoint:String="https://192.168.2.3:44319",fingerprint:String=pin)=
        JSONObject().put("transportId","lan-wss").put("endpoint",endpoint).put("peerFingerprint",fingerprint)
    private fun context(vararg routes:JSONObject)=JSONObject().put("target",target.json()).put("connectionCandidates",JSONArray(routes.toList()))

    @Test fun newAuthenticatedAddressKeepsOriginalIdentityCredentialPreferenceRelayAndPin() {
        val result=checkNotNull(EasyWorkAuthenticatedCandidates.read(context(route()),target,original))
        assertEquals(original,result.copy(candidates=original.candidates))
        assertEquals(listOf("https://192.168.2.3:44319",old.endpoint,relay.endpoint),result.candidates.map { it.endpoint })
        assertEquals(old,result.candidates[1]);assertEquals(relay,result.candidates[2])
        assertEquals(listOf(old,relay),original.candidates)
    }
    @Test fun absentOrMalformedOptionalMetadataKeepsExistingConnection() {
        assertNull(EasyWorkAuthenticatedCandidates.read(JSONObject().put("target",target.json()),target,original))
        assertNull(EasyWorkAuthenticatedCandidates.read(context().put("connectionCandidates","invalid"),target,original))
        assertNull(EasyWorkAuthenticatedCandidates.read(context(route(fingerprint="bad")),target,original))
        assertNull(EasyWorkAuthenticatedCandidates.read(context(route("https://user:password@192.168.2.3:44319")),target,original))
    }
    @Test fun optionalRoutesCannotReplaceAnExistingPinOrDisagreeWithEachOther() {
        assertNull(EasyWorkAuthenticatedCandidates.read(context(route(old.endpoint,"b".repeat(64))),target,original))
        assertNull(EasyWorkAuthenticatedCandidates.read(context(route(),route(fingerprint="b".repeat(64))),target,original))
    }
    @Test fun anotherAuthenticatedTargetCannotContributeRoutes() {
        val wrong=context(route()).put("target",target.copy(workspaceId="another-workspace").json())
        assertTrue(runCatching { EasyWorkAuthenticatedCandidates.read(wrong,target,original) }.isFailure)
        assertTrue(runCatching { EasyWorkAuthenticatedCandidates.read(context(route()),target,
            original.copy(target=target.copy(subject="another-subject").scope())) }.isFailure)
    }
    @Test fun futureTransportDoesNotInvalidateIndependentlyUsableLanAddress() {
        val future=JSONObject().put("transportId","future-transport")
        val result=checkNotNull(EasyWorkAuthenticatedCandidates.read(context(future,route(),route()),target,original))
        assertEquals(3,result.candidates.size);assertEquals(1,result.candidates.count { it.endpoint=="https://192.168.2.3:44319" })
    }
    @Test fun boundedGrowthNeverDropsExistingRelayRoutes() {
        val relays=(1..31).map { relay.copy(id="relay-$it",endpoint="https://relay-$it.example") }
        val full=original.copy(candidates=listOf(old)+relays)
        val result=checkNotNull(EasyWorkAuthenticatedCandidates.read(context(route()),target,full))
        assertEquals(32,result.candidates.size);assertEquals(relays,result.candidates.drop(1))
        assertEquals("https://192.168.2.3:44319",result.candidates.first().endpoint)
        assertNull(EasyWorkAuthenticatedCandidates.read(context().put("connectionCandidates",JSONArray((1..33).map { route() })),target,original))
    }
}
