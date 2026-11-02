package com.agenthub.android

import android.content.Context
import android.content.Intent

/** No graph admission, state reader or fixture entry exists in release builds. */
internal object NativeChatNotificationGraphColdStart {
    fun hasMaterial(context: Context): Boolean = false
    fun selectForActivity(context: Context, intent: Intent, retained: MobileSession?, restored: Boolean): NativeChatNotificationColdStart.Binding =
        error("Debug acceptance graph is unavailable in release builds")
}
