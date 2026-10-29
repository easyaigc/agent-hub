package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.UUID

/** Native actual Session destinations and full migration. Injected speech is not JNI/acoustic proof. */
class NativeSavedVoiceSessionTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(240)
    private fun scenario(block: suspend (android.content.Context,NativeSavedVoiceSessionEvidence)->Unit)=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val evidence=NativeSavedVoiceSessionEvidence(context)
        try {withContext(Dispatchers.Main){block(context,evidence)}}finally{evidence.verify()}
    }
    private suspend fun fresh(context: android.content.Context)=NativeSavedVoiceSessionFixture.fresh(context).also {it.connect()}
    private suspend fun remove(f: NativeSavedVoiceSessionFixture) {
        f.close();val path=File(f.storage.coordinationKey)
        require(path.name=="P7-saved-session-"+UUID.fromString(path.name.removePrefix("P7-saved-session-")))
        require(path.parentFile.canonicalFile==f.context.noBackupFilesDir.canonicalFile)
        // Native storage children are siblings, all derived from this fresh fixture UUID.
        val owned=path.parentFile.listFiles().orEmpty().filter {it.name==path.name||it.name.startsWith(path.name+"-")}
        for(file in owned) {require(file.canonicalFile==file.absoluteFile);check(file.deleteRecursively())}
    }
    private suspend fun settled(f: NativeSavedVoiceSessionFixture,c: VoiceInputController) {
        NativeSavedVoiceSessionFixture.until("actual writer/controller settlement") {!c.savedFileBusy};f.storage.awaitQueuedWrites()
    }
    @Test fun actualComposerAndQuestionPublishOnlyAfterExactSerialWriterReadback()=scenario {context,evidence->
        for(question in listOf(false,true)) {
            val f=fresh(context);val gate=NativeSavedVoiceSessionFixture.WriteGate(f.storage)
            try {
                val original=f.seed(question);val raw=f.storage.read("voice-recording:"+original.id)!!;val audio=f.store.bytes(original);val originalJson=easyCanonical(original.json())
                val c=f.begin(question);f.checkpoint(c,gate::checkpoint);f.transcribe(c);gate.await()
                assertEquals(NativeSavedVoiceSessionFixture.ORIGINAL,f.value(question));assertTrue(c.savedFileBusy)
                val staged=gate.staged.get().single();assertFalse(staged.applied);assertNull(staged.destination)
                gate.release.countDown();settled(f,c);assertEquals(VoiceInputController.Phase.IDLE,c.phase)
                assertEquals(NativeSavedVoiceSessionFixture.ACCEPTED,f.value(question))
                val result=NativeVoiceLocalResults.read(f.storage).single();assertTrue(result.applied);val receipt=result.destination!!
                val metadata=f.storage.reopen(context).metadata()!!
                assertEquals(receipt.metadataSha256,voiceSHA(f.storage.reopen(context).read("metadata")!!.toByteArray(Charsets.UTF_8)))
                assertEquals(f.key(question),receipt.destinationKey);assertEquals(f.ownerKey(question),receipt.ownerKey)
                assertEquals(receipt.acceptedVersion,metadata.getJSONObject("draftVersions").getString(receipt.ownerKey))
                assertEquals(receipt.textSha256,voiceSHA(metadata.getJSONObject("drafts").getString(receipt.ownerKey).toByteArray(Charsets.UTF_8)))
                assertNotEquals(receipt.sourceVersion,receipt.acceptedVersion);result.validate(original);f.preserved(original,raw,audio,originalJson)
                if(question)assertEquals("independent composer draft",f.session.text)
                evidence.write(if(question)"question-durable"else"composer-durable",JSONObject().put("recordingId",original.id).put("actionId",result.actionId)
                    .put("destination",receipt.json()).put("busyUntilActualWriterExit",true).put("originalRowSha256",voiceSHA(raw.toByteArray())).put("audioSha256",voiceSHA(audio)))
                audio.fill(0)
            }finally{gate.release.countDown();remove(f)}
        }
    }
    @Test fun actualSessionIdenticalOldMetadataEnqueueRevokesVoicePublication()=scenario {context,evidence->
        for(question in listOf(false,true)) {
            val f=fresh(context);val gate=NativeSavedVoiceSessionFixture.WriteGate(f.storage)
            try {
                val original=f.seed(question);val raw=f.storage.read("voice-recording:"+original.id)!!
                val audio=f.store.bytes(original);val originalJson=easyCanonical(original.json())
                val c=f.begin(question);f.checkpoint(c,gate::checkpoint);f.transcribe(c);gate.await()
                // A real Session producer enqueues an identical pre-fill snapshot after the destination plan.
                f.session.changeAppearance(f.session.appearance)
                gate.release.countDown();settled(f,c)
                assertEquals(NativeSavedVoiceSessionFixture.ORIGINAL,f.value(question))
                val result=NativeVoiceLocalResults.read(f.storage).single();assertFalse(result.applied);assertNull(result.destination)
                assertEquals(NativeSavedVoiceSessionFixture.ORIGINAL,f.storage.reopen(context).metadata()!!.getJSONObject("drafts").getString(f.ownerKey(question)))
                assertNotNull(f.store.restore(original.scope,original.certificate,original.draftKey,original.originalText))
                f.preserved(original,raw,audio,originalJson)
                evidence.write(if(question)"question-queued-old"else"composer-queued-old",JSONObject().put("identicalOldSnapshotRejectedByActualSession",true).put("staged",true));audio.fill(0)
            }finally{gate.release.countDown();remove(f)}
        }
    }
    @Test fun actualSessionFailedAndUnknownDestinationWritesKeepTheResultStaged()=scenario {context,evidence->
        for(question in listOf(false,true))for(phase in listOf(0,1)) {
            val f=fresh(context)
            try {
                val original=f.seed(question);val raw=f.storage.read("voice-recording:"+original.id)!!;val audio=f.store.bytes(original);val originalJson=easyCanonical(original.json())
                val c=f.begin(question);f.checkpoint(c){if(it==phase)throw IOException("owned destination fault at "+phase)}
                f.transcribe(c);NativeSavedVoiceSessionFixture.until("destination fault") {!c.savedFileBusy}
                assertEquals(NativeSavedVoiceSessionFixture.ORIGINAL,f.value(question))
                val result=NativeVoiceLocalResults.read(f.storage).single();assertFalse(result.applied);assertNull(result.destination)
                val persisted=f.storage.reopen(context).metadata()!!.getJSONObject("drafts").getString(f.ownerKey(question))
                assertEquals(if(phase==0)NativeSavedVoiceSessionFixture.ORIGINAL else NativeSavedVoiceSessionFixture.ACCEPTED,persisted)
                assertNotNull(f.store.restore(original.scope,original.certificate,original.draftKey,original.originalText));f.preserved(original,raw,audio,originalJson)
                assertTrue(runCatching {f.storage.awaitQueuedWrites()}.isFailure)
                evidence.write((if(question)"question"else"composer")+"-failure-"+phase,JSONObject().put("faultPhase",phase).put("actualWriteCommitted",phase==1)
                    .put("memoryUnchanged",true).put("staged",true).put("explicitOriginalRecoveryAvailable",true).put("actionId",result.actionId));audio.fill(0)
            }finally{remove(f)}
        }
    }
    @Test fun actualSessionLaterDraftEditsRejectLateDestinationAcceptance()=scenario {context,evidence->
        for(question in listOf(false,true)) {
            val f=fresh(context);val gate=NativeSavedVoiceSessionFixture.WriteGate(f.storage)
            try {
                val original=f.seed(question);val raw=f.storage.read("voice-recording:"+original.id)!!
                val audio=f.store.bytes(original);val originalJson=easyCanonical(original.json())
                val c=f.begin(question);f.checkpoint(c,gate::checkpoint);f.transcribe(c);gate.await()
                f.edit(question,"later independent draft");gate.release.countDown();settled(f,c)
                assertEquals("later independent draft",f.value(question))
                assertEquals("later independent draft",f.storage.reopen(context).metadata()!!.getJSONObject("drafts").getString(f.ownerKey(question)))
                assertFalse(NativeVoiceLocalResults.read(f.storage).single().applied)
                assertNull(f.store.restore(original.scope,original.certificate,original.draftKey,"later independent draft"))
                assertNotNull(f.store.restore(original.scope,original.certificate,original.draftKey,original.originalText))
                f.preserved(original,raw,audio,originalJson)
                evidence.write(if(question)"question-later-draft"else"composer-later-draft",JSONObject().put("laterDraftPreserved",true).put("staged",true).put("noLateFill",true));audio.fill(0)
            }finally{gate.release.countDown();remove(f)}
        }
    }
    @Test fun actualQuestionOwnerDisposeWaitsForWriterThenAllowsExplicitRecovery()=scenario {context,evidence->
        val f=fresh(context);val gate=NativeSavedVoiceSessionFixture.WriteGate(f.storage)
        try {
            val original=f.seed(true);val raw=f.storage.read("voice-recording:"+original.id)!!
            val audio=f.store.bytes(original);val originalJson=easyCanonical(original.json())
            val c=f.begin(true);f.checkpoint(c,gate::checkpoint);f.transcribe(c);gate.await()
            c.suspendRecording();f.session.endQuestionVoice(f.key(true));assertNull(f.session.questionVoiceKey)
            delay(50);assertTrue(c.savedFileBusy);assertEquals(VoiceInputController.Phase.FINISHING,c.phase)
            gate.release.countDown();NativeSavedVoiceSessionFixture.until("actual detached question owner") {c.phase==VoiceInputController.Phase.IDLE&&!c.savedFileBusy}
            assertEquals(NativeSavedVoiceSessionFixture.ORIGINAL,f.value(true));val staged=NativeVoiceLocalResults.read(f.storage).single();assertFalse(staged.applied)
            val next=f.begin(true);f.transcribe(next);settled(f,next)
            assertEquals(NativeSavedVoiceSessionFixture.ACCEPTED,f.value(true));assertEquals(staged.actionId,NativeVoiceLocalResults.read(f.storage).single().actionId)
            assertEquals(1,f.decodes.get());f.preserved(original,raw,audio,originalJson)
            evidence.write("question-owner-dispose",JSONObject().put("noEarlyIdle",true).put("laterExplicitActionReusesStagedResult",true).put("decodes",f.decodes.get()));audio.fill(0)
        }finally{gate.release.countDown();remove(f)}
    }
    @Test fun actualSessionReopenReadsQuestionOwnerAndNeverAutoRecognizesOffIndexResults()=scenario {context,evidence->
        for(question in listOf(false,true))for(committed in listOf(false,true)) {
            val f=fresh(context);var reopened: NativeSavedVoiceSessionFixture?=null
            try {
                val original=f.seed(question);val raw=f.storage.read("voice-recording:"+original.id)!!;val audio=f.store.bytes(original);val originalJson=easyCanonical(original.json())
                val initial=f.begin(question);f.checkpoint(initial){if(it==(if(committed)1 else 0))throw IOException("owned reopen destination fault")}
                f.transcribe(initial);NativeSavedVoiceSessionFixture.until("failed/unknown actual owner before reopen") {!initial.savedFileBusy}
                val result=NativeVoiceLocalResults.read(f.storage).single();assertFalse(result.applied)
                f.storage.write("voice-index-v1","[]");f.close()
                val next=NativeSavedVoiceSessionFixture(context,f.storage.reopen(context));reopened=next;next.restore()
                assertEquals(if(committed)NativeSavedVoiceSessionFixture.ACCEPTED else NativeSavedVoiceSessionFixture.ORIGINAL,next.value(question))
                assertEquals(0,next.decodes.get());assertEquals(0,next.probes.get());assertEquals(result,NativeVoiceLocalResults.read(next.storage).single())
                // A committed-but-unacknowledged text is already visible from disk. Only a deliberate
                // edit back to the original text re-enables that original's exact recovery destination.
                if(committed) {
                    assertNull(next.store.restore(original.scope,original.certificate,original.draftKey,next.value(question)))
                    next.edit(question,NativeSavedVoiceSessionFixture.ORIGINAL);next.storage.awaitQueuedWrites()
                }
                val c=next.begin(question);assertEquals(0,next.decodes.get());assertEquals(0,next.probes.get());assertFalse(NativeVoiceLocalResults.read(next.storage).single().applied)
                next.transcribe(c);settled(next,c)
                assertEquals(NativeSavedVoiceSessionFixture.ACCEPTED,next.value(question));assertEquals(0,next.decodes.get())
                assertEquals(result.actionId,NativeVoiceLocalResults.read(next.storage).single().actionId);next.preserved(original,raw,audio,originalJson)
                evidence.write((if(question)"question-reopen-"else"composer-reopen-")+(if(committed)"unknown"else"failed"),JSONObject().put("sameProcessSessionReopen",true).put("differentPidClaim",false).put("destinationWriteCommitted",committed)
                    .put("explicitRestoreRequired",true).put("decodes",next.decodes.get()).put("sameStagedAction",true));audio.fill(0)
            }finally{if(reopened!=null)remove(reopened!!)else remove(f)}
        }
    }
    @Test fun cancelledFullSessionMigrationWaitsAgainForSavedWorkerAndKeepsOffIndexQuestionGraph()=scenario {context,evidence->
        for(question in listOf(false,true)) {
            val f=fresh(context);var first: Deferred<NativeMigrationOwnerLease>?=null;var second: Deferred<NativeMigrationOwnerLease>?=null
            try {
                val original=f.seed(question);val raw=f.storage.read("voice-recording:"+original.id)!!;val audio=f.store.bytes(original);val originalJson=easyCanonical(original.json())
                val linked=f.result(original,"zh-CN");NativeEncryptedVoiceLocalResultPort(f.storage).stage(original,linked);f.storage.write("voice-index-v1","[]")
                f.engineRelease=CompletableDeferred();val c=f.begin(question);f.transcribe(c);withTimeout(5_000){f.engineEntered.await()}
                assertEquals(1,f.decodes.get());first=CoroutineScope(currentCoroutineContext()).async {f.session.quiesceForMigration(UUID.randomUUID().toString())}
                NativeSavedVoiceSessionFixture.until("first complete Session pause") {f.session.migrationPaused};delay(50);assertFalse(first!!.isCompleted)
                first!!.cancelAndJoin();assertFalse(f.session.migrationPaused);assertTrue(c.savedFileBusy);assertFalse(f.engineExited)
                NativeSavedVoiceSessionFixture.until("retry admission after cancelled wait") {f.session.migrationReady}
                second=CoroutineScope(currentCoroutineContext()).async {f.session.quiesceForMigration(UUID.randomUUID().toString())}
                NativeSavedVoiceSessionFixture.until("second complete Session pause") {f.session.migrationPaused};delay(50)
                assertFalse(second!!.isCompleted);assertFalse(f.engineExited);assertNull(f.storage.migrationPublicationReceipt());assertEquals(1,f.decodes.get())
                f.engineRelease!!.complete(Unit);val lease=withTimeout(15_000){second!!.await()};lease.requireCurrent();assertTrue(f.engineExited);assertFalse(c.savedFileBusy)
                val clear=withContext(Dispatchers.IO){NativeMigrationExport(context,f.storage).capture()}
                try {
                    val archive=NativeMigrationCandidateStore(context,f.storage).validateArchive(clear)
                    assertEquals(raw,archive.text["voice-recording:"+original.id]);assertEquals(linked,NativeVoiceLocalResults.decode(archive.text.getValue(NativeVoiceLocalResults.RECORD)).single())
                    assertArrayEquals(audio,NativeMigrationRecords.bytes(archive.binary.getValue("voice-audio:"+original.id)))
                    val encrypted=File(evidence.folder,"saved-session-"+UUID.randomUUID()+".encrypted")
                    try {
                        val exporter=NativeMigrationExport(context,f.storage);withContext(Dispatchers.IO){exporter.sealFile(encrypted,"owned saved voice session password")}
                        val destination=f.storage.child(context,"voice-session-stage");val candidate=NativeMigrationCandidateStore(context,destination)
                        val receipt=withContext(Dispatchers.IO){candidate.stageFile(UUID.randomUUID().toString(),encrypted,"owned saved voice session password")}
                        assertEquals(receipt,candidate.currentReceipt())
                    }finally{check(encrypted.delete())}
                    f.preserved(original,raw,audio,originalJson);assertNotNull(f.store.restore(original.scope,original.certificate,original.draftKey,original.originalText))
                    evidence.write(if(question)"question-second-migration"else"composer-second-migration",JSONObject().put("firstWaitCancelled",true).put("secondLeaseOnlyAfterWorkerExit",true)
                        .put("injectedEngineNotJNI",true).put("typedAndFramedGraphValidated",true).put("offIndexOriginalPreserved",true).put("decodes",f.decodes.get()))
                }finally{clear.fill(0);audio.fill(0)}
            }finally{f.engineRelease?.complete(Unit);first?.cancel();second?.cancel();remove(f)}
        }
    }
}
