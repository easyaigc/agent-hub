package com.agenthub.android

import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.ReplyRecoveryRules
import com.agenthub.core.TargetScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

/** Synthetic old snapshots exercise the production guard, not a disk-fault reproduction. */
@RunWith(AndroidJUnit4::class)
class NativeLegacyReplyRetrySafetyTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=TargetScope("codex",null,null,"host","workspace","runtime")
    private fun client()=MobileClient("host","fixture",scope,"https://127.0.0.1:1",true,"desktop-runtime",false,
        codexDirect=PairedCodexConnection(scope,"a".repeat(64),listOf(CodexRoute("https://127.0.0.1:1","lan"))))
    private fun intent()=ReplyIntent("host","thread",UUID.randomUUID().toString(),"preserved original",emptyList(),"next-turn")

    /** Only these test-created stores, UUID directory and UUID Keystore alias are owned here.
     * Tests call synchronous journal/storage writes, never enqueue an asynchronous writer. */
    private inner class Fixture: Closeable {
        private val id=UUID.randomUUID().toString()
        private val namespace="legacy-reply-guard-$id"
        private val alias="agent-hub-test-legacy-reply-$id"
        private val base=context.noBackupFilesDir.canonicalFile.toPath()
        private val root=base.resolve(namespace)
        private val stores=mutableListOf<NativeSessionStorage>()
        init {
            check(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))
            check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        }
        fun store()=NativeSessionStorage(context,namespace,alias).also {stores.add(it);Os.chmod(root.toString(),0x1c0)}
        override fun close() {
            stores.forEach {it.retireWriter()}
            check(root.parent==base&&root.fileName.toString()==namespace)
            if(Files.exists(root,LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(root))
                // walkFileTree does not follow symbolic links by default; delete only the link itself.
                Files.walkFileTree(root,object: SimpleFileVisitor<Path>() {
                    private fun own(path: Path) {
                        check(path.normalize().startsWith(root)&&Os.lstat(path.toString()).st_uid==Process.myUid())
                    }
                    override fun visitFile(file: Path,attrs: BasicFileAttributes): FileVisitResult {
                        own(file);Files.delete(file);return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path,failure: java.io.IOException?): FileVisitResult {
                        if(failure!=null)throw failure
                        own(dir);Files.delete(dir);return FileVisitResult.CONTINUE
                    }
                })
            }
            check(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))
            val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            if(keys.containsAlias(alias))keys.deleteEntry(alias)
            check(!keys.containsAlias(alias))
        }
    }

    private fun proof(record: SavedOperation,now: Long)=JSONObject().put("version",2).put("hostId","host").put("threadId","thread")
        .put("clientMessageId",record.id).put("target",JSONObject(record.replyRecovery!!.capturedTarget))
        .put("capturedTarget",JSONObject(record.replyRecovery.capturedTarget)).put("status","not-accepted")
        .put("observedAt",Instant.ofEpochMilli(now).toString()).put("fingerprintVersion",2).put("fingerprint",record.replyRecovery.fingerprint)
        .put("receipt",JSONObject.NULL).put("evidence",JSONObject().put("kind","operator-fenced-checkpoint-v2")
            .put("fingerprint",record.replyRecovery.fingerprint).put("oldPid",100).put("newPid",200)
            .put("ledgerSha256","b".repeat(64)).put("fenceSha256","c".repeat(64)).put("ledgerId",UUID.randomUUID().toString()))

    @Test fun coldVersionOneReadyNotWrittenCannotRetryOrRestoreIntoANewDraft()=runBlocking {
        Fixture().use {fixture->
            val store=fixture.store()
            val original=NativeOperationJournal(store).prepare(client(),"thread","reply",intent().body())
            // A legacy snapshot can be genuine never-sent or stale after a soft attempted-state write.
            // The guard makes the same conservative choice without inventing either history.
            store.write("operations:${original.target}",JSONObject().put("version",1).put("target",original.target)
                .put("operations",JSONArray(listOf(original.body()))).toString())
            val restored=NativeOperationJournal(fixture.store()).records(original.target).single()
            assertEquals("ready",restored.phase);assertEquals("not-written",restored.replyRecovery!!.phase)
            assertFalse(restored.replyRecovery.mayRetry(client(),restored))
            var statusReads=0;var sends=0
            val commands=object: CodexCommands by NativeCodexCommands({ "unused synthetic token" }) {
                override suspend fun replyDeliveryStatus(client: MobileClient,threadId: String,operationId: String): JSONObject {
                    statusReads++;error("The guard must reject before network")
                }
                override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt {
                    sends++;error("Legacy not-written must not authorize a send")
                }
            }
            val flow=NativeCodexFlow(fixture.store(),NativeHostReads { "unused synthetic token" },commands)
            val retry=runCatching {flow.retryReply(client(),restored,{true},{})}
            assertTrue(retry.exceptionOrNull() is IllegalArgumentException)
            assertEquals("原消息仍待核对，不能重新提交",retry.exceptionOrNull()?.message)
            val content=runCatching {flow.originalReplyContent(client(),restored)}
            assertTrue(content.exceptionOrNull() is IllegalArgumentException)
            assertEquals("原输入未提交尚未得到确认，原消息和当前草稿均已保留",content.exceptionOrNull()?.message)
            assertEquals(0,statusReads);assertEquals(0,sends)
            val retained=flow.journal.records(restored.target).single()
            assertEquals(restored.id,retained.id);assertEquals(restored.target,retained.target)
            assertEquals(restored.intent,retained.intent);assertEquals(restored.intentSha256,retained.intentSha256)
            assertFalse(retained.detached);assertFalse(retained.replyRecovery!!.consumed)
        }
    }

    @Test fun androidGuardLeavesSharedRuleAndFirstJournalTransitionUnchanged()=runBlocking {
        Fixture().use {fixture->
            val journal=NativeOperationJournal(fixture.store())
            val fresh=journal.prepare(client(),"thread","reply",intent().body())
            assertTrue(ReplyRecoveryRules.mayRetry("not-written",false,true,false))
            assertFalse(fresh.replyRecovery!!.mayRetry(client(),fresh))
            val failed=fresh.copy(phase="failed")
            assertTrue(failed.unconfirmedFailedReply)
            assertFalse(failed.copy(detached=true).unconfirmedFailedReply)
            assertFalse(failed.copy(kind="creation").unconfirmedFailedReply)
            val received=encodeReceipt(DeliveryReceipt("host","thread",fresh.id,"submitted","run",null,null,null,emptyList())).toString()
            assertFalse(failed.copy(result=received).unconfirmedFailedReply)
            // Functional journal transition only, not a full send or a durability certificate.
            val attempted=journal.markSubmitting(fresh.target,fresh.id)
            assertTrue(attempted.submissionAttempted);assertEquals("uncertain",attempted.replyRecovery!!.phase)
            assertEquals(fresh.id,attempted.id);assertEquals(fresh.intent,attempted.intent)
        }
    }

    @Test fun freshStrongProofStillAllowsExactlyOneJournalConsumption()=runBlocking {
        Fixture().use {fixture->
            val journal=NativeOperationJournal(fixture.store())
            val original=journal.prepare(client(),"thread","reply",intent().body())
            journal.markSubmitting(original.target,original.id)
            val now=System.currentTimeMillis()
            val authorized=journal.observeReplyInspection(client(),original.target,original.id,proof(original,now))
            assertTrue(authorized.replyRecovery!!.mayRetry(client(),authorized,now))
            val consumed=journal.consumeReplyRetry(client(),original.target,original.id)
            assertTrue(consumed.replyRecovery!!.consumed)
            assertFalse(consumed.replyRecovery.mayRetry(client(),consumed,now))
            assertTrue(runCatching {journal.consumeReplyRetry(client(),original.target,original.id)}.isFailure)
            val restored=NativeOperationJournal(fixture.store()).records(original.target).single()
            assertTrue(restored.replyRecovery!!.consumed)
            assertEquals(original.id,restored.id);assertEquals(original.intent,restored.intent)
            assertEquals(original.intentSha256,restored.intentSha256)
        }
    }

    @Test fun strongLabelCannotBypassFreshnessTrustTargetOrReadOnlyGuards()=runBlocking {
        Fixture().use {fixture->
            val journal=NativeOperationJournal(fixture.store())
            val original=journal.prepare(client(),"thread","reply",intent().body())
            val now=System.currentTimeMillis()
            val authorized=journal.observeReplyInspection(client(),original.target,original.id,proof(original,now))
            val recovery=authorized.replyRecovery!!
            assertTrue(recovery.mayRetry(client(),authorized,now))
            assertFalse(recovery.mayRetry(client(),authorized,now+61_000))
            assertFalse(recovery.mayRetry(client().copy(codexDirect=client().codexDirect!!.copy(fingerprint="d".repeat(64))),authorized,now))
            assertFalse(recovery.mayRetry(client().copy(scope=TargetScope("codex",null,null,"host","workspace","different-runtime")),authorized,now))
            assertFalse(recovery.mayRetry(client(),authorized.copy(detached=true),now))
            assertFalse(recovery.mayRetry(client(),authorized.copy(migrationReadOnly=true),now))
            val received=encodeReceipt(DeliveryReceipt("host","thread",authorized.id,"submitted","run",null,null,null,emptyList())).toString()
            assertFalse(recovery.mayRetry(client(),authorized.copy(result=received),now))
            assertFalse(recovery.copy(inspection=null).mayRetry(client(),authorized,now))
            assertFalse(recovery.copy(phase="uncertain").mayRetry(client(),authorized,now))
            assertFalse(recovery.copy(consumed=true).mayRetry(client(),authorized,now))
        }
    }
}
