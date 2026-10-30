package com.agenthub.android

import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Parent kills one held writer for both owners. This class never kills or resets an app itself. */
class NativeSavedVoicePostWriteColdTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(240)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun scenario(block: suspend (NativeSavedVoicePostWriteProtocol,NativeSavedVoicePostWriteEvidence)->Unit)=runBlocking {
        val protocol=NativeSavedVoicePostWriteProtocol(context);val evidence=protocol.evidence()
        try {withContext(Dispatchers.Main){block(protocol,evidence)}}finally{protocol.protectDefault(evidence)}
    }
    private data class Owner(val fixture: NativeSavedVoiceSessionFixture,val baseline: NativeSavedVoicePostWriteBaseline,
        var controller: VoiceInputController?=null,var gate: NativeSavedVoicePostWriteGate?=null)
    private fun rootHashes(value: JSONObject)=value.keys().asSequence().associateWith {value.getString(it)}
    private fun observation(p: NativeSavedVoicePostWriteProtocol,b: NativeSavedVoicePostWriteBaseline): JSONObject =
        p.read(if(b.question)"question-observation"else"composer-observation").also {v->
            p.verifyHeader(v,"observation");require(v.get("question")==b.question&&v.getString("namespace")==b.namespace)
            require(v.getString("recordingId")==b.original.id&&v.getString("actionId")==b.staged.actionId)
        }
    private fun verifyStored(p: NativeSavedVoicePostWriteProtocol,b: NativeSavedVoicePostWriteBaseline,store: NativeSessionStorage): JSONObject {
        val observed=observation(p,b);b.preserved(store)
        val metadata=store.read("metadata")!!;assertEquals(observed.getString("metadata"),metadata)
        assertEquals(observed.getString("metadataSha256"),voiceSHA(metadata.toByteArray(Charsets.UTF_8)))
        val value=StrictMigrationJson.parse(metadata.toByteArray(Charsets.UTF_8))
        assertEquals(NativeSavedVoiceSessionFixture.ACCEPTED,value.getJSONObject("drafts").getString(b.ownerKey))
        assertEquals(observed.getString("acceptedVersion"),value.getJSONObject("draftVersions").getString(b.ownerKey))
        assertNotEquals(b.sourceVersion,observed.getString("acceptedVersion"))
        if(p.boundary==3) {
            val receipt=NativeVoiceDraftReceipt.decode(observed.getJSONObject("destinationReceipt"));receipt.validate(b.original,b.staged)
            assertEquals(b.sourceVersion,receipt.sourceVersion);assertEquals(b.ownerKey,receipt.ownerKey)
            assertEquals(observed.getString("acceptedVersion"),receipt.acceptedVersion);assertEquals(observed.getString("metadataSha256"),receipt.metadataSha256)
        }else assertEquals(JSONObject.NULL,observed.get("destinationReceipt"))
        return observed
    }
    @Test fun holdBothActualDestinationsAfterWriteUntilParentTerminatesThisPid()=scenario {p,evidence->
        p.fresh();p.publish("intent",p.header("intent").put("owners",JSONArray(listOf("composer","non-secret-question")))
            .put("successfulWriterReturnAllowed",false).put("gateBudgetMs",120_000))
        val owners=mutableListOf<Owner>()
        val fixtures=mutableListOf<NativeSavedVoiceSessionFixture>()
        try {
            for(question in listOf(false,true)) {
                val namespace=p.namespace(question);val storage=NativeSessionStorage(context,namespace,namespace)
                val f=NativeSavedVoiceSessionFixture(context,storage);fixtures.add(f);f.connect();val original=f.seed(question)
                val raw=storage.read("voice-recording:"+original.id)!!;val canonical=easyCanonical(original.json());val audio=f.store.bytes(original)
                // The old staged action is known before destination acceptance. Neither writer nor reader re-decodes it.
                val staged=f.result(original);NativeEncryptedVoiceLocalResultPort(storage).stage(original,staged);storage.write("voice-index-v1","[]")
                storage.awaitQueuedWrites();val metadata=storage.metadata()!!
                val b=NativeSavedVoicePostWriteBaseline(question,namespace,original,raw,canonical,audio,storage.read(NativeVoiceLocalResults.RECORD)!!,
                    f.ownerKey(question),metadata.getJSONObject("draftVersions").getString(f.ownerKey(question)))
                b.preserved(storage);owners.add(Owner(f,b))
            }
            p.publish("inputs",p.header("inputs").put("cases",JSONArray(owners.map {it.baseline.json()})))
            for(owner in owners) {
                val f=owner.fixture;val c=f.begin(owner.baseline.question);owner.controller=c
                val gate=NativeSavedVoicePostWriteGate(p,owner.baseline,f.storage);owner.gate=gate
                if(p.boundary in 1..2)f.checkpoint(c,gate::checkpoint)
                else {
                    val actual=c.localResultPort!!;assertTrue(actual is NativeEncryptedVoiceLocalResultPort)
                    c.localResultPort=object: NativeVoiceLocalResultPort by actual {
                        override suspend fun applied(original: PendingVoiceRecording,result: NativeVoiceLocalResult,destination: NativeVoiceDraftReceipt) {
                            // Receipt came from the actual Session destination. Pause before the actual catalog terminal.
                            withContext(Dispatchers.IO){gate.captureAndHold(destination)}
                        }
                    }
                }
                f.transcribe(c)
            }
            NativeSavedVoiceSessionFixture.until("both actual post-write gates") {owners.all {it.gate!!.held.get()}}
            for(owner in owners) {
                assertTrue(owner.controller!!.savedFileBusy);assertTrue(owner.controller!!.active)
                assertEquals(if(p.boundary==3)NativeSavedVoiceSessionFixture.ACCEPTED else NativeSavedVoiceSessionFixture.ORIGINAL,owner.fixture.value(owner.baseline.question))
                assertEquals(0,owner.fixture.decodes.get());assertEquals(0,owner.fixture.voiceHostCalls.get());assertEquals(0,owner.fixture.microphoneCalls.get())
            }
            p.protectDefault(evidence)
            val deadline=owners.minOf {it.gate!!.expiresElapsed};require(deadline-SystemClock.elapsedRealtime()>40_000)
            p.publish("ready",p.header("ready").put("intentSha256",p.hash("intent")).put("inputsSha256",p.hash("inputs"))
                .put("composerObservationSha256",p.hash("composer-observation")).put("questionObservationSha256",p.hash("question-observation"))
                .put("expiresElapsed",deadline).put("readyElapsed",SystemClock.elapsedRealtime()).put("readyWallMillis",System.currentTimeMillis())
                .put("oneKillForBothOwners",true).put("productApplied",false).put("defaultMetadataSha256",p.defaultMetadataHash)
                .put("defaultProofRelative",File(evidence.folder,"default-storage-proof.json").relativeTo(context.filesDir).path))
            while(SystemClock.elapsedRealtime()<deadline-5_000) {
                check(owners.all {it.gate!!.held.get()&&it.controller!!.savedFileBusy}) {"A writer left its requested boundary"}
                p.publish("heartbeat",p.header("heartbeat").put("readySha256",p.hash("ready")).put("bothGatesHeld",true)
                    .put("elapsed",SystemClock.elapsedRealtime()).put("wallMillis",System.currentTimeMillis()),heartbeat=true)
                delay(1_000)
            }
            error("Parent did not terminate the held writer within its advertised window")
        }finally {
            // A genuine external process termination cannot execute this marker or cleanup path.
            if(!p.file("writer-ended").exists())p.publish("writer-ended",p.header("writer-ended").put("requestedKillWindowCompleted",false))
            withContext(NonCancellable) {
                fixtures.forEach {it.session.leaveForeground()}
                owners.forEach {it.gate?.release?.countDown()}
                for(f in fixtures)f.close()
                owners.forEach {it.baseline.audio.fill(0)}
            }
        }
    }
    @Test fun inspectSamePostWriteDataUnderDifferentPidWithoutChangingAnySourceRecord()=scenario {p,evidence->
        val ready=p.requireKilledWriter();val readySha=p.hash("ready");p.claim("inspect-claim",readySha)
        val inputs=p.baselines();val proofs=JSONArray()
        for(b in inputs) {
            val store=p.storage(b.question,readOnlyKey=true);val tree=MigrationActivationFixtures.tree(store)
            val observed=verifyStored(p,b,store);assertEquals(rootHashes(observed.getJSONObject("rootCiphertext")),tree)
            b.preserved(store);assertEquals(tree,MigrationActivationFixtures.tree(store))
            proofs.put(JSONObject().put("question",b.question).put("namespace",b.namespace).put("sameOriginalAndOldAction",true)
                .put("acceptedTextAlreadyDurable",true).put("catalogStillStaged",true).put("allSourceCiphertextUnchanged",true))
            b.audio.fill(0)
        }
        p.protectDefault(evidence)
        p.publish("inspect-complete",p.header("inspect-complete").put("writerPid",ready.getInt("pid")).put("readySha256",readySha)
            .put("cases",proofs).put("SessionConstructed",false).put("sourceWrites",0).put("recognition",0).put("appliedMarked",false))
    }
    private suspend fun beginWithoutMatchingOriginal(f: NativeSavedVoiceSessionFixture,b: NativeSavedVoicePostWriteBaseline,expected: String): VoiceInputController {
        val controller=if(b.question)f.session.questionVoice!!else f.session.voice!!
        NativeSavedVoiceSessionFixture.until("actual Session destination admission without matching original") {
            !f.session.loading&&!f.session.historyLoading&&f.session.canUseVoice&&
                (!b.question||f.session.canBeginQuestionVoice(f.approval(),f.question(),f.session.approvalActionDestination()))
        }
        f.storage.awaitQueuedWrites()
        assertEquals(expected,f.value(b.question));assertNotEquals(b.original.originalText,expected)
        if(b.question)f.session.beginQuestionVoice(f.approval(),f.question(),f.session.approvalActionDestination(),{f.value(true)},{f.edit(true,it)},recoverSavedFile=true)
        else f.session.beginVoice(recoverSavedFile=true)
        NativeSavedVoiceSessionFixture.until("explicit restore without matching original") {controller.phase==VoiceInputController.Phase.FAILED}
        assertFalse(controller.canInspectSavedFile);assertFalse(controller.canTranscribeSavedFile)
        assertEquals(expected,f.value(b.question));f.preserved(b.original,b.raw,b.audio,b.canonical)
        assertEquals(0,f.probes.get());assertEquals(0,f.decodes.get())
        return controller
    }
    private suspend fun explicitNoAppend(f: NativeSavedVoiceSessionFixture,b: NativeSavedVoicePostWriteBaseline,expected: String) {
        NativeSavedVoiceSessionFixture.until("actual Session idle before explicit restore snapshot") {
            !f.session.loading&&!f.session.historyLoading&&f.session.canUseVoice
        }
        f.storage.awaitQueuedWrites()
        val before=f.storage.read("metadata")!!;val version=f.storage.metadata()!!.getJSONObject("draftVersions").getString(b.ownerKey)
        val c=beginWithoutMatchingOriginal(f,b,expected);val writes=AtomicInteger()
        f.checkpoint(c){writes.incrementAndGet();error("An unmatched saved original must not write the destination")}
        assertFalse(c.canInspectSavedFile);assertFalse(c.canTranscribeSavedFile)
        c.inspectSavedFile();c.transcribeSavedFile();f.storage.awaitQueuedWrites()
        assertEquals(0,writes.get());assertEquals(0,f.probes.get());assertEquals(0,f.decodes.get())
        assertEquals(expected,f.value(b.question));assertEquals(before,f.storage.read("metadata"))
        assertEquals(version,f.storage.metadata()!!.getJSONObject("draftVersions").getString(b.ownerKey))
        f.preserved(b.original,b.raw,b.audio,b.canonical);assertEquals(b.catalog,f.storage.read(NativeVoiceLocalResults.RECORD))
        assertFalse(NativeVoiceLocalResults.read(f.storage).single().applied)
        if(b.question)f.session.endQuestionVoice(f.key(true))else c.cancel()
        NativeSavedVoiceSessionFixture.until("explicit restore presentation closed") {c.phase==VoiceInputController.Phase.IDLE}
    }
    @Test fun explicitRecoveryKeepsAlreadyWrittenTextAndNeverAppendsIntoANewerDraft()=scenario {p,evidence->
        val ready=p.requireKilledWriter();val readySha=p.hash("ready");val inspected=p.read("inspect-complete")
        p.verifyHeader(inspected,"inspect-complete");require(inspected.getString("readySha256")==readySha&&inspected.getInt("writerPid")==ready.getInt("pid"))
        p.claim("continue-claim",readySha)
        for(b in p.baselines()) {
            // Preflight is read-only with existing-key-only access; normal Session uses that same verified namespace/key.
            val source=p.storage(b.question,readOnlyKey=true);val beforeTree=MigrationActivationFixtures.tree(source)
            verifyStored(p,b,source);assertEquals(beforeTree,MigrationActivationFixtures.tree(source))
            val f=NativeSavedVoiceSessionFixture(context,p.storage(b.question))
            try {
                f.restore();assertEquals(NativeSavedVoiceSessionFixture.ACCEPTED,f.value(b.question))
                assertEquals(0,f.probes.get());assertEquals(0,f.decodes.get());b.preserved(f.storage)
                // No external observation receipt is injected back into the product to manufacture applied=true.
                explicitNoAppend(f,b,NativeSavedVoiceSessionFixture.ACCEPTED)
                val later="later independent post-write draft"
                f.edit(b.question,later);f.storage.awaitQueuedWrites();explicitNoAppend(f,b,later)
                assertEquals(0,f.answers.get());assertEquals(0,f.microphoneCalls.get());assertEquals(0,f.voiceHostCalls.get())
                evidence.write(if(b.question)"postwrite-question-explicit"else"postwrite-composer-explicit",JSONObject().put("writerPid",ready.getInt("pid"))
                    .put("readerPid",Process.myPid()).put("alreadyStoredTextNotAppended",true).put("laterDraftNotOverwritten",true).put("catalogRemainsStaged",true)
                    .put("sameOldActionId",b.staged.actionId).put("voiceDestinationWrites",0).put("recognitions",0))
            }finally{f.close();b.audio.fill(0)}
        }
        p.protectDefault(evidence);p.publish("continue-complete",p.header("continue-complete").put("readySha256",readySha)
            .put("writerPid",ready.getInt("pid")).put("cases",2).put("noDuplicateAppendOrNewDraftOverwrite",true).put("appliedMarked",false))
    }
}
