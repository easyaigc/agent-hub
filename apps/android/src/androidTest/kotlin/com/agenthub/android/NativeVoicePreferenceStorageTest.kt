package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real encrypted adapter and existing migration. No recognizer, capability/permission probe or Host. */
class NativeVoicePreferenceStorageTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val settingsTasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val password="owned voice preference migration password"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"voice-preference");owned.session.resetAcceptance()}}
    @After fun finish(){settingsTasks.cancel();if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->root.walkTopDown().filter{it.isFile}.associate {file->
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        file.relativeTo(root).path to digest.digest().joinToString(""){"%02x".format(it)}
    }}
    private fun child(prefix: String)=owned.storage.child(ui.activity,prefix+"-"+UUID.randomUUID().toString().take(8))
    private suspend fun source(value: VoiceInputController.Provider?=null): NativeSessionStorage {
        val storage=child("vp-src");val rows=MigrationActivationFixtures.payload().getJSONObject("records");rows.keys().forEach{storage.write(it,rows.getString(it))}
        if(value!=null)NativeEncryptedVoicePreferenceStore(storage).save(value);return storage
    }
    private fun export(storage: NativeSessionStorage)=File(owned.folder,"vp-${UUID.randomUUID().toString().take(8)}.migration").also{NativeMigrationExport(ui.activity,storage).sealFile(it,password)}
    private fun stage(source: NativeSessionStorage,destination: NativeSessionStorage=owned.storage)=NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),export(source),password)
    private fun bind(storage: NativeSessionStorage): NativeVoiceSettings {
        lateinit var settings: NativeVoiceSettings
        ui.runOnIdle{settings=NativeVoiceSettings(settingsTasks);settings.bind(NativeEncryptedVoicePreferenceStore(storage))}
        return settings
    }
    @Test fun missingPreferenceIsReadOnlyAndRealBindingKeepsDesktopWithoutCreatingARecord()=runBlocking {
        val storage=child("vp-empty");val before=tree(storage);assertNull(NativeEncryptedVoicePreferenceStore(storage).load());assertEquals(before,tree(storage))
        val settings=bind(storage);ui.waitUntil(10_000){settings.persisted};assertEquals(VoiceInputController.Provider.DESKTOP,settings.defaultProvider);assertFalse(settings.preferenceFailure)
        assertNull(storage.read(NativeVoicePreferenceRecord.RECORD));assertEquals(before,tree(storage))
        assertNull(NativeEncryptedVoicePreferenceStore(storage.reopen(ui.activity)).load());assertEquals(before,tree(storage))
    }
    @Test fun bothChoicesAreEncryptedAtomicAndReadFromAReopenedHandleWithSameValueRetryReadOnly()=runBlocking {
        val storage=child("vp-choice");val adapter=NativeEncryptedVoicePreferenceStore(storage)
        for(value in listOf(VoiceInputController.Provider.DESKTOP,VoiceInputController.Provider.SYSTEM)) {
            adapter.save(value);assertEquals(value,NativeEncryptedVoicePreferenceStore(storage.reopen(ui.activity)).load())
            val raw=storage.read(NativeVoicePreferenceRecord.RECORD)!!;assertEquals(value,NativeVoicePreferenceRecord.decode(raw))
            val encrypted=File(storage.coordinationKey,sha(NativeVoicePreferenceRecord.RECORD.toByteArray())+".bin").readBytes()
            assertFalse(String(encrypted,Charsets.ISO_8859_1).contains(raw));val before=tree(storage);adapter.save(value);assertEquals(before,tree(storage))
        }
    }
    @Test fun corruptFutureEnumsAndPendingFilesPreserveOriginalsAndRejectLoadSaveAndExport()=runBlocking {
        val invalid=listOf("{broken","{\"version\":2,\"defaultProvider\":\"desktop\"}","{\"version\":1.0,\"defaultProvider\":\"desktop\"}",
            "{\"version\":1,\"version\":1,\"defaultProvider\":\"desktop\"}","{\"version\":1,\"defaultProvider\":\"local\"}",
            "{\"version\":1,\"defaultProvider\":\"SYSTEM\"}","{\"version\":1,\"defaultProvider\":null}",
            "{\"version\":1,\"defaultProvider\":\"system\",\"permission\":true}",NativeVoicePreferenceRecord.encode(VoiceInputController.Provider.SYSTEM)+" ".repeat(257))
        for(raw in invalid) {
            val storage=source();storage.write(NativeVoicePreferenceRecord.RECORD,raw);val before=tree(storage);val adapter=NativeEncryptedVoicePreferenceStore(storage)
            assertTrue(runCatching{adapter.load()}.isFailure);assertTrue(runCatching{adapter.save(VoiceInputController.Provider.DESKTOP)}.isFailure)
            assertTrue(runCatching{export(storage)}.isFailure);assertEquals(before,tree(storage));assertEquals(raw,storage.read(NativeVoicePreferenceRecord.RECORD))
        }
        for(suffix in listOf(".bak",".new")) {
            val storage=source(VoiceInputController.Provider.SYSTEM);val sidecar=File(storage.coordinationKey,sha(NativeVoicePreferenceRecord.RECORD.toByteArray())+".bin"+suffix)
            sidecar.writeText("owned pending preference");val before=tree(storage);val adapter=NativeEncryptedVoicePreferenceStore(storage)
            assertTrue(runCatching{adapter.load()}.isFailure);assertTrue(runCatching{adapter.save(VoiceInputController.Provider.DESKTOP)}.isFailure)
            assertTrue(runCatching{export(storage)}.isFailure);assertEquals(before,tree(storage))
        }
    }
    @Test fun actualSettingsBindingReportsFailureInsteadOfPretendingACorruptChoiceWasPersisted() {
        val storage=child("vp-failed");val raw="{\"version\":9,\"defaultProvider\":\"future\"}";storage.write(NativeVoicePreferenceRecord.RECORD,raw);val before=tree(storage)
        val settings=bind(storage);ui.waitUntil(10_000){settings.preferenceFailure};assertFalse(settings.persisted)
        ui.runOnIdle{settings.select(VoiceInputController.Provider.SYSTEM)};ui.waitUntil(10_000){settings.preferenceFailure}
        assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider);assertFalse(settings.persisted);assertEquals(raw,storage.read(NativeVoicePreferenceRecord.RECORD));assertEquals(before,tree(storage))
    }
    @Test fun sourceChoiceMigratesWithoutRetargetingOriginalUnknownOperationsOrStartingVoice()=runBlocking {
        val storage=source(VoiceInputController.Provider.SYSTEM);val before=tree(storage);val original=storage.migrationStreamSnapshot({emptySet()}){it.text.filterKeys{name->name.startsWith("operations:")}}
        val receipt=stage(storage);val candidate=owned.storage.child(ui.activity,"migration-candidates");val candidateBefore=tree(candidate)
        val originalPreference=NativeMigrationStreamCandidate(ui.activity,owned.storage).read(receipt){it.text.getValue(NativeVoicePreferenceRecord.RECORD)}
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        assertEquals(VoiceInputController.Provider.SYSTEM,NativeEncryptedVoicePreferenceStore(current).load())
        assertEquals(originalPreference,current.read(NativeVoicePreferenceRecord.RECORD))
        for((name,raw)in original) {
            val prior=JSONObject(raw).getJSONArray("operations").getJSONObject(0);val imported=JSONObject(current.read(name)!!).getJSONArray("operations").getJSONObject(0)
            for(key in listOf("id","intent","intentSha256","result","phase","submissionAttempted","detached","sourceKey","sourceVersion"))assertEquals(prior.get(key),imported.get(key))
            assertTrue(imported.getBoolean("migrationReadOnly"))
        }
        val settings=bind(current);ui.waitUntil(10_000){settings.persisted};assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider);assertNull(settings.capabilities)
        NativeEncryptedVoicePreferenceStore(current).save(VoiceInputController.Provider.DESKTOP);val after=tree(current)
        assertEquals(generation,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt));assertEquals(after,tree(current));assertEquals(before,tree(storage));assertEquals(candidateBefore,tree(candidate))
        File(owned.folder,"voice-preference-migration.json").writeText(JSONObject().put("onlyUserDefaultMigrated",true).put("originalUnknownOperationsPreserved",true).put("capabilitiesRestored",false)
            .put("permissionChanges",0).put("recordingStarts",0).put("hostCalls",0).put("laterChoicePreserved",true).toString())
    }
    @Test fun destinationExplicitChoiceWinsAndMissingSourceNeverCreatesAnImplicitDesktopRecord()=runBlocking {
        val cases=listOf(null to null,null to VoiceInputController.Provider.SYSTEM,VoiceInputController.Provider.SYSTEM to null,
            VoiceInputController.Provider.SYSTEM to VoiceInputController.Provider.DESKTOP,VoiceInputController.Provider.DESKTOP to VoiceInputController.Provider.SYSTEM)
        for((from,to)in cases) {
            val storage=source(from);val destination=child("vp-dst");val adapter=NativeEncryptedVoicePreferenceStore(destination);if(to!=null)adapter.save(to)
            val targetRaw=destination.read(NativeVoicePreferenceRecord.RECORD);val original=tree(storage);val receipt=stage(storage,destination)
            NativeMigrationActivation(ui.activity,destination).activate(receipt);val current=destination.reopen(ui.activity)
            assertEquals(to?:from,NativeEncryptedVoicePreferenceStore(current).load());if(to!=null)assertEquals(targetRaw,current.read(NativeVoicePreferenceRecord.RECORD))
            if(to==null&&from==null)assertNull(current.read(NativeVoicePreferenceRecord.RECORD));assertEquals(original,tree(storage))
        }
    }
    @Test fun invalidSourceCandidatesCannotReplaceTheOriginalAndFutureRecordNamesAreNotSkipped()=runBlocking {
        val storage=source(VoiceInputController.Provider.SYSTEM);val first=stage(storage);val local=owned.storage.child(ui.activity,"migration-candidates");val before=tree(local)
        val clear=NativeMigrationExport(ui.activity,storage).capture();val original=try{JSONObject(String(clear,Charsets.UTF_8))}finally{clear.fill(0)}
        for(kind in listOf("version","enum","capability","record-v2")) {
            val value=JSONObject(original.toString());val rows=value.getJSONObject("records");val record=JSONObject(rows.getString(NativeVoicePreferenceRecord.RECORD))
            when(kind){"version"->record.put("version",2);"enum"->record.put("defaultProvider","future");"capability"->record.put("onDevice",true);else->Unit}
            if(kind=="record-v2"){rows.remove(NativeVoicePreferenceRecord.RECORD);rows.put("native-voice-preference-v2",record.toString())}else rows.put(NativeVoicePreferenceRecord.RECORD,record.toString())
            val envelope=NativeMigrationCrypto.seal(value.toString().toByteArray(),password)
            assertTrue(kind,runCatching{NativeMigrationCandidateStore(ui.activity,owned.storage).stage(UUID.randomUUID().toString(),envelope,password,first)}.isFailure);assertEquals(before,tree(local))
        }
    }
    @Test fun targetChoiceAndCandidateSurviveInvalidTargetRollbackAndStaleWriter()=runBlocking {
        val storage=source();val receipt=stage(storage);val local=owned.storage.child(ui.activity,"migration-candidates");val candidateBefore=tree(local)
        val invalid="{\"version\":2,\"defaultProvider\":\"system\"}";owned.storage.write(NativeVoicePreferenceRecord.RECORD,invalid);val corruptBefore=tree(owned.storage)
        assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)}.isFailure);assertEquals(corruptBefore,tree(owned.storage));assertEquals(candidateBefore,tree(local))
        // Explicit test-fixture replacement only; the production adapter never repairs this record.
        owned.storage.remove(NativeVoicePreferenceRecord.RECORD);val stale=NativeEncryptedVoicePreferenceStore(owned.storage);stale.save(VoiceInputController.Provider.SYSTEM)
        val before=tree(owned.storage)
        for(point in listOf(1,receipt.recordCount+1,0)) {
            assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt){if(it==point)throw java.io.IOException("owned preference activation interruption")}}.isFailure)
            assertEquals(before,tree(owned.storage));assertEquals(candidateBefore,tree(local))
        }
        NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        assertEquals(VoiceInputController.Provider.SYSTEM,NativeEncryptedVoicePreferenceStore(current).load());val after=tree(current)
        assertTrue(runCatching{stale.save(VoiceInputController.Provider.DESKTOP)}.isFailure);assertEquals(after,tree(current))
    }
    @Test fun stageUserDefaultForIndependentColdReader()=runBlocking {
        NativeEncryptedVoicePreferenceStore(owned.storage).save(VoiceInputController.Provider.SYSTEM);owned.verifyDefault()
        val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"voice-preference-cold-writer.json").writeText(JSONObject().put("version",1).put("kind","voice-preference-cold-v1").put("namespace",namespace)
            .put("writerPid",android.os.Process.myPid()).put("defaultProvider","system").put("ciphertexts",JSONObject(tree(owned.storage))).toString())
    }
    @Test fun readOriginalUserDefaultInIndependentColdReader()=runBlocking {
        val name=InstrumentationRegistry.getArguments().getString("voicePreferenceFixture")?:error("Original preference fixture required")
        require(name.matches(Regex("voice-default-cold-[a-f0-9]{16}\\.json")));val base=File(ui.activity.filesDir,"acceptance").canonicalFile;val file=File(base,name)
        require(file.canonicalFile==File(base,name)&&file.isFile&&file.length()<=16384)
        val fixture=StrictMigrationJson.parse(file.readBytes());require(fixture.keys().asSequence().toSet()==setOf("version","kind","namespace","writerPid","defaultProvider","ciphertexts"))
        require(fixture.get("version")==1&&fixture.getString("kind")=="voice-preference-cold-v1"&&fixture.getString("defaultProvider")=="system")
        assertNotEquals(fixture.getInt("writerPid"),android.os.Process.myPid());val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        val storage=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val before=tree(storage);val expected=fixture.getJSONObject("ciphertexts")
        assertEquals(expected.keys().asSequence().associateWith{expected.getString(it)},before)
        assertEquals(VoiceInputController.Provider.SYSTEM,NativeEncryptedVoicePreferenceStore(storage).load());val settings=bind(storage)
        ui.waitUntil(10_000){settings.persisted};assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider);assertEquals(before,tree(storage))
        File(owned.folder,"voice-preference-cold-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",android.os.Process.myPid())
            .put("originalCiphertextsPreserved",true).put("systemDefaultRead",true).put("capabilityCalls",0).put("recordingStarts",0).put("hostCalls",0).toString())
    }
}
