package com.agenthub.android

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.UUID

/** Opt-in fresh synthetic pair. Parent ends the writer process before running the reader. */
class NativeSavedVoiceSessionColdTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(240)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun runId()=requireNotNull(InstrumentationRegistry.getArguments().getString("savedVoiceSessionColdRun")) {"Fresh parent-owned UUID required"}
        .also {require(UUID.fromString(it).toString()==it)}
    private fun name(run: String,question: Boolean)="P7-saved-session-cold-"+(if(question)"q-"else"c-")+run
    private fun storage(run: String,question: Boolean)=name(run,question).let {NativeSessionStorage(context,it,it)}
    private fun proof(run: String)=File(context.filesDir,"acceptance/saved-voice-session-cold-"+run+".json")
    private fun scenario(block: suspend (NativeSavedVoiceSessionEvidence)->Unit)=runBlocking {
        val evidence=NativeSavedVoiceSessionEvidence(context)
        try {withContext(Dispatchers.Main){block(evidence)}}finally{evidence.verify()}
    }
    @Test fun stageFreshActualSessionResultsForDifferentPidWithoutReplayingAnyOriginal()=scenario {evidence->
        val run=runId();require(!proof(run).exists());for(question in listOf(false,true))require(!File(context.noBackupFilesDir,name(run,question)).exists()) {"Do not replay a prior cold writer"}
        val rows=JSONArray()
        for(question in listOf(false,true)) {
            val f=NativeSavedVoiceSessionFixture(context,storage(run,question));var closed=false
            try {
                f.connect();val original=f.seed(question);val raw=f.storage.read("voice-recording:"+original.id)!!;val audio=f.store.bytes(original);val originalJson=easyCanonical(original.json())
                val c=f.begin(question);f.checkpoint(c){if(it==0)throw IOException("owned pre-write cold failure")};f.transcribe(c)
                NativeSavedVoiceSessionFixture.until("actual Session failed destination") {!c.savedFileBusy}
                val staged=NativeVoiceLocalResults.read(f.storage).single();assertFalse(staged.applied);assertNull(staged.destination)
                assertEquals(NativeSavedVoiceSessionFixture.ORIGINAL,f.value(question));f.storage.write("voice-index-v1","[]")
                f.preserved(original,raw,audio,originalJson);f.close();closed=true
                rows.put(JSONObject().put("question",question).put("namespace",name(run,question)).put("recordingId",original.id).put("actionId",staged.actionId)
                    .put("draftKey",original.draftKey).put("ownerKey",f.ownerKey(question)).put("originalRowSha256",voiceSHA(raw.toByteArray())).put("audioSha256",voiceSHA(audio))
                    .put("rootCiphertext",JSONObject(MigrationActivationFixtures.tree(f.storage))))
                audio.fill(0)
            }finally{if(!closed)f.close()}
        }
        proof(run).writeText(JSONObject().put("version",1).put("kind","p7-saved-voice-session-cold-v1").put("run",run).put("writerPid",Process.myPid()).put("cases",rows).toString())
        evidence.write("cold-writer",JSONObject().put("run",run).put("cases",2).put("originalInputsOrQueriesReplayed",0).put("requiresDifferentReaderPid",true))
    }
    @Test fun reopenSameActualSessionResultsUnderDifferentPidAndFillOnlyAfterExplicitAction()=scenario {evidence->
        val run=runId();val file=proof(run);require(file.isFile&&file.length() in 1..256*1024)
        val root=StrictMigrationJson.parse(file.readBytes());require(root.keys().asSequence().toSet()==setOf("version","kind","run","writerPid","cases"))
        require(root.get("version")==1&&root.get("kind")=="p7-saved-voice-session-cold-v1"&&root.get("run")==run);assertNotEquals(root.getInt("writerPid"),Process.myPid())
        val claim=File(context.filesDir,"acceptance/saved-voice-session-cold-"+run+"-reader-started.json")
        require(claim.createNewFile()) {"A prior reader intent exists; reconcile it instead of replaying"}
        claim.writeText(JSONObject().put("run",run).put("readerPid",Process.myPid()).put("writerPid",root.getInt("writerPid")).toString())
        val rows=root.getJSONArray("cases");require(rows.length()==2)
        require((0 until rows.length()).map {rows.getJSONObject(it).get("question") as Boolean}.toSet()==setOf(false,true))
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);require(row.keys().asSequence().toSet()==setOf("question","namespace","recordingId","actionId","draftKey","ownerKey","originalRowSha256","audioSha256","rootCiphertext"))
            val question=row.get("question") as Boolean;require(row.getString("namespace")==name(run,question));require(File(context.noBackupFilesDir,name(run,question)).isDirectory)
            val store=storage(run,question);val hashes=row.getJSONObject("rootCiphertext").let {x->x.keys().asSequence().associateWith {x.getString(it)}}
            assertEquals(hashes,MigrationActivationFixtures.tree(store))
            val raw=store.read("voice-recording:"+row.getString("recordingId"))!!;assertEquals(row.getString("originalRowSha256"),voiceSHA(raw.toByteArray()))
            val original=NativeVoiceRecordingRecord.decode(StrictMigrationJson.parse(raw.toByteArray()));val audio=VoiceRecordingStore(context,store).bytes(original);val originalJson=easyCanonical(original.json())
            assertEquals(row.getString("audioSha256"),voiceSHA(audio));val before=NativeVoiceLocalResults.read(store).single();assertFalse(before.applied);assertEquals(row.getString("actionId"),before.actionId)
            val f=NativeSavedVoiceSessionFixture(context,store)
            try {
                f.restore();assertEquals(NativeSavedVoiceSessionFixture.ORIGINAL,f.value(question));assertEquals(row.getString("ownerKey"),f.ownerKey(question))
                assertEquals(0,f.decodes.get());assertEquals(0,f.probes.get());assertEquals(before,NativeVoiceLocalResults.read(store).single())
                val c=f.begin(question);assertEquals(0,f.decodes.get());assertEquals(0,f.probes.get());assertEquals(before,NativeVoiceLocalResults.read(store).single())
                f.transcribe(c);NativeSavedVoiceSessionFixture.until("explicit cold recovered fill") {!c.savedFileBusy};store.awaitQueuedWrites()
                assertEquals(NativeSavedVoiceSessionFixture.ACCEPTED,f.value(question));assertEquals(0,f.decodes.get())
                val applied=NativeVoiceLocalResults.read(store).single();assertTrue(applied.applied);assertEquals(before.actionId,applied.actionId)
                assertEquals(row.getString("ownerKey"),applied.destination!!.ownerKey);f.preserved(original,raw,audio,originalJson)
                evidence.write(if(question)"cold-question-reader"else"cold-composer-reader",JSONObject().put("run",run).put("writerPid",root.getInt("writerPid"))
                    .put("readerPid",Process.myPid()).put("sameOriginalAndStagedAction",true).put("decodeCount",f.decodes.get()).put("explicitRecoveryOnly",true).put("destination",applied.destination!!.json()))
            }finally{audio.fill(0);f.close()}
        }
        File(context.filesDir,"acceptance/saved-voice-session-cold-"+run+"-reader-complete.json").writeText(JSONObject().put("run",run).put("readerPid",Process.myPid()).put("cases",2).toString())
    }
}
