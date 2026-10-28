package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual encrypted storage locks/permits. Main progress is checked while another thread holds ioLock. */
class NativeMigrationStoragePublicationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun store()=NativeSessionStorage(context,"P7-owned-publication-${UUID.randomUUID()}").also {check(context.packageName=="com.easyai.agenthub.android.acceptance")}
    private suspend fun acquired(gate: CountDownLatch)=withContext(Dispatchers.IO){check(gate.await(5,TimeUnit.SECONDS))}
    @Test fun mainUsesPreparedChildAndRetiresWriterWithoutWaitingForHeldDiskLock()=runBlocking {
        val target=withContext(Dispatchers.IO){store().also {it.write("metadata","original unknown draft")}}
        val child=withContext(Dispatchers.IO){target.prepareChild(context,"owned-runtime")}
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val late=AtomicReference<Throwable?>()
        val work=launch(Dispatchers.IO){target.operationTransaction {
            entered.countDown();check(release.await(10,TimeUnit.SECONDS))
            late.set(runCatching {target.write("metadata","late retired write")}.exceptionOrNull())
        }}
        try {
            acquired(entered)
            withTimeout(2_000){withContext(Dispatchers.Main.immediate){assertSame(child,target.child(context,"owned-runtime"));target.retireWriter()}}
            assertFalse(work.isCompleted)
        }finally {release.countDown();work.join()}
        assertNotNull(late.get());assertEquals("original unknown draft",target.read("metadata"))
        val fresh=withContext(Dispatchers.IO){target.reopen(context)}
        fresh.write("metadata","fresh owner draft");assertEquals("fresh owner draft",fresh.read("metadata"))
    }
    @Test fun oneUseMainPublicationHasNoDiskLockAndRejectsSupersededRestore()=runBlocking {
        val target=withContext(Dispatchers.IO){store().also {it.write("metadata","original local snapshot")}}
        val permit=withContext(Dispatchers.IO){target.prepareRestorePublication(target.beginRestore())}
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val work=launch(Dispatchers.IO){target.operationTransaction {entered.countDown();check(release.await(10,TimeUnit.SECONDS))}}
        var published=false
        try {
            acquired(entered)
            withTimeout(2_000){withContext(Dispatchers.Main.immediate){target.finishPreparedRestore(permit){published=true}}}
            assertTrue(published);assertFalse(work.isCompleted)
            assertTrue(runCatching {target.finishPreparedRestore(permit){error("Permit cannot publish twice")}}.isFailure)
        }finally {release.countDown();work.join()}
        val stale=withContext(Dispatchers.IO){target.prepareRestorePublication(target.beginRestore())}
        val newer=withContext(Dispatchers.IO){target.beginRestore()}
        withContext(Dispatchers.Main.immediate){assertTrue(runCatching {target.finishPreparedRestore(stale){error("Stale snapshot cannot publish")}}.isFailure)}
        withContext(Dispatchers.IO){target.finishRestore(newer){}}
        val finished=withContext(Dispatchers.IO){target.beginRestore().let {generation->
            target.prepareRestorePublication(generation).also {target.finishRestore(generation){}}
        }}
        withContext(Dispatchers.Main.immediate){assertTrue(runCatching {target.finishPreparedRestore(finished){error("A completed restore cannot publish again")}}.isFailure)}
        assertEquals("original local snapshot",target.read("metadata"))
    }
    @Test fun interruptedPublicationInvalidatesPreparedChildWithoutRecoveringOrChangingOriginalFiles()=runBlocking {
        val target=withContext(Dispatchers.IO){store().also {it.write("metadata","original source")}}
        withContext(Dispatchers.IO){target.prepareChild(context,"owned-runtime")}
        val before=MigrationActivationFixtures.tree(target)
        assertTrue(withContext(Dispatchers.IO){runCatching {
            target.publishMigration(UUID.randomUUID().toString(),"a".repeat(64),mapOf("metadata" to "candidate source")){if(it==1)throw java.io.IOException("owned publication interruption")}
        }}.isFailure)
        assertEquals(before,MigrationActivationFixtures.tree(target))
        withTimeout(2_000){withContext(Dispatchers.Main.immediate){assertTrue(runCatching {target.child(context,"owned-runtime")}.isFailure)}}
        assertEquals("original source",withContext(Dispatchers.IO){target.reopen(context).read("metadata")})
    }
}
