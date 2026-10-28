package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Exact encrypted candidates and their graph are checked without any Host/model commands. */
class NativeMigrationReferencesTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var store: NativeMigrationCandidateStore
    private val password="owned migration graph 100% %@"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-references");store=NativeMigrationCandidateStore(ui.activity,owned.storage)}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun body()=NativeMigrationCandidateTest().syntheticPayload()
    private fun records(value: JSONObject)=value.getJSONObject("records")
    private fun rowKey(value: JSONObject)=records(value).keys().asSequence().single{it.startsWith("operations:")}
    private fun sourceKey(value: JSONObject)=records(value).keys().asSequence().single{it.startsWith("submission:")}
    private fun valid(value: JSONObject)=store.validate(value.toString().toByteArray())
    private fun reject(value: JSONObject){assertTrue("Malformed archive graph accepted",runCatching{valid(value)}.isFailure)}
    private fun sha(value: ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it)}
    private fun cipher()=File(owned.storage.child(ui.activity,"migration-candidates").coordinationKey,sha("candidate-v1".toByteArray())+".bin").readBytes()
    private fun stage(value: JSONObject,replacing: NativeMigrationCandidateStore.Receipt?=null)=store.stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(value.toString().toByteArray(),password),password,replacing)
    @Test fun completeGraphPreservesEveryOriginalRecordAndUnknownOperation(){
        val value=body();val result=valid(value)
        for(key in records(value).keys())assertEquals(records(value).getString(key),result[key])
        val original=JSONObject(result.getValue(rowKey(value))).getJSONArray("operations").getJSONObject(0)
        assertEquals("uncertain",original.getString("phase"));assertTrue(original.getBoolean("submissionAttempted"))
    }
    @Test fun missingSubmissionAndOrphanPayloadCannotReplaceEncryptedPredecessor(){
        val value=body();val receipt=stage(value);val before=cipher();val saved=store.read(receipt)
        val missing=body();records(missing).remove(sourceKey(missing))
        val orphan=body();val files=records(orphan);val original=files.keys().asSequence().single{it.startsWith("draft-file:")};val newId=UUID.randomUUID().toString()
        val copy=JSONObject(files.getString(original));copy.getJSONObject("file").put("id",newId);files.put("draft-file:$newId",copy.toString())
        for(invalid in listOf(missing,orphan)){
            assertTrue(runCatching{stage(invalid,receipt)}.exceptionOrNull() is NativeMigrationCandidateStore.InvalidCandidate)
            assertArrayEquals(before,cipher());assertEquals(saved,store.read(receipt))
        }
    }
    @Test fun submissionAttachmentOrderMustMatchOriginalReplyIntent(){
        val value=body();val entries=records(value);val meta=JSONObject(entries.getString("metadata"));val draft=meta.getJSONObject("draftFiles");val target=draft.keys().next()
        val first=draft.getJSONArray(target).getJSONObject(0);val next=JSONObject(first.toString()).put("id",UUID.randomUUID().toString()).put("name","second.txt")
        val originalFile=JSONObject(entries.getString("draft-file:"+first.getString("id")));entries.put("draft-file:"+next.getString("id"),JSONObject(originalFile.toString()).put("file",next).toString())
        draft.getJSONArray(target).put(next);entries.put("metadata",meta.toString())
        val journal=JSONObject(entries.getString(rowKey(value)));val row=journal.getJSONArray("operations").getJSONObject(0);val intent=JSONObject(row.getString("intent"));intent.getJSONArray("attachmentIds").put(next.getString("id"))
        row.put("intent",intent.toString()).put("intentSha256",sha(intent.toString().toByteArray()));entries.put(rowKey(value),journal.toString())
        val source=JSONObject(entries.getString(sourceKey(value)));source.getJSONArray("files").put(next);entries.put(sourceKey(value),source.toString());valid(value)
        source.put("files",JSONArray().put(next).put(first));entries.put(sourceKey(value),source.toString());reject(value)
    }
    @Test fun unknownAndCoercedSourceOrJournalStateCannotBeSilentlyDiscarded(){
        val changes=listOf<Pair<String,(JSONObject)->Unit>>(
            "source" to {it.put("unreviewedFutureState",true)},"source" to {it.put("speed",7)},"source" to {it.put("text",7)},
            "source" to {it.put("photoSources","discarded")},"row" to {it.put("unknownAuthority",true)},
            "row" to {it.put("submissionAttempted","false")},"row" to {it.put("detached",0)},"row" to {it.put("phase","future-phase")},
            "row" to {it.put("sourceVersion",JSONObject.NULL)},"journal" to {it.put("unexpectedState",JSONObject())})
        for((kind,change)in changes){val value=body();val key=if(kind=="source")sourceKey(value)else rowKey(value);val raw=JSONObject(records(value).getString(key));change(if(kind=="row")raw.getJSONArray("operations").getJSONObject(0)else raw);records(value).put(key,raw.toString());reject(value)}
    }
    private fun paired(): JSONObject {
        val value=body();val entries=records(value);val meta=JSONObject(entries.getString("metadata"));val raw=meta.getJSONArray("clients").getJSONObject(0);val original=decodeClient(raw)
        val client=original.copy(endpoint="https://127.0.0.1:1",codexDirect=PairedCodexConnection(original.scope!!,"a".repeat(64),listOf(CodexRoute("https://127.0.0.1:1","lan"))))
        meta.put("clients",JSONArray().put(encodeClient(client)));entries.put("metadata",meta.toString())
        entries.put("codex-private-connections-v1",JSONObject().put("version",1).put("connections",JSONArray().put(JSONObject().put("client",encodeClient(client)).put("token","a".repeat(43)))).toString())
        return value
    }
    @Test fun verifiedCredentialStagesWithExactClientAndCanBeReadByOrdinaryVault(){
        val value=paired();valid(value);val receipt=stage(value);val restored=NativeMigrationCandidateStore(ui.activity,owned.storage).read(receipt)
        val probe=owned.storage.child(ui.activity,"credential-probe");restored.forEach{(name,raw)->probe.write(name,raw)}
        val vault=CodexConnectionVault(probe);val client=vault.clients().single();assertEquals("a".repeat(43),vault.token(client.id));assertEquals("a".repeat(64),client.codexDirect!!.fingerprint)
        assertEquals(records(value).getString("codex-private-connections-v1"),restored.getValue("codex-private-connections-v1"))
    }
    @Test fun changedTargetCertificateRoutesOrUnscopedCredentialsRejectBeforePublication(){
        val changes=listOf<(JSONObject)->Unit>(
            {it.getJSONObject("client").getJSONObject("codexDirect").put("fingerprint","b".repeat(64))},
            {it.getJSONObject("client").getJSONObject("codexDirect").getJSONArray("routes").getJSONObject(0).put("endpoint","https://127.0.0.1:2")},
            {it.getJSONObject("client").put("storageIdentity",MobileStorageIdentity(null,listOf("different-storage-key")).json())},
            {it.getJSONObject("client").put("requiresReadTarget",true)},
            {it.getJSONObject("client").put("id","unscoped-host")},
            {it.put("token",true)}, {it.put("unexpectedCredential",true)})
        for(change in changes){val value=paired();val vault=JSONObject(records(value).getString("codex-private-connections-v1"));change(vault.getJSONArray("connections").getJSONObject(0));records(value).put("codex-private-connections-v1",vault.toString());reject(value)}
        val duplicate=paired();val vault=JSONObject(records(duplicate).getString("codex-private-connections-v1"));vault.getJSONArray("connections").put(vault.getJSONArray("connections").getJSONObject(0));records(duplicate).put("codex-private-connections-v1",vault.toString());reject(duplicate)
    }
}
