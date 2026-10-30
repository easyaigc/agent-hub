package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/** Actual Store jobs/reset/Compose projection + real storage lock and encrypted ledger.
 * No Activity, Session network/command port, new consumer or replacement unread rule.
 * Queue membership is inspected on this unique storage lock; elapsed time/UI busy is never proof. */
class NativePhoneReadingStoreEpochTest {
    private val a=TargetScope("codex","historical","subject","owner-host","workspace","removed-runtime")
    private val b=TargetScope("codex",null,null,"owner-host","workspace","current-runtime")
    private val reader="a".repeat(64)
    private val question=NativeReadingEvent("owner-question",a,"thread","question",runId="owner-run",requestId="owner-request")
    private fun position(n: Long)=ThreadReadPosition(n,"owner-message-$n",ThreadReadPosition.contentVersion("owner-message-$n","owner-reply-$n","completed"))
    private fun value(target: TargetScope,n: Long)=NativeReadingSnapshot(target,"thread",position(n),true)
    private fun obsoleteValue()=NativeReadingSnapshot(a,"thread",position(99),true,reader,ThreadReadState(position(99),reader,position(1)))
    private fun offMain()=check(Looper.myLooper()!=Looper.getMainLooper()) {"Storage/gate/join must not run on Main"}
    private fun <T> main(block: ()->T): T {offMain();return runBlocking {withContext(Dispatchers.Main.immediate){block()}}}
    private fun join(job: Job) {offMain();runBlocking {withTimeout(15_000){job.join()}};assertTrue(job.isCompleted)}

