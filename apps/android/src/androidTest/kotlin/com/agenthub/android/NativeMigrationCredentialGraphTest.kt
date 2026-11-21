package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Complete production graph entries, not the helper/DTO harness. No storage, Session or transport. */
class NativeMigrationCredentialGraphTest {
    private val applicationId get()=InstrumentationRegistry.getInstrumentation().targetContext.packageName
    private val secret="synthetic-legacy-credential-100%-raw"
    private fun metadata(text: Map<String,String>)=JSONObject(text.getValue("metadata"))
    private fun clientId(text: Map<String,String>)=metadata(text).getJSONArray("clients").getJSONObject(0).getString("id")
    private fun tokenName(text: Map<String,String>)="token:${clientId(text)}"
    private fun claim(text: Map<String,String>): String {
        val target=metadata(text).getJSONArray("clients").getJSONObject(0).getJSONObject("scope")
        return "\n "+JSONObject().put("claim",JSONObject().put("version",1).put("target",target).put("publicKey","A".repeat(43)))
            .put("endpoint","https://127.0.0.1:1").put("fingerprint","a".repeat(64)).toString()+" \n"
    }
    private fun fixture(): Map<String,String> {
        // This existing fixture only builds values; its storage @Before/@After are never invoked.
        val values=NativeMigrationCandidateTest().syntheticPayload().getJSONObject("records")
        val text=values.keys().asSequence().associateWith {values.getString(it)}.toMutableMap()
        text[tokenName(text)]=secret
        val original=claim(text)
        text[NativeMigrationPrivateClaims.ACTIVE_PREFIX+NativeMigrationInertArchive.sha(original.toByteArray(Charsets.UTF_8))]=original
        return NativeMigrationPrivateClaims.projectSource(NativeMigrationStreamRecords(text,emptyMap(),4)).text.toMap()
    }
    private fun envelope(text: Map<String,String>,binary: Map<String,String> = emptyMap())=JSONObject()
        .put("format","agent-hub/android-migration").put("version",if(binary.isEmpty())1 else 2)
        .put("applicationId",applicationId).put("records",JSONObject(text))
        .also {if(binary.isNotEmpty())it.put("binaries",JSONObject(binary))}.toString().toByteArray(Charsets.UTF_8)
    private fun blob()=NativeMigrationTextFrames.retained("synthetic-binary-token")
    private fun accept(text: Map<String,String>) {
        val before=text.toMap()
        val legacy=NativeMigrationArchiveValidation.archive(envelope(text),applicationId)
        assertEquals(before,legacy.text)
        for(version in listOf(3,4))NativeMigrationArchiveValidation.stream(NativeMigrationStreamRecords(text,emptyMap(),version))
        assertEquals(before,text)
    }
    private fun reject(text: Map<String,String>) {
        val before=text.toMap()
        assertTrue("v1 graph must reject",runCatching {NativeMigrationArchiveValidation.archive(envelope(text),applicationId)}.isFailure)
        for(version in listOf(3,4))assertTrue("v$version graph must reject",runCatching {
            NativeMigrationArchiveValidation.stream(NativeMigrationStreamRecords(text,emptyMap(),version))
        }.isFailure)
        assertEquals(before,text)
    }
    @Test fun nonemptySessionOperationsAndInertClaimsKeepOpaqueAndJsonShapedTokens() {
        val text=fixture()
        assertTrue(text.keys.any {it.startsWith("operations:")})
        assertTrue(text.keys.any {it.startsWith("submission:")})
        assertTrue(NativeMigrationPrivateClaims.RECORD in text)
        assertEquals(mapOf(NativeMigrationPrivateClaims.ACTIVE_PREFIX+NativeMigrationInertArchive.sha(claim(text).toByteArray(Charsets.UTF_8)) to claim(text)),
            NativeMigrationPrivateClaims.readOnly(NativeMigrationStreamRecords(text,emptyMap(),4)))
        for(value in listOf(secret,"7".repeat(43),"\""+"x".repeat(40)+"\"","x".repeat(512)))accept(text+mapOf(tokenName(text) to value))
    }
    @Test fun unknownTokenKeysAndMalformedSecretsCannotLeaveTheClosedGraph() {
        val text=fixture();accept(text)
        for(name in listOf("token:","token:unknown-host",tokenName(text)+":future"))reject(text+mapOf(name to secret))
        for(value in listOf("","x".repeat(31),"x".repeat(513),"x".repeat(42)+"\n"))reject(text+mapOf(tokenName(text) to value))
    }
    @Test fun tokenExclusionDoesNotAdmitUnclaimedObjectsArraysScalarsOrFutureNamespaces() {
        val text=fixture();accept(text)
        for((name,raw) in listOf("unclaimed-object" to "{}","unclaimed-array" to "[]","unclaimed-scalar" to "false",
            "tokens:future" to "{}","token-v2:future" to "{}"))reject(text+mapOf(name to raw))
    }
    @Test fun binaryOnlyTokenMustRejectAtCompleteBinaryInventory() {
        val text=fixture();accept(text)
        for(name in listOf(tokenName(text),"token:unknown-host")) {
            val without=text-name
            val encoded=Base64.getEncoder().encodeToString("synthetic-binary-token".toByteArray(Charsets.UTF_8))
            assertTrue(runCatching {NativeMigrationArchiveValidation.archive(envelope(without,mapOf(name to encoded)),applicationId)}.isFailure)
            for(version in listOf(3,4))assertTrue(runCatching {
                NativeMigrationArchiveValidation.stream(NativeMigrationStreamRecords(without,mapOf(name to blob()),version))
            }.isFailure)
        }
    }
    @Test fun duplicateTextAndBinaryTokenRejectBeforeAnyGraphConsumption() {
        val text=fixture();accept(text);val name=tokenName(text)
        val encoded=Base64.getEncoder().encodeToString("synthetic-binary-token".toByteArray(Charsets.UTF_8))
        // These intentionally hit the records constructors' namespace-disjointness gate.
        assertTrue(runCatching {NativeMigrationArchiveValidation.archive(envelope(text,mapOf(name to encoded)),applicationId)}.isFailure)
        for(version in listOf(3,4))assertTrue(runCatching {NativeMigrationStreamRecords(text,mapOf(name to blob()),version)}.isFailure)
    }
    @Test fun invalidProviderCannotBorrowCodexHostIdAndRawCredential() {
        val text=fixture();accept(text)
        for(provider in listOf("easywork","unknown-provider")) {
            val meta=metadata(text);meta.getJSONArray("clients").getJSONObject(0).getJSONObject("scope").put("agentId",provider)
            // This is the full entry's metadata gate, not a valid projected EasyWork fixture.
            reject(text+mapOf("metadata" to meta.toString()))
        }
    }
    @Test fun unchangedCredentialCannotAuthorizeOperationsFromOldWorkspaceOrRuntime() {
        val text=fixture();accept(text)
        for(field in listOf("workspaceId","runtimeId")) {
            val meta=metadata(text);meta.getJSONArray("clients").getJSONObject(0).getJSONObject("scope").put(field,"different-"+field)
            NativeMigrationMetadata.validate(meta) // The changed metadata is valid by itself.
            reject(text+mapOf("metadata" to meta.toString()))
        }
        val source=text.keys.single {it.startsWith("submission:")}
        val body=JSONObject(text.getValue(source));body.getJSONObject("target").put("runtimeId","different-runtimeId")
        reject(text+mapOf(source to body.toString()))
    }
}
