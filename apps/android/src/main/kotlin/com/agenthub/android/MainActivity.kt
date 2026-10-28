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

class MainActivity : ComponentActivity() {
    companion object { const val P9_NOTIFICATION_COLD_HOOK = NativeChatNotificationColdStart.HOOK_VERSION }
    private data class Ingress(val intent: android.content.Intent,val id: String)
    private data class Retained(val session: MobileSession,val migration: NativeMigrationController,val ingress: java.util.ArrayDeque<Ingress>)
    private var deferredIngress=java.util.ArrayDeque<Ingress>()
    internal lateinit var migration: NativeMigrationController;private set
    private var started=false
    private val migrationLifecycle=object:NativeMigrationLifecycle {
        override val ready get()=session.migrationReady
        override val canImport get()=session.migrationCanImport
        override fun currentStorage()=session.migrationStorage
        override suspend fun quiesce(operationId: String): NativeMigrationOwnerLease {
            val original=session
            readNetworkMonitor.stop();original.discovery?.stop();original.easyWorkDiscovery?.stop()
            try{return original.quiesceForMigration(operationId)}
            catch(failure: Throwable){original.configureChatNotifications(applicationContext);if(started)startRuntime();drainMigrationIngress();throw failure}
        }
        override suspend fun restore(lease: NativeMigrationOwnerLease,requiresExplicitEnrollment: Boolean) {
            if(session.migrationStorage===lease.restoredStorage)return
            check(session.migrationStorage===lease.storage)
            // IO flag was checked before this boundary. The production coordinator also starts
            // closed and verifies the durable preference on IO before any enrollment or recovery.
            check(!isDestroyed&&!isChangingConfigurations)
            check(lease.requiresExplicitEnrollment==requiresExplicitEnrollment)
            check(session.ownsMigrationLease(lease))
            val original=session
            val freshStorage=withContext(Dispatchers.IO){prepareNativeMigrationStorage(applicationContext,lease.storage)}
            val fresh=MobileSession(freshStorage)
            fresh.migrationController=migration
            try {fresh.restoreForMigration();check(!isDestroyed&&!isChangingConfigurations)}catch(failure: Throwable){fresh.abandonMigrationRestore();throw failure}
            original.retireForMigration(lease.operationId)
            original.voice?.retireForMigration();original.questionVoice?.retireForMigration()
            session=fresh;lease.restoredStorage=fresh.migrationStorage
            configureRuntime(fresh,fresh.migrationStorage);renderSession()
            if(started)startRuntime();drainMigrationIngress()
        }
    }
    private fun configureRuntime(value: MobileSession,storage: NativeSessionStorage) {
        value.migrationController=migration
        readNetworkMonitor=NativeReadNetworkMonitor(applicationContext,value::observeNetworkReadPath)
        value.configureVoice(applicationContext)
        if(!NativeChatNotificationColdStart.bound(value)){
            value.discovery=CodexHostDiscovery(applicationContext,value,storage)
            value.easyWorkDiscovery=EasyWorkDiscovery(applicationContext,value,storage)
        }
        value.configureExternalInbox(applicationContext);value.configurePhotoPicker(applicationContext);value.configureCameraCapture(applicationContext);value.configureDocumentPicker(applicationContext);value.configureRecentPhotos(applicationContext,NativeChatNotificationColdStart.recentPhotoSource(applicationContext,value) ?: NativeRecentPhotos(applicationContext))
        value.configureChatNotifications(applicationContext)
    }
    private fun drainMigrationIngress() {
        if(session.migrationPaused)return
        while(deferredIngress.isNotEmpty()){val original=deferredIngress.removeFirst();val next=externalOperationId
            externalOperationId=original.id;try {receive(original.intent)}finally{externalOperationId=next}}
    }
    private fun startRuntime() {
        if(session.migrationPaused)return
        session.enterForeground();session.bindStoredVoicePreferencesIfReady();session.bindStoredVoiceDiscardIfReady(applicationContext)
        readNetworkMonitor.start();session.discovery?.start();session.easyWorkDiscovery?.start()
    }
    private var systemBarsDark: Boolean? = null
    private var externalOperationId=java.util.UUID.randomUUID().toString()
    private fun receive(intent: android.content.Intent) {
        if(session.migrationPaused){session.invalidateMigrationLease();if(deferredIngress.none {it.id==externalOperationId})deferredIngress.addLast(Ingress(android.content.Intent(intent),externalOperationId));return}
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
        NativeReadAcceptanceTiming.configure(applicationContext,intent)
        @Suppress("DEPRECATION")
        val retainedState=lastCustomNonConfigurationInstance
        val retained=(retainedState as? Retained)?.session ?: retainedState as? MobileSession
        val cold=NativeChatNotificationColdStart.selectForActivity(applicationContext,intent,retained,savedInstanceState!=null)
        migration=(retainedState as? Retained)?.migration ?: NativeMigrationController(applicationContext)
        session=retained ?: cold?.session ?: run {NativeChatNotificationColdStart.noteDefaultStorage(applicationContext);MobileSession(NativeSessionStorage(applicationContext))}
        session.migrationController=migration
        deferredIngress=(retainedState as? Retained)?.ingress ?: deferredIngress
        val storage=session.migrationStorage
        readNetworkMonitor=NativeReadNetworkMonitor(applicationContext,session::observeNetworkReadPath)
        if(!session.migrationPaused)session.configureChatNotifications(applicationContext)
        session.configureVoice(applicationContext)
        if(!NativeChatNotificationColdStart.bound(session)) {
            session.discovery = CodexHostDiscovery(applicationContext, session, storage)
            session.easyWorkDiscovery=EasyWorkDiscovery(applicationContext,session,storage)
        }
        externalOperationId=savedInstanceState?.getString("agentHubExternalOperationId")?.takeIf { value -> runCatching { java.util.UUID.fromString(value).toString()==value }.getOrDefault(false) } ?: externalOperationId
        session.configureExternalInbox(applicationContext);session.configurePhotoPicker(applicationContext);session.configureCameraCapture(applicationContext);session.configureDocumentPicker(applicationContext);session.configureRecentPhotos(applicationContext,NativeChatNotificationColdStart.recentPhotoSource(applicationContext,session) ?: NativeRecentPhotos(applicationContext))
        if(retained==null)intent?.let(::receive)
        if (retained == null) session.restore()
        enableEdgeToEdge()
        renderSession();migration.attach(migrationLifecycle)
    }
    private fun renderSession() {
        setContent {
            NativeMigrationSystemHost(migration)
            LaunchedEffect(session,session.restoring) { session.bindStoredVoicePreferencesIfReady();session.bindStoredVoiceDiscardIfReady(applicationContext) }
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
        session=replacement;session.migrationController=migration;session.configureVoice(applicationContext);session.configureExternalInbox(applicationContext);session.configurePhotoPicker(applicationContext);session.configureCameraCapture(applicationContext);session.configureDocumentPicker(applicationContext);session.configureRecentPhotos(applicationContext,NativeChatNotificationColdStart.recentPhotoSource(applicationContext,session) ?: NativeRecentPhotos(applicationContext));session.configureChatNotifications(applicationContext)
        readNetworkMonitor=NativeReadNetworkMonitor(applicationContext,session::observeNetworkReadPath)
        renderSession();session.enterForeground();session.bindStoredVoicePreferencesIfReady();session.bindStoredVoiceDiscardIfReady(applicationContext);readNetworkMonitor.start()
    }
    override fun onNewIntent(intent: android.content.Intent) { easyWorkConnectionAuthority(session).invalidate(); session.coverPanelNotices();
        super.onNewIntent(intent);setIntent(intent);externalOperationId=java.util.UUID.randomUUID().toString();receive(intent);NativeChatNotificationColdStart.afterNewIntent(applicationContext,intent,session)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("agentHubExternalOperationId",externalOperationId);super.onSaveInstanceState(outState)
    }
    override fun onStart() {super.onStart();started=true;startRuntime()}
    override fun onStop() {started=false; readNetworkMonitor.stop();session.discovery?.stop();session.easyWorkDiscovery?.stop(); session.leaveForeground(); super.onStop() }
    @Deprecated("Native retained state until the repository-backed ViewModel is introduced")
    override fun onRetainCustomNonConfigurationInstance(): Any = Retained(session,migration,deferredIngress)
    override fun onDestroy(){migration.detach(migrationLifecycle);super.onDestroy()}
}
