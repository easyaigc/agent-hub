package com.agenthub.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

/** A fresh test session; default encrypted drafts are observed and never reset or restored over. */
internal class OwnedControlAcceptance(private val activity: MainActivity, private val category: String) {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val defaultStorage=NativeSessionStorage(activity)
    private val defaults=defaultStorage.metadata().let { metadata->listOf("drafts","draftFiles","draftVersions","attachmentImportGenerations").associateWith { metadata?.opt(it)?.toString() } }
    private val nonce=java.util.UUID.randomUUID().toString()
    val storage=NativeSessionStorage(activity,"P4-owned-control-$nonce")
    val session=MobileSession(storage)
    val folder: File
    init {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        require(!activity.session.restoring)
        val run=InstrumentationRegistry.getArguments().getString("ownerCallbacksRun") ?: "owned-model"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")));require(category.matches(Regex("[a-zA-Z0-9_-]+")))
        folder=File(activity.filesDir,"acceptance/P4-owner-callbacks/$run/$category-$nonce").apply { mkdirs() }
        activity.readNetworkMonitor.stop();activity.session.discovery?.stop();activity.session.easyWorkDiscovery?.stop();activity.session.leaveForeground()
        activity.setContent { AgentHubApp(session) { } }
    }
    fun verifyDefault() {
        val now=defaultStorage.metadata()
        for((key,value)in defaults)assertEquals("Owned controls preserve default $key",value,now?.opt(key)?.toString())
        File(folder,"default-storage-proof.json").writeText(JSONObject().put("draftsPreserved",true).put("draftFilesPreserved",true).put("draftVersionsPreserved",true).put("attachmentImportGenerationsPreserved",true).put("namespace",nonce).toString())
    }
    /** Capture only the app-owned semantic subtree, including a native popup's rows. */
    fun nodeShot(node: SemanticsNodeInteraction,name: String) {
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val bitmap=node.captureToImage().asAndroidBitmap()
        File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        File(folder,"$name-window.json").writeText(JSONObject().put("method","Compose.captureToImage.owned-semantic-subtree").put("package",i.targetContext.packageName).put("width",bitmap.width).put("height",bitmap.height).put("targetKey",session.targetKey).put("threadId",session.selectedThread).put("fullSystemComposite",false).toString())
    }
}
