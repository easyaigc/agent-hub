package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Adapter boundaries; synthetic records are auxiliary evidence, never real Host acceptance. */
@RunWith(AndroidJUnit4::class)
class EasyWorkIndependentUploadsBoundaryTest {
    private fun context(max:Any=JSONObject.NULL,chunk:Any=32768)=JSONObject()
        .put("capabilities",JSONArray(listOf("file-transfers","resources:transfer-limits:v1")))
        .put("resourceTransferLimits",JSONObject().put("version",1).put("uploadMaxBytes",20*1024*1024)
            .put("downloadMaxBytes",JSONObject.NULL).put("transferMaxBytes",max).put("maxChunkBytes",chunk))
    private fun manifest(size:Long=64L*1024*1024)=NativeUploadManifest(ComputerFileManifest("test-host",
        "641191ae-f1a8-4a12-920f-8473b424b788","synthetic.bin","application/octet-stream",size,"a".repeat(64)),"file-transfer")
    private fun wire(m:NativeUploadManifest,phase:String="transferring",count:Long=32768)=JSONObject().put("transfer",JSONObject()
        .put("transferId",m.id).put("mobileDeviceId","test-device").put("direction","mobile-to-desktop")
        .put("purpose","transfer").put("name",m.file.name).put("contentType",m.file.mediaType).put("size",m.file.sizeBytes)
        .put("expectedSha256",m.file.sha256).put("sha256",m.file.sha256).put("status",phase).put("transferredBytes",count)
        .put("absolutePath","/test-received/synthetic.bin").put("createdAt","2026-11-22T00:00:00Z"))
    private fun rejects(body:()->Unit) {try {body();fail("must reject")}catch(_:IllegalArgumentException){}catch(_:org.json.JSONException){}}
    @Test fun negotiatedAndLegacyLimitsRemainIndependentFromAttachmentBudget() {
        val unlimited=EasyWorkFileLimits.decode(context());unlimited.validate(21L*1024*1024);unlimited.validate(64L*1024*1024)
        assertNull(unlimited.maximum);assertEquals(32768,unlimited.chunk)
        val limited=EasyWorkFileLimits.decode(context(32L*1024*1024,999999))
        limited.validate(21L*1024*1024);rejects {limited.validate(64L*1024*1024)}
        assertEquals(EasyWorkFileTransfers.CHUNK,limited.chunk)
        val legacy=EasyWorkFileLimits.decode(JSONObject().put("capabilities",JSONArray(listOf("file-transfers"))))
        rejects {legacy.validate(21L*1024*1024)};assertEquals(20L*1024*1024,legacy.maximum)
        rejects {EasyWorkFileLimits.decode(context(0))};rejects {EasyWorkFileLimits.decode(context(chunk=true))}
        rejects {limited.catalogMaximum(JSONObject().put("maxFileBytes",JSONObject.NULL))}
    }
    @Test fun standaloneReceiptPreservesOriginalIdentityAndCompletionCancelRace() {
        val m=manifest()
        val paused=EasyWorkFileTransfers.uploadReceipt(wire(m),m,"test-device",32768)
        assertEquals(m,paused.manifest);assertEquals(32768L,paused.committedBytes)
        assertEquals("cancelled",EasyWorkFileTransfers.uploadReceipt(wire(m,"cancelled"),m,"test-device").phase)
        // A completed cancel reply stays completed, with its original destination and digest.
        val done=EasyWorkFileTransfers.uploadReceipt(wire(m,"completed",m.file.sizeBytes),m,"test-device")
        assertEquals("completed",done.phase);assertEquals(m.id,done.result!!.getString("id"))
        for((key,value) in listOf("mobileDeviceId" to "wrong-device","threadId" to "chat", "expectedSha256" to "b".repeat(64),
            "size" to m.file.sizeBytes+1,"sha256" to "b".repeat(64),"transferredBytes" to m.file.sizeBytes-1)) {
            rejects {EasyWorkFileTransfers.uploadReceipt(wire(m,"completed",m.file.sizeBytes).apply {getJSONObject("transfer").put(key,value)},m,"test-device")}
        }
        rejects {EasyWorkFileTransfers.uploadReceipt(wire(m),m.copy(file=m.file.copy(sha256="b".repeat(64))),"test-device")}
    }
    @Test fun preparationQueriesOriginalBeforeApplyingNewQuotaOrWriting()=runBlocking {
        val m=manifest();val calls=mutableListOf<String>()
        val target=EasyWorkTarget("https://test.invalid","test-subject","test-host","test-workspace","test-runtime")
        val value=EasyWorkIndependentUploads.prepare(m,"test-device",target,EasyWorkFileLimits(1,32768,true)) {method,_,_->
            calls+=method;wire(m)
        }
        assertEquals(listOf("resource/upload-status"),calls);assertEquals(m.id,value.getJSONObject("transfer").getString("transferId"))
    }
    @Test fun missingTransferChecksOriginalCommandAndRejectsUnknownOrOverLimitWithoutWrite()=runBlocking {
        val m=manifest();val target=EasyWorkTarget("https://test.invalid","subject","desktop","workspace","runtime")
        val calls=mutableListOf<String>()
        try {
            EasyWorkIndependentUploads.prepare(m,"test-device",target,EasyWorkFileLimits(20L*1024*1024,32768,false)) {method,_,_->
                calls+=method
                if(method=="resource/upload-status")throw EasyWorkRejection("synthetic missing","TRANSFER_NOT_FOUND")
                if(method=="command/status")JSONObject() else error("must not write")
            }
            fail("over limit must reject")
        } catch(_:IllegalArgumentException) {}
        assertEquals(listOf("resource/upload-status","command/status"),calls)
    }
    @Test fun originalSourceTargetProjectionAndJournalPreserveEasyWorkIdentity() {
        val scope=EasyWorkTarget("https://test.invalid","subject","desktop","workspace","runtime").scope()
        val target=ComputerFileTarget(scope,null)
        assertNotEquals(scope.hostId,target.clientId)
        assertEquals(target,NativeUploadTarget(target.clientId,scope,null).computerFileTarget())
        val m=manifest().copy(file=manifest().file.copy(hostId=target.clientId,sizeBytes=1))
        val completed=EasyWorkIndependentUploads.receipt(wire(m,"completed",1).getJSONObject("transfer"),target.clientId,"test-device","")
        val row=PendingComputerFile(m.id,m.file.name,m.file.mediaType,1,m.file.sha256,listOf(ComputerFileChunk(1,"b".repeat(64))),ComputerFilePhase.COMPLETED,receipt=completed)
        val raw=JSONObject().put("schema","agent-hub/android-computer-files-v1").put("target",target.json()).put("files",JSONArray(listOf(row.json())))
        val restored=NativeComputerFileJournal.decode("computer-files-v1:"+target.key,raw.toString(),target)
        assertEquals(completed,restored.files.single().receipt)
    }

    @Test fun cancelledOrCompletedRemoteReplyNeverReplaysCompletion()=runBlocking {
        val m=manifest();val calls=mutableListOf<String>()
        val result=EasyWorkFileTransfers.cancel(m,"test-device",setOf("file-transfers")) {method,params,id->
            calls+=method;assertEquals(m.id,params.getString("transferId"));assertEquals("cancel-${m.id}",id)
            wire(m,"completed",m.file.sizeBytes)
        }
        assertEquals("completed",EasyWorkFileTransfers.uploadReceipt(result,m,"test-device").phase)
        assertEquals(listOf("resource/transfer-cancel"),calls)
    }

}
