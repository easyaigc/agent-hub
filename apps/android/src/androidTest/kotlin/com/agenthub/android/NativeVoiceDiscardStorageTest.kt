package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Production encrypted storage and migration graph. No microphone, recognizer, Host or model. */
class NativeVoiceDiscardStorageTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var client: MobileClient
    private val rawFiles=mutableListOf<File>()
    private val password="owned durable voice discard migration"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"voice-discard")};client=MigrationActivationFixtures.client(MigrationActivationFixtures.payload())}
    @After fun finish(){rawFiles.forEach {check(it.delete()||!it.exists())};if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun source(): NativeSessionStorage {
        val storage=owned.storage.child(ui.activity,"vd-"+UUID.randomUUID().toString().take(8));val rows=MigrationActivationFixtures.payload().getJSONObject("records")
        rows.keys().forEach {storage.write(it,rows.getString(it))};return storage
    }
    private fun wave()=WavePCM.header(3200)+ByteArray(3200){(it*37%256).toByte()}
    private suspend fun begin(storage: NativeSessionStorage,text: String="original 中文 😀 100% %@"): PendingVoiceRecording {
        val store=VoiceRecordingStore(ui.activity,storage);return store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,
            client.cacheKey("synthetic-thread"),"voice-v1:original","synthetic-thread",text).also {rawFiles.add(store.file(it.id))}
    }
    private suspend fun sealed(storage: NativeSessionStorage): PendingVoiceRecording {
        val store=VoiceRecordingStore(ui.activity,storage);val value=begin(storage);store.file(value.id).writeBytes(wave());return store.seal(value)
    }
    private fun tree(storage: NativeSessionStorage)=MigrationActivationFixtures.tree(storage)
    private fun marker(storage: NativeSessionStorage,id: String)=NativeVoiceDiscardCatalog.read(storage).getValue(id)
    private suspend fun discard(storage: NativeSessionStorage,pending: PendingVoiceRecording): NativeVoiceDiscardReceipt {
        val request=NativeVoiceDiscardRequest(pending);return NativeEncryptedVoiceDiscardPort(VoiceRecordingStore(ui.activity,storage)).discard(request).also {assertTrue(it.acknowledges(request))}
    }
    private suspend fun noOffer(storage: NativeSessionStorage,pending: PendingVoiceRecording) {
        assertNull(VoiceRecordingStore(ui.activity,storage).restore(pending.scope,pending.certificate,pending.draftKey,pending.originalText))
    }
    private fun archive(storage: NativeSessionStorage): JSONObject {
        val clear=NativeMigrationExport(ui.activity,storage).capture();try{return JSONObject(String(clear,Charsets.UTF_8))}finally{clear.fill(0)}
    }
    private fun validate(value: JSONObject)=NativeMigrationCandidateStore(ui.activity,owned.storage).validateArchive(value.toString().toByteArray(Charsets.UTF_8))
    @Test fun unknownSealedOriginalSurvivesDiscardReopenLateReceiptAndRemoval()=runBlocking {
        val storage=source();val pending=sealed(storage);val store=VoiceRecordingStore(ui.activity,storage)
        store.mark(pending,true,true,VoiceReceipt(pending.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"))
        val original=storage.read("voice-recording:${pending.id}")!!;val before=tree(storage);val request=NativeVoiceDiscardRequest(pending);val receipt=discard(storage,pending)
        val terminal=marker(storage,pending.id);assertEquals(request.fingerprint,terminal.fingerprint);assertEquals(original,storage.read(terminal.auditName))
        assertEquals(original,storage.read("voice-recording:${pending.id}"));for((name,hash)in before)assertEquals(hash,tree(storage)[name])
        assertArrayEquals(wave(),store.bytes(pending));noOffer(storage,pending);noOffer(storage.reopen(ui.activity),pending)
        val committed=tree(storage);assertEquals(receipt,discard(storage,pending));assertEquals(committed,tree(storage))
        store.mark(pending,receipt=VoiceReceipt(pending.manifest!!,"completed","late original result",null,"2030-01-01T00:00:00Z"))
        assertEquals("completed",JSONObject(storage.read("voice-recording:${pending.id}")!!).getString("phase"));assertEquals(terminal,marker(storage,pending.id));assertEquals(original,storage.read(terminal.auditName));noOffer(storage,pending)
        val late=tree(storage);store.remove(pending);assertEquals(late,tree(storage));assertArrayEquals(wave(),store.bytes(pending))
    }
    @Test fun incompleteOddRawAbsentAndEmptyOriginalsAreTerminalWithoutSealingOrDeleting()=runBlocking {
        for(bytes in listOf<ByteArray?>(byteArrayOf(7,3,9),ByteArray(0),null)) {
            val storage=source();val pending=begin(storage);val store=VoiceRecordingStore(ui.activity,storage);val file=store.file(pending.id)
            if(bytes!=null)file.writeBytes(bytes);val original=storage.read("voice-recording:${pending.id}")!!;discard(storage,pending)
            val terminal=marker(storage,pending.id);assertEquals(if(bytes==null)"absent"else if(bytes.isEmpty())"empty"else"raw",terminal.evidence.kind)
            assertEquals(original,storage.read("voice-recording:${pending.id}"));assertEquals(original,storage.read(terminal.auditName));assertNull(store.current(pending).manifest)
            if(bytes!=null){assertArrayEquals(bytes,file.readBytes());if(bytes.isNotEmpty())assertArrayEquals(bytes,storage.readBinary(terminal.rawName,bytes.size))}else assertFalse(file.exists())
            assertTrue(runCatching {store.seal(pending)}.isFailure);val before=tree(storage);store.remove(pending,true);assertEquals(before,tree(storage));noOffer(storage.reopen(ui.activity),pending)
            assertTrue(store.migrationProvisionalIds().isEmpty());validate(archive(storage))
        }
    }
    @Test fun provisionalRequestCanMatchAnAlreadySealedSameOriginalWithoutChangingItsIntent()=runBlocking {
        val storage=source();val pending=begin(storage);val request=NativeVoiceDiscardRequest(pending);val store=VoiceRecordingStore(ui.activity,storage)
        store.file(pending.id).writeBytes(wave());val current=store.seal(pending);val before=storage.read("voice-recording:${pending.id}")
        val port=NativeEncryptedVoiceDiscardPort(store);assertTrue(port.discard(request).acknowledges(request));val terminal=marker(storage,pending.id)
        assertFalse(terminal.manifestKnown);assertEquals(request.fingerprint,terminal.fingerprint);assertEquals(before,storage.read("voice-recording:${pending.id}"))
        val committed=tree(storage);assertTrue(port.discard(NativeVoiceDiscardRequest(current)).discarded);assertEquals(committed,tree(storage));noOffer(storage,current);validate(archive(storage));Unit
    }
    @Test fun everyImmutableMismatchRejectsWithoutPublishingOrChangingOriginalEvidence()=runBlocking {
        val storage=source();val pending=sealed(storage);val before=tree(storage);val port=NativeEncryptedVoiceDiscardPort(VoiceRecordingStore(ui.activity,storage))
        val mismatches=listOf(pending.copy(id=UUID.randomUUID().toString()),pending.copy(certificate="other"),pending.copy(draftKey="other"),pending.copy(draftId="other"),
            pending.copy(threadId=null),pending.copy(createdAt=pending.createdAt+1),pending.copy(originalText="other"),pending.copy(scope=pending.scope.copy(model="other")),
            pending.copy(scope=AISharingScope.decode(pending.scope.json().also {it.getJSONObject("target").put("runtimeId","other-runtime")})),pending.copy(manifest=pending.manifest!!.copy(sha256="b".repeat(64))))
        for(value in mismatches){assertTrue(runCatching {port.discard(NativeVoiceDiscardRequest(value))}.isFailure);assertEquals(before,tree(storage))}
        assertNotNull(VoiceRecordingStore(ui.activity,storage).restore(pending.scope,pending.certificate,pending.draftKey,pending.originalText))
    }
    @Test fun precommitInterruptionsLeaveOriginalOfferableAndLostAcknowledgementRetriesReadOnly()=runBlocking {
        for(point in listOf(1,2,0,-1)) {
            val storage=source();val pending=begin(storage);val store=VoiceRecordingStore(ui.activity,storage);store.file(pending.id).writeBytes(byteArrayOf(9,8,7))
            val original=storage.read("voice-recording:${pending.id}");val before=tree(storage);val request=NativeVoiceDiscardRequest(pending)
            val failing=NativeEncryptedVoiceDiscardPort(store){if(it==point)throw java.io.IOException("owned discard interruption")}
            assertTrue(runCatching {failing.discard(request)}.isFailure);assertEquals(original,storage.read("voice-recording:${pending.id}"));assertArrayEquals(byteArrayOf(9,8,7),store.file(pending.id).readBytes())
            if(point==-1){noOffer(storage,pending);val published=tree(storage);assertTrue(NativeEncryptedVoiceDiscardPort(store).discard(request).acknowledges(request));assertEquals(published,tree(storage))}
            else {assertEquals(before,tree(storage));assertNotNull(store.restore(pending.scope,pending.certificate,pending.draftKey,pending.originalText))}
        }
        for(missing in listOf(false,true)) {
            val storage=source();val pending=sealed(storage);val file=File(storage.coordinationKey,voiceSHA("voice-audio:${pending.id}".toByteArray())+".bin")
            if(missing)assertTrue(file.delete())else file.writeBytes(file.readBytes().also {it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})
            val before=tree(storage);assertTrue(runCatching {discard(storage,pending)}.isFailure);assertEquals(before,tree(storage));assertTrue(NativeVoiceDiscardCatalog.read(storage).isEmpty())
        }
    }
    @Test fun corruptCatalogPendingFilesAndChangedAuditFailClosedWithoutRepair()=runBlocking {
        val storage=source();val pending=sealed(storage);discard(storage,pending);val terminal=marker(storage,pending.id);val catalog=storage.read(NativeVoiceDiscardCatalog.RECORD)!!
        val invalid=listOf("{broken",catalog.replace("\"version\":1","\"version\":2"),catalog.replace("\"manifestKnown\":true","\"manifestKnown\":\"true\""))
        for(raw in invalid){storage.write(NativeVoiceDiscardCatalog.RECORD,raw);val before=tree(storage);assertTrue(runCatching {noOffer(storage,pending)}.isFailure);assertTrue(runCatching {discard(storage,pending)}.isFailure);assertTrue(runCatching {archive(storage)}.isFailure);assertEquals(before,tree(storage))}
        storage.write(NativeVoiceDiscardCatalog.RECORD,catalog)
        val physical=File(storage.coordinationKey,voiceSHA(NativeVoiceDiscardCatalog.RECORD.toByteArray())+".bin")
        for(suffix in listOf(".bak",".new")){val file=File(physical.path+suffix);file.writeText("owned unfinished terminal");val before=tree(storage);assertTrue(runCatching {noOffer(storage,pending)}.isFailure);assertEquals(before,tree(storage));assertTrue(file.delete())}
        storage.write(terminal.auditName,storage.read(terminal.auditName)!!+" ");val before=tree(storage);assertTrue(runCatching {noOffer(storage,pending)}.isFailure);assertTrue(runCatching {archive(storage)}.isFailure);assertEquals(before,tree(storage))
    }
    @Test fun sealedAndRawTerminalsMigrateTogetherWithExactAuditsAndNeverBecomeAnOffer()=runBlocking {
        val storage=source();val first=sealed(storage);val second=begin(storage,"raw original");val store=VoiceRecordingStore(ui.activity,storage);val raw=byteArrayOf(1,5,3,7,9);store.file(second.id).writeBytes(raw)
        store.mark(first,true,true,VoiceReceipt(first.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"));discard(storage,first);discard(storage,second)
        val value=archive(storage);val expected=validate(value);val original=tree(storage);val destination=owned.storage.child(ui.activity,"vd-import")
        val envelope=File(owned.folder,"discard.migration");NativeMigrationExport(ui.activity,storage).sealFile(envelope,password)
        val receipt=NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),envelope,password)
        val generation=NativeMigrationActivation(ui.activity,destination).activate(receipt);val current=destination.reopen(ui.activity);val fresh=VoiceRecordingStore(ui.activity,current)
        for(pending in listOf(first,second)){noOffer(current,pending);assertTrue(fresh.current(pending).migrationReadOnly);val terminal=marker(current,pending.id);assertEquals(storage.read(terminal.auditName),current.read(terminal.auditName));assertEquals(marker(storage,pending.id),terminal);fresh.remove(pending)}
        assertArrayEquals(wave(),fresh.bytes(first));assertArrayEquals(raw,current.readBinary(marker(current,second.id).rawName,raw.size));assertArrayEquals(raw,store.file(second.id).readBytes())
        val after=tree(current);assertEquals(generation,NativeMigrationActivation(ui.activity,destination).activate(receipt));assertEquals(after,tree(current));assertEquals(original,tree(storage))
        assertEquals(expected.text.getValue(NativeVoiceDiscardCatalog.RECORD),current.read(NativeVoiceDiscardCatalog.RECORD));validate(archive(current));Unit
    }
    @Test fun malformedTerminalGraphsCannotReplaceAnExistingCandidate()=runBlocking {
        val storage=source();val pending=begin(storage);VoiceRecordingStore(ui.activity,storage).file(pending.id).writeBytes(byteArrayOf(1,2,3));discard(storage,pending)
        val value=archive(storage);val terminal=marker(storage,pending.id);val candidate=NativeMigrationCandidateStore(ui.activity,owned.storage)
        val first=candidate.stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(value.toString().toByteArray(),password),password);val before=tree(owned.storage.child(ui.activity,"migration-candidates"))
        val changes=listOf<(JSONObject)->Unit>(
            {it.getJSONObject("records").remove(terminal.auditName)},
            {it.getJSONObject("records").put(terminal.auditName,it.getJSONObject("records").getString(terminal.auditName)+" ")},
            {it.getJSONObject("binaries").remove(terminal.rawName)},
            {it.getJSONObject("binaries").put(terminal.rawName,java.util.Base64.getEncoder().encodeToString(byteArrayOf(3,2,1)))},
            {val rows=it.getJSONObject("records");rows.put("voice-recording:${pending.id}",JSONObject(rows.getString("voice-recording:${pending.id}")).put("prepareAttempted",true).toString())},
            {it.getJSONObject("records").remove(NativeVoiceDiscardCatalog.RECORD)},
            {it.getJSONObject("records").put(NativeVoiceDiscardCatalog.AUDIT_PREFIX+UUID.randomUUID()+":"+UUID.randomUUID(),pending.json().toString())})
        for(change in changes){val broken=JSONObject(value.toString());change(broken);assertTrue(runCatching {candidate.stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(broken.toString().toByteArray(),password),password,first)}.isFailure);assertEquals(before,tree(owned.storage.child(ui.activity,"migration-candidates")))}
    }
    @Test fun terminalRowsReleaseActiveCapacityWhileRemainingInTheMigrationGraph()=runBlocking {
        val storage=source();val originals=List(32){begin(storage)};val store=VoiceRecordingStore(ui.activity,storage)
        assertTrue(runCatching {begin(storage)}.isFailure);for(pending in originals)discard(storage,pending)
        val next=sealed(storage);assertEquals(listOf(next.id),JSONArray(storage.read("voice-index-v1")!!).strings());assertEquals(32,NativeVoiceDiscardCatalog.read(storage).size)
        // Restore is keyed by draft/text, not recording ID: the legitimate new capture must remain offerable.
        val originalIds=originals.map {it.id}.toSet()
        for(pending in originals){
            assertNotNull(storage.read("voice-recording:${pending.id}"))
            val restored=store.restore(pending.scope,pending.certificate,pending.draftKey,pending.originalText)
            assertEquals(next.id,restored?.id);assertFalse(restored!!.id in originalIds)
        }
        val records=validate(archive(storage));assertEquals(33,records.text.keys.count {it.startsWith("voice-recording:")})
        assertNotNull(store.restore(next.scope,next.certificate,next.draftKey,next.originalText))
    }
    @Test fun easyWorkAuthorizationFencesAreAnExplicitWholeArchiveGapAndNeverDropped() {
        val storage=source();val value=archive(storage);val candidate=NativeMigrationCandidateStore(ui.activity,owned.storage)
        val first=candidate.stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(value.toString().toByteArray(),password),password);val before=tree(owned.storage.child(ui.activity,"migration-candidates"))
        val name="easywork-authorization-fence:"+voiceSHA("synthetic-ref".toByteArray())
        for(state in listOf("pending","confirmed","future")) {
            val fence=JSONObject().put("version",1).put("ref","synthetic-ref").put("epoch",UUID.randomUUID().toString()).put("revoke",JSONObject().put("state",state).put("operationId",UUID.randomUUID().toString()).put("saved","synthetic original grant audit"))
            storage.write(name,fence.toString());val original=tree(storage);assertTrue(runCatching {archive(storage)}.isFailure);assertEquals(original,tree(storage))
            val broken=JSONObject(value.toString());broken.getJSONObject("records").put(name,fence.toString())
            assertTrue(runCatching {candidate.stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(broken.toString().toByteArray(),password),password,first)}.isFailure);assertEquals(before,tree(owned.storage.child(ui.activity,"migration-candidates")))
        }
    }
}
