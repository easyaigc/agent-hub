package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure graph/projection rules and production restore port. No storage/profile, Activity,
 * migration execution, file/binary access, network, credential or model input is used.
 */
@RunWith(AndroidJUnit4::class)
class NativeUploadMigrationRulesTest {
    private val client=MobileClient("synthetic-host","Synthetic",
        TargetScope("codex",null,null,"synthetic-host","synthetic-workspace","desktop-runtime"),"https://synthetic.invalid",isDemo=false)
    private fun row(index:Long=1):NativeUploadRow {
        val file=ComputerFileManifest(client.id,java.util.UUID(0,index).toString(),"sample.bin","application/octet-stream",6,
            ComputerFileCodec.hash("ABCDEF".toByteArray()))
        val m=NativeUploadManifest(file,"file-transfer");val t=NativeUploadTarget.capture(client)
        return NativeUploadRow(NativeUploadRow.key(t,m),t,m,null,phase="waiting",pending="chunk",
            capability=NativeUploadCapability(2,true,"codex-upload-v1"))
    }
    private fun raw()=" \n"+NativeUploadJournalCodec.encode(listOf(row()))+"\n "
    private fun rejects(action:()->Unit) {assertTrue(runCatching(action).isFailure)}
    private fun easyWorkClient():MobileClient {
        val scope=EasyWorkTarget("https://synthetic.invalid","synthetic-subject","synthetic-desktop-id","synthetic-workspace","local").scope()
        return MobileClient("synthetic-local-client-id","Synthetic EW",scope,"https://synthetic.invalid",isDemo=false,
            agentConnection=AgentConnection(scope,listOf(AgentCandidate("synthetic-lan","https","https://synthetic.invalid")),"synthetic-credential-reference"))
    }
    @Test fun easyWorkLocalClientIdMayDifferFromDesktopIdAndAuditPreservesBoth() {
        val ew=easyWorkClient();val target=NativeUploadTarget.capture(ew)
        assertNotEquals(ew.id,target.scope.hostId);assertEquals(ew.id,target.clientId);assertTrue(target.matches(ew))
        val m=row().manifest.copy(file=row().manifest.file.copy(hostId=ew.id),purpose="attachment",threadId="synthetic-thread")
        val saved=NativeUploadRow(NativeUploadRow.key(target,m),target,m,m.id,phase="waiting",pending="chunk",
            capability=NativeUploadCapability(192*1024,false,"easywork-attachment-v1"))
        val journal=NativeUploadJournalCodec.encode(listOf(saved))
        val decoded=NativeUploadJournalCodec.decode(journal).single()
        assertEquals(ew.id,decoded.manifest.file.hostId);assertEquals(target.scope.hostId,decoded.target.scope.hostId)
        assertTrue(decoded.target.matches(ew))
        val portable=NativeMigrationUploadArchives.projectSource(mapOf(NativeUploadJournalCodec.RECORD to journal))
        val audit=JSONObject(portable.getValue(NativeMigrationUploadArchives.RECORD)).getJSONArray("entries").getJSONObject(0)
        assertEquals(journal,audit.getString("journal"));NativeMigrationUploadArchives.validatePortable(portable)
        rejects {target.computerFileTarget()} // F59 remains Codex-only.
        val unbound=ew.copy(agentConnection=null)
        assertFalse(target.matches(unbound));rejects {NativeUploadTarget.capture(unbound)}
        val wrong=ew.copy(agentConnection=ew.agentConnection!!.copy(target=TargetScope("easywork",ew.scope!!.accountAuthority,ew.scope!!.accountSubject,"different-desktop",ew.scope!!.workspaceId,ew.scope!!.runtimeId)))
        assertFalse(target.matches(wrong));rejects {NativeUploadTarget.capture(wrong)}
        val badManifest=saved.json().apply {getJSONObject("manifest").put("hostId",target.scope.hostId)}
        rejects {NativeUploadJournalCodec.decode(JSONObject().put("version",1).put("jobs",JSONArray().put(badManifest)).toString())}
    }
    @Test fun codexAccountMetadataChangesNeitherMachineKeyNorJournalAdmission() {
        val old=client.copy(scope=TargetScope("codex","synthetic-old-authority","synthetic-old-subject",client.id,client.scope!!.workspaceId,client.scope!!.runtimeId))
        val changed=client.copy(scope=TargetScope("codex","synthetic-new-authority","synthetic-new-subject",client.id,client.scope!!.workspaceId,client.scope!!.runtimeId))
        val a=NativeUploadTarget.capture(old);val b=NativeUploadTarget.capture(changed)
        assertEquals(a.key,b.key);assertTrue(a.matches(changed));assertTrue(NativeUploadTarget.decode(a.json()).matches(changed))
        val original=row();val saved=original.copy(target=a,key=NativeUploadRow.key(a,original.manifest))
        val journal=NativeUploadJournalCodec.encode(listOf(saved))
        assertTrue(NativeUploadJournalCodec.decode(journal).single().target.matches(changed))
        val portable=NativeMigrationUploadArchives.projectSource(mapOf(NativeUploadJournalCodec.RECORD to journal))
        assertEquals(journal,JSONObject(portable.getValue(NativeMigrationUploadArchives.RECORD)).getJSONArray("entries").getJSONObject(0).getString("journal"))
        rejects {NativeUploadTarget("different-local-id",a.scope,null)} // Codex still requires local == machine host.
    }
    @Test fun forgedTargetTypesScopeExtrasAndMismatchedFullTargetsStillReject() {
        val target=NativeUploadTarget.capture(client)
        rejects {NativeUploadTarget.decode(target.json().put("auth","synthetic-placeholder"))}
        rejects {NativeUploadTarget.decode(target.json().put("clientId",17))}
        rejects {NativeUploadTarget.decode(target.json().put("certificate",17))}
        rejects {NativeUploadTarget.decode(target.json().apply {remove("clientId")})}
        rejects {NativeUploadTarget.decode(target.json().apply {getJSONObject("scope").put("unexpected",true)})}
        rejects {NativeUploadTarget.decode(target.json().apply {getJSONObject("scope").remove("runtimeId")})}
        val changed=client.copy(scope=TargetScope("codex",client.scope!!.accountAuthority,client.scope!!.accountSubject,client.id,"different-workspace",client.scope!!.runtimeId))
        assertFalse(target.matches(changed))
        val wrong=row().json().apply {getJSONObject("target").getJSONObject("scope").put("workspaceId","different-workspace")}
        rejects {NativeUploadJournalCodec.decode(JSONObject().put("version",1).put("jobs",JSONArray().put(wrong)).toString())}
        val legacyShape=row().json().apply {getJSONObject("target").remove("clientId")}
        rejects {NativeUploadJournalCodec.decode(JSONObject().put("version",1).put("jobs",JSONArray().put(legacyShape)).toString())}
    }
    @Test fun originalManifestOrSourceChangeRejectsBeforeAnyCapabilityOrDispatch()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            var calls=0;val saved=row();val original=NativeUploadJournalCodec.encode(listOf(saved))
            val journal=object:NativeUploadJournal {
                override fun read()=original
                override fun write(value:String)=error("Unexpected write")
                override fun requireNetworkOwner() {}
            }
            val commands=object:AgentCommands by NativeCodexCommands({error("Unexpected credential lookup")}) {
                override suspend fun uploadCapability(client:MobileClient,manifest:NativeUploadManifest):NativeUploadCapability? {calls++;error("Unexpected capability request")}
                override suspend fun uploadRequest(client:MobileClient,manifest:NativeUploadManifest,action:NativeUploadAction,offset:Long,bytes:ByteArray?):NativeUploadReceipt {calls++;error("Unexpected request")}
            }
            val center=NativeUploadCenter(journal,scope,{client},{ {true} },{_,_->error("Unexpected source")},{commands})
            try {
                center.restore()
                val changed=saved.manifest.copy(file=saved.manifest.file.copy(sha256=ComputerFileCodec.hash("UVWXYZ".toByteArray())))
                assertTrue(runCatching {center.transfer(client,changed,null,{_,_->error("Unexpected source")},commands){error("Unexpected legacy")}}.isFailure)
                assertTrue(runCatching {center.transfer(client,saved.manifest,"different-source",{_,_->error("Unexpected source")},commands){error("Unexpected legacy")}}.isFailure)
                assertEquals(0,calls)
            } finally {scope.coroutineContext[Job]!!.cancelAndJoin()}
        }}
    }
    @Test fun projectionKeepsExactOriginalAndDigestWithoutTouchingSourceOrFollowingReferences() {
        val original=raw();val source=linkedMapOf(NativeUploadJournalCodec.RECORD to original,"unrelated-record" to "untouched")
        val before=source.toMap();val projected=NativeMigrationUploadArchives.projectSource(source)
        assertEquals(before,source);assertFalse(NativeUploadJournalCodec.RECORD in projected)
        assertEquals("untouched",projected["unrelated-record"])
        val entry=JSONObject(projected.getValue(NativeMigrationUploadArchives.RECORD)).getJSONArray("entries").getJSONObject(0)
        assertEquals(original,entry.getString("journal"))
        assertEquals(ComputerFileCodec.hash(original.toByteArray()),entry.getString("sha256"))
        assertEquals(setOf(NativeMigrationUploadArchives.RECORD),NativeMigrationUploadArchives.validatePortable(projected))
        // No source descriptor is turned into another claimed alias or binary ownership.
        assertEquals(setOf("unrelated-record",NativeMigrationUploadArchives.RECORD),projected.keys)
    }
    @Test fun repeatedExportAndExistingAuditAreIdempotentAndImportMergeStaysInert() {
        val original=raw();val first=NativeMigrationUploadArchives.projectSource(mapOf(NativeUploadJournalCodec.RECORD to original))
        val bytes=first.getValue(NativeMigrationUploadArchives.RECORD)
        val repeated=NativeMigrationUploadArchives.projectSource(first+mapOf(NativeUploadJournalCodec.RECORD to original))
        assertEquals(bytes,repeated.getValue(NativeMigrationUploadArchives.RECORD))
        assertEquals(first,NativeMigrationUploadArchives.projectSource(first))
        val secondRaw=original+" " // Equal semantics does not replace a distinct original text/SHA.
        val local=mapOf(NativeUploadJournalCodec.RECORD to secondRaw)
        val merged=NativeMigrationUploadArchives.mergeDestination(first,local)
        val entries=JSONObject(merged.getValue(NativeMigrationUploadArchives.RECORD)).getJSONArray("entries").objects()
        assertEquals(setOf(original,secondRaw),entries.map {it.getString("journal")}.toSet())
        assertFalse(NativeUploadJournalCodec.RECORD in merged);assertEquals(secondRaw,local[NativeUploadJournalCodec.RECORD])
        assertEquals(merged,NativeMigrationUploadArchives.mergeDestination(merged,local))
    }
    @Test fun sharedDecoderRejectsBadRowsDuplicateKeysAndOriginalByteOrJobOverrun() {
        val r=row().json();r.put("key","0".repeat(64))
        rejects {NativeUploadJournalCodec.decode(JSONObject().put("version",1).put("jobs",JSONArray().put(r)).toString())}
        val r2=row().json()
        rejects {NativeUploadJournalCodec.decode(JSONObject().put("version",1).put("jobs",JSONArray().put(r2).put(r2)).toString())}
        rejects {NativeUploadJournalCodec.decode("{\"version\":1,\"version\":1,\"jobs\":[]}")}
        val tooMany=JSONArray((1L..33L).map {row(it).json()})
        rejects {NativeUploadJournalCodec.decode(JSONObject().put("version",1).put("jobs",tooMany).toString())}
        val large="{\"version\":1,\"jobs\":[]}"+" ".repeat(NativeUploadJournalCodec.MAX_BYTES)
        rejects {NativeUploadJournalCodec.decode(large)}
    }
    @Test fun portableRejectsLiveAuthorityForgedDigestUnknownFlagsAndEmbeddedAuth() {
        val original=raw();val portable=NativeMigrationUploadArchives.projectSource(mapOf(NativeUploadJournalCodec.RECORD to original))
        rejects {NativeMigrationUploadArchives.validatePortable(portable+mapOf(NativeUploadJournalCodec.RECORD to original))}
        fun audit()=JSONObject(portable.getValue(NativeMigrationUploadArchives.RECORD))
        val badDigest=audit().apply {getJSONArray("entries").getJSONObject(0).put("sha256","0".repeat(64))}
        rejects {NativeMigrationUploadArchives.validatePortable(mapOf(NativeMigrationUploadArchives.RECORD to badDigest.toString()))}
        val liveFlag=audit().put("resumeOnImport",true)
        rejects {NativeMigrationUploadArchives.validatePortable(mapOf(NativeMigrationUploadArchives.RECORD to liveFlag.toString()))}
        val dup=audit().apply {val rows=getJSONArray("entries");rows.put(rows.getJSONObject(0))}
        rejects {NativeMigrationUploadArchives.validatePortable(mapOf(NativeMigrationUploadArchives.RECORD to dup.toString()))}
        val liveRow=row().json().put("auth",JSONObject().put("token","synthetic-placeholder"))
        val live=JSONObject().put("version",1).put("jobs",JSONArray().put(liveRow)).toString()
        rejects {NativeMigrationUploadArchives.projectSource(mapOf(NativeUploadJournalCodec.RECORD to live))}
        // Legacy creation result's old wire schema allowed extra properties. The shared journal
        // decoder now rejects opaque auth/execution fields instead of exporting or stripping them.
        val originalRow=row();val m=originalRow.manifest.copy(purpose="creation-attachment",creationId="synthetic-creation",attachmentId=originalRow.manifest.id)
        val result=JSONObject().put("hostId",client.id).put("clientOperationId",m.creationId).put("id",m.attachmentId)
            .put("sizeBytes",m.file.sizeBytes).put("sha256",m.file.sha256).put("auth","synthetic-placeholder")
        val unsafe=originalRow.copy(key=NativeUploadRow.key(originalRow.target,m),manifest=m,phase="completed",pending=null,
            capability=null,legacy=true,legacyResultWire=result.toString())
        rejects {NativeUploadJournalCodec.decode(JSONObject().put("version",1).put("jobs",JSONArray().put(unsafe.json())).toString())}
    }
    @Test fun completedCodexAttachmentResourceAliasSurvivesRestoreAndInertMigration() {
        val original=row()
        val manifest=original.manifest.copy(purpose="attachment",threadId="synthetic-thread")
        val result=manifest.file.json().put("threadId",manifest.threadId).put("resourceId",manifest.id)
        val receipt=NativeUploadReceipt.decode(JSONObject().put("version",1).put("manifest",manifest.json())
            .put("phase","completed").put("committedBytes",manifest.file.sizeBytes).put("maxChunkBytes",2)
            .put("createdAt","2026-01-01T00:00:00Z").put("updatedAt","2026-01-01T00:00:01Z")
            .put("publicationStarted",true).put("result",result),manifest)
        val completed=original.copy(key=NativeUploadRow.key(original.target,manifest),manifest=manifest,
            phase="completed",pending=null,receiptWire=receipt.wire)
        val encoded=NativeUploadJournalCodec.encode(listOf(completed))
        val restored=NativeUploadJournalCodec.decode(encoded).single()
        assertTrue(restored.terminal)
        assertEquals(receipt.wire,restored.receiptWire)
        assertEquals(manifest.id,restored.receipt!!.result!!.getString("resourceId"))
        val portable=NativeMigrationUploadArchives.projectSource(mapOf(NativeUploadJournalCodec.RECORD to encoded))
        NativeMigrationUploadArchives.validatePortable(portable)
        assertEquals(encoded,JSONObject(portable.getValue(NativeMigrationUploadArchives.RECORD))
            .getJSONArray("entries").getJSONObject(0).getString("journal"))
        val legacy=completed.copy(capability=null,legacy=true,receiptWire=null,legacyResultWire=result.toString())
        assertEquals(result.toString(),NativeUploadJournalCodec.decode(NativeUploadJournalCodec.encode(listOf(legacy)))
            .single().legacyResultWire)
    }
    @Test fun attachmentResourceAliasDoesNotAdmitForeignIdentityOrOpaqueFields() {
        val original=row()
        val manifest=original.manifest.copy(purpose="attachment",threadId="synthetic-thread")
        val result=manifest.file.json().put("threadId",manifest.threadId)
        fun saved(value:JSONObject)=original.copy(key=NativeUploadRow.key(original.target,manifest),manifest=manifest,
            phase="completed",pending=null,capability=null,legacy=true,legacyResultWire=value.toString())
        // Receipts from older Hosts without the alias remain readable.
        assertTrue(NativeUploadJournalCodec.decode(NativeUploadJournalCodec.encode(listOf(saved(result)))).single().terminal)
        for(value in listOf(JSONObject.NULL,7,true,"foreign-resource")) {
            rejects {NativeUploadJournalCodec.encode(listOf(saved(JSONObject(result.toString()).put("resourceId",value))))}
        }
        rejects {NativeUploadJournalCodec.encode(listOf(saved(JSONObject(result.toString())
            .put("resourceId",manifest.id).put("auth","synthetic-placeholder"))))}
    }
    @Test fun creationAttachmentHostDescriptionSurvivesRestoreWithoutAdmittingChangedFile() {
        val original=row()
        val manifest=original.manifest.copy(purpose="creation-attachment",creationId="synthetic-creation",
            attachmentId=original.manifest.id)
        val result=manifest.file.json().put("clientOperationId",manifest.creationId)
        fun saved(value:JSONObject)=original.copy(key=NativeUploadRow.key(original.target,manifest),manifest=manifest,
            phase="completed",pending=null,capability=null,legacy=true,legacyResultWire=value.toString())
        val encoded=NativeUploadJournalCodec.encode(listOf(saved(result)))
        assertEquals(result.toString(),NativeUploadJournalCodec.decode(encoded).single().legacyResultWire)
        val portable=NativeMigrationUploadArchives.projectSource(mapOf(NativeUploadJournalCodec.RECORD to encoded))
        NativeMigrationUploadArchives.validatePortable(portable)
        assertEquals(encoded,JSONObject(portable.getValue(NativeMigrationUploadArchives.RECORD))
            .getJSONArray("entries").getJSONObject(0).getString("journal"))
        val minimal=JSONObject(result.toString()).apply {remove("name");remove("mediaType")}
        assertTrue(NativeUploadJournalCodec.decode(NativeUploadJournalCodec.encode(listOf(saved(minimal)))).single().terminal)
        for((key,value) in listOf("name" to "different.bin","mediaType" to "text/plain","name" to JSONObject.NULL,
            "mediaType" to 7,"auth" to "synthetic-placeholder")) {
            rejects {NativeUploadJournalCodec.encode(listOf(saved(JSONObject(result.toString()).put(key,value))))}
        }
    }
    @Test fun auditCountAndEncodedSizeLimitsFailWithoutTrimmingExistingEvidence() {
        val original=raw();var previous:Map<String,String> = emptyMap()
        repeat(NativeMigrationUploadArchives.MAX_ENTRIES) {i->previous=NativeMigrationUploadArchives.projectSource(previous+mapOf(NativeUploadJournalCodec.RECORD to original+" ".repeat(i)))}
        val before=previous.toMap();val overflow=previous+mapOf(NativeUploadJournalCodec.RECORD to original+" ".repeat(20))
        rejects {NativeMigrationUploadArchives.projectSource(overflow)}
        assertEquals(before,previous);assertEquals(original+" ".repeat(20),overflow[NativeUploadJournalCodec.RECORD])
        val rows=JSONArray((0 until 8).map {i->
            val journal="{\"version\":1,\"jobs\":[]}"+" ".repeat(700_000+i)
            JSONObject().put("journal",journal).put("sha256",ComputerFileCodec.hash(journal.toByteArray()))
        })
        val tooLarge=JSONObject().put("version",1).put("entries",rows).toString()
        assertTrue(tooLarge.toByteArray().size>NativeMigrationUploadArchives.MAX_AUDIT_BYTES)
        rejects {NativeMigrationUploadArchives.validatePortable(mapOf(NativeMigrationUploadArchives.RECORD to tooLarge))}
    }
    @Test fun ordinaryRestoreAndAuditOnlyImportDoNotRequestOrWriteAndProtectedOwnerRefusesBeforeCapability()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val original=raw();val portable=NativeMigrationUploadArchives.projectSource(mapOf(NativeUploadJournalCodec.RECORD to original))
            var values=mapOf(NativeUploadJournalCodec.RECORD to original);var writes=0;var calls=0;val reads=mutableListOf<String>()
            val journal=object:NativeUploadJournal {
                override fun read():String? {reads.add(NativeUploadJournalCodec.RECORD);return values[NativeUploadJournalCodec.RECORD]}
                override fun write(value:String) {writes++;error("Unexpected restore write")}
                override fun requireNetworkOwner() {error("Protected profile cannot own this journal")}
            }
            val commands=object:AgentCommands by NativeCodexCommands({error("Unexpected credential lookup")}) {
                override suspend fun uploadCapability(client:MobileClient,manifest:NativeUploadManifest):NativeUploadCapability? {calls++;error("Unexpected capability request")}
                override suspend fun uploadRequest(client:MobileClient,manifest:NativeUploadManifest,action:NativeUploadAction,offset:Long,bytes:ByteArray?):NativeUploadReceipt {calls++;error("Unexpected wire request")}
            }
            fun center()=NativeUploadCenter(journal,scope,{error("Unexpected target lookup")},{error("Unexpected lease")},
                {_,_->error("Unexpected source/binary lookup")},{commands})
            try {
                val ordinary=center();ordinary.restore()
                assertTrue(ordinary.rows.values.single().paused);assertEquals(original,values[NativeUploadJournalCodec.RECORD])
                val rejected=runCatching {ordinary.transfer(client,row().manifest,null,{_,_->error("Unexpected source")},commands){error("Unexpected legacy")}}
                assertTrue(rejected.isFailure);assertEquals(0,calls);assertEquals(0,writes)
                values=portable
                val imported=center();imported.restore()
                assertTrue(imported.rows.isEmpty());assertEquals(0,calls);assertEquals(0,writes)
                assertEquals(listOf(NativeUploadJournalCodec.RECORD,NativeUploadJournalCodec.RECORD),reads)
            } finally {scope.coroutineContext[Job]!!.cancelAndJoin()}
        }}
    }
}
