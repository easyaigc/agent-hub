package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Actual encrypted storage + production ownership gate, with bounded test-owned IO holds.
 * No Activity launch, issuer, Host, model, credential replay or default-store writes.
 */
class NativeEasyWorkWriteGateTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(45)
    @get:Rule val method=org.junit.rules.TestName()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val id=UUID.randomUUID().toString()
    private lateinit var store: NativeSessionStorage
    private lateinit var session: MobileSession
    private lateinit var folder: File
    private lateinit var journalKey: String
    private lateinit var journal: String
    private var defaults=emptyMap<String,String>()
    private fun hash(file: File)=file.inputStream().use {input->val d=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString("") {"%02x".format(it)}}
    private fun defaultFiles()=i.targetContext.noBackupFilesDir.listFiles().orEmpty().filter {it.name=="mobile-v1"||it.name.startsWith("mobile-v1-")}.flatMap {root->root.walkTopDown().filter {it.isFile}.toList()}.associate {it.relativeTo(i.targetContext.noBackupFilesDir).path to hash(it)}
    private fun <T> main(block: ()->T): T {var result: Result<T>?=null;i.runOnMainSync {result=runCatching(block)};return result!!.getOrThrow()}
    @Before fun setup() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        val build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PairingUiBuild")))
        for(k in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(k).matches(Regex("[a-f0-9]{64}")))
        assertEquals(build.getString("appSha256"),hash(File(i.targetContext.applicationInfo.sourceDir)));assertEquals(build.getString("runnerSha256"),hash(File(i.context.applicationInfo.sourceDir)))
        defaults=defaultFiles();store=NativeSessionStorage(i.targetContext,"p8-write-gate-$id","p8-write-gate-$id")
        folder=File(i.targetContext.filesDir,"acceptance/p8-write-gate/$id").apply {check(!exists());check(mkdirs())}
        main {session=MobileSession(store);session.resetAcceptance();session.text="original unsent draft"}
        store.write("easywork-device-grant:original","original synthetic grant");store.write("easywork-device-claim:original","original synthetic unknown claim")
        val target=TargetScope("easywork","https://synthetic.invalid","synthetic-account","synthetic-host","synthetic-workspace","synthetic-runtime")
        val client=MobileClient("synthetic-client","Synthetic",target,"https://not-used.invalid",false,"easywork-runtime",false,agentConnection=AgentConnection(target,listOf(AgentCandidate("unused","relay-http","https://not-used.invalid")),"unused"))
        val record=runBlocking {val j=NativeOperationJournal(store);val r=j.prepare(client,"synthetic-thread","reply",ReplyIntent(client.id,"synthetic-thread",UUID.randomUUID().toString(),"synthetic immutable input",emptyList(),"next-turn").body());j.markSubmitting(r.target,r.id);j.uncertain(r.target,r.id,"original unknown acknowledgement")}
        journalKey="operations:${record.target}";journal=store.read(journalKey)!!
    }
    @After fun finish() {
        if(::session.isInitialized)main {session.leaveForeground()}
        if(::store.isInitialized){assertEquals("original synthetic grant",store.read("easywork-device-grant:original"));assertEquals("original synthetic unknown claim",store.read("easywork-device-claim:original"));if(::journal.isInitialized)assertEquals(journal,store.read(journalKey))}
        if(::store.isInitialized)assertEquals(defaults,defaultFiles())
    }
    private fun obstruct(afterActualWrite: Boolean) {
        val pool=Executors.newFixedThreadPool(3);val held=CountDownLatch(1);val release=CountDownLatch(1);val revoked=CountDownLatch(1);val thread=AtomicReference<Thread>()
        val key="easywork-credential:isolated-candidate";store.write(key,"original mapping")
        val form=main {easyWorkConnectionForm(session)};val action=main {form.begin(EasyWorkConnectionInput("save","Synthetic","","public"))};val target=main {session.targetKey}
        var blocker: Future<*>?=null;var worker: Future<*>?=null;var revoker: Future<*>?=null
        try {
            if(!afterActualWrite) {blocker=pool.submit {store.operationTransaction {held.countDown();check(release.await(8,TimeUnit.SECONDS))}};assertTrue(held.await(2,TimeUnit.SECONDS))}
            worker=pool.submit {
                thread.set(Thread.currentThread())
                if(afterActualWrite)store.operationTransaction {
                    EasyWorkConnectionWriteGate().write(store.coordinationKey+"\n"+key,"candidate",action,{store.read(key)}) {value->
                        if(value==null)store.remove(key)else store.write(key,value)
                        if(value=="candidate"){held.countDown();check(release.await(8,TimeUnit.SECONDS))}
                    }
                } else EasyWorkConnectionCredentialWrites.write(store,key,"candidate",action)
            }
            if(afterActualWrite)assertTrue(held.await(2,TimeUnit.SECONDS))else {
                // Kotlin internal functions use the actual module-mangled JVM name.
                val transaction=NativeSessionStorage::class.java.declaredMethods.single {it.name.substringBefore('$')=="operationTransaction"&&it.parameterTypes.size==1}.name
                val until=android.os.SystemClock.elapsedRealtime()+2_000
                var observed=emptyList<StackTraceElement>();var state=Thread.State.NEW
                fun waiting(): Boolean {
                    val writer=thread.get()?:return false;observed=writer.stackTrace.toList();state=writer.state
                    return observed.any {it.className==NativeSessionStorage::class.java.name&&it.methodName==transaction}&&
                        observed.any {it.className==EasyWorkConnectionCredentialWrites::class.java.name&&it.methodName=="write"}&&
                        observed.any {it.className.startsWith("java.util.concurrent.locks.")}&&state in setOf(Thread.State.WAITING,Thread.State.BLOCKED)
                }
                try {while(!waiting()) {check(android.os.SystemClock.elapsedRealtime()<until) {"Actual native writer did not reach the held storage transaction"};Thread.sleep(10)}}
                finally {File(folder,"storage-wait-stack.json").writeText(JSONObject().put("declaredTransactionJvmName",transaction).put("threadState",state.name).put("releaseStillHeld",release.count==1L).put("storageHolderEntered",held.count==0L)
                    .put("frames",org.json.JSONArray(observed.take(40).map {it.className+"."+it.methodName})).put("argumentsOrDataCaptured",false).toString())}
                assertFalse(worker.isDone);assertEquals(1L,release.count)
            }
            revoker=pool.submit {main {session.leaveForeground();form.close();session.text="newer unsent draft"};revoked.countDown()}
            assertTrue("Main revocation and draft edit return before held IO is released",revoked.await(1,TimeUnit.SECONDS));assertFalse(worker.isDone)
            release.countDown();val failure=assertThrows(ExecutionException::class.java) {worker.get(5,TimeUnit.SECONDS)};assertTrue(failure.cause is EasyWorkConnectionAbandoned)
            blocker?.get(5,TimeUnit.SECONDS);revoker.get(5,TimeUnit.SECONDS)
            assertEquals("original mapping",store.read(key))
            main {assertEquals(target,session.targetKey);assertEquals("newer unsent draft",session.text);assertTrue(session.clients.isEmpty());assertFalse(action.currentForUi());assertThrows(EasyWorkConnectionAbandoned::class.java) {action.commit {fail("Old result must not select or replace a client")}}}
            File(folder,"slow-io-observations.json").writeText(JSONObject().put("method",method.methodName).put("hold",if(afterActualWrite)"production gate after actual encrypted write" else "actual native credential wrapper waiting on storage transaction")
                .put("mainReturnedBeforeIoRelease",true).put("newDraftPreserved",true).put("oldResultNotActivated",true).put("originalMappingRestored",true).put("originalGrantClaimAndUnknownJournalPreserved",true).put("networkOrModelCalls",0).put("observationsOnly",true).toString())
        }finally {release.countDown();runCatching {worker?.get(6,TimeUnit.SECONDS)};runCatching {blocker?.get(6,TimeUnit.SECONDS)};runCatching {revoker?.get(6,TimeUnit.SECONDS)};pool.shutdownNow()}
    }
    @Test fun mainRevocationReturnsWhileActualCredentialWriterWaitsForStorageTransaction()=obstruct(false)
    @Test fun mainRevocationAfterActualEncryptedWriteRollsBackWithoutSelectingOldResult()=obstruct(true)
}
