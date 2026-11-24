package com.agenthub.android

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Uses only emulator-local synthetic server and task-specific acceptance storage. */
class Life01FinalLifecycleTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private suspend fun waitFor(predicate: ()->Boolean)=withTimeout(15_000) { while(!withContext(Dispatchers.Main) { predicate() }) delay(25) }
    @Test fun bundledPreviewResourcesAreReadableInBothLocales() {
        val ctx=instrumentation.targetContext
        for(pair in listOf("product_a84478cfd2a8b49769b3d688" to "Preview paused","product_018098ff54e63100fef53765" to "Continue preview","product_9885597cc98254af7e818e36" to "Retry closing","product_704c1f90a8e9580ea3a5dc59" to "The previous preview status is unconfirmed")) {
            val id=ctx.resources.getIdentifier(pair.first,"string",ctx.packageName);assertNotEquals(0,id)
            val en=android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) }
            assertEquals(pair.second,ctx.createConfigurationContext(en).getString(id))
            val zh=android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
            assertTrue(ctx.createConfigurationContext(zh).getString(id).isNotBlank())
        }
    }
    @Test fun actualHomeShortLongAndTwentyReturnsWithNetworkToggle()=runBlocking {
        ChatManagementFixture().use { fixture ->
            fixture.writableHistory=true
            val context=instrumentation.targetContext
            val owned=withContext(Dispatchers.Main) { MobileSession(NativeSessionStorage(context,"life01-final-"+UUID.randomUUID()),NativeHostReads { "" }).also { it.restore() } }
            waitFor { !owned.restoring }
            val scenario=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java))
            suspend fun leave() {shell("input keyevent KEYCODE_HOME");delay(250)}
            suspend fun resume() { val result=shell("am start -n ${context.packageName}/com.agenthub.android.MainActivity");assertTrue(result.contains("Starting:"));waitFor { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().any { it.session===owned } && owned.canStop && !owned.loading && !owned.historyLoading } }
            try {
                scenario.onActivity { it.adoptSessionForAcceptance(owned) }
                withContext(Dispatchers.Main) { owned.connect("local fixture",fixture.server.endpoint,"");owned.selectThread(owned.threads.first { it.id==ChatManagementFixture.first });owned.text="retained lifecycle draft" }
                waitFor { owned.canStop && !owned.loading && !owned.historyLoading }
                leave();resume()
                leave();delay(31_000);resume()
                assertTrue(withContext(Dispatchers.Main) { owned.selectedClient.online && owned.text=="retained lifecycle draft" })
                val start=fixture.server.requests.size
                repeat(20) { n ->
                    leave()
                    if(n==5) { shell("svc wifi disable");shell("svc data disable") }
                    if(n==6) { shell("svc wifi enable");shell("svc data enable") }
                    resume()
                    assertTrue(withContext(Dispatchers.Main) { owned.selectedClient.online && owned.text=="retained lifecycle draft" })
                }
                val requests=fixture.server.requests.drop(start)
                assertTrue("twenty returns have bounded local reads: ${requests.size}",requests.size<240)
                assertTrue(requests.none { it.method=="POST" })
            } finally {shell("svc wifi enable");shell("svc data enable");scenario.close()}
        }
    }
}
