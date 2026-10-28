package com.agenthub.android

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal object MigrationActivationFixtures {
    fun payload(): JSONObject {
        val value=NativeMigrationCandidateTest().syntheticPayload();val entries=value.getJSONObject("records");val meta=JSONObject(entries.getString("metadata"));val prior=decodeClient(meta.getJSONArray("clients").getJSONObject(0))
        val client=prior.copy(endpoint="https://127.0.0.1:1",readerId="old-phone-reader",codexDirect=PairedCodexConnection(prior.scope!!,"a".repeat(64),listOf(CodexRoute("https://127.0.0.1:1","lan"))))
        meta.put("clients",JSONArray().put(encodeClient(client))).put("attachmentImportGenerations",JSONObject().put(client.cacheKey("synthetic-thread"),UUID.randomUUID().toString()));entries.put("metadata",meta.toString())
        val name=entries.keys().asSequence().single{it.startsWith("operations:")};val body=JSONObject(entries.getString(name));val row=body.getJSONArray("operations").getJSONObject(0)
        row.put("replyRecovery",NativeReplyRecovery.capture(client,row.getString("intent"))!!.body());entries.put(name,body.toString())
        entries.put("codex-private-connections-v1",JSONObject().put("version",1).put("connections",JSONArray().put(JSONObject().put("client",encodeClient(client)).put("token","a".repeat(43)))).toString())
        return value
    }
    fun stage(context: Context,storage: NativeSessionStorage,value: JSONObject)=NativeMigrationCandidateStore(context,storage).stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(value.toString().toByteArray(),"owned activation password"),"owned activation password")
    fun client(value: JSONObject)=decodeClient(JSONObject(value.getJSONObject("records").getString("metadata")).getJSONArray("clients").getJSONObject(0))
    fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let{root->root.walkTopDown().filter{it.isFile}.associate{it.relativeTo(root).path to sha(it.readBytes())}}
}

