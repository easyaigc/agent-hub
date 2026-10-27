package com.agenthub.android

import android.util.AtomicFile
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Display language cannot turn read-only metadata into authority or overwrite unreadable storage. */
class MobileReadingCopyTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"reading-copy");session.resetAcceptance() }
    }
    @After fun finish() { ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } } }
    @Test fun everyReadOnlyNoticeTracksLanguageWithoutChangingOriginalMetadataDraftOrAuthority() {
        data class Case(val archived: Boolean,val management: Boolean,val mode: String,val zh: String,val en: String)
        val cases=listOf(
            Case(true,true,"desktop-runtime","对话已归档，仅支持查看，可在对话选项中恢复。","This conversation is archived and read-only. Restore it from conversation options."),
            Case(true,false,"desktop-runtime","对话已归档，仅支持查看，可在电脑端恢复。","This conversation is archived and read-only. Restore it on the computer."),
            Case(false,false,"history-only","历史连接仅支持查看，草稿保存在手机。","History connections are read-only. Your draft stays on this phone."),
            Case(false,false,"desktop-runtime","等待电脑确认连接与控制权，草稿保存在手机。","Waiting for the computer to confirm connection and control. Your draft stays on this phone."))
        for((index,c)in cases.withIndex()) {
            val host="reading-copy-host-"+UUID.randomUUID();val thread="reading-copy-thread-"+index
            val target=JSONObject().put("agentId","codex").put("hostId",host).put("workspaceId","reading-workspace").put("runtimeId","reading-runtime")
            val reader=object: HostReads {
                override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(
                    JSONObject().put("hostId",host).put("name","原连接 100% %@").put("connectionMode",c.mode).put("readTarget",target)
                        .put("agentConnectionState",JSONObject().put("runtimeConnected",false))
                        .put("capabilities",JSONArray(if(c.management)listOf("threads:manage") else emptyList<String>()))
                        .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id",thread).put("hostId",host)
                            .put("title","原聊天 100% %@").put("state","idle").put("archived",c.archived))).toString())
                override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(
                    JSONObject().put("hostId",host).put("threadId",threadId).put("readTarget",target).put("messages",JSONArray().put(JSONObject()
                        .put("id","reading-original-message").put("role","assistant").put("text","历史原文 100% %@"))).toString())
            }
            main { session.resetAcceptance(reader);session.changeLanguage("zh-Hans")
                session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme") ?: "light")
                session.connect("原连接 100% %@","https://fixture.invalid","") }
            ui.waitUntil(10_000) { !session.loading&&session.threads.size==1 }
            main { session.selectThread(session.threads.single());session.text="原草稿 100% %@" }
            ui.waitUntil(10_000) { !session.historyLoading }
            assertEquals("Readonly case $index history: "+session.error,1,session.messages.size)
            val originalClient=session.selectedClient;val originalThreads=session.threads;val originalMessages=session.messages
            assertFalse(session.canCompose);assertEquals(c.management,session.supportsChatManagement)
            ui.onNodeWithTag("readonly-notice").performScrollTo().assertTextEquals(c.zh)
            main { session.changeLanguage("en") }
            ui.onNodeWithTag("readonly-notice").performScrollTo().assertTextEquals(c.en)
            ui.onNodeWithTag("send-message").performScrollTo().assertIsNotEnabled()
            owned.nodeShot(ui.onNodeWithTag("readonly-notice"),"english-readonly-$index")
            main { session.changeLanguage("zh-Hans") }
            ui.onNodeWithTag("readonly-notice").performScrollTo().assertTextEquals(c.zh)
            assertEquals(originalClient,session.selectedClient);assertEquals(originalThreads,session.threads)
            assertEquals(originalMessages,session.messages);assertEquals("原草稿 100% %@",session.text)
            assertTrue(session.operationRecords.isEmpty());assertTrue(session.detachedReplies.isEmpty());assertFalse(session.canCompose)
        }
        File(owned.folder,"readonly-copy-proof.json").writeText(JSONObject().put("cases",4).put("languages",2)
            .put("originalMetadataAndDraftPreserved",true).put("writeAuthorityNeverGranted",true).toString())
    }
    @Test fun recoveryButtonCopyAndActualFailedRetryKeepEncryptedOriginalUntilRestored() {
        val id=UUID.randomUUID().toString().replace("-","");val namespace="P6-reading-recovery-$id"
        val storage=NativeSessionStorage(activity,namespace,"p6_reading_recovery_$id")
        lateinit var writer: MobileSession
        main { writer=MobileSession(storage);writer.restore() };ui.waitUntil(10_000) { !writer.restoring }
        main { writer.setDemoMode(true);writer.text="原恢复草稿 100% %@";writer.changeLanguage("en");writer.changeAppearance("dark");writer.leaveForeground() }
        val barrier=CountDownLatch(1);storage.enqueueSnapshot("owned-barrier",{ barrier.countDown() },{ barrier.countDown() });assertTrue(barrier.await(5,TimeUnit.SECONDS))
        val hash=MessageDigest.getInstance("SHA-256").digest("metadata".toByteArray()).joinToString("") { "%02x".format(it) }
        val file=File(File(activity.noBackupFilesDir,namespace),"$hash.bin");val original=file.readBytes()
        storage.write("metadata","{P6_OWNED_INVALID_METADATA");val unreadable=file.readBytes()
        lateinit var recovering: MobileSession
        main { session.leaveForeground();recovering=MobileSession(storage);recovering.restore();activity.setContent { AgentHubApp(recovering) } }
        try {
            ui.waitUntil(10_000) { recovering.localRecoveryRequired&&!recovering.restoring }
            main { recovering.changeLanguage("en") }
            ui.onNodeWithText("Local data is temporarily unavailable").assertExists()
            ui.onNodeWithTag("retry-local-recovery").assertTextEquals("Retry reading").assertIsEnabled()
            assertArrayEquals(unreadable,file.readBytes())
            // The production stateless busy component is an auxiliary UI fixture;
            // actual encrypted failed/successful retries remain separate below.
            var busyRetryCalls=0
            main { activity.setContent { ProductTextProvider("en") { androidx.compose.material3.MaterialTheme {
                LocalRecoveryNotice(true) { busyRetryCalls++ }
            } } } }
            ui.onNodeWithTag("retry-local-recovery").assertTextEquals("Reading…").assertIsNotEnabled()
                .performTouchInput { click(center) }
            assertEquals(0,busyRetryCalls);assertArrayEquals(unreadable,file.readBytes())
            owned.nodeShot(ui.onNodeWithTag("local-recovery-required"),"english-recovery-reading-component")
            main { activity.setContent { AgentHubApp(recovering) } }
            ui.onNodeWithTag("retry-local-recovery").performClick()
            ui.waitUntil(10_000) { recovering.localRecoveryRequired&&!recovering.restoring }
            ui.onNodeWithTag("retry-local-recovery").assertTextEquals("Retry reading").assertIsEnabled()
            assertArrayEquals(unreadable,file.readBytes())
            val atomic=AtomicFile(file);val out=atomic.startWrite();out.write(original);atomic.finishWrite(out)
            ui.onNodeWithTag("retry-local-recovery").performClick()
            ui.waitUntil(10_000) { !recovering.localRecoveryRequired&&!recovering.restoring }
            assertEquals("原恢复草稿 100% %@",recovering.text);assertEquals("en",recovering.language);assertEquals("dark",recovering.appearance)
            ui.onNodeWithTag("local-recovery-required").assertDoesNotExist()
            File(owned.folder,"recovery-copy-proof.json").writeText(JSONObject().put("failedEncryptedBytesPreserved",true)
                .put("originalDraftLanguageAppearanceRestored",true).put("busyPresentationIsolatedProductionComponent",true).toString())
        } finally { main { recovering.leaveForeground();activity.setContent { AgentHubApp(session) } } }
    }
}
