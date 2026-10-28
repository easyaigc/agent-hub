package com.agenthub.android

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agenthub.core.TargetScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Actual warm MainActivity dispatch. Explicit parent opt-in may prepare the actual
 * idle acceptance Main inside this instrumentation process, preserving default files.
 * Existing form-lease scenarios own late network delivery. No issuer/Host/model here.
 */
class NativeEasyWorkMainIntentTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(60)
    @get:Rule val method=org.junit.rules.TestName()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val id=UUID.randomUUID().toString()
    private lateinit var activity: MainActivity
    private lateinit var storage: NativeSessionStorage
    private lateinit var session: MobileSession
    private lateinit var folder: File
    private var previous: MainActivity.AcceptanceSessionState?=null
    private var defaults=emptyMap<String,String>()
    private val draft="P8 owned original draft 中文 😀 100% %@"
    private val grant="original synthetic grant metadata; never used for authentication"
    private val claim="original synthetic unknown claim; never submitted"
    private lateinit var journal: String
    private lateinit var journalKey: String
    private fun hash(file: File)=file.inputStream().use {input->val d=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString("") {"%02x".format(it)}}
    private fun defaultFiles(): Map<String,String> = i.targetContext.noBackupFilesDir.listFiles().orEmpty()
        .filter {it.name=="mobile-v1"||it.name.startsWith("mobile-v1-")}.flatMap {root->root.walkTopDown().filter {it.isFile}.toList()}
        .associate {it.relativeTo(i.targetContext.noBackupFilesDir).path to hash(it)}
    private fun <T> main(block: ()->T): T {var result: Result<T>?=null;i.runOnMainSync {result=runCatching(block)};return result!!.getOrThrow()}
    private fun resumed(): MainActivity?=main {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull {it.packageName==i.targetContext.packageName}}
    private fun await(predicate: ()->Boolean) {val until=SystemClock.elapsedRealtime()+10_000;while(!predicate()){check(SystemClock.elapsedRealtime()<until) {"Bounded actual MainActivity dispatch did not finish"};SystemClock.sleep(25)}}
    private fun prepareActualMain() {
        defaults=defaultFiles()
        folder=File(i.targetContext.filesDir,"acceptance/p8-main-intent/$id").apply {check(!exists());check(mkdirs())}
        val start=InstrumentationRegistry.getArguments().getString("p8ActualMainStart")=="1"
        val proof=JSONObject().put("processPid",android.os.Process.myPid()).put("instrumentationProcessStartOptIn",start).put("defaultFilesBefore",JSONObject(defaults)).put("defaultDataReset",false)
        try {
            if(start) {
                // Refuse a normal restore that could activate a saved real target/callback.
                val roots=i.targetContext.noBackupFilesDir.listFiles().orEmpty().filter {it.name=="mobile-v1"||it.name.startsWith("mobile-v1-")}
                require(roots.none {root->root.walkTopDown().any {it.isFile&&(it.name.endsWith(".bak")||it.name.endsWith(".new"))}}) {"Pending default AtomicFile recovery requires its original owner"}
                require(roots.filter {root->listOf("-document-results","-photo-results","-camera-results").any {suffix->root.name.endsWith(suffix)}}.none {root->root.walkTopDown().any {it.isFile}}) {"Original default result records must remain with their owner"}
                val store=NativeSessionStorage(i.targetContext)
                val metadata=store.metadata();require(metadata==null||metadata.getInt("version")==1&&metadata.getJSONArray("clients").length()==0) {"Actual Main preparation requires no saved real default connection"}
                val vault=store.read("codex-private-connections-v1")?.let(::JSONObject);require(vault==null||vault.getInt("version")==1&&vault.getJSONArray("connections").length()==0)
                assertEquals("Read-only preflight preserves every default encrypted file",defaults,defaultFiles())
                val fd=i.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.easyai.agenthub.android.acceptance/com.agenthub.android.MainActivity")
                val output=android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use {it.readText()}
                proof.put("startResultSha256",java.security.MessageDigest.getInstance("SHA-256").digest(output.toByteArray()).joinToString("") {"%02x".format(it)})
                check(output.contains("Status: ok")) {"Explicit in-process actual Main activation failed"}
            }
            await {resumed()!=null};activity=checkNotNull(resumed())
            await {main {!activity.session.restoring}}
            main {check(!activity.session.localRecoveryRequired&&activity.session.clients.isEmpty()&&activity.session.operationRecords.isEmpty()&&activity.session.voice?.active!=true) {"Actual Main must finish restore idle without real connections or running input"};check(android.os.Process.myPid()==proof.getInt("processPid"))}
            assertEquals("Actual Main startup/restore must preserve default encrypted files",defaults,defaultFiles())
            proof.put("actualMainResumedAndRestoreReady",true).put("actualMainClass",activity.javaClass.name)
        } finally {proof.put("defaultFilesAfter",JSONObject(defaultFiles())).put("defaultFilesUnchanged",defaults==defaultFiles());File(folder,"actual-main-preparation.json").writeText(proof.toString())}
    }
    @Before fun setup() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        require(i.targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        require(InstrumentationRegistry.getArguments().getString("p8ActualMainReady")=="1") {"Parent must lease the exact actual Main acceptance lane"}
        val build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PairingUiBuild")))
        for(k in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(k).matches(Regex("[a-f0-9]{64}")))
        assertEquals(build.getString("appSha256"),hash(File(i.targetContext.applicationInfo.sourceDir)));assertEquals(build.getString("runnerSha256"),hash(File(i.context.applicationInfo.sourceDir)))
        prepareActualMain()
        storage=NativeSessionStorage(i.targetContext,"p8-main-intent-$id","p8-main-intent-$id")
        main {session=MobileSession(storage);session.resetAcceptance();session.text=draft;previous=activity.adoptSessionForAcceptance(session)}
        storage.write("easywork-device-grant:original",grant);storage.write("easywork-device-claim:original",claim)
        val target=TargetScope("easywork","https://synthetic.invalid","synthetic-account","synthetic-host","synthetic-workspace","synthetic-runtime")
        val client=MobileClient("synthetic-no-network","Synthetic",target,"https://not-used.invalid",false,"easywork-runtime",false,agentConnection=AgentConnection(target,listOf(AgentCandidate("unused","relay-http","https://not-used.invalid")),"unused"))
        val row=runBlocking {val j=NativeOperationJournal(storage);val r=j.prepare(client,"synthetic-thread","reply",ReplyIntent(client.id,"synthetic-thread",UUID.randomUUID().toString(),"synthetic immutable original input",emptyList(),"next-turn").body());j.markSubmitting(r.target,r.id);j.uncertain(r.target,r.id,"synthetic unknown original acknowledgement")}
        journalKey="operations:${row.target}";journal=storage.read(journalKey)!!
        assertEquals(defaults,defaultFiles())
    }
    @After fun finish() {
        if(::session.isInitialized)main {session.leaveForeground();assertEquals(draft,session.text);assertTrue(session.clients.isEmpty());assertTrue(session.operationRecords.isEmpty())}
        previous?.let {old->main {activity.restoreSessionForAcceptance(old)}}
        if(::storage.isInitialized){assertEquals(grant,storage.read("easywork-device-grant:original"));assertEquals(claim,storage.read("easywork-device-claim:original"));if(::journal.isInitialized)assertEquals(journal,storage.read(journalKey))}
        if(::activity.isInitialized)assertEquals(defaults,defaultFiles())
    }
    private fun dispatch(consumedByInbox: Boolean) {
        val input=EasyWorkConnectionInput("code","Synthetic","https://not-used.invalid","synthetic-not-submitted")
        val form=main {easyWorkConnectionForm(session)}
        val action=main {form.begin(input)}
        val target=main {session.targetKey};val navigation=main {session.suggestionNavigation}
        main {assertTrue(action.currentForUi());action.checkpoint();assertNull(session.incomingEasyWorkLink);assertNull(session.error)}
        val nonce=UUID.randomUUID().toString()
        val intent=Intent(activity,MainActivity::class.java).setAction(if(consumedByInbox)Intent.ACTION_SEND else Intent.ACTION_VIEW)
            .putExtra("p8OwnedIntent",nonce).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if(consumedByInbox)intent.setDataAndType(Uri.parse("easywork://pair?synthetic-invalid-never-consumed-as-pairing"),"text/plain").putExtra(Intent.EXTRA_TEXT,"Synthetic text-only unsupported file action")
        val lifecycleEvents=mutableListOf<Lifecycle.Event>();val observer=LifecycleEventObserver {_,event->lifecycleEvents+=event}
        main {activity.lifecycle.addObserver(observer);lifecycleEvents.clear();activity.startActivity(intent)}
        await {main {activity.intent.getStringExtra("p8OwnedIntent")==nonce}}
        assertSame("Warm dispatch must reach this same actual MainActivity",activity,resumed())
        main {
            activity.lifecycle.removeObserver(observer);assertFalse("A stop/start must not substitute for the Main Intent hunk",Lifecycle.Event.ON_STOP in lifecycleEvents||Lifecycle.Event.ON_DESTROY in lifecycleEvents)
            assertSame(session,activity.session);assertEquals(target,session.targetKey);assertEquals(navigation,session.suggestionNavigation)
            assertNull(session.incomingEasyWorkLink);assertNull(session.error);assertEquals(draft,session.text)
            assertFalse("Removing only the MainActivity invalidation hunk must make this assertion fail",action.currentForUi())
            assertThrows(EasyWorkConnectionAbandoned::class.java) {action.write {fail("Stale capability must not enter its write block")}}
            if(consumedByInbox)assertTrue(session.externalInbox!!.available||session.externalInbox!!.presentation!=null)
            assertTrue(action.releaseBusy());form.close()
        }
        assertEquals(journal,storage.read(journalKey));assertEquals(defaults,defaultFiles())
        File(folder,"intent-observations.json").writeText(JSONObject().put("method",method.methodName).put("entry","actual warm MainActivity.startActivity -> onNewIntent -> receive")
            .put("sameActivity",true).put("sameOwnedSession",true).put("inboxConsumed",consumedByInbox).put("incomingLinkRemainedNull",true).put("targetNavigationAndDraftUnchanged",true)
            .put("oldCapabilityRefused",true).put("originalGrantClaimAndUnknownJournalPreserved",true).put("defaultEncryptedFilesUnchanged",true).put("networkOrModelCalls",0).put("observationsOnly",true).toString())
    }
    @Test fun actualWarmMainWithoutLinkChangeRevokesOriginalPairingCapability()=dispatch(false)
    @Test fun actualWarmMainInboxConsumesIntentBeforePairingAndRevokesCapability()=dispatch(true)
}
