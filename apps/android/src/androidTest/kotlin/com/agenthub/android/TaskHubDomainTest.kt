package com.agenthub.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual Android SQLite/Keystore/coroutine boundaries; synthetic HTTP is auxiliary evidence. */
@RunWith(AndroidJUnit4::class)
class TaskHubDomainTest {
    @get:org.junit.Rule val timeout=org.junit.rules.Timeout.seconds(120)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun namespace()="um06-"+UUID.randomUUID()
    private val project="00000000-0000-4000-8000-000000000010"
    private val task="00000000-0000-4000-8000-000000000011"
    private val round="00000000-0000-4000-8000-000000000012"
    private val device="00000000-0000-4000-8000-000000000013"
    private val human=TaskHubHumanSession("human-one","人类","member","synthetic-token","2030-01-01T00:00:00Z")
    private val binding=TaskHubBinding(TaskHubService("deployment","instance","primary","验收主服务",listOf("business-operation-receipts")),"https://127.0.0.1:445","a".repeat(64))
    private fun operation(scope: String,id: String=UUID.randomUUID().toString())=TaskHubOperation(id,scope,project,task,round,7,"tasks/$task/feedbacks","POST","{\"revision\":7,\"body\":\"原提交 中文 😀\"}",taskHubSha("{\"revision\":7,\"body\":\"原提交 中文 😀\"}".toByteArray()))
    private suspend fun fails(block: suspend ()->Unit) { try { block();fail("Expected failure") } catch(e: Exception) { assertFalse(e is CancellationException) } }
    private class Transport(val project: String,val binding: TaskHubBinding): TaskHubUploadTransport {
        val writes=AtomicInteger();var status=409;var revoked=false;var changed=false
        var eventCursor: String?=null
        var revision=7L;var wrongSnapshot=false
        var actorRole="member"
        var detailRelease: CompletableDeferred<Unit>?=null;var detailStarted: CompletableDeferred<Unit>?=null
        private val detailCount=AtomicInteger()
        private fun detail(version: Long)=JSONObject().put("id","00000000-0000-4000-8000-000000000011").put("projectId",project).put("currentRound",1).put("revision",version).put("rounds",JSONArray().put(JSONObject().put("number",1).put("id","00000000-0000-4000-8000-000000000012")))
        private fun snapshot()=JSONObject().put("token",if(wrongSnapshot) "other" else "snapshot-one").put("taskId","00000000-0000-4000-8000-000000000011").put("projectId",project).put("currentRoundId","00000000-0000-4000-8000-000000000012").put("revision",revision).put("syncCursor","41").put("expiresAt","2030-01-01T00:00:00Z")
        val fileWrites=AtomicInteger();var attachment: JSONObject?=null;var fileRequestSha: String?=null
        override suspend fun uploadFile(projectId: String,ticketId: String?,operationId: String,file: File,contentType: String,sha256: String): String {
            check(projectId==project && taskHubSha(file.readBytes())==sha256)
            fileRequestSha=sha256;fileWrites.incrementAndGet();throw TaskHubHTTPError(409,"上传结果未确认")
        }
        override suspend fun request(path: String,method: String,body: String?,operationId: String?,query: Map<String,String>): String {
            if(revoked && path!="service") throw TaskHubHTTPError(403,"撤销")
            if(path=="service") return binding.service.json().put("serverInstanceId",if(changed) "other" else binding.service.serverInstanceId).toString()
            if(path=="auth/me") return "{\"actor\":{\"id\":\"human-one\",\"name\":\"人类\",\"kind\":\"human\",\"role\":\"$actorRole\"}}"
            if(path=="projects" && method=="GET") return "[{\"id\":\"$project\"}]"
            if(path=="tasks/00000000-0000-4000-8000-000000000011") {
                val version=revision
                if(detailCount.incrementAndGet()==1 && detailRelease!=null) { detailStarted?.complete(Unit);detailRelease?.await() }
                return detail(version).toString()
            }
            if(path.endsWith("/snapshot")) return detail(revision).put("currentRoundDetail",JSONObject().put("id","00000000-0000-4000-8000-000000000012").put("number",1)).put("snapshot",snapshot()).toString()
            if(path.endsWith("/rounds")) return JSONObject().put("snapshot",snapshot()).put("items",JSONArray()).put("nextCursor",JSONObject.NULL).toString()
            if(path.endsWith("/events")) return JSONObject().put("snapshot",snapshot()).put("items",JSONArray().put(JSONObject().put("roundNumber",1))).put("nextCursor",JSONObject.NULL).toString()
            if(path.startsWith("operations/")) return JSONObject().put("operationId",path.substringAfter('/')).put("phase","confirmed").put("result",attachment ?: JSONObject().put("accepted",true)).toString()
            check(method=="POST");writes.incrementAndGet();throw TaskHubHTTPError(status,"结果未确认")
        }
        override suspend fun events(projectId: String,cursor: String?,changed: (String,String,Long)->Unit) {
            eventCursor=cursor;changed("42","00000000-0000-4000-8000-000000000011",8)
        }
    }
    @Test fun deviceRevocationUsesExactCurrentServiceAndNeverRetriesUnknownDelete()=runBlocking {
        val parent=Transport(project,binding);val deletes=AtomicInteger();val sessionId=UUID.randomUUID().toString()
        val service=binding.service.copy(capabilities=binding.service.capabilities+"mobile-device-sessions")
        val selected=binding.copy(service=service)
        val transport=object: TaskHubTransport {
            override suspend fun request(path: String,method: String,body: String?,operationId: String?,query: Map<String,String>): String {
                if(path=="service") return service.json().toString()
                if(path=="auth/mobile/sessions") return JSONArray().put(JSONObject().put("id",sessionId).put("deviceId",device).put("deviceName","本机").put("createdAt","2026-01-01T00:00:00Z").put("expiresAt","2030-01-01T00:00:00Z").put("revokedAt",JSONObject.NULL).put("status","active").put("current",true)).toString()
                if(path=="auth/mobile/sessions/$sessionId") { check(method=="DELETE");deletes.incrementAndGet();throw java.io.IOException("unknown revocation") }
                return parent.request(path,method,body,operationId,query)
            }
            override suspend fun events(projectId: String,cursor: String?,changed: (String,String,Long)->Unit) { }
        }
        val repository=TaskHubRepository(TaskHubSQLitePersistence(context,namespace()),device,selected,human,transport) { _,_-> }
        assertEquals(sessionId,repository.deviceSessions().single().id)
        fails { repository.revokeDeviceSession(sessionId) };assertEquals(1,deletes.get())
        repository.detach();assertEquals(1,deletes.get())
    }
    @Test fun sqliteAuthenticatedRestartAndTransactionRollback() {
        val name=namespace();val db=TaskHubSQLitePersistence(context,name)
        db.transaction { it["scope/草稿"]="secret 中文 😀";it["receipt"]="original" }
        assertEquals("secret 中文 😀",TaskHubSQLitePersistence(context,name).readAll()["scope/草稿"])
        try { db.transaction { it["receipt"]="bad";throw IllegalStateException("rollback") } } catch(_: IllegalStateException) { }
        assertEquals("original",db.readAll()["receipt"])
        val file=File(context.noBackupFilesDir,"$name/business.sqlite")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("secret"))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("receipt"))
    }
    @Test fun sqliteConcurrentInstancesDoNotLoseDrafts()=runBlocking {
        val name=namespace()
        (0 until 24).map { index -> async(Dispatchers.IO) {
            TaskHubSQLitePersistence(context,name).transaction { it["draft-$index"]="$index" }
        } }.awaitAll()
        assertEquals(24,TaskHubSQLitePersistence(context,name).readAll().size)
    }
    @Test fun missingFrameFailsClosedAndKeepsOriginalBytes() {
        val name=namespace();val db=TaskHubSQLitePersistence(context,name)
        db.transaction { it["draft"]="preserve" }
        val file=File(context.noBackupFilesDir,"$name/business.sqlite")
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM frames WHERE id <> 'manifest'") }
        val before=file.readBytes()
        try { db.readAll();fail() } catch(_: IllegalStateException) { }
        try { db.transaction { it["new"]="bad" };fail() } catch(_: IllegalStateException) { }
        assertArrayEquals(before,file.readBytes())
    }
    @Test fun copiedCiphertextAndUnknownVersionAreNotMigratedOrReset() {
        val first=namespace();val second=namespace()
        TaskHubSQLitePersistence(context,first).transaction { it["draft"]="first" }
        TaskHubSQLitePersistence(context,second).transaction { it["draft"]="second" }
        val a=File(context.noBackupFilesDir,"$first/business.sqlite");val b=File(context.noBackupFilesDir,"$second/business.sqlite")
        a.copyTo(b,overwrite=true)
        try { TaskHubSQLitePersistence(context,second).readAll();fail() } catch(_: Exception) { }
        SQLiteDatabase.openDatabase(a.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.version=99 }
        val before=a.readBytes()
        try { TaskHubSQLitePersistence(context,first).readAll();fail() } catch(_: IllegalStateException) { }
        assertArrayEquals(before,a.readBytes())
    }
    @Test fun unknownWriteSurvivesRestartQueriesOriginalAndPreservesNewDraft()=runBlocking {
        val name=namespace();val db=TaskHubSQLitePersistence(context,name);val transport=Transport(project,binding)
        fun repository()=TaskHubRepository(TaskHubSQLitePersistence(context,name),device,binding,human,transport) { _,_-> }
        val first=repository();val op=operation(first.scope)
        first.saveDraft(project,task,round,"feedback","原草稿")
        fails { first.submit(op) }
        assertEquals(1,transport.writes.get());assertEquals("unknown",first.operations().single().phase)
        first.endWaiting(op.id);first.detach()
        val restored=repository();restored.saveDraft(project,task,round,"feedback","新草稿")
        assertEquals("confirmed",restored.queryReceipt(op.id).phase)
        assertEquals(1,transport.writes.get());assertEquals("新草稿",restored.draft(project,task,round,"feedback"))
        fails { restored.submit(op) };assertEquals(1,transport.writes.get())
        assertEquals(op.body,restored.operations().single().body)
        assertTrue(db.readAll().isNotEmpty())
    }
    @Test fun changedPrimaryAndDeniedSharingNeverSubmit()=runBlocking {
        val transport=Transport(project,binding);val db=TaskHubSQLitePersistence(context,namespace())
        val denied=TaskHubRepository(db,device,binding,human,transport) { _,_-> error("Sharing declined") }
        fails { denied.submit(operation(denied.scope)) };assertEquals(0,transport.writes.get());assertTrue(denied.operations().isEmpty())
        transport.changed=true
        val changed=TaskHubRepository(db,device,binding,human,transport) { _,_-> }
        fails { changed.submit(operation(changed.scope)) };assertEquals(0,transport.writes.get());assertTrue(changed.operations().isEmpty())
    }
    @Test fun sseCursorAndInvalidationCommitTogetherAcrossRestart()=runBlocking {
        val name=namespace();val transport=Transport(project,binding)
        val repo=TaskHubRepository(TaskHubSQLitePersistence(context,name),device,binding,human,transport) { _,_-> }
        var count=0;repo.observe(project) { _,_-> count++ };assertEquals(1,count);assertNull(transport.eventCursor)
        val restored=TaskHubRepository(TaskHubSQLitePersistence(context,name),device,binding,human,transport) { _,_-> }
        restored.observe(project) { _,_-> };assertEquals("42",transport.eventCursor)
    }
    @Test fun revokedMembershipStopsReadsAndKeepsIsolatedDraft()=runBlocking {
        val transport=Transport(project,binding);val repo=TaskHubRepository(TaskHubSQLitePersistence(context,namespace()),device,binding,human,transport) { _,_-> }
        repo.saveDraft(project,task,round,"feedback","本机草稿")
        repo.verify();transport.revoked=true
        fails { repo.submit(operation(repo.scope)) };assertEquals(0,transport.writes.get())
        assertEquals("本机草稿",repo.draft(project,task,round,"feedback"))
    }
    @Test fun encryptedOriginalUploadUnknownIsOnlyQueriedAfterRepositoryRestart()=runBlocking {
        val name=namespace();val fileNamespace=namespace();val transport=Transport(project,binding)
        val data="原附件 中文 😀".toByteArray()
        transport.attachment=JSONObject().put("id",UUID.randomUUID().toString()).put("sizeBytes",data.size).put("sha256",taskHubSha(data))
        fun repository()=TaskHubRepository(TaskHubSQLitePersistence(context,name),device,binding,human,transport) { _,_-> }
        val first=repository();val files=TaskHubUploadCoordinator(context,first,fileNamespace)
        val upload=files.prepare(project,task,round,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"feedback","证据.txt","text/plain",data)
        fails { files.submit(upload.operationId) }
        assertEquals(1,transport.fileWrites.get());assertEquals(upload.bodySha256,transport.fileRequestSha)
        assertEquals("unknown",files.records().single().phase)
        val restored=TaskHubUploadCoordinator(context,repository(),fileNamespace)
        assertEquals("confirmed",restored.query(upload.operationId).phase)
        assertEquals(1,transport.fileWrites.get());fails { restored.submit(upload.operationId) }
        assertFalse(File(context.noBackupFilesDir,"$fileNamespace/${upload.operationId}.body").exists())
    }
    @Test fun maximumAttachmentPreservesMultipartBytesWithoutWholeBodyCopies()=runBlocking {
        val db=TaskHubSQLitePersistence(context,namespace());val transport=Transport(project,binding)
        val repo=TaskHubRepository(db,device,binding,human,transport) { _,_-> }
        val fileNamespace=namespace();val uploads=TaskHubUploadCoordinator(context,repo,fileNamespace)
        val data=ByteArray(20*1024*1024) { (it%251).toByte() }
        val record=uploads.prepare(project,task,round,UUID.randomUUID().toString(),UUID.randomUUID().toString(),
            "feedback","evidence.bin","application/octet-stream",data)
        val expected=java.security.MessageDigest.getInstance("SHA-256")
        expected.update("--${record.boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"evidence.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
        expected.update(data);expected.update("\r\n--${record.boundary}--\r\n".toByteArray())
        assertEquals(taskHubSha(data),record.fileSha256)
        assertEquals(expected.digest().joinToString("") { "%02x".format(it.toInt() and 255) },record.bodySha256)
        assertTrue(record.bodySize>data.size && record.bodySize<=21*1024*1024)
        assertEquals(record,uploads.records().single())
        assertFalse(File(context.noBackupFilesDir,"$fileNamespace/${record.operationId}.body").exists())
        assertEquals(0,transport.fileWrites.get())
    }
    @Test fun uploadAssociationAndWrongReceiptDigestFailClosed()=runBlocking {
        val name=namespace();val transport=Transport(project,binding)
        val repo=TaskHubRepository(TaskHubSQLitePersistence(context,name),device,binding,human,transport) { _,_-> }
        val uploads=TaskHubUploadCoordinator(context,repo,namespace())
        val draft=UUID.randomUUID().toString();val evidence=UUID.randomUUID().toString()
        val original=uploads.prepare(project,task,round,draft,evidence,"feedback","e.txt","text/plain",byteArrayOf(1,2,3))
        fails { uploads.prepare(project,task,round,draft,evidence,"feedback","e.txt","text/plain",byteArrayOf(3,2,1)) }
        fails { uploads.submit(original.operationId) }
        transport.attachment=JSONObject().put("id",UUID.randomUUID().toString()).put("sizeBytes",3).put("sha256","0".repeat(64))
        fails { uploads.query(original.operationId) }
        assertEquals("unknown",uploads.records().single().phase);assertEquals(1,transport.fileWrites.get())
    }
    @Test fun olderDetailCannotOverwriteNewReadOrDraft()=runBlocking {
        val transport=Transport(project,binding);val repo=TaskHubRepository(TaskHubSQLitePersistence(context,namespace()),device,binding,human,transport) { _,_-> }
        transport.detailRelease=CompletableDeferred();transport.detailStarted=CompletableDeferred()
        val first=async { runCatching { repo.readDetail(project,task) } }
        try {
            withTimeout(10000) { transport.detailStarted!!.await() };transport.revision=8
            val newer=repo.readDetail(project,task);assertEquals(8L,TaskHubTask.decode(newer).revision)
            repo.saveDraft(project,task,round,"feedback","新草稿")
        } finally { transport.detailRelease!!.complete(Unit) }
        assertTrue(first.await().isFailure)
        assertEquals("新草稿",repo.draft(project,task,round,"feedback"))
    }
    @Test fun historyPagesMustKeepOriginalSnapshotAndRound()=runBlocking {
        val transport=Transport(project,binding);val repo=TaskHubRepository(TaskHubSQLitePersistence(context,namespace()),device,binding,human,transport) { _,_-> }
        val snapshot=repo.readSnapshot(project,task)
        assertEquals("snapshot-one",snapshot.token)
        repo.readHistory(snapshot);repo.readHistory(snapshot,round,true)
        transport.wrongSnapshot=true;fails { repo.readHistory(snapshot,cursor="next") }
        assertEquals(0,transport.writes.get())
    }
    @Test fun replayedOldFrameCannotJoinCurrentManifest() {
        val name=namespace();val db=TaskHubSQLitePersistence(context,name);db.transaction { it["draft"]="v1" }
        val file=File(context.noBackupFilesDir,"$name/business.sqlite")
        val old=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { sql -> sql.rawQuery("SELECT payload FROM frames WHERE id <> 'manifest'",null).use { it.moveToFirst();it.getBlob(0) } }
        db.transaction { it["draft"]="v2" }
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("UPDATE frames SET payload=? WHERE id <> 'manifest'",arrayOf(old)) }
        val before=file.readBytes()
        try { db.readAll();fail() } catch(_: Exception) { }
        assertArrayEquals(before,file.readBytes())
    }
    @Test fun binaryAdapterKeepsVoiceDefaultAndSeparatesBusinessKey() {
        val name=namespace();val source=File(context.cacheDir,"um06-binary-"+UUID.randomUUID())
        try {
            source.writeText("原二进制 中文 😀")
            val voice=NativeSessionStorage(context,name)
            voice.writeBinary("original",source)
            assertArrayEquals(source.readBytes(),NativeSessionStorage(context,name).readBinary("original",source.length().toInt()))
            val business=NativeSessionStorage(context,name,"um06-business-"+UUID.randomUUID())
            try { business.readBinary("original",source.length().toInt(),20*1024*1024);fail() } catch(_: Exception) { }
            business.writeBinary("business",source,20*1024*1024)
            assertArrayEquals(source.readBytes(),business.readBinary("business",source.length().toInt(),20*1024*1024))
            java.io.RandomAccessFile(source,"rw").use { it.setLength(13L*1024*1024) }
            try { voice.writeBinary("too-large",source);fail() } catch(_: IllegalArgumentException) { }
        } finally { source.delete() }
    }
    @Test fun projectCreationKeepsUnknownServerIdAndChecksCurrentAdminRole()=runBlocking {
        val transport=Transport(project,binding);val repo=TaskHubRepository(TaskHubSQLitePersistence(context,namespace()),device,binding,human,transport) { _,_-> }
        val body="{\"name\":\"original project\",\"key\":\"ORIGINAL\",\"description\":\"human only\"}"
        val original=TaskHubOperation(UUID.randomUUID().toString(),repo.scope,null,null,null,null,"projects","POST",body,taskHubSha(body.toByteArray()))
        fails { repo.submit(original) };assertEquals(0,transport.writes.get());assertTrue(repo.operations().isEmpty())
        transport.actorRole="admin";fails { repo.submit(original) }
        assertEquals(1,transport.writes.get());assertNull(repo.operations().single().projectId)
        assertEquals("unknown",repo.operations().single().phase);assertEquals(body,repo.operations().single().body)
    }
}
