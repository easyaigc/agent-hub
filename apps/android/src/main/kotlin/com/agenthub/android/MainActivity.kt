package com.agenthub.android

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {
    private var systemBarsDark: Boolean? = null
    private var externalOperationId=java.util.UUID.randomUUID().toString()
    private fun receive(intent: android.content.Intent) {
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
        val retained = lastCustomNonConfigurationInstance as? MobileSession
        val storage=NativeSessionStorage(applicationContext)
        session = retained ?: MobileSession(storage)
        readNetworkMonitor=NativeReadNetworkMonitor(applicationContext,session::observeNetworkReadPath)
        session.configureVoice(applicationContext)
        session.discovery = CodexHostDiscovery(applicationContext, session, storage)
        session.easyWorkDiscovery=EasyWorkDiscovery(applicationContext,session,storage)
        externalOperationId=savedInstanceState?.getString("agentHubExternalOperationId")?.takeIf { value -> runCatching { java.util.UUID.fromString(value).toString()==value }.getOrDefault(false) } ?: externalOperationId
        session.configureExternalInbox(applicationContext);session.configurePhotoPicker(applicationContext);session.configureCameraCapture(applicationContext);session.configureDocumentPicker(applicationContext);session.configureRecentPhotos(applicationContext)
        intent?.let(::receive)
        if (retained == null) session.restore()
        enableEdgeToEdge()
        renderSession()
    }
    private fun renderSession() {
        setContent {
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
        session=replacement;session.configureVoice(applicationContext);session.configureExternalInbox(applicationContext);session.configurePhotoPicker(applicationContext);session.configureCameraCapture(applicationContext);session.configureDocumentPicker(applicationContext);session.configureRecentPhotos(applicationContext)
        readNetworkMonitor=NativeReadNetworkMonitor(applicationContext,session::observeNetworkReadPath)
        renderSession();session.enterForeground();readNetworkMonitor.start()
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent);setIntent(intent);externalOperationId=java.util.UUID.randomUUID().toString();receive(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("agentHubExternalOperationId",externalOperationId);super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); session.enterForeground();readNetworkMonitor.start(); session.discovery?.start();session.easyWorkDiscovery?.start() }
    override fun onStop() { readNetworkMonitor.stop();session.discovery?.stop();session.easyWorkDiscovery?.stop(); session.leaveForeground(); super.onStop() }
    @Deprecated("Native retained state until the repository-backed ViewModel is introduced")
    override fun onRetainCustomNonConfigurationInstance(): Any = session
}
