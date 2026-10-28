package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Authenticated malicious archives must fail before any active publication or original candidate replacement. */
class NativeMigrationMetadataTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var store: NativeMigrationCandidateStore
    private val password="owned archive password 100% %@"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-metadata");store=NativeMigrationCandidateStore(ui.activity,owned.storage)}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun body()=NativeMigrationCandidateTest().syntheticPayload()
    private fun metadata(body: JSONObject)=JSONObject(body.getJSONObject("records").getString("metadata"))
    private fun stage(body: JSONObject,replacing: NativeMigrationCandidateStore.Receipt?=null)=store.stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(body.toString().toByteArray(),password),password,replacing)
    private fun cipher(): ByteArray {
        val name=MessageDigest.getInstance("SHA-256").digest("candidate-v1".toByteArray()).joinToString(""){"%02x".format(it)}+".bin"
        return File(owned.storage.child(ui.activity,"migration-candidates").coordinationKey,name).readBytes()
    }
    private fun rejects(changes: List<(JSONObject)->Unit>){
        val originalBody=body();val original=stage(originalBody);val before=cipher();val records=store.read(original)
        for(change in changes){val candidate=body();val meta=metadata(candidate);change(meta);candidate.getJSONObject("records").put("metadata",meta.toString())
            assertTrue("An authenticated malformed restore field must reject before replacing its predecessor",runCatching{stage(candidate,original)}.exceptionOrNull() is NativeMigrationCandidateStore.InvalidCandidate)
            assertArrayEquals(before,cipher());assertEquals(records,store.read(original))
        }
    }
    @Test fun malformedRestoreFieldsCannotReplaceOriginalCandidateEnvelope(){rejects(listOf(
        {it.getJSONObject("drafts").put("owned-invalid",7)},
        {it.getJSONObject("selectedThreads").put("owned-invalid",true)},
        {it.getJSONObject("expanded").put("owned-invalid","not an array")},
        {it.put("sidebar",JSONObject().put("owned",JSONObject().put("recent","true").put("projects",true).put("lists",JSONArray())))},
        {it.put("generation",JSONObject().put("owned","invalid settings"))},
        {it.put("attachmentImportGenerations",JSONObject().put("owned","invalid generation"))},
        {it.put("newProjects",JSONObject().put("owned",false))},
        {it.put("dismissedReceiptFailures",JSONObject().put("owned",JSONObject()))}
    ))}
    @Test fun unsupportedMetadataAndCoercedClientFieldsCannotBeSilentlyDiscarded(){rejects(listOf(
        {it.put("unreviewedFutureState",JSONObject().put("receipt","original"))},
        {it.put("language","unreviewed-language")},
        {it.getJSONArray("clients").getJSONObject(0).put("requiresReadTarget","true")},
        {it.getJSONArray("clients").getJSONObject(0).put("endpoint",7)},
        {it.getJSONArray("clients").getJSONObject(0).put("readerId",false)},
        {it.getJSONArray("clients").getJSONObject(0).put("codexDirect","discarded pairing")},
        {it.getJSONArray("clients").getJSONObject(0).put("storageIdentity","discarded original keys")},
        {it.getJSONArray("clients").getJSONObject(0).put("unexpectedCredential","must reject")}
    ))}
    @Test fun countAndNestedSettingBoundsPreserveOriginalCandidate(){rejects(listOf(
        {it.getJSONObject("drafts").put("owned","x".repeat(100001))},
        {it.put("generation",JSONObject().put("owned",JSONObject().put("effort",true)))},
        {it.put("generationPreferences",JSONObject().put("owned",JSONObject().put("unreviewedSetting","original")))},
        {it.put("replyModes",JSONObject().put("owned","unexpected mode"))},
        {it.put("sidebar",JSONObject().put("owned",JSONObject().put("recent",true).put("projects",true).put("lists",JSONArray(listOf("same","same")))))},
        {it.getJSONObject("expanded").put("owned",JSONArray((1..10001).map{n->"item$n"}))}
    ))}
    @Test fun knownOptionalMetadataAndOriginalRecordTextsRemainUnnormalized(){
        val value=body();val meta=metadata(value)
        meta.getJSONObject("selectedThreads").put("owned-new-chat","")
        meta.put("attachmentImportGenerations",JSONObject().put("owned",UUID.randomUUID().toString())).put("newProjects",JSONObject().put("owned",JSONObject.NULL))
            .put("sidebar",JSONObject().put("owned",JSONObject().put("recent",true).put("projects",false).put("lists",JSONArray(listOf("one"))).put("pinnedSection",true).put("recentSection",false)))
            .put("generation",JSONObject().put("owned",JSONObject().put("model","原 model 100% %@").put("effort","xhigh").put("permissionMode","read-only").put("speed",JSONObject.NULL)))
            .put("generationPreferences",JSONObject()).put("replyModes",JSONObject().put("owned","queue")).put("dismissedReceiptFailures",JSONObject().put("owned",JSONArray(listOf("original receipt"))))
        val exact=" \n"+meta.toString()+"\n ";value.getJSONObject("records").put("metadata",exact);val receipt=stage(value);assertEquals(exact,store.read(receipt).getValue("metadata"))
        val before=cipher();assertEquals(receipt,store.stage(receipt.id,NativeMigrationCrypto.seal(value.toString().toByteArray(),password),password));assertArrayEquals(before,cipher())
    }
    @Test fun actualSessionSnapshotStagesWithoutChangingActiveInstallation()=runBlocking{
        ui.runOnIdle{owned.session.restore()};ui.waitUntil(10_000){!owned.session.restoring};ui.runOnIdle{owned.session.setDemoMode(true);owned.session.changeLanguage("en");owned.session.changeAppearance("dark");owned.session.text="Local draft 100% %@ 😀"};owned.storage.awaitQueuedWrites()
        val raw=owned.storage.read("metadata")!!;val value=JSONObject().put("format","agent-hub/android-migration").put("version",1).put("applicationId",ui.activity.packageName).put("records",JSONObject().put("metadata",raw))
        val receipt=stage(value);assertEquals(raw,store.read(receipt).getValue("metadata"));assertEquals(raw,owned.storage.read("metadata"));assertEquals("Local draft 100% %@ 😀",owned.session.text)
    }
    @Test fun capturedLegacyTenFilesRemainValidWhileAnEleventhCannotReplaceTheirCandidate(){
        val value=body();val meta=metadata(value);val records=value.getJSONObject("records");val key=meta.getJSONObject("draftFiles").keys().next()
        val files=meta.getJSONObject("draftFiles").getJSONArray(key);val original=files.getJSONObject(0)
        val payload=JSONObject(records.getString("draft-file:"+original.getString("id"))).getString("bytes")
        fun append(index: Int){val file=JSONObject(original.toString()).put("id",UUID.randomUUID().toString()).put("name","legacy-$index.txt");files.put(file);records.put("draft-file:"+file.getString("id"),JSONObject().put("file",file).put("bytes",payload).toString())}
        repeat(9){append(it+1)};records.put("metadata",meta.toString());val receipt=stage(value);val before=cipher()
        assertEquals(10,JSONObject(store.read(receipt).getValue("metadata")).getJSONObject("draftFiles").getJSONArray(key).length())
        append(11);records.put("metadata",meta.toString());assertTrue(runCatching{stage(value,receipt)}.exceptionOrNull() is NativeMigrationCandidateStore.InvalidCandidate)
        assertArrayEquals(before,cipher());assertEquals(10,JSONObject(store.read(receipt).getValue("metadata")).getJSONObject("draftFiles").getJSONArray(key).length())
    }
}
