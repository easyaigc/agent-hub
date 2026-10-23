package com.agenthub.android

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource

/** Opt-in shell activation of this exact test target before ActivityScenario starts.
 * Some physical devices refuse an instrumentation launch once the previous Activity closes. */
internal class NativeTestForeground: ExternalResource() {
    override fun before() {
        if(InstrumentationRegistry.getArguments().getString("nativeForeground")!="1") return
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext.packageName
        val descriptor=instrumentation.uiAutomation.executeShellCommand("am start -W -n $target/com.agenthub.android.MainActivity")
        val result=ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertTrue("Activate only the selected native acceptance target",result.contains("Status: ok"))
    }
}
