package com.agenthub.android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.security.MessageDigest

/** SOURCE ONLY: no build/run claim; requires the two reviewed seams and debug manifest entry.
 * Actual production Main lifecycle SUBCLASS, real AgentHubApp and real session save/restore.
 * Native observers/notification/media/voice assembly are intentionally excluded. */
class NativeP8MainPreferenceTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val actual get()=instrumentation.targetContext
    private lateinit var admission: NativeP8MainAdmission.Accepted
    private enum class Outcome { NOT_STARTED,UNKNOWN,CONFIRMED }
    private class Failures {
        var first: Throwable?=null;private set
        fun add(error: Throwable) {val original=first;if(original==null)first=error else if(original!==error&&original.suppressed.none {it===error})original.addSuppressed(error)}
        fun throwIfAny(){first?.let {throw it}}
        fun capture(action: ()->Unit){try{action()}catch(error: Throwable){add(error)}}
    }
    /** One record per exact operation: UNKNOWN never re-enters its action. */
    private class Ledger {
        private val outcomes=linkedMapOf<String,Outcome>()
        fun confirmed(vararg names: String)=names.all {outcomes[it]==Outcome.CONFIRMED}
        fun step(name: String,errors: Failures,ready: Boolean=true,action: ()->Unit): Boolean {
            val current=outcomes.getOrPut(name){Outcome.NOT_STARTED}
            if(current!=Outcome.NOT_STARTED)return current==Outcome.CONFIRMED
            if(!ready)return false
            outcomes[name]=Outcome.UNKNOWN
            try {action();outcomes[name]=Outcome.CONFIRMED;return true}catch(error: Throwable){errors.add(error);return false}
        }
        fun summary()=outcomes.mapValues {it.value.name}
    }
    private class CaseOwners(val run: String) {
        val pid=android.os.Process.myPid();val nonce=NativeChatNotificationColdStart.processNonce
        val ledger=Ledger();val entries=mutableListOf<Entry>();val activities=mutableListOf<ActivityOwner>()
        var context: P8MainFilesContext?=null;var keys: KeyStore?=null;var storage: NativeSessionStorage?=null
        var fence: P8MainScopeBoundary?=null
        var expectedClient: MobileClient?=null
    }
    private class ActivityOwner(val owner: CaseOwners) {
        val ledger=Ledger();var scenario: ActivityScenario<P8MainPreferenceActivity>?=null
        val closed get()=ledger.confirmed("launch-returned","closed")
    }
    private class Entry(val owner: CaseOwners,val session: MobileSession,val storage: NativeSessionStorage,val context: P8MainFilesContext,
        val migration: NativeMigrationController?,val ports: P8MainDenyPorts,val activity: ActivityOwner?=null) {
        val ledger=Ledger();var sessionJob: Job?=null;var migrationJob: Job?=null
        var io: NativeMigrationIoWork?=null;var writer: ExecutorService?=null
        val released get()=ledger.confirmed("leave","session-joined","io-closed","io-idle","migration-joined","writer-drained","writer-retired","writer-terminated","port-evidence")
    }
    companion object {
        // Test-only strong references, including exact unknown FDs/scenarios/Jobs/executors.
        // No retry/clear API: unknown ownership survives this test instance until its original PID exits.
        private val retainedUntilProcessExit=CopyOnWriteArrayList<CaseOwners>()
    }
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    private fun awaitStore(store: NativeSessionStorage)=runBlocking {withTimeout(10000){store.awaitQueuedWrites()}}
    @Suppress("UNCHECKED_CAST") private fun addOwnedClient(session: MobileSession,client: MobileClient) {
        (MobileSession::class.java.getDeclaredField("realClients").apply{isAccessible=true}.get(session) as MutableList<MobileClient>).add(client)
    }
    private fun preference(store: NativeSessionStorage,id: String): String {
        awaitStore(store)
        val clients=checkNotNull(store.metadata()).getJSONArray("clients")
        return (0 until clients.length()).map {clients.getJSONObject(it)}.single {it.getString("id")==id}.getJSONObject("agentConnection").getString("preference")
    }
    private fun closeActivity(activity: ActivityOwner,errors: Failures) {
        activity.ledger.step("closed",errors,ready=activity.ledger.confirmed("launch-returned")) {checkNotNull(activity.scenario).close()}
    }
    private fun cleanupEntry(entry: Entry,errors: Failures) {
        // Known session/IO/controller handles can quiesce once even if Activity close is unknown.
        // Retirement and stable evidence wait for that UI owner plus every producer to be confirmed.
        val state=entry.ledger
        state.step("leave",errors){main {entry.session.leaveForeground()}}
        state.step("session-joined",errors){
            val tasks=MobileSession::class.java.getDeclaredField("tasks").apply{isAccessible=true}.get(entry.session) as CoroutineScope
            entry.sessionJob=checkNotNull(tasks.coroutineContext[Job])
            runBlocking {withTimeout(10000){withContext(Dispatchers.Main.immediate){entry.sessionJob!!.cancelAndJoin()}}}
        }
        state.step("io-closed",errors){entry.io=checkNotNull(entry.session.codexMigrationContext[NativeMigrationIoWork]);entry.io!!.close()}
        state.step("io-idle",errors,ready=state.confirmed("io-closed")){runBlocking {withTimeout(10000){entry.io!!.awaitIdle()}}}
        state.step("migration-joined",errors){entry.migration?.let {migration->
            val scope=NativeMigrationController::class.java.getDeclaredField("tasks").apply{isAccessible=true}.get(migration) as CoroutineScope
            entry.migrationJob=checkNotNull(scope.coroutineContext[Job])
            runBlocking {withTimeout(10000){withContext(Dispatchers.Main.immediate){entry.migrationJob!!.cancelAndJoin()}}}
        }}
        val producersJoined=(entry.activity?.closed!=false)&&state.confirmed("session-joined","io-closed","io-idle","migration-joined")
        state.step("writer-drained",errors,ready=producersJoined){awaitStore(entry.storage)}
        state.step("writer-retired",errors,ready=state.confirmed("writer-drained")){
            entry.writer=NativeSessionStorage::class.java.getDeclaredField("writes").apply{isAccessible=true}.get(entry.storage) as ExecutorService
            entry.storage.retireWriter()
        }
        state.step("writer-terminated",errors,ready=state.confirmed("writer-retired")){check(entry.writer!!.awaitTermination(10,TimeUnit.SECONDS)) {"Owned writer completion unknown"}}
        state.step("port-evidence",errors,ready=producersJoined){assertAndExportPorts(entry)}
    }
    private fun closeTracked(entry: Entry) {
        val errors=Failures();entry.activity?.let {closeActivity(it,errors)};cleanupEntry(entry,errors)
        if(!entry.released)errors.add(IllegalStateException("Owner incomplete; retain original PID ${entry.owner.pid}: ${entry.ledger.summary()}"))
        errors.throwIfAny()
        // Keep records, including confirmed handles. Never remove an entry to simulate release.
    }
    private fun status(phase: String,context: P8MainFilesContext,details: String="") {
        admission.emit(phase,JSONObject().put("ownedRun",context.run).put("ownedScope",context.root.path).put("ownedAlias",context.alias)
            .put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("processNonce",NativeChatNotificationColdStart.processNonce)
            .put("component",P8MainPreferenceActivity::class.java.name).put("coverage","Main lifecycle subclass; native assembly excluded; no shipping pixels")
            .put("defaultSelections",NativeChatNotificationColdStart.defaultSelections.get()).put("details",details))
    }
    private fun assertAndExportPorts(entry: Entry) {
        val calls=entry.ports.snapshot();val expected=checkNotNull(entry.owner.expectedClient)
        fun fields(target: TargetScope?)=target?.let {listOf(it.agentId,it.accountAuthority,it.accountSubject,it.hostId,it.workspaceId,it.runtimeId)}
        fun targetHash(target: TargetScope?)=MessageDigest.getInstance("SHA-256").digest((fields(target)?.let {JSONArray(it).toString()}?:"null").toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        // UsageViews:55–68 polls the visible saved-client row; MobileSession:583–609 sweeps directories.
        // Both are real local read attempts, rejected here rather than delegated or cache-prefilled.
        val unexpected=calls.filterNot {it.provider=="easywork"&&it.method in setOf("workspace","usage")&&it.clientId==expected.id&&
            fields(it.target)==fields(expected.scope)&&it.projectCursor==null&&it.threadCursor==null}
        val errors=Failures()
        errors.capture {check(entry.ports.overflow.get()==0&&unexpected.isEmpty()) {"Unexpected typed attempts: ${unexpected.map {it.provider+"/"+it.method}}; overflow=${entry.ports.overflow.get()}"}}
        errors.capture {check(entry.context.denied.isEmpty()) {"Runtime-context system/resolver forwarding was attempted"}}
        errors.capture {
            val counts=calls.groupingBy {it.provider+"/"+it.method+"/"+targetHash(it.target)}.eachCount()
            status("typed-port-proof-before-owner-release",entry.context,JSONObject().put("counts",JSONObject(counts))
                .put("allowed","exact synthetic EasyWork target/client: workspace(null cursors) directory sweep; usage visible-client presentation poll")
                .put("workspaceRejections",calls.count {it.method=="workspace"}).put("usageRejections",calls.count {it.method=="usage"})
                .put("expectedTargetSha256",targetHash(expected.scope)).put("expectedClientId",expected.id)
                .put("unexpected",unexpected.size).put("overflow",entry.ports.overflow.get()).put("contextDeniedCount",entry.context.denied.size)
                .put("contextDeniedLabels",JSONArray(entry.context.denied.take(16))).put("contextServiceForwardingByFixture",false)
                .put("externalNetworkProof",false).put("ledger",JSONObject(entry.ledger.summary())).toString())
        }
        errors.throwIfAny()
    }
    private fun launch(owner: CaseOwners): Entry {
        val activityOwner=ActivityOwner(owner);owner.activities.add(activityOwner)
        val errors=Failures()
        activityOwner.ledger.step("launch-returned",errors){activityOwner.scenario=ActivityScenario.launch(Intent(actual,P8MainPreferenceActivity::class.java).setAction(Intent.ACTION_MAIN).putExtra(P8MainPreferenceActivity.RUN,owner.run))}
        errors.throwIfAny()
        var result: Entry?=null
        activityOwner.ledger.step("owner-captured",errors){checkNotNull(activityOwner.scenario).onActivity {activity->
            result=Entry(owner,activity.session,activity.ownedStorage,activity.ownedContext,activity.migration,activity.deny,activityOwner)
            owner.entries.add(checkNotNull(result))
            assertTrue(activity.preInitDefaultFamiliesAbsent&&activity.afterCreateDefaultFamiliesAbsent)
        }};errors.throwIfAny()
        val entry=checkNotNull(result)
        ui.waitUntil(10000){!entry.session.restoring}
        ui.runOnIdle {assertTrue(entry.session.demo);assertTrue(entry.session.selectedClient.isDemo);assertNull(entry.session.discovery);assertNull(entry.session.easyWorkDiscovery);assertNull(entry.session.chatNotifications);assertNull(entry.session.voice)}
        status("inherited-Main-restored",entry.context)
        return entry
    }
    private fun openEditor(id: String) {
        ui.onNodeWithTag("client-selector").assertIsDisplayed().performClick()
        ui.onNodeWithTag("edit-client-$id").performScrollTo().performClick()
        ui.onNodeWithTag("connection-preference").performScrollTo().assertIsEnabled()
    }
    private fun options(expected: String,english: Boolean) {
        val labels=if(english)mapOf("automatic" to "Automatic","lan-only" to "Direct only","relay-only" to "Relay only")
            else mapOf("automatic" to "自动","lan-only" to "仅直连","relay-only" to "仅中继")
        labels.forEach {(value,label)->
            val row=ui.onNodeWithTag("connection-preference-$value").assertIsDisplayed().assertIsEnabled().assertHasClickAction().assertTextContains(label)
            if(value==expected)row.assertIsSelected()else row.assertIsNotSelected()
        }
    }
    @Test fun savedPreferenceThroughMainSubclassMenuPersistsWithoutNativeRuntime() {
        // Admission precedes every owned fixture/KeyStore/Session/Main acquisition.
        admission=NativeP8MainAdmission.admit()
        val cases=JSONArray()
        for(index in 0..1) {
            val row=admission.case(index)
            cases.put(runCase(row.getString("run"),row.getString("language"),row.getString("appearance")))
        }
        admission.finish(cases)
    }
    private fun runCase(run: String,language: String,appearance: String): JSONObject {
        check(UUID.fromString(run).toString()==run)
        val owner=CaseOwners(run);val errors=Failures()
        val seedPorts=P8MainDenyPorts();lateinit var seed: MobileSession
        val scope=TargetScope("easywork","https://issuer.main-ui.invalid","synthetic-account-$run","synthetic-host-$run","synthetic-workspace-$run","synthetic-runtime-$run")
        val connection=AgentConnection(scope,listOf(AgentCandidate("direct","lan-wss","https://192.0.2.6:7443","a".repeat(64)),AgentCandidate("relay","relay-http","https://relay.main-ui.invalid")),"synthetic-ref-$run")
        val client=MobileClient("p8-main-$run","Saved computer 中文 %@",scope,connection.candidates.first().endpoint,false,"easywork-runtime",false,requiresReadTarget=true,agentConnection=connection)
        owner.expectedClient=client
        val originalDraft="Owned Main menu draft 中文 😀 %@";val credentialName="easywork-credential:synthetic-ref-$run"
        val credential="Inert synthetic credential; never parse or send";val sentinelName="p8-owned-cache-sentinel";val sentinel="Synthetic local cache sentinel"
        var credentialBefore="";var journalName="";var journalBefore="";var selectedBefore="";var draftMapBefore="";var workspacesBefore=""
        var bodyPassed=false
        try {
            owner.ledger.step("acquisition-status",errors){admission.emit("before-scope-acquisition",JSONObject()
                .put("ownedRun",run).put("expectedOwnedScope",java.io.File(actual.noBackupFilesDir,"p8-main-preference-$run").path)
                .put("expectedOwnedAlias","p8-main-preference-$run").put("pid",owner.pid).put("processNonce",owner.nonce))};errors.throwIfAny()
            owner.ledger.step("scope-created",errors){owner.context=P8MainFilesContext.create(actual,run)};errors.throwIfAny()
            val context=checkNotNull(owner.context)
            owner.ledger.step("scope-pinned",errors){owner.fence=P8MainScopeBoundary(actual,context);owner.fence!!.pin()};errors.throwIfAny()
            owner.ledger.step("created-status",errors){status("scope-created-before-key-and-Main",context)};errors.throwIfAny()
            owner.ledger.step("keys-open",errors){owner.keys=KeyStore.getInstance("AndroidKeyStore");owner.keys!!.load(null)};errors.throwIfAny()
            owner.ledger.step("alias-new",errors){check(!owner.keys!!.containsAlias(context.alias)) {"Exact UUID alias already exists; preserve it"}};errors.throwIfAny()
            owner.ledger.step("store-created",errors){owner.storage=NativeSessionStorage(context,"session",context.alias)};errors.throwIfAny()
            val store=checkNotNull(owner.storage)
            owner.ledger.step("seed-created",errors){main {
                seed=MobileSession(store);owner.entries.add(Entry(owner,seed,store,context,null,seedPorts))
                seed.resetAcceptance(seedPorts,seedPorts,acceptanceEasyWorkPorts=seedPorts to seedPorts);seed.leaveForeground()
                // A saved Demo selection is valid only while Demo mode is enabled; use the product API.
                addOwnedClient(seed,client);seed.setDemoMode(true);seed.text=originalDraft;seed.changeLanguage(language);seed.changeAppearance(appearance)
                // This real product save API owns the preference and metadata persistence.
                seed.setEasyWorkPreference(client,"automatic")
            }};errors.throwIfAny()
            store.write(credentialName,credential);store.write(sentinelName,sentinel)
            val journal=NativeOperationJournal(store)
            val record=runBlocking {val prepared=journal.prepare(client,"owned-menu-thread","reply",ReplyIntent(client.id,"owned-menu-thread",UUID.randomUUID().toString(),"Owned synthetic immutable unknown input",emptyList(),"next-turn").body());journal.markSubmitting(prepared.target,prepared.id);journal.uncertain(prepared.target,prepared.id,"Synthetic unknown acknowledgement")}
            journalName="operations:"+record.target;journalBefore=checkNotNull(store.read(journalName));credentialBefore=checkNotNull(store.read(credentialName))
            awaitStore(store)
            val before=checkNotNull(store.metadata());selectedBefore=before.getString("selected");draftMapBefore=before.getJSONObject("drafts").toString();workspacesBefore=before.getJSONObject("workspaces").toString()
            closeTracked(owner.entries.last())
            var entry=launch(owner)
            fun assertProtectedOriginals() {
                awaitStore(entry.storage)
                val metadata=checkNotNull(entry.storage.metadata())
                assertTrue(metadata.getBoolean("demo"));assertEquals(selectedBefore,metadata.getString("selected"));assertEquals(draftMapBefore,metadata.getJSONObject("drafts").toString());assertEquals(workspacesBefore,metadata.getJSONObject("workspaces").toString())
                assertEquals(credentialBefore,entry.storage.read(credentialName));assertEquals(journalBefore,entry.storage.read(journalName));assertEquals(sentinel,entry.storage.read(sentinelName))
                ui.runOnIdle {
                    assertTrue(entry.session.demo);assertTrue(entry.session.selectedClient.isDemo);assertEquals(selectedBefore,entry.session.selectedClient.id);assertEquals(originalDraft,entry.session.text)
                    val live=entry.session.clients.single {it.id==client.id};assertEquals(connection.copy(preference=live.agentConnection!!.preference),live.agentConnection)
                    assertTrue(entry.session.operationRecords.isEmpty());assertNull(entry.session.chatNotifications)
                }
                assertTrue(entry.context.denied.isEmpty());P8MainFilesContext.assertDefaultFamiliesAbsent(actual)
            }
            openEditor(client.id);ui.onNodeWithTag("connection-preference").performClick();options("automatic",language=="en")
            for(next in listOf("lan-only","relay-only","automatic","relay-only")) {
                ui.onNodeWithTag("connection-preference-$next").performClick()
                listOf("automatic","lan-only","relay-only").forEach {ui.onNodeWithTag("connection-preference-$it").assertDoesNotExist()}
                assertEquals(next,preference(entry.storage,client.id));assertProtectedOriginals()
                ui.onNodeWithTag("cancel-client-form").performClick();ui.onNodeWithTag("edit-client-${client.id}").performScrollTo().performClick()
                ui.onNodeWithTag("connection-preference").performScrollTo().performClick();options(next,language=="en")
            }
            closeTracked(entry)
            // New Activity + new session/store handle; real restore reads the encrypted saved choice.
            entry=launch(owner);assertEquals("relay-only",preference(entry.storage,client.id));assertProtectedOriginals()
            ui.runOnIdle {assertEquals(language,entry.session.language);assertEquals(appearance,entry.session.appearance)}
            openEditor(client.id);ui.onNodeWithTag("connection-preference").performClick();options("relay-only",language=="en")
            closeTracked(entry);bodyPassed=true
        } catch(error: Throwable) {errors.add(error)} finally {
            // Each helper guards its own actions. UNKNOWN operations never run again.
            owner.activities.forEach {closeActivity(it,errors)}
            owner.entries.forEach {cleanupEntry(it,errors)}
            val acquisitionsKnown=owner.ledger.confirmed("scope-created","scope-pinned","keys-open","alias-new","store-created","seed-created")&&
                owner.activities.all {it.ledger.confirmed("launch-returned","owner-captured")}
            val ownersClosed=acquisitionsKnown&&owner.activities.all {it.closed}&&owner.entries.all {it.released}&&!P8MainMarkerRead.hasUnknown(run)
            if(!ownersClosed)errors.add(IllegalStateException("Unconfirmed owner retained to original PID ${owner.pid}/${owner.nonce}; no retry"))
            owner.ledger.step("final-defaults",errors,ready=ownersClosed){P8MainFilesContext.assertDefaultFamiliesAbsent(actual)}
            owner.ledger.step("final-status",errors,ready=owner.context!=null){status("owners-confirmed-or-retained-before-scope-cleanup",owner.context!!,
                JSONObject().put("ownerLedger",JSONObject(owner.ledger.summary())).put("entryLedgers",JSONArray(owner.entries.map {JSONObject(it.ledger.summary())}))
                    .put("activityLedgers",JSONArray(owner.activities.map {JSONObject(it.ledger.summary())})).put("acquisitionsKnown",acquisitionsKnown).put("ownersClosed",ownersClosed)
                    .put("stableFinalDefaultFamiliesConfirmed",owner.ledger.confirmed("final-defaults")).put("bodyPassed",bodyPassed).toString())}
            val deletionReady=bodyPassed&&errors.first==null&&ownersClosed&&owner.ledger.confirmed("scope-created","scope-pinned","alias-new","store-created","seed-created","final-defaults","final-status")
            owner.ledger.step("scope-removed",errors,ready=deletionReady){owner.fence!!.removeConfirmedOwnedScope {facts->
                status("complete-bounded-scope-inventory-before-delete",owner.context!!,JSONObject(facts).toString())
            }}
            owner.ledger.step("alias-removed",errors,ready=owner.ledger.confirmed("scope-removed","alias-new")){
                owner.keys!!.deleteEntry(owner.context!!.alias);check(!owner.keys!!.containsAlias(owner.context!!.alias))
            }
            if(!owner.ledger.confirmed("scope-removed","alias-removed")) {
                retainedUntilProcessExit.add(owner)
                owner.ledger.step("retained-status",errors,ready=owner.context!=null){status("retained-to-original-process-exit",owner.context!!,
                    JSONObject().put("originalPid",owner.pid).put("originalNonce",owner.nonce).put("ownerLedger",JSONObject(owner.ledger.summary()))
                        .put("entryLedgers",JSONArray(owner.entries.map {JSONObject(it.ledger.summary())}))
                        .put("scopeReads",owner.fence?.let {JSONObject(it.retentionSummary())}).put("markerReadUnknowns",JSONArray(P8MainMarkerRead.retainedSummary(run)))
                        .put("stableFinalDefaultFamiliesConfirmed",owner.ledger.confirmed("final-defaults")).toString())}
            }
        }
        errors.throwIfAny()
        check(bodyPassed&&owner.ledger.confirmed("final-defaults","scope-removed","alias-removed")&&!P8MainMarkerRead.hasUnknown(run))
        return JSONObject().put("run",run).put("language",language).put("appearance",appearance)
            .put("choices",JSONArray(listOf("lan-only","relay-only","automatic","relay-only")))
            .put("restoredPreference","relay-only").put("protectedOriginalsUnchanged",true)
            .put("stableFinalDefaultsConfirmed",true).put("scopeRemoved",true).put("aliasRemoved",true)
    }
}
