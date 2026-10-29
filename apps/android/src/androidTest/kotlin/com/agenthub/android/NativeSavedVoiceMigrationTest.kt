package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual encrypted typed/framed graph with synthetic speech output; no acoustic proof. */
class NativeSavedVoiceMigrationTest {
    @get:org.junit.Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(180)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private data class Source(val storage: NativeSessionStorage,val original: PendingVoiceRecording,val result: NativeVoiceLocalResult,val draft: VoiceDraft,val beforeRow: String,val audio: ByteArray)
    private suspend fun source(question: Boolean,applied: Boolean): Source {
        val storage=NativeSessionStorage(context,"saved-question-migration-${UUID.randomUUID()}");val payload=MigrationActivationFixtures.payload();val client=MigrationActivationFixtures.client(payload)
        payload.getJSONObject("records").let {rows->rows.keys().forEach {storage.write(it,rows.getString(it))}}
        val thread="synthetic-thread";val base=client.cacheKey(thread)
        val key=if(question)NativeVoiceDestinationKeys.question(base,thread,"turn","approval","question")else base
        val scope=AISharingScope(client.scope!!,"codex-dictation");val draft=VoiceDraft(client,scope,key,UUID.randomUUID().toString(),"voice-v1:fixture",thread,"original")
        val store=VoiceRecordingStore(context,storage);val provisional=store.begin(scope,client.codexDirect!!.fingerprint,key,draft.draftId,thread,draft.text)
        store.file(provisional.id).writeBytes(WavePCM.header(3200)+ByteArray(3200));val original=store.seal(provisional);store.mark(original,true,true)
        val result=NativeVoiceLocalResult(UUID.randomUUID().toString(),original.id,NativeVoiceDiscardRequest(original).fingerprint,original.manifest!!.sha256,"en-US","vosk-model-small-en-us-0.15","30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498","fixture only",1)
        val owner=NativeSavedVoiceDestinationFixture(storage,draft);val port=NativeEncryptedVoiceLocalResultPort(storage);port.stage(original,result)
        if(applied) {val receipt=owner.port.accept(NativeVoiceDraftRequest(draft,original,result)){};port.applied(original,result,receipt)}
        storage.write("voice-index-v1",JSONArray().toString())
        return Source(storage,original,result,draft,storage.read("voice-recording:${original.id}")!!,store.bytes(original))
    }
    private fun forgedFrame(records: NativeMigrationRecords,file: File) {
        val binaries=records.binary.mapValues {(_,encoded)->val bytes=NativeMigrationRecords.bytes(encoded);NativeMigrationBlob(bytes.size,voiceSHA(bytes),bytes.copyOf(minOf(44,bytes.size))){it.write(bytes)}}
        file.outputStream().use {out->NativeMigrationStreamCrypto.write(out,"synthetic saved file password"){NativeMigrationStreamArchive.write(it,NativeMigrationStreamRecords(records.text,binaries,4),context.packageName)}}
    }
    @Test fun composerAndActualQuestionKeysStageAndApplyOffIndexThroughTypedAndFramedMigration()=runBlocking {
        for(question in listOf(false,true))for(applied in listOf(false,true)) {
            val f=source(question,applied)
            try {
                val exporter=NativeMigrationExport(context,f.storage);val clear=exporter.capture()
                try {
                    val archive=NativeMigrationCandidateStore(context,f.storage).validateArchive(clear)
                    assertEquals(f.beforeRow,archive.text["voice-recording:${f.original.id}"])
                    for(framed in listOf(false,true)) {
                        val destination=f.storage.child(context,if(framed)"framed"else"typed");val candidate=NativeMigrationCandidateStore(context,destination);val password="synthetic saved file password"
                        val receipt=if(framed) {
                            val file=File(context.cacheDir,"voice-framed-${UUID.randomUUID()}")
                            try {exporter.sealFile(file,password);candidate.stageFile(UUID.randomUUID().toString(),file,password)}finally{file.delete()}
                        }else candidate.stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(clear,password),password)
                        NativeMigrationActivation(context,destination).activate(receipt);val reopened=destination.reopen(context);val local=NativeVoiceLocalResults.read(reopened).single()
                        assertEquals(applied,local.applied);assertEquals(applied,local.destination!=null);local.validate(f.original)
                        val recovered=VoiceRecordingStore(context,reopened).restore(f.original.scope,f.original.certificate,f.original.draftKey,f.original.originalText)
                        if(applied)assertNull(recovered)else assertEquals(f.original.id,recovered!!.id)
                        assertArrayEquals(f.audio,VoiceRecordingStore(context,reopened).bytes(f.original.copy(migrationReadOnly=true)))
                        val ownerKey=NativeVoiceDestinationKeys.owner(f.draft.key)
                        assertEquals(if(applied)"original\nfixture only"else"original",reopened.metadata()!!.getJSONObject("drafts").getString(ownerKey))
                    }
                    assertEquals(f.beforeRow,f.storage.read("voice-recording:${f.original.id}"));assertArrayEquals(f.audio,VoiceRecordingStore(context,f.storage).bytes(f.original))
                }finally{clear.fill(0)}
            }finally{f.audio.fill(0);File(f.storage.coordinationKey).deleteRecursively()}
        }
    }
    @Test fun foreignThreadTargetMalformedQuestionSuffixAndResultTamperingRejectTypedAndFramedCandidates()=runBlocking {
        val f=source(true,true);val clear=NativeMigrationExport(context,f.storage).capture()
        try {
            val root=JSONObject(String(clear,Charsets.UTF_8));val voice="voice-recording:${f.original.id}"
            val changes=listOf<(JSONObject)->Unit>(
                {row->row.put("draftKey",f.draft.client.cacheKey("other-thread")+":question:"+"a".repeat(64))},
                {row->row.put("draftKey","other-target:question:"+"a".repeat(64))},
                {row->row.put("draftKey",f.draft.key+"x")},
                {row->row.put("draftKey",f.draft.key.substringBeforeLast(':')+":"+"A".repeat(64))})
            val variants=changes.map {change->JSONObject(root.toString()).also {value->val records=value.getJSONObject("records");val row=JSONObject(records.getString(voice));change(row);records.put(voice,row.toString())}}.toMutableList()
            variants+=JSONObject(root.toString()).also {value->val records=value.getJSONObject("records");val results=JSONObject(records.getString(NativeVoiceLocalResults.RECORD));results.getJSONArray("results").getJSONObject(0).put("fingerprint","0".repeat(64));records.put(NativeVoiceLocalResults.RECORD,results.toString())}
            for(value in variants) {
                val destination=f.storage.child(context,"reject");val candidate=NativeMigrationCandidateStore(context,destination)
                assertTrue(runCatching{candidate.validateArchive(value.toString().toByteArray())}.isFailure)
                val records=value.getJSONObject("records").let {rows->rows.keys().asSequence().associateWith {rows.getString(it)}}
                val binaries=value.getJSONObject("binaries").let {rows->rows.keys().asSequence().associateWith {rows.getString(it)}}
                val file=File(context.cacheDir,"voice-malformed-${UUID.randomUUID()}")
                try {forgedFrame(NativeMigrationRecords(records,binaries),file);assertTrue(runCatching{candidate.stageFile(UUID.randomUUID().toString(),file,"synthetic saved file password")}.isFailure)}finally{file.delete()}
                assertEquals(f.beforeRow,f.storage.read(voice))
            }
        }finally{clear.fill(0);f.audio.fill(0);File(f.storage.coordinationKey).deleteRecursively()}
    }
}
