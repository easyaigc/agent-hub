package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Test-only best-effort bounded diagnostics; never consumes or reclassifies the original crash. */
private class NativeOwnerUncaughtReporter private constructor(
    private val instrumentation: android.app.Instrumentation,
    private val run: String,
    private val original: Thread.UncaughtExceptionHandler,
) : java.io.Closeable {
    private val reporting=java.util.concurrent.atomic.AtomicBoolean(false)
    private val handler=Thread.UncaughtExceptionHandler { thread,error ->
        try {
            if(reporting.compareAndSet(false,true)) {
                val text=StringBuilder(4096)
                var truncated=false
                fun add(value: String) {
                    val remaining=4096-text.length
                    if(value.length>remaining)truncated=true
                    if(remaining>0)text.append(value.take(remaining))
                }
                fun clip(value: String,limit: Int): String {
                    if(value.length>limit)truncated=true
                    return value.take(limit)
                }
                val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
                var cursor: Throwable?=error
                var causes=0
                while(cursor!=null&&causes<8&&seen.add(cursor)&&text.length<4096) {
                    val failure=cursor
                    add("cause[${causes}]: "+clip(failure.javaClass.name,256)+": "+clip(failure.message.orEmpty(),384)+"\n")
                    val frames=failure.stackTrace
                    if(frames.size>24)truncated=true
                    for(frame in frames.take(24)) {
                        add("  at "+clip(frame.className,192)+"."+clip(frame.methodName,128)+"("+clip(frame.fileName.orEmpty(),128)+":"+frame.lineNumber+")\n")
                        if(text.length>=4096)break
                    }
                    cursor=failure.cause;causes++
                }
                if(cursor!=null)truncated=true
                val bytes=text.toString().toByteArray(Charsets.UTF_8) // <=12288 bytes / <=16384 base64 bytes.
                val payload=android.os.Bundle().apply {
                    putInt("source53OwnerDiagVersion",1)
                    putString("source53OwnerDiagKind","uncaught")
                    putString("source53OwnerDiagRun",run)
                    putInt("source53OwnerDiagPid",android.os.Process.myPid())
                    putLong("source53OwnerDiagThreadId",thread.id)
                    putString("source53OwnerDiagThread",thread.name.replace('\n','_').replace('\r','_').take(64))
                    putInt("source53OwnerDiagTraceUtf8Bytes",bytes.size)
                    putBoolean("source53OwnerDiagTruncated",truncated)
                    putString("source53OwnerDiagTraceBase64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
                }
                // A distinct diagnostic status, never class/test/method result/overall/OK1/stream injection.
                instrumentation.sendStatus(731,payload)
            }
        } catch(_: Throwable) {
            // Reporting must not consume or replace the original uncaught exception.
        } finally {
            original.uncaughtException(thread,error)
        }
    }
    override fun close() {
        if(Thread.getDefaultUncaughtExceptionHandler()===handler)
            Thread.setDefaultUncaughtExceptionHandler(original)
    }
    companion object {
        fun install(instrumentation: android.app.Instrumentation,run: String): NativeOwnerUncaughtReporter {
            require(java.util.UUID.fromString(run).toString()==run)
            val original=checkNotNull(Thread.getDefaultUncaughtExceptionHandler()) {"Original default uncaught handler required"}
            val reporter=NativeOwnerUncaughtReporter(instrumentation,run,original)
            check(Thread.getDefaultUncaughtExceptionHandler()===original) {"Default handler changed before diagnostic installation"}
            Thread.setDefaultUncaughtExceptionHandler(reporter.handler)
            check(Thread.getDefaultUncaughtExceptionHandler()===reporter.handler) {"Default handler changed during diagnostic installation"}
            return reporter
        }
    }
}

/** Real Activity migration controller/lifecycle + real Session/VoiceInputController. No microphone or Host. */
class NativeMigrationProvisionalOwnerTest {
    private lateinit var fixture: MigrationProvisionalFixture
    private lateinit var voice: NativeSavedVoiceSessionFixture
    private lateinit var pending: PendingVoiceRecording
    private lateinit var originalRaw: ByteArray
    private lateinit var originalRow: String
    private lateinit var rawFile: File
    private var uncaughtReporter: NativeOwnerUncaughtReporter?=null
    @get:Rule(order=0)val installation: org.junit.rules.TestRule=object: org.junit.rules.ExternalResource() {
        override fun before() { prepareInstallation() }
        override fun after() {
            try { uncaughtReporter?.close() }
            finally { if(::fixture.isInitialized)NativeMigrationProvisionalOwnerActivity.release(fixture.run) }
        }
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<NativeMigrationProvisionalOwnerActivity>()
    private fun prepareInstallation()=runBlocking {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        instrumentation.runOnMainSync {
            val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val active=androidx.test.runner.lifecycle.Stage.values().filter {it!=androidx.test.runner.lifecycle.Stage.DESTROYED}
                .flatMap {monitor.getActivitiesInStage(it)}
            check(active.none {it is MainActivity}) {"Existing Activity retained; owner fixture must start before any Main Activity"}
            NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(app)
        }
        withContext(Dispatchers.IO) {
            val parent=app.noBackupFilesDir
            check(!NativeNaturalEntryFence.hasMaterial(parent)) {"Existing natural entry retained"}
            check(java.nio.file.Files.notExists(File(parent,NativeChatNotificationColdRouterFiles.NAMESPACE).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "Existing cold router material retained"
            }
        }
        fixture=MigrationProvisionalFixture();fixture.fresh();val context=fixture.context("source");val storage=fixture.storage("source")
        voice=NativeSavedVoiceSessionFixture(context,storage)
        withContext(Dispatchers.Main.immediate){voice.connect();voice.edit(false,NativeSavedVoiceSessionFixture.ORIGINAL)}
        storage.awaitQueuedWrites();val client=voice.session.selectedClient
        pending=VoiceRecordingStore(context,storage).begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect?.fingerprint,voice.key(false),"draft","thread",voice.value(false))
        originalRaw=fixture.originalBytes();rawFile=VoiceRecordingStore(context,storage).file(pending.id);rawFile.writeBytes(originalRaw)
        originalRow=storage.read("voice-recording:${pending.id}")!!
        withContext(Dispatchers.Main.immediate){
            NativeSavedVoiceSessionFixture.until("actual provisional Session destination admission") {
                !voice.session.loading&&!voice.session.historyLoading&&voice.session.canUseVoice
            }
            storage.awaitQueuedWrites()
            voice.session.beginVoice(recoverSavedFile=true)
        }
        withTimeout(30_000) {
            while(!withContext(Dispatchers.Main.immediate){voice.session.voice!!.phase==VoiceInputController.Phase.FAILED&&voice.session.migrationReady&&runCatching {voice.session.voice!!.requireMigrationRawIdle()}.isSuccess})delay(5)
        }
        withContext(Dispatchers.Main.immediate){
            val controller=voice.session.voice!!
            val restored=VoiceInputController::class.java.getDeclaredField("pending").apply {isAccessible=true}.get(controller) as PendingVoiceRecording
            assertEquals(pending.id,restored.id);assertNull(restored.manifest)
            assertFalse(controller.canInspectSavedFile);assertFalse(controller.hasRetryableRecording);assertFalse(controller.canRetry)
            assertEquals("录音未完成",controller.failureTitleKey);assertOriginal()
        }
        withContext(Dispatchers.IO){NativeMigrationProvisionalOwnerActivity.prepare(fixture.run,context,voice.session)}
        uncaughtReporter=NativeOwnerUncaughtReporter.install(instrumentation,fixture.run)
    }
    @Before fun setup() {
        ui.waitUntil(30_000){!ui.activity.session.restoring&&ui.activity.session.migrationReady}
        ui.runOnIdle {assertSame(voice.session,ui.activity.session)}
    }
    @After fun finish() {
        // Retire only this fixture's controllers before Activity teardown; ordinary pause must not seal its raw original.
        ui.runOnIdle {ui.activity.migration.cancel();ui.activity.session.voice?.retireForMigration();ui.activity.session.questionVoice?.retireForMigration()}
    }
    private fun assertOriginal() {
        assertArrayEquals(originalRaw,rawFile.readBytes());assertEquals(originalRow,voice.storage.read("voice-recording:${pending.id}"))
        assertNull(JSONObject(originalRow).optional("manifest"));assertFalse(JSONObject(originalRow).getBoolean("prepareAttempted"));assertFalse(JSONObject(originalRow).getBoolean("audioAttempted"))
        assertEquals(0,voice.voiceHostCalls.get());assertEquals(0,voice.microphoneCalls.get())
    }
    @Test fun controllerExportOfStationaryFailedRawNeverCallsOrdinaryPauseSealOrReplacesOriginal()=runBlocking {
        val oldController=voice.session.voice!!;val oldError=oldController.error
        val hostField=NativeMigrationController::class.java.getDeclaredField("host").apply {isAccessible=true}
        val realOwner=hostField.get(ui.activity.migration) as NativeMigrationLifecycle
        val lateChecks=AtomicInteger()
        val observedOwner=object: NativeMigrationLifecycle by realOwner {
            override suspend fun quiesce(operationId: String): NativeMigrationOwnerLease {
                val held=realOwner.quiesce(operationId) // The actual Activity/Session epoch and kernel freeze.
                withContext(Dispatchers.IO){
                    assertThrows(NativeVoiceRawGate.Busy::class.java){NativeVoiceRawOwnership.writer(rawFile)}
                    held.requireRawWriters();lateChecks.incrementAndGet();assertOriginal()
                }
                return held
            }
        }
        ui.runOnIdle {ui.activity.migration.attach(observedOwner);ui.activity.migration.password=fixture.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){ui.activity.migration.phase in setOf(NativeMigrationPhase.CHOOSE_EXPORT,NativeMigrationPhase.FAILED)}
        assertEquals(NativeMigrationPhase.CHOOSE_EXPORT,ui.activity.migration.phase);assertEquals(1,lateChecks.get());assertOriginal()
        assertEquals(VoiceInputController.Phase.FAILED,oldController.phase);assertEquals(oldError,oldController.error)
        assertNotSame(voice.session,ui.activity.session)
        val field=NativeMigrationController::class.java.getDeclaredField("exportFile").apply {isAccessible=true}
        val envelope=field.get(ui.activity.migration) as File
        val destination=fixture.storage("destination");val receipt=NativeMigrationCandidateStore(fixture.context("destination"),destination).stageFile(UUID.randomUUID().toString(),envelope,fixture.password)
        NativeMigrationActivation(fixture.context("destination"),destination).activate(receipt)
        val current=destination.reopen(fixture.context("destination"));val original=NativeMigrationProvisionalVoice.read(current).single()
        assertEquals(originalRow,original.row);val bytes=java.io.ByteArrayOutputStream();NativeMigrationProvisionalVoice.copy(current,original,bytes);assertArrayEquals(originalRaw,bytes.toByteArray())
        assertEquals("[]",current.read("voice-index-v1"));assertNull(current.read("voice-recording:${pending.id}"));assertOriginal()
    }
    @Test fun actualControllerRejectsRegisteredLiveWriterWithoutPausingOrStoppingIt() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val writes=AtomicInteger();val failure=AtomicReference<Throwable?>()
        val thread=Thread {
            try {NativeVoiceRawOwnership.writer(rawFile).use {writer->
                RandomAccessFile(rawFile,"rw").use {out->entered.countDown();while(!release.await(1,TimeUnit.MILLISECONDS)){writer.requireCurrent();out.seek(out.length());out.write(7);writes.incrementAndGet()}}
            }}catch(error: Throwable){failure.set(error)}
        }
        thread.start()
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));val originalController=voice.session.voice!!;val oldError=originalController.error
            ui.runOnIdle {ui.activity.migration.password=fixture.password;ui.activity.migration.export()}
            ui.waitUntil(30_000){ui.activity.migration.phase==NativeMigrationPhase.FAILED}
            ui.runOnIdle {assertEquals(NativeMigrationResult.UNSUPPORTED,ui.activity.migration.result);assertSame(voice.session,ui.activity.session);assertFalse(voice.session.migrationPaused);assertEquals(VoiceInputController.Phase.FAILED,originalController.phase);assertEquals(oldError,originalController.error)}
            val before=writes.get();val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(writes.get()<=before&&System.nanoTime()<deadline)Thread.yield()
            assertTrue(writes.get()>before);assertTrue(thread.isAlive);assertEquals(originalRow,voice.storage.read("voice-recording:${pending.id}"));assertNull(failure.get())
        }finally {release.countDown();thread.join(5_000)}
        assertFalse(thread.isAlive);assertNull(failure.get())
    }
    @Test fun frozenOwnerRejectsRealCaptureEntryAndEveryOriginalMutationBeforeAnyRwOpen()=runBlocking {
        val operation=UUID.randomUUID().toString();val raw=NativeVoiceRawOwnership.freeze(voice.storage,operation)
        try {
            val worker=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            try {
                val capture=NativeAudioCapture(rawFile,worker){}
                assertTrue(runCatching {capture.start()}.exceptionOrNull() is NativeVoiceRawGate.Busy);assertTrue(capture.ended)
            }finally {worker.cancel()}
            val store=VoiceRecordingStore(fixture.context("source"),voice.storage)
            assertTrue(runCatching {store.seal(pending)}.exceptionOrNull() is NativeVoiceRawGate.Busy)
            assertTrue(runCatching {store.remove(pending)}.exceptionOrNull() is NativeVoiceRawGate.Busy)
            assertTrue(runCatching {store.mark(pending,true)}.exceptionOrNull() is NativeVoiceRawGate.Busy)
            raw.requireCurrent(voice.storage,operation);assertOriginal()
        }finally {raw.close()}
    }
}
