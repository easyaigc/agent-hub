package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Product migration page/controller + actual restore + shipping HTTP reader. Document selection
 * is supplied at the original picker boundary using a fresh owned content URI; this is NOT SAF,
 * camera, inherited Main assembly, or real DEV Host acceptance. No real credential is accepted. */
class P3LegacyMigrationProductUiTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var controller: NativeMigrationController
    private lateinit var host: NativeMigrationLifecycle
    private lateinit var server: CommandFixture
    private lateinit var artifacts: File
    private var current by mutableStateOf<MobileSession?>(null)
    private val session get()=checkNotNull(current)
    private val run=UUID.randomUUID().toString()
    private val target=TargetScope("codex",null,null,"p3-legacy-$run","p3-workspace","p3-runtime")
    private val secret="p3-owned-legacy-$run"
    private val password="p3 owned encrypted archive password"
    private val draftKey="owned-main-ui-draft-$run"
    private var failOwnerPreparation=false
    private val denied=object: CodexCommands by NativeCodexCommands({error("No business command allowed")}) {}
    private fun main(action: suspend ()->Unit)=runBlocking {withContext(Dispatchers.Main.immediate){action()}}
    @Before fun prepare() {
        ui.waitUntil(30_000){!ui.activity.session.restoring}
        ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"p3-legacy-product-ui");owned.session.leaveForeground()}
        artifacts=File(ui.activity.cacheDir,"resources/p3-legacy-$run").apply {check(mkdirs())}
        server=CommandFixture {request->
            if(request.method!="GET"||request.headers["authorization"]!="Bearer $secret")WireResponse(403)
            else when(request.path) {
                "/v1/workspace"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("name","P3 owned legacy target")
                    .put("readTarget",CodexCommandCodec.target(target)).put("connectionMode","history").put("capabilities",JSONArray())
                    .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("hostId",target.hostId).put("id","thread").put("title","P3 restored thread").put("state","idle"))).toString())
                "/v1/history"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("threadId","thread").put("readTarget",CodexCommandCodec.target(target))
                    .put("messages",JSONArray().put(JSONObject().put("id","p3-imported-reply").put("role","assistant").put("text","P3 legacy authenticated read").put("status","completed"))).toString())
                else->WireResponse(404)
            }
        }
        owned.storage.write("metadata",NativeMigrationMainSafMetadata.value(run,"original P3 local draft").toString())
        main {
            current=MobileSession(owned.storage,NativeHostReads(owned.storage::token),denied)
            session.restore()
        }
        ui.waitUntil(30_000){session.migrationReady}
        controller=NativeMigrationController(ui.activity)
        host=object: NativeMigrationLifecycle {
            override val ready get()=session.migrationReady
            override val canImport get()=session.migrationCanImport
            override fun currentStorage()=session.migrationStorage
            override suspend fun quiesce(operationId: String): NativeMigrationOwnerLease {
                // Explicit failure BEFORE an owner lease exists; not a simulated successful publish.
                check(!failOwnerPreparation){"P3 controlled owner preparation failure"}
                return session.quiesceForMigration(operationId)
            }
            override suspend fun restore(lease: NativeMigrationOwnerLease,requiresExplicitEnrollment: Boolean) {
                current=restoreNativeMigrationSession(ui.activity,session,lease,create={storage->
                    MobileSession(storage,NativeHostReads(storage::token),denied)
                })
            }
        }
        main {
            controller.attach(host)
            ui.activity.setContent {ProductTextProvider("en"){MaterialTheme{
                if(controller.presented)NativeMigrationPage(controller) else AgentHubApp(session)
            }}}
            controller.show()
        }
        ui.waitUntil(15_000){controller.ready&&!controller.busy}
        runBlocking {owned.storage.awaitQueuedWrites()}
    }
    private fun archive(wrongAgent: Boolean=false): JSONObject {
        val client=MobileClient(target.hostId,"P3 old-format source",target,server.endpoint,false,"history",false,"p3-reader-$run",true)
        val metadata=NativeMigrationMainSafMetadata.value(run,"imported P3 draft").put("demo",false).put("selected",client.id)
            .put("clients",JSONArray().put(encodeClient(client))).put("selectedThreads",JSONObject().put(client.cacheKey(null),"thread"))
        if(wrongAgent)metadata.getJSONArray("clients").getJSONObject(0).getJSONObject("scope").put("agentId","easywork")
        return JSONObject().put("format","agent-hub/android-migration").put("version",1).put("applicationId",ui.activity.packageName)
            .put("records",JSONObject().put("metadata",metadata.toString()).put("token:${client.id}",secret))
    }
    private var diagnosticSequence=0
    /** Scalars/digests only, bounded to this fixture; never serialize a client, archive or credential. */
    private fun migrationState(label: String): JSONObject {
        require(label.matches(Regex("[a-z0-9-]{1,64}")))
        val state=runBlocking {withTimeout(5_000){withContext(Dispatchers.Main.immediate){
            val candidate=runCatching {NativeMigrationController::class.java.getDeclaredField("candidate").apply {isAccessible=true}.get(controller)!=null}
            val selected=session.selectedClient
            JSONObject().put("stage",label).put("phase",controller.phase.name)
                .put("result",controller.result?.name?:JSONObject.NULL).put("ready",controller.ready).put("busy",controller.busy)
                .put("canImport",controller.canImport).put("pickerPresent",controller.picker!=null)
                .put("candidatePresent",candidate.getOrNull()?:JSONObject.NULL)
                .put("candidateObservation",if(candidate.isSuccess)"controller-memory"else"unavailable")
                .put("previewPresent",controller.preview!=null).put("sessionReady",session.migrationReady)
                .put("selectedClientIdSha256",MigrationActivationFixtures.sha(selected.id.toByteArray()))
                .put("selectedScopeSha256",selected.scope?.let {MigrationActivationFixtures.sha(CodexCommandCodec.target(it).toString().toByteArray())}?:JSONObject.NULL)
                .put("selectedThreadIdSha256",MigrationActivationFixtures.sha(session.selectedThread.toByteArray()))
                .put("selectedExpectedFixture",selected.id==target.hostId&&sameScope(selected.scope,target))
        }}}
        val bytes=state.toString().toByteArray();check(bytes.size<=4096&&diagnosticSequence<16)
        val file=File(owned.folder,"migration-state-${++diagnosticSequence}-$label.json")
        val fd=android.system.Os.open(file.path,android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW,0x180)
        java.io.FileOutputStream(fd).use {it.write(bytes)}
        return state
    }
    private fun awaitMigrationState(label: String,timeoutMillis: Long,settled: ()->Boolean): JSONObject {
        try {ui.waitUntil(timeoutMillis){settled()}}
        catch(failure: Throwable) {
            try {failure.addSuppressed(AssertionError("Migration state: "+migrationState(label+"-wait-failed")))}
            catch(diagnostic: Throwable) {failure.addSuppressed(AssertionError("Bounded migration diagnostic failed: "+diagnostic.javaClass.simpleName))}
            throw failure
        }
        return migrationState(label)
    }
    /** Fail at the fixture boundary before UI delivery if a future schema change rejects this input. */
    private fun sealedValidArchive(): ByteArray {
        val clear=archive().toString().toByteArray()
        try {
            val validated=try {NativeMigrationArchiveValidation.archive(clear,ui.activity.packageName)}
                catch(failure: Exception) {throw AssertionError("Synthetic legacy fixture contract rejected ("+failure.javaClass.simpleName+"): "+migrationState("fixture-contract-rejected"))}
            assertTrue("Validated synthetic raw token must remain verbatim",validated.text["token:${target.hostId}"]==secret)
            return NativeMigrationCrypto.seal(clear,password)
        }finally {clear.fill(0)}
    }
    private fun deliverEnvelope(label: String,bytes: ByteArray,expectPreview: Boolean=true) {
        val file=File(artifacts,"$label.codexmigration").apply {writeBytes(bytes)}
        val uri=FileProvider.getUriForFile(ui.activity,ui.activity.packageName+".resources",file)
        // Record only source schema/provenance and ciphertext fingerprint, never token or archive text.
        File(owned.folder,"$label-origin.json").writeText(JSONObject().put("source","task-owned schema-v1 raw token record; no private claim")
            .put("synthetic",true).put("fileBytes",bytes.size).put("ciphertextSha256",MigrationActivationFixtures.sha(bytes)).toString())
        ui.onNodeWithTag("migration-password").performTextReplacement(password)
        ui.onNodeWithTag("migration-import").performScrollTo().assertIsEnabled().performClick()
        // No NativeMigrationSystemHost is mounted in this boundary test: no system picker is launched.
        main {
            val request=checkNotNull(controller.claimPicker());assertEquals(NativeMigrationPickerKind.IMPORT,request.kind)
            controller.completePicker(request.kind,uri)
        }
        val state=awaitMigrationState(label+"-envelope-terminal",30_000){
            !controller.busy&&controller.picker==null&&(controller.canDismiss||controller.phase==NativeMigrationPhase.UNKNOWN)
        }
        if(expectPreview)assertTrue("Valid legacy envelope must reach READY preview before any UI lookup: $state",
            state.getString("phase")=="PREVIEW"&&state.optString("result")=="READY"&&state.getBoolean("ready")&&
                state.optBoolean("candidatePresent")&&state.getBoolean("previewPresent"))
        else assertTrue("Invalid envelope must reach explicit INVALID failure: $state",
            state.getString("phase")=="FAILED"&&state.optString("result")=="INVALID"&&!state.getBoolean("previewPresent"))
    }
    @Test fun legacyArchiveConfirmRestoresRawCredentialAndProductReadsSameTarget() {
        deliverEnvelope("legacy-v1",sealedValidArchive())
        ui.onNodeWithTag("migration-preview").performScrollTo().assertExists()
        ui.onNodeWithTag("migration-confirm").performScrollTo().assertIsEnabled().performClick()
        awaitMigrationState("import-complete",30_000){controller.result==NativeMigrationResult.IMPORTED&&!controller.busy&&session.migrationReady}
        assertTrue("Restored legacy credential must match exact original",secret==session.migrationStorage.token(target.hostId))
        assertEquals("imported P3 draft",session.migrationStorage.metadata()!!.getJSONObject("drafts").getString(draftKey))
        ui.onNodeWithTag("migration-cancel").performScrollTo().performClick()
        ui.onNodeWithTag("product-surface").assertExists()
        // Exercise product refresh/navigation, not a direct HostReads call.
        ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("sidebar-refresh").performTouchInput {swipeDown()}
        ui.waitUntil(15_000){!session.loading&&session.threads.any {it.id=="thread"}}
        val before=server.requests.count {it.path=="/v1/history"}
        ui.onNodeWithTag("recent-thread-thread").performScrollTo().performClick()
        ui.waitUntil(15_000){server.requests.count {it.path=="/v1/history"}>before&&!session.historyLoading&&session.messages.any {it.text=="P3 legacy authenticated read"}}
        ui.onNodeWithText("P3 legacy authenticated read").assertExists()
        assertEquals(target.hostId,session.selectedClient.id);assertTrue(sameScope(target,session.selectedClient.scope))
        assertTrue(server.requests.isNotEmpty());assertTrue(server.requests.all {it.method=="GET"&&it.headers["authorization"]=="Bearer $secret"})
    }
    @Test fun malformedAndWrongClientArchiveErrorsPreserveOriginalDraftSettings() {
        val before=checkNotNull(owned.storage.read("metadata"))
        for((label,bytes)in listOf("malformed" to byteArrayOf(1,2,3),"wrong-agent" to NativeMigrationCrypto.seal(archive(true).toString().toByteArray(),password))) {
            deliverEnvelope(label,bytes,expectPreview=false)
            assertEquals(NativeMigrationResult.INVALID,controller.result)
            ui.onNodeWithTag("migration-result").performScrollTo().assertTextContains(NativeL10n(ui.activity,"en").tr("密码错误、文件损坏或资料暂不支持；原数据未更改。"))
            assertEquals(before,owned.storage.read("metadata"));assertNull(owned.storage.migrationPublicationReceipt())
            assertTrue(server.requests.isEmpty())
        }
    }
    @Test fun failedOwnerPreparationShowsFailureWithoutChangingDraftSettingsOrCandidate() {
        deliverEnvelope("preparation-failure",sealedValidArchive())
        val before=checkNotNull(owned.storage.read("metadata"));val candidate=checkNotNull(NativeMigrationCandidateStore(ui.activity,owned.storage).currentReceipt())
        val original=NativeMigrationCandidateStore(ui.activity,owned.storage).read(candidate)
        failOwnerPreparation=true
        ui.onNodeWithTag("migration-confirm").performScrollTo().performClick()
        awaitMigrationState("owner-preparation-failed",15_000){!controller.busy&&controller.phase==NativeMigrationPhase.FAILED}
        ui.onNodeWithTag("migration-result").performScrollTo().assertTextContains(NativeL10n(ui.activity,"en").tr("迁移未完成，原本机资料已保留。"))
        assertEquals(NativeMigrationResult.SAVE_FAILED,controller.result)
        assertEquals(before,owned.storage.read("metadata"));assertNull(owned.storage.migrationPublicationReceipt())
        assertEquals(original,NativeMigrationCandidateStore(ui.activity,owned.storage).read(candidate));assertTrue(server.requests.isEmpty())
    }
    @After fun finish() {
        // Unknown/in-flight controller outcome retains only this task's fixture for Root reconciliation.
        if(::controller.isInitialized)check(!controller.busy&&!controller.needsOwnerRestore){"Retain P3 private fixture: controller outcome not settled"}
        if(current!=null)main {
            session.leaveForeground()
            if(::controller.isInitialized)controller.close()
            if(::host.isInitialized)controller.detach(host)
            withTimeout(30_000){while(!session.migrationReady)delay(20)}
            val operation=UUID.randomUUID().toString();val lease=session.quiesceForMigration(operation)
            withContext(NonCancellable+Dispatchers.IO){lease.releaseRawWriters()}
            session.retireForMigration(operation);session.voice?.retireForMigration();session.questionVoice?.retireForMigration()
        }
        if(::server.isInitialized) {
            server.close()
            val executor=CommandFixture::class.java.getDeclaredField("workers").apply {isAccessible=true}.get(server) as java.util.concurrent.ExecutorService
            check(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)){"Retain P3 HTTP fixture: worker exit unknown"}
        }
        if(::owned.isInitialized)ui.runOnIdle {owned.verifyDefault()}
        if(::artifacts.isInitialized) {artifacts.listFiles().orEmpty().forEach {check(it.isFile&&!java.nio.file.Files.isSymbolicLink(it.toPath()));check(it.delete())};check(artifacts.delete())}
    }
}
