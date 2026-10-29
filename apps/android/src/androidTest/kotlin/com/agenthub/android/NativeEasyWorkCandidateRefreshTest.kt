package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Actual Session/storage read-return race, not discovery/network/UI/real issuer acceptance.
 * The test owns one fresh encrypted namespace; it never opens Main or a transport.
 */
class NativeEasyWorkCandidateRefreshTest {
    @get:Rule val method=org.junit.rules.TestName()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var store: NativeSessionStorage
    private lateinit var session: MobileSession
    private lateinit var folder: File
    private lateinit var client: MobileClient
    private lateinit var binding: EasyWorkBinding
    private lateinit var originalCredential: String
    private lateinit var journalKey: String
    private lateinit var journalRaw: String
    private lateinit var build: JSONObject
    private var defaults=emptyMap<String,String>()
    private val id=UUID.randomUUID().toString()
    private val namespace="p8-candidate-refresh-$id"
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val old=AgentCandidate("old","lan-wss","https://192.168.42.1:7443","a".repeat(64))
    private val next=AgentCandidate("next","lan-wss","https://192.168.42.2:7443","a".repeat(64))
    private val relay=AgentCandidate("relay","relay-http","https://relay.invalid")
    private val originalText="Unsubmitted isolated draft "+UUID.randomUUID()
    private fun sha(file: File)=file.inputStream().use {input->val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val size=input.read(buffer);if(size<0)break;digest.update(buffer,0,size)};digest.digest().joinToString("") {"%02x".format(it)}}
    private fun defaults(): Map<String,String> {
        val base=i.targetContext.noBackupFilesDir.toPath();if(!Files.exists(base))return emptyMap()
        val result=linkedMapOf<String,String>();var roots=0
        Files.newDirectoryStream(base,"mobile-v1*").use {entries->for(root in entries) {
            val name=root.fileName.toString();if(name!="mobile-v1"&&!name.startsWith("mobile-v1-"))continue
            check(++roots<=64);check(!Files.isSymbolicLink(root))
            Files.walk(root).use {walk->val iterator=walk.iterator();while(iterator.hasNext()) {
                val path=iterator.next();check(!Files.isSymbolicLink(path));if(!Files.isRegularFile(path))continue
                check(result.size<1024);result[base.relativize(path).toString()]=sha(path.toFile())
            }}
        }}
        check(easyCanonical(JSONObject(result)).toByteArray().size<=262144)
        return result
    }
    private fun <T> main(block: suspend ()->T): T=runBlocking {withContext(Dispatchers.Main) {block()}}
    @Suppress("UNCHECKED_CAST") private fun clients()=MobileSession::class.java.getDeclaredField("realClients").apply {isAccessible=true}.get(session) as MutableList<MobileClient>
    @Suppress("UNCHECKED_CAST") private fun revisions()=MobileSession::class.java.getDeclaredField("revisions").apply {isAccessible=true}.get(session) as MutableMap<String,Long>
    private fun persist() {MobileSession::class.java.getDeclaredMethod("persistMetadata").apply {isAccessible=true}.invoke(session)}
    private fun live()=clients().single {it.id==client.id}
    private fun noRuntimeSession() {
        val pool=MobileSession::class.java.getDeclaredField("easyWorkSessions").apply {isAccessible=true}.get(session)!!
        val entries=EasyWorkSessions::class.java.getDeclaredField("sessions").apply {isAccessible=true}.get(pool) as Map<*,*>
        assertTrue(entries.isEmpty());assertTrue(session.selectedClient.isDemo)
    }
    @Before fun setup()=runBlocking {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8CandidateRefreshBuild")))
        for(key in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(key).matches(Regex("[a-f0-9]{64}")))
        assertEquals(build.getString("appSha256"),sha(File(i.targetContext.applicationInfo.sourceDir)));assertEquals(build.getString("runnerSha256"),sha(File(i.context.applicationInfo.sourceDir)))
        defaults=defaults() // Complete protected default families or fail before creating owned data.
        folder=File(i.targetContext.filesDir,"acceptance/p8-candidate-refresh/$id");check(!folder.exists()&&folder.mkdirs())
        store=NativeSessionStorage(i.targetContext,namespace,namespace)
        val vector=JSONObject(i.context.assets.open("easywork-crypto-vector.json").bufferedReader().use {it.readText()})
        originalCredential=vector.getJSONObject("credential").toString();binding=EasyWorkCredential.decode(JSONObject(originalCredential)).binding
        val connection=AgentConnection(binding.target.scope(),listOf(old,relay),"ref-original")
        client=MobileClient("synthetic-original","Original",connection.target,old.endpoint,false,"easywork-runtime",false,agentConnection=connection)
        withContext(Dispatchers.IO) {store.write("easywork-credential:ref-original",originalCredential);store.write("easywork-credential:ref-rotated",originalCredential);store.write("protected-synthetic-grant","immutable synthetic authorization record")}
        main {session=MobileSession(store);session.resetAcceptance();session.leaveForeground();clients().add(client);revisions()[client.id]=0;session.text=originalText;session.connectionBusy=true;persist()}
        val journal=NativeOperationJournal(store)
        val record=journal.prepare(client,"original-thread","reply",ReplyIntent(client.id,"original-thread",UUID.randomUUID().toString(),"synthetic immutable original input",emptyList(),"next-turn").body())
        journal.markSubmitting(record.target,record.id);journal.uncertain(record.target,record.id,"synthetic unknown original acknowledgement")
        journalKey="operations:${record.target}";journalRaw=requireNotNull(store.read(journalKey));store.awaitQueuedWrites();main {noRuntimeSession()}
    }
    /** The actual storage read queues on its existing fair IO lock. No production test hook. */
    private fun heldRead(change: ()->Unit): Boolean=runBlocking {
        store.awaitQueuedWrites()
        val lock=NativeSessionStorage::class.java.getDeclaredField("ioLock").apply {isAccessible=true}.get(store) as ReentrantLock
        val held=CountDownLatch(1);val release=CountDownLatch(1)
        val owner=async(Dispatchers.IO) {store.operationTransaction {check(lock.queueLength==0);held.countDown();check(release.await(5,TimeUnit.SECONDS))}}
        var update: Deferred<Boolean>?=null
        try {
            check(held.await(2,TimeUnit.SECONDS))
            update=main {tasks.async(start=CoroutineStart.UNDISPATCHED) {session.updateEasyWorkCandidates(client,binding,listOf(next))}}
            withTimeout(2000) {while(lock.queueLength==0)delay(5)}
            // At this point the method captured its old connection; its read is first in the fair queue.
            main {change()};release.countDown();withTimeout(5000) {owner.await();requireNotNull(update).await()}
        } finally {release.countDown();withTimeout(5000) {owner.join();update?.join()}}
    }
    @Test(timeout=25_000) fun removedSavedConnectionCannotBeResurrectedByDelayedDiscovery() {
        assertFalse(heldRead {session.removeClient(client.id)})
        assertTrue(main {clients().none {it.id==client.id}})
    }
    @Test(timeout=25_000) fun preferenceRoundTripCannotReactivateTheOldProbe() {
        assertFalse(heldRead {session.setEasyWorkPreference(client,"relay-only");session.setEasyWorkPreference(client,"automatic")})
        assertEquals(listOf(old,relay),main {live().agentConnection!!.candidates})
    }
    @Test(timeout=25_000) fun newerCredentialReferenceCannotBeReplacedByTheOldProbe() {
        assertFalse(heldRead {val index=clients().indexOfFirst {it.id==client.id};clients()[index]=live().copy(agentConnection=live().agentConnection!!.copy(credentialRef="ref-rotated"));revisions()[client.id]=1;persist()})
        assertEquals("ref-rotated",main {live().agentConnection!!.credentialRef})
    }
    @Test(timeout=25_000) fun validRouteRepairPreservesConcurrentNameOnlineAndRouteChanges() {
        val concurrent=AgentCandidate("concurrent","lan-wss","https://192.168.42.3:7443","a".repeat(64))
        assertTrue(heldRead {session.renameEasyWork(client,"Human renamed");val index=clients().indexOfFirst {it.id==client.id};clients()[index]=live().copy(online=true,agentConnection=live().agentConnection!!.copy(candidates=listOf(concurrent)+live().agentConnection!!.candidates));persist()})
        main {assertEquals("Human renamed",live().name);assertTrue(live().online);assertEquals(listOf(next,concurrent,old,relay),live().agentConnection!!.candidates)}
    }
    @Test(timeout=25_000) fun unchangedConnectionRepairsOnlyRoutesAndPersistsItsOriginalPointer()=runBlocking {
        assertTrue(main {session.updateEasyWorkCandidates(client,binding,listOf(next))});store.awaitQueuedWrites()
        main {assertEquals(listOf(next,old,relay),live().agentConnection!!.candidates);assertEquals("ref-original",live().agentConnection!!.credentialRef);assertTrue(sameScope(client.scope,live().scope));assertEquals("automatic",live().agentConnection!!.preference)}
        val saved=JSONObject(requireNotNull(store.read("metadata"))).getJSONArray("clients").objects().single();assertEquals("ref-original",saved.getJSONObject("agentConnection").getString("credentialRef"))
    }
    @After fun finish()=runBlocking {
        if(!::session.isInitialized)return@runBlocking
        main {session.leaveForeground();noRuntimeSession()};tasks.cancel();store.awaitQueuedWrites()
        assertEquals(originalText,main {session.text});assertTrue(main {session.connectionBusy});assertEquals(journalRaw,store.read(journalKey));assertEquals("immutable synthetic authorization record",store.read("protected-synthetic-grant"));assertEquals(originalCredential,store.read("easywork-credential:ref-rotated"));assertEquals(defaults,defaults())
        val proof=JSONObject().put("build",build).put("caseId",id).put("namespace",namespace).put("method",method.methodName).put("pid",android.os.Process.myPid()).put("defaultFilesUnchanged",true).put("originalUnknownJournalSha256",easyHash(journalRaw.toByteArray())).put("unknownNotReplayed",true).put("selectedDraftAndBusyUnchanged",true).put("transportPoolEmptyAtCheckpoints",true).put("networkOrModelAcceptance",false).put("metadataSha256",store.read("metadata")?.let {easyHash(it.toByteArray())}).put("scope","Actual local Session/storage fixture; not network discovery/pairing/UI/real Desktop acceptance")
        val file=File(folder,"local-proof.json");check(!file.exists());file.writeText(easyCanonical(proof))
    }
}
