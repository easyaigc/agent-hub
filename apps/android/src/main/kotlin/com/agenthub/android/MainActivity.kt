package com.agenthub.android

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class MainActivity : ComponentActivity() {
    companion object { const val P9_NOTIFICATION_COLD_HOOK = NativeChatNotificationColdStart.HOOK_VERSION }
    private data class Ingress(val intent: android.content.Intent,val id: String)
    private data class Retained(val session: MobileSession,val migration: NativeMigrationController,val ingress: java.util.ArrayDeque<Ingress>)
    private var deferredIngress=java.util.ArrayDeque<Ingress>()
    internal lateinit var migration: NativeMigrationController;private set
    private var startupReady=false
    private var started=false
    private val migrationLifecycle=object:NativeMigrationLifecycle {
        override val ready get()=session.migrationReady
        override val canImport get()=session.migrationCanImport
        override fun currentStorage()=session.migrationStorage
        override suspend fun quiesce(operationId: String): NativeMigrationOwnerLease {
            val original=session
            var raw: NativeVoiceRawGate.Frozen?=null
            try {
                raw=original.freezeVoiceForMigration(operationId) // Refuse before stopping or pausing anything.
                readNetworkMonitor.stop();original.discovery?.stop();original.easyWorkDiscovery?.stop()
                return original.quiesceForMigration(operationId,raw)
            }catch(failure: Throwable){
                if(raw!=null){original.releaseVoiceForMigration(raw);original.configureChatNotifications(runtimeContext());if(started)startRuntime()}
                drainMigrationIngress();throw failure
            }
        }
        override suspend fun restore(lease: NativeMigrationOwnerLease,requiresExplicitEnrollment: Boolean) {
            if(session.migrationStorage===lease.restoredStorage){session.resumeSelectionsAfterMigration();return}
            check(session.migrationStorage===lease.storage)
            // IO flag was checked before this boundary. The production coordinator also starts
            // closed and verifies the durable preference on IO before any enrollment or recovery.
            check(!isDestroyed&&!isChangingConfigurations)
            check(lease.requiresExplicitEnrollment==requiresExplicitEnrollment)
            check(session.ownsMigrationLease(lease))
            val original=session
            val prepared=withContext(Dispatchers.IO){
                val value=prepareNativeMigrationStorage(runtimeContext(),lease.storage,lease.inboxWriters?.childKey)
                Triple(value,value.migrationInboxKey(),NativeMigrationSelectionJournal.Kind.entries.associateWith(value::migrationSelectionKeys))
            }
            val (freshStorage,inboxKey,selectionKeys)=prepared
            val fresh=MobileSession(freshStorage)
            fresh.migrationController=migration
            try {fresh.restoreForMigration();original.awaitSelectionReturnsForMigration(lease.operationId);check(!isDestroyed&&!isChangingConfigurations)}catch(failure: Throwable){fresh.abandonMigrationRestore();throw failure}
            val selections=original.takeSelectionsForMigration(lease,selectionKeys)
            fresh.adoptInboxForMigration(original.takeInboxForMigration(lease,inboxKey),lease.operationId)
            fresh.adoptSelectionsForMigration(selections,lease.operationId)
            original.retireForMigration(lease.operationId)
            original.voice?.retireForMigration();original.questionVoice?.retireForMigration()
            session=fresh;lease.restoredStorage=fresh.migrationStorage
            configureRuntime(fresh,fresh.migrationStorage,migrationRestore=true);fresh.resumeSelectionsAfterMigration();renderSession()
            if(started)startRuntime();drainMigrationIngress()
        }
    }
    private fun configureRuntime(value: MobileSession,storage: NativeSessionStorage,migrationRestore: Boolean=false) {
        value.migrationController=migration
        readNetworkMonitor=NativeReadNetworkMonitor(runtimeContext(),value::observeNetworkReadPath)
        value.configureVoice(runtimeContext())
        if(!NativeChatNotificationColdStart.bound(value)){
            value.discovery=CodexHostDiscovery(runtimeContext(),value,storage)
            value.easyWorkDiscovery=EasyWorkDiscovery(runtimeContext(),value,storage)
        }
        value.configureExternalInbox(runtimeContext(),migrationRestore);value.configurePhotoPicker(runtimeContext());value.configureCameraCapture(runtimeContext());value.configureDocumentPicker(runtimeContext());value.configureRecentPhotos(runtimeContext(),NativeChatNotificationColdStart.recentPhotoSource(runtimeContext(),value) ?: NativeRecentPhotos(runtimeContext()))
        value.configureChatNotifications(runtimeContext())
    }
    private fun drainMigrationIngress() {
        if(session.migrationIngressPaused)return
        while(deferredIngress.isNotEmpty()){val original=deferredIngress.removeFirst();val next=externalOperationId
            externalOperationId=original.id;try {receive(original.intent)}finally{externalOperationId=next}}
    }
    private fun startRuntime() {
        if(session.migrationIngressPaused)return
        session.enterForeground();session.bindStoredVoicePreferencesIfReady();session.bindStoredVoiceDiscardIfReady(runtimeContext())
        readNetworkMonitor.start();session.discovery?.start();session.easyWorkDiscovery?.start()
    }
    private var systemBarsDark: Boolean? = null
    private var externalOperationId=java.util.UUID.randomUUID().toString()
    private fun receive(intent: android.content.Intent) {
        if(session.migrationIngressPaused){session.invalidateMigrationLease();if(deferredIngress.none {it.id==externalOperationId})deferredIngress.addLast(Ingress(android.content.Intent(intent),externalOperationId));return}
        if(intent.action==NativeChatNotificationPlatform.ACTION) {session.chatNotifications?.receiveIntent(intent);return}
        if(session.externalInbox?.receive(intent,externalOperationId)!=true)intent.data?.let(session::receiveLink)
    }
    internal lateinit var readNetworkMonitor: NativeReadNetworkMonitor
        private set
    // Activity-owned native state is retained on rotation. Durable repository
    // and process restoration are introduced at A6 with explicit migration.
    internal lateinit var session: MobileSession
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(NativeNaturalLaunch.deferIfRequired(this,savedInstanceState!=null) { finishCreation(savedInstanceState); if(started)startRuntime() })return
        finishCreation(savedInstanceState)
    }
    /** Application framework context stays intact; integrations may use a scoped installation. */
    protected open fun runtimeContext(): android.content.Context=applicationContext
    protected class InitialSession(val session: MobileSession,val needsRestore: Boolean=true)
    /** Debug fixtures can supply an isolated installation before the real lifecycle starts. */
    protected open fun createInitialSession(context: android.content.Context): InitialSession {
        NativeChatNotificationColdStart.noteDefaultStorage(context)
        return InitialSession(MobileSession(NativeSessionStorage(context)))
    }
    private fun finishCreation(savedInstanceState: Bundle?) {
        NativeReadAcceptanceTiming.configure(runtimeContext(),intent)
        @Suppress("DEPRECATION")
        val retainedState=lastCustomNonConfigurationInstance
        val retained=(retainedState as? Retained)?.session ?: retainedState as? MobileSession
        val cold=NativeChatNotificationColdStart.selectForActivity(runtimeContext(),intent,retained,savedInstanceState!=null)
        migration=(retainedState as? Retained)?.migration ?: NativeMigrationController(runtimeContext())
        val initial=if(retained==null&&cold?.session==null)createInitialSession(runtimeContext())else null
        session=retained ?: cold?.session ?: checkNotNull(initial).session
        session.migrationController=migration
        deferredIngress=(retainedState as? Retained)?.ingress ?: deferredIngress
        val storage=session.migrationStorage
        readNetworkMonitor=NativeReadNetworkMonitor(runtimeContext(),session::observeNetworkReadPath)
        if(!session.migrationPaused)session.configureChatNotifications(runtimeContext())
        session.configureVoice(runtimeContext())
        if(!NativeChatNotificationColdStart.bound(session)) {
            session.discovery = CodexHostDiscovery(runtimeContext(), session, storage)
            session.easyWorkDiscovery=EasyWorkDiscovery(runtimeContext(),session,storage)
        }
        externalOperationId=savedInstanceState?.getString("agentHubExternalOperationId")?.takeIf { value -> runCatching { java.util.UUID.fromString(value).toString()==value }.getOrDefault(false) } ?: externalOperationId
        session.configureExternalInbox(runtimeContext());session.configurePhotoPicker(runtimeContext());session.configureCameraCapture(runtimeContext());session.configureDocumentPicker(runtimeContext());session.configureRecentPhotos(runtimeContext(),NativeChatNotificationColdStart.recentPhotoSource(runtimeContext(),session) ?: NativeRecentPhotos(runtimeContext()))
        if(retained==null)intent?.let(::receive)
        if (retained == null&&initial?.needsRestore!=false) session.restore()
        enableEdgeToEdge()
        renderSession();migration.attach(migrationLifecycle)
        startupReady=true;NativeNaturalLaunch.created(this)
    }
    private fun renderSession() {
        setContent {
            NativeMigrationSystemHost(migration)
            LaunchedEffect(session,session.restoring) { session.bindStoredVoicePreferencesIfReady();session.bindStoredVoiceDiscardIfReady(runtimeContext()) }
            AgentHubApp(session) { dark ->
                if (systemBarsDark != dark) {
                    systemBarsDark = dark
                    // API 28 and below also need the matching navigation scrim;
                    // changing the icon appearance alone leaves a light background.
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(Color.WHITE, Color.BLACK) { dark },
                    )
                }
            }
        }
    }
    internal data class AcceptanceSessionState(val session: MobileSession,val intent: android.content.Intent,val operationId: String)
    /** Isolate acceptance storage while exercising this actual Activity/intent path unchanged. */
    internal fun adoptSessionForAcceptance(replacement: MobileSession): AcceptanceSessionState {
        check(packageName=="com.easyai.agenthub.android.acceptance"&&applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        val old=AcceptanceSessionState(session,android.content.Intent(intent),externalOperationId)
        activateAcceptanceSession(replacement)
        return old
    }
    internal fun restoreSessionForAcceptance(previous: AcceptanceSessionState) {
        check(packageName=="com.easyai.agenthub.android.acceptance"&&applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        setIntent(android.content.Intent(previous.intent));externalOperationId=previous.operationId
        activateAcceptanceSession(previous.session)
    }
    private fun activateAcceptanceSession(replacement: MobileSession) {
        check(!replacement.restoring)
        readNetworkMonitor.stop();session.discovery?.stop();session.easyWorkDiscovery?.stop();session.leaveForeground()
        session=replacement;session.migrationController=migration;session.configureVoice(runtimeContext());session.configureExternalInbox(runtimeContext());session.configurePhotoPicker(runtimeContext());session.configureCameraCapture(runtimeContext());session.configureDocumentPicker(runtimeContext());session.configureRecentPhotos(runtimeContext(),NativeChatNotificationColdStart.recentPhotoSource(runtimeContext(),session) ?: NativeRecentPhotos(runtimeContext()));session.configureChatNotifications(runtimeContext())
        readNetworkMonitor=NativeReadNetworkMonitor(runtimeContext(),session::observeNetworkReadPath)
        renderSession();session.enterForeground();session.bindStoredVoicePreferencesIfReady();session.bindStoredVoiceDiscardIfReady(runtimeContext());readNetworkMonitor.start()
    }
    override fun onNewIntent(intent: android.content.Intent) {
        if(NativeNaturalLaunch.blocksIngress(this,startupReady)){super.onNewIntent(intent);NativeNaturalLaunch.rejectedIngress(this);return}
        easyWorkConnectionAuthority(session).invalidate(); session.coverPanelNotices();
        super.onNewIntent(intent);setIntent(intent);externalOperationId=java.util.UUID.randomUUID().toString();receive(intent);NativeChatNotificationColdStart.afterNewIntent(runtimeContext(),intent,session)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("agentHubExternalOperationId",externalOperationId);super.onSaveInstanceState(outState)
    }
    override fun onStart() {super.onStart();started=true;if(startupReady)startRuntime()}
    override fun onStop() {started=false;if(!startupReady){super.onStop();return}; readNetworkMonitor.stop();session.discovery?.stop();session.easyWorkDiscovery?.stop(); session.leaveForeground(); super.onStop() }
    @Deprecated("Native retained state until the repository-backed ViewModel is introduced")
    override fun onRetainCustomNonConfigurationInstance(): Any? = if(NativeNaturalLaunch.canRetain(this,startupReady))Retained(session,migration,deferredIngress)else null
    override fun onDestroy(){NativeNaturalLaunch.destroyed(this);if(startupReady)migration.detach(migrationLifecycle);super.onDestroy()}
}
