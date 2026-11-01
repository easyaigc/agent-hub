package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Warm synthetic state only. No seal, crash, process death, model input, UI or OS acceptance. */
class NativeMigrationMixedProvisionalVersionTest {
    private class Fixture {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val run=checkNotNull(InstrumentationRegistry.getArguments().getString("migrationProvisionalRun")).also {require(UUID.fromString(it).toString()==it)}
        val roles=listOf("v1-source","mixed-source","destination","reexport-audit")
        val folder=File(app.filesDir,"acceptance/P7-mixed-$run")
        val password="owned mixed provisional versions"
        val handles=mutableListOf<NativeSessionStorage>()
        val exportOperations=listOf("v1","mixed","repeat").associateWith {UUID.randomUUID().toString()}
        val stageOperations=listOf("v1","mixed","repeat").associateWith {UUID.randomUUID().toString()}
        private val exported=mutableSetOf<String>()
        init {NativeChatNotificationNaturalStore.guard(app);require((exportOperations.values+stageOperations.values+run).toSet().size==7)}
        fun fresh() {
            require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance"&&app.packageName==BuildConfig.APPLICATION_ID&&app.applicationInfo.uid==Process.myUid())
            require(!Files.exists(folder.toPath(),NOFOLLOW_LINKS));check(folder.mkdirs());android.system.Os.chmod(folder.path,0x1c0)
            roles.forEach {role->val root=File(folder,role);check(root.mkdir());android.system.Os.chmod(root.path,0x1c0)}
        }
        fun base(role: String)=File(folder,role).also {require(role in roles&&it.isDirectory&&!Files.isSymbolicLink(it.toPath()))}
        fun context(role: String): Context {
            val root=base(role)
            return object: ContextWrapper(app) {override fun getNoBackupFilesDir()=root;override fun getApplicationContext(): Context=this}
        }
        fun alias(role: String)="p7-mixed-$role-$run".also {require(role in roles)}
        fun storage(role: String)=NativeSessionStorage(context(role),"installation",alias(role)).also {handles.add(it)}
        fun reopen(role: String,old: NativeSessionStorage)=old.reopen(context(role)).also {handles.add(it)}
        fun view(role: String)=NativeMigrationExistingView.open(context(role),base(role),"installation",alias(role))
        fun export(role: String,storage: NativeSessionStorage,name: String): File {
            require(name in exportOperations&&exported.add(name))
            val operation=exportOperations.getValue(name)
            val envelope=File(folder,"$name.migration");require(!Files.exists(envelope.toPath(),NOFOLLOW_LINKS))
            NativeVoiceRawOwnership.freeze(storage,operation).use {raw->
                NativeMigrationExport(context(role),storage).sealFile(envelope,password,NativeMigrationOwnerLease(storage,operation,raw){true})
            }
            return envelope
        }
        fun publish(value: JSONObject,name: String="roundtrip") {
            require(name in setOf("intent","v1-staged","v1-activation-intent","v1-activated","mixed-staged","mixed-activation-intent","mixed-activated","repeat-staged","roundtrip"))
            val file=File(folder,"source80-mixed-version-$name.json");check(file.createNewFile());android.system.Os.chmod(file.path,0x180)
            java.io.FileOutputStream(file).use {out->out.write(value.toString().toByteArray(Charsets.UTF_8));out.fd.sync()}
        }
        fun retire(){handles.asReversed().forEach {it.retireWriter()}}
    }
    private fun sha(bytes: ByteArray)=NativeMigrationProvisionalVoice.sha(bytes)
    private fun stamp(file: File)=android.system.Os.lstat(file.path).let {s->listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_nlink,s.st_uid.toLong(),s.st_gid.toLong(),s.st_size,s.st_mtime,s.st_ctime)}
    private fun regulars(base: File)=base.walkTopDown().filter {file->
        require(!Files.isSymbolicLink(file.toPath()));val entry=android.system.Os.lstat(file.path)
        require(entry.st_uid==Process.myUid()&&(android.system.OsConstants.S_ISDIR(entry.st_mode)||android.system.OsConstants.S_ISREG(entry.st_mode)))
        if(android.system.OsConstants.S_ISREG(entry.st_mode))require(entry.st_nlink==1L)
        android.system.OsConstants.S_ISREG(entry.st_mode)
    }.associate {it.relativeTo(base).path to (stamp(it) to sha(it.readBytes()))}
    private fun regularJSON(value: Map<String,Pair<List<Long>,String>>)=JSONObject().also {out->value.toSortedMap().forEach {(name,evidence)->out.put(name,JSONObject().put("stat",JSONArray(evidence.first)).put("sha256",evidence.second))}}
    private fun copied(blob: NativeMigrationBlob)=ByteArrayOutputStream().also(blob::verifiedCopy).toByteArray()
    private fun receiptJSON(value: NativeMigrationCandidateStore.Receipt)=JSONObject().put("id",value.id).put("sha256",value.sha256).put("recordCount",value.recordCount)
    private fun operations(records: NativeMigrationStreamRecords): Map<String,String> {
        val fields=listOf("id","kind","target","intent","intentSha256","phase","result","error","detached","submissionAttempted","sourceKey","sourceVersion","parentOperationId","forkContext","sharingScope")
        return records.text.filterKeys {it.startsWith("operations:")}.flatMap {(name,raw)->
            StrictMigrationJson.parse(raw.toByteArray()).getJSONArray("operations").objects().map {row->
                val immutable=JSONObject();fields.forEach {if(row.has(it))immutable.put(it,row.get(it))}
                "$name:${row.getString("id")}" to sha(immutable.toString().toByteArray())
            }
        }.toMap().also {require(it.isNotEmpty())}
    }
    private fun protectedText(records: NativeMigrationStreamRecords)=records.text.filterKeys {it.startsWith("submission:")||it.startsWith("draft-file:")}
    private fun assertReadOnlyOperations(records: NativeMigrationStreamRecords) {
        val client=decodeClient(JSONObject(records.text.getValue("metadata")).getJSONArray("clients").getJSONObject(0))
        records.text.filterKeys {it.startsWith("operations:")}.forEach {(name,raw)->
            NativeOperationJournal.validateExistingMigrationRecords(name.removePrefix("operations:"),JSONObject(raw)).forEach {record->
                assertTrue(record.migrationReadOnly);val recovery=requireNotNull(record.replyRecovery)
                assertEquals("uncertain",recovery.phase);assertNull(recovery.inspection);assertFalse(recovery.consumed)
                assertFalse(recovery.matches(client,record));assertFalse(recovery.mayRetry(client,record))
            }
        }
    }
    @Test fun v1InertCatalogAndSeededLiveAudioRoundTripAsOneV2Catalog()=runBlocking(Dispatchers.IO) {
        val f=Fixture();f.fresh()
        f.publish(JSONObject().put("version",1).put("run",f.run).put("exportOperations",JSONObject(f.exportOperations)).put("stageOperations",JSONObject(f.stageOperations)).put("noReplay",true),"intent")
        try {
            val source=f.storage("v1-source");val payload=MigrationActivationFixtures.payload();val entries=payload.getJSONObject("records")
            entries.keys().forEach {source.write(it,entries.getString(it))}
            val client=MigrationActivationFixtures.client(payload);val store=VoiceRecordingStore(f.context("v1-source"),source)
            val first=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey("synthetic-thread"),"mixed-v1-draft","synthetic-thread","synthetic old v1 original")
            val oldBytes=ByteArray(131_073){(it*37%251).toByte()};store.file(first.id).writeBytes(oldBytes);android.system.Os.chmod(store.file(first.id).path,0x180)
            val oldRow=requireNotNull(source.read("voice-recording:${first.id}"));val oldIndex=requireNotNull(source.read("voice-index-v1"))
            val v1Originals=regulars(f.base("v1-source"));val v1Envelope=f.export("v1-source",source,"v1")
            assertEquals(v1Originals,regulars(f.base("v1-source")))
            val mixedRoot=f.storage("mixed-source");val v1Candidates=NativeMigrationCandidateStore(f.context("mixed-source"),mixedRoot)
            val v1Receipt=v1Candidates.stageFile(f.stageOperations.getValue("v1"),v1Envelope,f.password)
            f.publish(JSONObject().put("version",1).put("run",f.run).put("receipt",receiptJSON(v1Receipt)),"v1-staged")
            lateinit var v1Catalog: String;lateinit var immutableOperations: Map<String,String>;lateinit var immutableText: Map<String,String>
            NativeMigrationStreamCandidate(f.context("mixed-source"),mixedRoot).read(v1Receipt){records->
                v1Catalog=records.text.getValue(NativeMigrationProvisionalVoice.RECORD)
                val catalog=JSONObject(v1Catalog);assertEquals(1,catalog.getInt("version"));val row=catalog.getJSONArray("originals").getJSONObject(0)
                assertEquals(1,catalog.getJSONArray("originals").length());assertFalse(row.has("uncommittedAudio"));assertEquals(oldRow,row.getString("row"));assertEquals(oldIndex,row.getString("index"))
                assertEquals(setOf(NativeMigrationProvisionalVoice.PREFIX+first.id),records.binary.keys);assertArrayEquals(oldBytes,copied(records.binary.values.single()))
                immutableOperations=operations(records);immutableText=protectedText(records)
            }
            f.publish(JSONObject().put("version",1).put("run",f.run).put("receipt",receiptJSON(v1Receipt)).put("noReplay",true),"v1-activation-intent")
            val v1Generation=NativeMigrationActivation(f.context("mixed-source"),mixedRoot).activate(v1Receipt)
            f.publish(JSONObject().put("version",1).put("run",f.run).put("receipt",receiptJSON(v1Receipt)).put("generation",v1Generation),"v1-activated")
            val mixed=f.reopen("mixed-source",mixedRoot);assertEquals(v1Receipt,mixed.migrationPublicationReceipt())
            val oldInert=NativeMigrationProvisionalVoice.read(mixed).single();assertEquals(first.id,oldInert.id);assertNull(oldInert.uncommittedAudio)
            assertEquals(v1Catalog,mixed.read(NativeMigrationProvisionalVoice.RECORD));assertEquals("[]",mixed.read("voice-index-v1"))
            // Explicitly seeded post-binary/pre-row state. No seal/checkpoint is called or claimed.
            val importedClient=decodeClient(mixed.metadata()!!.getJSONArray("clients").getJSONObject(0));val mixedStore=VoiceRecordingStore(f.context("mixed-source"),mixed)
            val second=mixedStore.begin(AISharingScope(importedClient.scope!!,"codex-dictation"),importedClient.codexDirect!!.fingerprint,importedClient.cacheKey("synthetic-thread"),"mixed-v2-draft","synthetic-thread","synthetic new audio window")
            assertNotEquals(first.id,second.id)
            val pcm=ByteArray(131_072){(it*43%251).toByte()};val audio=WavePCM.header(pcm.size)+pcm
            val secondRaw=mixedStore.file(second.id);secondRaw.writeBytes(audio);android.system.Os.chmod(secondRaw.path,0x180);mixed.writeBinary("voice-audio:${second.id}",secondRaw)
            val secondRow=requireNotNull(mixed.read("voice-recording:${second.id}"));val secondIndex=requireNotNull(mixed.read("voice-index-v1"))
            val pending=NativeVoiceRecordingRecord.decode(JSONObject(secondRow));assertNull(pending.manifest);assertFalse(pending.prepareAttempted);assertFalse(pending.audioAttempted)
            val mixedOriginals=regulars(f.base("mixed-source"));val mixedEnvelope=f.export("mixed-source",mixed,"mixed")
            assertEquals(mixedOriginals,regulars(f.base("mixed-source")));assertEquals(v1Originals,regulars(f.base("v1-source")))
            val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
            val mixedReceipt=candidates.stageFile(f.stageOperations.getValue("mixed"),mixedEnvelope,f.password);assertNotEquals(v1Receipt.id,mixedReceipt.id)
            f.publish(JSONObject().put("version",1).put("run",f.run).put("receipt",receiptJSON(mixedReceipt)),"mixed-staged")
            val expectedBytes=mapOf(NativeMigrationProvisionalVoice.PREFIX+first.id to oldBytes,NativeMigrationProvisionalVoice.PREFIX+second.id to audio,NativeMigrationProvisionalVoice.AUDIO_PREFIX+second.id to audio)
            val expectedRows=mapOf(first.id to (oldRow to oldIndex),second.id to (secondRow to secondIndex));lateinit var mixedCatalog: String
            fun assertMixed(records: NativeMigrationStreamRecords) {
                val raw=records.text.getValue(NativeMigrationProvisionalVoice.RECORD);val catalog=JSONObject(raw);assertEquals(2,catalog.getInt("version"))
                val rows=catalog.getJSONArray("originals").objects();assertEquals(expectedRows.keys.sorted(),rows.map {it.getString("id")})
                rows.forEach {row->val id=row.getString("id");assertEquals(expectedRows.getValue(id).first,row.getString("row"));assertEquals(expectedRows.getValue(id).second,row.getString("index"));assertTrue(row.has("uncommittedAudio"))
                    if(id==first.id)assertEquals(JSONObject.NULL,row.get("uncommittedAudio"))
                    else {val descriptor=row.getJSONObject("uncommittedAudio");assertEquals(setOf("size","sha256"),descriptor.keys().asSequence().toSet());assertEquals(audio.size,descriptor.getInt("size"));assertEquals(sha(audio),descriptor.getString("sha256"))}
                }
                assertEquals(expectedBytes.keys,records.binary.keys);assertEquals(393305L,records.binary.values.sumOf {it.size.toLong()})
                expectedBytes.forEach {(name,bytes)->assertArrayEquals(bytes,copied(records.binary.getValue(name)))}
                assertEquals("[]",records.text.getValue("voice-index-v1"));expectedRows.keys.forEach {assertFalse("voice-recording:$it" in records.text);assertFalse("voice-audio:$it" in records.binary)}
                assertEquals(immutableOperations,operations(records));assertEquals(immutableText,protectedText(records));mixedCatalog=raw
            }
            NativeMigrationStreamCandidate(f.context("destination"),destination).read(mixedReceipt){records->
                assertMixed(records)
                fun changed(edit: (JSONObject)->Unit): NativeMigrationStreamRecords {val texts=NativeMigrationTextMap.copy(records.text);val catalog=JSONObject(mixedCatalog);edit(catalog);texts[NativeMigrationProvisionalVoice.RECORD]=catalog.toString();return records.copy(text=texts)}
                fun row(catalog: JSONObject,id: String)=catalog.getJSONArray("originals").objects().single {it.getString("id")==id}
                fun rejected(value: NativeMigrationStreamRecords){assertThrows(Exception::class.java){candidates.validateStreamRecords(value)}}
                rejected(changed {it.put("version",1)})
                rejected(changed {row(it,first.id).remove("uncommittedAudio")})
                rejected(changed {row(it,first.id).put("uncommittedAudio",NativeMigrationProvisionalVoice.UncommittedAudio(oldBytes.size,sha(oldBytes)).json())})
                rejected(changed {it.put("originals",JSONArray().put(row(it,second.id)))})
                rejected(records.copy(binary=(records.binary-(NativeMigrationProvisionalVoice.AUDIO_PREFIX+second.id))+((NativeMigrationProvisionalVoice.AUDIO_PREFIX+first.id) to records.binary.getValue(NativeMigrationProvisionalVoice.AUDIO_PREFIX+second.id))))
                rejected(changed {row(it,second.id).put("uncommittedAudio",JSONObject.NULL)}.copy(binary=records.binary-(NativeMigrationProvisionalVoice.AUDIO_PREFIX+second.id)))
            }
            val originalCatalog=mixedCatalog
            f.publish(JSONObject().put("version",1).put("run",f.run).put("receipt",receiptJSON(mixedReceipt)).put("noReplay",true),"mixed-activation-intent")
            val generation=NativeMigrationActivation(f.context("destination"),destination).activate(mixedReceipt)
            f.publish(JSONObject().put("version",1).put("run",f.run).put("receipt",receiptJSON(mixedReceipt)).put("generation",generation),"mixed-activated")
            val current=f.reopen("destination",destination);assertEquals(mixedReceipt,current.migrationPublicationReceipt())
            val originals=NativeMigrationProvisionalVoice.read(current).associateBy {it.id};assertEquals(expectedRows.keys,originals.keys)
            originals.forEach {(id,original)->
                assertEquals(expectedRows.getValue(id).first,original.row);assertEquals(expectedRows.getValue(id).second,original.index)
                val bytes=if(id==first.id)oldBytes else audio;assertArrayEquals(bytes,ByteArrayOutputStream().also {NativeMigrationProvisionalVoice.copy(current,original,it)}.toByteArray())
                assertNull(current.read("voice-recording:$id"));assertThrows(Exception::class.java){current.migrationCopyBinaryTo("voice-audio:$id",bytes.size,ByteArrayOutputStream())}
                assertTrue(Files.notExists(File(f.base("destination"),"voice-recordings-v1/$id.wav").toPath(),NOFOLLOW_LINKS))
            }
            assertNull(originals.getValue(first.id).uncommittedAudio);assertThrows(Exception::class.java){NativeMigrationProvisionalVoice.copyUncommittedAudio(current,originals.getValue(first.id),ByteArrayOutputStream())}
            assertArrayEquals(audio,ByteArrayOutputStream().also {NativeMigrationProvisionalVoice.copyUncommittedAudio(current,originals.getValue(second.id),it)}.toByteArray())
            f.view("destination").using {saved->assertEquals(generation,saved.published(mixedReceipt));assertMixed(saved.records);assertReadOnlyOperations(saved.records);saved.originalCandidate(null,mixedReceipt,::assertMixed);saved.unchanged()}
            val restore=VoiceRecordingStore(f.context("destination"),current)
            for(original in listOf(first,second))assertNull(restore.restore(original.scope,original.certificate,original.draftKey,original.originalText))
            val later="Source80 independent later draft ${f.run}";val metadata=current.metadata()!!;metadata.getJSONObject("drafts").put(second.draftKey,later);current.write("metadata",metadata.toString())
            // Prime only this fresh destination's empty coordination file before its stable baseline.
            val prime=UUID.randomUUID().toString();NativeVoiceRawOwnership.freeze(current,prime).use {NativeVoiceRawOwnership.requireFrozen(current,prime,it)}
            val finalOriginals=regulars(f.base("destination"));val repeat=f.export("destination",current,"repeat")
            assertEquals(finalOriginals,regulars(f.base("destination")));assertEquals(later,current.metadata()!!.getJSONObject("drafts").getString(second.draftKey))
            val audit=f.storage("reexport-audit");val repeated=NativeMigrationCandidateStore(f.context("reexport-audit"),audit).stageFile(f.stageOperations.getValue("repeat"),repeat,f.password)
            f.publish(JSONObject().put("version",1).put("run",f.run).put("receipt",receiptJSON(repeated)),"repeat-staged")
            assertEquals(3,setOf(v1Receipt.id,mixedReceipt.id,repeated.id).size)
            NativeMigrationStreamCandidate(f.context("reexport-audit"),audit).read(repeated){records->assertMixed(records);assertEquals(originalCatalog,records.text.getValue(NativeMigrationProvisionalVoice.RECORD));assertReadOnlyOperations(records);assertEquals(later,JSONObject(records.text.getValue("metadata")).getJSONObject("drafts").getString(second.draftKey))}
            f.view("mixed-source").using {saved->assertEquals(v1Generation,saved.published(v1Receipt));saved.originalCandidate(null,v1Receipt){records->assertEquals(v1Catalog,records.text.getValue(NativeMigrationProvisionalVoice.RECORD));assertEquals(setOf(NativeMigrationProvisionalVoice.PREFIX+first.id),records.binary.keys);assertArrayEquals(oldBytes,copied(records.binary.values.single()))};saved.unchanged()}
            assertEquals(v1Originals,regulars(f.base("v1-source")));assertEquals(mixedOriginals,regulars(f.base("mixed-source")));assertEquals(finalOriginals,regulars(f.base("destination")))
            assertEquals(3,f.exportOperations.values.toSet().size)
            assertEquals(7,(f.exportOperations.values+listOf(v1Receipt.id,mixedReceipt.id,repeated.id,f.run)).toSet().size)
            f.publish(JSONObject().put("version",1).put("kind","mixed-provisional-v1-v2-warm").put("run",f.run).put("pid",Process.myPid())
                .put("secondWindowSeeded",true).put("realSealFunctionCalled",false).put("realRowIoFailure",false).put("realPowerLoss",false).put("realProcessDeath",false).put("coldPidAccepted",false)
                .put("v1Receipt",receiptJSON(v1Receipt)).put("mixedReceipt",receiptJSON(mixedReceipt)).put("repeatReceipt",receiptJSON(repeated)).put("v1Generation",v1Generation).put("generation",generation)
                .put("v1Catalog",v1Catalog).put("mixedCatalog",originalCatalog).put("oldId",first.id).put("newId",second.id).put("totalIndependentBinaryBytes",393305L).put("binaryOriginalsCompared",3)
                .put("v1SourceOriginals",regularJSON(v1Originals)).put("mixedSourceOriginals",regularJSON(mixedOriginals)).put("destinationOriginals",regularJSON(finalOriginals))
                .put("exportOperations",JSONObject(f.exportOperations)).put("stageOperations",JSONObject(f.stageOperations)).put("envelopeSha256",JSONObject().put("v1",sha(v1Envelope.readBytes())).put("mixed",sha(mixedEnvelope.readBytes())).put("repeat",sha(repeat.readBytes())))
                .put("stageCalls",3).put("activationCalls",2).put("mixedGraphRejections",6).put("activeVoiceRestored",false).put("originalOperationReplayed",false)
                .put("runtimeOrUiAccepted",false).put("physicalDevAccepted",false).put("fullMigrationAccepted",false).put("lowHeapAccepted",false).put("NativeAcceptance",false))
        } finally {f.retire()}
    }
}
