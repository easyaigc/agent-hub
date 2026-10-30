package com.agenthub.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Refuses an existing Activity/P9 owner before preparing the debug-only private installation. */
internal class NativeMigrationPrivateActivityRule(private val prepare: suspend ()->Triple<String,MobileSession,Context>): TestRule {
    override fun apply(base: Statement,description: Description)=object: Statement() {
        override fun evaluate() {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val app=instrumentation.targetContext
            check(BuildConfig.DEBUG&&app.packageName=="com.easyai.agenthub.android.acceptance")
            instrumentation.runOnMainSync {
                val activities=Stage.values().filter {it!=Stage.DESTROYED}.flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}
                check(activities.none {it is MainActivity}) {"Existing Activity retained; do not replace its owner for this selector"}
                NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(app)
            }
            runBlocking {withContext(Dispatchers.IO){
                check(!NativeNaturalEntryFence.hasMaterial(app.noBackupFilesDir)) {"Existing P9 natural entry retained"}
                check(Files.notExists(File(app.noBackupFilesDir,NativeChatNotificationColdRouterFiles.NAMESPACE).toPath(),LinkOption.NOFOLLOW_LINKS)) {"Existing cold router material retained"}
            }}
            val (run,session,context)=runBlocking {prepare()}
            NativeMigrationInboxOwnerActivity.prepare(run,context,session)
            // The outer rule releases only after the inner Activity rule has closed the Activity.
            try {base.evaluate()}finally {NativeMigrationInboxOwnerActivity.release(run)}
        }
    }
}
