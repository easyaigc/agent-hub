package com.agenthub.android

import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.security.MessageDigest
import java.util.UUID

/** Actual encrypted journal/index round trips, legacy snapshots and immutable per-request quota. */
class NativeCapturedQuotaTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup() {ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"captured-quota")}}
    @After fun finish() {if(::owned.isInitialized)ui.runOnIdle {owned.session.leaveForeground();owned.verifyDefault();assertTrue(owned.session.operationRecords.isEmpty())}}
    private fun destination(maximum: Int)=AttachmentImportDestination("owned-quota",UUID.randomUUID().toString(),DraftDestination("owned-quota",1),maximum=maximum)
    private fun sources(count: Int)=(1..count).map {Uri.parse("content://quota-source/item/$it")}
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun requestRow(id: String,d: AttachmentImportDestination,count: Int)=JSONObject().put("id",id).put("key",d.key).put("generation",d.generation)
        .put("selection",d.presentation.selection).put("requestId",d.requestId).put("errorGeneration",1).put("phase","selected").put("sources",JSONArray(sources(count).map {it.toString()})).put("releaseReadGrants",JSONArray())
    @Test fun newFiveIsDurableAndCannotBeChangedDuringRecordUpdate()=runBlocking {
        val store=owned.storage.child(ui.activity,"quota-docs");val journal=NativeDocumentJournal(store);val d=destination(5);val request=NativePhotoRequest(d,1,false)
        val original=journal.prepare(request);val restored=NativeDocumentJournal(store).records().single();assertEquals(original,restored);assertEquals(5,restored.destination.maximum)
        val raw=store.read("document-requests-v1");assertEquals(2,JSONObject(raw!!).getInt("version"))
        assertTrue(runCatching {journal.update(original.id){it.copy(destination=it.destination.copy(maximum=10))}}.isFailure);assertEquals(raw,store.read("document-requests-v1"))
        assertTrue(runCatching {journal.update(original.id){it.copy(phase="selected",sources=sources(6).map {it.toString()})}}.isFailure);assertEquals(raw,store.read("document-requests-v1"))
    }
    @Test fun versionOneTenSurvivesNewWriterAndKeepsOriginalIdsAndSources()=runBlocking {
        val store=owned.storage.child(ui.activity,"quota-docs");val journal=NativeDocumentJournal(store);val d=destination(10);val id=UUID.randomUUID().toString();val row=requestRow(id,d,10)
        store.write("document-requests-v1",JSONObject().put("version",1).put("revision",0).put("records",JSONArray().put(row)).toString())
        val original=journal.restore().single();assertEquals(10,original.destination.maximum)
        journal.update(id){it.copy(phase="ready")};val after=NativeDocumentJournal(store).records().single();assertEquals(id,after.id);assertEquals(original.destination,after.destination);assertEquals(original.sources,after.sources)
        assertEquals(2,JSONObject(store.read("document-requests-v1")!!).getInt("version"));assertEquals(10,after.destination.maximum)
    }
    @Test fun malformedQuotaAndUnsupportedVersionsPreserveTheStoredReceipt()=runBlocking {
        val store=owned.storage.child(ui.activity,"quota-docs");val journal=NativeDocumentJournal(store)
        for(value in listOf<Any>(0,11,5.0,"5",true)) {
            val row=requestRow(UUID.randomUUID().toString(),destination(5),1).put("maximum",value)
            val encoded=JSONObject().put("version",2).put("revision",0).put("records",JSONArray().put(row)).toString()
            val raw=if(value is Double)encoded.replace("\"maximum\":5","\"maximum\":5.0") else encoded;store.write("document-requests-v1",raw)
            assertTrue(runCatching {journal.records()}.isFailure);assertEquals(raw,store.read("document-requests-v1"))
        }
        val raw=JSONObject().put("version",3).put("revision",0).put("records",JSONArray()).toString();store.write("document-requests-v1",raw);assertTrue(runCatching {journal.records()}.isFailure);assertEquals(raw,store.read("document-requests-v1"))
    }
    @Test fun newInboxRejectsSixBeforeReadAndRetainsFiveWhenSameIdIsRetried()=runBlocking {
        val store=NativeAttachmentInboxStore(owned.storage.child(ui.activity,"quota-inbox"));var reads=0;val resolver=ui.activity.contentResolver
        assertTrue(runCatching {store.stage(UUID.randomUUID().toString(),sources(6),resolver){reads++;LocalAttachmentInput("test.txt","text/plain",byteArrayOf(1))}}.isFailure)
        assertEquals(0,reads);assertTrue(store.snapshot().records.isEmpty())
        val id=UUID.randomUUID().toString();val first=store.stage(id,sources(5),resolver){reads++;LocalAttachmentInput(it.lastPathSegment+".txt","text/plain",byteArrayOf(1))}
        assertEquals(5,first.maximum);assertEquals(5,reads)
        val again=store.stage(id,sources(5),resolver,maximum=10){error("Original successful input is never replayed")};assertEquals(first,again);assertEquals(first.slots.map {it.id},again.slots.map {it.id})
        assertTrue(runCatching {store.bind(first.copy(maximum=10),InboxBinding("owned",UUID.randomUUID().toString()))}.isFailure)
    }
    @Test fun oldInboxTenCanBeBoundAndQueriedWithoutReplayingOriginalPayloads()=runBlocking {
        val storage=owned.storage.child(ui.activity,"quota-inbox");val id=UUID.randomUUID().toString();val uris=sources(10);val fileIds=(1..10).map {UUID.randomUUID().toString()}
        val slots=JSONArray(fileIds.map {fileId->JSONObject().put("id",fileId).put("file",CreationFile(fileId,"legacy.txt","text/plain",1,digest(byteArrayOf(1))).body())})
        val row=JSONObject().put("id",id).put("phase","pending").put("sourceHash",digest(JSONArray(uris.map {it.toString()}).toString().toByteArray())).put("slots",slots).put("binding",JSONObject.NULL)
        storage.write("external-inbox-index-v1",JSONObject().put("version",1).put("revision",0).put("records",JSONArray().put(row)).toString())
        val store=NativeAttachmentInboxStore(storage);val original=store.snapshot().records.single();assertEquals(10,original.maximum)
        val bound=store.bind(original,InboxBinding("owned",UUID.randomUUID().toString()));assertEquals(10,bound.maximum)
        val queried=store.stage(id,uris,ui.activity.contentResolver){error("Legacy pending bytes must not be read again")};assertEquals(bound,queried);assertEquals(fileIds,queried.slots.map {it.id})
        assertEquals(2,JSONObject(storage.read("external-inbox-index-v1")!!).getInt("version"))
    }
}
