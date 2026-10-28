package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real preference coordinator with a narrow fake store; no Schema, Activity, recognizer or Host service. */
class NativeVoicePreferenceLifecycleTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(90)
    private val gates=mutableListOf<CompletableDeferred<Unit>>()
    private fun gate()=CompletableDeferred<Unit>().also(gates::add)
    private class Store(initial: VoiceInputController.Provider?): NativeVoicePreferenceStore {
        val value=AtomicReference(initial)
        val reads=AtomicInteger();val writes=AtomicInteger()
        @Volatile var failReads=false
        @Volatile var failWrites=false
        var beforeLoad: suspend (Int)->Unit={}
        var beforeSave: suspend (Int,VoiceInputController.Provider)->Unit={_,_->}
        override suspend fun load(): VoiceInputController.Provider? {
            beforeLoad(reads.incrementAndGet());if(failReads)throw IOException("synthetic preference read failure")
            return value.get()
        }
        override suspend fun save(value: VoiceInputController.Provider) {
            beforeSave(writes.incrementAndGet(),value);if(failWrites)throw IOException("synthetic preference write failure")
            this.value.set(value)
        }
    }
    private fun scenario(block: suspend (CoroutineScope)->Unit)=runBlocking {
        val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try { withContext(Dispatchers.Main) { block(tasks) } }
        finally { gates.forEach { it.complete(Unit) };tasks.cancel() }
    }
    private suspend fun await(job: Job?)=withTimeout(10_000) { job?.join() }
    private suspend fun until(value: ()->Boolean)=withTimeout(10_000) { while(!value())delay(5) }
    @Test fun retainedSessionConfigureAndBindIfMissingNeverConstructOrBindTwice()=scenario { _ ->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val session=MobileSession(NativeSessionStorage(context,"voice-binding-"+UUID.randomUUID()))
        val backend=Store(VoiceInputController.Provider.SYSTEM);var factories=0
        session.resetAcceptance()
        try {
            assertTrue(session.bindVoicePreferencesIfMissing { factories++;backend })
            session.configureVoice(context);val controller=session.voice;val question=session.questionVoice
            repeat(3) {
                assertFalse(session.bindVoicePreferencesIfMissing { error("Repeated configure must not construct a new adapter") })
                session.configureVoice(context)
            }
            await(session.voiceSettings.resumePreferences())
            assertEquals(1,factories);assertEquals(1,backend.reads.get());assertTrue(session.voiceSettings.persisted)
            assertSame(controller,session.voice);assertSame(question,session.questionVoice)
            try { session.voiceSettings.bind(backend);fail("Explicit bind must still reject replacement") }catch(_: IllegalStateException) { }
        }finally { session.leaveForeground() }
    }
    @Test fun deferredBindingWaitsForRecoveryAndPreservesASelectionMadeBeforeLoad()=scenario { tasks ->
        val settings=NativeVoiceSettings(tasks);val backend=Store(VoiceInputController.Provider.DESKTOP)
        settings.select(VoiceInputController.Provider.SYSTEM)
        assertTrue(settings.bindIfMissing(false) { backend });assertFalse(settings.persisted)
        assertNull(settings.refreshPreferences());assertEquals(0,backend.reads.get());assertEquals(0,backend.writes.get())
        await(settings.resumePreferences())
        assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider);assertEquals(VoiceInputController.Provider.SYSTEM,backend.value.get())
        assertEquals(1,backend.reads.get());assertEquals(1,backend.writes.get());assertTrue(settings.persisted)
    }
    @Test fun missingPreferenceUsesVerifiedDefaultWithoutWritingANewSchema()=scenario { tasks ->
        val settings=NativeVoiceSettings(tasks);val backend=Store(null)
        settings.bind(backend);await(settings.resumePreferences())
        assertEquals(VoiceInputController.Provider.DESKTOP,settings.defaultProvider);assertTrue(settings.persisted)
        assertEquals(1,backend.reads.get());assertEquals(0,backend.writes.get())
    }
    @Test fun failedRestoreCannotPretendPersistenceOrWriteOverUnreadSource()=scenario { tasks ->
        val settings=NativeVoiceSettings(tasks);val backend=Store(VoiceInputController.Provider.DESKTOP).apply { failReads=true }
        settings.bind(backend);await(settings.resumePreferences())
        assertFalse(settings.persisted);assertEquals(NativeVoiceSettings.PreferenceFailure.READ,settings.preferenceError)
        settings.select(VoiceInputController.Provider.SYSTEM);await(settings.resumePreferences())
        assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider);assertFalse(settings.persisted);assertEquals(0,backend.writes.get())
        backend.failReads=false;await(settings.retryPreferences())
        assertTrue(settings.persisted);assertEquals(VoiceInputController.Provider.SYSTEM,backend.value.get());assertEquals(1,backend.writes.get())
    }
    @Test fun cancelledLateLoadCannotOverwriteTheNewChoiceOrPublishDuringRecovery()=scenario { tasks ->
        val entered=gate();val released=gate();val backend=Store(VoiceInputController.Provider.DESKTOP)
        backend.beforeLoad={ n -> if(n==1) { entered.complete(Unit);withContext(NonCancellable) { released.await() } } }
        val settings=NativeVoiceSettings(tasks);settings.bind(backend);entered.await()
        settings.pausePreferences();settings.select(VoiceInputController.Provider.SYSTEM)
        assertFalse(settings.persisted);assertNull(settings.refreshPreferences());assertEquals(1,backend.reads.get())
        val resumed=settings.resumePreferences();assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider)
        released.complete(Unit);await(resumed)
        assertEquals(2,backend.reads.get());assertEquals(1,backend.writes.get());assertTrue(settings.persisted)
        assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider)
    }
    @Test fun writeFailureNeedsVerifiedReadbackAndExplicitRetryInsteadOfFalseAcknowledgement()=scenario { tasks ->
        val backend=Store(VoiceInputController.Provider.DESKTOP);val settings=NativeVoiceSettings(tasks)
        settings.bind(backend);await(settings.resumePreferences());backend.failWrites=true
        settings.select(VoiceInputController.Provider.SYSTEM);until { settings.preferenceStatus==NativeVoiceSettings.PreferenceStatus.FAILED }
        assertFalse(settings.persisted);assertEquals(NativeVoiceSettings.PreferenceFailure.WRITE,settings.preferenceError)
        await(settings.refreshPreferences());assertFalse(settings.persisted);assertEquals(1,backend.writes.get())
        assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider)
        backend.failWrites=false;await(settings.retryPreferences())
        assertTrue(settings.persisted);assertEquals(2,backend.writes.get());assertEquals(VoiceInputController.Provider.SYSTEM,backend.value.get())
    }
    @Test fun cancelledCommittedWriteIsReadBackAndCannotAcknowledgeADifferentNewChoice()=scenario { tasks ->
        for(change in listOf(false,true)) {
            val backend=Store(VoiceInputController.Provider.DESKTOP);val entered=gate();val released=gate()
            backend.beforeSave={ n,value -> if(n==1)withContext(NonCancellable) { backend.value.set(value);entered.complete(Unit);released.await() } }
            val settings=NativeVoiceSettings(tasks);settings.bind(backend);await(settings.resumePreferences())
            settings.select(VoiceInputController.Provider.SYSTEM);entered.await()
            if(change)settings.select(VoiceInputController.Provider.DESKTOP)
            settings.pausePreferences();assertFalse(settings.persisted)
            val resumed=settings.resumePreferences();released.complete(Unit);await(resumed)
            assertEquals(1,backend.writes.get())
            if(change) {
                assertEquals(VoiceInputController.Provider.DESKTOP,settings.defaultProvider);assertFalse(settings.persisted)
                await(settings.retryPreferences());assertTrue(settings.persisted);assertEquals(2,backend.writes.get())
                assertEquals(VoiceInputController.Provider.DESKTOP,backend.value.get())
            }else { assertTrue(settings.persisted);assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider) }
        }
    }
}
