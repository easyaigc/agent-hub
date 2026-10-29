package com.agenthub.android

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Actual local P8 credential/journal/history writers with public/synthetic data only. */
internal object NativeMigrationEasyWorkBusinessFixtures {
    const val THREAD="synthetic-thread"
    class Seed(val storage: NativeSessionStorage,val client: MobileClient,val authority: NativeMigrationEasyWorkFixtures.Seed,
        val reply: SavedOperation,val completed: SavedOperation,val creation: SavedOperation,val open: SavedOperation,val fileName: String)
    fun seed(context: Context,storage: NativeSessionStorage): Seed=runBlocking {
        val authority=NativeMigrationEasyWorkFixtures.seed(context,storage,"active")
        val credential=EasyWorkCredential.decode(JSONObject(requireNotNull(storage.read(authority.credentialName))))
        val target=credential.binding.target.scope();val id="easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(target)).toByteArray())
        val connection=AgentConnection(target,listOf(AgentCandidate("synthetic-lan","lan-wss","https://127.0.0.1:1","a".repeat(64),"lan"),AgentCandidate("synthetic-relay","relay-http","https://relay.example.invalid")),authority.credentialName.removePrefix(NativeMigrationEasyWorkOriginals.CREDENTIAL),"lan-only")
        val client=MobileClient(id,"Synthetic imported EasyWork",target,connection.candidates.first().endpoint,false,"easywork-runtime",false,readerId="original-phone-reader",requiresReadTarget=true,agentConnection=connection)
        val rows=NativeMigrationCandidateTest().syntheticPayload().getJSONObject("records");val old=JSONObject(rows.getString("metadata"));val oldKey=old.getJSONObject("drafts").keys().next();val key=client.cacheKey(THREAD)
        val manifest=old.getJSONObject("draftFiles").getJSONArray(oldKey).getJSONObject(0);val fileName="draft-file:"+manifest.getString("id")
        storage.write(fileName,rows.getString(fileName))
        val metadata=JSONObject().put("version",1).put("appearance","dark").put("language","en").put("demo",false).put("selected",id)
            .put("clients",JSONArray().put(encodeClient(client))).put("drafts",JSONObject().put(key,"later editable draft, never the original submission"))
            .put("draftFiles",JSONObject().put(key,JSONArray().put(manifest))).put("draftVersions",JSONObject().put(key,"later-version"))
            .put("selectedThreads",JSONObject().put(client.cacheKey(null),THREAD)).put("expanded",JSONObject()).put("workspaces",JSONObject())
        val thread=MobileThread(THREAD,id,"Original cached chat","idle",null)
        val workspace=MobileSnapshotCodec.header(client).put("name",client.name).put("connectionMode","easywork-runtime").put("projects",JSONArray())
            .put("threads",JSONArray().put(encodeThread(thread))).put("capabilities",JSONArray(listOf("commands:target-bound:v1","events","models:read"))).put("runtimeConnected",true)
        metadata.getJSONObject("workspaces").put(client.cacheKey(null),workspace)
        storage.write("metadata",metadata.toString())
        val file=CreationFile(manifest.getString("id"),manifest.getString("name"),manifest.getString("mediaType"),manifest.getLong("sizeBytes"),manifest.getString("sha256"))
        val journal=NativeOperationJournal(storage);val intent=ReplyIntent(id,THREAD,UUID.randomUUID().toString()," original submitted body ",listOf(file.id),"next-turn").body()
        var reply=journal.prepare(client,THREAD,"reply",intent,key,"original-version");reply=journal.markSubmitting(reply.target,reply.id);reply=journal.uncertain(reply.target,reply.id,"original result unknown")
        storage.write("submission:"+reply.id,JSONObject().put("hostId",id).put("target",CodexCommandCodec.target(target)).put("sourceKey",key)
            .put("sourceVersion","original-version").put("text"," original submitted body ").put("files",JSONArray().put(manifest)).toString())
        val completedText=" original completed body "
        val completedIntent=ReplyIntent(id,THREAD,UUID.randomUUID().toString(),completedText,listOf(file.id),"next-turn").body()
        var completed=journal.prepare(client,THREAD,"reply",completedIntent,key,"completed-original-version")
        completed=journal.markSubmitting(completed.target,completed.id)
        val completedReceipt=DeliveryReceipt(id,THREAD,completed.id,"completed","synthetic-original-turn",null,null," original completed receipt ",listOf(file.id))
        completed=journal.observed(completed.target,completed.id,"completed",encodeReceipt(completedReceipt))
        storage.write("submission:"+completed.id,JSONObject().put("hostId",id).put("target",CodexCommandCodec.target(target)).put("sourceKey",key)
            .put("sourceVersion","completed-original-version").put("text",completedText).put("files",JSONArray().put(manifest)).toString())
        val creation=journal.prepare(client,null,"creation",CreationIntent(id,UUID.randomUUID().toString(),null," original unsubmitted creation ",emptyList()).body())
        val openIntent=JSONObject().put("hostId",id).put("threadId",THREAD).put("clientOperationId",UUID.randomUUID().toString())
        var open=journal.prepare(client,THREAD,"open",openIntent);open=journal.markSubmitting(open.target,open.id);open=journal.uncertain(open.target,open.id,"original opening result unknown")
        val messages=listOf(MobileMessage("original-message","assistant","Original cached body",status="complete"))
        val history=MobileSnapshotCodec.history(MobileSnapshotCodec.header(client).put("threadId",THREAD).put("messages",JSONArray(messages.map(::encodeMessage))).put("nextCursor",JSONObject.NULL).toString())
        NativeHistoryCache(storage).save(key,client,THREAD,history,messages)
        val ledger=NativeReadingLedger(storage);ledger.reconcile(listOf(target),listOf(NativeReadingSnapshot(target,THREAD,ThreadReadPosition(7,"original-message",ThreadReadPosition.contentVersion("original-message","Original cached body","complete")),true)))
        Seed(storage,client,authority,reply,completed,creation,open,fileName)
    }
    fun operationRows(seed: Seed)=runBlocking {NativeOperationJournal(seed.storage).let {journal->
        (journal.records(seed.client.cacheKey(THREAD))+journal.records(seed.client.cacheKey(null))).associate {it.id to it}
    }}
}