    /** Test-only, exact production lock field; fail closed if the storage implementation changes.
     * A holder on a dedicated executor keeps membership stable until release. No lock wait on Main. */
    private class Gate(private val storage: NativeSessionStorage,private val fixtureId: String): Closeable {
        private val lock=(NativeSessionStorage::class.java.getDeclaredField("ioLock").apply {isAccessible=true}.get(storage) as ReentrantLock)
        private val queued=ReentrantLock::class.java.getDeclaredMethod("getQueuedThreads").apply {isAccessible=true}
        private val held=CountDownLatch(1);private val release=CountDownLatch(1)
        private val executor=Executors.newSingleThreadExecutor()
        private var holder: java.util.concurrent.Future<*>?=null
        var closeAttempted=false;private set
        var joined=false;private set
        fun hold(): Gate {
            check(Looper.myLooper()!=Looper.getMainLooper());check(holder==null&&!closeAttempted)
            holder=executor.submit {
                check(Looper.myLooper()!=Looper.getMainLooper())
                storage.operationTransaction {
                    check(lock.isHeldByCurrentThread&&lock.isFair);held.countDown()
                    check(release.await(30,TimeUnit.SECONDS)) {"Owned storage gate deadline"}
                }
            }
            try {check(held.await(10,TimeUnit.SECONDS));check(lock.isLocked)}
            catch(error: Throwable) {try{close()}catch(exit: Throwable){error.addSuppressed(exit)};throw error}
            return this
        }
        fun awaitQueued(count: Int,prior: Set<Thread> = emptySet()): Set<Thread> {
            check(Looper.myLooper()!=Looper.getMainLooper())
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(true) {
                check(release.count==1L&&!checkNotNull(holder).isDone&&lock.isLocked)
                @Suppress("UNCHECKED_CAST") val waiting=(queued.invoke(lock) as Collection<Thread>).toSet()
                check(waiting.size<=count) {"Unexpected writer on fixture's private lock"}
                if(waiting.size==count&&waiting.containsAll(prior)&&waiting.all(lock::hasQueuedThread))return waiting
                check(System.nanoTime()<deadline) {"Actual storage queue membership not observed"}
                Thread.yield() // scheduling only; success requires the real lock's exact queued threads
            }
        }
        fun requireQueued(threads: Set<Thread>) {check(release.count==1L&&threads.all(lock::hasQueuedThread))}
        override fun close() {
            check(Looper.myLooper()!=Looper.getMainLooper())
            if(closeAttempted)return
            closeAttempted=true;release.countDown();executor.shutdown()
            var failure: Throwable?=null
            try {holder?.get(10,TimeUnit.SECONDS)}catch(error: Throwable){failure=error}
            try {
                joined=executor.awaitTermination(10,TimeUnit.SECONDS)
                if(!joined)System.err.println("P7_READING_STORE_EPOCH_RETAINED fixture=$fixtureId phase=gate-exit holderJoined=false")
                check(joined) {"Owned gate holder exit unknown; preserve fixture/key"}
            }catch(error: Throwable){if(failure==null)failure=error else if(failure!==error)failure.addSuppressed(error)}
            failure?.let {throw it}
        }
    }
    private inner class Fixture(private val caseName: String) {
        val id=UUID.randomUUID().toString();val alias="agent-hub-reading-store-epoch-"+id
        private val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root: File
        val storage: NativeSessionStorage
        private val parent=SupervisorJob()
        private val scope=CoroutineScope(parent+Dispatchers.Main.immediate)
        private val gates=mutableListOf<Gate>()
        val changes=AtomicInteger()
        val phone: NativePhoneReadingStore
        val ledger: NativeReadingLedger
        lateinit var original: String
        lateinit var originalCipher: ByteArray
        lateinit var projection: NativeUnreadSnapshot
        lateinit var originalRow: String
        lateinit var pending: NativePendingRead
        var bodyPassed=false
        var workersJoined=false;private set
        var storageRetired=false;private set
        private var finishAttempted=false
        val cleanup=NativeReadingFixtureCleanup {sequence,phase,states->
            immutable("cleanup-%02d.json".format(sequence),JSONObject().put("version",1).put("case",caseName)
                .put("fixture",id).put("alias",alias).put("phase",phase).put("states",JSONObject(states))
                .put("completionMissingMeansUnknown",true).put("ownedJobsJoined",workersJoined)
                .put("storageRetired",storageRetired).put("nativeStoreComponentAssertionsPassed",bodyPassed)
                .put("interactiveUiProof",false).toString().toByteArray())
        }
        init {
            offMain();require(base.packageName=="com.easyai.agenthub.android.acceptance"&&base.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0)
            root=File(base.noBackupFilesDir,"reading-store-epoch-"+id);check(root.mkdir());Os.chmod(root.path,448)
            val local=object: ContextWrapper(base) {
                override fun getApplicationContext(): Context=this
                override fun getNoBackupFilesDir()=root
            }
            try {
                storage=NativeSessionStorage(local,"ledger",alias);Os.chmod(File(root,"ledger").path,448)
                phone=main {NativePhoneReadingStore(storage,scope){changes.incrementAndGet()}}
                ledger=checkNotNull(phone.ledger)
            }catch(error: Throwable) {
                parent.cancel();System.err.println("P7_READING_STORE_EPOCH_RETAINED fixture=$id phase=initialization originalBaselineCaptured=false")
                throw error
            }
        }
        private val cipher get()=File(File(root,"ledger"),sha(NativeReadingLedger.STORAGE_NAME.toByteArray())+".bin")
        fun cipherBytes(): ByteArray {
            offMain();val fd=Os.open(cipher.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0)
            try {
                val before=Os.fstat(fd)
                require(OsConstants.S_ISREG(before.st_mode)&&before.st_uid==Process.myUid()&&before.st_mode==33152&&before.st_nlink==1L&&before.st_size in 1L..65536L)
                val bytes=ByteArray(before.st_size.toInt());var at=0
                while(at<bytes.size){val n=Os.read(fd,bytes,at,bytes.size-at);check(n>0);at+=n}
                check(Os.read(fd,ByteArray(1),0,1)==0)
                for(after in listOf(Os.fstat(fd),Os.lstat(cipher.path)))check(after.st_dev==before.st_dev&&after.st_ino==before.st_ino&&
                    after.st_size==before.st_size&&after.st_mode==before.st_mode&&after.st_uid==before.st_uid&&after.st_nlink==1L&&
                    after.st_mtime==before.st_mtime&&after.st_ctime==before.st_ctime)
                return bytes
            }finally{Os.close(fd)}
        }
        private fun immutable(name: String,data: ByteArray) {
            offMain();val file=File(root,name)
            val fd=Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,384)
            FileOutputStream(fd).use {out->out.write(data);out.fd.sync()}
            NativeReadingFixtureFiles.syncDirectory(root)
        }
        fun seed() {
            offMain();ledger.reconcile(listOf(a,b),listOf(value(a,1),value(b,1)));ledger.receive(question)
            val view=NativeReadingLifecycle().apply {foreground(true);uncovered(true);present(a,"thread",position(1),setOf(question.id))}
            ledger.readVisible(a,"thread",reader,view,checkNotNull(view.visible(a,"thread",position(1).messageId,"owner-reply-1","completed",true)))
            ledger.readVisible(a,"thread",reader,view,checkNotNull(view.visibleNotices(a,"thread",setOf(question.id))))
            pending=ledger.pendingReads().single();assertEquals(position(1),pending.position)
            projection=ledger.reconcile(listOf(b),emptyList()) // removed scope's row/read/question/ack remain
            assertEquals(1,projection.count);assertFalse(projection.isUnread(a,"thread"));assertTrue(projection.isUnread(b,"thread"))
            original=checkNotNull(storage.read(NativeReadingLedger.STORAGE_NAME));originalCipher=cipherBytes();originalRow=row(original,a)
            immutable("original-ledger.txt",original.toByteArray());immutable("original-ledger.bin",originalCipher)
            main {phone.publish(projection);assertEquals(projection,phone.snapshot);assertTrue(phone.ready)}
        }
        fun submit(scopes: List<TargetScope>,values: List<NativeReadingSnapshot>): Job=main {
            val before=parent.children.toSet()
            phone.reconcile(scopes,values,emptyList())
            parent.children.single {it !in before}
        }
        fun coalesce(scopes: List<TargetScope>,values: List<NativeReadingSnapshot>)=main {phone.reconcile(scopes,values,emptyList())}
        fun resetAndRestoreProjection(old: Job,waiting: Set<Thread>,gate: Gate) {
            main {
                phone.reset();assertTrue(old.isCancelled);assertFalse(old.isCompleted)
                assertFalse(phone.ready);assertEquals(0,phone.snapshot.count)
                phone.publish(projection);phone.focus(ThreadReadingIdentity.key(b,"thread"))
                assertTrue(phone.ready);assertEquals(projection,phone.snapshot)
            }
            gate.requireQueued(waiting) // cancellation did not remove the actual synchronous IO waiter
        }
        fun originalUnchanged() {
            offMain();assertEquals(original,storage.read(NativeReadingLedger.STORAGE_NAME))
            val actual=cipherBytes();try{assertArrayEquals(originalCipher,actual)}finally{actual.fill(0)}
            assertEquals(originalRow,row(checkNotNull(storage.read(NativeReadingLedger.STORAGE_NAME)),a))
            assertTrue(ledger.pendingReads().isEmpty());assertTrue(ledger.notices(a,"thread").isEmpty())
            main {assertEquals(projection,phone.snapshot);assertTrue(phone.ready);assertFalse(phone.busy);assertNull(phone.failure)}
            assertEquals(0,changes.get())
        }
        fun currentOwnerPreserved(n: Long,expectedChanges: Int) {
            offMain();val saved=checkNotNull(storage.read(NativeReadingLedger.STORAGE_NAME))
            assertEquals(originalRow,row(saved,a));assertTrue(ledger.notices(a,"thread").isEmpty());assertTrue(ledger.pendingReads().isEmpty())
            val actual=ledger.snapshot();assertEquals(1,actual.count);assertFalse(actual.isUnread(a,"thread"));assertTrue(actual.isUnread(b,"thread"))
            main {assertEquals(actual,phone.snapshot);assertTrue(phone.ready);assertFalse(phone.busy);assertNull(phone.failure)
                assertEquals(position(n),phone.heads[ThreadReadingIdentity.key(b,"thread")])}
            assertEquals(expectedChanges,changes.get())
        }
        fun reconnectRemovedScope() {
            join(submit(listOf(a,b),emptyList()))
            assertEquals(originalRow,row(checkNotNull(storage.read(NativeReadingLedger.STORAGE_NAME)),a))
            val kept=ledger.pendingReads().single();assertEquals(pending.position,kept.position);assertEquals(pending.readerId,kept.readerId)
            assertEquals(ThreadReadingIdentity.key(pending.target,pending.threadId),ThreadReadingIdentity.key(kept.target,kept.threadId))
            val before=storage.read(NativeReadingLedger.STORAGE_NAME)
            assertEquals(1,ledger.receive(question).count);assertFalse(ledger.mayPresent(question))
            assertEquals(before,storage.read(NativeReadingLedger.STORAGE_NAME))
            val actual=ledger.snapshot();main {assertEquals(actual,phone.snapshot);assertEquals(1,phone.snapshot.count)}
        }
        fun gate(): Gate {offMain();val gate=Gate(storage,id);gates.add(gate);return gate.hold()}
        fun stop(): List<Throwable> {
            offMain();parent.cancel()
            val errors=mutableListOf<Throwable>()
            gates.filter { !it.closeAttempted }.forEach {try{it.close()}catch(error: Throwable){errors.add(error)}}
            val jobs=try {runBlocking {withTimeout(15_000){parent.join()}};parent.isCompleted}
                catch(error: Throwable) {errors.add(error);false}
            workersJoined=jobs&&gates.all {it.joined}
            if(workersJoined)try {storage.retireWriter();storageRetired=true}catch(error: Throwable){errors.add(error)}
            else if(errors.isEmpty())errors.add(IllegalStateException("Owned Store physical exit unknown; fixture cleanup not attempted"))
            return errors
        }
        fun finish(name: String) {
            offMain();check(!finishAttempted&&bodyPassed&&workersJoined&&storageRetired);finishAttempted=true
            val directory=File(root,"ledger")
            require(root.canonicalFile==File(base.noBackupFilesDir.canonicalFile,root.name)&&directory.canonicalFile==File(root.canonicalFile,"ledger"))
            require(root.list()?.toSet()==setOf("ledger","original-ledger.txt","original-ledger.bin")&&directory.list()?.toSet()==setOf(cipher.name))
            for(dir in listOf(root,directory)){val s=Os.lstat(dir.path);require(s.st_uid==Process.myUid()&&s.st_mode==16832&&!Files.isSymbolicLink(dir.toPath()))}
            for(file in listOf(cipher,File(root,"original-ledger.txt"),File(root,"original-ledger.bin"))){val s=Os.lstat(file.path)
                require(s.st_uid==Process.myUid()&&s.st_mode==33152&&s.st_nlink==1L&&!Files.isSymbolicLink(file.toPath()))}
            cleanup.step("cipher","removed") {Files.delete(cipher.toPath())}
            cleanup.step("directory","removed") {Files.delete(directory.toPath())}
            cleanup.step("alias","removed") {
                val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)};keys.deleteEntry(alias);require(!keys.containsAlias(alias))
            }
            cleanup.step("result","written") {immutable("result.json",JSONObject().put("version",1).put("case",name).put("fixture",id)
                .put("originalLedgerSha256",sha(original.toByteArray())).put("originalCipherSha256",sha(originalCipher))
                .put("ownedJobsJoined",true).put("executionNamespaceRemoved",true).put("syntheticAliasRemoved",true)
                .put("nativeStoreComponentAssertionsPassed",true).put("parentCaptured",false).put("interactiveUiProof",false)
                .put("taskId","android-parallel-p7-reading-voice-20261028").put("purpose","Root capture of exact synthetic original evidence")
                .put("releaseCondition","Root independently captures originals and result, then removes this exact evidence fixture")
                .put("reviewAtMs",System.currentTimeMillis()+21_600_000L).toString().toByteArray())}
            System.err.println("P7_READING_STORE_EPOCH_CAPTURE case=$name fixture=$id originalLedger=original-ledger.txt originalCipher=original-ledger.bin result=result.json")
            originalCipher.fill(0)
        }
    }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun row(text: String,target: TargetScope): String {
        val rows=JSONObject(text).getJSONArray("rows");val key=ThreadReadingIdentity.key(target,"thread")
        return (0 until rows.length()).map {rows.getJSONObject(it)}.single {
            it.getString("threadId")=="thread"&&ThreadReadingIdentity.key(checkNotNull(HostReadCodec.target(it.getJSONObject("target"))),"thread")==key
        }.toString()
    }
    private fun fixture(name: String,scenario: (Fixture)->Unit) {
        offMain();val fixture=Fixture(name);var failure: Throwable?=null
        try {fixture.seed();scenario(fixture);fixture.bodyPassed=true}catch(error: Throwable){failure=error}
        for(error in fixture.stop()) {if(failure==null)failure=error else if(failure!==error)failure.addSuppressed(error)}
        if(failure==null)try {fixture.finish(name)}catch(error: Throwable){failure=error}
        if(failure!=null) {
            // RETAINED identifies the evidence directory, never a claim that a deleted key survived.
            System.err.println("P7_READING_STORE_EPOCH_RETAINED case=$name fixture=${fixture.id} alias=${fixture.alias} workersJoined=${fixture.workersJoined} storageRetired=${fixture.storageRetired} bodyPassed=${fixture.bodyPassed} cleanupPhase=${fixture.cleanup.phase} cleanupStates=${JSONObject(fixture.cleanup.snapshot())} originalLedger=original-ledger.txt originalCipher=original-ledger.bin")
            throw failure
        }
    }
    @Test fun actualQueuedStoreResetPreservesOriginalLedgerAndRestoredProjection()=fixture("reset-original") {f->
        val old: Job
        f.gate().use {gate->
            old=f.submit(listOf(a),listOf(obsoleteValue()))
            val waiting=gate.awaitQueued(1);f.resetAndRestoreProjection(old,waiting,gate)
        }
        join(old);f.originalUnchanged()
        join(f.submit(listOf(b),listOf(value(b,2))));f.currentOwnerPreserved(2,1);f.reconnectRemovedScope()
    }
    @Test fun actualNewStoreOwnerCoalescesWhileCancelledOldIoStillWaits()=fixture("reset-overlap") {f->
        val old: Job;val current: Job
        f.gate().use {gate->
            old=f.submit(listOf(a),listOf(obsoleteValue()))
            val first=gate.awaitQueued(1);f.resetAndRestoreProjection(old,first,gate)
            current=f.submit(listOf(b),listOf(value(b,2)));val both=gate.awaitQueued(2,first)
            f.coalesce(listOf(b),listOf(value(b,3)));gate.requireQueued(both)
        }
        join(old);join(current);f.currentOwnerPreserved(3,2);f.reconnectRemovedScope()
    }
}
