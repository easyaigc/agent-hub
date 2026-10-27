package com.agenthub.android

import android.graphics.Bitmap
import android.util.AtomicFile
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MobileLocalRecoveryUiTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        val frames=CountDownLatch(1);ui.runOnUiThread { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,TimeUnit.SECONDS));i.uiAutomation.waitForIdle(300,5_000)
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val run=InstrumentationRegistry.getArguments().getString("managementRunId") ?: UUID.randomUUID().toString()
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder=File(i.targetContext.filesDir,"acceptance/P4-recovery/$run").apply { mkdirs() }
        val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @Test fun nativeRecoveryBlocksNormalProductAndFailedRetryPreservesOriginalThenRestoresDraft() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString().replace("-","")
        val namespace="P4-ui-recovery-$id";val storage=NativeSessionStorage(context,namespace,"p4_ui_recovery_$id")
        lateinit var writer: MobileSession
        main { writer=MobileSession(storage);writer.restore() }
        ui.waitUntil(5_000) { !writer.restoring }
        main { writer.setDemoMode(true);writer.text="P4 original unsent draft";writer.changeLanguage("zh-Hans");writer.changeAppearance("dark");writer.leaveForeground() }
        val barrier=CountDownLatch(1);storage.enqueueSnapshot("owned-barrier",{ barrier.countDown() },{ barrier.countDown() })
        assertTrue(barrier.await(5,TimeUnit.SECONDS))
        val hash=MessageDigest.getInstance("SHA-256").digest("metadata".toByteArray()).joinToString("") { "%02x".format(it) }
        val file=File(File(context.noBackupFilesDir,namespace),"$hash.bin");val valid=file.readBytes()
        storage.write("metadata","{P4_OWNED_UI_INVALID_METADATA");val failed=file.readBytes()
        lateinit var session: MobileSession
        main { ui.activity.session.leaveForeground();session=MobileSession(storage);session.changeLanguage("zh-Hans");session.restore();ui.activity.setContent { AgentHubApp(session) } }
        try {
            ui.waitUntil(5_000) { session.localRecoveryRequired&&!session.restoring }
            ui.onNodeWithTag("local-recovery-required").assertExists()
            ui.onNodeWithText("本地数据暂时无法读取").assertExists();ui.onNodeWithTag("local-recovery-icon").assertExists()
            ui.onNodeWithTag("open-sidebar").assertDoesNotExist();ui.onNodeWithTag("composer").assertDoesNotExist()
            assertFalse(session.canCompose);shot("unreadable-original-protected")
            ui.onNodeWithTag("retry-local-recovery").performClick()
            ui.waitUntil(5_000) { session.localRecoveryRequired&&!session.restoring }
            ui.onNodeWithTag("retry-local-recovery").assertIsEnabled();assertArrayEquals(failed,file.readBytes())
            val atomic=AtomicFile(file);val out=atomic.startWrite();out.write(valid);atomic.finishWrite(out)
            ui.onNodeWithTag("retry-local-recovery").performClick()
            ui.waitUntil(5_000) { !session.localRecoveryRequired&&!session.restoring }
            ui.onNodeWithTag("local-recovery-required").assertDoesNotExist()
            ui.onNodeWithTag("open-sidebar").assertExists();assertEquals("P4 original unsent draft",session.text)
            assertTrue(session.demo);assertEquals("dark",session.appearance);assertEquals("zh-Hans",session.language);assertNull(session.error)
            shot("retry-restores-original-draft")
        } finally { main { session.leaveForeground() } }
    }
}
