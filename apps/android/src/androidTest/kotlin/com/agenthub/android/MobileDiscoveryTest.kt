package com.agenthub.android

import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MobileDiscoveryTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val session get() = ui.activity.session
    private val pairing get() = arguments.getString("discoveryPairing")!!
    @Before fun reset() { ui.runOnIdle { session.resetAcceptance() } }
    private fun form() { ui.onNodeWithTag("client-selector").performClick(); ui.onNodeWithTag("add-client").performClick() }
    private fun method(value: String) { ui.onNodeWithTag("codex-connection-method").performClick(); ui.onNodeWithTag("codex-method-$value").performClick() }
    private fun shot(name: String) {
        ui.waitForIdle(); val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200,5_000)
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"acceptance/A3").apply { mkdirs() }
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        try { File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
    }
    private fun permissionButton(vararg ids: String): Boolean {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val deadline=System.currentTimeMillis()+10_000
        while(System.currentTimeMillis()<deadline) {
            val root=automation.rootInActiveWindow
            if(root != null) for(id in ids) for(packageName in listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller")) {
                val button=root.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                if(button != null) return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            Thread.sleep(50)
        }
        return false
    }
    @Test fun descriptorValidationAndPrivateEndpointBounds() {
        val parsed=CodexDescriptor.parsePairing(pairing)
        assertEquals("codex",parsed.target.agentId); assertEquals(64,parsed.fingerprint.length)
        val vectors=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("state-v1.json").bufferedReader().readText()).getJSONArray("discovery")
        for(index in 0 until vectors.length()) { val case=vectors.getJSONObject(index); assertEquals(case.getString("name"),case.getBoolean("valid"),com.agenthub.core.CodexDiscoveryContract.validate(case.getString("json"))==null) }
        for(endpoint in listOf("https://10.0.0.1", "https://172.16.0.1", "https://192.168.1.2", "https://100.64.0.1", "https://computer.local.", "https://computer.example.ts.net", "https://[::1]", "https://[fc00::1]", "https://[fe80::1]", "https://[::ffff:192.168.1.1]")) assertTrue(CodexEndpoints.local(endpoint).startsWith("https://"))
        for(endpoint in listOf("https://8.8.8.8", "https://172.32.0.1", "https://100.128.0.1", "http://127.0.0.1", "https://public.example", "https://[2001:4860::1]", "https://me@computer.local", "https://computer.local/path", "https://computer.local?x=1", "https://computer.local:0")) {
            try { CodexEndpoints.local(endpoint); fail(endpoint) } catch (_: IllegalArgumentException) {}
        }
        assertEquals("https://computer.local",CodexEndpoints.local("https://COMPUTER.local.:443/"))
        val json=JSONObject(String(android.util.Base64.decode(pairing.substringAfter("descriptor="),android.util.Base64.URL_SAFE)))
        for(change in listOf<(JSONObject)->Unit>({ it.put("version",2) },{ it.put("agentId","other") },{ it.put("token","too-short") },{ it.put("unknown",true) },{ it.getJSONObject("target").put("hostId","") },{ it.put("fingerprint","wrong") })) {
            val bad=JSONObject(json.toString()); change(bad)
            val uri="codex-host://pair?descriptor="+android.util.Base64.encodeToString(bad.toString().toByteArray(),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            try { CodexDescriptor.parsePairing(uri); fail("Invalid descriptor accepted") } catch (_: IllegalArgumentException) {}
        }
        try { CodexDescriptor.parsePairing(pairing+"&extra=1"); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun nativeQRPairingRejectsWrongCertificateThenReadsHistory() {
        form(); method("扫码连接")
        ui.onNodeWithTag("codex-qr-description").performScrollTo().performTextReplacement(arguments.getString("discoveryWrongPin")!!)
        ui.onNodeWithTag("codex-connect-qr").performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("client-form-error").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(session.hasClient)
        shot("pairing-wrong-certificate-rejected")
        ui.onNodeWithTag("codex-qr-description").performScrollTo().performTextReplacement(pairing)
        ui.onNodeWithTag("codex-connect-qr").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.hasClient }
        ui.onNodeWithTag("client-fixture-paired-codex").performClick()
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { session.messages.any { it.id=="paired-reply" } }
        assertNotNull(session.selectedClient.codexDirect)
        ui.onNodeWithTag("send-message").assertIsNotEnabled()
        shot("pairing-native-read-history")
    }
    @Test fun pairedTLSReadTargetRedirectAndPersistenceStayScoped() = runBlocking {
        val offer=CodexDescriptor.parsePairing(pairing)
        val client=MobileClient(offer.target.hostId,offer.name,offer.target,offer.routes.first().endpoint,false,"desktop-runtime",false,"synthetic-reader",true,PairedCodexConnection(offer.target,offer.fingerprint,offer.routes))
        val reader=NativeHostReads { offer.token!! }
        assertEquals(offer.target.hostId,reader.workspace(client).hostId)
        assertEquals("paired-reply",reader.history(client,"paired-read-thread").messages.single().id)
        assertTrue(sameScope(client.scope,decodeClient(encodeClient(client)).scope))
        assertEquals(client.codexDirect,decodeClient(encodeClient(client)).codexDirect)
        val bad=listOf(client.copy(id="wrong-host"),client.copy(scope=TargetScope("codex",offer.target.accountAuthority,offer.target.accountSubject,offer.target.hostId,offer.target.workspaceId,"wrong-runtime")),client.copy(codexDirect=client.codexDirect!!.copy(fingerprint="0".repeat(64))),client.copy(endpoint="https://localhost:1"))
        for(candidate in bad) try { reader.workspace(candidate); fail("Wrong identity accepted") } catch (_: Exception) {}
        try { reader.history(client,"redirect"); fail("Redirect followed") } catch(failure: HostReadError) { assertTrue(failure.message!!.contains("重定向")) }
        try { reader.history(client,"wrong-thread"); fail("Wrong thread accepted") } catch(failure: HostReadError) { assertTrue(failure.message!!.contains("不匹配")) }
    }
    @Test fun localPermissionAndNSDUseHintsOnlyAndStopOnBackground() {
        form()
        if(Build.VERSION.SDK_INT>=37 && !session.discovery!!.permitted()) {
            ui.onNodeWithTag("allow-local-network").performScrollTo().performClick()
            assertTrue("The system local network permission prompt must be answered",permissionButton("permission_allow_button","permission_allow_foreground_only_button"))
            ui.waitUntil(10_000) { session.discovery!!.permitted() }
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=context.getSystemService(NsdManager::class.java)
        val registered=CountDownLatch(1)
        var registrationError: Int? = null
        val id="fixture-nsd-unpaired"
        val service=NsdServiceInfo().apply { serviceName="Agent Hub synthetic NSD"; serviceType="_codex-host._tcp."; port=8862; setAttribute("hostId",id) }
        val listener=object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(value: NsdServiceInfo) { registered.countDown() }
            override fun onRegistrationFailed(value: NsdServiceInfo,code: Int) { registrationError=code; registered.countDown() }
            override fun onServiceUnregistered(value: NsdServiceInfo) {}
            override fun onUnregistrationFailed(value: NsdServiceInfo,code: Int) {}
        }
        manager.registerService(service,NsdManager.PROTOCOL_DNS_SD,listener)
        try {
            assertTrue(registered.await(10,TimeUnit.SECONDS))
            assertNull("NSD registration failed: $registrationError",registrationError)
            ui.waitUntil(20_000) { session.discovery!!.hosts.any { it.id==id } }
            assertFalse(session.discovery!!.hosts.first { it.id==id }.known)
            ui.onNodeWithTag("codex-discovered-$id").performScrollTo().performClick()
            assertFalse(session.hasClient)
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("client-form-error").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("codex-discovery-rescan").assertExists()
            ui.onNodeWithTag("codex-scan-qr").assertDoesNotExist()
            ui.onNodeWithTag("client-address").assertDoesNotExist()
            shot("nsd-unreachable-explicit-connect-stays-discovery")
            ui.runOnIdle { session.discovery!!.stop(); session.discovery!!.start() }
            assertFalse(session.hasClient)
        } finally { manager.unregisterService(listener) }
    }
    @Test fun cameraDeniedKeepsPasteAvailableAndQRDecoderReadsLocalPixels() {
        form(); method("扫码连接"); ui.onNodeWithTag("codex-scan-qr").performScrollTo().performClick()
        shot("qr-camera-system-prompt")
        assertTrue("The camera permission denial must be exercised",permissionButton("permission_deny_button"))
        ui.onNodeWithTag("qr-camera-denied").assertExists()
        shot("qr-camera-denied")
        ui.onNodeWithTag("qr-scanner-close").performClick()
        ui.onNodeWithTag("codex-qr-description").performScrollTo().assertExists()
        val regression=InstrumentationRegistry.getInstrumentation().context.assets.open("dense-qr.txt").bufferedReader().use { it.readText().trim() }
        for(value in listOf(pairing,regression)) {
            val matrix=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,640,640)
            var pixels=ByteArray(640*640) { index -> if(matrix[index%640,index/640]) 0 else 255.toByte() }
            repeat(4) {
                assertEquals(value,NativeQRDecoder.decode(pixels,640,640))
                val previous=pixels
                pixels=ByteArray(previous.size) { index -> previous[(639-index%640)*640+index/640] }
            }
        }
        assertNull(NativeQRDecoder.decode(ByteArray(640*640) { 255.toByte() },640,640))
    }
    @Test fun connectionMethodShowsOnlySelectedFormAndKeepsSavedClients() {
        form()
        ui.onNodeWithTag("codex-discovery-rescan").assertExists()
        ui.onNodeWithTag("client-address").assertDoesNotExist()
        ui.onNodeWithTag("codex-qr-description").assertDoesNotExist()
        shot("connection-default-discovery")
        method("扫码连接")
        ui.onNodeWithTag("codex-qr-description").assertExists()
        ui.onNodeWithTag("client-address").assertDoesNotExist()
        ui.onNodeWithTag("codex-discovery-rescan").assertDoesNotExist()
        shot("connection-selected-qr")
        method("手动连接")
        ui.onNodeWithTag("client-address").assertExists()
        ui.onNodeWithTag("codex-qr-description").assertDoesNotExist()
        ui.onNodeWithTag("client-address").performTextReplacement(arguments.getString("readFixture")!!)
        ui.onNodeWithTag("client-address").performImeAction()
        ui.onNodeWithTag("connect-client").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.hasClient && !session.loading }
        ui.onNodeWithTag("add-client").performScrollTo().performClick()
        ui.onNodeWithTag("codex-discovered-fixture-android-host").assertExists()
        ui.onNodeWithText("已连接").assertExists()
        shot("connection-discovery-saved-client")
    }
}
