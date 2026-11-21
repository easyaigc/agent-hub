package com.agenthub.android

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.rules.TestName
import java.security.MessageDigest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Synthetic local boundaries with real Keystore/AtomicFile. Never posts an OS/remote notice. */
class NativeChatNotificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace by lazy { "P9-chat-notification-${UUID.randomUUID()}" }
    @get:Rule val testMethod=TestName()
    private var preferenceFixture: PreferenceEpochFixture?=null
    private var preferencePrimary: Throwable?=null
    private lateinit var storage: NativeSessionStorage
    private lateinit var store: NativeChatNotificationStore
    private val target = NativeChatNoticeTarget("host", "workspace", "desktop-runtime")
    private val now = 1_793_180_000_000L
    private fun event(id: String = "a".repeat(64), target: NativeChatNoticeTarget = this.target,
        occurred: Long = now, kind: NativeChatNoticeKind = NativeChatNoticeKind.APPROVAL) =
        NativeChatNotificationEvent(id, target, kind, Instant.ofEpochMilli(occurred).toString(), "thread",
            threadTitle = "private title", taskSummary = "private summary")
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }
    private fun enable(): NativeChatNoticeLease { store.update { it.copy(enabled = true) }; return store.bind(target, "credential-reference-1") }
    @Before fun prepare() {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        val exactMethod=checkNotNull(testMethod.methodName) {"JUnit must identify the method before fixture construction"}
        if(exactMethod=="preferenceCommitRevokesEpochsCapturedWhileWaitingForStorage") {
            val owned=PreferenceEpochFixture(context);preferenceFixture=owned
            try {owned.prepare();storage=owned.storage;store=NativeChatNotificationStore(storage)}
            catch(error: Throwable){preferencePrimary=error;owned.rememberFailure(error);throw error}
        } else {
            storage = NativeSessionStorage(context, namespace)
            store = NativeChatNotificationStore(storage)
        }
    }
    @After fun finishOwnedPreferenceFixture() {
        preferenceFixture?.let {owned->
            try {owned.finish()}catch(error: Throwable) {
                val original=preferencePrimary
                if(original==null)throw error
                if(original!==error)original.addSuppressed(error) // The original Before/body failure still fails JUnit.
            }
        }
    }
    @Test fun preferenceCommitRevokesEpochsCapturedWhileWaitingForStorage() {
        val owned=checkNotNull(preferenceFixture)
        try {owned.run()}catch(error: Throwable){preferencePrimary=error;owned.rememberFailure(error);throw error}
    }
    @Test fun preferencesStrictRoundtripAndDefaults() {
        val initial = store.preferences(); assertFalse(initial.enabled); assertTrue(initial.showThreadTitle)
        assertEquals(7, initial.kinds.size)
        val encoded = NativeChatNotificationCodec.preferences(initial)
        assertEquals(initial, NativeChatNotificationCodec.preferences(encoded))
        rejected { NativeChatNotificationCodec.preferences(JSONObject(encoded.toString()).put("enabled", "false")) }
        rejected { NativeChatNotificationCodec.preferences(JSONObject(encoded.toString()).put("generation", 0.5)) }
        rejected { NativeChatNotificationCodec.preferences(JSONObject(encoded.toString()).put("future", true)) }
        rejected { NativeChatNotificationCodec.preferences(JSONObject(encoded.toString()).put("version", 2)) }
    }
    @Test fun filtersAndCategorySilencePersistAcrossColdStore() {
        store.update { it.copy(enabled = true, kinds = setOf(NativeChatNoticeKind.APPROVAL), excludedHosts = setOf("other"),
            showThreadTitle = false, categorySounds = mapOf(NativeChatNoticeKind.APPROVAL to "none")) }
        val cold = NativeChatNotificationStore(NativeSessionStorage(context, namespace)).preferences()
        assertTrue(cold.permits(event())); assertFalse(cold.permits(event(kind = NativeChatNoticeKind.COMPLETED)))
        assertFalse(cold.permits(event(target = target.copy(hostId = "other"))))
        assertFalse(cold.showThreadTitle); assertEquals("none", cold.soundFor(NativeChatNoticeKind.APPROVAL))
    }
    @Test fun exactTargetsIgnoreOnlyCodexAccountMetadata() {
        val a = NativeChatNoticeTarget.from(TargetScope("codex", "old", "user-a", "host", "workspace", "desktop-runtime"))
        val b = NativeChatNoticeTarget.from(TargetScope("codex", "new", "user-b", "host", "workspace", "desktop-runtime"))
        assertEquals(a, b); assertNotEquals(a, a.copy(runtimeId = "other"))
        rejected { NativeChatNoticeTarget.from(TargetScope("easywork", null, null, "host", "workspace", "runtime")) }
        rejected { NativeChatNotificationCodec.target(NativeChatNotificationCodec.target(a).put("hostId", JSONObject.NULL)) }
    }
    @Test fun eventRejectsUnknownPayloadAndReadScopeMismatch() {
        val valid = NativeChatNotificationCodec.event(event())
        assertEquals(event(), NativeChatNotificationCodec.event(valid))
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("kind", "business-task")) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("shell", "unsafe")) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("taskSummary", "secret\nsecond line")) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("taskSummary", "secret\u202erendering")) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("readTarget", NativeChatNotificationCodec.target(target.copy(runtimeId = "wrong")))) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("version", "1")) }
    }
    @Test fun completedReadVersionRemainsExactTransportValue() {
        val original = event(kind = NativeChatNoticeKind.COMPLETED).copy(readTarget = target,
            readPosition = NativeChatNoticeReadPosition(9, "message", "b".repeat(64)))
        assertEquals(original, NativeChatNotificationCodec.event(NativeChatNotificationCodec.event(original)))
        rejected { original.copy(kind = NativeChatNoticeKind.QUESTION) }
        rejected { original.copy(threadId = null) }
        rejected { NativeChatNoticeReadPosition(0, "message", "b".repeat(64)) }
    }
    @Test fun duplicateClaimCannotReplayAfterRestartOrUnknownSystemResult() {
        val lease = enable(); val receipt = store.claim(event(), lease, now)!!
        store.finish(receipt.ticket, false)
        val cold = NativeChatNotificationStore(NativeSessionStorage(context, namespace))
        assertNull(cold.claim(event(), lease, now + 1))
        assertEquals("unknown", cold.ticket(receipt.ticket, now)!!.state)
    }
    @Test fun eventIdIsIsolatedByCompleteTargetAndRegistration() {
        val lease = enable(); assertNotNull(store.claim(event(), lease, now))
        val other = target.copy(workspaceId = "other-workspace")
        val next = store.bind(other, "credential-reference-1")
        assertNotNull(store.claim(event(target = other), next, now))
        assertNull(store.claim(event(target = other), lease, now))
    }
    @Test fun preferencesAndRevocationInvalidateCapturedLeaseAndTap() {
        val lease = enable(); val receipt = store.claim(event(), lease, now)!!
        store.update { it.copy(showThreadTitle = false) }
        assertFalse(store.current(lease)); assertNull(store.ticket(receipt.ticket, now))
        assertNull(store.claim(event(), lease, now))
        val next = store.bind(target, "credential-reference-1"); store.revoke(target)
        assertFalse(store.current(next)); assertEquals(false, store.preferences().showThreadTitle)
    }
    @Test fun credentialRotationInvalidatesOldRegistrationWithoutReusingGeneration() {
        val old = enable(); assertEquals(old, store.bind(target, "credential-reference-1"))
        assertNotNull(store.claim(event(), old, now))
        val rotated = store.bind(target, "credential-reference-2")
        assertNotEquals(old.registrationGeneration, rotated.registrationGeneration)
        assertFalse(store.current(old)); assertTrue(store.current(rotated)); assertNull(store.claim(event(), old, now))
        assertNull(store.claim(event(), rotated, now))
    }
    @Test fun expiredAndFutureEventsAreNeverClaimed() {
        val lease = enable()
        assertNull(store.claim(event(occurred = now - NativeChatNotificationStore.TTL_MILLIS), lease, now))
        assertNull(store.claim(event(occurred = now + 60_001), lease, now))
        val receipt = store.claim(event(), lease, now)!!
        assertNull(store.ticket(receipt.ticket, now + NativeChatNotificationStore.TTL_MILLIS))
    }
    @Test fun clickRefreshesExactTargetAndConsumesOnceWithoutCommandSurface() {
        val lease = enable(); val receipt = store.claim(event(), lease, now)!!
        var opened = 0; var refreshed = 0
        val route = object : NativeChatNotificationRoute {
            override suspend fun refreshExactTarget(target: NativeChatNoticeTarget, credentialEpoch: String): Boolean {
                assertEquals(lease.target, target); assertEquals(lease.credentialEpoch, credentialEpoch); refreshed++; return true
            }
            override fun openPreservingDraft(event: NativeChatNotificationEvent) { assertEquals(receipt.event, event); opened++ }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking {
                val runtime = runtime()
                val intent = Intent(NativeChatNotificationPlatform.ACTION).putExtra(NativeChatNotificationPlatform.TICKET, receipt.ticket)
                assertTrue(runtime.open(intent, route)); assertFalse(runtime.open(intent, route))
            }
        }
        assertEquals(1, opened); assertEquals(1, refreshed)
    }
    @Test fun lateClickRefreshCannotOpenAfterPreferenceChange() {
        val lease = enable(); val receipt = store.claim(event(), lease, now)!!
        val route = object : NativeChatNotificationRoute {
            override suspend fun refreshExactTarget(target: NativeChatNoticeTarget, credentialEpoch: String): Boolean {
                store.update { it.copy(enabled = false) }; return true
            }
            override fun openPreservingDraft(event: NativeChatNotificationEvent) { fail("Late click must not open") }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking { assertFalse(runtime().open(Intent(NativeChatNotificationPlatform.ACTION).putExtra(NativeChatNotificationPlatform.TICKET, receipt.ticket), route)) }
        }
    }
    @Test fun readingReceivesExactVersionButCanSuppressPresentation() {
        val lease = enable(); var received = 0
        val reading = object : NativeChatNotificationReading {
            override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult {
                received++; return NativeChatNoticeReadingResult(false, 0)
            }
            override fun mayPresent(event: NativeChatNotificationEvent) = false
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking { assertEquals("suppressed", runtime(reading).receive(NativeChatNotificationCodec.event(event()).toString(), lease, "computer", true)) }
        }
        assertEquals(1, received)
    }
    @Test fun expiredEventsNeverReachReadingAuthority() {
        val lease = enable()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking { assertEquals("expired-or-future", runtime().receive(
                NativeChatNotificationCodec.event(event(occurred = now - NativeChatNotificationStore.TTL_MILLIS)).toString(), lease, "computer", true)) }
        }
    }
    @Test fun lateReadingResultCannotClaimAfterRevocation() {
        val lease = enable()
        val reading = object : NativeChatNotificationReading {
            override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult {
                store.revoke(target); return NativeChatNoticeReadingResult(true, 1)
            }
            override fun mayPresent(event: NativeChatNotificationEvent): Boolean = error("Revoked delivery cannot proceed")
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking { assertEquals("stale-target-or-generation", runtime(reading).receive(
                NativeChatNotificationCodec.event(event()).toString(), lease, "computer", true)) }
        }
    }
    @Test fun previewOffRedactsCollapsedExpandedAndPublicPresentation() {
        val hidden = NativeChatNoticePresentation.create(event(), "computer", false) { it }
        assertEquals(event().kind.title, hidden.title); assertEquals("", hidden.summary)
        assertFalse(hidden.toString().contains("private"))
        val visible = NativeChatNoticePresentation.create(event(), "computer\u202e", true) { it }
        assertEquals("private title", visible.title); assertEquals("private summary", visible.summary)
        assertFalse(visible.metadata.contains('\u202e')); assertFalse(visible.publicTitle.contains("private"))
        val old = NativeChatNoticePresentation.create(event().copy(threadTitle = null, taskSummary = null), "computer", true) { it }
        assertEquals("对话 · thread", old.title); assertEquals(NativeChatNoticeKind.APPROVAL.fallback, old.summary)
    }
    @Test fun corruptPreferencesArePreservedAndFailClosed() {
        storage.write(NativeChatNotificationStore.RECORD, "{\"version\":9}")
        val before = storage.read(NativeChatNotificationStore.RECORD)
        rejected { store.preferences() }; rejected { store.bind(target, "credential-reference-1") }
        assertEquals(before, storage.read(NativeChatNotificationStore.RECORD))
    }
    private fun runtime(reading: NativeChatNotificationReading = object : NativeChatNotificationReading {
        override suspend fun receive(event: NativeChatNotificationEvent) = error("Route must never reconcile or execute")
        override fun mayPresent(event: NativeChatNotificationEvent) = false
    }) = NativeChatNotificationRuntime(store, NativeChatNotificationPlatform(context, NativeL10n(context, "en")), reading) { now }

    /** Only the new preference wrapper owns this fixture; no default installation key is queried. */
    private class PreferenceEpochFixture(private val context: android.content.Context) {
        val id=UUID.randomUUID().toString()
        val namespace="P9-preference-epoch-$id"
        val alias="agent-hub-pref-epoch-$id"
        private val root=java.io.File(context.noBackupFilesDir,namespace)
        private val evidence=java.io.File(context.filesDir,"acceptance/notification-preference-epoch/$id")
        private val group=ThreadGroup("preference-epoch-$id")
        private var body: Thread?=null
        private val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
        private var initialized=false;private var rootOwned=false;private var aliasChecked=false;private var aliasAbsent=false
        private var rootInode=0L;private var rootDevice=0L;private var cipherInode=0L;private var cipherDevice=0L
        private var bodyPassed=false;private var workersClosed=false;private var storageClosed=false
        private var finishAttempted=false
        private var executor: java.util.concurrent.ExecutorService?=null
        private val writer=java.util.concurrent.atomic.AtomicReference<Thread>()
        private var originalCipher: ByteArray?=null
        lateinit var storage: NativeSessionStorage;private set
        private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        private fun directory(file: java.io.File) {
            require(file.isDirectory&&!java.nio.file.Files.isSymbolicLink(file.toPath())&&file.canonicalFile==file.absoluteFile)
            val observed=android.system.Os.lstat(file.path)
            require(observed.st_uid==android.os.Process.myUid())
            if(file==root&&rootOwned)require(observed.st_ino==rootInode&&observed.st_dev==rootDevice&&observed.st_mode and 511==448)
        }
        private fun mkdir(file: java.io.File,fresh: Boolean=false) {
            directory(checkNotNull(file.parentFile))
            if(fresh||!file.exists()) {java.nio.file.Files.createDirectory(file.toPath());android.system.Os.chmod(file.path,448)}
            directory(file);require(android.system.Os.lstat(file.path).st_mode and 511==448)
        }
        private fun bytes(name: String,value: ByteArray) {
            directory(evidence)
            val file=java.io.File(evidence,name)
            val fd=android.system.Os.open(file.path,android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW,384)
            java.io.FileOutputStream(fd).use {it.write(value);it.fd.sync()}
            require(file.readBytes().contentEquals(value))
            NativeReadingFixtureFiles.syncDirectory(evidence)
        }
        private fun record(name: String,value: JSONObject)=bytes(name,value.toString().toByteArray(Charsets.UTF_8))
        private fun state()=JSONObject().put("fixture",id).put("namespace",namespace).put("root",root.path).put("alias",alias)
            .put("evidence",evidence.path).put("rootOwned",rootOwned).put("aliasAbsenceChecked",aliasChecked).put("aliasAbsentBeforeFixture",aliasAbsent)
            .put("initialized",initialized).put("bodyPassed",bodyPassed).put("workersClosed",workersClosed).put("storageExecutorTerminated",storageClosed)
            .put("bodyAlive",body?.isAlive?:false).put("bodyThreadId",body?.id).put("writerAlive",writer.get()?.isAlive?:false)
            .put("writerThreadId",writer.get()?.id).put("executorIdentity",executor?.let(System::identityHashCode))
            .put("failureClass",failure.get()?.javaClass?.name).put("failureStack",failure.get()?.stackTraceToString())
            .put("replayAllowed",false).put("defaultAliasAccessAttempted",false).put("reviewAtMs",System.currentTimeMillis()+21_600_000L)
            .put("recovery","Read this same fixture ownership/originals/cleanup records; reconcile physical workers, executor, files and alias. Never re-run the wrapper to resolve unknown.")
            .put("releaseCondition","Root captures originals and confirms exact worker/writer and cleanup outcomes, then separately retires only this evidence fixture; no shared/default key deletion")
        private val cleanup=NativeReadingFixtureCleanup {n,phase,states->
            record("cleanup-%02d.json".format(n),state().put("phase",phase).put("states",JSONObject(states)).put("missingCompletionMeansUnknown",true))
        }
        fun prepare() {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
            mkdir(java.io.File(context.filesDir,"acceptance"));mkdir(checkNotNull(evidence.parentFile));mkdir(evidence,true)
            record("identity.json",state()) // Durable ownership before any namespace/key creation.
            require(!java.nio.file.Files.exists(root.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            aliasAbsent=!keys.containsAlias(alias);aliasChecked=true;require(aliasAbsent)
            mkdir(root,true);android.system.Os.lstat(root.path).also {rootInode=it.st_ino;rootDevice=it.st_dev};rootOwned=true
            storage=NativeSessionStorage(context,namespace,alias)
            executor=NativeSessionStorage::class.java.getDeclaredField("writes").apply {isAccessible=true}.get(storage) as java.util.concurrent.ExecutorService
            initialized=true;record("prepared.json",state())
        }
        fun rememberFailure(error: Throwable) {
            if(!failure.compareAndSet(null,error))failure.get()?.let {if(it!==error)it.addSuppressed(error)}
        }
        fun run() {
            check(initialized&&body==null&&!finishAttempted)
            body=Thread(group,{
                try {NativeChatNotificationPreferenceEpochAssertions.run(storage);bodyPassed=true}
                catch(error: Throwable){rememberFailure(error)}
            },"preference-body-$id").also {it.start()}
            checkNotNull(body).join(40_000)
            check(body?.isAlive==false) {"Owned preference body exit unknown; retain exact fixture"}
            failure.get()?.let {throw it}
            check(bodyPassed)
        }
        private fun closeWorkers() {
            body?.join(5_000)
            check(body?.isAlive!=true) {"Owned preference controller still alive"}
            // All four helper writers inherit this exact group, never a global name match.
            val threads=arrayOfNulls<Thread>(16);val count=group.enumerate(threads,true)
            check(count<threads.size) {"Owned helper thread inventory overflow"}
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            threads.take(count).filterNotNull().forEach {t->
                val left=deadline-System.nanoTime()
                if(t.isAlive&&left>0)java.util.concurrent.TimeUnit.NANOSECONDS.timedJoin(t,left)
                check(!t.isAlive) {"Owned helper writer exit unknown"}
            }
            val remainingThreads=arrayOfNulls<Thread>(16)
            check(group.enumerate(remainingThreads,true)==0);workersClosed=true
        }
        private fun captureOriginal() {
            directory(root)
            val name=sha(NativeChatNotificationStore.RECORD.toByteArray(Charsets.UTF_8))+".bin"
            require(root.list()?.toSet()==setOf(name)) {"Unexpected fixture files; retain without deletion"}
            val file=java.io.File(root,name);val s=android.system.Os.lstat(file.path)
            require(android.system.OsConstants.S_ISREG(s.st_mode)&&s.st_uid==android.os.Process.myUid()&&s.st_nlink==1L&&s.st_mode and 511==384&&s.st_size in 1L..1_048_576L)
            val fd=android.system.Os.open(file.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK,0)
            val raw=java.io.FileInputStream(fd).use {input->
                val before=android.system.Os.fstat(fd);val value=input.readBytes();val after=android.system.Os.fstat(fd)
                require(value.size.toLong()==before.st_size&&before.st_size==after.st_size&&before.st_ino==after.st_ino);value
            }
            cipherInode=s.st_ino;cipherDevice=s.st_dev
            originalCipher=raw;bytes("original-record.bin",raw)
            val text=checkNotNull(storage.read(NativeChatNotificationStore.RECORD)).toByteArray(Charsets.UTF_8)
            bytes("original-record.json",text)
            record("original-manifest.json",state().put("cipherName",name).put("cipherBytes",raw.size).put("cipherSha256",sha(raw)).put("textBytes",text.size).put("textSha256",sha(text)))
        }
        private fun closeStorage() {
            check(initialized&&workersClosed)
            val actual=checkNotNull(executor)
            try {
                // Capture the thread of the ACTUAL storage executor, not a substitute or a flag.
                actual.submit(Runnable {writer.set(Thread.currentThread())}).get(5,java.util.concurrent.TimeUnit.SECONDS)
                if(bodyPassed)captureOriginal()
            } finally {storage.retireWriter()}
            check(actual.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)) {"Storage executor exit unknown"}
            checkNotNull(writer.get()).join(5_000)
            storageClosed=actual.isTerminated&&writer.get()?.isAlive==false
            check(storageClosed)
        }
        fun finish() {
            check(!finishAttempted);finishAttempted=true
            var closeError: Throwable?=null
            try {closeWorkers();if(initialized)closeStorage()}catch(error: Throwable){closeError=error}
            if(failure.get()!=null||!bodyPassed||!workersClosed||!storageClosed||closeError!=null) {
                val error=failure.get()?:closeError?:IllegalStateException("Fixture initialization/body/physical closure incomplete")
                if(closeError!=null&&closeError!==error)error.addSuppressed(closeError)
                runCatching {record("retained.json",state().put("cleanupPhase",cleanup.phase).put("cleanupStates",JSONObject(cleanup.snapshot())).put("retentionReason","Failed/unknown; inspect original outcome and exact owned identities before any later action"))}.exceptionOrNull()?.let {if(it!==error)error.addSuppressed(it)}
                System.err.println("NOTIFICATION_PREFERENCE_RETAINED fixture=$id root=${root.path} alias=$alias evidence=${evidence.path} workersClosed=$workersClosed storageClosed=$storageClosed")
                throw error
            }
            val file=java.io.File(root,sha(NativeChatNotificationStore.RECORD.toByteArray(Charsets.UTF_8))+".bin")
            try {
                cleanup.step("cipher","removed") {
                    directory(root);require(root.list()?.toSet()==setOf(file.name))
                    val s=android.system.Os.lstat(file.path);require(android.system.OsConstants.S_ISREG(s.st_mode)&&s.st_uid==android.os.Process.myUid()&&s.st_nlink==1L&&s.st_mode and 511==384&&s.st_ino==cipherInode&&s.st_dev==cipherDevice)
                    require(file.readBytes().contentEquals(checkNotNull(originalCipher)))
                    java.nio.file.Files.delete(file.toPath());require(!java.nio.file.Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));NativeReadingFixtureFiles.syncDirectory(root)
                }
                cleanup.step("directory","removed") {directory(root);require(root.list()?.isEmpty()==true);java.nio.file.Files.delete(root.toPath());require(!java.nio.file.Files.exists(root.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));NativeReadingFixtureFiles.syncDirectory(context.noBackupFilesDir)}
                cleanup.step("alias","removed") {
                    check(aliasAbsent&&rootOwned)
                    val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
                    require(keys.containsAlias(alias));keys.deleteEntry(alias);require(!keys.containsAlias(alias))
                }
                cleanup.step("result","written") {record("result.json",state().put("executionNamespaceRemoved",true).put("syntheticAliasRemoved",true).put("originalsRetainedForRoot",true))}
                originalCipher?.fill(0)
                System.err.println("NOTIFICATION_PREFERENCE_CAPTURE fixture=$id evidence=${evidence.path} workersClosed=true storageClosed=true")
            }catch(error: Throwable) {
                rememberFailure(error)
                runCatching {record("retained.json",state().put("cleanupPhase",cleanup.phase).put("cleanupStates",JSONObject(cleanup.snapshot())))}.exceptionOrNull()?.let {if(it!==error)error.addSuppressed(it)}
                System.err.println("NOTIFICATION_PREFERENCE_RETAINED fixture=$id root=${root.path} alias=$alias evidence=${evidence.path} phase=${cleanup.phase}; no automatic replay")
                throw error
            }
        }
    }
}
