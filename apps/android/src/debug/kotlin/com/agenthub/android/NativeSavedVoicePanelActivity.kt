package com.agenthub.android

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Acceptance-only empty window. No MainActivity, Session, discovery, Host or speech startup. */
class NativeSavedVoicePanelActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        check(packageName=="com.easyai.agenthub.android.acceptance")
        super.onCreate(savedInstanceState)
    }
}
