package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Actual audit-only codec/common payload graph. JVM auxiliary; no OS request, grant or native owner. */
class NativeMigrationSelectionContractTest {
    private val kind=NativeMigrationSelectionJournal.Kind.DOCUMENT
    private fun id(name: String)=InboxContractFixture.id("selection-$name")
    private fun row(phase: String="selected",count: Int=1,maximum: Int=10)=JSONObject()
        .put("id",id(phase)).put("key","opaque original target 😀").put("generation",id("generation"))
        .put("selection",7).put("requestId",id("request")).put("errorGeneration",Long.MIN_VALUE)
        .put("phase",phase).put("sources",JSONArray(List(count){"content://owned.invalid/original-$it?audit=%E4%B8%AD"}))
        .put("releaseReadGrants",JSONArray()).put("maximum",maximum)
    private fun journal(rows: List<JSONObject>,version: Int=2)=JSONObject().put("version",version).put("revision",19L).put("records",JSONArray(rows))
    private fun records(raw: String)=NativeMigrationStreamRecords(mapOf(NativeMigrationSelectionJournal.RECORD to raw),emptyMap(),4)
    private fun reject(body: JSONObject,selected: NativeMigrationSelectionJournal.Kind=kind) {
        assertTrue(runCatching {NativeMigrationSelectionJournal.decode(body.toString(),selected)}.isFailure)
    }
    @Test fun allElevenPhasesRetainExactOriginalSignedFieldsAndHistoricalMaximum() {
        for(phase in listOf("permission","prepared","launched","selected","ready","detached","hold-selected","held","consumed","retired","failed")) {
            val empty=phase in setOf("permission","prepared","launched","detached")
            for(version in listOf(1,2)) {
                val count=if(empty)0 else 1
                val r=row(phase,count,if(version==1)10 else 5);if(version==1)r.remove("maximum")
                val raw="\n "+journal(listOf(r),version).toString()+"\n"
                val audit=NativeMigrationSelectionJournal.decode(raw,NativeMigrationSelectionJournal.Kind.CAMERA)
                assertEquals(raw,audit.raw);assertEquals(phase,audit.requests.single().phase)
                assertEquals(Long.MIN_VALUE,audit.requests.single().errorGeneration);assertEquals(if(version==1)10 else 5,audit.requests.single().maximum)
                assertEquals(count,audit.requests.single().sources.size)
                if(phase in setOf("permission","detached","hold-selected","held"))reject(journal(listOf(r),version))
            }
        }
        val ten=row("ready",10);ten.remove("maximum")
        assertEquals(10,NativeMigrationSelectionJournal.decode(journal(listOf(ten),1).toString(),kind).requests.single().sources.size)
        reject(journal(listOf(row("ready",10))),NativeMigrationSelectionJournal.Kind.PHOTO)
    }
    @Test fun unknownFieldsVersionsCoercedNumbersDuplicateIdsAndGrantChangesReject() {
        fun mutate(work: (JSONObject)->Unit) {val value=journal(listOf(row()));work(value);reject(value)}
        mutate {it.put("version",3)};mutate {it.put("revision","19")};mutate {it.put("extra",true)}
        mutate {it.getJSONArray("records").getJSONObject(0).put("maximum",6)}
        mutate {it.getJSONArray("records").getJSONObject(0).put("selection",1.5)}
        mutate {it.getJSONArray("records").getJSONObject(0).put("errorGeneration","-1")}
        mutate {it.getJSONArray("records").getJSONObject(0).put("sources",JSONArray(listOf("https://owned.invalid/original")))}
        mutate {it.getJSONArray("records").getJSONObject(0).put("sources",JSONArray(listOf("content:///missing-authority")))}
        mutate {it.getJSONArray("records").getJSONObject(0).put("releaseReadGrants",JSONArray(listOf("content://owned.invalid/different")))}
        reject(journal(listOf(row(),row())));reject(journal(listOf(row("prepared",0),row("launched",0))))
        val duplicate="{\"version\":2,\"version\":1,\"revision\":0,\"records\":[]}"
        assertTrue(runCatching {NativeMigrationSelectionJournal.decode(duplicate,kind)}.isFailure)
    }
    internal fun linked(): Pair<NativeMigrationStreamRecords,NativeMigrationStreamRecords> {
        val vector=InboxContractFixture.all();val index=JSONObject(vector.text.getValue(NativeMigrationInboxGraph.INDEX))
        val rows=index.getJSONArray("records");val requests=mutableListOf<JSONObject>()
        for(n in 0 until rows.length()) {
            val batch=rows.getJSONObject(n);val phase=when(batch.getString("phase")){"pending"->"ready";"receiving"->"selected";else->batch.getString("phase")}
            val request=row(phase,batch.getJSONArray("slots").length(),batch.getInt("maximum")).put("id",batch.getString("id"))
            batch.put("binding",JSONObject().put("key",request.getString("key")).put("generation",request.getString("generation")))
            requests+=request
        }
        return records(journal(requests).toString()) to NativeMigrationStreamRecords(vector.text+mapOf(NativeMigrationInboxGraph.INDEX to index.toString()),vector.binary,4)
    }
    @Test fun sharedPayloadGraphRetainsPartialCleanupAndExplainedExpiredConsumedReceipts() {
        val (source,payload)=linked();val original=source.text.getValue(NativeMigrationSelectionJournal.RECORD)
        val audit=NativeMigrationSelectionGraph.validate(kind,source,payload);assertEquals(original,audit.originalJournal!!.raw);assertTrue(audit.expiredConsumedIds.isEmpty())
        val body=JSONObject(original);val rows=body.getJSONArray("records");val consumed=rows.getJSONObject(rows.length()-1).getString("id");rows.remove(rows.length()-1)
        val expired=NativeMigrationSelectionGraph.validate(kind,records(body.toString()),payload)
        assertEquals(setOf(consumed),expired.expiredConsumedIds)
        // The existing common validator still sees every original byte/thumbnail and partial record.
        NativeMigrationInboxGraph.validate(payload.text,payload.binary)
    }
    @Test fun nonterminalOrphanBindingMismatchMissingReadyBytesAndCameraRawStayClosed() {
        val (source,payload)=linked();val original=source.text.getValue(NativeMigrationSelectionJournal.RECORD)
        val missing=JSONObject(original);missing.getJSONArray("records").remove(0)
        assertTrue(runCatching {NativeMigrationSelectionGraph.validate(kind,records(missing.toString()),payload)}.isFailure)
        val mismatch=JSONObject(original);mismatch.getJSONArray("records").getJSONObject(0).put("key","different target")
        assertTrue(runCatching {NativeMigrationSelectionGraph.validate(kind,records(mismatch.toString()),payload)}.isFailure)
        val changedLimit=JSONObject(original);changedLimit.getJSONArray("records").getJSONObject(0).put("maximum",10)
        assertTrue(runCatching {NativeMigrationSelectionGraph.validate(kind,records(changedLimit.toString()),payload)}.isFailure)
        val broken=payload.copy(binary=payload.binary.filterKeys {it!=payload.binary.keys.first {name->name.contains(InboxContractFixture.id("pending-file-0"))}})
        assertTrue(runCatching {NativeMigrationSelectionGraph.validate(kind,source,broken)}.isFailure)
        assertTrue(runCatching {NativeMigrationSelectionGraph.validate(kind,null,payload)}.isFailure)
        val output=NativeMigrationStreamRecords(mapOf("output-v1-${id("camera")}" to "{\"version\":1}"),emptyMap(),4)
        assertEquals("Camera output originals are not yet supported",runCatching {NativeMigrationSelectionGraph.validate(NativeMigrationSelectionJournal.Kind.CAMERA,source,outputs=output)}.exceptionOrNull()?.message)
        assertEquals("Camera output originals are not yet supported",runCatching {NativeMigrationSelectionGraph.validate(NativeMigrationSelectionJournal.Kind.CAMERA,source,rawNames=setOf("photo-original.jpg"))}.exceptionOrNull()?.message)
        assertNull(NativeMigrationSelectionGraph.validate(kind,null).originalJournal)
    }
    @Test fun outputClaimNamesAndClosedBodiesAreAuditOnlyAndNeverOwnBinaryOrUnknownFields() {
        val name="output-v1-${id("claim")}";val raw="\n"+JSONObject().put("version",1).put("id",id("claim")).toString()+"\n"
        val original=mapOf(name to raw);NativeMigrationSelectionOutputClaims.validate(original,emptyMap());assertEquals(raw,original.getValue(name))
        for(body in listOf(JSONObject(raw).put("version",2),JSONObject(raw).put("authority",true),JSONObject(raw).put("id",id("other")))) {
            assertTrue(runCatching {NativeMigrationSelectionOutputClaims.validate(mapOf(name to body.toString()),emptyMap())}.isFailure)
        }
        assertTrue(runCatching {NativeMigrationSelectionOutputClaims.validate(mapOf("unknown" to raw),emptyMap())}.isFailure)
        assertTrue(runCatching {NativeMigrationSelectionOutputClaims.validate(original,mapOf("unowned" to InboxContractFixture.blob(byteArrayOf(1))))}.isFailure)
    }
}
