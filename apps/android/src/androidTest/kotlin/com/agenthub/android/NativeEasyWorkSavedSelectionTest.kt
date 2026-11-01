package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestName

/** SOURCE-ONLY until separately compiled/admitted. Actual Session and production form with
 * typed fixture read/command ports; this is not issuer/desktop/model or physical-device proof.
 * ComponentActivity is the established isolated Compose harness, never MainActivity.
 */
class NativeEasyWorkSavedSelectionTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var run: String
    private lateinit var build: JSONObject
    @get:Rule(order=0) val identity=object: ExternalResource() { override fun before() {
        require(instrumentation.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        require(instrumentation.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        run=requireNotNull(InstrumentationRegistry.getArguments().getString("p8SavedSelectionRun"))
        require(UUID.fromString(run).toString()==run)
        build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8SavedSelectionBuild")))
        require(build.keys().asSequence().toSet()==setOf("sourceSha256","appSha256","runnerSha256"))
        for(key in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(key).matches(Regex("[a-f0-9]{64}")))
        assertEquals(build.getString("appSha256"),sha(File(instrumentation.targetContext.applicationInfo.sourceDir)))
        assertEquals(build.getString("runnerSha256"),sha(File(instrumentation.context.applicationInfo.sourceDir)))
    } }
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order=2) val method=TestName()
    @get:Rule(order=3) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(90)

    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun sha(file: File)=file.inputStream().use {input->
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65_536)
        while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
        digest.digest().joinToString("") {"%02x".format(it)}
    }
    private fun <T> main(block: ()->T): T {
        var result: Result<T>?=null
        instrumentation.runOnMainSync {result=runCatching(block)}
        return requireNotNull(result).getOrThrow()
    }
    private fun field(name: String)=MobileSession::class.java.getDeclaredField(name).apply {isAccessible=true}

    private data class Read(val client: MobileClient,val input: EasyWorkConnectionInput?,val projectCursor: String?,val threadCursor: String?)
    private class ReadGate {
        val entered=CompletableDeferred<Read>()
        val release=CompletableDeferred<Unit>()
        val claimed=AtomicBoolean()
    }
    private class Reads: HostReads {
        val requests=CopyOnWriteArrayList<Read>()
        var gate: ReadGate?=null
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
            val request=Read(client,currentCoroutineContext()[EasyWorkConnectionActionLease]?.input,projectCursor,threadCursor)
            requests.add(request)
            val original=gate
            if(request.input?.kind=="saved"&&original!=null&&original.claimed.compareAndSet(false,true)) {
                original.entered.complete(request)
                // A deterministic late adapter return after caller invalidation, with no network.
                withContext(NonCancellable+Dispatchers.IO) {original.release.await()}
            }
            val wire=JSONObject().put("hostId",client.id).put("name",client.name).put("connectionMode",client.mode)
                .put("readTarget",encodeTarget(requireNotNull(client.scope))).put("projects",JSONArray()).put("threads",JSONArray())
                .put("capabilities",JSONArray()).toString()
            return MobileWorkspace(client.id,client.name,client.mode,emptyList(),emptyList(),null,null,client.scope,emptyList(),wire)
        }
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=
            MobileHistory(client.id,threadId,emptyList(),null,client.scope,"idle",null,"{}")
    }
    /** No delegated NativeCommands fallback: every unexpected operation terminates locally. */
    private class Commands: CodexCommands {
        val invoked=CopyOnWriteArrayList<String>()
        private fun denied(name: String): Nothing {invoked.add(name);error("Fixture forbids operation: $name")}
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
        override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch=denied("events")
        override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt=denied("send")
        override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt=denied("receipt")
        override suspend fun createThread(client: MobileClient,projectId: String?,operationId: String): MobileThread=denied("createThread")
        override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation=denied("prepareCreation")
        override suspend fun creation(client: MobileClient,operationId: String,inspectOnly: Boolean): DesktopCreation=denied("creation")
        override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation=denied("submitCreation")
        override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation=denied("detachCreation")
        override suspend fun open(client: MobileClient,threadId: String,operationId: String,queryOnly: Boolean): DesktopOpen=denied("open")
        override suspend fun stop(client: MobileClient,threadId: String,turnId: String): Unit=denied("stop")
        override suspend fun cancelQueue(client: MobileClient,threadId: String,operationId: String): Unit=denied("cancelQueue")
        override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>): Unit=denied("answer")
        override suspend fun upload(client: MobileClient,threadId: String,file: CreationFile,bytes: ByteArray): MobileFile=denied("upload")
        override suspend fun uploadCreation(client: MobileClient,operationId: String,file: CreationFile,bytes: ByteArray): Unit=denied("uploadCreation")
    }

    private inner class Fixture(val label: String,val online: Boolean) {
        val id=UUID.nameUUIDFromBytes((run+":"+method.methodName+":"+label).toByteArray()).toString()
        val folder=File(instrumentation.targetContext.filesDir,"acceptance/P8-saved-selection-$id")
        val alias="p8-saved-selection-$id"
        val reads=Reads();val commands=Commands()
        val caller=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val waiting=mutableListOf<Deferred<Result<MobileClient>>>()
        val forms=mutableListOf<EasyWorkConnectionAuthority.Form>()
        val source=MobileClient("p8-source-$id","Original source",TargetScope("codex",null,null,"p8-source-$id","workspace-source","runtime-source"),"https://source.invalid",true,"desktop-runtime",false,requiresReadTarget=true)
        val other=MobileClient("p8-other-$id","Other source",TargetScope("codex",null,null,"p8-other-$id","workspace-other","runtime-other"),"https://other.invalid",true,"desktop-runtime",false,requiresReadTarget=true)
        val target=TargetScope("easywork","https://issuer.invalid","subject-$id","desktop-$id","workspace-$id","runtime-$id")
        val connection=AgentConnection(target,listOf(AgentCandidate("relay-$id","relay-http","https://relay.invalid")),"fixture-ref-$id",preference="relay-only")
        val saved=MobileClient("p8-saved-$id","Saved relay only",target,"https://relay.invalid",online,"easywork-runtime",false,requiresReadTarget=true,agentConnection=connection)
        val originalDraft="Owned source draft 中文 😀 %@"
        lateinit var store: NativeSessionStorage
        lateinit var session: MobileSession
        lateinit var context: Context
        lateinit var protectedBodies: Map<String,String>
        lateinit var ciphertext: Map<String,String>
        private lateinit var originalOperation: SavedOperation
        private var created=false
        private var ready=false

        @Suppress("UNCHECKED_CAST") fun clients()=field("realClients").get(session) as MutableList<MobileClient>
        @Suppress("UNCHECKED_CAST") fun draftSnapshot()=(field("drafts").get(session) as Map<String,String>).toMap()
        @Suppress("UNCHECKED_CAST") fun targetCache()=(field("workspaces").get(session) as Map<String,MobileWorkspace>)[saved.cacheKey(null)]
        private fun cipher(key: String)=File(File(context.noBackupFilesDir,"installation"),sha(key.toByteArray())+".bin")
        fun open() {
            check(!folder.exists()) {"Parent run already has an original; never reuse or clear it"}
            check(folder.mkdirs());created=true;android.system.Os.chmod(folder.path,0x1c0)
            val secure=File(folder,"secure");check(secure.mkdir());android.system.Os.chmod(secure.path,0x1c0)
            context=object: ContextWrapper(instrumentation.targetContext) {
                override fun getNoBackupFilesDir()=secure
                override fun getApplicationContext(): Context=this
            }
            store=NativeSessionStorage(context,"installation",alias)
            runBlocking {
                withContext(Dispatchers.IO) {
                    store.write("easywork-credential:${connection.credentialRef}","Original inert fixture credential; never issued or decoded")
                    store.write("easywork-device-grant:$id","Original inert fixture grant; never submitted")
                    val protectedClient=source.copy(id="p8-protected-$id",scope=TargetScope("codex",null,null,"p8-protected-$id","workspace-protected","runtime-protected"))
                    val journal=NativeOperationJournal(store)
                    val operation=journal.prepare(protectedClient,"original-thread","reply",
                        ReplyIntent(protectedClient.id,"original-thread",UUID.nameUUIDFromBytes((id+":original-operation").toByteArray()).toString(),"Original immutable submitted input",emptyList(),"next-turn").body())
                    journal.markSubmitting(operation.target,operation.id)
                    originalOperation=journal.uncertain(operation.target,operation.id,"Synthetic original unknown outcome; never submitted by this fixture")
                    val names=listOf("easywork-credential:${connection.credentialRef}","easywork-device-grant:$id","operations:${originalOperation.target}")
                    protectedBodies=names.associateWith {requireNotNull(store.read(it))}
                    ciphertext=names.associateWith {sha(cipher(it))}
                }
            }
            main {
                session=MobileSession(store)
                // This fixture tests selection, without starting application restore.
                session.resetAcceptance(reads,commands)
                val ports=AgentRegistry(reads,commands).apply {register("easywork",reads,commands)}
                field("providers").set(session,ports);field("reads").set(session,ports);field("commands").set(session,ports)
                field("execution").set(session,NativeCodexFlow(store,ports,ports))
                clients().addAll(listOf(source,other,saved));session.select(source);session.text=originalDraft
            }
            ui.waitUntil(10_000) {!session.loading}
            main {check(session.selectedClient.id==source.id&&session.text==originalDraft);check(targetCache()==null);check(session.easyWorkDiscovery==null)}
            ready=true
        }
        fun request(): Deferred<Result<MobileClient>> = main {
            val form=easyWorkConnectionForm(session);forms.add(form)
            val input=EasyWorkConnectionInput("saved","","",saved.id);val action=form.begin(input)
            caller.async {runCatching {
                session.runEasyWorkConnectionFormAction(action) {session.connectSavedEasyWork(saved.id)}
            }.also {action.releaseBusy();form.close()}}.also {waiting.add(it)}
        }
        fun awaitRequest(gate: ReadGate): Read=runBlocking {withTimeout(10_000){gate.entered.await()}}
        fun awaitResult(job: Deferred<Result<MobileClient>>): Result<MobileClient> = runBlocking {withTimeout(10_000){job.await()}}
        fun assertNoTransport()=main {
            val pool=field("easyWorkSessions").get(session)!!
            val entries=EasyWorkSessions::class.java.getDeclaredField("sessions").apply {isAccessible=true}.get(pool) as Map<*,*>
            check(entries.isEmpty());check(commands.invoked.isEmpty());check(session.easyWorkDiscovery==null)
        }
        fun assertProtected()=runBlocking {withContext(Dispatchers.IO) {
            store.awaitQueuedWrites()
            for((key,body)in protectedBodies){check(store.read(key)==body);check(sha(cipher(key))==ciphertext.getValue(key))}
            check(NativeOperationJournal(store).records(originalOperation.target).single()==originalOperation)
        }}
        fun close() {
            if(!created){caller.cancel();return} // Never touch a pre-existing parent run.
            var failure: Throwable?=null
            var joined=false
            var writerRetired=false
            fun attempt(block: ()->Unit) {
                try {block()}catch(error: Throwable) {
                    if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
                }
            }
            reads.gate?.release?.complete(Unit)
            try {
                attempt {main {ui.activity.setContent {}}}
                attempt {main {forms.forEach {it.close()};waiting.forEach {it.cancel()}
                    if(::session.isInitialized)session.leaveForeground()}}
                // A disposal failure must not skip joining the actual Session and callers.
                attempt {runBlocking {withTimeout(10_000) {
                    waiting.forEach {it.join()}
                    if(::session.isInitialized)withContext(Dispatchers.Main.immediate) {session.abandonMigrationRestore()}
                    caller.coroutineContext[Job]!!.cancelAndJoin()
                    joined=true
                }}}
                if(joined) {
                    if(ready)attempt {assertNoTransport();assertProtected()}
                    if(::store.isInitialized)attempt {
                        runBlocking {withTimeout(10_000) {store.awaitQueuedWrites()}}
                        store.retireWriter();writerRetired=true
                    }
                }
            }finally {
                caller.cancel()
                // Failed joins retain their own store/owner for independent diagnosis.
                attempt {File(folder,"fixture-evidence.json").writeText(JSONObject().put("version",1).put("run",run).put("fixture",id)
                    .put("method",method.methodName).put("label",label).put("build",build).put("keyAlias",alias)
                    .put("namespace","installation").put("initializationCompleted",ready)
                    .put("protectedCiphertexts",if(::ciphertext.isInitialized)JSONObject(ciphertext)else JSONObject())
                    .put("sessionAndOwnedWorkJoined",joined).put("writerRetired",writerRetired)
                    .put("cleanupPassed",failure==null).put("cleanupFailureType",failure?.javaClass?.name?:JSONObject.NULL)
                    .put("MainActivityUsed",false).put("applicationRestoreStarted",false)
                    .put("discoveryInstanceCreated",false).put("defaultFamilyEnumeratedByFixture",false)
                    .put("defaultFamilyIndependentlyVerified",false).put("typedFixtureReadCalls",reads.requests.size)
                    .put("networkOrModelAcceptance",false).put("physicalUiAcceptance",false)
                    .put("fixtureAndKeyRetainedForIndependentReadback",true).toString())}
            }
            failure?.let {throw it}
        }
    }
    private fun withFixture(label: String,online: Boolean=false,block: (Fixture)->Unit) {
        val fixture=Fixture(label,online)
        try {fixture.open();block(fixture)}finally {fixture.close()}
    }

    @Test fun onlineRelayOnlySavedRowUsesActualSavedRowCallbackAndOpensWithoutLanOrPairing()=withFixture("online-ui",true) {f->
        val savedCount=AtomicInteger();val visible=mutableStateOf(true)
        main {ui.activity.setContent {ProductTextProvider("en") {MaterialTheme {Surface(Modifier.fillMaxSize()) {
            if(visible.value)ConnectionPage(f.session,f.saved) {savedCount.incrementAndGet();visible.value=false}
        }}}}}
        ui.onNodeWithTag("easywork-discovered-${f.saved.id}").performScrollTo().assertIsDisplayed().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button))
            .performTouchInput {click(center)}
        ui.waitUntil(10_000){savedCount.get()==1}
        main {
            check(f.session.selectedClient.id==f.saved.id)
            check(f.session.selectedClient.agentConnection==f.connection)
            check(f.reads.requests.none {it.input?.kind=="saved"})
            check(f.session.incomingEasyWorkLink==null&&!f.session.connectionBusy)
        }
        f.assertNoTransport();f.assertProtected()
    }

    @Test fun disconnectedSavedSelectionReadsTheExactRelayCandidatesAndPreferenceBeforeCommit()=withFixture("offline") {f->
        val gate=ReadGate();f.reads.gate=gate
        val job=f.request();val original=f.awaitRequest(gate)
        assertSame(f.saved,original.client);assertEquals(f.connection,original.client.agentConnection)
        assertEquals(EasyWorkConnectionInput("saved","","",f.saved.id),original.input)
        main {check(f.session.selectedClient.id==f.source.id&&f.targetCache()==null)}
        gate.release.complete(Unit);val result=f.awaitResult(job);check(result.isSuccess)
        main {
            check(f.session.selectedClient.id==f.saved.id&&f.session.selectedClient.online)
            check(f.session.selectedClient.agentConnection==f.connection)
            check(f.targetCache()?.hostId==f.saved.id&&f.targetCache()?.scope==f.saved.scope)
        }
        f.assertNoTransport();f.assertProtected()
    }

    private fun stale(kind: String)=withFixture("stale-$kind") {f->
        val gate=ReadGate();f.reads.gate=gate;val job=f.request();val original=f.awaitRequest(gate)
        assertSame(f.saved,original.client)
        main {
            when(kind) {
                "background"->{f.session.leaveForeground();f.session.enterForeground()}
                "draft"->f.session.text="New source draft, retain after old reply 中文 %@"
                "target"->{f.session.select(f.other);f.session.text="Other current draft"}
                "replacement"->{val index=f.clients().indexOfFirst {it.id==f.saved.id};check(index>=0)
                    f.clients()[index]=f.saved.copy(agentConnection=f.connection.copy(credentialRef="replacement-ref-${f.id}"))}
                else->error("Fixed stale scenario only")
            }
        }
        val selected=main {f.session.selectedClient.id};val drafts=main {f.draftSnapshot()}
        val replacement=main {f.clients().single {it.id==f.saved.id}}
        gate.release.complete(Unit);val result=f.awaitResult(job);check(result.isFailure)
        main {
            check(f.session.selectedClient.id==selected&&f.session.selectedClient.id!=f.saved.id)
            check(f.draftSnapshot()==drafts&&f.targetCache()==null)
            check(f.clients().single {it.id==f.saved.id}==replacement)
        }
        f.assertNoTransport();f.assertProtected()
    }
    @Test fun originalQueuedReadCannotSelectAfterBackgroundRoundTripOrNewDraft() {
        stale("background");stale("draft")
    }
    @Test fun originalQueuedReadCannotSelectAfterTargetSwitchOrSavedConnectionReplacement() {
        stale("target");stale("replacement")
    }
}
