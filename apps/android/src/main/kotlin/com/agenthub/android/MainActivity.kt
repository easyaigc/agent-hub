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
    // Activity-owned native state is retained on rotation. Durable repository
    // and process restoration are introduced at A6 with explicit migration.
    internal lateinit var session: MobileSession
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val retained = lastCustomNonConfigurationInstance as? MobileSession
        val storage=NativeSessionStorage(applicationContext)
        session = retained ?: MobileSession(storage)
        session.configureVoice(applicationContext)
        session.discovery = CodexHostDiscovery(applicationContext, session, storage)
        session.easyWorkDiscovery=EasyWorkDiscovery(applicationContext,session,storage)
        intent?.data?.let(session::receiveLink)
        if (retained == null) session.restore()
        enableEdgeToEdge()
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
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent);setIntent(intent);intent.data?.let(session::receiveLink) }
    override fun onStart() { super.onStart(); session.enterForeground(); session.discovery?.start();session.easyWorkDiscovery?.start() }
    override fun onStop() { session.discovery?.stop();session.easyWorkDiscovery?.stop(); session.leaveForeground(); super.onStop() }
    @Deprecated("Native retained state until the repository-backed ViewModel is introduced")
    override fun onRetainCustomNonConfigurationInstance(): Any = session
}
