package com.agenthub.android

import android.content.Intent
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Real owned MainActivity/Session/storage and shipping coordinator; synthetic reading, Host and OS ports only.
 * Base Activity handoff is explicit here until the parent applies the shared ingress patch. No native notify/permission/Host/model call.
 */
class NativeChatNotificationCoordinatorTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule = NativeTestForeground()
    @get:Rule(order=2) val ui = createAndroidComposeRule<MainActivity>()
    private val cleanupGates=CopyOnWriteArrayList<CompletableDeferred<Unit>>()
    private val cleanupCoordinators=CopyOnWriteArrayList<NativeChatNotificationCoordinator>()
    private val cleanupScopes=CopyOnWriteArrayList<CoroutineScope>()
    private val cleanupSessions=CopyOnWriteArrayList<MobileSession>()
    private val cleanupStores=CopyOnWriteArrayList<NativeSessionStorage>()
    private var prepared=false
    private fun gate()=CompletableDeferred<Unit>().also {cleanupGates.add(it)}
    private fun own(session:MobileSession)=session.also {cleanupSessions.addIfAbsent(it)}
    private fun own(storage:NativeSessionStorage)=storage.also {cleanupStores.addIfAbsent(it)}
    // Inside the Activity rule: retain the original test/Before failure while finalizing
    // every acquired owner, before ActivityScenario itself is closed by its rule.
    @get:Rule(order=3) val cleanup:TestRule=object:TestRule {
        override fun apply(base:Statement,description:Description)=object:Statement() {
            override fun evaluate() {
                var original:Throwable?=null
                try {base.evaluate()}catch(error:Throwable){original=error}
                try {preserve(original,description.methodName?:description.displayName)}catch(error:Throwable) {
                    val primary=original?:throw error
                    if(primary!==error)primary.addSuppressed(error)
                    throw primary
                }
            }
        }
    }
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var fixture: RecoveryNativeFixture
    private lateinit var actual: MobileSession
    private lateinit var port: Port
    private lateinit var system: SystemPort
    private lateinit var coordinator: NativeChatNotificationCoordinator
    private lateinit var tasks: CoroutineScope
    private val target=TargetScope("codex",null,null,"fixture-host","fixture-workspace","desktop-runtime")
    private val token="fixture-token-"+"x".repeat(32)
    private val first=MobileThread("fixture-thread-1",target.hostId,"Fixture original","idle")
    private val other=MobileThread("fixture-other",target.hostId,"Fixture draft","idle")
    private val workspace get()=HostReadCodec.workspace(JSONObject().put("hostId",target.hostId).put("name","Fixture").put("connectionMode","desktop-runtime")
        .put("readTarget",CodexCommandCodec.target(target)).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
        .put("capabilities",JSONArray()).put("projects",JSONArray()).put("threads",JSONArray(listOf(first,other).map{JSONObject().put("id",it.id).put("hostId",it.hostId).put("title",it.title).put("state",it.state)})).toString())
    private var locateGate:CompletableDeferred<Unit>?=null
        set(value){field=value;value?.let {cleanupGates.addIfAbsent(it)}}
    private var locateEntered:CompletableDeferred<Unit>?=null
    private var locateOwner:Job?=null
    private var lateLocate=false
    private var navigationClaimGate: suspend (NativeChatNoticeReceipt, Job) -> Unit = { _, _ -> }
    private var feedGate:CompletableDeferred<Unit>?=null
        set(value){field=value;value?.let {cleanupGates.addIfAbsent(it)}}
    private var feedEntered:CompletableDeferred<Unit>?=null
    private val reader=object:HostReads {
        override suspend fun workspace(client:MobileClient,projectCursor:String?,threadCursor:String?):MobileWorkspace {
            locateOwner=currentCoroutineContext()[Job];locateEntered?.complete(Unit)
            if(lateLocate)withContext(NonCancellable){locateGate?.await()}else locateGate?.await()
            return workspace
        }
        override suspend fun history(client:MobileClient,threadId:String,cursor:String?)=HostReadCodec.history(JSONObject().put("hostId",target.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray()).toString())
    }
    private fun main(action:suspend()->Unit)=runBlocking {withContext(Dispatchers.Main.immediate){action()}}
    private fun io(action:suspend()->Unit)=runBlocking {withContext(Dispatchers.IO){action()}}
    private inner class Port(override val storage:NativeSessionStorage=owned.storage):NativeChatNotificationSessionPort {
        var ready=true;var foreground=true;var visible=true;var navigation=0;var readingReady=true
        val received=mutableListOf<String>();val active=mutableListOf<Set<String>>();var mayAlert=true;var opened=0
        override val reading=object:NativeChatNotificationReading {
            override suspend fun receive(event:NativeChatNotificationEvent):NativeChatNoticeReadingResult {
                check(Looper.myLooper()!=Looper.getMainLooper());received.add(event.id);return NativeChatNoticeReadingResult(true,3)
            }
            override fun mayPresent(event:NativeChatNotificationEvent):Boolean=mayAlert
        }
        override fun state()=NativeChatNotificationSessionState(ready&&!actual.restoring&&!actual.localRecoveryRequired,foreground,"${actual.targetKey}:$navigation",visible,readingReady)
        override suspend fun savedConnections():List<NativeChatNotificationSavedConnection> {
            check(Looper.myLooper()==Looper.getMainLooper());val clients=actual.clients.filter{!it.isDemo}.toList()
            val saved=withContext(Dispatchers.IO){storage.snapshotTransaction {clients.map{NativeChatNotificationSavedConnection.capture(it,storage.token(it.id))}}}
            check(saved.all{source->actual.clients.any(source::matches)});return saved
        }
        override suspend fun reconcileActive(target:NativeChatNoticeTarget,ids:Set<String>,current:()->Boolean){check(Looper.myLooper()!=Looper.getMainLooper());if(current())active.add(ids)}
        override fun openExistingThread(location:NativeChatNotificationLocation,current:()->Boolean):Boolean {
            check(Looper.myLooper()==Looper.getMainLooper());if(!current()||actual.clients.none(location.connection::matches))return false
            coordinator.invalidateBeforeChange();actual.select(actual.clients.single(location.connection::matches));actual.selectThread(location.thread);opened++
            return actual.selectedThread==location.thread.id
        }
    }
    private inner class SystemPort:NativeChatNotificationSystem {
        override val sounds=NativeChatNotificationSoundLibrary(ui.activity,port.storage)
        override fun languageChanged(selection:String){check(Looper.myLooper()==Looper.getMainLooper())}
        var allowed by mutableStateOf(true)
        val posts=mutableStateListOf<NativeChatNoticeReceipt>();val intents=mutableListOf<Intent>();var cancellations=0
        var prepareGate:CompletableDeferred<Unit>?=null
            set(value){field=value;value?.let {cleanupGates.addIfAbsent(it)}}
        var prepareEntered:CompletableDeferred<Unit>?=null;var prepareReturned:CompletableDeferred<Boolean>?=null;var afterPrepare:(()->Unit)?=null
        override fun permission()=NativeChatNotificationPermission(false,allowed,allowed)
        override fun channels()=emptyList<NativeChatNotificationChannelState>()
        override fun settings(channel:String?)=Intent("fixture-only-never-launched")
        override fun soundProviderRegistered()=false
        override fun channelSoundTitle(channel:NativeChatNotificationChannelState)="Fixture"
        override suspend fun prepareAsync(kind:NativeChatNoticeKind,sound:String,badge:Boolean,current:()->Boolean):String? {
            check(Looper.myLooper()==Looper.getMainLooper());prepareEntered?.complete(Unit);prepareGate?.await();afterPrepare?.invoke()
            return "fixture-channel".takeIf{allowed&&current()}.also{prepareReturned?.complete(it!=null)}
        }
        override fun cancelOwned(target:NativeChatNoticeTarget?){check(Looper.myLooper()==Looper.getMainLooper());cancellations++}
        override fun cancelEvents(target:NativeChatNoticeTarget,ids:Set<String>){check(Looper.myLooper()==Looper.getMainLooper())}
        override fun post(receipt:NativeChatNoticeReceipt,preferences:NativeChatNotificationPreferences,computer:String,unread:Int,channel:String){
            check(Looper.myLooper()==Looper.getMainLooper()&&unread==3);posts.add(receipt);intents.add(NativeChatNotificationPlatform.openIntent(ui.activity,receipt.ticket))
        }
        override suspend fun testAsync(preferences:NativeChatNotificationPreferences,current:()->Boolean)=false
    }
    private class PageOwner:LifecycleOwner {val registry=LifecycleRegistry(this);override val lifecycle:Lifecycle get()=registry}
    private lateinit var pageOwner:PageOwner
    private lateinit var originalIntent:Intent
    @Before fun prepare() {
        ui.waitUntil(30_000){!ui.activity.session.restoring}
        ui.runOnIdle {originalIntent=Intent(ui.activity.intent);owned=OwnedControlAcceptance(ui.activity,"chat-notification-coordinator").also {own(it.storage);own(it.session)};owned.session.resetAcceptance()}
        fixture=runBlocking{withContext(Dispatchers.IO){RecoveryNativeFixture(owned.storage)}}
        main {
            val commands=fixtureCodexCommands()
            actual=own(MobileSession(owned.storage,reader,commands));actual.resetAcceptance(reader,commands);actual.connect("Fixture","https://fixture.example.invalid",token);actual.selectThread(other);actual.text="Original owned draft"
            port=Port();system=SystemPort()
            bindCoordinator()
            pageOwner=PageOwner().also{it.registry.currentState=Lifecycle.State.RESUMED}
        }
        prepared=true
    }
    private fun fixtureCodexCommands()=object:CodexCommands by NativeCodexCommands({error("No real Host commands in this fixture")}) {override suspend fun models(client:MobileClient)=emptyList<CodexModel>()}
    private fun bindCoordinator(){
        tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).also {cleanupScopes.add(it)}
        val transport=object:NativeChatNotificationCommands,NativeChatNotificationRecoveryCommands by fixture.commands {
            override suspend fun register(snapshot:NativeChatNotificationSnapshot,identity:NativeChatNotificationIdentity,preferences:NativeChatNotificationPreferences):NativeChatNotificationStatus=error("No v1 write expected")
            override suspend fun preferences(snapshot:NativeChatNotificationSnapshot,deviceId:String,preferences:NativeChatNotificationPreferences):NativeChatNotificationStatus=error("No v1 write expected")
            override suspend fun unregister(snapshot:NativeChatNotificationSnapshot,deviceId:String):NativeChatNotificationStatus=error("No v1 write expected")
            override suspend fun status(snapshot:NativeChatNotificationSnapshot,deviceId:String)=withContext(Dispatchers.IO){NativeChatNotificationStatus(snapshot.target,"foreground-only","unavailable",fixture.notices.preferences().revision,null,false)}
            override suspend fun events(snapshot:NativeChatNotificationSnapshot,deviceId:String,after:String):NativeChatNotificationFeed=error("No v1 feed expected")
            override suspend fun recoveryFeed(snapshot:NativeChatNotificationSnapshot,original:NativeChatRecoveryIntent,query:NativeChatRecoveryQuery,reset:NativeChatRecoverySnapshot?):NativeChatRecoveryFeed {
                feedEntered?.complete(Unit);feedGate?.await();return fixture.commands.recoveryFeed(snapshot,original,query,reset)
            }
        }
        coordinator=NativeChatNotificationCoordinator(tasks,port,system,transport,NativeChatNotificationThreadLocator{reader},{fixture.now},60_000,ui.activity.packageName,navigationClaimPersisted={receipt,owner->navigationClaimGate(receipt,owner)}).also {cleanupCoordinators.add(it)}
        coordinator.updateComputers(actual.clients)
    }
    private fun preserve(original:Throwable?,method:String) {
        var failure=original
        var interrupted=Thread.interrupted()
        val steps=JSONArray()
        fun failed(error:Throwable) {
            if(error is InterruptedException)interrupted=true
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }
        fun attempt(label:String,block:()->Unit):Boolean {
            return try {block();steps.put(JSONObject().put("step",label).put("completed",true));true}
            catch(error:Throwable) {
                failed(error);steps.put(JSONObject().put("step",label).put("completed",false)
                    .put("failureType",error.javaClass.name));false
            }
        }
        fun boundedMain(block:suspend()->Unit)=runBlocking {
            withTimeout(10_000){withContext(Dispatchers.Main.immediate){block()}}
        }
        fun evidence(name:String,value:String) {
            check(::owned.isInitialized){"Owned evidence directory was not acquired"}
            val file=File(owned.folder,name)
            check(file.createNewFile()) {"Never overwrite original cleanup evidence"}
            file.writeText(value)
        }
        if(interrupted&&failure==null)failed(InterruptedException("Test evaluator completion was interrupted before cleanup"))
        val coordinators=cleanupCoordinators.toList();val scopes=cleanupScopes.toList()
        val sessions=cleanupSessions.toList();val stores=cleanupStores.toList()
        try {
            if(::owned.isInitialized)attempt("cleanup-start-evidence") {
                evidence("owner-cleanup-started.json",JSONObject().put("version",1).put("method",method)
                    .put("pid",android.os.Process.myPid()).put("prepared",prepared).put("completed",false)
                    .put("coordinators",coordinators.size).put("scopes",scopes.size)
                    .put("sessions",sessions.size).put("stores",stores.size).toString())
            }
            // Fence callbacks before releasing fixture gates, including retired bindings.
            coordinators.forEachIndexed {index,value->attempt("coordinator-dispose-$index"){boundedMain {value.dispose()}}}
            scopes.forEachIndexed {index,value->attempt("scope-cancel-$index"){value.cancel()}}
            sessions.forEachIndexed {index,value->attempt("session-background-$index"){boundedMain {value.leaveForeground()}}}
            attempt("clear-owned-content"){boundedMain {ui.activity.setContent {}}}
            cleanupGates.forEachIndexed {index,value->attempt("release-fixture-gate-$index"){value.complete(Unit)}}
            val coordinatorJoined=coordinators.mapIndexed {index,value->
                attempt("coordinator-join-and-io-idle-$index"){boundedMain {value.stopAndJoinForMigration()}}
            }
            val scopeJoined=scopes.mapIndexed {index,value->
                attempt("scope-cancel-and-join-$index"){runBlocking {withTimeout(10_000){value.coroutineContext[Job]!!.cancelAndJoin()}}}
            }
            val sessionJoined=sessions.mapIndexed {index,value->
                // Existing terminal lifecycle API, as in P8. This does not begin,
                // restore, publish or resume a migration or replay any command.
                attempt("session-stop-and-join-$index"){boundedMain {value.abandonMigrationRestore()}}
            }
            val knownOwnersJoined=coordinatorJoined.all {it}&&scopeJoined.all {it}&&sessionJoined.all {it}
            var defaultCheckCompleted=false
            if(::originalIntent.isInitialized)attempt("restore-original-intent"){boundedMain {ui.activity.setIntent(originalIntent)}}
            if(::owned.isInitialized)defaultCheckCompleted=attempt("verify-default-originals"){boundedMain {owned.verifyDefault()}}
            if(::actual.isInitialized)attempt("verify-no-business-operations"){boundedMain {check(actual.operationRecords.isEmpty())}}
            val writers=JSONArray()
            stores.forEachIndexed {index,value->
                // A timed-out join is unknown. Never retire a writer while an owner
                // may still enqueue, and never delete its store or key on failure.
                val drain=prepared&&knownOwnersJoined&&attempt("queued-write-barrier-$index") {
                    runBlocking {withTimeout(10_000){value.awaitQueuedWrites()}}
                }
                val retired=drain&&attempt("request-writer-shutdown-$index"){value.retireWriter()}
                writers.put(JSONObject().put("index",index).put("queuedWriteBarrierCompleted",drain)
                    .put("retireWriterReturned",retired).put("executorShutdownRequested",retired)
                    .put("physicalExecutorTerminationVerified",false)
                    .put("retainedForIndependentReadback",true)
                    .put("notRetiredReason",if(retired)JSONObject.NULL else "owner-or-write-completion-not-confirmed"))
            }
            if(::owned.isInitialized) {
                attempt("cleanup-result-evidence") {
                    evidence("owner-cleanup.json",JSONObject().put("version",1).put("method",method)
                        .put("pid",android.os.Process.myPid()).put("prepared",prepared)
                        .put("knownCoordinatorsJoined",coordinatorJoined.all {it})
                        .put("knownParentScopesJoined",scopeJoined.all {it}).put("knownSessionsJoined",sessionJoined.all {it})
                        .put("defaultCheckCompleted",defaultCheckCompleted)
                        .put("defaultStorageWritersRetired",false).put("defaultStartupRuntimeRetirementVerified",false)
                        .put("outerJUnitTimeoutCompletionVerified",false).put("processExitVerified",false)
                        .put("cleanupFailureType",failure?.javaClass?.name?:JSONObject.NULL)
                        .put("writers",writers).put("steps",steps).toString())
                }
                if(failure!=null)attempt("cleanup-failure-stack") {
                    evidence("owner-cleanup-failure.txt",failure!!.stackTraceToString())
                }
            }
        } finally {if(interrupted)Thread.currentThread().interrupt()}
        failure?.let {throw it}
    }
    private fun start(){main{coordinator.reconcile()}}
    private fun synchronized(){ui.waitUntil(10_000){coordinator.reports.values.any{it.state in setOf("foreground-synchronized","foreground-outcome-unknown")}}}
    private fun mount() {
        ui.runOnIdle{ui.activity.setContent {ProductTextProvider("en"){MaterialTheme{CompositionLocalProvider(LocalLifecycleOwner provides pageOwner){
            var page by remember{mutableStateOf(false)}
            Column(Modifier.fillMaxSize()){if(page)NativeChatNotificationsPage(coordinator)else NativeChatNotificationSettingsEntry(coordinator){page=true};NativeChatNotificationOpenStatus(coordinator)}
        }}}}}
    }
    private fun ownedActivityIntent(intent:Intent){main{ui.activity.setIntent(intent);check(coordinator.receiveIntent(ui.activity.intent))}}
    private fun ticket():String {
        var ticket="";main{val source=port.savedConnections().single();ticket=withContext(Dispatchers.IO){val lease=coordinator.store.bind(source.target,source.snapshot.credentialEpoch);coordinator.store.claim(fixture.event(),lease,fixture.now)!!.also{coordinator.store.finish(it.ticket,true)}.ticket}};return ticket
    }
    @Test fun settingsEntryRoutesActualPageAndDefaultOffCreatesNoIdentity(){
        io{fixture.notices.update{it.copy(enabled=false)}};start();mount();ui.onNodeWithTag("settings-chat-notifications").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("chat-notifications-enabled").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("chat-notifications-enabled").assertIsOff()
        check(fixture.storage.read(NativeChatNotificationIdentity.RECORD)==null&&fixture.storage.read(NativeChatNotificationAuthorizations.RECORD)==null&&fixture.submitted.isEmpty())
    }
    @Test fun stableSnapshotPublicationDoesNotRestartOrAcceleratePoll(){start();synchronized();val reads=fixture.queries.size;repeat(10){main{coordinator.reconcile()}};Thread.sleep(300);check(fixture.queries.size==reads&&fixture.submitted.size==1)}
    @Test fun permissionDenialAndRecoveryShowOnlyNewEventThroughRequiredReadingPort(){
        main{system.allowed=false};fixture.events.add(fixture.event(1));start();synchronized();check(system.posts.isEmpty()&&port.received.size==1)
        fixture.events.add(fixture.event(2));main{system.allowed=true;coordinator.preferencesSaved()};ui.waitUntil(10_000){system.posts.size==1};check(system.posts.single().event.id==fixture.event(2).id&&port.received.size==2)
    }
    @Test fun foregroundPresentationUsesOpaqueIntentAndP7UnreadCount(){
        val event=fixture.event();fixture.events.add(event);start()
        // A visible posts append precedes the rest of the Main callback. Wait for the
        // actual durable delivery outcome, then inspect both callback products on Main.
        io{withTimeout(10_000){
            while(true){
                val outcome=fixture.view().outcomes.singleOrNull{it.event.id==event.id}
                if(outcome?.terminal==true){check(outcome.state=="submitted-to-system");break}
                delay(10)
            }
        }}
        main{val posted=system.posts.single();val intent=system.intents.single()
            check(posted.event.id==event.id&&port.received.single()==event.id)
            check(intent.extras!!.keySet()==setOf(NativeChatNotificationPlatform.TICKET)&&intent.getStringExtra(NativeChatNotificationPlatform.TICKET)==posted.ticket)
        }
    }
    @Test fun readingRecheckAfterChannelPreparationPreventsOldBanner(){system.afterPrepare={port.mayAlert=false};fixture.events.add(fixture.event());start();synchronized();check(port.received.size==1&&system.posts.isEmpty())}
    @Test fun backgroundInvalidatesSuspendedPresentationBeforeSystemPost(){
        system.prepareEntered=CompletableDeferred();system.prepareGate=CompletableDeferred();fixture.events.add(fixture.event());start();runBlocking{withTimeout(10_000){system.prepareEntered!!.await()}}
        main{coordinator.invalidateBeforeChange();port.foreground=false;coordinator.reconcile();system.prepareGate!!.complete(Unit)};Thread.sleep(150);check(system.posts.isEmpty())
    }
    @Test fun oldCallbackCannotPostAfterNavigation(){
        val event=fixture.event()
        system.prepareEntered=CompletableDeferred();system.prepareGate=CompletableDeferred();system.prepareReturned=CompletableDeferred();fixture.events.add(event)
        start();runBlocking{withTimeout(10_000){system.prepareEntered!!.await()}}
        main{
            check(coordinator.reports.isEmpty()&&actual.selectedThread==other.id&&actual.text=="Original owned draft")
            coordinator.navigationWillChange();actual.selectThread(first);actual.text="New navigation draft";port.navigation++;coordinator.reconcile();system.prepareGate!!.complete(Unit)
        }
        runBlocking{withTimeout(10_000){check(!system.prepareReturned!!.await())}}
        // The journal writes this terminal result only AFTER the held delivery callback has returned.
        // Its stale presentation lease must never publish a new synchronized report.
        io{withTimeout(10_000){
            while(true){
                val outcome=fixture.view().outcomes.singleOrNull{it.event.id==event.id}
                if(outcome?.terminal==true){check(outcome.state=="system-unavailable");break}
                delay(10)
            }
        }}
        ui.waitForIdle()
        main{
            check(system.posts.isEmpty()&&system.intents.isEmpty()&&coordinator.reports.isEmpty())
            check(port.received==listOf(event.id)&&fixture.queries.size==1&&fixture.submitted.size==1)
            check(actual.selectedThread==first.id&&actual.text=="New navigation draft"&&port.opened==0)
            coordinator.navigationWillChange();actual.selectThread(other);port.navigation++
            check(actual.text=="Original owned draft")
        }
    }
    @Test fun coldIntentWaitsForRealSessionReadyThenPreservesOtherDraft(){
        val ticket=ticket();main{port.ready=false};ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket));check(port.opened==0&&actual.selectedThread==other.id)
        main{port.ready=true;coordinator.reconcile()};ui.waitUntil(10_000){coordinator.openState=="opened"};main{check(actual.selectedThread==first.id);actual.selectThread(other);check(actual.text=="Original owned draft")}
    }
    @Test fun warmIntentAndDuplicateTicketOpenExactlyOnce(){val ticket=ticket();ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket));ui.waitUntil(10_000){coordinator.openState=="opened"};ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket));Thread.sleep(100);check(port.opened==1)}
    @Test fun foreignTargetAndCredentialNeverUseAnotherConnection(){
        val ticket=ticket();io{fixture.storage.setToken(target.hostId,"u".repeat(43))};ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket));ui.waitUntil(10_000){coordinator.openState=="unavailable"};check(port.opened==0)
    }
    @Test fun unavailableOriginalThreadIsNotSynthesized(){
        var ticket="";main{val source=port.savedConnections().single();ticket=withContext(Dispatchers.IO){val lease=coordinator.store.bind(source.target,source.snapshot.credentialEpoch);coordinator.store.claim(fixture.event().copy(threadId="missing-original"),lease,fixture.now)!!.ticket}}
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket));ui.waitUntil(10_000){coordinator.openState=="unavailable"};check(port.opened==0&&actual.selectedThread==other.id)
    }
    @Test fun invalidOpaqueIntentIsRecognizedAndNeverBecomesPairingLink(){
        main{check(coordinator.receiveIntent(Intent(NativeChatNotificationPlatform.ACTION).putExtra(NativeChatNotificationPlatform.TICKET,UUID.randomUUID().toString()).putExtra("threadId",first.id)))};check(coordinator.openState=="unavailable"&&port.opened==0)
    }
    @Test fun navigationDuringOriginalLookupDoesNotConsumeOrOpenTicket(){
        val ticket=ticket();locateGate=CompletableDeferred();locateEntered=CompletableDeferred();ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket));runBlocking{withTimeout(10_000){locateEntered!!.await()}}
        main{coordinator.invalidateBeforeChange();port.navigation++;coordinator.reconcile();locateGate!!.complete(Unit)};ui.waitUntil(10_000){coordinator.openState=="unavailable"};check(port.opened==0);io{check(coordinator.store.ticket(ticket,fixture.now)!=null)}
    }
    @Test fun unknownQueryButtonUsesRetainedOriginalAndNeverPostsAgain(){
        fixture.loseAck=true;start();ui.waitUntil(10_000){coordinator.reports.values.any{it.state=="mutation-unknown-original-query"}};mount();ui.onNodeWithTag("settings-chat-notifications").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("chat-notification-query-original-"+chatNotificationTargetTag(fixture.target)).fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("chat-notification-query-original-"+chatNotificationTargetTag(fixture.target)).performClick()
        ui.waitUntil(10_000){fixture.queried.isNotEmpty()};check(fixture.submitted.size==1&&fixture.queried.all{it.operationId==fixture.submitted.single().operationId})
    }
    @Test fun restoredOriginalAuthoritySurvivesCredentialRotation(){
        fixture.loseAck=true;fixture.saveReceipt=false;start();ui.waitUntil(10_000){fixture.submitted.size==1};ui.waitUntil(10_000){coordinator.reports.isNotEmpty()}
        main{coordinator.invalidateBeforeChange()};io{fixture.storage.setToken(target.hostId,"u".repeat(43))};main{coordinator.reconcile();coordinator.queryOriginals(fixture.target)}
        ui.waitUntil(10_000){fixture.queried.isNotEmpty()};check(fixture.queried.all{it.encoded()==fixture.submitted.first().encoded()})
        io{check(NativeChatNotificationAuthorizations(fixture.storage).all().any{it.snapshot.authorizationFingerprint==fixture.view().operations.first().authorization})}
    }
    @Test fun missingRetainedAuthorityShowsRecoveryInsteadOfNewId(){
        fixture.loseAck=true;fixture.saveReceipt=false;start();ui.waitUntil(10_000){coordinator.reports.values.any{it.state=="mutation-unknown-original-query"}}
        main{coordinator.invalidateBeforeChange()};io{fixture.storage.remove(NativeChatNotificationAuthorizations.RECORD);fixture.storage.setToken(target.hostId,"u".repeat(43))}
        main{coordinator.queryOriginals(fixture.target)};ui.waitUntil(10_000){coordinator.originalReports[fixture.target]?.state=="original-credential-recovery-required"};check(fixture.submitted.size==1)
    }
    @Test fun wrongFullTargetTicketNeverSelectsKnownHostWithDifferentRuntime(){
        var ticket="";io{val foreign=fixture.target.copy(runtimeId="foreign-runtime");val lease=coordinator.store.bind(foreign,"fixture-foreign-authority");ticket=coordinator.store.claim(fixture.event().copy(target=foreign),lease,fixture.now)!!.ticket}
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket));ui.waitUntil(10_000){coordinator.openState=="unavailable"};check(port.opened==0)
    }
    @Test fun expiredTicketCannotReadOrNavigate(){
        var ticket="";main{val source=port.savedConnections().single();ticket=withContext(Dispatchers.IO){val then=fixture.now-NativeChatNotificationStore.TTL_MILLIS-1;val lease=coordinator.store.bind(source.target,source.snapshot.credentialEpoch);coordinator.store.claim(fixture.event().copy(occurredAt=java.time.Instant.ofEpochMilli(then).toString()),lease,then)!!.ticket}}
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket));ui.waitUntil(10_000){coordinator.openState=="unavailable"};check(port.opened==0)
    }
    @Test fun queryButtonOnDefaultOffDoesNotCreateIdentityOrRegister(){
        io{fixture.notices.update{it.copy(enabled=false)}};start();main{coordinator.queryOriginals(fixture.target)}
        ui.waitUntil(5_000){coordinator.originalReports[fixture.target]?.state=="original-query-complete"};check(fixture.submitted.isEmpty()&&fixture.storage.read(NativeChatNotificationIdentity.RECORD)==null)
    }

    @Test fun removedConnectionKeepsOriginalTargetAndRetainedQueryVisible(){
        fixture.loseAck=true;start();ui.waitUntil(10_000){coordinator.reports.values.any{it.state=="mutation-unknown-original-query"}}
        main{coordinator.connectionsWillChange();actual.removeClient(target.hostId);coordinator.updateComputers(actual.clients);coordinator.reconcile()}
        ui.waitUntil(10_000){coordinator.originalReports.containsKey(fixture.target)};main{coordinator.queryOriginals(fixture.target)}
        ui.waitUntil(10_000){fixture.queried.isNotEmpty()};check(fixture.submitted.size==1&&actual.clients.none{!it.isDemo})
    }

    @Test fun newerIntentSupersedesOldLookupWithoutConsumingOldTicket(){
        val older=ticket();var newer="";main{val source=port.savedConnections().single();newer=withContext(Dispatchers.IO){val lease=coordinator.store.bind(source.target,source.snapshot.credentialEpoch);coordinator.store.claim(fixture.event(2).copy(threadId=other.id),lease,fixture.now)!!.ticket}}
        locateGate=CompletableDeferred();locateEntered=CompletableDeferred();ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,older));runBlocking{withTimeout(10_000){locateEntered!!.await()}}
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,newer));main{locateGate!!.complete(Unit)};ui.waitUntil(10_000){coordinator.openState=="opened"}
        check(port.opened==1&&actual.selectedThread==other.id);io{check(coordinator.store.ticket(older,fixture.now)!=null)}
    }

    @Test fun credentialReplacementDuringLookupKeepsOriginalTicketAndDraft(){
        main{port.readingReady=false};val original=ticket();locateGate=CompletableDeferred();locateEntered=CompletableDeferred()
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,original));runBlocking{withTimeout(10_000){locateEntered!!.await()}}
        main{coordinator.connectionsWillChange()};io{port.storage.setToken(target.hostId,"u".repeat(43))}
        main{coordinator.reconcile();locateGate!!.complete(Unit)}
        ui.waitUntil(10_000){coordinator.openState=="unavailable"}
        main{check(port.opened==0&&actual.selectedThread==other.id&&actual.text=="Original owned draft"&&system.posts.isEmpty())}
        io{check(coordinator.store.ticket(original,fixture.now)!=null)}
        check(fixture.submitted.isEmpty()&&fixture.queries.isEmpty())
    }
    @Test fun restoredSessionCanOpenOriginalBeforeReadingLedgerAllowsPolling(){
        val original=ticket();main{port.ready=false;port.readingReady=false;coordinator.reconcile()}
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,original))
        main{check(coordinator.openState=="waiting-for-restoration"&&port.opened==0);port.ready=true;coordinator.reconcile()}
        ui.waitUntil(10_000){coordinator.openState=="opened"}
        main{check(actual.selectedThread==first.id&&port.opened==1&&fixture.submitted.isEmpty()&&fixture.queries.isEmpty())}
        io{check(port.storage.read(NativeChatNotificationIdentity.RECORD)==null)}
        main{port.readingReady=true;coordinator.reconcile()};synchronized()
        main{check(fixture.submitted.size==1&&port.received.isEmpty());coordinator.navigationWillChange();actual.selectThread(other);check(actual.text=="Original owned draft")}
    }
    @Test fun disposedBindingCannotPublishLateLookupIntoReplacementStorage(){
        val oldCoordinator=coordinator;val oldPort=port;val oldActual=actual;val oldFixture=fixture;val oldSystem=system
        val original=ticket();val gate=CompletableDeferred<Unit>();locateGate=gate;locateEntered=CompletableDeferred();lateLocate=true
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,original));runBlocking{withTimeout(10_000){locateEntered!!.await()}}
        val lookup=checkNotNull(locateOwner)
        try{
            main{oldCoordinator.dispose();tasks.cancel();oldActual.leaveForeground();locateGate=null;lateLocate=false}
            var replacement:NativeSessionStorage?=null
            io{replacement=own(owned.storage.child(ui.activity,"p9-binding-replacement"));fixture=RecoveryNativeFixture(checkNotNull(replacement))}
            main{
                val storage=checkNotNull(replacement);val commands=fixtureCodexCommands()
                actual=own(MobileSession(storage,reader,commands));actual.resetAcceptance(reader,commands);actual.connect("Replacement","https://fixture.example.invalid",token);actual.selectThread(other);actual.text="Replacement owned draft"
                port=Port(storage).also{it.readingReady=false};system=SystemPort();bindCoordinator();coordinator.reconcile()
            }
        }finally{gate.complete(Unit)}
        runBlocking{withTimeout(10_000){lookup.join()}}
        main{
            check(oldPort.storage!==port.storage&&oldPort.opened==0&&port.opened==0)
            check(actual.selectedThread==other.id&&actual.text=="Replacement owned draft"&&coordinator.openState==null&&coordinator.reports.isEmpty())
            check(system.posts.isEmpty()&&oldSystem.posts.isEmpty()&&fixture.submitted.isEmpty()&&oldFixture.submitted.isEmpty())
        }
        io{
            check(oldCoordinator.store.ticket(original,oldFixture.now)!=null)
            for(record in listOf(NativeChatNotificationIdentity.RECORD,NativeChatNotificationAuthorizations.RECORD,NativeChatNotificationRecoveryStore.RECORD,NativeChatNotificationForegroundStore.RECORD))check(port.storage.read(record)==null)
        }
    }
    @Test fun actualSessionRestoreRetainsOriginalUnknownWithoutAnotherSubmit(){
        fixture.loseAck=true;fixture.saveReceipt=false;start();ui.waitUntil(10_000){coordinator.reports.values.any{it.state=="mutation-unknown-original-query"}}
        val original=fixture.submitted.single().encoded();var identity:String?=null
        io{owned.storage.awaitQueuedWrites();identity=owned.storage.read(NativeChatNotificationIdentity.RECORD)}
        main{
            coordinator.dispose();tasks.cancel();actual.leaveForeground()
            actual=own(MobileSession(owned.storage,reader,fixtureCodexCommands()));port=Port().also{it.readingReady=false};system=SystemPort();bindCoordinator()
            coordinator.reconcile();check(coordinator.reports.isEmpty());actual.restore()
        }
        ui.waitUntil(10_000){!actual.restoring}
        main{
            check(!actual.localRecoveryRequired&&actual.clients.any{it.id==target.hostId}&&actual.selectedThread==other.id&&actual.text=="Original owned draft")
            coordinator.reconcile();coordinator.queryOriginals(fixture.target)
        }
        ui.waitUntil(10_000){coordinator.originalReports[fixture.target]?.state=="mutation-unknown-original-query"}
        main{check(coordinator.reports.isEmpty()&&system.posts.isEmpty()&&fixture.submitted.size==1&&fixture.queries.isEmpty())}
        check(fixture.queried.isNotEmpty()&&fixture.queried.all{it.encoded()==original})
        io{check(owned.storage.read(NativeChatNotificationIdentity.RECORD)==identity&&fixture.view().operations.single().original.encoded()==original)}
    }


    private fun originalNavigationReceipt(ticket: String): NativeChatNoticeReceipt =
        JSONObject(requireNotNull(port.storage.read(NativeChatNotificationStore.RECORD))).getJSONArray("receipts").objects()
            .single { it.getString("ticket") == ticket }.let(NativeChatNotificationCodec::receipt)

    @Test fun backgroundAfterDurableNavigationClaimResumesOnlyOriginalReadOnlyOpen() {
        val ticket=ticket();val entered=CompletableDeferred<NativeChatNoticeReceipt>()
        lateinit var originalOpenJob: Job
        var checkpoints=0
        main {
            port.readingReady=false
            navigationClaimGate={receipt,owner->
                checkpoints++
                if(checkpoints==1) {
                    originalOpenJob=owner
                    entered.complete(receipt)
                    awaitCancellation()
                }
            }
        }
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket))
        val pending=runBlocking {withTimeout(10_000){entered.await()}}
        check(pending.consumed&&pending.navigationClaim!=null)
        main {check(port.opened==0&&actual.selectedThread==other.id);port.foreground=false;coordinator.reconcile()}
        runBlocking {withTimeout(10_000){originalOpenJob.join()}}
        io {
            val restored=NativeChatNotificationStore(port.storage)
            check(restored.navigationTicket(ticket,fixture.now)==pending)
            check(restored.ticket(ticket,fixture.now)==null)
        }
        main {port.foreground=true;coordinator.reconcile()}
        ui.waitUntil(10_000){coordinator.openState=="opened"}
        main {
            check(port.opened==1&&actual.selectedThread==first.id&&!coordinator.openReceiptPending)
            check(fixture.submitted.isEmpty()&&fixture.queries.isEmpty()&&actual.operationRecords.isEmpty())
            actual.selectThread(other);check(actual.text=="Original owned draft")
        }
        io {
            val settled=originalNavigationReceipt(ticket)
            check(settled.consumed&&settled.navigationClaim==null)
            check(settled.event==pending.event&&settled.lease==pending.lease&&settled.expiresAt==pending.expiresAt)
            check(coordinator.store.navigationTicket(ticket,fixture.now)==null)
        }
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket))
        main {check(port.opened==1)}
    }

    @Test fun pendingNavigationStoreReloadKeepsOriginalNonceWithoutAnotherClaimWrite() {
        val ticket=ticket()
        io {
            val original=requireNotNull(coordinator.store.claimNavigation(ticket,fixture.now){true})
            val bytes=port.storage.read(NativeChatNotificationStore.RECORD)
            val fresh=NativeChatNotificationStore(port.storage)
            check(fresh.navigationTicket(ticket,fixture.now)==original)
            check(fresh.claimNavigation(ticket,fixture.now){true}==original)
            check(port.storage.read(NativeChatNotificationStore.RECORD)==bytes)
            check(fresh.ticket(ticket,fixture.now)==null&&original.navigationClaim!=null)
            check(fresh.acknowledgeNavigation(original))
            check(originalNavigationReceipt(ticket)==original.copy(navigationClaim=null))
            check(fresh.navigationTicket(ticket,fixture.now)==null)
        }
    }

    @Test fun legacySpentV1TicketStaysTerminalAndOnlyUnconsumedV1UpgradesOnClaim() {
        val ticket=ticket()
        io {
            val legacy=JSONObject(requireNotNull(port.storage.read(NativeChatNotificationStore.RECORD))).put("version",1)
            legacy.getJSONArray("receipts").objects().single().put("consumed",true).remove("navigationClaim")
            port.storage.write(NativeChatNotificationStore.RECORD,legacy.toString())
            val before=port.storage.read(NativeChatNotificationStore.RECORD);val fresh=NativeChatNotificationStore(port.storage)
            check(fresh.navigationTicket(ticket,fixture.now)==null)
            check(fresh.claimNavigation(ticket,fixture.now){true}==null)
            check(port.storage.read(NativeChatNotificationStore.RECORD)==before)
            legacy.getJSONArray("receipts").objects().single().put("consumed",false)
            port.storage.write(NativeChatNotificationStore.RECORD,legacy.toString())
            val claimed=requireNotNull(fresh.claimNavigation(ticket,fixture.now){true})
            check(claimed.consumed&&claimed.navigationClaim!=null)
            check(JSONObject(requireNotNull(port.storage.read(NativeChatNotificationStore.RECORD))).getInt("version")==2)
        }
    }

    @Test fun expiredPendingNavigationCannotBeReclaimedOrNavigate() {
        val ticket=ticket()
        io {
            val pending=requireNotNull(coordinator.store.claimNavigation(ticket,fixture.now){true})
            val before=port.storage.read(NativeChatNotificationStore.RECORD)
            check(coordinator.store.navigationTicket(ticket,pending.expiresAt)==null)
            check(coordinator.store.claimNavigation(ticket,pending.expiresAt){true}==null)
            check(port.storage.read(NativeChatNotificationStore.RECORD)==before)
        }
        main {check(port.opened==0&&actual.selectedThread==other.id&&actual.operationRecords.isEmpty())}
    }

    @Test fun credentialRotationCannotResumeAnOldPendingNavigationClaim() {
        val ticket=ticket()
        io {
            val pending=requireNotNull(coordinator.store.claimNavigation(ticket,fixture.now){true})
            coordinator.store.bind(pending.lease.target,"new-credential-epoch")
            val before=port.storage.read(NativeChatNotificationStore.RECORD)
            check(coordinator.store.navigationTicket(ticket,fixture.now)==null)
            check(coordinator.store.claimNavigation(ticket,fixture.now){true}==null)
            check(port.storage.read(NativeChatNotificationStore.RECORD)==before)
        }
    }

    @Test fun preferenceRotationCannotResumeAnOldPendingNavigationClaim() {
        val ticket=ticket()
        io {
            requireNotNull(coordinator.store.claimNavigation(ticket,fixture.now){true})
            coordinator.store.update {it.copy(foregroundBanner=!it.foregroundBanner)}
            val before=port.storage.read(NativeChatNotificationStore.RECORD)
            check(coordinator.store.navigationTicket(ticket,fixture.now)==null)
            check(coordinator.store.claimNavigation(ticket,fixture.now){true}==null)
            check(port.storage.read(NativeChatNotificationStore.RECORD)==before)
        }
    }

    @Test fun staleNavigationAckCannotClearAReplacementClaimOrPreferences() {
        val ticket=ticket()
        io {
            val original=requireNotNull(coordinator.store.claimNavigation(ticket,fixture.now){true})
            val raw=JSONObject(requireNotNull(port.storage.read(NativeChatNotificationStore.RECORD)))
            raw.getJSONArray("receipts").objects().single().put("navigationClaim",UUID.randomUUID().toString())
            port.storage.write(NativeChatNotificationStore.RECORD,raw.toString())
            val before=port.storage.read(NativeChatNotificationStore.RECORD)
            check(!coordinator.store.acknowledgeNavigation(original))
            check(port.storage.read(NativeChatNotificationStore.RECORD)==before)
            val replacement=requireNotNull(coordinator.store.navigationTicket(ticket,fixture.now))
            check(replacement.navigationClaim!=original.navigationClaim&&replacement.event==original.event&&replacement.lease==original.lease)
        }
    }

    @Test fun navigationClaimCodecRejectsLegacyInjectionMalformedNonceAndUnconsumedClaim() {
        val ticket=ticket()
        io {
            val original=requireNotNull(coordinator.store.claimNavigation(ticket,fixture.now){true})
            val valid=JSONObject(requireNotNull(port.storage.read(NativeChatNotificationStore.RECORD)))
            NativeChatNotificationStore.validateEncoded(valid.toString())
            for(version in listOf(1,3)) {
                val changed=JSONObject(valid.toString()).put("version",version)
                check(runCatching {NativeChatNotificationStore.validateEncoded(changed.toString())}.isFailure)
            }
            for(nonce in listOf("", "not-a-uuid","00000000-0000-4000-8000-00000000000A")) {
                val changed=JSONObject(valid.toString())
                changed.getJSONArray("receipts").objects().single().put("navigationClaim",nonce)
                check(runCatching {NativeChatNotificationStore.validateEncoded(changed.toString())}.isFailure)
            }
            val unconsumed=JSONObject(valid.toString())
            unconsumed.getJSONArray("receipts").objects().single().put("consumed",false)
            check(runCatching {NativeChatNotificationStore.validateEncoded(unconsumed.toString())}.isFailure)
        }
    }


    @Test fun preferenceInvalidationAfterDurableClaimStopsTheOriginalMainRoute() {
        val ticket=ticket();val entered=CompletableDeferred<NativeChatNoticeReceipt>();val release=gate()
        lateinit var originalOpenJob: Job
        main {
            port.readingReady=false
            navigationClaimGate={receipt,owner->originalOpenJob=owner;entered.complete(receipt);release.await()}
        }
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket))
        val pending=runBlocking {withTimeout(10_000){entered.await()}}
        check(pending.consumed&&pending.navigationClaim!=null)
        var saved: String?=null
        io {saved=port.storage.read(NativeChatNotificationStore.RECORD)}
        // This is the synchronous production settings invalidation, before its queued IO save.
        main {check(port.opened==0);coordinator.store.invalidatePreferences()}
        release.complete(Unit)
        runBlocking {withTimeout(10_000){originalOpenJob.join()}}
        main {
            check(coordinator.openState=="unavailable"&&port.opened==0&&actual.selectedThread==other.id)
            check(actual.text=="Original owned draft"&&actual.operationRecords.isEmpty())
            check(fixture.submitted.isEmpty()&&fixture.queries.isEmpty())
        }
        io {
            // Revocation does not discard or regenerate the immutable original navigation claim.
            check(originalNavigationReceipt(ticket)==pending)
            check(port.storage.read(NativeChatNotificationStore.RECORD)==saved)
            check(coordinator.store.ticket(ticket,fixture.now)==null)
        }
    }

    @Test fun savedPreferenceDisableDuringClaimPreventsNavigationAndKeepsOriginalClaim() {
        val ticket=ticket();val entered=CompletableDeferred<NativeChatNoticeReceipt>();val release=gate()
        lateinit var originalOpenJob: Job
        main {
            port.readingReady=false
            navigationClaimGate={receipt,owner->originalOpenJob=owner;entered.complete(receipt);release.await()}
        }
        ownedActivityIntent(NativeChatNotificationPlatform.openIntent(ui.activity,ticket))
        val pending=runBlocking {withTimeout(10_000){entered.await()}}
        main {check(port.opened==0);coordinator.store.invalidatePreferences()}
        io {coordinator.store.update {it.copy(enabled=false)}}
        main {coordinator.preferencesSaved()}
        var saved: String?=null
        io {saved=port.storage.read(NativeChatNotificationStore.RECORD)}
        release.complete(Unit)
        runBlocking {withTimeout(10_000){originalOpenJob.join()}}
        main {
            check(coordinator.openState=="unavailable"&&port.opened==0&&actual.selectedThread==other.id)
            check(actual.text=="Original owned draft"&&actual.operationRecords.isEmpty())
            check(fixture.submitted.isEmpty()&&fixture.queries.isEmpty())
        }
        io {
            check(originalNavigationReceipt(ticket)==pending)
            check(coordinator.store.navigationTicket(ticket,fixture.now)==null)
            check(coordinator.store.claimNavigation(ticket,fixture.now){true}==null)
            check(port.storage.read(NativeChatNotificationStore.RECORD)==saved)
        }
    }

}
