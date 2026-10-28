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

/** Real owned MainActivity/Session/storage and shipping coordinator; synthetic reading, Host and OS ports only.
 * Base Activity handoff is explicit here until the parent applies the shared ingress patch. No native notify/permission/Host/model call.
 */
class NativeChatNotificationCoordinatorTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule = NativeTestForeground()
    @get:Rule(order=2) val ui = createAndroidComposeRule<MainActivity>()
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
    private var locateEntered:CompletableDeferred<Unit>?=null
    private var feedGate:CompletableDeferred<Unit>?=null
    private var feedEntered:CompletableDeferred<Unit>?=null
    private val reader=object:HostReads {
        override suspend fun workspace(client:MobileClient,projectCursor:String?,threadCursor:String?):MobileWorkspace {
            locateEntered?.complete(Unit);locateGate?.await();return workspace
        }
        override suspend fun history(client:MobileClient,threadId:String,cursor:String?)=HostReadCodec.history(JSONObject().put("hostId",target.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray()).toString())
    }
    private fun main(action:suspend()->Unit)=runBlocking {withContext(Dispatchers.Main.immediate){action()}}
    private fun io(action:suspend()->Unit)=runBlocking {withContext(Dispatchers.IO){action()}}
    private inner class Port:NativeChatNotificationSessionPort {
        override val storage get()=owned.storage
        var ready=true;var foreground=true;var visible=true;var navigation=0
        val received=mutableListOf<String>();val active=mutableListOf<Set<String>>();var mayAlert=true;var opened=0
        override val reading=object:NativeChatNotificationReading {
            override suspend fun receive(event:NativeChatNotificationEvent):NativeChatNoticeReadingResult {
                check(Looper.myLooper()!=Looper.getMainLooper());received.add(event.id);return NativeChatNoticeReadingResult(true,3)
            }
            override fun mayPresent(event:NativeChatNotificationEvent):Boolean=mayAlert
        }
        override fun state()=NativeChatNotificationSessionState(ready&&!actual.restoring&&!actual.localRecoveryRequired,foreground,"${actual.targetKey}:$navigation",visible)
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
        override val sounds=NativeChatNotificationSoundLibrary(ui.activity,owned.storage)
        override fun languageChanged(selection:String){check(Looper.myLooper()==Looper.getMainLooper())}
        var allowed by mutableStateOf(true)
        val posts=mutableStateListOf<NativeChatNoticeReceipt>();val intents=mutableListOf<Intent>();var cancellations=0
        var prepareGate:CompletableDeferred<Unit>?=null;var prepareEntered:CompletableDeferred<Unit>?=null;var prepareReturned:CompletableDeferred<Boolean>?=null;var afterPrepare:(()->Unit)?=null
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
        ui.runOnIdle {originalIntent=Intent(ui.activity.intent);owned=OwnedControlAcceptance(ui.activity,"chat-notification-coordinator");owned.session.resetAcceptance()}
        fixture=runBlocking{withContext(Dispatchers.IO){RecoveryNativeFixture(owned.storage)}}
        main {
            val commands=object:CodexCommands by NativeCodexCommands({error("No real Host commands in this fixture")}) {override suspend fun models(client:MobileClient)=emptyList<CodexModel>()}
            actual=MobileSession(owned.storage,reader,commands);actual.resetAcceptance(reader,commands);actual.connect("Fixture","https://fixture.example.invalid",token);actual.selectThread(other);actual.text="Original owned draft"
            port=Port();system=SystemPort();tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
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
            coordinator=NativeChatNotificationCoordinator(tasks,port,system,transport,NativeChatNotificationThreadLocator{reader},{fixture.now},60_000,ui.activity.packageName)
            coordinator.updateComputers(actual.clients);pageOwner=PageOwner().also{it.registry.currentState=Lifecycle.State.RESUMED}
        }
    }
    @After fun preserve() {
        if(::coordinator.isInitialized)main{coordinator.dispose();tasks.cancel();actual.leaveForeground();owned.session.leaveForeground();ui.activity.setIntent(originalIntent);owned.verifyDefault();check(actual.operationRecords.isEmpty())}
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
    @Test fun foregroundPresentationUsesOpaqueIntentAndP7UnreadCount(){fixture.events.add(fixture.event());start();ui.waitUntil(10_000){system.posts.size==1};val intent=system.intents.single();check(intent.extras!!.keySet()==setOf(NativeChatNotificationPlatform.TICKET)&&intent.getStringExtra(NativeChatNotificationPlatform.TICKET)==system.posts.single().ticket)}
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

}
