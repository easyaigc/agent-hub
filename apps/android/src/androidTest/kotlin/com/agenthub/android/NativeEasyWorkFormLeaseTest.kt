package com.agenthub.android

import com.agenthub.core.TargetScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/** Session boundary probes use the actual mounted form owner and actual native issuer traffic.
 * A bounded test-owned NonCancellable wait makes late delivery falsifiable independently of UI cancellation.
 * They are not button-click/real Desktop/camera/model evidence. Existing 14 methods cover the button lane.
 */
class NativeEasyWorkFormLeaseTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {override fun before() {
        val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        if(InstrumentationRegistry.getArguments().getString("p8UiForeground")!="1")return
        val fd=i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity")
        assertTrue(android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use {it.readText()}.contains("Status: ok"))
    }}
    @get:Rule val methodName=org.junit.rules.TestName()
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val config get()=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PairingUiConfig")))
    private val caseId=UUID.randomUUID().toString()
    private lateinit var store: NativeSessionStorage
    private lateinit var session: MobileSession
    private lateinit var client: MobileClient
    private lateinit var folder: File
    private lateinit var original: SavedOperation
    private lateinit var journalBytes: String
    private lateinit var identity: EasyWorkIdentity
    private val draft=EasyWorkConnectionDraft("Lease probe","https://51easyai.com")
    private val visible=mutableStateOf(true)
    private val originalText="P8 synthetic original draft "+UUID.randomUUID()
    private var started=false
    private var job: Deferred<Result<MobileClient>>?=null
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val defaultRoot get()=File(i.targetContext.noBackupFilesDir,"mobile-v1")
    private var defaultFiles=emptyMap<String,String>()
    private fun sha(file: File)=file.inputStream().use {input->val d=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString("") {"%02x".format(it)}}
    private fun defaults()=if(!defaultRoot.exists())emptyMap()else defaultRoot.walkTopDown().filter {it.isFile}.associate {it.relativeTo(defaultRoot).path to sha(it)}
    private fun <T> main(block: suspend ()->T): T=runBlocking {withContext(Dispatchers.Main) {block()}}
    private fun api(path: String,body: JSONObject?=null): JSONObject {
        require(path in setOf("/health","/begin-case","/stats","/resolve","/end-case"));val c=URL(config.getString("controlOrigin")+path).openConnection() as HttpURLConnection
        try {c.connectTimeout=1500;c.readTimeout=1500;c.instanceFollowRedirects=false;c.setRequestProperty("X-P8-Fixture",config.getString("instanceId"));if(body!=null){val b=body.toString().toByteArray();c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(b.size);c.outputStream.use {it.write(b)}};check(c.responseCode==200);return JSONObject(c.inputStream.bufferedReader().use {it.readText()})}finally {c.disconnect()}
    }
    private fun stats()=api("/stats").also {assertEquals(caseId,it.getString("caseId"));assertEquals(0,it.getJSONArray("errors").length());assertEquals(0,it.getInt("realDesktopRequests"));assertEquals(0,it.getInt("realModelInputs"))}
    @Before fun setup() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        require(config.getString("fixture")=="p8-synthetic-pairing-ui-v1")
        for((key,scheme) in listOf("authOrigin" to "http","tlsOrigin" to "https","controlOrigin" to "http")){val u=URI(config.getString(key));require(u.scheme==scheme&&u.host=="127.0.0.1"&&u.port in 1..65535&&u.rawPath.isNullOrEmpty()&&u.rawQuery==null&&u.rawFragment==null&&u.rawUserInfo==null)}
        val build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PairingUiBuild")));for(k in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(k).matches(Regex("[a-f0-9]{64}")));assertEquals(build.getString("appSha256"),sha(File(i.targetContext.applicationInfo.sourceDir)));assertEquals(build.getString("runnerSha256"),sha(File(i.context.applicationInfo.sourceDir)))
        val h=api("/health");for(k in listOf("instanceId","sourceSha256","certificateSha256","authOrigin","tlsOrigin","controlOrigin"))require(h.getString(k)==config.getString(k));assertTrue(h.getBoolean("onlyLoopback"));assertFalse(h.getBoolean("realDesktop"));assertFalse(h.getBoolean("realModel"))
        defaultFiles=defaults();store=NativeSessionStorage(i.targetContext,"p8-form-lease-$caseId","p8-form-lease-$caseId");folder=File(i.targetContext.filesDir,"acceptance/p8-form-lease/$caseId").apply {check(!exists());mkdirs()}
        val v=JSONObject(i.context.assets.open("easywork-crypto-vector.json").bufferedReader().use {it.readText()});val credential=EasyWorkCredential.decode(v.getJSONObject("credential"));identity=EasyWorkIdentity("p8-fence-$caseId-a",credential.privateKey,credential.binding.mobilePublicKey);store.write("easywork-device-identity",identity.json().toString())
        val target=credential.binding.target.scope();client=MobileClient("original-synthetic", "Original",target,config.getString("authOrigin"),false,"easywork-runtime",false,agentConnection=AgentConnection(target,listOf(AgentCandidate("synthetic","relay-http",config.getString("authOrigin"))),"missing-owned-ref"))
        main {session=MobileSession(store);session.resetAcceptance();session.select(client);session.text=originalText}
        val journal=NativeOperationJournal(store);original=runBlocking {val r=journal.prepare(client,"original-thread","reply",ReplyIntent(client.id,"original-thread",UUID.randomUUID().toString(),"synthetic immutable original input",emptyList(),"next-turn").body());journal.markSubmitting(r.target,r.id);journal.uncertain(r.target,r.id,"synthetic unknown original acknowledgement")};journalBytes=store.read("operations:${original.target}")!!
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {ProductTextProvider("zh-Hans") {if(visible.value)ConnectionPage(session,null,draft) {error("Boundary probe must not complete a UI button action")}}}}}}
    }
    @After fun finish() {
        if(::session.isInitialized){ui.runOnIdle {visible.value=false;ui.activity.setContent {};session.leaveForeground()};ui.waitForIdle()}
        runBlocking {withTimeout(15_000) {job?.join()}};tasks.cancel()
        if(started){val s=stats();File(folder,"native-proof.json").writeText(JSONObject().put("caseId",caseId).put("stats",s).put("defaultFilesUnchanged",defaultFiles==defaults()).put("probe","actual form owner plus test-owned bounded noncancellable Session call; not a UI button/real Desktop result").toString());api("/end-case",JSONObject().put("caseId",caseId))}
        if(::folder.isInitialized)File(folder,"local-observations.json").writeText(JSONObject().put("method",methodName.methodName).put("caseId",caseId).put("defaultEncryptedFilesUnchanged",defaultFiles==defaults()).put("originalJournalUnchanged",if(::original.isInitialized)journalBytes==store.read("operations:${original.target}")else false).put("observationsOnly",true).toString())
        assertEquals(defaultFiles,defaults())
    }
    private fun begin(mode: String="approve-block") {
        api("/begin-case",JSONObject().put("caseId",caseId).put("mode",mode));started=true
        ui.onNodeWithTag("agent-provider-easywork").performScrollTo().performClick();ui.onNodeWithTag("easywork-connection-method").performScrollTo().performClick();ui.onNodeWithTag("easywork-method-通过中继连接").performClick();ui.onNodeWithTag("easywork-relay-url").performScrollTo().performTextReplacement(config.getString("authOrigin"));ui.onNodeWithTag("easywork-relay-code").performScrollTo().performTextReplacement("0123456789ABCDEF")
        val input=EasyWorkConnectionInput("code",draft.name,draft.relay,draft.code);val lease=ui.runOnIdle {draft.formOwner!!.begin(input) {EasyWorkConnectionInput("code",draft.name,draft.relay,draft.code)}}
        job=tasks.async {withContext(NonCancellable+lease) {withTimeout(12_000) {runCatching {session.connectEasyWorkCode(input.name,input.endpoint,input.value)}}}}
        ui.waitUntil(8000) {stats().getJSONArray("requests").length()>=2}
    }
    private fun resolve(status: String="approved")=api("/resolve",JSONObject().put("caseId",caseId).put("status",status))
    private fun refused(approved: Boolean=true) {
        val result=runBlocking {withTimeout(15_000) {job!!.await()}};assertTrue(result.exceptionOrNull() is EasyWorkConnectionAbandoned);assertFalse(result.exceptionOrNull() is CancellationException)
        val s=stats();assertEquals(0,s.getInt("blockedRelay"));assertEquals(1,s.getJSONArray("requests").objects().count {it.getString("method")=="POST"});assertTrue(main {session.clients.isEmpty()});assertEquals(client.scope,main {session.selectedClient.scope});assertEquals(originalText,main {session.text});assertEquals(journalBytes,store.read("operations:${original.target}"))
        val signer=EasyWorkDPoP.load(identity);val key="easywork-device-claim:"+easyHash((config.getString("authOrigin")+"\n"+signer.thumbprint+"\n0123456789ABCDEF").toByteArray())
        if(approved){val claim=JSONObject(store.read(key)!!);val grant=JSONObject(store.read(claim.getString("grantRef"))!!).getJSONObject("grant");val target=EasyWorkBinding(grant.getJSONObject("binding").toString()).target.scope();val id="easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(target)).toByteArray());assertNull(store.read("easywork-credential:agent-$id"));File(folder,"retained-authorization.json").writeText(JSONObject().put("claimOperationId",JSONObject(claim.getString("body")).getString("operationId")).put("bodySha256",easyHash(claim.getString("body").toByteArray())).put("grantRetained",true).put("connectionCredentialAbsent",true).toString())}
        else assertTrue(generateSequence(result.exceptionOrNull()) {it.cause}.take(8).any {it.message=="电脑拒绝了此设备，请重新配对"})
    }
    @Test(timeout=40_000) fun closedManualCodeFormWithNullLinkCannotInstallLateApproval() {begin();ui.runOnIdle {visible.value=false};ui.waitForIdle();resolve();refused()}
    @Test(timeout=40_000) fun actualActivityPauseResumeCannotReviveTheOldFormAction() {begin();ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED);ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);resolve();refused()}
    @Test(timeout=40_000) fun sessionBackgroundForegroundWithSameNullLinkCannotReviveAction() {begin();main {session.leaveForeground();session.enterForeground()};resolve();refused()}
    @Test(timeout=40_000) fun newIncomingLinkDismissedBackToNullCannotReviveAction() {begin();main {val link=PendingAgentLink(payload="easywork://pair?version=1&relay=http%3A%2F%2F127.0.0.1%3A1&code=0123456789ABCDEF");session.incomingEasyWorkLink=link;session.dismissIncomingLink(link.presentation)};resolve();refused()}
    @Test(timeout=40_000) fun targetSwitchAndReturnKeepsBothDraftsAndRefusesOldApproval() {begin();val t=client.scope!!;val scope=TargetScope(t.agentId,t.accountAuthority,t.accountSubject,t.hostId,t.workspaceId,"other-runtime");val b=client.copy(id="other-synthetic",scope=scope,agentConnection=client.agentConnection!!.copy(target=scope));main {session.select(b);session.text="other unsent draft";session.select(client)};resolve();refused();main {session.select(b)};assertEquals("other unsent draft",main {session.text})}
    @Test(timeout=40_000) fun originalConversationDraftChangeAndRestoreCannotReviveAction() {begin();main {session.text="changed draft";session.text=originalText};resolve();refused()}
    @Test(timeout=40_000) fun connectionInputChangeAndRestoreCannotReviveAction() {begin();ui.runOnIdle {draft.code="FEDCBA9876543210";draft.code="0123456789ABCDEF"};resolve();refused()}
    @Test(timeout=40_000) fun closedFormLateFailureKeepsOriginalDraftAndUnknownBusinessReceipt() {begin("reject");ui.runOnIdle {visible.value=false};ui.waitForIdle();resolve("rejected");refused(false)}
    private fun lease()=easyWorkConnectionForm(session).begin(EasyWorkConnectionInput("save","native disk probe","","public"))
    @Test(timeout=40_000) fun revokedLeaseCannotWriteTheActualEncryptedCredentialMapping() {val a=lease();a.form.close();assertThrows(EasyWorkConnectionAbandoned::class.java) {EasyWorkConnectionCredentialWrites.write(store,"easywork-credential:disk-probe","new",a)};assertNull(store.read("easywork-credential:disk-probe"));assertEquals(journalBytes,store.read("operations:${original.target}"))}
    @Test(timeout=40_000) fun revokedAuthorizedWriteRollsBackOnlyItsMappingAndKeepsGrantAndUnknownReceipt() {val key="easywork-credential:disk-probe";store.write(key,"previous");store.write("easywork-device-grant:disk-probe","original grant receipt");val a=lease();EasyWorkConnectionCredentialWrites.write(store,key,"candidate",a);a.form.close();EasyWorkConnectionCredentialWrites.rollback(store,key,"candidate","previous",a);assertEquals("previous",store.read(key));assertEquals("original grant receipt",store.read("easywork-device-grant:disk-probe"));assertEquals(journalBytes,store.read("operations:${original.target}"))}
    @Test(timeout=40_000) fun staleRollbackCannotOverwriteANewerIdenticalCredentialWrite() {val key="easywork-credential:disk-probe";val a=lease();EasyWorkConnectionCredentialWrites.write(store,key,"same",a);a.form.close();val b=lease();EasyWorkConnectionCredentialWrites.write(store,key,"same",b);EasyWorkConnectionCredentialWrites.rollback(store,key,"same","old",a);assertEquals("same",store.read(key));EasyWorkConnectionCredentialWrites.committed(store,key,b);a.releaseBusy();assertTrue(b.releaseBusy())}
}
