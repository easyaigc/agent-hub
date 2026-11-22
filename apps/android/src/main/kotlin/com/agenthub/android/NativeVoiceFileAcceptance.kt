package com.agenthub.android

import android.content.Context
import android.content.pm.ApplicationInfo

/** Local file input / delivery fault only. Null in product; never replaces transport or ASR. */
internal interface NativeVoiceFileAcceptance {
    suspend fun capture(original: VoiceDraft,store: VoiceRecordingStore): PendingVoiceRecording
    fun completed(recording: PendingVoiceRecording,queryOnly: Boolean)
}

internal fun requireNativeVoiceFileAcceptance(context: Context) {
    val app=context.applicationContext
    require(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.startsWith("com.easyai.agenthub.android.") &&
        BuildConfig.APPLICATION_ID.endsWith(".acceptance") && context.packageName==BuildConfig.APPLICATION_ID &&
        app.packageName==BuildConfig.APPLICATION_ID && app.applicationInfo.uid==android.os.Process.myUid() &&
        (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)!=0) { "Independent debug acceptance installation required" }
}
