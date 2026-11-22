package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.io.File
import java.io.IOException

/** Synthetic R3-only sources. No device/service/model input. Run only in Root's new window. */
@RunWith(AndroidJUnit4::class)
class R3EasyWorkFileTransfersBoundaryTest {
    private val bytes="R3 independent receive".toByteArray()
    private val digest=ComputerFileCodec.hash(bytes)
    private fun incoming(phase:String="waiting")=IncomingComputerFile("r3-host","r3-transfer","r3-resource","r3-device",
        "r3-only.bin","application/octet-stream",bytes.size.toLong(),digest,phase,"2026-11-21T00:00:00Z")
    private fun record(f:IncomingComputerFile)=JSONObject().put("purpose","transfer").put("direction","desktop-to-mobile")
        .put("transferId",f.id).put("resourceId",f.resourceId).put("mobileDeviceId",f.mobileDeviceId).put("name",f.name)
        .put("contentType",f.mediaType).put("size",f.sizeBytes).put("sha256",f.sha256).put("status",f.phase)
        .put("createdAt",f.createdAt).put("transferredBytes",if(f.phase=="completed")f.sizeBytes else 0)
    private fun chunk(f:IncomingComputerFile)=JSONObject().put("resourceId",f.resourceId).put("transferId",f.id)
        .put("offset",0).put("nextOffset",bytes.size).put("dataBase64",easyBase64(bytes)).put("complete",true)
    private fun rejects(work:()->Unit) {try {work();fail("must reject")}catch(expected:IllegalArgumentException) {}}
    @Test fun standaloneCatalogBindsDeviceAndRejectsThreadAndUnsafeName() {
        val f=incoming();assertEquals(f,EasyWorkFileTransfers.record(record(f),f.hostId,f.mobileDeviceId))
        rejects {EasyWorkFileTransfers.record(record(f),f.hostId,"wrong-device")}
        rejects {EasyWorkFileTransfers.record(record(f).put("threadId","r3-chat"),f.hostId,f.mobileDeviceId)}
        rejects {f.copy(name="../escape").validate(f.hostId,f.mobileDeviceId)}
        rejects {f.copy(sizeBytes=ComputerFileCodec.MAX_FILE_BYTES+1).validate(f.hostId,f.mobileDeviceId)}
    }
    @Test fun chunkChecksExactIdsOffsetsLengthAndJsonBoolean() {
        val f=incoming();assertArrayEquals(bytes,EasyWorkFileTransfers.chunk(chunk(f),f,0,bytes.size))
        rejects {EasyWorkFileTransfers.chunk(chunk(f).put("transferId","wrong"),f,0,bytes.size)}
        rejects {EasyWorkFileTransfers.chunk(chunk(f).put("nextOffset",bytes.size+1),f,0,bytes.size)}
        rejects {EasyWorkFileTransfers.chunk(chunk(f).put("complete","true"),f,0,bytes.size)}
        rejects {EasyWorkFileTransfers.chunk(chunk(f),f,0,bytes.size-1)}
    }
    private fun manifest()=NativeUploadManifest(ComputerFileManifest("r3-host","b38adcaf-60b9-4c4d-99aa-acd809efc3d0",
        "r3-only.bin","application/octet-stream",bytes.size.toLong(),digest),"attachment",threadId="r3-thread")
    private fun upload(m:NativeUploadManifest,phase:String)=JSONObject().put("transfer",JSONObject()
        .put("transferId",m.id).put("threadId",m.threadId).put("mobileDeviceId","r3-device").put("direction","mobile-to-desktop")
        .put("purpose","attachment").put("name",m.file.name).put("contentType",m.file.mediaType).put("size",m.file.sizeBytes)
        .put("expectedSha256",digest).put("sha256",digest).put("resourceId","r3-resource").put("status",phase)
        .put("transferredBytes",if(phase=="completed")m.file.sizeBytes else 0))
    @Test fun legacyMissingExpectedDigestStillAcceptsProgressAndVerifiedCompletion() {
        val m=manifest()
        for(phase in listOf("transferring","verifying","completed")) {
            val wire=upload(m,phase).apply {getJSONObject("transfer").remove("expectedSha256")}
            val receipt=EasyWorkFileTransfers.uploadReceipt(wire,m,"r3-device")
            assertEquals(when(phase){"completed"->"completed";"verifying"->"verifying";else->"uploading"},receipt.phase)
            if(phase=="completed") {
                assertEquals(m.file.sizeBytes,receipt.committedBytes)
                assertEquals(m.file.sha256,receipt.result!!.getString("sha256"))
            }
        }
    }
    @Test fun legacyMissingEchoNeverRelaxesFinalDigestIdentityOrOffset() {
        val m=manifest()
        fun legacy()=upload(m,"completed").apply {getJSONObject("transfer").remove("expectedSha256")}
        for((field,value) in listOf("sha256" to "0".repeat(64),"transferId" to "r3-wrong-id","threadId" to "r3-wrong-thread",
            "mobileDeviceId" to "r3-wrong-device","name" to "other.bin","contentType" to "text/plain","size" to m.file.sizeBytes+1,
            "transferredBytes" to m.file.sizeBytes-1)) {
            rejects {EasyWorkFileTransfers.uploadReceipt(legacy().apply {getJSONObject("transfer").put(field,value)},m,"r3-device")}
        }
        rejects {EasyWorkFileTransfers.uploadReceipt(upload(m,"transferring").apply {
            getJSONObject("transfer").remove("expectedSha256");getJSONObject("transfer").put("transferredBytes",m.file.sizeBytes+1)
        },m,"r3-device")}
    }
    @Test fun explicitConflictingOrMalformedExpectedDigestIsRejectedInEveryPhase() {
        val m=manifest()
        for(phase in listOf("transferring","completed","cancelled"))for(value in listOf("0".repeat(64),JSONObject.NULL,42)) {
            rejects {EasyWorkFileTransfers.uploadReceipt(upload(m,phase).apply {getJSONObject("transfer").put("expectedSha256",value)},m,"r3-device")}
        }
    }
    @Test fun cancellationAndCompletionRaceKeepActualTerminalAndManifest() {
        val m=manifest()
        assertEquals("cancelled",EasyWorkFileTransfers.uploadReceipt(upload(m,"cancelled"),m,"r3-device").phase)
        val done=EasyWorkFileTransfers.uploadReceipt(upload(m,"completed"),m,"r3-device")
        assertEquals("completed",done.phase);assertEquals("r3-resource",done.result!!.getString("id"))
        rejects {EasyWorkFileTransfers.uploadReceipt(upload(m,"completed").apply {getJSONObject("transfer").put("sha256","0".repeat(64))},m,"r3-device")}
        rejects {EasyWorkFileTransfers.uploadReceipt(upload(m,"cancelled"),m,"other-device")}
    }
    @Test fun cancelUsesOriginalIdOldCapabilityDoesNotSendAndTimeoutDoesNotReplay()=runBlocking {
        val m=manifest();val calls=mutableListOf<String>()
        try {
            EasyWorkFileTransfers.cancel(m,"r3-device",emptySet()) {_,_,_->calls.add("unexpected");JSONObject()}
            fail("old target")
        }catch(expected:IllegalStateException) {}
        assertTrue(calls.isEmpty())
        try {
            EasyWorkFileTransfers.cancel(m,"r3-device",setOf("file-transfers")) {method,params,id->
                assertEquals("resource/transfer-cancel",method);assertEquals(m.id,params.getString("transferId"))
                assertEquals("cancel-${m.id}",id);calls.add(id);throw IOException("R3 timeout")
            };fail("timeout")
        }catch(expected:IOException) {}
        assertEquals(listOf("cancel-${m.id}"),calls)
        // Coordinator STATUS uses upload-status with this original transfer id; no second cancel.
        assertEquals("cancelled",EasyWorkFileTransfers.uploadReceipt(upload(m,"cancelled"),m,"r3-device").phase)
        assertEquals(1,calls.size)
    }
    @Test fun receivePublishesVerifiedBytesAndUnknownAckOnlyQueriesOriginalId()=runBlocking {
        val root=Files.createTempDirectory("r3-receive-").toFile()
        val f=incoming();val calls=mutableListOf<Pair<String,String?>>()
        try {
            try {
                EasyWorkFileTransfers.receive(f,root,{true},{}) {method,_,id->
                    calls.add(method to id)
                    if(method=="resource/download")chunk(f) else throw IOException("R3 synthetic lost ACK")
                }
                fail("lost ACK")
            }catch(expected:IOException) {}
            val saved=File(File(root,f.snapshotKey),f.name)
            assertArrayEquals(bytes,saved.readBytes())
            try {
                EasyWorkFileTransfers.receive(f,root,{true},{}) {method,params,id->
                    calls.add(method to id);assertEquals("file-receive-complete-${f.snapshotKey}",params.getString("commandId"));JSONObject()
                };fail("unknown ACK remains unknown")
            }catch(expected:IllegalStateException) {}
            assertEquals(listOf("resource/download","resource/download-complete","command/status"),calls.map {it.first})
            val path=EasyWorkFileTransfers.receive(f.copy(phase="completed"),root,{true},{}) {_,_,_->error("no wire for confirmed cache")}
            assertEquals(saved,path)
        } finally {root.deleteRecursively()}
    }
    @Test fun cachedUnknownAcknowledgementCanRecoverOnlyVerifiedOriginalResult()=runBlocking {
        val root=Files.createTempDirectory("r3-original-ack-").toFile();val f=incoming()
        try {
            val folder=File(root,f.snapshotKey).apply {mkdir()};File(folder,f.name).writeBytes(bytes)
            val calls=mutableListOf<String>()
            EasyWorkFileTransfers.receive(f,root,{true},{}) {method,_,_->
                calls.add(method)
                JSONObject().put("operationId","file-receive-complete-${f.snapshotKey}").put("method","resource/download-complete")
                    .put("state","completed")
                    .put("response",JSONObject().put("protocolVersion",2).put("kind","response").put("requestId","file-receive-complete-${f.snapshotKey}")
                        .put("ok",true).put("result",JSONObject().put("transfer",record(f.copy(phase="completed")))))
            }
            assertEquals(listOf("command/status"),calls)
        } finally {root.deleteRecursively()}
    }
    @Test fun unresolvedOrWrongOriginalAckEnvelopeCannotConfirmCachedFile()=runBlocking {
        val root=Files.createTempDirectory("r3-ack-envelope-").toFile();val f=incoming()
        try {
            val folder=File(root,f.snapshotKey).apply {mkdir()};val saved=File(folder,f.name).apply {writeBytes(bytes)}
            for(state in listOf("completed","uncertain"))for(wrongId in listOf(false,true)) {
                if(state=="completed"&&!wrongId)continue
                try {
                    EasyWorkFileTransfers.receive(f,root,{true},{}) {method,_,_->
                        assertEquals("command/status",method)
                        JSONObject().put("operationId","file-receive-complete-${f.snapshotKey}").put("method","resource/download-complete").put("state",state)
                            .put("response",JSONObject().put("protocolVersion",2).put("kind","response")
                                .put("requestId",if(wrongId)"r3-other-ack" else "file-receive-complete-${f.snapshotKey}")
                                .put("ok",true).put("result",JSONObject().put("transfer",record(f.copy(phase="completed")))))
                    };fail("unconfirmed original envelope")
                }catch(expected:IllegalStateException) {}
                assertArrayEquals(bytes,saved.readBytes())
            }
        }finally {root.deleteRecursively()}
    }
    @Test fun linkedSnapshotDirectoryIsRejectedBeforeAnyRead()=runBlocking {
        val root=Files.createTempDirectory("r3-symlink-").toFile();val other=Files.createTempDirectory("r3-other-").toFile()
        val f=incoming()
        try {
            Files.createSymbolicLink(File(root,f.snapshotKey).toPath(),other.toPath())
            try {EasyWorkFileTransfers.receive(f,root,{true},{}) {_,_,_->error("must not request")};fail("linked directory")}
            catch(expected:IllegalArgumentException) {}
            assertTrue(other.listFiles().orEmpty().isEmpty())
        } finally {Files.deleteIfExists(File(root,f.snapshotKey).toPath());root.deleteRecursively();other.deleteRecursively()}
    }
    @Test fun targetChangeOrBadDigestCannotPublishOrAcknowledge()=runBlocking {
        for(changeTarget in listOf(false,true)) {
            val root=Files.createTempDirectory("r3-reject-").toFile();val f=incoming();var current=true
            val calls=mutableListOf<String>()
            try {
                try {
                    EasyWorkFileTransfers.receive(f,root,{current},{}) {method,_,_->
                        calls.add(method)
                        if(changeTarget)current=false
                        chunk(f).apply {if(!changeTarget)put("dataBase64",easyBase64(ByteArray(bytes.size)))}
                    };fail("must reject")
                }catch(expected:IllegalStateException) {}catch(expected:IllegalArgumentException) {}
                assertEquals(listOf("resource/download"),calls)
                assertFalse(File(File(root,f.snapshotKey),f.name).exists())
                assertTrue(File(root,f.snapshotKey).listFiles().orEmpty().isEmpty())
            } finally {root.deleteRecursively()}
        }
    }
}
