package com.agenthub.android

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Actual Compose saved-state and Android registry routing; injected permission results only.
 * Both providers stop before microphone/audio-focus/file capture. No OS permission dialog, Host,
 * recognizer, model or saved-recording transcription is invoked by this fixture. */
class NativeVoicePermissionLifecycleTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val controllers=mutableListOf<VoiceInputController>()
    private var nextNamespace=0
    private var applies=0
    private var systemStarts=0
    private var readiness=0
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private class Registry(private val immediate: Boolean?=null): ActivityResultRegistry() {
        val launches=mutableListOf<Int>()
        override fun <I,O> onLaunch(requestCode: Int,contract: ActivityResultContract<I,O>,input: I,options: ActivityOptionsCompat?) {
            assertEquals(Manifest.permission.RECORD_AUDIO,input);launches+=requestCode
            immediate?.let { dispatchResult(requestCode,it) }
        }
    }
    private fun controller(): VoiceInputController {
        val context=object: ContextWrapper(ui.activity) {
            override fun checkSelfPermission(permission: String)=if(permission==Manifest.permission.RECORD_AUDIO)PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        val commands=object: CodexCommands by NativeCodexCommands({error("No actual Host commands")}) {
            override suspend fun voiceReadiness(client: MobileClient): VoiceReadiness {
                readiness++;return VoiceReadiness(client.id,true,null,4096,1000)
            }
        }
        return VoiceInputController(context,VoiceRecordingStore(ui.activity,owned.storage.child(ui.activity,"permission-${nextNamespace++}"))) {commands}
            .also { value ->
                value.systemFactory={object: NativeSystemVoice {
                    override fun start(listener: NativeSystemVoice.Listener) { systemStarts++;error("Permission fixture must stop before system capture") }
                    override fun finish() {} ;override fun cancel() {}
                }}
                controllers+=value
            }
    }
    private fun draft(id: String): VoiceDraft {
        val scope=TargetScope("codex",null,null,"host-$id","workspace-$id","runtime-$id")
        return VoiceDraft(MobileClient(scope.hostId,"Synthetic $id",scope),AISharingScope(scope,"codex-dictation"),"draft-$id","revision-$id","voice-$id","thread-$id","Original $id")
    }
    private fun start(value: VoiceInputController,id: String,provider: VoiceInputController.Provider,allowed: ()->Boolean={true}) {
        main { value.start(draft(id),allowed,{_,_->applies++;true},restore=false,provider=provider);yield() }
        assertNotNull(value.permissionRequest)
    }
    private fun content(registry: Registry,current: ()->VoiceInputController?): StateRestorationTester {
        // MainActivity owns its initial content. Replace it through the same native setContent
        // route used by the existing owned-control tests, while delegating idle/state machinery.
        val contentRule=object: ComposeContentTestRule by ui {
            override fun setContent(composable: @Composable ()->Unit) { main { ui.activity.setContent { composable() } } }
        }
        val tester=StateRestorationTester(contentRule)
        val owner=object: ActivityResultRegistryOwner { override val activityResultRegistry=registry }
        tester.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { Box(Modifier.fillMaxSize()) { VoicePermission(current()) } } }
        ui.waitForIdle();return tester
    }
    @Before fun begin() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        main { owned=OwnedControlAcceptance(ui.activity,"voice-permission-lifecycle");owned.session.resetAcceptance() }
    }
    @After fun end() {
        main { controllers.forEach { it.cancel() };yield();owned.session.leaveForeground();owned.verifyDefault() }
        assertEquals(0,systemStarts);assertEquals(0,applies)
    }
    @Test fun restoringTheSamePendingRequestDoesNotLaunchAgainForEitherProvider() {
        for(provider in VoiceInputController.Provider.entries) {
            val value=main { controller() };val registry=Registry();start(value,provider.name,provider)
            val id=value.permissionRequest;val tester=content(registry) {value}
            ui.waitUntil(5_000) { registry.launches.size==1 };val code=registry.launches.single()
            tester.emulateSavedInstanceStateRestore();ui.waitForIdle()
            assertEquals(1,registry.launches.size);assertEquals(id,value.permissionRequest)
            main { assertTrue(registry.dispatchResult(code,false));yield() }
            ui.waitUntil(5_000) { value.phase==VoiceInputController.Phase.FAILED }
            assertNull(value.permissionRequest);assertEquals("Original ${provider.name}",value.capturedText)
            assertEquals("麦克风权限未开启，请在系统设置中允许访问麦克风。",value.statusCopyKey)
            assertEquals(1,registry.launches.size);main { value.cancel();yield() }
        }
        assertEquals(1,readiness)
    }
    @Test fun restoredOldReceiptAndDuplicateCannotGrantOrDenyTheReplacementCapture() {
        for(provider in VoiceInputController.Provider.entries) {
            val old=main { controller() };val fresh=main { controller() };val registry=Registry()
            var shown by mutableStateOf(old);var freshAllowed=true;start(old,"old-${provider.name}",provider)
            val tester=content(registry) {shown};ui.waitUntil(5_000) { registry.launches.size==1 };val oldCode=registry.launches.single()
            main { old.cancel();yield() };start(fresh,"fresh-${provider.name}",provider,{freshAllowed});val freshId=fresh.permissionRequest
            main { shown=fresh };ui.waitForIdle();tester.emulateSavedInstanceStateRestore();ui.waitForIdle()
            assertEquals("Old physical request still owns its receipt",1,registry.launches.size)
            // A broken old-result association must fail authority before it could open a real
            // desktop microphone. The correct path leaves the fresh request waiting untouched.
            main { freshAllowed=false;assertTrue(registry.dispatchResult(oldCode,true));yield() }
            ui.waitUntil(5_000) { registry.launches.size==2 };val freshCode=registry.launches.last()
            assertNotEquals(oldCode,freshCode);assertEquals(freshId,fresh.permissionRequest)
            assertEquals(VoiceInputController.Phase.REQUESTING,fresh.phase);assertEquals(0,systemStarts)
            main { registry.dispatchResult(oldCode,false);yield() };ui.waitForIdle()
            assertEquals(freshId,fresh.permissionRequest);assertEquals(VoiceInputController.Phase.REQUESTING,fresh.phase)
            main { freshAllowed=true;assertTrue(registry.dispatchResult(freshCode,false));yield() }
            ui.waitUntil(5_000) { fresh.phase==VoiceInputController.Phase.FAILED }
            assertEquals("Original fresh-${provider.name}",fresh.capturedText);assertEquals(2,registry.launches.size)
            main { fresh.cancel();yield() }
        }
        assertEquals(2,readiness)
    }
    @Test fun aCurrentPermissionReceiptStillCannotBypassChangedDraftAuthority() {
        for(provider in VoiceInputController.Provider.entries) {
            val value=main { controller() };val registry=Registry();var allowed=true
            start(value,"changed-${provider.name}",provider,{allowed});content(registry) {value}
            ui.waitUntil(5_000) { registry.launches.size==1 }
            main { allowed=false;assertTrue(registry.dispatchResult(registry.launches.single(),true));yield() }
            ui.waitUntil(5_000) { value.phase==VoiceInputController.Phase.FAILED }
            assertEquals("Original changed-${provider.name}",value.capturedText);assertEquals(0,systemStarts);assertEquals(0,applies)
            main { value.cancel();yield() }
        }
        assertEquals(1,readiness)
    }
    @Test fun synchronousDenialRetiresTheLaunchBeforeAnotherCompositionCanReplayIt() {
        for(provider in VoiceInputController.Provider.entries) {
            val value=main { controller() };val registry=Registry(false);start(value,"synchronous-${provider.name}",provider)
            val tester=content(registry) {value};ui.waitUntil(5_000) { value.phase==VoiceInputController.Phase.FAILED }
            ui.waitForIdle();tester.emulateSavedInstanceStateRestore();ui.waitForIdle()
            assertEquals(1,registry.launches.size);assertNull(value.permissionRequest)
            assertEquals("Original synchronous-${provider.name}",value.capturedText);main { value.cancel();yield() }
        }
        assertEquals(1,readiness)
    }
}
