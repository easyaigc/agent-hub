package com.agenthub.android

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Opt-in real process boundary for the importer and its original idempotent receipt. */
class NativeMigrationActivationColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-activation-cold")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    @Test fun activateOriginalCandidateForSeparateProcessRead()=runBlocking {
        val value=MigrationActivationFixtures.payload();val receipt=MigrationActivationFixtures.stage(ui.activity,owned.storage,value)
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        val metadata=current.metadata()!!;val target=metadata.getJSONObject("drafts").keys().next();metadata.getJSONObject("drafts").put(target,"edited after import 100% %@ 😀");current.write("metadata",metadata.toString())
        val records=value.getJSONObject("records").keys().asSequence().associateWith{current.read(it)!!}
        val operationName=records.keys.single{it.startsWith("operations:")};val row=NativeOperationJournal(current).records(operationName.removePrefix("operations:")).single();assertTrue(row.migrationReadOnly)
        owned.verifyDefault();val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"migration-activation-writer.json").writeText(JSONObject().put("version",1).put("namespace",namespace).put("writerPid",Process.myPid()).put("candidateId",receipt.id).put("candidateSha256",receipt.sha256).put("recordCount",receipt.recordCount)
            .put("generation",generation).put("records",JSONObject(records)).put("target",target).put("originalCiphertext",JSONObject(MigrationActivationFixtures.tree(current))).toString())
    }
    @Test fun readOriginalImportReceiptAndObserveOnlyRowsInFreshProcess()=runBlocking {
        val name=InstrumentationRegistry.getArguments().getString("migrationActivationFixture")?:error("Original import fixture required");require(name.matches(Regex("activation-cold-[a-f0-9]{16}\\.json")))
        val fixture=JSONObject(File(ui.activity.filesDir,"acceptance/$name").readText());require(fixture.getInt("version")==1);assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        val current=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val before=MigrationActivationFixtures.tree(current);assertEquals(fixture.getJSONObject("originalCiphertext").toString(),JSONObject(before).toString())
        val expected=fixture.getJSONObject("records");for(key in expected.keys())assertEquals(expected.getString(key),current.read(key))
        val receipt=NativeMigrationCandidateStore.Receipt(fixture.getString("candidateId"),fixture.getString("candidateSha256"),fixture.getInt("recordCount"))
        assertEquals(fixture.getString("generation"),NativeMigrationActivation(ui.activity,current).activate(receipt))
        val row=NativeOperationJournal(current).records(expected.keys().asSequence().single{it.startsWith("operations:")}.removePrefix("operations:")).single();val client=CodexConnectionVault(current).clients().single()
        assertTrue(row.migrationReadOnly);assertFalse(row.replyRecovery!!.mayRetry(client,row));assertEquals("a".repeat(43),current.token(client.id))
        assertEquals("edited after import 100% %@ 😀",current.metadata()!!.getJSONObject("drafts").getString(fixture.getString("target")));assertEquals(before,MigrationActivationFixtures.tree(current))
        File(owned.folder,"migration-activation-reader.json").writeText(JSONObject().put("differentProcess",true).put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid()).put("sameDeviceKeystore",true)
            .put("originalImportReceiptMatched",true).put("allImportedCiphertextUnchanged",true).put("laterDraftPreserved",true).put("importedOriginalObserveOnly",true).put("credentialMatched",true).put("remoteWrites",0).toString())
    }
}
