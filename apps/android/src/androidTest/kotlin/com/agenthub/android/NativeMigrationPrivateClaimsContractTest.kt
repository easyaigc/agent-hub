package com.agenthub.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Boundary vectors only. No connection, token, device identity, HTTP or pairing is created. */
class NativeMigrationPrivateClaimsContractTest {
    private fun intent(host: String="host-a",account: String?="historical-account"): String {
        val target=JSONObject().put("agentId","codex").put("hostId",host).put("workspaceId","workspace-a").put("runtimeId","runtime-a")
        if(account!=null)target.put("account",JSONObject().put("authority","example-authority").put("subject",account))
        return "\n "+JSONObject().put("claim",JSONObject().put("version",1).put("target",target).put("publicKey","A".repeat(43)))
            .put("endpoint","https://host.local").put("fingerprint","a".repeat(64)).toString()+" \n"
    }
    private fun name(raw: String)=NativeMigrationPrivateClaims.ACTIVE_PREFIX+NativeMigrationInertArchive.sha(raw.toByteArray(Charsets.UTF_8))
    private fun root(raws: List<String> = emptyList())=NativeMigrationStreamRecords(mapOf("metadata" to "{}")+raws.associateBy(::name),emptyMap(),4)
    private fun portable(raw: String)=NativeMigrationPrivateClaims.projectSource(root(listOf(raw)))
    private fun reject(raw: String)=assertTrue(runCatching {portable(raw)}.isFailure)
    private fun originals(source: NativeMigrationStreamRecords): Map<String,String> {
        val rows=JSONObject(source.text.getValue(NativeMigrationPrivateClaims.RECORD)).getJSONArray("archives")
        val result=linkedMapOf<String,String>()
        for(n in 0 until rows.length()) {
            val row=rows.getJSONObject(n);val entries=row.getJSONArray("entries")
            for(i in 0 until entries.length())result[entries.getJSONObject(i).getString("name")]=source.text.getValue(NativeMigrationPrivateClaims.TEXT+row.getString("id")+":"+i)
        }
        return result
    }
    @Test fun exactPrePostIntentBecomesAuditWithoutActiveNamesOrOutcomeInference() {
        val raw=intent();val source=portable(raw)
        assertEquals(mapOf(name(raw) to raw),originals(source))
        assertEquals("{}",source.text.getValue("metadata"))
        assertFalse(source.text.keys.any {it.startsWith(NativeMigrationPrivateClaims.ACTIVE_PREFIX)})
        assertEquals(2,NativeMigrationPrivateClaims.validatePortable(source.text,source.binary).size)
        assertSame(source,NativeMigrationPrivateClaims.projectSource(source))
    }
    @Test fun accountMetadataDifferencesAndMissingAccountAreBothPreserved() {
        val raws=listOf(intent(account="old"),intent(account="new"),intent(account=null))
        val projected=NativeMigrationPrivateClaims.projectSource(root(raws))
        assertEquals(raws.associateBy(::name),originals(projected))
    }
    @Test fun destinationIntentIsRetainedAndIdenticalOriginalIsDeduplicated() {
        val first=intent();val second=intent("host-b")
        val merged=NativeMigrationPrivateClaims.merge(portable(first),root(listOf(second)))
        assertEquals(mapOf(name(first) to first,name(second) to second),originals(merged))
        assertEquals(originals(merged),originals(NativeMigrationPrivateClaims.merge(merged,root(listOf(first,second)))))
        assertFalse(merged.text.keys.any {it.startsWith(NativeMigrationPrivateClaims.ACTIVE_PREFIX)})
    }
    @Test fun rawActiveNamesNeverPassAnImportedCandidate() {
        val raw=intent()
        assertTrue(runCatching {NativeMigrationPrivateClaims.validatePortable(root(listOf(raw)).text,emptyMap())}.isFailure)
        assertTrue(runCatching {NativeMigrationPrivateClaims.projectSource(root().copy(text=root().text+mapOf(NativeMigrationPrivateClaims.ACTIVE_PREFIX+"b".repeat(64) to raw)))}.isFailure)
        assertTrue(runCatching {NativeMigrationPrivateClaims.projectSource(root().copy(text=root().text+mapOf(NativeMigrationPrivateClaims.ACTIVE_PREFIX+"future" to raw)))}.isFailure)
    }
    @Test fun unknownAndSecretFieldsCannotHideInAnAudit() {
        val raw=intent()
        for(field in listOf("token","privateKey","phase","response","future"))reject(JSONObject(raw).put(field,"unexpected").toString())
        for(field in listOf("token","privateKey","future"))reject(JSONObject(raw).apply {getJSONObject("claim").put(field,"unexpected")}.toString())
        reject(JSONObject(raw).apply {getJSONObject("claim").getJSONObject("target").put("future",true)}.toString())
        reject(JSONObject(raw).apply {getJSONObject("claim").put("version",2)}.toString())
        reject(JSONObject(raw).apply {getJSONObject("claim").put("version","1")}.toString())
        reject(JSONObject(raw).apply {getJSONObject("claim").getJSONObject("target").put("agentId","other-agent")}.toString())
    }
    @Test fun malformedDuplicateFieldsAndNonCanonicalEndpointRejectBeforeProjection() {
        val raw=intent()
        reject(raw.replace("\"claim\":","\"claim\":{},\"claim\":"))
        reject(raw.replace("\"version\":1","\"version\":1,\"version\":1"))
        for(endpoint in listOf("http://host.local","https://example.com","https://host.local/","https://user@host.local","https://host.local?token=x"))
            reject(JSONObject(raw).put("endpoint",endpoint).toString())
        reject(JSONObject(raw).put("fingerprint","A".repeat(64)).toString())
        reject(JSONObject(raw).put("fingerprint",true).toString())
        reject(JSONObject(raw).apply {getJSONObject("claim").put("publicKey","too-short")}.toString())
    }
    @Test fun missingOrChangedAliasesAndForgedCatalogsRejectTheWholeFamily() {
        val source=portable(intent());val alias=source.text.keys.first {it.startsWith(NativeMigrationPrivateClaims.TEXT)}
        fun rejectMap(text: Map<String,String>)=assertTrue(runCatching {NativeMigrationPrivateClaims.validatePortable(text,emptyMap())}.isFailure)
        rejectMap(source.text-alias);rejectMap(source.text+mapOf(alias to intent("host-other")))
        rejectMap(source.text-NativeMigrationPrivateClaims.RECORD)
        rejectMap(source.text+mapOf(NativeMigrationPrivateClaims.TEXT+"a".repeat(64)+":9" to intent()))
        rejectMap(source.text+mapOf(NativeMigrationPrivateClaims.RECORD to JSONObject(source.text.getValue(NativeMigrationPrivateClaims.RECORD)).put("version",2).toString()))
    }
    @Test fun binaryClaimsAndEmptyOriginalsRejectRatherThanDroppingUnknownData() {
        val probe=NativeMigrationProbe();probe.write(byteArrayOf(1))
        val blob=NativeMigrationBlob(1,probe.sha256(),probe.prefix()){it.write(1)}
        assertTrue(runCatching {NativeMigrationPrivateClaims.projectSource(root().copy(binary=mapOf(NativeMigrationPrivateClaims.ACTIVE_PREFIX+"a".repeat(64) to blob)))}.isFailure)
        assertTrue(runCatching {NativeMigrationPrivateClaims.validatePortable(root().text,mapOf(NativeMigrationPrivateClaims.BINARY+"a".repeat(64)+":0" to blob))}.isFailure)
        assertTrue(runCatching {NativeMigrationPrivateClaims.validateOriginal(emptyMap(),emptyMap())}.isFailure)
        val unknown=root().copy(text=root().text+mapOf("unrecognized-orphan" to "{}"))
        assertSame(unknown,NativeMigrationPrivateClaims.projectSource(unknown)) // Root graph still has to reject it.
    }
    @Test fun boundedSourceAndIndividualIntentsRejectWithoutPartialArchive() {
        val full=(1..128).map {intent("host-$it")}
        assertEquals(full.associateBy(::name),originals(NativeMigrationPrivateClaims.projectSource(root(full))))
        assertTrue(runCatching {NativeMigrationPrivateClaims.projectSource(root((1..129).map {intent("host-$it")}))}.isFailure)
        val oversized=JSONObject(intent()).apply {getJSONObject("claim").getJSONObject("target").put("hostId","h".repeat(8192))}.toString()
        reject(oversized)
        val large=(1..128).map {n->intent("host-$n",account="a".repeat(4096))}
        assertTrue(runCatching {NativeMigrationPrivateClaims.projectSource(root(large))}.isFailure)
    }
    @Test fun legacyDiscoveryOnlyUsesAuthenticatedCatalogNames() {
        assertEquals(setOf(NativeMigrationPrivateClaims.RECORD),NativeMigrationPrivateClaims.legacyNames {null})
        val source=portable(intent())
        assertEquals(NativeMigrationPrivateClaims.validatePortable(source.text,source.binary),NativeMigrationPrivateClaims.legacyNames(source.text::get))
        assertTrue(runCatching {NativeMigrationPrivateClaims.legacyNames {"{\"version\":2,\"archives\":[]}"}}.isFailure)
    }
    @Test fun futureOrMisspelledClaimFamiliesCannotDisappearDuringDestinationUnion() {
        for(name in listOf("codex-private-claims-v2","codex-private-claim","migration-private-claims-v2","migration-private-claim-future")) {
            val unknown=root().copy(text=root().text+mapOf(name to "{}"))
            assertTrue(runCatching {NativeMigrationPrivateClaims.projectSource(unknown)}.isFailure)
            assertTrue(runCatching {NativeMigrationPrivateClaims.merge(root(),unknown)}.isFailure)
            assertTrue(runCatching {NativeMigrationPrivateClaims.validatePortable(unknown.text,unknown.binary)}.isFailure)
            assertTrue(name in unknown.text)
        }
    }
}
