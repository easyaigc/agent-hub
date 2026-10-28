package com.agenthub.android

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Same-device encrypted generation survives process exit; no remote/client execution interface. */
class NativeMigrationPublicationColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-publication-cold")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->JSONObject().apply{root.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(root).path}.forEach{put(it.relativeTo(root).path,sha(it.readBytes()))}}}
    @Test fun publishOwnedGenerationForSeparateProcessRead(){
        val store=owned.storage;store.write("metadata","previous installation");store.write("previous-only","previous local note")
        val id=UUID.randomUUID().toString();val digest=sha("original owned candidate".toByteArray());val records=mapOf("metadata" to "imported draft 100% %@ 😀","submission:owned" to "original immutable unknown input","draft-file:owned" to "original attachment bytes")
        val generation=store.publishMigration(id,digest,records);val current=store.reopen(ui.activity);current.write("new-edit","edit after import")
        owned.verifyDefault();val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"migration-publication-writer.json").writeText(JSONObject().put("version",1).put("namespace",namespace).put("writerPid",Process.myPid()).put("candidateId",id).put("candidateSha256",digest)
            .put("generation",generation).put("records",JSONObject(records)).put("originalCiphertext",tree(store)).toString())
    }
    @Test fun readSamePublishedGenerationAndReceiptInFreshProcessWithoutOverwritingNewEdit(){
        val name=InstrumentationRegistry.getArguments().getString("migrationPublicationFixture")?:error("Original publication fixture required");require(name.matches(Regex("publication-cold-[a-f0-9]{16}\\.json")))
        val fixture=JSONObject(File(ui.activity.filesDir,"acceptance/$name").readText());require(fixture.getInt("version")==1);assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        val store=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val before=tree(store);assertEquals(fixture.getJSONObject("originalCiphertext").toString(),before.toString())
        val expected=fixture.getJSONObject("records");val records=expected.keys().asSequence().associateWith{expected.getString(it)}
        for((name,text)in records)assertEquals(text,store.read(name));assertNull(store.read("previous-only"));assertEquals("edit after import",store.read("new-edit"))
        assertEquals(fixture.getString("generation"),store.publishMigration(fixture.getString("candidateId"),fixture.getString("candidateSha256"),records));assertEquals("edit after import",store.read("new-edit"));assertEquals(before.toString(),tree(store).toString())
        File(owned.folder,"migration-publication-reader.json").writeText(JSONObject().put("differentProcess",true).put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid()).put("sameDeviceKeystore",true)
            .put("publishedGenerationAndOriginalReceiptMatched",true).put("originalCiphertextUnchanged",true).put("laterEditPreserved",true).put("previousOnlyRecordNotExposed",true).put("remoteWrites",0).toString())
    }
}
