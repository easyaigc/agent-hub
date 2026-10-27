package com.agenthub.android

import android.net.Uri
import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Different native processes, same installation Keystore; synthetic owned local receipts only. */
class NativeCapturedQuotaColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup() {ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"captured-quota-cold")}}
    @After fun finish() {if(::owned.isInitialized)ui.runOnIdle {owned.session.leaveForeground();owned.verifyDefault();assertTrue(owned.session.operationRecords.isEmpty())}}
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun sources(count: Int)=(1..count).map {Uri.parse("content://owned-cold-quota/item/$it")}
    private fun bytes(position: Int)="Original owned payload $position 100% %@ 😀\r\n".toByteArray()
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->JSONObject().apply {root.walkTopDown().filter {it.isFile}.sortedBy {it.relativeTo(root).path}.forEach {put(it.relativeTo(root).path,hash(it.readBytes()))}}}
    @Test fun writeOriginalNewFiveAndLegacyTenForSeparateProcessRead()=runBlocking {
        val expected=JSONObject()
        for(maximum in listOf(5,10)) {
            val docs=owned.storage.child(ui.activity,"cold-doc-$maximum");val journal=NativeDocumentJournal(docs)
            val key="owned-cold-target-$maximum";val d=AttachmentImportDestination(key,UUID.randomUUID().toString(),DraftDestination(key,1),maximum=maximum)
            val original=journal.prepare(NativePhotoRequest(d,1,false));journal.update(original.id){it.copy(phase="selected",sources=sources(maximum).map(Uri::toString))}
            if(maximum==10) {val root=JSONObject(docs.read("document-requests-v1")!!);root.put("version",1);root.getJSONArray("records").getJSONObject(0).remove("maximum");docs.write("document-requests-v1",root.toString())}
            val inbox=owned.storage.child(ui.activity,"cold-inbox-$maximum");val store=NativeAttachmentInboxStore(inbox);var reads=0
            val batch=store.stage(UUID.randomUUID().toString(),sources(maximum),ui.activity.contentResolver,maximum=maximum) {uri->reads++;LocalAttachmentInput("owned-${uri.lastPathSegment}.txt","text/plain",bytes(uri.lastPathSegment!!.toInt()))}
            assertEquals(maximum,reads);val binding=InboxBinding(key,d.generation);val bound=store.bind(batch,binding)
            if(maximum==10) {val root=JSONObject(inbox.read("external-inbox-index-v1")!!);root.put("version",1);root.getJSONArray("records").getJSONObject(0).remove("maximum");inbox.write("external-inbox-index-v1",root.toString())}
            expected.put(maximum.toString(),JSONObject().put("requestId",original.id).put("destinationRequestId",d.requestId).put("key",key).put("generation",d.generation)
                .put("batchId",bound.id).put("fileIds",JSONArray(bound.readyFiles.map {it.id})).put("docCiphertext",tree(docs)).put("inboxCiphertext",tree(inbox)))
        }
        owned.verifyDefault();val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"captured-quota-cold-writer.json").writeText(JSONObject().put("version",1).put("namespace",namespace).put("writerPid",Process.myPid()).put("expected",expected).toString())
    }
    @Test fun readOriginalEncryptedQuotasIdsAndBytesInFreshProcessWithoutSourceReplay()=runBlocking {
        val name=InstrumentationRegistry.getArguments().getString("capturedQuotaFixture")?:error("Original owned cold fixture required")
        require(name.matches(Regex("quota-cold-[a-f0-9]{16}\\.json")))
        val fixture=JSONObject(File(ui.activity.filesDir,"acceptance/$name").readText());require(fixture.getInt("version")==1)
        val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace);assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        val original=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace")
        var reads=0
        for(maximum in listOf(5,10)) {
            val expected=fixture.getJSONObject("expected").getJSONObject(maximum.toString())
            val docs=original.child(ui.activity,"cold-doc-$maximum");val inbox=original.child(ui.activity,"cold-inbox-$maximum")
            assertEquals(expected.getJSONObject("docCiphertext").toString(),tree(docs).toString());assertEquals(expected.getJSONObject("inboxCiphertext").toString(),tree(inbox).toString())
            val record=NativeDocumentJournal(docs).restore().single();assertEquals(maximum,record.destination.maximum);assertEquals(expected.getString("requestId"),record.id)
            assertEquals(expected.getString("destinationRequestId"),record.destination.requestId);assertEquals(expected.getString("generation"),record.destination.generation);assertEquals(expected.getString("key"),record.destination.key)
            assertEquals("selected",record.phase);assertEquals(sources(maximum).map(Uri::toString),record.sources)
            val store=NativeAttachmentInboxStore(inbox);val batch=store.snapshot().records.single();assertEquals(maximum,batch.maximum);assertEquals(expected.getString("batchId"),batch.id)
            assertEquals(expected.getJSONArray("fileIds").toString(),JSONArray(batch.readyFiles.map {it.id}).toString());assertEquals(InboxBinding(record.destination.key,record.destination.generation),batch.binding)
            val queried=store.stage(batch.id,sources(maximum),ui.activity.contentResolver){reads++;error("Original pending sources must not be read again")};assertEquals(batch,queried)
            for((index,file)in batch.readyFiles.withIndex()) {val payload=store.payload(batch,file);assertEquals(file.id,payload.fixedId);assertArrayEquals(bytes(index+1),payload.bytes)}
            assertEquals(expected.getJSONObject("docCiphertext").toString(),tree(docs).toString());assertEquals(expected.getJSONObject("inboxCiphertext").toString(),tree(inbox).toString())
        }
        assertEquals(0,reads)
        File(owned.folder,"captured-quota-cold-reader.json").writeText(JSONObject().put("differentProcess",true).put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid())
            .put("sameDeviceKeystoreRead",true).put("newFiveAndLegacyTenPreserved",true).put("originalIdsAndTargetPreserved",true).put("originalEncryptedBytesUnchanged",true).put("originalPayloadBytesPreserved",true).put("sourceReads",reads).put("noUploadOrSubmit",true).toString())
    }
}
