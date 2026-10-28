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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in controlled process death before publication; never touches an actual migrated installation. */
class NativeMigrationInterruptedTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val args get()=InstrumentationRegistry.getArguments()
    private fun runId()=args.getString("publicationInterruptRun").also {require(it?.matches(Regex("publication-interrupt-[a-f0-9]{24}"))==true)}!!
    @Before fun setup(){require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance");ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-interrupted")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun rootFiles(store: NativeSessionStorage)=File(store.coordinationKey).let{root->JSONObject().apply{root.listFiles().orEmpty().filter{it.isFile}.sortedBy{it.name}.forEach{put(it.name,sha(it.readBytes()))}}}
    private fun fixtureFile()=File(ui.activity.filesDir,"acceptance/${runId()}.json")
    @Test fun pauseAfterFirstStagedRecordUntilControllerEndsOnlyThisOwnedProcess(){
        val store=owned.storage;store.write("metadata","original active draft 100% %@ 😀");store.write("submission:owned","original immutable input");val before=rootFiles(store)
        val id=UUID.randomUUID().toString();val digest=sha("owned interrupted candidate".toByteArray())
        store.publishMigration(id,digest,mapOf("metadata" to "uncommitted replacement","submission:owned" to "uncommitted replacement input")){step->
            if(step==1){
                owned.verifyDefault();val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
                val folder=File(store.coordinationKey).listFiles().orEmpty().single{it.isDirectory&&it.name.startsWith("migration-generation-")};val staged=folder.listFiles().orEmpty().single();assertTrue(staged.isFile)
                fixtureFile().writeText(JSONObject().put("version",1).put("namespace",namespace).put("writerPid",Process.myPid()).put("candidateId",id).put("originalRootCiphertext",before)
                    .put("stagedFolder",folder.name).put("stagedFile",staged.name).put("stagedSha256",sha(staged.readBytes())).put("published",false).toString())
                assertTrue("Controller must stop only this exact owned process before the bounded pause expires",CountDownLatch(1).await(90,TimeUnit.SECONDS))
            }
        }
        fail("Interrupted writer must not reach publication")
    }
    @Test fun freshProcessReadsOriginalGenerationAndCleansOnlyTheExactUnpublishedFixture(){
        val fixture=JSONObject(fixtureFile().readText());require(fixture.getInt("version")==1&&!fixture.getBoolean("published"));assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        val store=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");assertEquals("original active draft 100% %@ 😀",store.read("metadata"));assertEquals("original immutable input",store.read("submission:owned"))
        assertEquals(fixture.getJSONObject("originalRootCiphertext").toString(),rootFiles(store).toString())
        assertFalse(File(store.coordinationKey,sha("migration-active-generation-v1".toByteArray())+".bin").exists())
        val name=fixture.getString("stagedFolder");require(name.matches(Regex("migration-generation-[a-f0-9-]{36}")))
        val root=File(store.coordinationKey);val folder=File(root,name);require(folder.canonicalFile==File(root.canonicalFile,name))
        val stagedName=fixture.getString("stagedFile");require(stagedName.matches(Regex("[a-f0-9]{64}\\.bin")))
        val staged=File(folder,stagedName);require(staged.canonicalFile==File(folder.canonicalFile,stagedName));assertEquals(listOf(stagedName),folder.listFiles()!!.map{it.name});assertEquals(fixture.getString("stagedSha256"),sha(staged.readBytes()))
        // Remove exactly this test's one uncommitted encrypted record, after the original-state proof is complete.
        assertTrue(staged.delete());assertTrue(folder.delete());assertEquals(fixture.getJSONObject("originalRootCiphertext").toString(),rootFiles(store).toString())
        assertEquals("original active draft 100% %@ 😀",store.reopen(ui.activity).read("metadata"))
        File(owned.folder,"migration-interrupted-reader.json").writeText(JSONObject().put("differentProcess",true).put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid()).put("originalGenerationRemainsActive",true)
            .put("allOriginalCiphertextPreserved",true).put("noPublicationPointer",true).put("onlyExactOwnedUncommittedFixtureCleaned",true).put("remoteWrites",0).toString())
    }
}
