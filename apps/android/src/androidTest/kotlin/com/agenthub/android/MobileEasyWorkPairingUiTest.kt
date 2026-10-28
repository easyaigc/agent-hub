package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual production Provider/form/session/HTTP/TLS. Synthetic issuer/desktop only; no camera or model. */
class MobileEasyWorkPairingUiTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {
        override fun before() {
            val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
            if(InstrumentationRegistry.getArguments().getString("p8UiForeground")!="1")return
            val fd=i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity")
            val result=android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use {it.readText()};assertTrue(result.contains("Status: ok"))
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val build get()=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PairingUiBuild")))
    private val config get()=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PairingUiConfig")))
    private lateinit var owned: PairingUiIsolation
    private val session get()=owned.session
    private val storage get()=owned.storage
    private val saved=AtomicInteger(0)
    private val caseId=UUID.randomUUID().toString()
    private var started=false
    private lateinit var client: MobileClient
    private lateinit var original: SavedOperation
    private lateinit var originalJournal: String
    private val originalText="P8 synthetic original unsent draft "+UUID.randomUUID()
    private val vector by lazy {JSONObject(instrumentation.context.assets.open("easywork-crypto-vector.json").bufferedReader().use {it.readText()})}
    private val credential get()=EasyWorkCredential.decode(vector.getJSONObject("credential"))
    private val origin get()=config.getString("authOrigin")
    private fun <T> main(block: suspend ()->T): T=runBlocking {withContext(Dispatchers.Main) {block()}}
    private fun api(path: String,body: JSONObject?=null): JSONObject {
        require(path in setOf("/health","/begin-case","/stats","/offer","/resolve","/end-case"));val c=URL(config.getString("controlOrigin")+path).openConnection() as HttpURLConnection
        try {c.connectTimeout=1500;c.readTimeout=1500;c.instanceFollowRedirects=false;c.setRequestProperty("X-P8-Fixture",config.getString("instanceId"));if(body!=null){val b=body.toString().toByteArray();c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(b.size);c.outputStream.use {it.write(b)}};check(c.responseCode==200);return JSONObject(c.inputStream.bufferedReader().use {it.readText()})}finally {c.disconnect()}
    }
    private fun stats()=api("/stats").also {assertEquals(caseId,it.getString("caseId"));assertEquals(0,it.getJSONArray("errors").length());assertEquals(0,it.getInt("realDesktopRequests"));assertEquals(0,it.getInt("realModelInputs"))}
    @Before fun isolate() {
        require(instrumentation.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&instrumentation.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        require(config.getInt("version")==1&&config.getString("fixture")=="p8-synthetic-pairing-ui-v1")
        fun hashFile(file: java.io.File): String {val digest=java.security.MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}};return digest.digest().joinToString("") {"%02x".format(it)}}
        for(key in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(key).matches(Regex("[a-f0-9]{64}")))
        require(build.getString("appSha256")==hashFile(java.io.File(instrumentation.targetContext.applicationInfo.sourceDir)))
        require(build.getString("runnerSha256")==hashFile(java.io.File(instrumentation.context.applicationInfo.sourceDir)))
        for((key,scheme) in listOf("authOrigin" to "http","tlsOrigin" to "https","controlOrigin" to "http")){val u=URI(config.getString(key));require(u.scheme==scheme&&u.host=="127.0.0.1"&&u.port in 1..65535&&u.rawPath.isNullOrEmpty()&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null)}
        require(listOf("authOrigin","tlsOrigin","controlOrigin").map {URI(config.getString(it)).port}.distinct().size==3)
        require(config.getString("sourceFixtureSha256")==easyHash(instrumentation.context.assets.open("easywork-crypto-vector.json").use {it.readBytes()}))
        val h=api("/health");for(k in listOf("instanceId","sourceSha256","certificateSha256","authOrigin","tlsOrigin","controlOrigin"))require(config.getString(k)==h.getString(k));assertTrue(h.getBoolean("onlyLoopback"));assertFalse(h.getBoolean("realDesktop"));assertFalse(h.getBoolean("realModel"))
        ui.runOnIdle {owned=PairingUiIsolation();session.resetAcceptance()}
    }
    private inner class PairingUiIsolation {
        private val context=instrumentation.targetContext
        private val defaultRoot=java.io.File(context.noBackupFilesDir,"mobile-v1")
        private fun fingerprints(): Map<String,String> = if(!defaultRoot.exists())emptyMap()else defaultRoot.walkTopDown().filter {it.isFile}.associate {file->
            val digest=java.security.MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
            file.relativeTo(defaultRoot).path to digest.digest().joinToString("") {"%02x".format(it)}
        }
        private val defaults=fingerprints()
        val storage=NativeSessionStorage(context,"p8-pairing-ui-$caseId","p8-pairing-ui-$caseId")
        val session=MobileSession(storage)
        val folder=java.io.File(context.filesDir,"acceptance/p8-pairing-ui/$caseId").apply {check(!exists());mkdirs()}
        fun verifyDefault() {
            assertEquals("Blank test Activity must preserve every original encrypted record",defaults,fingerprints())
            java.io.File(folder,"default-storage-proof.json").writeText(JSONObject().put("defaultEncryptedFilesUnchanged",true).put("recordCount",defaults.size).put("mainActivityStarted",false).put("namespace","p8-pairing-ui-$caseId").toString())
        }
        fun nodeShot(node: SemanticsNodeInteraction,name: String) {
            assertEquals(context.packageName,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            val bitmap=node.captureToImage().asAndroidBitmap();java.io.File(folder,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    private fun show(mode: String="form",language: String="zh-Hans",dark: Boolean=false,editing: Boolean=false) {
        api("/begin-case",JSONObject().put("caseId",caseId).put("mode",mode));started=true
        val identity=EasyWorkIdentity(if(mode in listOf("form","wrong-pin","wrong-host-target","wrong-binding","expired-local"))credential.binding.mobileId else "p8-fence-$caseId-a",credential.privateKey,credential.binding.mobilePublicKey)
        storage.write("easywork-device-identity",identity.json().toString())
        val target=credential.binding.target.scope();client=MobileClient("p8-synthetic-original", "Original EasyWork",target,origin,false,"easywork-runtime",false,agentConnection=AgentConnection(target,listOf(AgentCandidate("synthetic-only","relay-http",origin)),"missing-public-fixture-ref"))
        main {session.select(client);session.text=originalText}
        val journal=NativeOperationJournal(storage);original=runBlocking {val r=journal.prepare(client,"original-thread","reply",ReplyIntent(client.id,"original-thread",UUID.randomUUID().toString(),"P8 synthetic immutable original input",emptyList(),"next-turn").body());journal.markSubmitting(r.target,r.id);journal.uncertain(r.target,r.id,"synthetic original acknowledgement unknown")};originalJournal=storage.read("operations:${original.target}")!!
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme()) {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {ProductTextProvider(language) {ConnectionPage(session,if(editing)client else null) {saved.incrementAndGet()}}}}}}
        ui.waitForIdle()
    }
    @After fun finish() {
        if(::owned.isInitialized){ui.runOnIdle {ui.activity.setContent {};session.leaveForeground()};ui.waitForIdle();owned.verifyDefault()}
        if(started){val s=stats();java.io.File(owned.folder,"pairing-$caseId.json").writeText(JSONObject().put("caseId",caseId).put("configSha256",easyHash(easyCanonical(config).toByteArray())).put("api",android.os.Build.VERSION.SDK_INT).put("build",build).put("savedCallbacks",saved.get()).put("stats",s).put("syntheticOnly",true).toString());api("/end-case",JSONObject().put("caseId",caseId))}
    }
    private fun tap(tag: String,scroll: Boolean=true) {val n=ui.onNodeWithTag(tag);if(scroll)n.performScrollTo();n.assertIsDisplayed().performClick()}
    private fun field(tag: String,value: String) {ui.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)}
    private fun method(value: String) {tap("easywork-connection-method");tap("easywork-method-$value",false)}
    private fun easy() {tap("agent-provider-easywork")}
    private fun code() {easy();method("通过中继连接");field("easywork-relay-url",origin);field("easywork-relay-code","0123456789ABCDEF");tap("easywork-pair-relay")}
    private fun qr(value: String) {easy();method("扫码连接");field("easywork-qr-description",value);tap("easywork-pair-lan")}
    private fun pending() {ui.waitUntil(8_000){ui.onAllNodesWithTag("easywork-connecting").fetchSemanticsNodes().isNotEmpty()&&stats().getJSONArray("requests").length()>=2};ui.onNodeWithTag("agent-provider-codex").assertIsNotEnabled();ui.onNodeWithTag("easywork-pair-relay").assertIsNotEnabled();ui.onNodeWithTag("easywork-relay-code").assertIsNotEnabled()}
    private fun resolve(value: String)=api("/resolve",JSONObject().put("caseId",caseId).put("status",value))
    private fun error(message: EasyWorkConnectionMessage,language: String="zh-Hans") {ui.waitUntil(8_000){ui.onAllNodesWithTag("easywork-pair-error").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("easywork-pair-error").performScrollTo().assertTextEquals(NativeL10n(instrumentation.targetContext,language).tr(message.key));assertEquals(0,saved.get());assertFalse(main {session.connectionBusy})}
    private fun preserved() {assertEquals(originalText,main {session.text});assertEquals(client.scope,main {session.selectedClient.scope});assertEquals(originalJournal,storage.read("operations:${original.target}"));val r=runBlocking {NativeOperationJournal(storage).records(original.target)}.single();assertEquals(original.id,r.id);assertEquals(original.intent,r.intent);assertEquals(original.intentSha256,r.intentSha256);assertEquals("uncertain",r.phase);assertEquals(0,saved.get())}
    private fun roundTrip(language: String,dark: Boolean) {
        show(language=language,dark=dark);easy();field("easywork-name","Unsent EasyWork name");method("通过中继连接");field("easywork-relay-url",origin);field("easywork-relay-code","0123-4567-89AB-CDEF");tap("agent-provider-codex");tap("agent-provider-easywork")
        ui.onNodeWithTag("easywork-name").assertTextContains("Unsent EasyWork name");ui.onNodeWithTag("easywork-relay-code").assertTextContains("0123-4567-89AB-CDEF");ui.onNodeWithTag("easywork-relay-url").assertTextContains(origin)
        method("扫码连接");field("easywork-qr-description","unsubmitted QR draft");tap("agent-provider-codex");easy();ui.onNodeWithTag("easywork-qr-description").assertTextContains("unsubmitted QR draft");preserved();assertEquals(0,stats().getJSONArray("requests").length());owned.nodeShot(ui.onRoot(),"provider-$language-${if(dark)"dark" else "light"}")
    }
    @Test(timeout=45_000) fun providerRoundTripPreservesOriginalDraftAndUnknownReceiptChineseLight()=roundTrip("zh-Hans",false)
    @Test(timeout=45_000) fun providerRoundTripPreservesOriginalDraftAndUnknownReceiptEnglishDark()=roundTrip("en",true)
    private fun completeTarget(language: String,dark: Boolean) {show(language=language,dark=dark,editing=true);ui.onNodeWithTag("agent-provider").assertDoesNotExist();val t=client.scope!!;for((key,value) in listOf("agent" to t.agentId,"authority" to t.accountAuthority!!,"subject" to t.accountSubject!!,"host" to t.hostId,"workspace" to t.workspaceId,"runtime" to t.runtimeId))ui.onNodeWithTag("easywork-target-$key").performScrollTo().assertTextEquals(value);preserved();owned.nodeShot(ui.onRoot(),"target-$language-${if(dark)"dark" else "light"}")}
    @Test(timeout=45_000) fun completeTargetIsReachableInChineseDark()=completeTarget("zh-Hans",true)
    @Test(timeout=45_000) fun completeTargetIsReachableInEnglishLight()=completeTarget("en",false)
    @Test(timeout=45_000) fun pendingConfirmationDisablesProviderAndDuplicateSubmissionThenShowsRejection() {show("reject");code();pending();assertEquals(1,stats().getJSONArray("requests").objects().count {it.getString("method")=="POST"});resolve("rejected");error(EasyWorkConnectionMessage.REJECTED);preserved()}
    @Test(timeout=45_000) fun expiredConfirmationIsAnExplicitEnglishFailure() {show("expire","en");code();pending();resolve("expired");error(EasyWorkConnectionMessage.EXPIRED,"en");preserved()}
    @Test(timeout=45_000) fun invalidCodeAndInvalidQrNeverReachTheIssuer() {show();easy();method("通过中继连接");field("easywork-relay-code","bad");tap("easywork-pair-relay");error(EasyWorkConnectionMessage.CODE);method("扫码连接");field("easywork-qr-description","invalid");tap("easywork-pair-lan");error(EasyWorkConnectionMessage.QR);assertEquals(0,stats().getJSONArray("requests").length());preserved()}
    private fun lanFailure(mode: String,message: EasyWorkConnectionMessage,hostReads: Int,claims: Int) {show(mode);qr(api("/offer").getString("text"));error(message);assertEquals(hostReads,stats().getInt("hostReads"));assertEquals(claims,stats().getInt("lanClaims"));preserved()}
    @Test(timeout=45_000) fun expiredLanQrIsRejectedBeforeAnyNetwork()=lanFailure("expired-local",EasyWorkConnectionMessage.EXPIRED,0,0)
    @Test(timeout=45_000) fun wrongCertificatePinIsVisibleAndSendsNoClaim()=lanFailure("wrong-pin",EasyWorkConnectionMessage.CERTIFICATE,0,0)
    @Test(timeout=45_000) fun changedDescriptorTargetIsVisibleAndSendsNoClaim()=lanFailure("wrong-host-target",EasyWorkConnectionMessage.TARGET,1,0)
    @Test(timeout=45_000) fun mismatchedBindingCannotQuietlyCompleteTheLanForm()=lanFailure("wrong-binding",EasyWorkConnectionMessage.TARGET,1,1)
    @Test(timeout=45_000) fun unknownClaimDoesNotReplayWhenProviderChanges() {show("drop");code();error(EasyWorkConnectionMessage.FAILED);val before=stats().getJSONArray("requests").toString();tap("agent-provider-codex");easy();ui.waitForIdle();assertEquals(before,stats().getJSONArray("requests").toString());assertEquals(1,stats().getJSONArray("requests").length());preserved()}
    @Test(timeout=45_000) fun approvedGrantWithUnavailableRuntimeDoesNotQuietlyReportSuccess() {show("approve-block");code();pending();resolve("approved");error(EasyWorkConnectionMessage.FAILED);assertTrue(stats().getInt("blockedRelay")>0);preserved()}
    @Test(timeout=45_000) fun newerIncomingQrSurvivesOldApprovalWithoutDispatchingRuntime() {show("approve-block");code();pending();val newText="easywork://pair?version=1&relay=http%3A%2F%2F127.0.0.1%3A1&code=FEDCBA9876543210";main {session.incomingEasyWorkLink=PendingAgentLink(payload=newText)};resolve("approved");ui.waitUntil(8_000){!main {session.connectionBusy}};ui.onNodeWithTag("easywork-qr-description").performScrollTo().assertTextContains(newText);ui.onNodeWithTag("easywork-pair-error").assertDoesNotExist();assertEquals(0,stats().getInt("blockedRelay"));assertEquals(newText,main {session.incomingEasyWorkLink!!.payload});preserved()}
}
