package com.agenthub.android

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real content-provider streams, encrypted candidates and serial writer. No device commands/Host. */
class NativeMigrationSystemBoundaryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val password="owned native system migration password"
    private val nonce=UUID.randomUUID().toString()
    private fun storage(suffix: String)=NativeSessionStorage(context,"P7-owned-system-$nonce-$suffix")
    private fun source(name: String)=File(context.cacheDir,"resources/p7-$nonce-$name.codexmigration").apply {parentFile!!.mkdirs()}
    private fun uri(file: File)=FileProvider.getUriForFile(context,context.packageName+".resources",file)
    @Before fun isolated(){check(context.packageName=="com.easyai.agenthub.android.acceptance")}
    @Test fun actualProviderStreamsKeepExactEncryptedSourceAndTruncateOnlyChosenOutput() {
        val documents=NativeMigrationDocuments(context);val id=UUID.randomUUID().toString()
        val original=source("input");val bytes=ByteArray(196_659){(it%251).toByte()};original.writeBytes(bytes)
        val copied=documents.copyInput(id,uri(original)){}
        try {
            assertArrayEquals(bytes,copied.readBytes());assertArrayEquals(bytes,original.readBytes())
            val export=documents.file(id,"export");export.writeBytes(bytes)
            val chosen=source("output");chosen.writeBytes(ByteArray(bytes.size+70_000){99})
            try {documents.saveOutput(id,export,uri(chosen)){};assertArrayEquals(bytes,chosen.readBytes());assertArrayEquals(bytes,original.readBytes())}
            finally {documents.remove(export);chosen.delete()}
        }finally {documents.remove(copied);original.delete()}
    }
    @Test fun cancelledProviderCopyRemovesOnlyOwnedPartialAndRejectsNonContentUris() {
        val documents=NativeMigrationDocuments(context);val id=UUID.randomUUID().toString();val original=source("cancel");val bytes=ByteArray(200_000){42};original.writeBytes(bytes)
        try {
            var chunks=0
            assertTrue(runCatching {documents.copyInput(id,uri(original)){if(++chunks==2)throw CancellationException("owned cancel")}}.isFailure)
            assertFalse(documents.file(id,"input").exists());assertArrayEquals(bytes,original.readBytes())
            assertTrue(runCatching {documents.copyInput(UUID.randomUUID().toString(),android.net.Uri.fromFile(original)) {}}.isFailure)
            assertArrayEquals(bytes,original.readBytes())
        }finally {original.delete()}
    }
    @Test fun coldCandidateQueryValidatesOriginalWithoutActivationAndRefusesPendingAtomicFile() {
        val target=storage("candidate");val store=NativeMigrationCandidateStore(context,target)
        assertNull(store.currentReceipt());val payload=MigrationActivationFixtures.payload().toString().toByteArray()
        val envelope=NativeMigrationCrypto.seal(payload,password)
        val receipt=store.stage(UUID.randomUUID().toString(),envelope,password)
        val before=MigrationActivationFixtures.tree(target)
        assertEquals(receipt,NativeMigrationCandidateStore(context,target.reopen(context)).currentReceipt())
        assertNull(target.metadata());assertNull(target.migrationPublicationReceipt());assertEquals(before,MigrationActivationFixtures.tree(target))
        val child=target.child(context,"migration-candidates");val root=File(child.coordinationKey)
        val slot=root.resolve(MigrationActivationFixtures.sha("candidate-v1".toByteArray())+".bin")
        val pending=File(slot.path+".new");pending.writeText("owned interrupted candidate pointer")
        val original=slot.readBytes();val failedTree=MigrationActivationFixtures.tree(child)
        try {assertTrue(runCatching {store.currentReceipt()}.isFailure);assertArrayEquals(original,slot.readBytes());assertEquals(failedTree,MigrationActivationFixtures.tree(child))}
        finally {pending.delete();payload.fill(0);envelope.fill(0)}
        assertEquals(receipt,store.currentReceipt())
    }
    @Test fun serialWriterBarrierRejectsUnresolvedFailureAndPreservesNewestRetry()=runBlocking {
        val target=storage("writer");target.write("metadata","original durable draft")
        val generation=target.beginRestore();val failed=CompletableDeferred<Unit>()
        target.enqueue("metadata","must not replace while restoring"){failed.complete(Unit)}
        failed.await();assertTrue(runCatching {target.awaitQueuedWrites()}.isFailure)
        assertEquals("original durable draft",target.read("metadata"))
        target.finishRestore(generation){}
        target.enqueue("metadata","latest in-memory draft"){throw AssertionError(it)}
        target.awaitQueuedWrites();val fresh=target.reopen(context);assertEquals("latest in-memory draft",fresh.read("metadata"))
        target.retireWriter();assertTrue(runCatching {target.write("metadata","retired same-generation writer")}.isFailure)
        fresh.write("metadata","fresh same-generation writer");assertEquals("fresh same-generation writer",fresh.read("metadata"))
    }
    @Test fun migratedEnrollmentStaysClosedUntilActualDurableEnabledPreference()=runBlocking {
        val source=storage("enrollment-source");val payload=MigrationActivationFixtures.payload();val records=payload.getJSONObject("records")
        records.keys().forEach {source.write(it,records.getString(it))}
        val client=MigrationActivationFixtures.client(payload);val identity=NotificationArchiveFixtures.identity()
        val auth=NotificationArchiveFixtures.authority(client)
        source.write(NativeMigrationForeground.IDENTITY,identity.raw)
        source.write(NativeMigrationForeground.JOURNAL,NotificationArchiveFixtures.v1(identity,NativeChatNoticeTarget.from(client.scope!!),auth.fingerprint))
        source.write(NativeMigrationForeground.AUTHORIZATIONS,auth.raw)
        val target=storage("enrollment-target");val receipt=NativeMigrationCandidateStore(context,target).stage(UUID.randomUUID().toString(),NativeMigrationExport(context,source).seal(password),password)
        NativeMigrationActivation(context,target,NativeMigrationNotificationActivationLease(target,receipt){true}).activate(receipt)
        val current=target.reopen(context);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            val gate=withContext(Dispatchers.Main.immediate){NativeMigrationNotificationEnrollment(current,scope)}
            suspend fun read(){val done=CompletableDeferred<Unit>();withContext(Dispatchers.Main.immediate){gate.refresh {done.complete(Unit)}};withTimeout(10_000){done.await()}}
            assertFalse(gate.allowed);read();assertFalse(gate.allowed);assertTrue(gate.required)
            NativeMigrationNotificationArchives.readOnlyArchives(current);read();assertFalse(gate.allowed)
            NativeChatNotificationStore(current).update {it.copy(showThreadTitle=false)};read();assertFalse(gate.allowed)
            NativeChatNotificationStore(current).update {it.copy(enabled=true)};read();assertTrue(gate.allowed);assertFalse(gate.required)
            assertNull(current.read(NativeMigrationForeground.AUTHORIZATIONS));assertNull(current.read(NativeMigrationForeground.IDENTITY))
            withContext(Dispatchers.Main.immediate){gate.dispose()};assertFalse(gate.allowed)
        }finally {scope.cancel()}
    }
}