/** Actual native import boundary and durable journal gates; network doubles are auxiliary evidence. */
class NativeMigrationActivationTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-activation")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun stage(value: JSONObject)=MigrationActivationFixtures.stage(ui.activity,owned.storage,value)
    private fun activate(value: JSONObject): NativeSessionStorage {NativeMigrationActivation(ui.activity,owned.storage).activate(stage(value));return owned.storage.reopen(ui.activity)}
    private suspend fun row(storage: NativeSessionStorage,value: JSONObject): SavedOperation {val name=value.getJSONObject("records").keys().asSequence().single{it.startsWith("operations:")};return NativeOperationJournal(storage).records(name.removePrefix("operations:")).single()}
    @Test fun activationRestoresOriginalFilesAndCreatesFreshInstallAuthority()=runBlocking {
        val value=MigrationActivationFixtures.payload();val original=value.getJSONObject("records");val beforeClient=MigrationActivationFixtures.client(value)
        owned.storage.write("ai-sharing-v1","original installation consent");owned.storage.child(ui.activity,"photo-permission").write("attempt-images","old OS grant")
        val sourceName=original.keys().asSequence().single{it.startsWith("submission:")};val metadata=JSONObject(original.getString("metadata"));val target=metadata.getJSONObject("draftFiles").keys().next();val manifest=metadata.getJSONObject("draftFiles").getJSONArray(target).getJSONObject(0)
        val fileName="draft-file:"+manifest.getString("id");val file=JSONObject(original.getString(fileName));manifest.put("mediaType","image/png");file.put("file",manifest);original.put(fileName,file.toString())
        val origin=JSONObject().put(manifest.getString("id"),"content://media/external/images/media/42");metadata.put("photoSources",origin);original.put("metadata",metadata.toString());original.put(sourceName,JSONObject(original.getString(sourceName)).put("files",JSONArray().put(manifest)).put("photoSources",origin).toString())
        val current=activate(value);val restored=current.metadata()!!;val client=decodeClient(restored.getJSONArray("clients").getJSONObject(0));assertNotEquals(beforeClient.readerId,client.readerId);assertNotNull(client.readerId)
        assertFalse(restored.has("photoSources"));assertFalse(restored.has("attachmentImportGenerations"));assertNull(current.read("ai-sharing-v1"));assertNull(current.child(ui.activity,"photo-permission").read("attempt-images"))
        assertEquals("a".repeat(43),current.token(client.id));assertEquals(client.readerId,CodexConnectionVault(current).clients().single().readerId)
        assertEquals(original.getString(fileName),current.read(fileName));assertFalse(JSONObject(current.read(sourceName)!!).has("photoSources"))
        val imported=row(current,value);val old=JSONObject(original.getString("operations:"+imported.target)).getJSONArray("operations").getJSONObject(0)
        for(field in listOf("id","intent","intentSha256","result","phase","submissionAttempted","detached","sourceKey","sourceVersion"))assertEquals(old.get(field),imported.body().get(field))
        assertTrue(imported.migrationReadOnly);assertEquals("uncertain",imported.replyRecovery!!.phase);assertNull(imported.replyRecovery.inspection);assertFalse(imported.replyRecovery.consumed)
    }
    @Test fun existingClientAndVaultOnlyPairingRejectWithoutChangingAnyActiveCiphertext(){
        val value=MigrationActivationFixtures.payload();val receipt=stage(value)
        for(vaultOnly in listOf(false,true)) {
            if(vaultOnly) {owned.storage.remove("metadata");owned.storage.write("codex-private-connections-v1",value.getJSONObject("records").getString("codex-private-connections-v1"))}
            else owned.storage.write("metadata",value.getJSONObject("records").getString("metadata"))
            val before=MigrationActivationFixtures.tree(owned.storage)
            assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)}.exceptionOrNull() is NativeMigrationActivation.ExistingInstallation)
            assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
        }
    }
    @Test fun eachPrecommitFailurePreservesCurrentInstallationAndOriginalCandidate(){
        val value=MigrationActivationFixtures.payload();val receipt=stage(value);owned.storage.write("metadata",JSONObject().put("clients",JSONArray()).toString())
        val before=MigrationActivationFixtures.tree(owned.storage);val original=NativeMigrationCandidateStore(ui.activity,owned.storage).read(receipt)
        for(index in 0..receipt.recordCount){assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt){if(it==index)throw java.io.IOException("owned import interrupted")}}.isFailure);assertEquals(before,MigrationActivationFixtures.tree(owned.storage));assertEquals(original,NativeMigrationCandidateStore(ui.activity,owned.storage).read(receipt))}
    }
    @Test fun repeatedActivationReadsOriginalReceiptAndPreservesLaterEdits(){
        val value=MigrationActivationFixtures.payload();val receipt=stage(value);val first=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        current.write("new-edit","independent local draft");val before=MigrationActivationFixtures.tree(current)
        assertEquals(first,NativeMigrationActivation(ui.activity,current).activate(receipt));assertEquals(first,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt))
        assertEquals("independent local draft",current.read("new-edit"));assertEquals(before,MigrationActivationFixtures.tree(current))
        assertTrue(runCatching{NativeMigrationActivation(ui.activity,current).activate(receipt.copy(sha256="0".repeat(64)))}.isFailure);assertEquals(before,MigrationActivationFixtures.tree(current))
        assertTrue(runCatching{owned.storage.write("metadata","old callback")}.isFailure)
    }
    @Test fun importedReplyCanAcceptOriginalReceiptButCannotRetryOrOverwriteItsSnapshot()=runBlocking {
        val value=MigrationActivationFixtures.payload();val current=activate(value);val client=CodexConnectionVault(current).clients().single();val original=row(current,value);val journal=NativeOperationJournal(current)
        assertTrue(original.replyRecovery!!.matchesObservation(client,original));assertFalse(original.replyRecovery.mayRetry(client,original))
        assertTrue(runCatching{journal.consumeReplyRetry(client,original.target,original.id)}.isFailure);assertTrue(runCatching{journal.markSubmitting(original.target,original.id)}.isFailure)
        var reads=0;var writes=0
        val commands=object: CodexCommands by NativeCodexCommands({""}) {
            override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt {writes++;error("must not send")}
            override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt {reads++;return DeliveryReceipt(client.id,threadId,operationId,"completed","original-turn",null,null,null,JSONObject(original.intent).getJSONArray("attachmentIds").strings())}
        }
        val flow=NativeCodexFlow(current,NativeHostReads{""},commands);val snapshot=current.read("submission:"+original.id)
        assertTrue(runCatching{flow.retryReply(client,original,{true},{})}.isFailure)
        assertTrue(runCatching{flow.submit(client,"synthetic-thread",null,SubmissionOrigin(original.sourceKey!!,original.sourceVersion!!),CodexContent("replacement",emptyList(),GenerationSettings()),"next-turn",null,guard={true},operationId=original.id)}.isFailure)
        assertEquals(snapshot,current.read("submission:"+original.id));val result=flow.query(client,original).record
        assertEquals(1,reads);assertEquals(0,writes);assertEquals("completed",result.phase);assertTrue(result.migrationReadOnly);assertEquals(original.intent,result.intent);assertFalse(result.replyRecovery!!.mayRetry(client,result))
        assertTrue(runCatching{journal.observeReplyInspection(client,result.target,result.id,JSONObject())}.isFailure)
    }
    @Test fun importedPreparedCreationCannotUploadSubmitOrNotifyDetach()=runBlocking {
        val value=MigrationActivationFixtures.payload();val entries=value.getJSONObject("records");val client=MigrationActivationFixtures.client(value);val oldName=entries.keys().asSequence().single{it.startsWith("operations:")};val body=JSONObject(entries.getString(oldName));val row=body.getJSONArray("operations").getJSONObject(0);val id=row.getString("id")
        val source=JSONObject(entries.getString("submission:$id"));val files=source.getJSONArray("files").objects().map{CreationFile(it.getString("id"),it.getString("name"),it.getString("mediaType"),it.getLong("sizeBytes"),it.getString("sha256"))}
        val intent=CreationIntent(client.id,id,null,source.getString("text"),files).body().toString();val target=client.cacheKey(null)
        row.put("kind","creation").put("target",target).put("intent",intent).put("intentSha256",MigrationActivationFixtures.sha(intent.toByteArray())).put("phase","prepared").put("submissionAttempted",false).put("sourceKey",target).put("replyRecovery",JSONObject.NULL)
        body.put("target",target);entries.remove(oldName);entries.put("operations:$target",body.toString());source.put("sourceKey",target);entries.put("submission:$id",source.toString())
        val current=activate(value);val imported=NativeOperationJournal(current).records(target).single();var writes=0
        val commands=object: CodexCommands by NativeCodexCommands({""}) {override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation {writes++;error("must not notify original operation")}}
        val flow=NativeCodexFlow(current,NativeHostReads{""},commands);assertFalse(flow.mayContinueCreation(imported));assertTrue(runCatching{flow.continuePrepared(client,imported,{true},{})}.isFailure)
        assertTrue(runCatching{flow.journal.markPreparing(target,id)}.isFailure);assertTrue(runCatching{flow.journal.markCreationRetrySubmitting(target,id)}.isFailure)
        assertTrue(flow.detach(client,imported).detached);assertEquals(0,writes);val restored=flow.journal.records(target).single();assertTrue(restored.migrationReadOnly);assertEquals(intent,restored.intent)
    }
}
