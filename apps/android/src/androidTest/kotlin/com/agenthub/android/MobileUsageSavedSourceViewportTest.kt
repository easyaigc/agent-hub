package com.agenthub.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.agenthub.core.TargetScope
import com.agenthub.core.UsageReadRules
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Default-disabled, offline native auxiliary candidate. The controller stages one saved source
 * per run and schedules every Provider × language × theme × font × orientation × height cell.
 * This never calls a Provider, pairs a device, selects a thread or claims fresh real DEV evidence.
 */
class MobileUsageSavedSourceViewportTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val limit=Timeout.seconds(120)
    // Same Handler/latch public boundary as Q04NoModelQ02StartupTest.onMainBounded.
    // Timeout stops waiting; an already executing Main callback may still complete. Never retry it.
    private fun <T> onMainBounded(maximum:Long=2000,action: ()->T): T {
        check(Looper.myLooper()!==Looper.getMainLooper())
        val main=Handler(Looper.getMainLooper());val done=CountDownLatch(1)
        val open=AtomicBoolean(true);val result=AtomicReference<Result<T>>()
        val cutoff=SystemClock.elapsedRealtime()+maximum
        val task=Runnable {
            if(!open.get() || SystemClock.elapsedRealtime()>=cutoff)return@Runnable
            try {result.set(runCatching(action))}finally {done.countDown()}
        }
        check(main.post(task)) {"Main post rejected"}
        val completed=try {done.await(maximum,TimeUnit.MILLISECONDS)}finally {open.set(false);main.removeCallbacks(task)}
        check(completed) {"Main callback outcome unknown; preserve this run and do not retry"}
        return checkNotNull(result.get()).getOrThrow()
    }
    @Test fun savedSourceTwoEntriesLoadingDenialAndRecovery() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Root owns the native window",args.getString("f73SavedSourceViewport")=="1")
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance")
        require(i.context.packageName==context.packageName+".test")
        require(args.getString("f73Foreground")=="external-public-start")
        val language=checkNotNull(args.getString("f73Language"));require(language in setOf("zh-Hans","en"))
        val theme=checkNotNull(args.getString("f73Theme"));require(theme in setOf("light","dark"))
        val font=checkNotNull(args.getString("f73FontScale")).toFloat();require(font in setOf(1f,1.3f))
        val orientation=checkNotNull(args.getString("f73Orientation"));require(orientation in setOf("portrait","landscape"))
        require(context.resources.configuration.orientation==if(orientation=="portrait")Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE)
        val height=checkNotNull(args.getString("f73Height"));require(height in setOf("regular","short"))
        val config=context.resources.configuration
        require((minOf(config.screenWidthDp,config.screenHeightDp)<=320)==(height=="short"))
        val run=checkNotNull(args.getString("f73SavedSourceRun"));require(UUID.fromString(run).toString()==run)
        val base=File(context.filesDir,"acceptance/f73-saved-source").canonicalFile
        val folder=File(base,run);require(folder.canonicalFile==folder.absoluteFile&&folder.parentFile==base)
        val source=File(folder,"desktop-usage-source.json")
        val stat=android.system.Os.lstat(source.path)
        require(android.system.OsConstants.S_ISREG(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_nlink==1L&&stat.st_mode and 0x1ff==0x180)
        require(source.canonicalFile==source.absoluteFile&&stat.st_size in 1..262144)
        val bytes=source.readBytes()
        val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        require(sha==args.getString("f73SavedSourceSha256"))
        // Exclusive evidence directory is a local test intent. Never reuse old Native/once/case.
        val output=File(folder,"viewport-evidence");require(output.mkdir())
        android.system.Os.chmod(output.path,0x1c0)
        val body=JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("body")
        val t=body.getJSONObject("target");val agent=t.getString("agentId");require(agent in setOf("codex","easywork"))
        require(agent==args.getString("f73Provider"))
        val client=MobileClient("saved-source","Saved source",TargetScope(agent,null,null,t.getString("hostId"),t.getString("workspaceId"),t.getString("runtimeId")))
        // Historical clock is injected only at the read-boundary fixture; never rewrite source times.
        var now=Instant.parse(body.getJSONObject("snapshot").getString("sampledAt")).toEpochMilli()
        val expected=JSONObject(UsageReadRules.projectResponse(body.toString(),t.toString(),now))
        require(expected.getString("status")=="ok")
        var reads=0;var denied=false;val release=CompletableDeferred<Unit>()
        val store=MobileUsageStore(clock={now}) {
            reads++;if(reads==1)release.await()
            if(denied)throw CodexCommandError("offline saved-source regression",401)
            body.toString()
        }
        var selected by mutableStateOf(false)
        val ownActivity=AtomicReference<ComponentActivity>()
        fun privateText(name:String,value:String) {
            val file=File(output,name);require(file.createNewFile());android.system.Os.chmod(file.path,0x180);file.writeText(value)
        }
        fun save(name:String) {
            lateinit var value:JSONObject;ui.runOnIdle {value=JSONObject(store.value(client).toString())}
            privateText("$name-private-projection.json",value.toString())
            val dialog=ui.onNode(isDialog())
            privateText("$name-semantics.txt",dialog.printToString())
            val bitmap=dialog.captureToImage().asAndroidBitmap()
            val file=File(output,"$name.png");require(file.createNewFile());android.system.Os.chmod(file.path,0x180)
            try {file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
        }
        var primary:Throwable?=null
        try {
            // Root issues the existing public am start -W path ONLY after this exact request.
            // No ActivityScenario/startActivitySync or permission delegation in this candidate.
            onMainBounded {
                require(Stage.values().filter {it!=Stage.DESTROYED}.all {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).isEmpty()
                }) {"Another Activity is present; no takeover"}
            }
            privateText("foreground-request.json",JSONObject().put("run",run).put("package",context.packageName)
                .put("component","androidx.activity.ComponentActivity").put("action","com.agenthub.android.F73_SAVED_SOURCE")
                .put("requestedAtElapsedMs",SystemClock.elapsedRealtime()).toString())
            val cutoff=SystemClock.elapsedRealtime()+20000
            while(ownActivity.get()==null && SystemClock.elapsedRealtime()<cutoff) {
                onMainBounded(minOf(2000,cutoff-SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                    val resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    val matched=resumed.filter {it.javaClass==ComponentActivity::class.java && it.packageName==context.packageName &&
                        it.intent?.action=="com.agenthub.android.F73_SAVED_SOURCE" && it.intent?.getStringExtra("f73SavedSourceRun")==run}
                    require(matched.size<=1) {"Ambiguous foreground owner"}
                    matched.singleOrNull()?.let {ownActivity.set(it as ComponentActivity)}
                }
                if(ownActivity.get()==null)SystemClock.sleep(50)
            }
            require(SystemClock.elapsedRealtime()<cutoff) {"Foreground deadline passed; preserve original request"}
            val activity=checkNotNull(ownActivity.get()) {"Exact RESUMED Activity not observed within 20s; do not relaunch"}
            onMainBounded {
                require(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)==Stage.RESUMED)
                require(!activity.isFinishing&&!activity.isDestroyed)
            }
            privateText("foreground-observed.json",JSONObject().put("run",run).put("pid",android.os.Process.myPid())
                .put("component",activity.javaClass.name).put("resumed",true).put("observedAtElapsedMs",SystemClock.elapsedRealtime()).toString())
            onMainBounded { activity.setContent {
                ProductTextProvider(language) { MaterialTheme(colorScheme=if(theme=="dark")darkColorScheme()else lightColorScheme()) {
                    val density=LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
                        Column(Modifier.fillMaxSize()) {
                            if(!selected)TextButton(modifier=Modifier.testTag("saved-source-selection"),onClick={selected=true}) {ClientUsageSummaryContent(store,client,true)}
                            if(selected)TextButton(modifier=Modifier.testTag("saved-source-selector-open"),onClick={selected=false}) {Text("Saved source")}
                            UsageFooterContent(store,client,true,true)
                        }
                    }
                } }
            } }
            ui.waitUntil(5000){reads==1}
            ui.onNodeWithTag("saved-source-selection").performClick()
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.onNodeWithText(if(language=="en")"Refresh usage"else"刷新额度").assertIsNotEnabled()
            ui.onNodeWithTag("usage-refresh-loading").assertIsDisplayed()
            ui.runOnIdle {assertEquals(1,reads);release.complete(Unit)}
            ui.waitUntil(5000){store.value(client).optString("status")=="ok"&&!store.refreshing(client)}
            ui.onNodeWithTag("usage-refresh-loading").assertDoesNotExist()
            ui.onNodeWithText(if(language=="en")"Refresh usage"else"刷新额度").assertIsEnabled()
            ui.runOnIdle {assertEquals(easyCanonical(expected),easyCanonical(store.value(client)))}
            val text=NativeL10n(context,language)
            ui.onNodeWithText(text.tr("更新时间")+"："+expected.getString("sampledAt")).performScrollTo().assertIsDisplayed()
            val sourceInfo=expected.getJSONObject("source")
            for((key,title) in listOf("scopeLabel" to "额度来源","planLabel" to "套餐"))if(!sourceInfo.isNull(key))
                ui.onNodeWithText(text.tr(title)+"："+sourceInfo.getString(key)).performScrollTo().assertIsDisplayed()
            val metrics=expected.getJSONArray("metrics")
            for(index in 0 until metrics.length()) {
                val metric=metrics.getJSONObject(index)
                ui.onNodeWithText(usageMetricTitle(metric)).performScrollTo().assertIsDisplayed()
                ui.onNodeWithText(usageMetricText(metric){text.tr(it)}).performScrollTo().assertIsDisplayed()
            }
            save("initial")
            val refresh=if(language=="en")"Refresh usage"else"刷新额度"
            val done=if(language=="en")"Done"else"完成"
            // Same snapshot in summary, footer and dialog; only the selected entry polls now.
            ui.onNodeWithText(done).performClick()
            if(expected.has("balance"))ui.onNodeWithText(expected.getString("balance"),substring=true).assertIsDisplayed()
            ui.onNodeWithTag("saved-source-selector-open").performClick()
            if(expected.has("balance"))ui.onNodeWithTag("client-usage-summary-${client.id}",useUnmergedTree=true)
                .assert(hasAnyDescendant(hasText(expected.getString("balance")+" "+expected.optString("balanceUnit"),substring=true)))
            ui.runOnIdle {assertEquals(easyCanonical(expected),easyCanonical(store.value(client)));assertEquals(1,reads)}
            ui.onNodeWithTag("saved-source-selection").performClick()
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.onNodeWithText(refresh).performClick()
            ui.runOnIdle {assertEquals("Manual refresh must not bypass 5 seconds",1,reads);now+=5000;denied=true}
            ui.onNodeWithText(refresh).performClick()
            ui.waitUntil(5000){store.value(client).optString("reason")=="http-unauthenticated"}
            ui.onNodeWithText(if(language=="en")"Usage read authentication failed (HTTP 401)"else"电脑额度读取认证失败（HTTP 401）").performScrollTo().assertIsDisplayed()
            ui.runOnIdle {assertFalse(store.value(client).has("metrics"));assertFalse(store.value(client).has("balance"));now+=5000;denied=false}
            save("denied")
            ui.onNodeWithText(refresh).performClick()
            ui.waitUntil(5000){store.value(client).optString("status")=="ok"}
            ui.runOnIdle {assertEquals(expected.getString("snapshotId"),store.value(client).getString("snapshotId"));assertEquals(expected.getBoolean("partial"),store.value(client).getBoolean("partial"))}
            save("recovered")
            if(expected.optBoolean("partial"))ui.onNodeWithText(if(language=="en")"Some usage is unavailable"else"部分额度暂不可用").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText(done).performClick()
            privateText("result.json",JSONObject().put("kind","offline-saved-source-native-auxiliary")
                .put("sourceSha256",sha).put("agent",agent).put("language",language).put("theme",theme).put("fontScale",font)
                .put("orientation",orientation).put("height",height).put("reads",reads).put("realProviderReads",0)
                .put("freshSource",false).put("realDevGatePassed",false).toString())
        } catch(failure:Throwable) {
            primary=failure
            privateText("failure-private.txt",failure.stackTraceToString())
            throw failure
        } finally {
            release.complete(Unit)
            var finishRequested=false;var destroyed=false
            val close=runCatching {ownActivity.get()?.let {activity ->
                onMainBounded {
                    require(activity.intent?.getStringExtra("f73SavedSourceRun")==run)
                    if(!activity.isDestroyed) {activity.setContent {};activity.finish();finishRequested=true}
                }
                val cutoff=SystemClock.elapsedRealtime()+2000
                while(!destroyed && SystemClock.elapsedRealtime()<cutoff) {
                    destroyed=onMainBounded(minOf(500,cutoff-SystemClock.elapsedRealtime()).coerceAtLeast(1)) {activity.isDestroyed}
                    if(!destroyed)SystemClock.sleep(25)
                }
                check(destroyed) {"Owned Activity destruction unconfirmed; retain this run and do not advance"}
            }}
            privateText("cleanup.json",JSONObject().put("run",run).put("finishRequested",finishRequested)
                .put("cleanupOutcomeUnknown",close.isFailure).put("destroyedVerified",destroyed).toString())
            close.exceptionOrNull()?.let {cleanup -> if(primary!=null)primary?.addSuppressed(cleanup)else throw cleanup}
        }
    }
}
