package com.agenthub.android

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Two named methods run in separate instrumentation processes on an isolated acceptance install. */
class Life01FinalColdDraftTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun storage()=NativeSessionStorage(context,"life01-final-cold-owned")
    private val data="synthetic original attachment bytes 100%".toByteArray()
    private suspend fun waitFor(predicate: ()->Boolean)=withTimeout(15_000) { while(!withContext(Dispatchers.Main) { predicate() }) delay(25) }
    @Test fun prepareDraftAndAttachment()=runBlocking {
        ChatManagementFixture().use { fixture ->
            fixture.writableHistory=true
            val store=storage();val session=withContext(Dispatchers.Main) { MobileSession(store,NativeHostReads { "" }).also { it.restore() } }
            waitFor { !session.restoring }
            withContext(Dispatchers.Main) { session.enterForeground();session.connect("cold fixture",fixture.server.endpoint,"");session.selectThread(session.threads.first { it.id==ChatManagementFixture.first });session.text="cold original draft" }
            waitFor { session.canStop && !session.loading && !session.historyLoading }
            withContext(Dispatchers.Main) { session.importAttachments(session.attachmentImportDestination(),listOf({ LocalAttachmentInput("cold-original.txt","text/plain",data) }))!!.await() }
            waitFor { session.attachments.size==1 }
            withTimeout(10_000) { while(withContext(Dispatchers.IO) { store.metadata()?.toString()?.contains("cold-original.txt")!=true })delay(25) }
            context.filesDir.resolve("life01-final-cold-pid").writeText(Process.myPid().toString())
            withContext(Dispatchers.Main) { session.leaveForeground() }
            assertTrue(fixture.posts.isEmpty())
        }
    }
    @Test fun restoreDraftAndBytesInNewProcessWithoutControlProof()=runBlocking {
        assertNotEquals(context.filesDir.resolve("life01-final-cold-pid").readText(),Process.myPid().toString())
        val store=storage();val session=withContext(Dispatchers.Main) { MobileSession(store,NativeHostReads { "" }).also { it.restore() } }
        waitFor { !session.restoring }
        withContext(Dispatchers.Main) {
            assertEquals("cold original draft",session.text);assertEquals(1,session.attachments.size)
            assertFalse(session.canStop);assertFalse(session.canCreateProjectless)
        }
        val manifest=withContext(Dispatchers.Main) { session.attachments.single().manifest }
        assertArrayEquals(data,NativeDraftFiles(store).bytes(manifest))
    }
}
