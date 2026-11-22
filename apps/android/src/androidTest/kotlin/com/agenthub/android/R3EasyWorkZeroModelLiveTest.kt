package com.agenthub.android

import android.app.Activity
import androidx.activity.ComponentActivity
import android.os.SystemClock
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

/** SOURCE PROPOSAL ONLY. Root must select/review/build and issue fresh explicit I/O windows.
 * Real production storage/session/channels/provider/cards/page. No HostReads/AgentCommands fake,
 * reader injection, transport override, observer HTTP, model send, old-case replay, or grant copy.
 * Phase A pairs ONLY in its separately selected Root pairing window. Later phases reuse that
 * same owned pair; desktop offer/revoke remain separate Root windows, never implicit test actions.
 */
class R3EasyWorkZeroModelLiveTest {
    @get:Rule(order=0) val timeout:org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(40)
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=ui.activity
    private lateinit var cfg:JSONObject
    private lateinit var storage:NativeSessionStorage
    private var session:MobileSession?=null // F59 only; F31 never instantiates a MobileSession/channel.
    private var channels:EasyWorkSessions?=null // F31 only; never present with session.
    private lateinit var scope:CoroutineScope
    private lateinit var client:MobileClient
    private var caseFolder:File?=null
    private var monitor:Instrumentation.ActivityMonitor?=null
    private var alive=true
    private var deadline=0L
    private fun remaining()=(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)
    private fun <T> main(block:suspend ()->T):T=runBlocking {withTimeout(remaining()){withContext(Dispatchers.Main.immediate){block()}}}
    private fun waitFor(condition:()->Boolean)=ui.waitUntil(remaining(),condition)
    private fun save(name:String,value:JSONObject) {
        val file=File(checkNotNull(caseFolder),name)
        require(file.createNewFile()) {"Evidence exists: reconcile original case; never replay"}
        file.setReadable(false,false);file.setWritable(false,false);file.setReadable(true,true);file.setWritable(true,true)
        file.writeText(value.toString())
    }
    private fun claimCase(id:String,kind:String) {
        java.util.UUID.fromString(id)
        val parent=File(context.filesDir,"r3-live-evidence").apply {mkdirs()}
        val folder=File(parent,id)
        require(!folder.exists()&&folder.mkdir()) {"Case already consumed; do not rerun"}
        caseFolder=folder
        save("intent.json",JSONObject().put("caseId",id).put("kind",kind).put("window",cfg.getString("executionWindowId"))
            .put("hostId",cfg.getString("expectedHostId")).put("businessInputsAllowed",0))
    }
    private fun matches(client:MobileClient):Boolean {
        val expected=EasyWorkTarget.decode(cfg.getJSONObject("expectedFullEasyWorkTarget")).scope()
        return !client.isDemo&&client.scope?.agentId=="easywork"&&sameScope(client.scope,expected)&&
            client.agentConnection?.let {sameScope(it.target,expected)}==true
    }
    @Before fun requireSeparateOwnedProcessWindow() {
        val text=InstrumentationRegistry.getArguments().getString("r3LiveConfig")
        Assume.assumeTrue("Requires selected Root Native/I/O window",text!=null)
        cfg=JSONObject(text!!)
        require(cfg.getBoolean("enabled"))
        val phase=cfg.getString("phase");require(phase in setOf("A-pair","B-cancel","B-complete","B-query","C-receive"))
        if(phase=="A-pair") {
            require(!cfg.getBoolean("pairingCompletedInSeparateRootWindow"))
            require(!File(context.noBackupFilesDir,cfg.getString("namespace")).exists()) {"Old namespace: reconcile original pairing, never create a second"}
            val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            require(!keys.containsAlias(cfg.getString("keyAlias"))) {"Preexisting namespace key is not fresh"}
        } else require(cfg.getBoolean("pairingCompletedInSeparateRootWindow"))
        require(cfg.getBoolean("previousNativeProcessEnded")) // Root must actually end the previous owned phase.
        java.util.UUID.fromString(cfg.getString("executionWindowId"))
        require(context.packageName=="com.easyai.agenthub.android.remaining20261121.acceptance")
        require(android.os.Process.myUid()==10234) // exact existing owned installation, not another app/profile
        require(cfg.getString("namespace").startsWith("r2-live-")&&cfg.getString("keyAlias")==cfg.getString("namespace"))
        val budget=cfg.getLong("maxNativeMs");require(budget in 1..40_000)
        deadline=SystemClock.elapsedRealtime()+budget
        // Public ordinary writable constructor. No fixture/inspection/migration store flags.
        storage=NativeSessionStorage(context,cfg.getString("namespace"),cfg.getString("keyAlias"))
    }
    @After fun leaveOwnedSurfaceOnly() {
        alive=false
        monitor?.let(instrumentation::removeMonitor)
        runBlocking {withTimeout(5_000){withContext(Dispatchers.Main.immediate) {
            abandonOwnedSessionLifecycle()
            channels?.closeAll()
            if(::scope.isInitialized)scope.coroutineContext[Job]!!.cancelAndJoin()
        }}}
        // Unknown transfer/source/journal state is retained. Separate Root window owns revoke/cleanup.
        // Root also ends only THIS owned native process between stages; no grant is removed here.
    }
    private suspend fun abandonOwnedSessionLifecycle() {
        val owned=session?:return
        session=null
        owned.closeComputerFiles();owned.leaveForeground()
        // Existing production full-release entry. Normal writable namespace has NO migrationPolicy;
        // retireMigrationSession is a no-op. Only stop/seal sessions/discovery, cancel/join tasks
        // and await I/O; no migration flags, persisted data or product guards are rewritten.
        owned.abandonMigrationRestore()
    }
    @Test fun phaseA_normalFreshPairing() {
        require(cfg.getString("phase")=="A-pair")
        require(storage.read("easywork-device-identity")==null&&storage.metadata()==null)
        claimCase(cfg.getString("PhaseACaseId"),"A-normal-fresh-pairing")
        val endpoint=cfg.getString("endpoint")
        val expected=EasyWorkTarget.decode(cfg.getJSONObject("expectedFullEasyWorkTarget"))
        require(expected.desktopDeviceId==cfg.getString("expectedHostId")&&expected.workspaceId==cfg.getString("expectedWorkspaceId")&&expected.runtimeId==cfg.getString("expectedRuntimeId"))
        val pin=cfg.getString("expectedCertificateSHA256")
        val desktopKey=cfg.getString("expectedDesktopPublicKey")
        val host=main {EasyWorkPairing.readHost(endpoint,pin,expected,desktopKey,5)}
        require(host.value.getString("connectionMode")=="automatic")
        require(ComputerFileCodec.hash(easyBytes(host.publicKey))==cfg.getString("expectedDesktopPublicKeySHA256"))
        val owned=main {MobileSession(storage).also {session=it;it.restore()}}
        try {
            waitFor {!owned.restoring}
            main {require(owned.clients.none {!it.isDemo});owned.enterForeground()}
            val identity=main {EasyWorkIdentity.load(storage)}
            val fingerprint=ComputerFileCodec.hash(easyBytes(identity.publicKey))
            require(identity.deviceId !in cfg.getJSONArray("preexistingMobileDeviceIds").strings())
            require(fingerprint !in cfg.getJSONArray("preexistingMobileKeySHA256s").strings())
            save("new-mobile-public.json",JSONObject().put("mobileDeviceId",identity.deviceId).put("publicKeySHA256",fingerprint))
            save("pair-submission.intent.json",JSONObject().put("caseId",cfg.getString("PhaseACaseId"))
                .put("endpoint",endpoint).put("targetSHA256",easyHash(easyCanonical(expected.json()).toByteArray()))
                .put("mobileDeviceId",identity.deviceId).put("exactlyOneConnectCall",true))
            val discovery=DiscoveredEasyWorkHost("r3-"+cfg.getString("PhaseACaseId"),"R3 task-owned EasyWork",endpoint,expected.desktopDeviceId,reachable=true)
            client=main {owned.connectDiscoveredEasyWork(discovery,"R3 fresh task installation")} // exactly once
            require(matches(client)&&client.agentConnection!=null)
            val saved=checkNotNull(storage.metadata()).getJSONArray("clients").objects().map(::decodeClient).single(::matches)
            assertEquals(client.id,saved.id)
            val ref=checkNotNull(saved.agentConnection).credentialRef
            val credential=JSONObject(checkNotNull(storage.read("easywork-credential:$ref")))
            // Only public binding metadata is inspected; private key/token/password never exported.
            val binding=EasyWorkBinding(credential.getJSONObject("binding").toString()).verify(identity,expected)
            require(binding.desktopPublicKey==desktopKey&&binding.mobileId==identity.deviceId&&binding.mobilePublicKey==identity.publicKey)
            require(binding.id !in cfg.getJSONArray("preexistingBindingIds").strings())
            save("pair-public-result.json",JSONObject().put("bindingId",binding.id).put("mobileDeviceId",binding.mobileId)
                .put("mobilePublicKeySHA256",fingerprint).put("desktopPublicKeySHA256",ComputerFileCodec.hash(easyBytes(binding.desktopPublicKey)))
                .put("clientId",saved.id).put("targetSHA256",easyHash(easyCanonical(expected.json()).toByteArray()))
                .put("certificateSHA256",pin).put("secretExported",false).put("modelInputs",0))
        } finally {runBlocking {withTimeout(5_000){withContext(Dispatchers.Main.immediate){abandonOwnedSessionLifecycle()}}}}
    }
    private fun hash(source:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        source.inputStream().use {input->val b=ByteArray(192*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    @Test fun phaseB_cancel() {require(cfg.getString("phase")=="B-cancel");fileOnlyF31("cancel")}
    @Test fun phaseB_complete() {require(cfg.getString("phase")=="B-complete");fileOnlyF31("complete")}
    private fun fileOnlyF31(stage:String) {
        // Independent <=40s Native phases, ONE paired grant; no MobileSession in phase B.
        client=checkNotNull(storage.metadata()).getJSONArray("clients").objects().map(::decodeClient).single(::matches)
        val caseId=cfg.getString(if(stage=="cancel")"F31CancelCaseId" else "F31CompleteCaseId")
        claimCase(caseId,"F31-$stage-file-only")
        val port=main {
            scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val owner=EasyWorkSessions(storage,EasyWorkLogin(storage),scope.coroutineContext)
            channels=owner
            EasyWorkAgentClient(owner)
        }
        val threadId=if(stage=="cancel") {
            val op=cfg.getString("F31ThreadOperationId")
            val thread=main {port.createThread(client,null,op)} // exactly ONE empty metadata row, no turn/send
            save("empty-thread.json",JSONObject().put("threadId",thread.id).put("operationId",op).put("modelInputs",0))
            thread.id
        } else cfg.getString("ownedEmptyThreadId") // actual returned first-phase ID supplied by Root, never guessed
        val name=if(stage=="cancel")"r2-zero-f31-cancel-v1-efd83934.bin" else "r2-zero-f31-complete-v1-efd83934.txt"
        val expectedSize=if(stage=="cancel")16_777_216L else 262_145L
        val expectedSha=if(stage=="cancel")"505395cdaefbc2d2cde1052c5a82662dde2412658e6e5585950f27206233af07" else "2f8787773fe45d1e13a6374d8394971d3b5170a4af2d91e0992170df9f3975d3"
        val original=File(File(context.filesDir,"r3-live-inputs"),name)
        require(original.isFile&&original.length()==expectedSize&&hash(original)==expectedSha)
        val sourceId=cfg.getString(if(stage=="cancel")"F31CancelFileId" else "F31CompleteFileId")
        val prepared=main {NativeDraftFiles(storage).prepareFile(name,"application/octet-stream",original,sourceId)}
        val file=prepared.file.manifest;prepared.close()
        val bytes=main {NativeDraftFiles(storage).bytes(file)}
        require(file.sizeBytes==expectedSize&&file.sha256==expectedSha&&ComputerFileCodec.hash(bytes)==expectedSha)
        val m=NativeUploadManifest.attachment(client,threadId,file)
        val key=NativeUploadRow.key(NativeUploadTarget.capture(client),m)
        val journal=storage.child(context,"r3-f31-"+caseId.take(8))
        val center=main {NativeUploadCenter(journal,scope,
            clientFor={target->client.takeIf {alive&&target.matches(it)}},
            leaseFor={bound->val target=NativeUploadTarget.capture(bound);{alive&&target.matches(client)}},
            resolveSource={_,row->
                val manifest=row.manifest.file
                val saved=NativeDraftFiles(storage).bytes(CreationFile(checkNotNull(row.sourceFileId),manifest.name,manifest.mediaType,manifest.sizeBytes,manifest.sha256))
                val reader:(Long,Int)->ByteArray={offset,n->saved.copyOfRange(offset.toInt(),offset.toInt()+n)}
                reader
            },commands={port})}
        main {center.restore();require(center.rows.isEmpty());require(port.uploadCapability(client,m)?.remoteCancel==true)}
        save("upload.json",JSONObject().put("transferId",m.id).put("threadId",threadId).put("sha256",file.sha256)
            .put("sizeBytes",file.sizeBytes).put("independentJournalSuffix","r3-f31-"+caseId.take(8)))
        ui.setContent {MaterialTheme {NativeUploadJobs(center,client,setOf("attachment"))}}
        val transfer=main {scope.async {runCatching {center.transfer(client,m,file.id,
            {offset,n->bytes.copyOfRange(offset.toInt(),offset.toInt()+n)},port) {error("Negotiated resumable case must not fall back")}}}}
        if(stage=="cancel") {
            waitFor {center.rows[key]?.let {it.terminal||it.capability?.remoteCancel==true&&it.committed>0}==true}
            if(main {center.rows.getValue(key).phase!="completed"}) {
                ui.onNodeWithTag("upload-cancel-$key").performScrollTo().assertIsEnabled().performClick()
                waitFor {center.rows[key]?.let {it.terminal||it.phase=="cancel-pending"}==true}
                if(main {center.rows[key]?.terminal!=true}) {
                    // Cancellation can become terminal between the state read and UI action.
                    // A terminal card removes its query button; preserve the outcome assertion.
                    try { ui.onNodeWithTag("upload-query-$key").performScrollTo().performClick() }
                    catch(error:AssertionError) { if(!main {center.rows[key]?.terminal==true})throw error }
                    waitFor {center.rows[key]?.terminal==true}
                }
            }
        } else waitFor {center.rows[key]?.terminal==true}
        val actual=main {port.uploadRequest(client,m,NativeUploadAction.STATUS,0,null)}
        val localPhase=main {center.rows.getValue(key).phase}
        save("terminal-evidence.json",JSONObject().put("transferId",m.id).put("actualPhase",actual.phase).put("cardPhase",localPhase)
            .put("completedRace",stage=="cancel"&&actual.phase=="completed").put("cancellationPassed",stage=="cancel"&&actual.phase=="cancelled"))
        assertEquals(actual.phase,localPhase)
        if(stage=="cancel")assertEquals("Cancellation itself remains unverified if completion wins; preserve evidence, no replay","cancelled",actual.phase)
        else {assertEquals("completed",actual.phase);assertEquals(expectedSha,actual.result!!.getString("sha256"));assertEquals(expectedSize,actual.committedBytes)}
        val outcome=main {transfer.await()}
        if(stage=="complete") {
            assertTrue(outcome.isSuccess)
            // Query/cancel the already completed ORIGINAL; never re-upload or re-complete.
            val completedWinner=main {port.uploadRequest(client,m,NativeUploadAction.CANCEL,0,null)}
            assertEquals("completed",completedWinner.phase)
            assertEquals(expectedSha,completedWinner.result!!.getString("sha256"))
            save("completed-cancel-evidence.json",JSONObject().put("transferId",m.id).put("phase",completedWinner.phase)
                .put("originalCompletedReceiptPreserved",true).put("bytesResent",0))
        }
        assertEquals(expectedSha,main {ComputerFileCodec.hash(NativeDraftFiles(storage).bytes(file))})
        save("native-result.json",JSONObject().put("phase",actual.phase).put("stage",stage).put("sourceRetained",true)
            .put("agentTriggeredModelInputs",0).put("requiresRootIndependentServiceAndZeroRunCountReview",true))
    }
    /** Proposal: authenticated route refresh, then retire this temporary owner before F31. */
    private fun refreshOriginalQueryRoute() {
        require(session==null && channels==null)
        val route=cfg.getJSONObject("originalQueryAddressRefresh")
        require(route.getBoolean("enabled"))
        val endpoint=CodexEndpoints.local(route.getString("endpoint"))
        require(endpoint==route.getString("endpoint") && endpoint==cfg.getString("endpoint"))
        val expected=EasyWorkTarget.decode(cfg.getJSONObject("expectedFullEasyWorkTarget"))
        require(expected.desktopDeviceId==cfg.getString("expectedHostId") &&
            expected.workspaceId==cfg.getString("expectedWorkspaceId") && expected.runtimeId==cfg.getString("expectedRuntimeId"))
        val pin=cfg.getString("expectedCertificateSHA256").lowercase()
        val desktopKey=cfg.getString("expectedDesktopPublicKey")
        val before=client
        val connection=checkNotNull(before.agentConnection)
        require(before.id==route.getString("expectedClientId") && matches(before))
        require(connection.preference!="relay-only")
        val oldRoute=connection.candidates.first {it.transportId=="lan-wss"}.endpoint
        require(connection.candidates.filter {it.transportId=="lan-wss"}.all {it.peerFingerprint?.lowercase()==pin})
        // This stays in the app's ordinary secure store and process. Never serialize/log credentials.
        val credential=main {withContext(Dispatchers.IO) {
            EasyWorkCredential.decode(JSONObject(checkNotNull(storage.read("easywork-credential:${connection.credentialRef}"))))
        }}
        require(credential.binding.target==expected && credential.binding.desktopPublicKey==desktopKey)
        require(credential.binding.id==route.getString("expectedBindingId"))
        require(credential.binding.mobileId==route.getString("expectedMobileDeviceId"))
        require(ComputerFileCodec.hash(easyBytes(credential.binding.mobilePublicKey))==route.getString("expectedMobilePublicKeySHA256"))
        val host=main {EasyWorkPairing.readHost(endpoint,pin,expected,desktopKey,5)}
        // Only the exact Root-selected endpoint enters the candidate update, never descriptor extras.
        val candidates=EasyWorkPairing.directCandidates(listOf(endpoint),fingerprint=host.fingerprint)
        fun publicRoutes()=JSONObject().put("oldRoute",oldRoute).put("newRoute",endpoint)
            .put("sameClient",true).put("sameTarget",true).put("sameGrant",true)
        val owned=main {MobileSession(storage).also {session=it;it.leaveForeground();it.restore()}}
        try {
            waitFor {!owned.restoring}
            main {
                require(!owned.restoring && !owned.localRecoveryRequired && !owned.migrationPaused && !owned.usageForeground)
                require(owned.migrationStorage===storage)
                val restored=owned.clients.single(::matches)
                require(restored.id==before.id && restored.agentConnection==connection)
                save("route-refresh-intent.json",publicRoutes())
                require(owned.updateEasyWorkCandidates(restored,credential.binding,candidates))
            }
            main {withContext(Dispatchers.IO) {storage.awaitQueuedWrites()}}
        } finally {
            // restore() can start ordinary reads even in the background. Do not enter foreground,
            // select, discover or explicitly refresh. Full cancel/join below precedes any F31 owner.
            runBlocking {withTimeout(5_000) {withContext(Dispatchers.Main.immediate) {abandonOwnedSessionLifecycle()}}}
        }
        require(session==null && channels==null)
        client=main {withContext(Dispatchers.IO) {
            checkNotNull(storage.metadata()).getJSONArray("clients").objects().map(::decodeClient).single(::matches)
        }}
        val after=checkNotNull(client.agentConnection)
        require(client.id==before.id && sameScope(client.scope,before.scope) && sameScope(after.target,connection.target))
        require(after.credentialRef==connection.credentialRef && after.preference==connection.preference)
        require(after.candidates.first()==candidates.single())
        val retained=main {withContext(Dispatchers.IO) {
            EasyWorkCredential.decode(JSONObject(checkNotNull(storage.read("easywork-credential:${after.credentialRef}"))))
        }}
        require(retained==credential) // Boolean only: never include actual/expected secret values in failures.
        save("route-refresh-public.json",publicRoutes())
    }
    /** Original-ID recovery only: no create/prepare/chunk/complete/cancel/re-upload path. */
    @Test fun phaseB_queryOriginalCancel() {
        require(cfg.getString("phase")=="B-query")
        client=checkNotNull(storage.metadata()).getJSONArray("clients").objects().map(::decodeClient).single(::matches)
        claimCase(cfg.getString("F31QueryCaseId"),"F31-original-cancel-query")
        val priorCase=cfg.getString("F31CancelCaseId");java.util.UUID.fromString(priorCase)
        val prior=JSONObject(File(context.filesDir,"r3-live-evidence/$priorCase/upload.json").readText())
        require(prior.getString("transferId")==cfg.getString("F31CancelFileId"))
        require(prior.getLong("sizeBytes")==16_777_216L&&prior.getString("sha256")=="505395cdaefbc2d2cde1052c5a82662dde2412658e6e5585950f27206233af07")
        refreshOriginalQueryRoute() // Same original grant; reload saved route before making its F31 manifest/channel.
        val file=CreationFile(prior.getString("transferId"),"r2-zero-f31-cancel-v1-efd83934.bin","application/octet-stream",prior.getLong("sizeBytes"),prior.getString("sha256"))
        val manifest=NativeUploadManifest.attachment(client,prior.getString("threadId"),file)
        val port=main {
            scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val owner=EasyWorkSessions(storage,EasyWorkLogin(storage),scope.coroutineContext);channels=owner
            EasyWorkAgentClient(owner)
        }
        val suffix=prior.getString("independentJournalSuffix")
        require(suffix=="r3-f31-"+priorCase.take(8))
        val journal=storage.child(context,suffix)
        val center=main {NativeUploadCenter(journal,scope,
            clientFor={target->client.takeIf {alive&&target.matches(it)}},
            leaseFor={bound->val target=NativeUploadTarget.capture(bound);{alive&&target.matches(client)}},
            resolveSource={_,_->error("Read-only original query must never resolve or resend bytes")},commands={port})}
        val key=NativeUploadRow.key(NativeUploadTarget.capture(client),manifest)
        main {center.restore();require(center.rows.getValue(key).manifest==manifest)}
        var cancelledLabel=""
        ui.setContent {MaterialTheme {cancelledLabel=currentProductText().tr("电脑已确认取消，手机原件保留。");NativeUploadJobs(center,client,setOf("attachment"))}}
        if(!main {center.rows.getValue(key).terminal}) {
            try {ui.onNodeWithTag("upload-query-$key").performScrollTo().assertIsEnabled().performClick()}
            catch(error:AssertionError) {if(!main {center.rows.getValue(key).terminal})throw error}
            waitFor {center.rows.getValue(key).terminal}
        }
        val original=main {port.uploadRequest(client,manifest,NativeUploadAction.STATUS,0,null)}
        save("original-query-evidence.json",JSONObject().put("originalCaseId",priorCase).put("transferId",manifest.id)
            .put("threadId",manifest.threadId).put("serverPhase",original.phase).put("cardPhase",main {center.rows.getValue(key).phase})
            .put("newUploads",0).put("newThreadCreates",0).put("newCancelCalls",0))
        assertEquals("cancelled",original.phase)
        assertEquals(original.phase,main {center.rows.getValue(key).phase})
        ui.onNodeWithTag("upload-phase-$key").assert(hasText(cancelledLabel))
        assertEquals(file.sha256,main {ComputerFileCodec.hash(NativeDraftFiles(storage).bytes(file))})
        save("native-result.json",JSONObject().put("transferId",manifest.id).put("phase",original.phase)
            .put("originalSourceRetained",true).put("originalOnlyQuery",true).put("newModelInputs",0))
    }
    @Test fun phaseC_receive() {
        require(cfg.getString("phase")=="C-receive")
        require(cfg.getBoolean("RootDesktopOfferWindowAuthorized"))
        val ownedSession=main {MobileSession(storage).also {session=it;it.configureVoice(context);it.restore()}}
        waitFor {!ownedSession.restoring}
        main {client=ownedSession.clients.single(::matches);ownedSession.enterForeground();ownedSession.select(client)}
        waitFor {ownedSession.selectedClient.online&&!ownedSession.selectedClient.isConnecting}
        main {require(ComputerFileTarget.capture(client).matches(client))} // Root's canonical EasyWork guard
        require(cfg.getLong("incomingSizeBytes")==52L&&cfg.getString("incomingFileName")=="r2-zero-f59-offer-v1-efd83934.txt")
        require(cfg.getString("incomingSha256")=="6b745c8a64e520de52958a0e74133c58bc97b1f3db4047e25d06fe6acfe46b4f")
        claimCase(cfg.getString("F59CaseId"),"F59-real-offer")
        main {require(ownedSession.canOpenComputerFiles);ownedSession.openComputerFiles(context)}
        val model=main {requireNotNull(ownedSession.computerFiles)}
        // Clipboard is a presentation-only port: no user's clipboard read/write; real commands stay real.
        val clipboard=object:ClipboardManager {
            var copied:AnnotatedString?=null
            override fun setText(annotatedString:AnnotatedString){copied=annotatedString}
            override fun getText():AnnotatedString?=copied
            override fun hasText():Boolean=copied!=null
        }
        var completedLabel=""
        ui.setContent {MaterialTheme {CompositionLocalProvider(LocalClipboardManager provides clipboard) {
            Column {completedLabel=currentProductText().tr("已接收");NativeComputerFilesPage(model)}
        }}}
        save("ready-for-desktop-offer.json",JSONObject().put("clientId",client.id).put("sessionOnline",true).put("window",cfg.getString("executionWindowId")))
        val notBefore=java.time.Instant.parse(cfg.getString("offerNotBeforeIso"))
        fun ownedOffer(f:IncomingComputerFile)=f.name==cfg.getString("incomingFileName")&&f.sizeBytes==cfg.getLong("incomingSizeBytes")&&
            f.sha256==cfg.getString("incomingSha256")&&java.time.Instant.parse(f.createdAt)>=notBefore&&
            f.id !in cfg.getJSONArray("preexistingOfferIds").strings()
        while(main {model.catalog?.incomingFiles?.any(::ownedOffer)!=true}) {
            require(remaining()>1) {"No new offer within this bounded window"}
            ui.onNodeWithTag("file-transfers").performScrollToNode(hasTestTag("refresh-transfer-files"))
            ui.onNodeWithTag("refresh-transfer-files").assertIsEnabled().performClick()
            waitFor {!model.busy}
            Thread.sleep(minOf(200,remaining()))
        }
        val offered=main {model.catalog!!.incomingFiles!!.single(::ownedOffer)}
        save("new-offer-public.json",JSONObject().put("transferId",offered.id).put("resourceId",offered.resourceId).put("sha256",offered.sha256))
        assertEquals(cfg.getString("incomingFileName"),offered.name)
        assertEquals(cfg.getLong("incomingSizeBytes"),offered.sizeBytes)
        assertEquals(cfg.getString("incomingSha256"),offered.sha256)
        ui.onNodeWithTag("file-transfers").performScrollToNode(hasTestTag("receive-transfer-${offered.id}"))
        ui.onNodeWithTag("receive-transfer-${offered.id}").assertIsEnabled().performClick()
        waitFor {model.receivedFiles[offered.snapshotKey]!=null&&!model.busy}
        val local=main {model.receivedFiles.getValue(offered.snapshotKey)}
        assertEquals(offered.sizeBytes,local.length())
        val digest=MessageDigest.getInstance("SHA-256")
        local.inputStream().use {input->val b=ByteArray(192*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        assertEquals(offered.sha256,digest.digest().joinToString(""){"%02x".format(it)})
        ui.onNodeWithTag("file-transfers").performScrollToNode(hasTestTag("refresh-transfer-files"))
        ui.onNodeWithTag("refresh-transfer-files").performClick()
        waitFor {!model.busy&&model.catalog?.incomingFiles?.any {it.sameFile(offered)&&it.phase=="completed"}==true}
        ui.onNodeWithTag("file-transfers").performScrollToNode(hasTestTag("copy-received-${offered.id}"))
        ui.onNodeWithTag("incoming-transfer-${offered.id}").assert(hasAnyDescendant(hasText(completedLabel)))
        val launched=ConcurrentLinkedQueue<Intent>()
        monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action !in setOf(Intent.ACTION_VIEW,Intent.ACTION_CHOOSER))return null
                launched.add(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null) // no external recipient
            }
        }.also(instrumentation::addMonitor)
        for(action in listOf("open","share"))ui.onNodeWithTag("$action-received-${offered.id}").performClick()
        ui.runOnIdle {
            assertEquals(2,launched.size)
            val intents=launched.toList()
            assertEquals(Intent.ACTION_VIEW,intents[0].action)
            assertEquals(Intent.ACTION_CHOOSER,intents[1].action)
            val send=intents[1].getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND,send.action)
            for(intent in listOf(intents[0],send)) {
                assertEquals(offered.mediaType,intent.type)
                assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)
                assertEquals(0,(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                val uri=if(intent.action==Intent.ACTION_VIEW)intent.data else intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                assertEquals("content",uri!!.scheme);assertEquals(context.packageName+".resources",uri.authority)
                assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
            }
        }
        ui.onNodeWithTag("copy-received-${offered.id}").performClick()
        ui.runOnIdle {assertEquals(local.path,clipboard.copied!!.text)}
        save("native-result.json",JSONObject().put("transferId",offered.id).put("resourceId",offered.resourceId).put("completed",true)
            .put("sha256",offered.sha256).put("systemClipboardTouched",false).put("externalActivitiesStarted",0)
            .put("productionIntentWiringChecked",true).put("requiresRootIndependentDesktopReceiptReview",true))
    }
}

