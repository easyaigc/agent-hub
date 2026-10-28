package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey

/** Original synthetic audio through production export, candidate, activation and voice receipt gates. */
class NativeMigrationVoiceTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var client: MobileClient
    private val thread="synthetic-thread"
    private val password="owned voice migration password"
    private fun wave()=WavePCM.header(131072)+ByteArray(131072){(it*37%256).toByte()}
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-voice")};val payload=MigrationActivationFixtures.payload();client=MigrationActivationFixtures.client(payload);val rows=payload.getJSONObject("records");rows.keys().forEach{owned.storage.write(it,rows.getString(it))}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private suspend fun recording(): PendingVoiceRecording {
        val store=VoiceRecordingStore(ui.activity,owned.storage);val value=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey(thread),"voice-v1:synthetic-draft",thread,"original 中文 😀 100% %@ "+UUID.randomUUID())
        store.file(value.id).writeBytes(wave());return store.seal(value)
    }
    private fun raw(): JSONObject {val clear=NativeMigrationExport(ui.activity,owned.storage).capture();try{return JSONObject(String(clear,Charsets.UTF_8))}finally{clear.fill(0)}}
    private fun validate(value: JSONObject)=NativeMigrationCandidateStore(ui.activity,owned.storage).validateArchive(value.toString().toByteArray(Charsets.UTF_8))
    private fun rejected(block: ()->Unit){assertTrue("Incomplete or changed original must reject",runCatching(block).isFailure)}
    private fun stage(destination: NativeSessionStorage,value: JSONObject)=NativeMigrationCandidateStore(ui.activity,destination).stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(value.toString().toByteArray(),password),password)
    private fun body(value: JSONObject,id: String)=JSONObject(value.getJSONObject("records").getString("voice-recording:$id"))
    private fun setBody(value: JSONObject,id: String,record: JSONObject){value.getJSONObject("records").put("voice-recording:$id",record.toString())}
    private fun binaryFile(id: String)=File(owned.storage.coordinationKey,MigrationActivationFixtures.sha("voice-audio:$id".toByteArray())+".bin")
    @Test fun typedExportImportsExactOriginalAudioAndIdempotentActivationPreservesLaterEdits()=runBlocking{
        val original=recording();val store=VoiceRecordingStore(ui.activity,owned.storage);store.mark(original,true,true)
        val before=MigrationActivationFixtures.tree(owned.storage);val value=raw();assertEquals(2,value.getInt("version"));val archive=validate(value);assertEquals(setOf("voice-audio:${original.id}"),archive.binary.keys)
        rejected{NativeMigrationCandidateStore(ui.activity,owned.storage).validate(value.toString().toByteArray())}
        val destination=owned.storage.child(ui.activity,"voice-destination");val receipt=stage(destination,value);assertEquals(archive.size,receipt.recordCount)
        rejected{NativeMigrationCandidateStore(ui.activity,destination).read(receipt)};assertEquals(archive,NativeMigrationCandidateStore(ui.activity,destination).readArchive(receipt))
        val generation=NativeMigrationActivation(ui.activity,destination).activate(receipt);val current=destination.reopen(ui.activity);val fresh=VoiceRecordingStore(ui.activity,current)
        val restored=fresh.restore(original.scope,original.certificate,original.draftKey,original.originalText)!!;assertTrue(restored.migrationReadOnly&&restored.prepareAttempted&&restored.audioAttempted);assertEquals(original.manifest,restored.manifest);assertArrayEquals(wave(),fresh.bytes(restored))
        val imported=JSONObject(current.read("voice-recording:${original.id}")!!);assertTrue(imported.remove("migrationReadOnly") as Boolean);assertEquals(body(value,original.id).toString(),imported.toString())
        current.write("later-edit","original later draft");val after=MigrationActivationFixtures.tree(current);assertEquals(generation,NativeMigrationActivation(ui.activity,destination).activate(receipt));assertEquals(after,MigrationActivationFixtures.tree(current));assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
        rejected{owned.storage.migrationTypedArchiveSnapshot({emptySet()},64)}
    }
    @Test fun legacyInlineAndChunkedAudioKeepExactOriginalsWithoutReadTimeRewrite()=runBlocking{
        val first=recording();val second=recording();val bytes=wave();val name="voice-audio:${first.id}"
        val secret=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey("agent-hub-mobile-v1",null) as SecretKey
        val legacy=java.io.ByteArrayOutputStream();java.io.DataOutputStream(legacy).use {out->out.writeInt(2);out.writeInt(bytes.size);var position=0;var index=0
            while(position<bytes.size){val count=minOf(65536,bytes.size-position);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret);cipher.updateAAD(name.toByteArray()+byteArrayOf(0)+ByteBuffer.allocate(8).putInt(bytes.size).putInt(index++).array());out.write(cipher.iv)
                var n=0;while(n<count){val amount=minOf(16384,count-n);cipher.update(bytes,position+n,amount)?.let{out.write(it)};n+=amount};out.write(cipher.doFinal());position+=count}}
        binaryFile(first.id).writeBytes(legacy.toByteArray())
        val inline=JSONObject(owned.storage.read("voice-recording:${second.id}")!!);inline.remove("audioStorage");inline.put("audio",Base64.getEncoder().encodeToString(bytes));owned.storage.write("voice-recording:${second.id}",inline.toString());owned.storage.remove("voice-audio:${second.id}")
        val before=MigrationActivationFixtures.tree(owned.storage);val value=raw();val archive=validate(value);assertEquals(setOf(name),archive.binary.keys);assertEquals(inline.toString(),archive.text.getValue("voice-recording:${second.id}"));assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
        val destination=owned.storage.child(ui.activity,"legacy-voice-destination");val receipt=stage(destination,value);NativeMigrationActivation(ui.activity,destination).activate(receipt);val fresh=VoiceRecordingStore(ui.activity,destination.reopen(ui.activity))
        for(original in listOf(first,second)){val restored=fresh.restore(original.scope,original.certificate,original.draftKey,original.originalText)!!;assertTrue(restored.migrationReadOnly);assertEquals(original.manifest,restored.manifest);assertArrayEquals(bytes,fresh.bytes(restored))}
    }
    @Test fun strictIndexMetadataTargetAndPermissionTypesRejectWithoutChangingOriginalCandidate()=runBlocking{
        val original=recording();val value=raw();val destination=owned.storage.child(ui.activity,"voice-invalid");stage(destination,value);val before=MigrationActivationFixtures.tree(destination.child(ui.activity,"migration-candidates"))
        val mutations=listOf<(JSONObject)->Unit>(
            {it.getJSONObject("records").put("voice-index-v1",JSONArray().put(original.id).put(original.id).toString())},
            {it.getJSONObject("records").remove("voice-index-v1")},
            {setBody(it,original.id,body(it,original.id).put("future",true))},
            {setBody(it,original.id,body(it,original.id).put("prepareAttempted","true"))},
            {setBody(it,original.id,body(it,original.id).put("createdAt","1"))},
            {setBody(it,original.id,body(it,original.id).put("migrationReadOnly",JSONObject.NULL))},
            {setBody(it,original.id,body(it,original.id).put("certificate","other-certificate"))},
            {setBody(it,original.id,body(it,original.id).put("draftKey","another-target"))},
            {val row=body(it,original.id);row.getJSONObject("scope").getJSONObject("target").put("runtimeId","other-runtime");setBody(it,original.id,row)},
            {setBody(it,original.id,body(it,original.id).put("phase","completed"))})
        for(change in mutations){val damaged=JSONObject(value.toString());change(damaged);rejected{validate(damaged)};rejected{stage(destination,damaged)};assertEquals(before,MigrationActivationFixtures.tree(destination.child(ui.activity,"migration-candidates")))}
    }
    @Test fun missingOrChangedAudioNoncanonicalBase64AndUnsupportedBinaryNeverBecomePartialSuccess()=runBlocking{
        val original=recording();val value=raw();val name="voice-audio:${original.id}";val before=MigrationActivationFixtures.tree(owned.storage)
        val mutations=listOf<(JSONObject)->Unit>(
            {it.getJSONObject("binaries").remove(name)},
            {it.getJSONObject("binaries").put("unknown-binary",Base64.getEncoder().encodeToString(wave()))},
            {it.getJSONObject("binaries").put(name,it.getJSONObject("binaries").getString(name)+"\n")},
            {val changed=wave();changed[44]=(changed[44].toInt() xor 1).toByte();it.getJSONObject("binaries").put(name,Base64.getEncoder().encodeToString(changed))},
            {val row=body(it,original.id);row.getJSONObject("manifest").put("sizeBytes",wave().size-2);setBody(it,original.id,row)},
            {val row=body(it,original.id);row.getJSONObject("manifest").put("durationMs",1);setBody(it,original.id,row)},
            {setBody(it,original.id,body(it,original.id).put("audio",Base64.getEncoder().encodeToString(wave())))},
            {it.put("version",1)},
            {it.getJSONObject("records").put(name,"{}")})
        for(change in mutations){val damaged=JSONObject(value.toString());change(damaged);rejected{validate(damaged)};assertEquals(before,MigrationActivationFixtures.tree(owned.storage))}
    }
    @Test fun binaryWriteAndFinalPointerFailurePreserveInstallationAndOriginalStagedArchive()=runBlocking{
        recording();val value=raw();val sourceBefore=MigrationActivationFixtures.tree(owned.storage)
        for(at in listOf(validate(value).size,0)){
            val destination=owned.storage.child(ui.activity,"voice-fail-${if(at==0)"pointer"else"binary"}");destination.write("sentinel","original new installation")
            val receipt=stage(destination,value);val before=MigrationActivationFixtures.tree(destination)
            rejected{NativeMigrationActivation(ui.activity,destination).activate(receipt){if(it==at)error("Synthetic publish interruption")}}
            assertEquals(before,MigrationActivationFixtures.tree(destination));assertEquals("original new installation",destination.read("sentinel"));assertEquals(validate(value),NativeMigrationCandidateStore(ui.activity,destination).readArchive(receipt))
        };assertEquals(sourceBefore,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun importedVoiceNeverPreparesOrUploadsEvenWhenOriginalReceiptIsMissingOrAwaitingAudio()=runBlocking{
        val original=recording();val destination=owned.storage.child(ui.activity,"voice-readonly");NativeMigrationActivation(ui.activity,destination).activate(stage(destination,raw()));val current=destination.reopen(ui.activity);val store=VoiceRecordingStore(ui.activity,current)
        val restored=store.restore(original.scope,original.certificate,original.draftKey,original.originalText)!!;var reads=0;var writes=0;var phase="missing"
        val commands=object: CodexCommands by NativeCodexCommands({""}){
            override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest): VoiceReceipt {reads++;if(phase=="missing")throw CodexCommandError("Synthetic missing",404);return VoiceReceipt(request,phase,if(phase=="completed")"original returned text"else null,null,"2030-01-01T00:00:00Z")}
            override suspend fun prepareVoice(client: MobileClient,request: VoiceManifest): VoiceReceipt {writes++;error("Imported prepare must not execute")}
            override suspend fun uploadVoice(client: MobileClient,request: VoiceManifest,bytes: ByteArray): VoiceReceipt {writes++;error("Imported upload must not execute")}
        }
        for(next in listOf("missing","awaiting-audio")){phase=next;assertTrue(runCatching{VoiceTranscriptionFlow(commands,store).transcribe(client,restored){true}}.isFailure)}
        phase="completed";assertEquals("original returned text",VoiceTranscriptionFlow(commands,store).transcribe(client,restored){true});assertEquals(3,reads);assertEquals(0,writes)
        assertTrue(store.current(restored).migrationReadOnly);assertArrayEquals(wave(),store.bytes(restored));assertTrue(runCatching{VoiceTranscriptionFlow(commands,store).transcribe(client,restored){false}}.isFailure);assertEquals(3,reads)
    }
    @Test fun incompleteProvisionalVoiceAndCorruptOriginalBinaryFailWithoutDeletingOrSealing()=runBlocking{
        val store=VoiceRecordingStore(ui.activity,owned.storage);val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey(thread),"voice-draft",thread,"provisional")
        store.file(pending.id).writeBytes(wave());var before=MigrationActivationFixtures.tree(owned.storage);rejected{raw()};assertArrayEquals(wave(),store.file(pending.id).readBytes());assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
        val sealed=store.seal(pending);val file=binaryFile(sealed.id);val damaged=file.readBytes();damaged[damaged.lastIndex]=(damaged.last().toInt() xor 1).toByte();file.writeBytes(damaged);before=MigrationActivationFixtures.tree(owned.storage);rejected{raw()};assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun concurrentVoiceRegistrationAndSealCannotExportMixedOriginals()=runBlocking{
        recording();val errors=java.util.concurrent.ConcurrentLinkedQueue<Throwable>();val writer=Thread{try{runBlocking{repeat(4){recording()}}}catch(e: Throwable){errors.add(e)}};writer.start()
        repeat(12){val captured=runCatching{raw()};if(captured.isSuccess){val archive=validate(captured.getOrThrow());assertEquals(JSONArray(archive.text.getValue("voice-index-v1")).length(),archive.binary.size)}}
        writer.join(30_000);assertFalse(writer.isAlive);assertTrue(errors.toString(),errors.isEmpty());val archive=validate(raw());assertEquals(5,archive.binary.size)
    }
}
