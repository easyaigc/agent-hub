package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Actual retained Activity factory plus real encrypted terminal. No recording, capability or network. */
class NativeVoiceDiscardAppIntegrationTest {
    @get:Rule(order=0)val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private var previous: MainActivity.AcceptanceSessionState?=null
    private val observed=setOf("voice-index-v1",NativeVoiceDiscardCatalog.RECORD)
    private var defaultVoice=emptyMap<String,String>()
    @Before fun setup() {
        ui.waitUntil(30_000){!ui.activity.session.restoring}
        defaultVoice=NativeSessionStorage(ui.activity).migrationReadTextRecords(observed)
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"voice-discard-app");owned.session.resetAcceptance()
            previous=ui.activity.adoptSessionForAcceptance(owned.session)
        }
    }
    @After fun finish() {
        if(::owned.isInitialized)ui.runOnIdle {owned.session.leaveForeground();owned.verifyDefault()}
        previous?.let {old->ui.runOnIdle{ui.activity.restoreSessionForAcceptance(old)}}
        assertEquals(defaultVoice,NativeSessionStorage(ui.activity).migrationReadTextRecords(observed))
    }
    @Test fun bothControllersUseOnlyTheirRestoredStorageAndRetainTheSameTerminalPortAfterRecreation()=runBlocking {
        ui.waitUntil(15_000){owned.session.voice?.discardPort!=null&&owned.session.questionVoice?.discardPort!=null}
        val port=requireNotNull(owned.session.voice!!.discardPort)
        assertSame(port,owned.session.questionVoice!!.discardPort)
        ui.runOnIdle {assertFalse(owned.session.bindVoiceDiscardIfMissing{error("Retained Session must not reconstruct the terminal adapter")})}
        val payload=MigrationActivationFixtures.payload();val client=MigrationActivationFixtures.client(payload)
        val store=VoiceRecordingStore(ui.activity,owned.storage)
        val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey("synthetic-thread"),"voice-v1:app-terminal","synthetic-thread","Synthetic original app terminal")
        val audio=WavePCM.header(6400)+ByteArray(6400){(it*19%256).toByte()};store.file(pending.id).writeBytes(audio)
        val sealed=store.seal(pending);store.mark(sealed,true,true,VoiceReceipt(sealed.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"))
        val request=NativeVoiceDiscardRequest(sealed);assertTrue(port.discard(request).acknowledges(request))
        assertNull(store.restore(sealed.scope,sealed.certificate,sealed.draftKey,sealed.originalText))
        assertArrayEquals(audio,store.bytes(sealed));assertEquals(sealed.manifest,store.current(sealed).manifest)
        assertTrue(NativeVoiceDiscardCatalog.read(owned.storage).containsKey(sealed.id))
        assertEquals(defaultVoice,NativeSessionStorage(ui.activity).migrationReadTextRecords(observed))
        ui.activityRule.scenario.recreate();ui.waitUntil(20_000){ui.activity.session===owned.session}
        assertSame(port,owned.session.voice!!.discardPort);assertSame(port,owned.session.questionVoice!!.discardPort)
        assertFalse(owned.session.voice!!.active);assertFalse(owned.session.questionVoice!!.active)
        assertTrue(owned.session.operationRecords.isEmpty())
    }
}
