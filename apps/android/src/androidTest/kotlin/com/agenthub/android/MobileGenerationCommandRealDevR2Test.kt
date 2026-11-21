package com.agenthub.android

import android.content.Intent
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Opt-in real UI, real Session/provider and fresh W2 installation. No fixture ports or sends
 * from a desktop helper. Each method requires its own fresh process/profile and controller lease.
 * Q02RealReadInstallation is owned by W2 and must be integrated before native compilation.
 */
@RunWith(AndroidJUnit4::class)
class MobileGenerationCommandRealDevR2Test {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val limit = Timeout.seconds(600)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var plan: JSONObject
    private lateinit var folder: File
    private lateinit var planSha: String
    private lateinit var profile: Q02RealReadInstallation.Profile
    private lateinit var activity: Q02RealReadActivity
    private val session get() = activity.session
    private val records = linkedMapOf<String, SavedOperation>()
    private data class ClockRequest(val action: String, val sha256: String, val deviceElapsedMs: Long)
    private data class ToolBudget(val startedAtHostMs: Long, val receipt: NativeProofClock.Receipt)
    @Volatile private var activeWindow: ToolBudget? = null
    private data class ReplyBoundary(val proof: JSONObject,val turn: String?)
    private val replyBoundaries=ConcurrentHashMap<String,ReplyBoundary>()
    private lateinit var leaseClock: NativeProofClock.Receipt
    private var continuing = false
    private var freshSingleOrigin=false
    private var knownZeroSuccessor=false
    private var successorAdmission: Q02RealReadInstallation.KnownZeroSuccessorPermit?=null
    private var independentBudget=false
    private var independentAdmission: Q02RealReadInstallation.IndependentBudgetPermit?=null
    private val startupPermissionRequired get()=knownZeroSuccessor||independentBudget
    private val startupPermissionName="android.permission.START_ACTIVITIES_FROM_BACKGROUND"
    private data class FreshWriteScope(val target: com.agenthub.core.TargetScope,val thread: String,
        val run: String,val lease: String,val budget: String,val nonce: String,val planSha: String,
        val authoritySha: String,val expires: Long,val receipt: NativeProofClock.Receipt,
        val endpoints: Set<String>,val certificateSha: String,val folderDevice: Long,val folderInode: Long)
    private data class FreshWriteProof(val scope: FreshWriteScope,val slot: String,val slotId: String,
        val textSha: String,val mode: String,val turn: String?,val observed: Long,val receipt: NativeProofClock.Receipt)
    private lateinit var freshScope: FreshWriteScope
    private val freshProofs=ConcurrentHashMap<String,FreshWriteProof>()
    private val freshDispatch=FreshQ04DispatchLedger()
    @Volatile private var freshAbortObserved=false
    @Volatile private var freshCancelConfirmed=false
    @Volatile private var freshExecuteConfirmed=false
    private var statusInspected = false
    private val submittedTurns = linkedSetOf<String>()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun sha(text: String) = sha(text.toByteArray(Charsets.UTF_8))
    private fun uuid(value: String) = value.also { require(UUID.fromString(it).toString() == it) }
    private fun hex(value: String) = value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun privateJson(file: File, expectedSha: String): JSONObject {
        require(file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath()))
        val fd = android.system.Os.open(file.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW, 0)
        val bytes = try {
            val before = android.system.Os.fstat(fd)
            require(android.system.OsConstants.S_ISREG(before.st_mode) && before.st_uid == android.os.Process.myUid() &&
                before.st_nlink == 1L && before.st_mode and 0x3f == 0 && before.st_size in 1..65536)
            val data = ByteArray(before.st_size.toInt()); var offset = 0
            while (offset < data.size) { val n = android.system.Os.read(fd, data, offset, data.size-offset); require(n > 0); offset += n }
            require(android.system.Os.read(fd, ByteArray(1), 0, 1) == 0)
            val after = android.system.Os.fstat(fd); val path = android.system.Os.lstat(file.path)
            require(before.st_dev == after.st_dev && before.st_ino == after.st_ino && before.st_size == after.st_size &&
                before.st_mtime == after.st_mtime && path.st_dev == before.st_dev && path.st_ino == before.st_ino && path.st_size == before.st_size)
            data
        } finally { android.system.Os.close(fd) }
        require(sha(bytes) == hex(expectedSha))
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun publish(name: String, value: JSONObject): String {
        require(name.matches(Regex("[a-zA-Z0-9-]+\\.json")))
        val file = File(folder, name)
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val fd = android.system.Os.open(file.path, android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or
            android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW, 0x180)
        try {
            var offset = 0
            while (offset < bytes.size) { val n = android.system.Os.write(fd, bytes, offset, bytes.size-offset); require(n > 0); offset += n }
            android.system.Os.fsync(fd)
        } finally { android.system.Os.close(fd) }
        return sha(bytes)
    }
    private fun privateSidecarSha(sidecar: File): String {
        require(sidecar.canonicalFile == sidecar.absoluteFile && !Files.isSymbolicLink(sidecar.toPath()))
        val st = android.system.Os.lstat(sidecar.path)
        require(android.system.OsConstants.S_ISREG(st.st_mode) && st.st_uid == android.os.Process.myUid() &&
            st.st_nlink == 1L && st.st_mode and 0x3f == 0 && st.st_size in 64..65)
        return hex(sidecar.readText().trim())
    }
    private fun requestClock(action: String): ClockRequest {
        val elapsed = SystemClock.elapsedRealtime()
        val request = JSONObject().put("version", 1).put("kind", "native-proof-clock-request-v1")
            .put("run", plan.getString("run")).put("leaseId", plan.getString("leaseId")).put("planSha256", planSha)
            .put("action", action).put("target", plan.getJSONObject("target")).put("threadId", plan.getString("threadId"))
            .put("deviceElapsedAtRequestMs", elapsed).put("nonce", UUID.randomUUID().toString())
        return ClockRequest(action, publish("clock-request-$action.json", request), elapsed)
    }
    private fun receiptClock(request: ClockRequest, sourceSha: String): NativeProofClock.Receipt {
        val file = File(folder, "${request.action}-receipt-clock.json")
        val sidecar = File(folder, "${request.action}-receipt-clock.sha256")
        ui.waitUntil(10000) { file.exists() && sidecar.exists() }
        val clock = privateJson(file, privateSidecarSha(sidecar))
        require(clock.getInt("version") == 1 && clock.getString("kind") == "native-proof-receipt-clock-v1")
        require(clock.getString("run") == plan.getString("run") && clock.getString("leaseId") == plan.getString("leaseId") &&
            clock.getString("planSha256") == planSha && clock.getString("action") == request.action &&
            clock.getString("sourceFileSha256") == sourceSha && clock.getString("threadId") == plan.getString("threadId"))
        require(sameScope(HostReadCodec.target(clock.getJSONObject("target")), HostReadCodec.target(plan.getJSONObject("target"))))
        require(clock.getString("requestSha256") == request.sha256 &&
            clock.getString("deviceClockSource") == "native-request-elapsed-realtime-ms" &&
            clock.getLong("deviceElapsedAtRequestMs") == request.deviceElapsedMs)
        return NativeProofClock.Receipt(clock.getLong("hostTransferCompletedAtEpochMs"), request.deviceElapsedMs)
    }
    private fun currentHostUpperBound(): Long = checkNotNull(leaseClock.hostNowUpperBound(SystemClock.elapsedRealtime())) {
        "Device receipt clock regressed or overflowed; no business action"
    }
    private fun load(scenario: String,remaining: Boolean=false,singleOrigin: Boolean=false,successor: Boolean=false,independent: Boolean=false) {
        continuing=remaining
        freshSingleOrigin=singleOrigin
        knownZeroSuccessor=successor;require(!successor||singleOrigin)
        independentBudget=independent;require(!independent||singleOrigin&&!successor)
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("w3LeaseReady") == "1" && args.getString("w3ModelBudgetApproved") == "1")
        require(args.getString("nativeForeground") == "0" && args.getString("nativeThreadSnapshots") == "0")
        require(BuildConfig.DEBUG && context.packageName == "com.easyai.agenthub.android.acceptance" &&
            instrumentation.context.packageName == context.packageName + ".test")
        val run = uuid(checkNotNull(args.getString("w3Run")))
        val freshFlag=args.getString("w3FreshSingleOrigin")
        require(freshFlag==null||freshFlag=="1")
        val successorFlag=args.getString("w3FreshKnownZeroSuccessor");require(successorFlag==null||successorFlag=="1")
        val independentFlag=args.getString("w3FreshIndependentBudget");require(independentFlag==null||independentFlag=="1")
        require((independentFlag=="1")==independent&&(successorFlag=="1")==successor&&(freshFlag=="1")== (singleOrigin&&!successor&&!independent))
        if(singleOrigin)require(!remaining&&scenario=="q04"&&(when {
            independent->run==FreshQ04IndependentBudgetCase.RUN
            successor->FreshQ04KnownZeroSuccessorCase.isApprovedRun(run)
            else->run==FreshQ04SingleOriginCase.RUN
        })&&
            listOf("w3PostSecondKnownZero","w3ConfirmedPreNativeRecovery","w3KnownNativeFailedBeforeBusiness","w3ContinuationReady").all {args.getString(it)==null})
        val postSecondFlag=args.getString("w3PostSecondKnownZero")
        require(postSecondFlag==null||postSecondFlag=="1")
        if(postSecondFlag=="1")require(remaining&&scenario=="q04"&&args.getString("w3ConfirmedPreNativeRecovery")!="1"&&args.getString("w3KnownNativeFailedBeforeBusiness")!="1")
        folder = File(context.filesDir.canonicalFile, "acceptance/w3-real-dev/$run"+
            if(postSecondFlag=="1")"/post-second-known-zero-v1" else "")
        require(folder.canonicalFile == folder.absoluteFile && folder.isDirectory && !Files.isSymbolicLink(folder.toPath()))
        planSha = hex(checkNotNull(args.getString("w3PlanSha256")))
        plan = privateJson(File(folder, "plan.json"), planSha)
        require(plan.getInt("version") == 1 && plan.getString("scenario") == scenario && plan.getString("run") == run)
        require((plan.optional("caseKind")==FreshQ04SingleOriginCase.CASE_KIND)==(singleOrigin&&!successor&&!independent))
        require(FreshQ04KnownZeroSuccessorCase.isApprovedCaseKind(plan.optional("caseKind"))==successor)
        require((plan.optional("caseKind")==FreshQ04IndependentBudgetCase.CASE_KIND)==independent)
        require(plan.getString("startupMode") == (if(remaining) "existing-owned-q04" else "new-isolated-installation") &&
            plan.opt("isolatedTargetApproved")==true && plan.opt("newBindingAuthorized")==!remaining &&
            plan.opt("modelBudgetApproved")==true && plan.opt("nativeAiSharingApproved")==true)
        if(remaining) require(scenario=="q04"&&args.getString("w3ContinuationReady")=="1")
        require(uuid(plan.getString("leaseId")) == args.getString("w3LeaseId"))
        require(uuid(plan.getString("controlHostId")) == args.getString("w3AuthorizedControlHostId"))
        require(plan.getString("nativeOperationIdMode") == "journal-generated-once" && plan.getString("onUnknown") == "query-original-only")
        leaseClock = receiptClock(requestClock("plan"), planSha)
        require(plan.getLong("expiresAtEpochMs") - currentHostUpperBound() in 60000..1800000)
        val target = checkNotNull(HostReadCodec.target(plan.getJSONObject("target")))
        require(target.agentId == plan.getString("provider") && target.agentId in listOf("codex", "easywork"))
        require(com.agenthub.core.DomainContract.validate(plan.getJSONObject("target").toString(), "target") == null)
        val excluded = plan.getJSONArray("excludedCodingThreadIds").strings()
        require(excluded.size == 6 && excluded.distinct().size == 6 && plan.getString("threadId").isNotBlank() && plan.getString("threadId") !in excluded)
        require(plan.opt("businessChatAuthorized") == true)
        val baseline = plan.getJSONArray("baselineTurnIds").strings()
        require(baseline.distinct().size == baseline.size && baseline.all { it.isNotBlank() })
        require(if(singleOrigin)baseline.size<=64 else if (scenario == "q03") baseline.isEmpty() else baseline.size <= 1)
        val settings = plan.getJSONObject("settings")
        require(settings.getString("effort") == "xhigh" && settings.getString("model").isNotBlank())
        require(listOf("speed", "serviceTier", "service_tier").none { settings.has(it) })
        if (target.agentId == "codex") require(settings.getString("model") == "gpt-6.1-sol" && settings.getString("permissionMode") == "full-access")
        hex(plan.getString("desktopExecutableSha256"))
        val mapping = privateJson(File(folder, "local-mapping.json"), plan.getString("localMappingSha256"))
        require(mapping.getInt("version") == 1 && mapping.getString("sourceAuthority") == "independent-local-desktop-and-host")
        require(mapping.getString("controlHostId") == plan.getString("controlHostId") && mapping.getString("threadId") == plan.getString("threadId"))
        require(sameScope(HostReadCodec.target(mapping.getJSONObject("actualTarget")), target))
        require(mapping.getString("endpoint") in plan.getJSONArray("allowedEndpoints").strings())
        val environment = mapping.getJSONObject("environment")
        require(environment.opt("processAndInstallVerified") == true && environment.opt("originalOwnerVerified") == true && environment.getString("threadId") == plan.getString("threadId"))
        require(environment.getString("fingerprint") == plan.getString("desktopRuntimeFingerprint") && environment.getJSONObject("identity").getString("executableSha256") == plan.getString("desktopExecutableSha256"))

        if (target.agentId == "codex") {
            val proof = privateJson(File(folder, "product-directory-proof.json"), plan.getString("productDirectoryProofSha256"))
            require(proof.getString("hostId") == target.hostId && proof.getString("runtimeMode") == "desktop-runtime")
            require(proof.getInt("workspaceStatus") == 200 && proof.opt("qaInWorkspace") == true) { "Real QA absent from product directory; never inject a MobileThread or send a placeholder" }
            val history = proof.getJSONObject("history")
            require(history.getInt("status") == 200 && history.getString("threadId") == plan.getString("threadId") && history.opt("ready") == true && history.getJSONObject("runtime").opt("canControl") == true)
            val clock = receiptClock(requestClock("product-directory-proof"), plan.getString("productDirectoryProofSha256"))
            require(clock.ageWithin(java.time.Instant.parse(proof.getString("observedAt")).toEpochMilli(), SystemClock.elapsedRealtime(), 30000) != null) {
                "Product directory proof exceeded its conservative 30-second freshness budget"
            }
        }
        if(singleOrigin) {
            val authority=privateJson(File(folder,"root-authority.json"),hex(plan.getString("rootAuthoritySha256")))
            if(independent)independentAdmission=FreshQ04IndependentBudgetCase.validate(plan,authority,currentHostUpperBound())
            else if(successor)successorAdmission=FreshQ04KnownZeroSuccessorCase.validate(plan,authority,currentHostUpperBound())
            else FreshQ04SingleOriginCase.validate(plan,authority,currentHostUpperBound())
            val stageStat=android.system.Os.lstat(folder.path)
            require(android.system.OsConstants.S_ISDIR(stageStat.st_mode)&&stageStat.st_uid==android.os.Process.myUid()&&stageStat.st_mode and 0xfff==0x1c0)
            freshScope=FreshWriteScope(target,plan.getString("threadId"),run,plan.getString("leaseId"),
                plan.getString("budgetOperationId"),plan.getString("nonce"),planSha,plan.getString("rootAuthoritySha256"),
                plan.getLong("expiresAtEpochMs"),leaseClock,plan.getJSONArray("allowedEndpoints").strings().toSet(),plan.getString("certificateSha256"),stageStat.st_dev,stageStat.st_ino)
        }
        val expected = if(singleOrigin)setOf("origin","queueCancel","queueExecute","steer") else if (scenario == "q03") setOf("q03") else setOf("origin", "queueCancel", "queueExecute", "steer", "stopOrigin")
        require(plan.getJSONObject("slots").keys().asSequence().toSet() == expected && plan.getInt("maxTextSubmissions") == expected.size)
        val ids = expected.map { name -> slot(name).let { row ->
            require(row.getJSONArray("attachments").length() == 0 && row.getString("text").isNotBlank() && sha(row.getString("text")) == hex(row.getString("textSha256")))
            require(!row.has("operationId") && !row.has("clientMessageId")); uuid(row.getString("slotId"))
        } }
        require(ids.distinct().size == ids.size)
        require(expected.map { slot(it).getString("textSha256") }.distinct().size == expected.size)
        if (scenario == "q04") {
            require(plan.getInt("maxChildStarts") == 0 && plan.getInt("maxToolSeconds") in 40..45)
            val command = "python3 -c \"import time; time.sleep(40)\""
            require(sha(command) == plan.getString("toolCommandSha256"))
            val directory = plan.getString("modelToolWorkingDirectory")
            require(directory.startsWith("/") && "/../" !in directory)
            for (name in if(singleOrigin)listOf("origin")else listOf("origin", "stopOrigin")) require(slot(name).getString("text").contains(command) &&
                slot(name).getString("text").contains(directory) && slot(name).getString("toolWorkdir") == directory)
        } else require(plan.getInt("maxChildStarts") in 0..1 && (plan.getInt("maxChildStarts") == 0 || target.agentId == "codex"))
    }
    private fun slot(name: String) = plan.getJSONObject("slots").getJSONObject(name)
    private fun target() {
        if(freshSingleOrigin)freshAbortAbsent()
        profile.checkOnlyApprovedTarget()
        require(session.selectedThread == plan.getString("threadId") && session.runtime?.canControl == true)
        require(session.attachments.isEmpty() && currentHostUpperBound() < plan.getLong("expiresAtEpochMs"))
        activeWindow?.let { require(it.receipt.ageWithin(it.startedAtHostMs, SystemClock.elapsedRealtime(), 45000) != null) { "Bounded original tool window ended; never create a replacement Turn" } }
    }
    /** Independent controller writes a fresh private proof and SHA sidecar, never a model response. */
    private fun gate(action: String, turn: String? = null, originalOperation: String? = null): JSONObject {
        target()
        val clockRequest = requestClock(action)
        val file = File(folder, "$action-desktop.json"); val sidecar = File(folder, "$action-desktop.sha256")
        ui.waitUntil(10000) { file.exists() && sidecar.exists() }
        val proofSha = privateSidecarSha(sidecar)
        val g = privateJson(file, proofSha)
        require(g.getString("run") == plan.getString("run") && g.getString("leaseId") == plan.getString("leaseId") &&
            g.getString("planSha256") == planSha && g.getString("action") == action && g.getString("threadId") == session.selectedThread)
        require(sameScope(HostReadCodec.target(g.getJSONObject("target")), profile.permit.target))
        require(g.opt("ownerVerified") == true)
        if(freshSingleOrigin)require(g.opt("controllerCleanupStarted")==false) {"Controller cleanup cannot count as Native UI acceptance"}
        require(g.opt("desktopRuntimeFingerprintVerified") == true &&
            g.optional("desktopRuntimeFingerprint") == plan.getString("desktopRuntimeFingerprint")) { "Independent current runtime/process/install fingerprint proof is required before any business action" }
        require(g.getString("sourceAuthority") == if (plan.getString("provider") == "codex") "original-desktop-ipc" else "easywork-original-electron")
        val clock = receiptClock(clockRequest, proofSha)
        require(clock.ageWithin(g.getLong("observedAtEpochMs"), SystemClock.elapsedRealtime(), 30000) != null) {
            "Original desktop proof exceeded its conservative 30-second freshness budget"
        }
        require(checkNotNull(clock.hostNowUpperBound(SystemClock.elapsedRealtime())) < plan.getLong("expiresAtEpochMs"))
        require(g.optional("activeTurnId") == turn)
        if (originalOperation != null) require(g.getString("subjectOperationId") == originalOperation)
        if (turn != null && action !in listOf("after-origin", "after-stopOrigin")) require(session.running && session.runtime?.turnId == turn)
        target() // Waiting for a proof must not extend the original tool or lease window.
        return g.put("verifiedFileSha256", proofSha)
            .put("verifiedHostTransferCompletedAtEpochMs", clock.transferredAtHostMs)
            .put("verifiedDeviceElapsedAtRequestMs", clock.requestedAtDeviceElapsedMs)
    }
    private fun originalTurns(proof: JSONObject): Set<String> {
        val ids = proof.getJSONArray("originalTurns").objects().map { it.getString("id") }
        require(ids.distinct().size == ids.size && ids.all { it.isNotBlank() })
        return ids.toSet()
    }
    private fun expectedTurns() = (if(continuing) plan.getJSONObject("continuation").getJSONArray("freshBaselineTurnIds")
        else plan.getJSONArray("baselineTurnIds")).strings().toSet() + submittedTurns
    private fun claim(action: String, turn: String? = null, operation: String? = null): JSONObject {
        if(freshSingleOrigin)freshNewWritesAllowed()
        val proof = gate(action, turn, operation)
        require(originalTurns(proof) == expectedTurns())
        publish("claim-$action.json", JSONObject().put("run", plan.getString("run")).put("leaseId", plan.getString("leaseId"))
            .put("planSha256", planSha).put("action", action).put("threadId", session.selectedThread)
            .put("slot", plan.getJSONObject("slots").optJSONObject(action)).put("expectedTurnId", turn)
            .put("originalOperationId", operation).put("desktopProofSha256", proof.getString("verifiedFileSha256")).put("claimedAtEpochMs", System.currentTimeMillis()))
        return proof
    }
    private fun businessBoundary(proof: JSONObject, turn: String?) {
        if(freshSingleOrigin)freshNewWritesAllowed()
        target()
        val clock = NativeProofClock.Receipt(proof.getLong("verifiedHostTransferCompletedAtEpochMs"),
            proof.getLong("verifiedDeviceElapsedAtRequestMs"))
        require(clock.ageWithin(proof.getLong("observedAtEpochMs"), SystemClock.elapsedRealtime(), 30000) != null) {
            "Original proof expired before the business gesture; never refresh a claim or replay"
        }
        if (turn == null) require(!session.running && session.runtime?.turnId == null)
        else require(session.running && session.runtime?.turnId == turn)
    }
    private fun addSuppressedSafely(primary: Throwable,secondary: Throwable) {
        if(primary!==secondary)runCatching {primary.addSuppressed(secondary)}
    }
    /** Every cleanup runs. A primary body failure keeps its identity; otherwise throw the first cleanup failure. */
    private fun completeAllCleanup(primary: Throwable?,vararg actions: ()->Unit) {
        var pending=primary
        for(action in actions) {
            try {action()} catch(next: Throwable) {
                val previous=pending
                if(previous==null)pending=next else addSuppressedSafely(previous,next)
            }
        }
        if(primary==null)pending?.let {throw it}
    }
    private fun failureScopeMatches(): Boolean = session.selectedClient.id==profile.permit.target.hostId &&
        sameScope(session.selectedClient.scope,profile.permit.target) && session.selectedThread==plan.getString("threadId")
    private fun requireFailureScope() {require(failureScopeMatches()) {"Diagnostic scope is not the original QA target and thread"}}
    private fun diagnosticMachineIdentity(value: com.agenthub.core.TargetScope?): JSONObject = JSONObject()
        .put("agentId",value?.agentId ?: JSONObject.NULL).put("hostId",value?.hostId ?: JSONObject.NULL)
        .put("workspaceId",value?.workspaceId ?: JSONObject.NULL).put("runtimeId",value?.runtimeId ?: JSONObject.NULL)
    /** Identity fields only: this function cannot inspect drafts, operations or foreign content. Main owns the read. */
    private fun failureScopeIdentity(): JSONObject = JSONObject().put("expectedTarget",diagnosticMachineIdentity(profile.permit.target))
        .put("expectedThreadId",plan.getString("threadId")).put("observedTarget",diagnosticMachineIdentity(session.selectedClient.scope))
        .put("observedClientHostId",session.selectedClient.id).put("observedThreadId",session.selectedThread)
        .put("scopeMismatch",!failureScopeMatches())
    private fun failureReconciliationRequired(failure: Throwable) {
        val value=ui.runOnIdle {
            val identity=failureScopeIdentity()
            if(identity.getBoolean("scopeMismatch"))identity else {
                val rows=session.operationRecords.filter {it.kind=="reply" && it.submissionAttempted}
                JSONObject().put("version",1).put("kind","failed-native-original-ids-query-only-v1")
                    .put("scopeIdentity",identity).put("originalFailureClass",failure.javaClass.name)
                    .put("target",CodexCommandCodec.target(profile.permit.target)).put("threadId",session.selectedThread)
                    .put("runtimeTurnId",session.runtime?.turnId ?: JSONObject.NULL).put("running",session.running)
                    .put("knownQueuedOperationIds",JSONArray(session.queuedReplies.map {it.operationId}))
                    .put("originalRecords",JSONArray(rows.map {JSONObject().put("operationId",it.id).put("intentSha256",it.intentSha256).put("lastKnownPhase",it.phase)}))
                    .put("remoteTerminalOrQueueAbsenceProven",false).put("cancellationSubmittedByFailureHandler",false)
                    .put("onUnknown","query-original-only").put("replacementInputOrTurnAllowed",false)
            }
        }
        publish("failure-reconciliation-required.json",value)
    }
    private fun captureFailure(failure: Throwable) {
        if(!::activity.isInitialized)return
        val identity=ui.runOnIdle {failureScopeIdentity()}
        if(identity.getBoolean("scopeMismatch")) {
            publish("failure-scope-mismatch.json",identity)
            return // No foreign operation, draft, UI capture or original-ID query.
        }
        completeAllCleanup(failure,{generationDiagnostic("catch")},{snapshot("failure-original-state.json")},
            {failureReconciliationRequired(failure)},{queryOriginalRecords()})
    }
    private fun snapshot(name: String) {
        val value=ui.runOnIdle {
            requireFailureScope()
            JSONObject().put("target",CodexCommandCodec.target(profile.permit.target)).put("threadId",session.selectedThread)
                .put("running",session.running).put("turnId",session.runtime?.turnId).put("draftSha256",sha(session.text))
                .put("records",JSONArray(session.operationRecords.map {it.body()})).put("profile",profile.proof())
        }
        publish(name,value)
    }
    private val freshLastMainCallback=AtomicReference<JSONObject>()
    /** A timeout stops waiting; it cannot cancel an already executing Main callback. No Main waits on test. */
    private fun freshMainBounded(maximum: Long=1000, action: ()->JSONObject): JSONObject {
        check(freshSingleOrigin&&Looper.myLooper()!==Looper.getMainLooper())
        val handler=Handler(Looper.getMainLooper());val end=SystemClock.elapsedRealtime()+maximum
        val done=CountDownLatch(1);val result=AtomicReference<JSONObject>();val admissible=AtomicBoolean(true)
        val task=Runnable {
            if(!admissible.get() || SystemClock.elapsedRealtime()>=end)return@Runnable
            try {result.set(action())}catch(failure: Throwable) {result.set(JSONObject().put("callbackErrorClass",failure.javaClass.name))}
            finally {done.countDown()}
        }
        if(!handler.post(task))return JSONObject().put("mainResponded",false).put("reason","handler-post-rejected")
        val completed=done.await(maximum,TimeUnit.MILLISECONDS)
        if(!completed){admissible.set(false);handler.removeCallbacks(task)}
        return JSONObject().put("mainResponded",completed).put("callbackMayStillBeInFlight",!completed)
            .put("observedAtElapsedMs",SystemClock.elapsedRealtime()).put("value",if(completed)result.get() else JSONObject.NULL)
            .also {freshLastMainCallback.set(JSONObject(it.toString()))}
    }
    private fun freshSourceFrames(thread: Thread?) = JSONObject().put("threadId",thread?.id ?: JSONObject.NULL)
        .put("alive",thread?.isAlive ?: JSONObject.NULL).put("state",thread?.state?.name ?: JSONObject.NULL)
        .put("frames",JSONArray(thread?.stackTrace.orEmpty().take(96).map {
            JSONObject().put("class",it.className.take(256)).put("method",it.methodName.take(256))
                .put("file",it.fileName?.take(128) ?: JSONObject.NULL).put("line",it.lineNumber)
        }))
    private fun freshIntentFilter(intent: Intent, expected: Intent) = JSONObject().put("filterEqualsExpected",intent.filterEquals(expected))
        .put("action",intent.action ?: JSONObject.NULL).put("component",intent.component?.flattenToString() ?: JSONObject.NULL)
        .put("package",intent.`package` ?: JSONObject.NULL).put("type",intent.type ?: JSONObject.NULL)
        .put("categories",JSONArray(intent.categories.orEmpty().sorted())).put("flags",intent.flags)
        .put("dataIsNull",intent.data==null).put("dataSha256",intent.dataString?.let {sha(it.toByteArray(Charsets.UTF_8))} ?: JSONObject.NULL)
        .put("extrasPresent",intent.extras!=null) // No arbitrary extras values or URI body is exported.
    private fun freshProfileFlags(profile: Q02RealReadInstallation.Profile) = JSONObject().put("run",profile.permit.run)
        .put("created",profile.created).put("destroyed",profile.destroyed).put("closed",profile.closed)
        .put("claimed",profile.claimed).put("pairingAttempted",profile.pairingAttempted)
        .put("storageConstructed",profile.storage!=null).put("sessionConstructed",profile.session!=null)
        .put("existingOwnedResume",profile.existing!=null)

    /** One public named-permission lease. No hidden getter/reflection or persistent settings. */
    private inner class FreshStartPermissionLease(private val startCutoff: Long) {
        private val automation=AtomicReference<UiAutomation>()
        private val stopRequested=AtomicBoolean(false);private val entered=AtomicBoolean(false)
        private val adoptionEntered=AtomicBoolean(false);private val adoptionEnded=AtomicBoolean(false)
        private val adoptionReturned=AtomicBoolean(false);private val dropClaimed=AtomicBoolean(false)
        private val dropReturned=AtomicBoolean(false);val restored=AtomicBoolean(false)
        private val before=AtomicReference<JSONObject>();private val during=AtomicReference<JSONObject>();private val after=AtomicReference<JSONObject>()
        private val dropError=AtomicReference<String>();private val dropDone=CountDownLatch(1)
        private fun open()=!stopRequested.get()&&SystemClock.elapsedRealtime()<startCutoff
        private fun specifiedPermission()=JSONObject().put("observedAtElapsedMs",SystemClock.elapsedRealtime())
            .put("shellPermission",context.packageManager.checkPermission(startupPermissionName,"com.android.shell"))
            .put("selfPermission",context.checkSelfPermission(startupPermissionName))
        private fun requireState(value: JSONObject,self: Int) {
            require(value.getInt("shellPermission")==PackageManager.PERMISSION_GRANTED&&value.getInt("selfPermission")==self)
        }
        fun runOriginalStart(start: ()->Unit) {
            check(entered.compareAndSet(false,true));var original: Throwable?=null
            try {
                check(open()) {"Original startup permission deadline closed"}
                val actual=checkNotNull(InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY))
                automation.set(actual);check(open()) {"Late permission connection must not start an Activity"}
                specifiedPermission().also {before.set(it);requireState(it,PackageManager.PERMISSION_DENIED)}
                check(open());adoptionEntered.set(true)
                try {actual.adoptShellPermissionIdentity(startupPermissionName);adoptionReturned.set(true)}
                finally {adoptionEnded.set(true)}
                specifiedPermission().also {during.set(it);requireState(it,PackageManager.PERMISSION_GRANTED)}
                check(open()) {"No late Activity start after permission preparation"}
                start() // Exactly the existing explicit same-UID Q02 Context.startActivity boundary.
            }catch(failure: Throwable){original=failure;throw failure}
            finally {
                try {release()}catch(cleanup: Throwable) {
                    val failure=original
                    if(failure!=null) {if(failure!==cleanup)runCatching {failure.addSuppressed(cleanup)}} else throw cleanup
                }
            }
        }
        private fun release() {
            // An in-flight adoption has not ended: its original worker must release when it returns.
            if(!adoptionEntered.get()||!adoptionEnded.get()||!dropClaimed.compareAndSet(false,true))return
            try {
                checkNotNull(automation.get()).dropShellPermissionIdentity();dropReturned.set(true)
                specifiedPermission().also {after.set(it);requireState(it,PackageManager.PERMISSION_DENIED)}
                restored.set(true)
            }catch(failure: Throwable){dropError.set(failure.javaClass.name);throw failure}
            finally {dropDone.countDown()}
        }
        fun stopAndRelease(): Thread? {
            stopRequested.set(true)
            if(!adoptionEntered.get()||!adoptionEnded.get()||dropClaimed.get())return null
            return Thread({runCatching {release()}},"q04-no-model-permission-release").apply {isDaemon=true;start()}
        }
        fun snapshot()=JSONObject().put("run",plan.getString("run")).put("leaseId",plan.getString("leaseId")).put("budgetOperationId",plan.getString("budgetOperationId")).put("pid",android.os.Process.myPid())
            .put("permission",startupPermissionName).put("readbackScope","specified-permission-only-public-api")
            .put("fullAdoptedPermissionSetReadback","unavailable-in-public-sdk").put("fullAdoptedPermissionSet",JSONObject.NULL)
            .put("adoptCallArguments",JSONArray(listOf(startupPermissionName))).put("adoptCallEntered",adoptionEntered.get()).put("adoptCallReturned",adoptionReturned.get())
            .put("adoptCallEnded",adoptionEnded.get()).put("dropCallClaimed",dropClaimed.get()).put("dropCallReturned",dropReturned.get())
            .put("dropCallFinished",dropDone.count==0L).put("specifiedPermissionRestored",restored.get()).put("dropErrorClass",dropError.get() ?: JSONObject.NULL)
            .put("before",before.get() ?: JSONObject.NULL).put("during",during.get() ?: JSONObject.NULL).put("after",after.get() ?: JSONObject.NULL)
            .put("newStartGateClosed",stopRequested.get()||SystemClock.elapsedRealtime()>=startCutoff)
            .put("persistentPermissionOrAppOpsSettingsMutation",false)
    }

    /** Same actual Main/Session lifecycle, but never Instrumentation.startActivitySync/queueIdle. */
    private inner class FreshLifecycleLaunch(private val profile: Q02RealReadInstallation.Profile, val expected: Intent) {
        val resumed=CountDownLatch(1);val destroyed=CountDownLatch(1)
        val activity=AtomicReference<Q02RealReadActivity>();val stage=AtomicReference<String>("not-observed")
        val events=ConcurrentLinkedQueue<String>();val requestReturned=AtomicBoolean(false)
        val requestEntered=AtomicBoolean(false);val requestError=AtomicReference<String>()
        val allowRequest=AtomicBoolean(true);val retired=AtomicBoolean(false);val registered=AtomicBoolean(false)
        private var launchDeadline=0L
        var permission: FreshStartPermissionLease?=null;private set
        var caller: Thread?=null;private set
        private val callback=ActivityLifecycleCallback { value, observed ->
            if(retired.get() || value.javaClass!=Q02RealReadActivity::class.java)return@ActivityLifecycleCallback
            val own=runCatching {(value as Q02RealReadActivity).profile===profile}.getOrDefault(false)
            if(!own)return@ActivityLifecycleCallback // No foreign profile content, ID, intent or Session read.
            val actual=value as Q02RealReadActivity
            activity.compareAndSet(null,actual);stage.set(observed.name)
            if(events.size<32)events.add(JSONObject().put("stage",observed.name).put("elapsedMs",SystemClock.elapsedRealtime()).toString())
            if(observed==Stage.RESUMED)resumed.countDown()
            if(observed==Stage.DESTROYED)destroyed.countDown()
        }
        fun register()=freshMainBounded {ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback);registered.set(true);JSONObject().put("registered",true)}
        fun start() {
            check(freshSingleOrigin && registered.get() && caller==null)
            launchDeadline=SystemClock.elapsedRealtime()+20000
            val lease=if(startupPermissionRequired)FreshStartPermissionLease(launchDeadline)else null;permission=lease
            caller=Thread({
                if(!allowRequest.compareAndSet(true,false) || SystemClock.elapsedRealtime()>=launchDeadline)return@Thread
                try {
                    val dispatch={requestEntered.set(true);context.startActivity(expected);requestReturned.set(true);Unit}
                    if(lease!=null)lease.runOriginalStart(dispatch)else dispatch()
                }catch(failure: Throwable){requestError.set(failure.javaClass.name)}
            },"q04-fresh-start-request").apply {isDaemon=true;start()}
        }
        fun launchBounded(): Q02RealReadActivity {
            freshMainValue(register());start()
            require(resumed.await((launchDeadline-SystemClock.elapsedRealtime()).coerceAtLeast(0),TimeUnit.MILLISECONDS)) {"Fresh RESUMED not observed in20s; original Activity request may be in flight"}
            val left=(launchDeadline-SystemClock.elapsedRealtime()).coerceAtLeast(0);val join=if(startupPermissionRequired)left else minOf(left,100);if(join>0)caller?.join(join)
            require(requestReturned.get()&&requestError.get()==null&&caller?.isAlive!=true) {"Fresh Activity request return remains unknown"}
            val initial=freshMainValue(snapshot())
            require(initial.optJSONObject("profile")?.opt("pairingAttempted")==false&&initial.opt("realClientCount")==0&&initial.opt("operationCount")==0)
            if(startupPermissionRequired)require(permission?.restored?.get()==true) {"Specified startup permission restore is unconfirmed; do not pair"}
            return checkNotNull(activity.get())
        }
        fun snapshot(includeSession: Boolean=true,maximum: Long=1000)=freshMainBounded(maximum) {
            val actual=activity.get()
            val value=JSONObject().put("profile",freshProfileFlags(profile)).put("lastObservedStage",stage.get())
                .put("activityObserved",actual!=null).put("events",JSONArray(events.toList().map(::JSONObject)))
            if(actual!=null && actual.profile===profile) {
                value.put("lifecycleStage",ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(actual).name)
                    .put("isFinishing",actual.isFinishing).put("isDestroyed",actual.isDestroyed)
                    .put("intentFilter",freshIntentFilter(actual.intent,expected))
                if(includeSession) {
                    val state=runCatching {actual.session}.getOrNull()
                    value.put("sessionAvailable",state!=null).put("restoring",state?.restoring ?: JSONObject.NULL)
                        .put("realClientCount",state?.clients?.count {!it.isDemo} ?: JSONObject.NULL)
                        .put("operationCount",state?.operationRecords?.size ?: JSONObject.NULL)
                }
            }
            value
        }
        fun requestFinish()=freshMainBounded {
            val actual=checkNotNull(activity.get());check(actual.profile===profile)
            if(!actual.isDestroyed && !actual.isFinishing)actual.finish()
            JSONObject().put("finishRequested",true).put("destroyedNow",actual.isDestroyed)
        }
        fun awaitLocalRestore() {
            val end=SystemClock.elapsedRealtime()+30000
            while(SystemClock.elapsedRealtime()<end) {
                val budget=(end-SystemClock.elapsedRealtime()).coerceIn(1,1000)
                val value=freshMainValue(snapshot(maximum=budget))
                if(value.opt("sessionAvailable")==true&&value.opt("restoring")==false)return
                val left=end-SystemClock.elapsedRealtime();if(left>0)Thread.sleep(minOf(25,left))
            }
            error("Fresh local restore did not finish in the original30s window")
        }
        fun finishBounded() {
            check(activity.get()!=null) {"No owned Activity observed; no close was fabricated"}
            val end=SystemClock.elapsedRealtime()+10000
            freshMainValue(requestFinish())
            require(destroyed.await((end-SystemClock.elapsedRealtime()).coerceAtLeast(0),TimeUnit.MILLISECONDS)) {"Fresh DESTROYED not observed in10s; retain original profile"}
        }
        fun retire(): JSONObject {
            allowRequest.set(false) // Does not withdraw a startActivity request already inside Binder.
            val result=freshMainBounded {
                if(registered.getAndSet(false))ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
                JSONObject().put("callbackUnregistered",true)
            }
            retired.set(true)
            return result
        }
    }

    private fun freshMainValue(response: JSONObject): JSONObject {
        require(response.opt("mainResponded")==true) {"Fresh Main callback did not respond; state unknown"}
        return response.getJSONObject("value").also {require(!it.has("callbackErrorClass")) {"Fresh Main callback failed; see bounded evidence"}}
    }
    private fun publishFreshLifecycle(name: String,value: JSONObject) {
        check(freshSingleOrigin);require(name in setOf("fresh-startup-failure.json","fresh-lifecycle-resource-state.json","fresh-startup-permission-state.json"))
        require(value.toString().toByteArray(Charsets.UTF_8).size<=65536);publish(name,value)
    }
    private fun captureFreshStartupFailure(failure: Throwable,value: FreshLifecycleLaunch?) {
        check(freshSingleOrigin)
        val preceding=freshLastMainCallback.get()
        val observed=value?.snapshot() ?: freshMainBounded {
            JSONObject().put("profile",if(::profile.isInitialized)freshProfileFlags(profile) else JSONObject.NULL)
                .put("activityObserved",false).put("activityState","unknown")
        }
        publishFreshLifecycle("fresh-startup-failure.json",JSONObject().put("kind","fresh-q04-startup-before-pairing-failure-v1")
            .put("run",plan.getString("run")).put("originalFailureClass",failure.javaClass.name)
            .put("mainStack",freshSourceFrames(Looper.getMainLooper().thread)).put("startWorkerStack",freshSourceFrames(value?.caller))
            .put("precedingMainCallback",preceding ?: JSONObject.NULL).put("mainSnapshot",observed).put("requestEntered",value?.requestEntered?.get() ?: JSONObject.NULL)
            .put("requestReturned",value?.requestReturned?.get() ?: JSONObject.NULL).put("requestErrorClass",value?.requestError?.get() ?: JSONObject.NULL)
            .put("startupCompleted",false).put("mainStateUnknown",observed.opt("mainResponded")!=true||observed.optJSONObject("value")?.has("callbackErrorClass")==true).put("startRequestCancelled",false).put("profileDeletionAttempted",false)
            .put("uiIdleWaitUsed",false).put("composerOrOperationBodiesExported",false).put("originalIdsQueried",false))
    }
    private fun launch(scenarioName: String,singleOrigin: Boolean=false,successor: Boolean=false,independent: Boolean=false,work: () -> Unit) {
        load(scenarioName,singleOrigin=singleOrigin,successor=successor,independent=independent)
        val pairing = privateJson(File(folder, "new-binding-input.json"), plan.getString("pairingFileSha256")).getString("input")
        require(sha(pairing) == plan.getString("pairingIntentSha256"))
        val remaining = plan.getLong("expiresAtEpochMs") - currentHostUpperBound()
        val permit = Q02RealReadInstallation.Permit(plan.getString("run"), plan.getString("leaseId"), plan.getString("installationAndroidIdSha256"),
            plan.getString("appSha256"), plan.getString("runnerSha256"), plan.getString("signerSha256"), plan.getInt("api"),
            SystemClock.elapsedRealtime()+remaining, plan.getString("controlHostId"), plan.getString("localMappingSha256"),
            checkNotNull(HostReadCodec.target(plan.getJSONObject("target"))), plan.getString("endpoint"), plan.getJSONArray("allowedEndpoints").strings(),
            plan.getString("certificateSha256"), plan.getString("pairingIntentSha256"), plan.optional("desktopPublicKeySha256"))
        var scenario: ActivityScenario<Q02RealReadActivity>? = null
        var freshLifecycle: FreshLifecycleLaunch?=null
        var freshStartupReady=false;var freshDestroyedConfirmed=false;var freshWriterCloseReturned=false
        var primaryFailure: Throwable?=null
        try {
            if(freshSingleOrigin) {
                freshMainValue(freshMainBounded(3000) {
                    val lifecycle=ActivityLifecycleMonitorRegistry.getInstance()
                    require(Stage.values().filter {it!=Stage.DESTROYED}.all {stage->lifecycle.getActivitiesInStage(stage).none {it is MainActivity}})
                    profile=Q02RealReadInstallation.prepare(context,permit)
                    if(independentBudget)profile.bindIndependentBudgetReplyDispatchFence(checkNotNull(independentAdmission),::freshOriginalReplyDispatch,::freshBindingIntentObserved)
                    else if(knownZeroSuccessor)profile.bindKnownZeroSuccessorReplyDispatchFence(checkNotNull(successorAdmission),::freshOriginalReplyDispatch,::freshBindingIntentObserved)
                    else profile.bindFreshQ04ReplyDispatchFence(::freshOriginalReplyDispatch,::freshBindingIntentObserved)
                    JSONObject().put("profilePrepared",true)
                })
                require(::profile.isInitialized)
                publish("profile-prepared.json",profile.proof())
                val active=FreshLifecycleLaunch(profile,Intent(context,Q02RealReadActivity::class.java).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                freshLifecycle=active;activity=active.launchBounded();active.awaitLocalRestore()
            } else {
            instrumentation.runOnMainSync {
                val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
                require(Stage.values().filter { it != Stage.DESTROYED }.all { stage -> lifecycle.getActivitiesInStage(stage).none { it is MainActivity } })
                profile = Q02RealReadInstallation.prepare(context, permit)
                if(freshSingleOrigin)profile.bindFreshQ04ReplyDispatchFence(::freshOriginalReplyDispatch,::freshBindingIntentObserved)
            }
            publish("profile-prepared.json", profile.proof())
            val launched = ActivityScenario.launch<Q02RealReadActivity>(Intent(context, Q02RealReadActivity::class.java).setAction(Intent.ACTION_MAIN))
            scenario = launched
            launched.onActivity { activity = it }
            ui.waitUntil(30000) { !session.restoring }
            }
            require(session.clients.none { !it.isDemo } && session.operationRecords.isEmpty() && session.text.isEmpty())
            if(freshSingleOrigin)freshStartupReady=true
            runBlocking { withTimeout(120000) { withContext(Dispatchers.Main) { Q02RealReadInstallation.pairFreshTarget(profile, pairing) } } }
            if(freshSingleOrigin)freshPublishEvidence("fresh-binding-proof.json",ui.runOnIdle {profile.proof()})
            ui.waitUntil(30000) { !session.loading && !session.historyLoading }
            var pages = 0
            while (session.threads.none { it.id == plan.getString("threadId") } && session.workspace?.nextThreadCursor != null && pages++ < 10) {
                ui.runOnIdle { session.refresh(more=true) }; ui.waitUntil(30000) { !session.loading }
            }
            ui.runOnIdle { session.selectThread(session.threads.single { it.id == plan.getString("threadId") }) }
            ui.waitUntil(30000) { !session.historyLoading && session.runtime != null }
            target(); require(!session.running && session.queuedReplies.isEmpty() && session.operationRecords.isEmpty())
            configureNative(); work()
            require(records.size == plan.getInt("maxTextSubmissions"))
            snapshot("complete.json")
        } catch (failure: Throwable) {
            primaryFailure=failure
            if(freshSingleOrigin&&!freshStartupReady) {
                runCatching {captureFreshStartupFailure(failure,freshLifecycle)}.exceptionOrNull()?.let {addSuppressedSafely(failure,it)}
            } else {
            runCatching {captureFailure(failure)}.exceptionOrNull()?.let {addSuppressedSafely(failure,it)}
            }
            throw failure
        } finally {
            completeAllCleanup(primaryFailure,
                {if(startupPermissionRequired) {
                    val permission=freshLifecycle?.permission;val cleanup=permission?.stopAndRelease()
                    if(cleanup!=null)cleanup.join(1000)
                    publishFreshLifecycle("fresh-startup-permission-state.json",permission?.snapshot() ?: JSONObject().put("specifiedPermissionRestored",false))
                    if(primaryFailure==null)require(permission?.restored?.get()==true&&cleanup?.isAlive!=true)
                }},
                {if(freshSingleOrigin) {
                    val current=freshLifecycle ?: error("Fresh Activity launch was not observed; retain profile")
                    current.finishBounded();freshDestroyedConfirmed=true
                } else {scenario?.close()}},
                {if(::profile.isInitialized && !profile.closed) {
                    if(freshSingleOrigin)check(freshDestroyedConfirmed) {"Fresh actual DESTROYED remains unconfirmed; do not close a live profile"}
                    check(profile.destroyed) {"Owned profile remains live; resource release is incomplete"}
                    runBlocking {withContext(Dispatchers.Main) {Q02RealReadInstallation.close(profile)}}
                    if(freshSingleOrigin)freshWriterCloseReturned=true
                }},
                {if(::profile.isInitialized)publish("profile-resource-state.json",profile.proof()
                    .put("retainUntil","controller evidence and new-binding revocation review")
                    .also {if(freshSingleOrigin)it.put("writerCloseReturned",freshWriterCloseReturned);if(startupPermissionRequired)it.put("startupSpecifiedPermissionRestored",freshLifecycle?.permission?.restored?.get()==true)})},
                {if(freshSingleOrigin) {
                    val current=freshLifecycle;val retired=current?.retire()
                    publishFreshLifecycle("fresh-lifecycle-resource-state.json",JSONObject().put("run",plan.getString("run"))
                        .put("actualDestroyedConfirmed",freshDestroyedConfirmed).put("callbackRemoval",retired ?: JSONObject.NULL)
                        .put("mainStack",freshSourceFrames(Looper.getMainLooper().thread)).put("startWorkerStack",freshSourceFrames(current?.caller))
                        .put("startRequestCancelled",false)
                        .put("profileDeletionAttempted",false).put("independentUidProcessReadStillRequired",true))
                    if(primaryFailure==null)require(retired?.opt("mainResponded")==true&&retired?.optJSONObject("value")?.opt("callbackUnregistered")==true)
                }})
        }
    }
    private fun shot(name: String) {
        ui.waitForIdle()
        val view = activity.window.decorView
        require(view.isAttachedToWindow && view.hasWindowFocus() && activity.packageName == context.packageName)
        require(view.width in 1..4096 && view.height in 1..4096)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        val latch = java.util.concurrent.CountDownLatch(1); var result = -1
        try {
            instrumentation.runOnMainSync { android.view.PixelCopy.request(activity.window, image, { result = it; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper())) }
            require(latch.await(5, java.util.concurrent.TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
            val file = File(folder, name + ".png"); require(file.createNewFile()); android.system.Os.chmod(file.path, 0x180)
            file.outputStream().use { require(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
    /** Public own-process window inspection only. Never copy the whole display. */
    private fun ownGenerationDiagnosticWindow(): android.view.Window? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        fun ownContext(value: android.content.Context): Boolean {
            var current = value
            repeat(16) {
                if (current === activity) return true
                val wrapped = current as? android.content.ContextWrapper ?: return false
                if (wrapped.baseContext === current) return false
                current = wrapped.baseContext
            }
            return false
        }
        val roots = android.view.inspector.WindowInspector.getGlobalWindowViews().filter {
            it.isAttachedToWindow && it.hasWindowFocus() && ownContext(it.context)
        }
        val root = roots.singleOrNull() ?: return null
        if (activity.window.decorView === root) return activity.window
        val pending = java.util.ArrayDeque<android.view.View>(); pending.add(root)
        var inspected = 0
        while (pending.isNotEmpty() && inspected++ < 64) {
            val view = pending.removeFirst()
            if (view is androidx.compose.ui.window.DialogWindowProvider) {
                val window = view.window
                if (window.decorView === root && window.decorView.hasWindowFocus()) return window
            }
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
        }
        return null // An unrecognized popup/window is unknown; no screenshot fallback.
    }
    private fun diagnosticControlFacts(tag: String): Pair<Int,Boolean?> {
        val nodes=ui.onAllNodesWithTag(tag).fetchSemanticsNodes()
        return nodes.size to nodes.singleOrNull()?.config?.contains(SemanticsProperties.Disabled)
    }
    /** Read-only diagnostics; callback failures never replace the original UI assertion. */
    private fun generationDiagnostic(phase: String) {
        val prefix = "generation-diagnostic-$phase"
        runCatching {
            require(phase in listOf("before-model", "after-model", "before-effort", "after-effort", "before-speed", "after-speed", "before-permission", "after-permission", "catch"))
            var value = JSONObject().put("phase", phase).put("run", plan.getString("run")).put("leaseId", plan.getString("leaseId"))
                .put("observedAtEpochMs", System.currentTimeMillis()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).put("expectedTarget", CodexCommandCodec.target(profile.permit.target))
            var confirmedTarget = false; var ownResumedActivity = false
            ui.runOnIdle {
                val identity=failureScopeIdentity()
                confirmedTarget = !identity.getBoolean("scopeMismatch")
                value.put("scopeIdentity",identity)
                if(confirmedTarget) {
                val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
                ownResumedActivity = lifecycle.getActivitiesInStage(Stage.RESUMED).singleOrNull() === activity
                value.put("targetConfirmed", confirmedTarget).put("threadId", session.selectedThread).put("targetKey", session.targetKey)
                    .put("canCompose", session.canCompose).put("canConfigureGeneration", session.canConfigureGeneration)
                    .put("canSelectReplyMode",session.canSelectReplyMode).put("replyMode",session.replyMode).put("replyModes",JSONArray(session.replyModes))
                    .put("lastConnectionReadFailure",session.lastConnectionReadFailure?.let(::JSONObject) ?: JSONObject.NULL)
                    .put("phoneReadingFailurePresent",session.phoneReadingError!=null)
                    .put("phoneReadingFailureSha256",session.phoneReadingError?.let(::sha) ?: JSONObject.NULL)
                    .put("phoneReadingDirectoryFailureCount",session.phoneReadingDirectoryFailures.size)
                    .put("clientOnline", session.selectedClient.online).put("clientId", session.selectedClient.id)
                    .put("clientConnectionPhase", session.selectedClient.connectionPhase.name).put("isWritable", session.isWritable)
                    .put("codexStorageConflictMessage", session.codexStorageConflictMessage ?: JSONObject.NULL)
                    .put("localRecoveryRequired", session.localRecoveryRequired).put("migrationPaused", session.migrationPaused)
                    .put("workspaceRuntimeConnected", session.workspace?.runtimeConnected ?: JSONObject.NULL)
                    .put("workspacePresent", session.workspace != null).put("workspaceCapabilities", session.workspace?.let { JSONArray(it.capabilities) } ?: JSONObject.NULL)
                    .put("runtimePresent", session.runtime != null).put("runtimeCanControl", session.runtime?.canControl ?: JSONObject.NULL).put("runtimeState", session.runtime?.state ?: JSONObject.NULL)
                    .put("runtimeError", session.runtime?.error ?: JSONObject.NULL).put("error", session.error ?: JSONObject.NULL)
                    .put("restoring", session.restoring).put("loading", session.loading).put("historyLoading", session.historyLoading).put("running", session.running)
                    .put("actualSettings", session.generationSettings.append(JSONObject()))
                    .put("runtimeSettings", session.runtime?.settings?.append(JSONObject()) ?: JSONObject.NULL)
                    .put("selectedModelId", session.selectedModel?.id ?: JSONObject.NULL)
                    .put("selectedModelSpeeds", session.selectedModel?.let { JSONArray(it.supportedSpeeds) } ?: JSONObject.NULL)
                    .put("selectedModelEfforts", session.selectedModel?.let { JSONArray(it.supportedEfforts) } ?: JSONObject.NULL)
                    .put("supportsGenerationSpeed", session.supportsGenerationSpeed)
                    .put("originalRecordCount", session.operationRecords.size).put("nativeObservedTextRecordCount", records.size).put("nativeSubmittedTurnCount", submittedTurns.size)
                    .put("originalOperationIds", JSONArray(records.values.map { it.id }))
                    .put("activityComponent", activity.componentName.flattenToString()).put("ownResumedActivity", ownResumedActivity)
                    .put("activityWindowFocus", activity.window.decorView.hasWindowFocus()).put("activityAttached", activity.window.decorView.isAttachedToWindow)
                    .put("activityFinishing", activity.isFinishing).put("activityDestroyed", activity.isDestroyed)
                    .put("foreground", JSONObject.NULL).put("freshWorkspaces", JSONObject.NULL).put("freshHistories", JSONObject.NULL)
                    .put("localReady", JSONObject.NULL).put("executionPresent", JSONObject.NULL)
                    .put("privateInputsNote", "Private Session inputs are not accessed or inferred; lifecycle/focus are separate observations.")
                } else value.put("targetConfirmed",false).put("scopeMismatch",true)
            }
            val controls = JSONArray()
            if (confirmedTarget && ownResumedActivity) {
                listOf("model-picker", "effort-picker", "speed-picker", "fast-mode-toggle", "permission-full-access", "sheet-done", "reply-mode-queue", "reply-mode-steer", "message-input", "send-message").forEach { tag ->
                    val row = JSONObject().put("tag", tag).put("observedAtEpochMs", System.currentTimeMillis())
                    runCatching {
                        require(ui.runOnIdle {failureScopeMatches()}) {"Diagnostic scope changed"}
                        val (count,disabled)=diagnosticControlFacts(tag);row.put("count",count)
                        disabled?.let {row.put("disabled",it)} // No Text/EditableText or raw semantics output.
                    }.onFailure { row.put("readFailureClass", it.javaClass.name) }
                    controls.put(row)
                }
            }
            value.put("controlsObserved", confirmedTarget && ownResumedActivity).put("controls", if (confirmedTarget && ownResumedActivity) controls else JSONObject.NULL)
            // PixelCopy only a positively identified Window owned by the exact test Activity.
            runCatching {
                require(confirmedTarget && ownResumedActivity && ui.runOnIdle {failureScopeMatches()} && activity.componentName.packageName == context.packageName)
                var found: android.view.Window? = null
                instrumentation.runOnMainSync { found = ownGenerationDiagnosticWindow() }
                val window = checkNotNull(found); val view = window.decorView
                require(view.isAttachedToWindow && view.hasWindowFocus() && view.width in 1..4096 && view.height in 1..4096)
                value.put("imageSource", "PixelCopy exact own Activity/Dialog Window").put("imageWindowIdentity", System.identityHashCode(window))
                    .put("imageWindowTitle", window.attributes.title?.toString() ?: JSONObject.NULL)
                    .put("imageWindowWidth", view.width).put("imageWindowHeight", view.height)
                val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                val latch = java.util.concurrent.CountDownLatch(1); var result = -1
                try {
                    instrumentation.runOnMainSync { android.view.PixelCopy.request(window, image, { result = it; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper())) }
                    require(latch.await(5, java.util.concurrent.TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
                    var stillOwn = false
                    instrumentation.runOnMainSync { stillOwn = failureScopeMatches() && ownGenerationDiagnosticWindow() === window && ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() === activity }
                    require(stillOwn)
                    val file = File(folder, "$prefix.png"); require(file.createNewFile()); android.system.Os.chmod(file.path, 0x180)
                    file.outputStream().use { require(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                    value.put("image", file.name).put("imageSha256", sha(file.readBytes()))
                } finally { image.recycle() }
            }.onFailure { value.put("imageSkippedOrDiscarded", it.javaClass.name).put("noWholeDisplayFallback", true) }
            val finalIdentity=ui.runOnIdle {failureScopeIdentity()}
            if(finalIdentity.getBoolean("scopeMismatch"))value=JSONObject().put("phase",phase)
                .put("scopeIdentity",finalIdentity).put("targetConfirmed",false).put("scopeMismatch",true)
            value.put("completedAtEpochMs", System.currentTimeMillis()); publish("$prefix.json", value)
        }.onFailure { failure ->
            runCatching { publish("$prefix-failed.json", JSONObject().put("phase", phase).put("observedAtEpochMs", System.currentTimeMillis())
                .put("diagnosticFailureClass", failure.javaClass.name).put("originalAssertionUnchanged", true)) }
        }
    }
    private fun configureNative() {
        ui.waitUntil(30000) { session.canConfigureGeneration && session.models.isNotEmpty() }
        val requested = plan.getJSONObject("settings"); val model = session.models.single { it.id == requested.getString("model") }
        require("xhigh" in model.supportedEfforts)
        publish("actual-catalog-before-ui.json", JSONObject().put("models", JSONArray(session.models.map { m ->
            JSONObject().put("id", m.id).put("supportedEfforts", JSONArray(m.supportedEfforts)).put("supportedSpeeds", JSONArray(m.supportedSpeeds))
                .put("defaultEffort", m.defaultEffort).put("defaultSpeed", m.defaultSpeed)
        })).put("beforeSettings", session.generationSettings.append(JSONObject())))
        ui.onNodeWithTag("generation-settings").assertIsEnabled().performClick()
        val speed = plan.optional("nativeSpeedSelection")
        if (speed == null) {
            generationDiagnostic("before-model")
            ui.onNodeWithTag("model-picker").performScrollTo().performClick(); ui.onNodeWithTag("model-${model.id}").performScrollTo().performClick()
            generationDiagnostic("after-model"); generationDiagnostic("before-effort")
            ui.onNodeWithTag("effort-picker").performScrollTo().performClick(); ui.onNodeWithTag("effort-xhigh").performScrollTo().performClick()
            generationDiagnostic("after-effort"); generationDiagnostic("before-speed")
            if (!freshSingleOrigin && session.supportsGenerationSpeed) ui.onNodeWithTag("reset-speed").performScrollTo().performClick()
            generationDiagnostic("after-speed"); generationDiagnostic("before-permission")
            ui.onNodeWithTag("permission-${requested.getString("permissionMode")}").performScrollTo().performClick()
            generationDiagnostic("after-permission")
        } else {
            require(speed in model.supportedSpeeds)
            // The shared native selector already chooses all four fields. Reopening model/effort
            // a second time adds a transient popup cycle before any original input is claimed.
            ui.selectNativeGeneration(model.id, "xhigh", speed, requested.getString("permissionMode")) { phase -> generationDiagnostic(phase) }
        }
        ui.onNodeWithTag("sheet-done").performClick()
        if (session.selectedCollaboration == "plan") ui.onNodeWithTag("toggle-plan-mode").performClick()
        assertEquals(model.id, session.generationSettings.model); assertEquals("xhigh", session.generationSettings.effort)
        if(!freshSingleOrigin)assertEquals(speed, session.generationSettings.speed)
        assertEquals(requested.getString("permissionMode"), session.generationSettings.permissionMode)
        publish("native-ui-generation-selection.json", JSONObject().put("explicitNativeUiAction", true).put("settings", session.generationSettings.append(JSONObject())))
        shot("native-generation-selection")
    }
    private fun sharing() {
        val scope = AISharingScope(checkNotNull(session.selectedClient.scope), plan.getJSONObject("settings").getString("model"))
        if (session.aiSharing.permits(scope)) return
        val before = session.operationRecords.map { it.id }; val text = session.text
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10000) { session.aiSharingRequest != null }
        require(scope.matches(checkNotNull(session.aiSharingRequest).scope))
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
        ui.waitUntil(10000) { session.aiSharingRequest == null && !session.aiSharing.busy }
        require(session.operationRecords.map { it.id } == before && session.text == text && session.transferStatus == null)
    }
    private fun awaitReplyModeReady(mode: String,turn: String?,originalIds: Set<String>) {
        require(mode in listOf("queue","steer") && turn!=null)
        ui.waitUntil(5000) {
            target() // Includes the unchanged original lease and 45s tool-window fence.
            if(freshSingleOrigin)freshNewWritesAllowed()
            require(session.running && session.runtime?.turnId==turn) {"Original control Turn ended; no replacement"}
            require(session.operationRecords.map {it.id}.toSet()==originalIds) {"Original operation set changed while waiting"}
            session.canSelectReplyMode && mode in session.replyModes &&
                ui.onAllNodesWithTag("reply-mode-$mode").fetchSemanticsNodes().singleOrNull()?.let {
                    !it.config.contains(SemanticsProperties.Disabled)
                }==true
        }
        target()
        require(session.running && session.runtime?.turnId==turn && session.canSelectReplyMode && mode in session.replyModes)
        require(session.operationRecords.map {it.id}.toSet()==originalIds)
    }
    private fun submit(name: String, mode: String, turn: String? = null): SavedOperation {
        target(); val row = slot(name); val before = session.operationRecords.map { it.id }.toSet()
        ui.onNodeWithTag("message-input").performTextReplacement(row.getString("text"))
        if (mode in listOf("queue","steer")) {
            awaitReplyModeReady(mode,turn,before)
            ui.onNodeWithTag("reply-mode-$mode").assertIsEnabled().performClick() // One selection gesture.
            require(session.replyMode==mode)
        } else require(mode=="next-turn" && turn==null && !session.running && session.runtime?.turnId==null)
        sharing(); val proof = claim(name, turn)
        if(freshSingleOrigin) {
            val frozen=FreshWriteProof(freshScope,name,row.getString("slotId"),row.getString("textSha256"),mode,turn,
                proof.getLong("observedAtEpochMs"),NativeProofClock.Receipt(proof.getLong("verifiedHostTransferCompletedAtEpochMs"),proof.getLong("verifiedDeviceElapsedAtRequestMs")))
            require(freshProofs.putIfAbsent(name,frozen)==null) {"A fresh reply proof cannot be refreshed"}
        }
        if(continuing)require(replyBoundaries.putIfAbsent(name,ReplyBoundary(JSONObject(proof.toString()),turn))==null) {
            "Original reply proof cannot be refreshed or reused"
        }
        val send = ui.onNodeWithTag("send-message").assertIsEnabled()
        businessBoundary(proof, turn)
        send.performClick() // Exactly one submission gesture after consent and the final budget fence.
        ui.waitUntil(30000) { session.operationRecords.any { it.id !in before } }
        val record = session.operationRecords.filter { it.id !in before }.single()
        require(record.kind == "reply" && sha(record.intent) == record.intentSha256)
        val intent = JSONObject(record.intent)
        require(intent.getString("threadId") == session.selectedThread && intent.getString("text") == row.getString("text") &&
            intent.getString("clientMessageId") == record.id && intent.getString("mode") == mode && intent.getJSONArray("attachmentIds").length() == 0)
        if (mode == "steer") {
            require(intent.getString("expectedTurnId") == turn)
            require(listOf("model", "effort", "permissionMode", "speed", "serviceTier").none { intent.has(it) })
        }
        records[name] = record
        publish("observed-$name.json", JSONObject().put("slotId", row.getString("slotId")).put("record", record.body()).put("records", JSONArray(session.operationRecords.map { it.body() })))
        ui.waitUntil(30000) { session.transferStatus == null }
        require(session.operationRecords.single { it.id == record.id }.phase !in listOf("failed", "rejected", "uncertain")) { "Preserve original ID; no retry" }
        return record
    }
    private fun turnAfter(name: String): String {
        ui.waitUntil(if(continuing) 45000L else 180000L) {
            val activeTurn = session.runtime?.turnId
            session.running && activeTurn != null && session.messages.any {
                it.role == "tool" && it.status == "inProgress" && it.runId == activeTurn
            }
        }
        val turn = checkNotNull(session.runtime?.turnId)
        val proof = gate("after-$name", turn)
        require(proof.getString("subjectOperationId") == records.getValue(name).id && proof.getInt("originalUserInputCount") == 1)
        require(originalTurns(proof) == expectedTurns() + turn)
        submittedTurns += turn
        val tool = proof.getJSONObject("toolWindow")
        require(tool.getString("cwd") == plan.getString("modelToolWorkingDirectory"))
        require(tool.getInt("processStarts") == 1)
        if (profile.permit.target.agentId == "codex") require(tool.getString("timestampSource") == "commandExecution.startedAtMs")
        else require(plan.getString("toolTimestampSource").isNotBlank() && tool.getString("timestampSource") == plan.getString("toolTimestampSource"))
        require(tool.getInt("seconds") in 1..plan.getInt("maxToolSeconds") && tool.getString("commandSha256") == plan.getString("toolCommandSha256"))
        activeWindow = ToolBudget(tool.getLong("startedAtEpochMs"), NativeProofClock.Receipt(
            proof.getLong("verifiedHostTransferCompletedAtEpochMs"), proof.getLong("verifiedDeviceElapsedAtRequestMs"))); target()
        val actual = proof.getJSONObject("generation")
        require(actual.getString("source") == "original-turn-params" && actual.getString("model") == plan.getJSONObject("settings").getString("model") && actual.getString("effort") == "xhigh")
        require(actual.getString("permissionMode") == plan.getJSONObject("settings").getString("permissionMode"))
        plan.optional("nativeSpeedSelection")?.let { require(actual.getString("speed") == it) }
        publish("$name-turn.json", JSONObject().put("turnId", turn).put("recordId", records.getValue(name).id))
        return turn
    }
    private fun queryOriginalRecords() {
        if (statusInspected) return
        statusInspected = true // Even a failed status lookup is never converted to a new write.
        val originals = ui.runOnIdle {requireFailureScope();session.operationRecords.filter {it.kind=="reply"}}
        val frozen = originals.associate { it.id to it.intentSha256 }
        for (record in originals) {
            profile.checkOnlyApprovedTarget(); require(session.selectedThread == plan.getString("threadId"))
            ui.runOnIdle { requireFailureScope(); session.queryOperation(record) }
            ui.waitUntil(30000) {requireFailureScope();session.transferStatus==null}
            ui.runOnIdle {
                requireFailureScope()
                require(session.operationRecords.map {it.id}.toSet()==frozen.keys)
                require(session.operationRecords.all {frozen[it.id]==it.intentSha256})
            }
        }
        publish("original-status-only.json", JSONObject().put("operationIds", JSONArray(frozen.keys.toList())).put("modelInputsAdded", 0))
    }
    /** IO only: no Session/Compose, dynamic target getter or main-thread wait is used here. */
    private fun freshAbortAbsent() {
        check(!freshAbortObserved) {"Fresh controller abort already observed; original IDs only"}
        require(folder.canonicalFile==folder.absoluteFile)
        val fd=android.system.Os.open(folder.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC,0)
        try {
            val stat=android.system.Os.fstat(fd)
            require(android.system.OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0xfff==0x1c0&&stat.st_dev==freshScope.folderDevice&&stat.st_ino==freshScope.folderInode)
            android.os.ParcelFileDescriptor.dup(fd).use {held ->
                try {
                    android.system.Os.lstat("/proc/self/fd/${held.fd}/controller-abort.json")
                    freshAbortObserved=true
                    error("Controller abort present; malformed or foreign abort also denies writes")
                } catch(failure: android.system.ErrnoException) {require(failure.errno==android.system.OsConstants.ENOENT)}
            }
        } finally {android.system.Os.close(fd)}
    }
    private fun freshBindingIntentObserved(proof: JSONObject) {
        require(freshSingleOrigin&&android.os.Looper.myLooper()!==android.os.Looper.getMainLooper())
        require(proof.getString("run")==freshScope.run&&proof.getString("storageOwnerRun")==freshScope.run&&
            proof.getString("leaseId")==freshScope.lease&&proof.getString("alias")=="q02-real-"+freshScope.run)
        require(proof.opt("existingOwnedResume")==false&&proof.opt("pairingAttempted")==true&&proof.opt("closed")==false)
        require(sameScope(HostReadCodec.target(proof.getJSONObject("target")),freshScope.target))
        val intent=proof.getJSONObject("newBindingIntent")
        require(intent.opt("attemptedOnce")==true&&sameScope(HostReadCodec.target(intent.getJSONObject("target")),freshScope.target))
        hex(intent.getString("mobilePublicKeySha256"));require(intent.getString("pairingIntentSha256")==plan.getString("pairingIntentSha256"))
        freshNewWritesAllowed()
        freshPublishEvidence("fresh-binding-intent.json",proof) // Attempt facts only; never a successful-pair assertion.
    }
    private fun freshPublishEvidence(name: String,value: JSONObject) {
        require(name in setOf("fresh-binding-intent.json","fresh-binding-proof.json")||
            name.matches(Regex("prepared-dispatch-(origin|queueCancel|queueExecute|steer)-(before-mark-submitting|before-send)\\.json")))
        val directory=android.system.Os.open(folder.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC,0)
        try {
            val stat=android.system.Os.fstat(directory)
            require(android.system.OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0xfff==0x1c0&&stat.st_dev==freshScope.folderDevice&&stat.st_ino==freshScope.folderInode)
            android.os.ParcelFileDescriptor.dup(directory).use {held ->
                val bytes=value.toString().toByteArray(Charsets.UTF_8)
                val fd=android.system.Os.open("/proc/self/fd/${held.fd}/$name",android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC,0x180)
                try {
                    val file=android.system.Os.fstat(fd)
                    require(android.system.OsConstants.S_ISREG(file.st_mode)&&file.st_uid==android.os.Process.myUid()&&file.st_nlink==1L&&file.st_mode and 0xfff==0x180)
                    var offset=0;while(offset<bytes.size){val n=android.system.Os.write(fd,bytes,offset,bytes.size-offset);require(n>0);offset+=n}
                    android.system.Os.fsync(fd);require(android.system.Os.fstat(fd).st_size==bytes.size.toLong())
                } finally {android.system.Os.close(fd)}
                android.system.Os.fsync(directory)
            }
        } finally {android.system.Os.close(directory)}
    }
    private fun freshNewWritesAllowed() {
        freshAbortAbsent()
        val now=SystemClock.elapsedRealtime()
        require(checkNotNull(freshScope.receipt.hostNowUpperBound(now))<freshScope.expires)
        // This is an extra fresh-case new-write cutoff, never an extension of the original 45s window.
        activeWindow?.let {require(it.receipt.ageWithin(it.startedAtHostMs,now,25000)!=null) {"Fresh watchdog new-write deadline"}}
    }
    private fun freshOriginalReplyDispatch(actual: NativeOriginalReplyDispatch) {
        require(freshSingleOrigin && android.os.Looper.myLooper()!==android.os.Looper.getMainLooper())
        freshNewWritesAllowed()
        require(actual.stage in setOf("before-mark-submitting","before-send")) {"Fresh case retries are forbidden; query the original ID"}
        val scope=freshScope;val reply=actual.reply
        require(actual.client.id==scope.target.hostId && sameScope(actual.client.scope,scope.target))
        require(actual.client.endpoint in scope.endpoints && actual.client.codexDirect?.fingerprint==scope.certificateSha)
        require(reply.hostId==scope.target.hostId&&reply.threadId==scope.thread&&reply.operationId==actual.operationId)
        require(reply.attachmentIds.isEmpty()&&reply.expectedPlanId==null)
        require(sha(actual.intentJson)==actual.intentSha256&&easyCanonical(JSONObject(actual.intentJson))==easyCanonical(reply.body()))
        val textSha=sha(reply.text);val proof=freshProofs.values.single {it.textSha==textSha}
        require(proof.scope===scope&&proof.slot in setOf("origin","queueCancel","queueExecute","steer"))
        require(reply.mode==proof.mode&&reply.expectedTurnId==proof.turn)
        require(FreshQ04GenerationFence.accepts(proof.slot,reply.settings)) {"Fresh reply settings must match this slot's explicit or inherited generation policy"}
        if(proof.slot=="queueExecute")require(freshCancelConfirmed)
        if(proof.slot=="steer")require(freshCancelConfirmed&&freshExecuteConfirmed)
        require(proof.receipt.ageWithin(proof.observed,SystemClock.elapsedRealtime(),30000)!=null)
        freshDispatch.consume(proof.slot,actual.operationId,actual.intentSha256,actual.stage)
        val evidence=JSONObject().put("version",1).put("kind","q04-fresh-prepared-dispatch-v1")
            .put("run",scope.run).put("leaseId",scope.lease).put("budgetOperationId",scope.budget).put("nonce",scope.nonce)
            .put("target",CodexCommandCodec.target(scope.target)).put("threadId",scope.thread).put("slotName",proof.slot)
            .put("slotId",proof.slotId).put("operationId",actual.operationId).put("intentSha256",actual.intentSha256)
            .put("textSha256",proof.textSha).put("mode",proof.mode).put("expectedTurnId",proof.turn ?: JSONObject.NULL)
            .put("stage",actual.stage).put("planSha256",scope.planSha).put("hostAccepted",JSONObject.NULL)
        freshPublishEvidence("prepared-dispatch-${proof.slot}-${actual.stage}.json",evidence)
        freshNewWritesAllowed() // Publication may consume the budget; no permission is renewed.
    }
    private fun freshOriginStillActive(turn: String) {
        target();freshNewWritesAllowed();require(session.running&&session.runtime?.turnId==turn)
    }
    private fun awaitFreshQueueAction(action: String,operation: String,turn: String) {
        require(action in setOf("cancel","execute"))
        ui.waitUntil(5000) {
            freshOriginStillActive(turn)
            val rows=session.queuedReplies
            require(rows.all {it.operationId==operation}) {"Only this case's single pending queue item is permitted"}
            val row=rows.singleOrNull()
            val receipt=session.runtime?.receipts?.singleOrNull {it.operationId==operation}
            receipt?.let {it.state=="queued"&&it.queueSource=="native"&&it.queuePresent==true}==true&&row!=null&&row.queueSource=="native"&&row.queuePresent==true&&
                (if(action=="cancel")session.canCancelQueue(row,session.targetKey)else session.canExecuteQueue(row,session.targetKey,turn))&&
                ui.onAllNodesWithTag("${action}-queued-$operation").fetchSemanticsNodes().singleOrNull()?.let {!it.config.contains(SemanticsProperties.Disabled)}==true
        }
        freshOriginStillActive(turn)
    }
    @Test fun q04SequentialSingleOriginControlsOnce() = launch("q04",singleOrigin=true) {
        require(session.operationRecords.isEmpty()) // Historical Host receipts remain baseline; only this fresh journal is empty.
        val original=submit("origin","next-turn");val turn=turnAfter("origin")
        val cancelled=submit("queueCancel","queue",turn)
        awaitFreshQueueAction("cancel",cancelled.id,turn)
        val cancelProof=claim("cancel",turn,cancelled.id)
        val cancel=ui.onNodeWithTag("cancel-queued-${cancelled.id}").performScrollTo().assertIsEnabled()
        businessBoundary(cancelProof,turn)
        require(session.canCancelQueue(session.queuedReplies.single {it.operationId==cancelled.id},session.targetKey))
        cancel.performClick()
        ui.waitUntil(5000) {
            freshOriginStillActive(turn)
            session.runtime?.receipts?.singleOrNull {it.operationId==cancelled.id}?.let {it.state=="cancelled"&&it.queuePresent==false}==true&&
                session.queuedReplies.none {it.operationId==cancelled.id}
        }
        val cancelledProof=gate("after-cancel",turn,cancelled.id)
        require(originalTurns(cancelledProof)==expectedTurns()&&cancelledProof.opt("cancelledInputNeverExecuted")==true&&
            cancelledProof.opt("cancelledReceiptState")=="cancelled"&&cancelledProof.opt("cancelledQueueAbsent")==true)
        freshCancelConfirmed=true // Only this independent proof can admit queueExecute's body.
        val executed=submit("queueExecute","queue",turn)
        awaitFreshQueueAction("execute",executed.id,turn)
        val executeProof=claim("execute",turn,executed.id)
        val execute=ui.onNodeWithTag("execute-queued-${executed.id}").performScrollTo().assertIsEnabled()
        businessBoundary(executeProof,turn)
        require(session.canExecuteQueue(session.queuedReplies.single {it.operationId==executed.id},session.targetKey,turn))
        execute.performClick()
        ui.waitUntil(5000) {freshOriginStillActive(turn);session.queuedReplies.none {it.operationId==executed.id}}
        val executedProof=gate("after-execute",turn,executed.id)
        require(originalTurns(executedProof)==expectedTurns()&&executedProof.opt("originalQueuePromotedOnce")==true&&
            executedProof.opt("queueExecuteTurnId")==turn&&executedProof.opt("executedQueueAbsent")==true)
        freshExecuteConfirmed=true
        val steered=submit("steer","steer",turn)
        val steeredProof=gate("after-steer",turn,steered.id)
        require(originalTurns(steeredProof)==expectedTurns()&&steeredProof.opt("sameTurnSteerOnce")==true&&steeredProof.opt("steerTurnId")==turn)
        val draft="LOCAL_UNSENT_DRAFT_"+plan.getString("run")+"_"+plan.getString("nonce")
        require(sha(draft)==plan.getString("unsentDraftSha256"))
        ui.onNodeWithTag("message-input").performTextReplacement(draft)
        val stopProof=claim("stop",turn,original.id)
        businessBoundary(stopProof,turn);ui.onNodeWithTag("send-message").performTouchInput {longClick()}
        val stop=ui.onNodeWithTag("action-stop").assertIsDisplayed().assertIsEnabled()
        businessBoundary(stopProof,turn);stop.performClick()
        ui.waitUntil(10000) {target();!session.running&&session.transferStatus==null}
        assertEquals(draft,session.text)
        val stopped=gate("stopped")
        require(originalTurns(stopped)==expectedTurns()&&stopped.getString("stoppedTurnId")==turn&&stopped.opt("originalTurnInterrupted")==true)
        require(stopped.opt("newActualTurns")==1&&stopped.opt("automaticQueueFollowups")==0&&stopped.opt("controllerCleanupStarted")==false)
        require(stopped.opt("originToolCount")==1&&stopped.opt("childStarts")==0&&stopped.opt("cancelledInputNeverExecuted")==true&&
            stopped.opt("originalQueuePromotedOnce")==true&&stopped.opt("sameTurnSteerOnce")==true)
        require(records.keys==setOf("origin","queueCancel","queueExecute","steer")&&freshDispatch.dispatchedSlots()==records.keys)
        activeWindow=null;shot("fresh-single-origin-stop-draft-preserved");queryOriginalRecords();assertEquals(draft,session.text)
        publish("fresh-single-origin-result-scope.json",JSONObject().put("kind","q04-fresh-single-origin-result-v1")
            .put("run",plan.getString("run")).put("budgetOperationId",plan.getString("budgetOperationId")).put("passed",true)
            .put("newActualTurns",1).put("automaticQueueFollowups",0).put("childStarts",0).put("controllerCleanupStarted",false))
    }
    @Test fun q04KnownZeroSuccessorSingleOriginControlsOnce() = launch("q04",singleOrigin=true,successor=true) {
        require(session.operationRecords.isEmpty()) // Historical Host receipts remain baseline; only this fresh journal is empty.
        val original=submit("origin","next-turn");val turn=turnAfter("origin")
        val cancelled=submit("queueCancel","queue",turn)
        awaitFreshQueueAction("cancel",cancelled.id,turn)
        val cancelProof=claim("cancel",turn,cancelled.id)
        val cancel=ui.onNodeWithTag("cancel-queued-${cancelled.id}").performScrollTo().assertIsEnabled()
        businessBoundary(cancelProof,turn)
        require(session.canCancelQueue(session.queuedReplies.single {it.operationId==cancelled.id},session.targetKey))
        cancel.performClick()
        ui.waitUntil(5000) {
            freshOriginStillActive(turn)
            session.runtime?.receipts?.singleOrNull {it.operationId==cancelled.id}?.let {it.state=="cancelled"&&it.queuePresent==false}==true&&
                session.queuedReplies.none {it.operationId==cancelled.id}
        }
        val cancelledProof=gate("after-cancel",turn,cancelled.id)
        require(originalTurns(cancelledProof)==expectedTurns()&&cancelledProof.opt("cancelledInputNeverExecuted")==true&&
            cancelledProof.opt("cancelledReceiptState")=="cancelled"&&cancelledProof.opt("cancelledQueueAbsent")==true)
        freshCancelConfirmed=true // Only this independent proof can admit queueExecute's body.
        val executed=submit("queueExecute","queue",turn)
        awaitFreshQueueAction("execute",executed.id,turn)
        val executeProof=claim("execute",turn,executed.id)
        val execute=ui.onNodeWithTag("execute-queued-${executed.id}").performScrollTo().assertIsEnabled()
        businessBoundary(executeProof,turn)
        require(session.canExecuteQueue(session.queuedReplies.single {it.operationId==executed.id},session.targetKey,turn))
        execute.performClick()
        ui.waitUntil(5000) {freshOriginStillActive(turn);session.queuedReplies.none {it.operationId==executed.id}}
        val executedProof=gate("after-execute",turn,executed.id)
        require(originalTurns(executedProof)==expectedTurns()&&executedProof.opt("originalQueuePromotedOnce")==true&&
            executedProof.opt("queueExecuteTurnId")==turn&&executedProof.opt("executedQueueAbsent")==true)
        freshExecuteConfirmed=true
        val steered=submit("steer","steer",turn)
        val steeredProof=gate("after-steer",turn,steered.id)
        require(originalTurns(steeredProof)==expectedTurns()&&steeredProof.opt("sameTurnSteerOnce")==true&&steeredProof.opt("steerTurnId")==turn)
        val draft="LOCAL_UNSENT_DRAFT_"+plan.getString("run")+"_"+plan.getString("nonce")
        require(sha(draft)==plan.getString("unsentDraftSha256"))
        ui.onNodeWithTag("message-input").performTextReplacement(draft)
        val stopProof=claim("stop",turn,original.id)
        businessBoundary(stopProof,turn);ui.onNodeWithTag("send-message").performTouchInput {longClick()}
        val stop=ui.onNodeWithTag("action-stop").assertIsDisplayed().assertIsEnabled()
        businessBoundary(stopProof,turn);stop.performClick()
        ui.waitUntil(10000) {target();!session.running&&session.transferStatus==null}
        assertEquals(draft,session.text)
        val stopped=gate("stopped")
        require(originalTurns(stopped)==expectedTurns()&&stopped.getString("stoppedTurnId")==turn&&stopped.opt("originalTurnInterrupted")==true)
        require(stopped.opt("newActualTurns")==1&&stopped.opt("automaticQueueFollowups")==0&&stopped.opt("controllerCleanupStarted")==false)
        require(stopped.opt("originToolCount")==1&&stopped.opt("childStarts")==0&&stopped.opt("cancelledInputNeverExecuted")==true&&
            stopped.opt("originalQueuePromotedOnce")==true&&stopped.opt("sameTurnSteerOnce")==true)
        require(records.keys==setOf("origin","queueCancel","queueExecute","steer")&&freshDispatch.dispatchedSlots()==records.keys)
        activeWindow=null;shot("fresh-single-origin-stop-draft-preserved");queryOriginalRecords();assertEquals(draft,session.text)
        publish("fresh-single-origin-result-scope.json",JSONObject().put("kind","q04-fresh-single-origin-result-v1")
            .put("run",plan.getString("run")).put("budgetOperationId",plan.getString("budgetOperationId")).put("passed",true)
            .put("newActualTurns",1).put("automaticQueueFollowups",0).put("childStarts",0).put("controllerCleanupStarted",false))
    }
    @Test fun q04NewIndependentBudgetSingleOriginControlsOnce() = launch("q04",singleOrigin=true,independent=true) {
        require(session.operationRecords.isEmpty()) // Historical Host receipts remain baseline; only this fresh journal is empty.
        val original=submit("origin","next-turn");val turn=turnAfter("origin")
        val cancelled=submit("queueCancel","queue",turn)
        awaitFreshQueueAction("cancel",cancelled.id,turn)
        val cancelProof=claim("cancel",turn,cancelled.id)
        val cancel=ui.onNodeWithTag("cancel-queued-${cancelled.id}").performScrollTo().assertIsEnabled()
        businessBoundary(cancelProof,turn)
        require(session.canCancelQueue(session.queuedReplies.single {it.operationId==cancelled.id},session.targetKey))
        cancel.performClick()
        ui.waitUntil(5000) {
            freshOriginStillActive(turn)
            session.runtime?.receipts?.singleOrNull {it.operationId==cancelled.id}?.let {it.state=="cancelled"&&it.queuePresent==false}==true&&
                session.queuedReplies.none {it.operationId==cancelled.id}
        }
        val cancelledProof=gate("after-cancel",turn,cancelled.id)
        require(originalTurns(cancelledProof)==expectedTurns()&&cancelledProof.opt("cancelledInputNeverExecuted")==true&&
            cancelledProof.opt("cancelledReceiptState")=="cancelled"&&cancelledProof.opt("cancelledQueueAbsent")==true)
        freshCancelConfirmed=true // Only this independent proof can admit queueExecute's body.
        val executed=submit("queueExecute","queue",turn)
        awaitFreshQueueAction("execute",executed.id,turn)
        val executeProof=claim("execute",turn,executed.id)
        val execute=ui.onNodeWithTag("execute-queued-${executed.id}").performScrollTo().assertIsEnabled()
        businessBoundary(executeProof,turn)
        require(session.canExecuteQueue(session.queuedReplies.single {it.operationId==executed.id},session.targetKey,turn))
        execute.performClick()
        ui.waitUntil(5000) {freshOriginStillActive(turn);session.queuedReplies.none {it.operationId==executed.id}}
        val executedProof=gate("after-execute",turn,executed.id)
        require(originalTurns(executedProof)==expectedTurns()&&executedProof.opt("originalQueuePromotedOnce")==true&&
            executedProof.opt("queueExecuteTurnId")==turn&&executedProof.opt("executedQueueAbsent")==true)
        freshExecuteConfirmed=true
        val steered=submit("steer","steer",turn)
        val steeredProof=gate("after-steer",turn,steered.id)
        require(originalTurns(steeredProof)==expectedTurns()&&steeredProof.opt("sameTurnSteerOnce")==true&&steeredProof.opt("steerTurnId")==turn)
        val draft="LOCAL_UNSENT_DRAFT_"+plan.getString("run")+"_"+plan.getString("nonce")
        require(sha(draft)==plan.getString("unsentDraftSha256"))
        ui.onNodeWithTag("message-input").performTextReplacement(draft)
        val stopProof=claim("stop",turn,original.id)
        businessBoundary(stopProof,turn);ui.onNodeWithTag("send-message").performTouchInput {longClick()}
        val stop=ui.onNodeWithTag("action-stop").assertIsDisplayed().assertIsEnabled()
        businessBoundary(stopProof,turn);stop.performClick()
        ui.waitUntil(10000) {target();!session.running&&session.transferStatus==null}
        assertEquals(draft,session.text)
        val stopped=gate("stopped")
        require(originalTurns(stopped)==expectedTurns()&&stopped.getString("stoppedTurnId")==turn&&stopped.opt("originalTurnInterrupted")==true)
        require(stopped.opt("newActualTurns")==1&&stopped.opt("automaticQueueFollowups")==0&&stopped.opt("controllerCleanupStarted")==false)
        require(stopped.opt("originToolCount")==1&&stopped.opt("childStarts")==0&&stopped.opt("cancelledInputNeverExecuted")==true&&
            stopped.opt("originalQueuePromotedOnce")==true&&stopped.opt("sameTurnSteerOnce")==true)
        require(records.keys==setOf("origin","queueCancel","queueExecute","steer")&&freshDispatch.dispatchedSlots()==records.keys)
        activeWindow=null;shot("fresh-single-origin-stop-draft-preserved");queryOriginalRecords();assertEquals(draft,session.text)
        publish("fresh-single-origin-result-scope.json",JSONObject().put("kind","q04-fresh-single-origin-result-v1")
            .put("run",plan.getString("run")).put("budgetOperationId",plan.getString("budgetOperationId")).put("passed",true)
            .put("newActualTurns",1).put("automaticQueueFollowups",0).put("childStarts",0).put("controllerCleanupStarted",false))
    }
    @Test fun q03NativeUiUsesOriginalTurnOnce() = launch("q03") {
        val record = submit("q03", "next-turn")
        ui.waitUntil(180000) { !session.running && session.messages.any { it.role == "assistant" && it.replyPhase == "final_answer" && it.text.trim() == plan.getString("expectedFinalMarker") } }
        require(session.messages.count { it.role == "user" && it.text == slot("q03").getString("text") } == 1)
        val proof = gate("q03-completed")
        require(proof.getString("subjectOperationId") == record.id && proof.getInt("originalUserInputCount") == 1)
        val actual = proof.getJSONObject("generation")
        require(actual.getString("source") == "original-turn-params" && actual.getString("model") == plan.getJSONObject("settings").getString("model") && actual.getString("effort") == "xhigh")
        require(actual.getString("permissionMode") == plan.getJSONObject("settings").getString("permissionMode"))
        plan.optional("nativeSpeedSelection")?.let { require(actual.getString("speed") == it) }
        require(proof.getInt("childStarts") == plan.getInt("maxChildStarts"))
        require(originalTurns(proof) == plan.getJSONArray("baselineTurnIds").strings().toSet() + proof.getString("subjectTurnId"))
        require(proof.getInt("originalFinalMarkerCount") == 1)
        if (plan.getInt("maxChildStarts") == 0) require(proof.getInt("originalToolCount") == 0)
        shot("q03-original-response"); queryOriginalRecords()
    }
    @Test fun q04NativeQueueAndIndependentStop() = launch("q04") {
        submit("origin", "next-turn"); val originalTurn = turnAfter("origin")
        val cancelled = submit("queueCancel", "queue", originalTurn)
        val executed = submit("queueExecute", "queue", originalTurn)
        ui.waitUntil(5000) { session.queuedReplies.any { it.operationId == cancelled.id } && session.queuedReplies.any { it.operationId == executed.id } }
        val cancelProof = claim("cancel", originalTurn, cancelled.id)
        val cancel = ui.onNodeWithTag("cancel-queued-${cancelled.id}").performScrollTo().assertIsEnabled()
        businessBoundary(cancelProof, originalTurn)
        cancel.performClick()
        ui.waitUntil(10000) { session.runtime?.receipts?.any { it.operationId == cancelled.id && it.state == "cancelled" } == true }
        val frozen = records.mapValues { it.value.intentSha256 }
        shot("original-cancelled-queue")
        val executeProof = claim("execute", originalTurn, executed.id)
        val row = session.queuedReplies.single { it.operationId == executed.id }
        require(session.canExecuteQueue(row, session.targetKey, originalTurn))
        val execute = ui.onNodeWithTag("execute-queued-${executed.id}").performScrollTo().assertIsEnabled()
        businessBoundary(executeProof, originalTurn)
        require(session.canExecuteQueue(session.queuedReplies.single { it.operationId == executed.id }, session.targetKey, originalTurn))
        execute.performClick()
        ui.waitUntil(10000) { session.queuedReplies.none { it.operationId == executed.id } }
        require(session.runtime?.turnId == originalTurn && session.running)
        submit("steer", "steer", originalTurn)
        shot("original-turn-steer")
        activeWindow = null // Controls have finished; let this original Turn produce its real final result.
        ui.waitUntil(180000) { !session.running && session.queuedReplies.isEmpty() && session.messages.any { it.role == "assistant" && it.replyPhase == "final_answer" && it.text.trim() == plan.getString("expectedFinalMarker") } }
        val final = gate("queue-completed")
        require((final.opt("cancelledInputNeverExecuted") == true) && (final.opt("originalQueuePromotedOnce") == true) && (final.opt("sameTurnSteerOnce") == true))
        require(originalTurns(final) == expectedTurns())
        require(final.getString("completedTurnId") == originalTurn && final.getInt("originalFinalMarkerCount") == 1 && final.getInt("originToolCount") == 1)
        require(session.operationRecords.filter { it.id in frozen }.all { frozen[it.id] == it.intentSha256 })
        submit("stopOrigin", "next-turn"); val stopTurn = turnAfter("stopOrigin")
        require(stopTurn != originalTurn)
        val draft = "LOCAL_UNSENT_DRAFT_" + plan.getString("run")
        ui.onNodeWithTag("message-input").performTextReplacement(draft)
        val stopProof = claim("stop", stopTurn, records.getValue("stopOrigin").id)
        businessBoundary(stopProof, stopTurn)
        ui.onNodeWithTag("send-message").performTouchInput { longClick() }
        val stop = ui.onNodeWithTag("action-stop").assertIsDisplayed().assertIsEnabled()
        businessBoundary(stopProof, stopTurn)
        stop.performClick()
        ui.waitUntil(10000) { !session.running && session.transferStatus == null }
        activeWindow = null
        assertEquals(draft, session.text)
        val stopped = gate("stopped")
        require(originalTurns(stopped) == expectedTurns())
        require(stopped.getString("stoppedTurnId") == stopTurn && (stopped.opt("originalTurnInterrupted") == true))
        require(stopped.opt("stopOriginToolCount") == 1 && stopped.opt("childStarts") == 0)
        shot("original-stop-draft-preserved"); queryOriginalRecords(); assertEquals(draft, session.text)
    }

    /** Separate entry; cannot pair, send origin or reuse an expired original authority. */
    private fun launchExistingQ04(work: ()->Unit) {
        load("q04",remaining=true)
        val args=InstrumentationRegistry.getArguments()
        val rootSha=hex(checkNotNull(args.getString("w3RootAuthoritySha256")))
        require(rootSha==plan.getString("rootAuthoritySha256"))
        val authority=privateJson(File(folder,"root-authority.json"),rootSha)
        val r=plan.getJSONObject("continuation")
        require(authority.getString("storageOwnerRun")==r.getString("storageOwnerRun"))
        val preNativeRecovering=authority.getString("kind")==Q04PreNativeRecoveryAdmission.AUTHORITY_KIND
        val knownNativeFailed=authority.getString("kind")==Q04KnownNativeFailureRecoveryAdmission.AUTHORITY_KIND
        val postSecondKnownZero=authority.getString("kind")==Q04PostSecondKnownZeroAdmission.AUTHORITY_KIND
        require(postSecondKnownZero==(args.getString("w3PostSecondKnownZero")=="1"))
        val recovering=preNativeRecovering||knownNativeFailed||postSecondKnownZero
        if(!recovering) {
        require(authority.getString("kind")=="root-q04-remaining-four-authority-v1"&&authority.opt("executionAuthorized")==true&&authority.opt("preparedOnly")==false)
        } else if(preNativeRecovering) require(args.getString("w3ConfirmedPreNativeRecovery")=="1")
        else if(knownNativeFailed) require(args.getString("w3KnownNativeFailedBeforeBusiness")=="1"&&args.getString("w3ConfirmedPreNativeRecovery")!="1")
        else require(args.getString("w3PostSecondKnownZero")=="1"&&args.getString("w3KnownNativeFailedBeforeBusiness")!="1"&&args.getString("w3ConfirmedPreNativeRecovery")!="1")
        require(authority.getString("run")==plan.getString("run")&&authority.getString("leaseId")==plan.getString("leaseId"))
        require(authority.getLong("expiresAtMs")==plan.getLong("expiresAtEpochMs")&&currentHostUpperBound()>=authority.getLong("notBeforeMs"))
        require(sameScope(HostReadCodec.target(authority.getJSONObject("target")),HostReadCodec.target(plan.getJSONObject("target")))&&authority.getString("threadId")==plan.getString("threadId"))
        require(authority.getString("budgetOperationId")==plan.getString("budgetOperationId")&&authority.getJSONObject("originalClaimRef").getString("sha256")==r.getString("originalClaimSha256"))
        val originalBudget=privateJson(File(folder,"original-budget.json"),authority.getJSONObject("originalBudgetRef").getString("sha256"))
        require(originalBudget.getString("budgetOperationId")==plan.getString("budgetOperationId")&&
            easyCanonical(originalBudget.getJSONObject("q04").getJSONObject("slots"))==easyCanonical(plan.getJSONObject("slots")))
        val binding=privateJson(File(folder,"native-binding.json"),authority.getJSONObject("nativeManifestRef").getString("sha256"))
        require(binding.getString("kind")=="q04-remaining-native-binding-v1"&&binding.getString("appSha256")==plan.getString("appSha256")&&
            binding.getString("runnerSha256")==plan.getString("runnerSha256")&&binding.getString("signerSha256")==plan.getString("signerSha256"))
        require(binding.getString("hostRuntimeSessionSha256")==authority.getString("hostRuntimeSessionSha256")&&
            binding.getString("hostRuntimeSessionSha256")!="d34dbf8cc17b4055fc7f25dc377e8af8c1cd28555e07345c3272cdb60d007fe6")
        require(authority.getJSONArray("remainingSlots").strings()==listOf("stopOrigin","queueCancel","queueExecute","steer"))
        require(authority.getInt("maxNewIndependentTurns")==1&&authority.getInt("maxTotalQ04Turns")==2&&authority.getInt("childStartsMax")==0&&
            authority.getInt("processSeconds")==40&&authority.getInt("terminalWindowMs")==45000&&authority.getInt("perProcessPollMax")==1)
        require(r.getString("kind")=="remaining-four-on-second-turn-v1"&&r.opt("originConsumed")==true&&r.getInt("hostBindings")==12)
        require(r.getInt("originalProcessCount")==1&&r.getInt("originalProcessPolls")==1)
        require(r.getString("originalToolBudgetReviewSha256")==authority.getJSONObject("toolBudgetReviewRef").getString("sha256"))
        require(r.getJSONArray("freshBaselineTurnIds").strings()==plan.getJSONArray("baselineTurnIds").strings()+r.getString("originalTurnId"))
        require(plan.getInt("maxIndependentOriginTurns")==2&&plan.getInt("maxOriginalSessionPolls")==1&&plan.getInt("maxToolSeconds")==40)
        val origin=authority.getJSONObject("originalOrigin")
        require(origin.getString("operationId")==r.getString("originalOperationId")&&origin.getString("turnId")==r.getString("originalTurnId")&&origin.getString("bodySha256")==slot("origin").getString("textSha256"))
        val releaseSha=authority.getJSONObject("originalLeaseReleaseRef").getString("sha256")
        val release=privateJson(File(folder,"original-lease-release.json"),releaseSha)
        require(release.getString("originalRun")==r.getString("storageOwnerRun")&&release.opt("oldLeaseReleased")==true&&
            release.getInt("newQ04Inputs")==1&&release.opt("continuationExecutionAuthorized")==false)
        val once=privateJson(File(folder,"continuation-once.json"),plan.getString("childOnceSha256"))
        if(!recovering) {
        require(once.getString("kind")=="remaining-four-on-second-turn-v1"&&once.getString("run")==plan.getString("run")&&once.getString("leaseId")==plan.getString("leaseId")&&once.getJSONObject("authorityRef").getString("sha256")==rootSha)
        }
        val manifest=privateJson(File(folder,"existing-profile.json"),plan.getString("existingProfileSha256"))
        require(authority.getJSONObject("existingProfileRef").getString("sha256")==plan.getString("existingProfileSha256"))
        require(easyCanonical(manifest.getJSONObject("slots"))==easyCanonical(plan.getJSONObject("slots")))
        require(manifest.getString("storageOwnerRun")==r.getString("storageOwnerRun")&&manifest.getString("originalOperationId")==r.getString("originalOperationId"))
        require(manifest.getString("originalClaimSha256")==r.getString("originalClaimSha256")&&manifest.getString("rootDecisionSha256")==releaseSha)
        var readingPermit: Q04ExistingOwnedReadingPermit?=null
        if(recovering) {
            val links=authority.getJSONObject(when {preNativeRecovering->"preNativeRecovery";knownNativeFailed->"knownNativeFailureRecovery";else->"postSecondKnownZero"})
            fun document(name: String,ref: JSONObject): Q04PreNativeRecoveryAdmission.Document {
                Q04PreNativeRecoveryAdmission.checkRef(ref)
                val file=File(folder,name);val value=privateJson(file,ref.getString("sha256"))
                require(android.system.Os.lstat(file.path).st_size==ref.getLong("bytes"))
                return Q04PreNativeRecoveryAdmission.Document(ref,value)
            }
            val onceDocument=document("continuation-once.json",links.getJSONObject("originalReservationRef"))
            val oldAuthorityDocument=document("original-root-authority.json",links.getJSONObject("originalAuthorityRef"))
            val oldPlanDocument=document("original-plan.json",links.getJSONObject("originalPlanRef"))
            if(preNativeRecovering) Q04PreNativeRecoveryAdmission.validate(authority,plan,manifest,originalBudget,
                onceDocument,oldAuthorityDocument,oldPlanDocument,currentHostUpperBound())
            else if(knownNativeFailed) Q04KnownNativeFailureRecoveryAdmission.validate(authority,plan,manifest,originalBudget,
                onceDocument,oldAuthorityDocument,oldPlanDocument,currentHostUpperBound())
            else readingPermit=Q04PostSecondKnownZeroAdmission.validate(authority,plan,manifest,originalBudget,
                onceDocument,oldAuthorityDocument,oldPlanDocument,currentHostUpperBound())
        }
        val remaining=plan.getLong("expiresAtEpochMs")-currentHostUpperBound()
        val permit=Q02RealReadInstallation.Permit(plan.getString("run"),plan.getString("leaseId"),plan.getString("installationAndroidIdSha256"),
            plan.getString("appSha256"),plan.getString("runnerSha256"),plan.getString("signerSha256"),plan.getInt("api"),SystemClock.elapsedRealtime()+remaining,
            plan.getString("controlHostId"),plan.getString("localMappingSha256"),checkNotNull(HostReadCodec.target(plan.getJSONObject("target"))),
            plan.getString("endpoint"),plan.getJSONArray("allowedEndpoints").strings(),plan.getString("certificateSha256"),plan.getString("pairingIntentSha256"),null)
        var scenario: ActivityScenario<Q02RealReadActivity>?=null
        var primaryFailure: Throwable?=null
        try {
            instrumentation.runOnMainSync {
                val lifecycle=ActivityLifecycleMonitorRegistry.getInstance()
                require(Stage.values().filter {it!=Stage.DESTROYED}.all {s->lifecycle.getActivitiesInStage(s).none {it is MainActivity}})
                profile=Q02RealReadInstallation.prepareExistingQ04(context,permit,manifest,readingPermit)
            }
            publish("profile-prepared.json",profile.proof())
            val launched=ActivityScenario.launch<Q02RealReadActivity>(Intent(context,Q02RealReadActivity::class.java).setAction(Intent.ACTION_MAIN))
            scenario=launched;launched.onActivity {activity=it}
            val old=checkNotNull(profile.existing).original
            ui.waitUntil(30000){!session.restoring&&!session.loading&&!session.historyLoading&&session.operationRecords.singleOrNull()?.id==old.id}
            require(!session.localRecoveryRequired)
            profile.checkOnlyApprovedTarget()
            require(session.operationRecords.size==1&&session.operationRecords.single().id==old.id&&session.operationRecords.single().intentSha256==old.intentSha256)
            records["origin"]=session.operationRecords.single()
            ui.runOnIdle {session.selectThread(session.threads.single {it.id==plan.getString("threadId")})}
            ui.waitUntil(30000){!session.historyLoading&&session.runtime!=null}
            target();require(!session.running&&session.queuedReplies.isEmpty())
            configureNative()
            val admitted=gate("existing-admitted")
            require(originalTurns(admitted)==expectedTurns()&&admitted.getString("completedTurnId")==r.getString("originalTurnId"))
            require(admitted.getString("subjectOperationId")==old.id&&admitted.getInt("originalUserInputCount")==1)
            ui.runOnIdle {checkNotNull(profile.existing).bindReplyBoundary {name->
                val boundary=checkNotNull(replyBoundaries[name]) {"No original reply proof"}
                businessBoundary(boundary.proof,boundary.turn)
            }}
            work()
            require(records.keys==setOf("origin","stopOrigin","queueCancel","queueExecute","steer"))
            snapshot("remaining-controls-complete.json")
        }catch(failure: Throwable) {
            primaryFailure=failure
            runCatching {captureFailure(failure)}.exceptionOrNull()?.let {addSuppressedSafely(failure,it)}
            throw failure
        }finally {
            completeAllCleanup(primaryFailure,
                {scenario?.close()},
                {if(::profile.isInitialized && !profile.closed) {
                    check(profile.destroyed) {"Owned profile remains live; resource release is incomplete"}
                    runBlocking {withContext(Dispatchers.Main) {Q02RealReadInstallation.close(profile)}}
                }},
                {if(::profile.isInitialized)publish("profile-resource-state.json",profile.proof())})
        }
    }

    @Test fun q04ContinueRemainingControlsOnExistingOwnedProfileOnce() = launchExistingQ04 {
        submit("stopOrigin","next-turn");val controlTurn=turnAfter("stopOrigin")
        require(controlTurn !in plan.getJSONObject("continuation").getJSONArray("freshBaselineTurnIds").strings())
        ui.runOnIdle {checkNotNull(profile.existing).bindControlTurn(controlTurn) {
            require(session.running&&session.runtime?.turnId==controlTurn&&session.selectedThread==plan.getString("threadId"))
        }}
        val cancelled=submit("queueCancel","queue",controlTurn)
        val executed=submit("queueExecute","queue",controlTurn)
        ui.waitUntil(5000){session.queuedReplies.any {it.operationId==cancelled.id}&&session.queuedReplies.any {it.operationId==executed.id}}
        val cancelProof=claim("cancel",controlTurn,cancelled.id)
        val cancel=ui.onNodeWithTag("cancel-queued-"+cancelled.id).performScrollTo().assertIsEnabled()
        businessBoundary(cancelProof,controlTurn);cancel.performClick()
        ui.waitUntil(10000){session.runtime?.receipts?.any {it.operationId==cancelled.id&&it.state=="cancelled"}==true}
        val executeProof=claim("execute",controlTurn,executed.id)
        val row=session.queuedReplies.single {it.operationId==executed.id}
        require(session.canExecuteQueue(row,session.targetKey,controlTurn))
        val execute=ui.onNodeWithTag("execute-queued-"+executed.id).performScrollTo().assertIsEnabled()
        businessBoundary(executeProof,controlTurn)
        require(session.canExecuteQueue(session.queuedReplies.single {it.operationId==executed.id},session.targetKey,controlTurn))
        execute.performClick();ui.waitUntil(10000){session.queuedReplies.none {it.operationId==executed.id}}
        require(session.running&&session.runtime?.turnId==controlTurn)
        submit("steer","steer",controlTurn)
        val draft="LOCAL_UNSENT_DRAFT_"+profile.storageOwnerRun
        require(sha(draft)==plan.getJSONObject("continuation").getString("unsentDraftSha256"))
        ui.onNodeWithTag("message-input").performTextReplacement(draft)
        val stopProof=claim("stop",controlTurn,records.getValue("stopOrigin").id)
        businessBoundary(stopProof,controlTurn)
        ui.onNodeWithTag("send-message").performTouchInput {longClick()}
        val stop=ui.onNodeWithTag("action-stop").assertIsDisplayed().assertIsEnabled()
        businessBoundary(stopProof,controlTurn);stop.performClick()
        ui.waitUntil(10000){!session.running&&session.transferStatus==null}
        assertEquals(draft,session.text)
        val done=gate("stopped")
        require(originalTurns(done)==expectedTurns()&&done.getString("stoppedTurnId")==controlTurn&&done.opt("originalTurnInterrupted")==true)
        require(done.opt("stopOriginToolCount")==1&&done.opt("childStarts")==0&&done.opt("cancelledInputNeverExecuted")==true&&
            done.opt("originalQueuePromotedOnControlTurnOnce")==true&&done.opt("sameControlTurnSteerOnce")==true)
        activeWindow=null;shot("remaining-stop-draft-preserved");queryOriginalRecords();assertEquals(draft,session.text)
        publish("remaining-result-scope.json",JSONObject().put("oldFullNativePassed",false).put("remainingControlsPassed",true).put("originalClaimRetained",true))
    }
    @Test fun q04KnownNativeFailureAdmissionCountsAndReviewedAppFence() {
        q04KnownFailureFixture().validate()
        fun denied(change: (Q04KnownFailureFixture)->Unit) {
            val f=q04KnownFailureFixture();change(f);assertTrue(runCatching {f.validate()}.isFailure)
        }
        denied {it.a.put("kind",Q04PreNativeRecoveryAdmission.AUTHORITY_KIND)}
        denied {it.a.put("maxNewIndependentTurns",2)}
        denied {it.a.put("run",UUID(0,999).toString())}
        denied {it.p.getJSONObject("target").put("hostId",UUID(0,998).toString())}
        denied {it.p.put("nativeSpeedSelection","standard")}
        denied {it.p.put("unreviewedField",true)}
        denied {it.a.getJSONObject("knownNativeFailureRecovery").getJSONObject("currentAdmission").put("noNativePopen",true)}
        denied {it.a.getJSONObject("knownNativeFailureRecovery").getJSONObject("currentAdmission").put("firstNativeMarkerExists",false)}
        denied {it.a.getJSONObject("knownNativeFailureRecovery").getJSONObject("currentAdmission").put("secondNativeMarkerAbsent",false)}
        denied {it.a.getJSONObject("knownNativeFailureRecovery").getJSONObject("currentAdmission").put("knownRemainingNativePopenCount","1")}
        denied {it.a.getJSONObject("knownNativeFailureRecovery").getJSONObject("currentAdmission").put("remainingOperationIds",JSONArray().put("already-created"))}
        denied {it.a.getJSONObject("knownNativeFailureRecovery").getJSONObject("appFingerprintChange").put("rootReviewed",false)}
        denied {it.a.getJSONObject("knownNativeFailureRecovery").getJSONObject("appFingerprintChange").put("newAppSha256","f".repeat(64))}
        denied {it.p.put("appSha256",it.oldPlan.value.getString("appSha256"))}
        for(value in listOf<Any>("0",false,JSONObject.NULL,1)) {
            denied {f->
                val links=f.a.getJSONObject("knownNativeFailureRecovery");val proof=JSONObject(links.getString("failureProofJson"))
                proof.getJSONObject("knownBusinessZero").put("fourRemainingBodySubmissions",value)
                val text=proof.toString();val bytes=text.toByteArray(Charsets.UTF_8)
                links.put("failureProofJson",text);links.getJSONObject("failureProofRef").put("bytes",bytes.size).put("sha256",sha(text))
            }
        }
    }
    @Test fun q04PreNativeRecoveryAdmissionStrictZeroAndOriginalReferences() {
        val exactOldShape=Q04PreNativeRecoveryAdmission.syntheticFixture()
        assertFalse(exactOldShape.old.value.has("settings"));exactOldShape.validate()
        for(v in listOf<Any>("0","unknown",JSONObject.NULL,true,1,-1)) {
            assertTrue(runCatching {Q04PreNativeRecoveryAdmission.syntheticFixture {
                it.getJSONObject("knownZero").put("firstNativeClientPopen",v)
            }.validate()}.isFailure)
        }
        for(k in listOf("firstNativeClientPopen","newModelOrQueueTextSubmissions","newIndependentTurns","newChildStarts","queueCancelOrExecuteOrSteerOrStop")) {
            assertTrue(runCatching {Q04PreNativeRecoveryAdmission.syntheticFixture {it.getJSONObject("knownZero").put(k,1)}.validate()}.isFailure)
        }
        assertTrue(runCatching {Q04PreNativeRecoveryAdmission.syntheticFixture {it.remove("rawReferenceIndexRef")}.validate()}.isFailure)
        assertTrue(runCatching {Q04PreNativeRecoveryAdmission.syntheticFixture {it.put("rootConfirmed",false)}.validate()}.isFailure)
        val wrongRun=Q04PreNativeRecoveryAdmission.syntheticFixture();wrongRun.authority.put("run",UUID(0,999).toString())
        assertTrue(runCatching {wrongRun.validate()}.isFailure)
        val wrongTarget=Q04PreNativeRecoveryAdmission.syntheticFixture();wrongTarget.plan.getJSONObject("target").put("hostId",UUID(0,998).toString())
        assertTrue(runCatching {wrongTarget.validate()}.isFailure)
        val overBudget=Q04PreNativeRecoveryAdmission.syntheticFixture();overBudget.authority.put("maxNewIndependentTurns",2)
        assertTrue(runCatching {overBudget.validate()}.isFailure)
        val wrongSettings=Q04PreNativeRecoveryAdmission.syntheticFixture();wrongSettings.budget.getJSONObject("settings").put("effort","low")
        assertTrue(runCatching {wrongSettings.validate()}.isFailure)
        val wrongSpeed=Q04PreNativeRecoveryAdmission.syntheticFixture();wrongSpeed.plan.put("nativeSpeedSelection","standard")
        assertTrue(runCatching {wrongSpeed.validate()}.isFailure)
        for(k in listOf("controlHostId","endpoint","certificateSha256","onUnknown","nativeOperationIdMode")) {
            val changed=Q04PreNativeRecoveryAdmission.syntheticFixture();changed.plan.put(k,"changed")
            assertTrue(runCatching {changed.validate()}.isFailure)
        }
        val extra=Q04PreNativeRecoveryAdmission.syntheticFixture();extra.plan.put("unreviewedField",true)
        assertTrue(runCatching {extra.validate()}.isFailure)
        val missingPlanField=Q04PreNativeRecoveryAdmission.syntheticFixture();missingPlanField.plan.remove("onUnknown")
        assertTrue(runCatching {missingPlanField.validate()}.isFailure)
        val stale=Q04PreNativeRecoveryAdmission.syntheticFixture();stale.authority.put("freshReviewEpochMs",1L)
        assertTrue(runCatching {stale.validate()}.isFailure)
        val stringTime=Q04PreNativeRecoveryAdmission.syntheticFixture();stringTime.plan.put("startsAtEpochMs","0")
        assertTrue(runCatching {stringTime.validate()}.isFailure)
        val wrongSha=Q04PreNativeRecoveryAdmission.syntheticFixture();wrongSha.authority.getJSONObject("preNativeRecovery").put("zeroProofJson","{}")
        assertTrue(runCatching {wrongSha.validate()}.isFailure)
        val oldRef=Q04PreNativeRecoveryAdmission.syntheticFixture();oldRef.authority.getJSONObject("preNativeRecovery").getJSONObject("originalReservationRef").put("sha256","f".repeat(64))
        assertTrue(runCatching {oldRef.validate()}.isFailure)
        val newReceipt=Q04PreNativeRecoveryAdmission.syntheticFixture()
        newReceipt.authority.getJSONObject("preNativeRecovery").getJSONObject("currentAdmission").put("remainingOperationIds",JSONArray().put("already-created"))
        assertTrue(runCatching {newReceipt.validate()}.isFailure)
    }
    @Test fun q04OwnedPolicyNoopAndRejectedExtensions() {
        val protected=Q04ExistingOwnedProfile.protectedNames.associateWith {"original-"+it}
        var validates=0
        val policy=NativeSessionStorage.ExistingOwnedPolicy(protected,setOf("metadata","operations:synthetic"),"synthetic",{},{_,_->validates++})
        protected.forEach {(name,text)->
            assertFalse(policy.beforeText(name,text));assertEquals(0,validates)
            assertTrue(runCatching {policy.beforeText(name,text+"changed")}.isFailure)
            assertTrue(runCatching {policy.beforeRemove(name)}.isFailure)
        }
        assertTrue(runCatching {policy.beforeText("unknown","{}")} .isFailure)
        assertTrue(runCatching {policy.beforeSnapshot("unknown")}.isFailure)
        assertTrue(runCatching {policy.rejectExtension()}.isFailure)
        assertTrue(policy.beforeText("metadata","{}"));assertEquals(1,validates)
    }
    @Test fun q04JournalRejectsNextTurnFallbackBeforeSubmission() {
        val slots=JSONObject()
        listOf("stopOrigin","queueCancel","queueExecute","steer").forEach {slots.put(it,JSONObject().put("textSha256",sha("synthetic-"+it)))}
        fun record(name: String,mode: String,turn: String?="control"): SavedOperation {
            val body=JSONObject().put("hostId","synthetic-host").put("threadId","synthetic-thread").put("clientMessageId","synthetic-id")
                .put("text","synthetic-"+name).put("attachmentIds",JSONArray()).put("mode",mode)
            turn?.let {body.put("expectedTurnId",it)}
            return SavedOperation("synthetic-id","reply","synthetic-target",body.toString(),sha(body.toString()),"ready")
        }
        fun validate(row: SavedOperation)=Q04ExistingOwnedProfile.validateRemainingReply(row,slots,"synthetic-host","synthetic-thread","control")
        assertEquals("stopOrigin",validate(record("stopOrigin","next-turn",null)))
        for(name in listOf("queueCancel","queueExecute","steer")) {
            assertEquals(name,validate(record(name,if(name=="steer")"steer" else "queue")))
            assertTrue(runCatching {validate(record(name,"next-turn",null))}.isFailure)
            assertTrue(runCatching {validate(record(name,if(name=="steer")"steer" else "queue","other-turn"))}.isFailure)
        }
        assertTrue(runCatching {validate(record("origin","next-turn",null))}.isFailure)
    }
    @Test fun q04JournalRechecksOriginalClockBeforeSubmitting() {
        var elapsed=49_000L
        var checks=0
        var sends=0
        val clock=NativeProofClock.Receipt(10_000L,5_000L)
        val verify: (String)->Unit={slot->
            assertEquals("steer",slot);checks++
            require(clock.ageWithin(10_000L,elapsed,45_000L)!=null)
        }
        val ready=SavedOperation("synthetic-id","reply","synthetic-target","{}",sha("{}"),"ready")
        Q04ExistingOwnedProfile.beforeNewReplyWrite(ready,null,"steer",verify)
        elapsed=51_001L // The same original tool is now past 45s; do not reset its start.
        val submitting=ready.copy(phase="submitting")
        assertTrue(runCatching {
            Q04ExistingOwnedProfile.beforeNewReplyWrite(submitting,ready,"steer",verify)
            sends++
        }.isFailure)
        assertEquals(2,checks);assertEquals(0,sends)
    }
}

/** Pure recovery admission only; never opens Context/storage/KeyStore or sends a command. */
internal object Q04PreNativeRecoveryAdmission {
    const val AUTHORITY_KIND="root-q04-confirmed-pre-native-recovery-authority-v1"
    internal val mutablePlanFields=setOf("startsAtEpochMs","expiresAtEpochMs","startsAt","expiresAt","rootAuthoritySha256",
        "runnerSha256","nativeManifestRef","desktopExecutableSha256","desktopRuntimeFingerprint","localMappingSha256","productDirectoryProofSha256")
    data class Document(val ref: JSONObject,val value: JSONObject)
    private fun same(a: Any?,b: Any?) {require(easyCanonical(a)==easyCanonical(b))}
    private fun digest(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun integer(o: JSONObject,k: String): Long {
        val v=o.get(k);require(v is Int||v is Long) {"Strict integer required: $k"};return (v as Number).toLong()
    }
    fun checkRef(r: JSONObject) {
        require(r.keys().asSequence().toSet()==setOf("path","bytes","sha256"))
        val p=r.getString("path")
        require(p.startsWith("/")&&!p.contains('\u0000')&&p.split('/').drop(1).all {it.isNotEmpty()&&it!="."&&it!=".."})
        require(integer(r,"bytes") in 1..1_048_576&&r.getString("sha256").matches(Regex("[a-f0-9]{64}")))
    }

    data class Fixture(val authority: JSONObject,val plan: JSONObject,val profile: JSONObject,val budget: JSONObject,val once: Document,val old: Document,val oldPlan: Document) {
        fun validate()=Q04PreNativeRecoveryAdmission.validate(authority,plan,profile,budget,once,old,oldPlan,1_000_000L)
    }
    fun syntheticFixture(mutateProof: (JSONObject)->Unit={}): Fixture {
        fun clone(o: JSONObject)=JSONObject(o.toString())
        fun doc(name: String,value: JSONObject): Document {
            val bytes=value.toString().toByteArray(Charsets.UTF_8)
            return Document(JSONObject().put("path","/synthetic-recovery-proof/$name.json").put("bytes",bytes.size).put("sha256",digest(value.toString())),value)
        }
        val raw=doc("raw",JSONObject().put("synthetic",true)).ref
        val run=UUID(0,1).toString();val lease=UUID(0,2).toString();val operation=UUID(0,3).toString();val thread=UUID(0,4).toString()
        val target=JSONObject().put("agentId","codex").put("hostId",UUID(0,5).toString()).put("workspaceId",UUID(0,5).toString()).put("runtimeId","desktop-runtime")
        val names=listOf("origin","stopOrigin","queueCancel","queueExecute","steer");val slots=JSONObject()
        names.forEachIndexed {i,n->slots.put(n,JSONObject().put("slotId",UUID(0,10L+i).toString()).put("text","synthetic-$n").put("textSha256",digest("synthetic-$n")).put("attachments",JSONArray()))}
        val inventory=JSONArray();repeat(11){i->inventory.put(JSONObject().put("path","no-backup/q02-session/"+digest("file-$i")+".bin").put("bytes",if(i==0)397635 else 1).put("sha256",digest("cipher-$i")))}
        raw.put("path","/synthetic-recovery-proof/runtime/q04-execution-claims-v1/$operation/intent.json")
        val profile=JSONObject().put("storageOwnerRun",UUID(0,6).toString()).put("inventory",inventory)
        val profileRef=doc("profile",profile).ref
        val a=JSONObject().put("kind","root-q04-remaining-four-authority-v1").put("executionAuthorized",true).put("preparedOnly",false)
            .put("run",run).put("leaseId",lease).put("budgetOperationId",operation).put("target",target).put("threadId",thread)
            .put("settings",JSONObject().put("model","gpt-6.1-sol").put("effort","xhigh").put("permissionMode","full-access"))
            .put("originalOrigin",JSONObject().put("operationId",UUID(0,7).toString()).put("turnId","origin-turn").put("bodySha256",digest("synthetic-origin")))
            .put("remainingSlots",JSONArray(names.drop(1))).put("originalClaimRef",raw).put("existingProfileRef",profileRef)
            .put("maxNewIndependentTurns",1).put("maxTotalQ04Turns",2).put("childStartsMax",0).put("processSeconds",40).put("terminalWindowMs",45000).put("perProcessPollMax",1)
            .put("notBeforeMs",1L).put("expiresAtMs",100L)
        val settings=JSONObject().put("model","gpt-6.1-sol").put("effort","xhigh").put("permissionMode","full-access")
        val control=UUID(0,8).toString()
        val originalBudget=JSONObject().put("budgetOperationId",operation).put("controlHostId",control).put("threadId",thread).put("target",clone(target)).put("settings",settings).put("nativeSpeedSelection","fast").put("q04",JSONObject().put("slots",slots))
        a.remove("settings") // Real immutable old authority has no settings field.
        a.put("originalBudgetRef",doc("budget",originalBudget).ref).put("storageOwnerRun",profile.getString("storageOwnerRun"))
            .put("settledOriginRef",raw).put("originalLeaseReleaseRef",raw).put("toolBudgetReviewRef",raw)
            .put("hostRuntimeSessionSha256",digest("synthetic-runtime")).put("hostImplementationRef",raw).put("nativeManifestRef",raw)
        val old=doc("old-authority",clone(a));val once=doc("reserved-once",JSONObject().put("kind","remaining-four-on-second-turn-v1")
            .put("run",run).put("leaseId",lease).put("budgetOperationId",operation).put("authorityRef",old.ref).put("originalClaimRef",raw).put("slots",JSONArray(names.drop(1))))
        once.ref.put("path","/synthetic-recovery-proof/runtime/q04-execution-claims-v1/$operation/continuations/remaining-four-on-second-turn-v1/intent.json")
        val p=JSONObject().put("taskId","synthetic-task").put("run",run).put("leaseId",lease).put("budgetOperationId",operation).put("target",clone(target)).put("threadId",thread)
            .put("controlHostId",control).put("endpoint","https://synthetic.invalid").put("certificateSha256",digest("synthetic-cert"))
            .put("onUnknown","query-original-only").put("nativeOperationIdMode","journal-generated-once").put("nativeManifestRef",raw)
            .put("settings",clone(settings)).put("nativeSpeedSelection","fast").put("slots",slots).put("baselineTurnIds",JSONArray(listOf("q03-turn")))
            .put("continuation",JSONObject().put("freshBaselineTurnIds",JSONArray(listOf("q03-turn","origin-turn"))).put("allowedHistoricalChildIds",JSONArray(listOf("old-child"))))
            .put("modelToolWorkingDirectory","/synthetic-workdir").put("toolCommandSha256",digest("synthetic-command"))
            .put("rootAuthoritySha256",old.ref.getString("sha256")).put("childOnceSha256",once.ref.getString("sha256"))
            .put("maxTextSubmissions",5).put("maxIndependentOriginTurns",2).put("maxChildStarts",0).put("maxToolSeconds",40).put("maxOriginalSessionPolls",1)
            .put("appSha256",digest("app")).put("runnerSha256",digest("runner")).put("signerSha256",digest("signer")).put("startsAtEpochMs",1L).put("expiresAtEpochMs",100L)
        val oldPlan=doc("old-plan",clone(p));val now=1_000_000L
        a.put("kind",AUTHORITY_KIND).put("notBeforeMs",now-1000).put("expiresAtMs",now+60000).put("freshReviewEpochMs",now).put("freshReviewRef",raw)
        p.put("startsAtEpochMs",now-1000).put("expiresAtEpochMs",now+60000)

        val z=JSONObject().put("kind","root-confirmed-zero-before-first-native-v1").put("rootConfirmed",true)
            .put("taskId","synthetic-task").put("run",run).put("leaseId",lease).put("target",clone(target)).put("threadId",thread)
            .put("originalReservationRef",once.ref).put("originalAuthorityRef",old.ref).put("originalPlanRef",oldPlan.ref).put("originalClaimRef",raw)
            .put("knownZero",JSONObject().put("firstNativeClientPopen",0).put("newModelOrQueueTextSubmissions",0).put("newIndependentTurns",0).put("newChildStarts",0).put("queueCancelOrExecuteOrSteerOrStop",0))
            .put("notZeroSideEffects",JSONObject().put("fixedChildCreatedAndRetained",true).put("controllerStartedPID",123).put("controllerExit",1)
                .put("phoneNineJSONStagedAndRemoved",true).put("phoneOwnerLockRetained",true).put("originalProfileNotRestoredByNative",true))
            .put("sourceAuditRef",raw).put("rawReferenceIndexRef",raw).put("actorReleaseHandoffRef",raw).put("rawRefsVerified",234)
            .put("rootReadSourceGuardAndRunOnceOrder",true).put("originalFilesUnchanged",true).put("unknownOrPopenRecoveryAllowed",false)
            .put("executionAuthorized",false).put("recoveryAuthorized",false).put("freshAuthorized",false)
        mutateProof(z)
        val current=JSONObject().put("reviewRef",raw).put("observedAtEpochMs",now).put("target",clone(target)).put("threadId",thread)
            .put("turnIds",JSONArray(listOf("q03-turn","origin-turn"))).put("turns",JSONArray().put(JSONObject().put("id","q03-turn").put("status","completed")).put(JSONObject().put("id","origin-turn").put("status","completed")))
            .put("childIds",JSONArray(listOf("old-child"))).put("pendingRequests",0).put("knownQ04Inputs",1).put("hostBindings",12).put("unreviewedInputs",0)
            .put("runtimeState","idle").put("originalOwnerVerified",true).put("newControlTurns",JSONArray()).put("remainingOperationIds",JSONArray())
            .put("profileInventorySha256",digest(easyCanonical(inventory))).put("rawRefs",JSONArray().put(raw))
        for(k in listOf("noFirstNativeIntent","noNativePopen","noRemainingReceipts","profileUnchanged","bindingContinuityVerified","ownerLockVerifiedAndReused"))current.put(k,true)
        val proof=doc("known-zero",z)
        a.put("preNativeRecovery",JSONObject().put("originalReservationRef",once.ref).put("originalAuthorityRef",old.ref).put("originalPlanRef",oldPlan.ref)
            .put("zeroProofRef",proof.ref).put("zeroProofJson",z.toString()).put("currentAdmission",current))
        p.put("taskId","synthetic-task")
        return Fixture(a,p,profile,originalBudget,once,old,oldPlan)
    }

    fun validate(a: JSONObject,p: JSONObject,profile: JSONObject,originalBudget: JSONObject,once: Document,old: Document,oldPlan: Document,now: Long) {
        require(a.getString("kind")==AUTHORITY_KIND&&a.opt("executionAuthorized")==true&&a.opt("preparedOnly")==false)
        require(now>=integer(a,"notBeforeMs")&&now<integer(a,"expiresAtMs")&&integer(a,"expiresAtMs")==integer(p,"expiresAtEpochMs"))
        require(integer(p,"startsAtEpochMs")==integer(a,"notBeforeMs"))
        val fresh=integer(a,"freshReviewEpochMs");require(now>=fresh&&now-fresh<=30_000);checkRef(a.getJSONObject("freshReviewRef"))
        val links=a.getJSONObject("preNativeRecovery")
        for((k,doc) in listOf("originalReservationRef" to once,"originalAuthorityRef" to old,"originalPlanRef" to oldPlan)) {
            checkRef(doc.ref);same(links.getJSONObject(k),doc.ref)
        }
        require(p.getString("childOnceSha256")==once.ref.getString("sha256"))
        val r=once.value
        require(r.getString("kind")=="remaining-four-on-second-turn-v1");same(r.getJSONObject("authorityRef"),old.ref)
        same(r.getJSONObject("originalClaimRef"),a.getJSONObject("originalClaimRef"))
        require(old.value.getString("kind")=="root-q04-remaining-four-authority-v1"&&old.value.opt("executionAuthorized")==true&&
            old.value.opt("preparedOnly")==false&&integer(old.value,"expiresAtMs")<now)
        require(oldPlan.value.getString("rootAuthoritySha256")==old.ref.getString("sha256")&&oldPlan.value.getString("childOnceSha256")==once.ref.getString("sha256"))
        for(k in listOf("run","leaseId","budgetOperationId")) {same(a.get(k),p.get(k));same(a.get(k),r.get(k));same(a.get(k),old.value.get(k));same(a.get(k),oldPlan.value.get(k))}
        val suffix="/runtime/q04-execution-claims-v1/"+a.getString("budgetOperationId")
        require(once.ref.getString("path").endsWith(suffix+"/continuations/remaining-four-on-second-turn-v1/intent.json"))
        require(a.getJSONObject("originalClaimRef").getString("path").endsWith(suffix+"/intent.json"))
        for(k in listOf("target","threadId","originalOrigin","remainingSlots","originalClaimRef","existingProfileRef","storageOwnerRun","originalBudgetRef",
            "settledOriginRef","originalLeaseReleaseRef","toolBudgetReviewRef","hostRuntimeSessionSha256","hostImplementationRef"))same(a.get(k),old.value.get(k))
        val oldKeys=oldPlan.value.keys().asSequence().toSet()-mutablePlanFields
        val newKeys=p.keys().asSequence().toSet()-mutablePlanFields
        require(oldKeys==newKeys);oldKeys.forEach {same(p.get(it),oldPlan.value.get(it))}
        same(p.getJSONObject("nativeManifestRef"),a.getJSONObject("nativeManifestRef"));checkRef(a.getJSONObject("nativeManifestRef"))
        for(k in listOf("budgetOperationId","controlHostId","target","threadId","settings","nativeSpeedSelection"))same(p.get(k),originalBudget.get(k))
        same(p.getJSONObject("slots"),originalBudget.getJSONObject("q04").getJSONObject("slots"))
        for((k,v) in mapOf("maxNewIndependentTurns" to 1L,"maxTotalQ04Turns" to 2L,"childStartsMax" to 0L,"processSeconds" to 40L,"terminalWindowMs" to 45000L,"perProcessPollMax" to 1L)) {
            require(integer(a,k)==v);same(a.get(k),old.value.get(k))
        }
        require(integer(p,"maxTextSubmissions")==5L&&integer(p,"maxIndependentOriginTurns")==2L&&integer(p,"maxChildStarts")==0L&&
            integer(p,"maxToolSeconds")==40L&&integer(p,"maxOriginalSessionPolls")==1L)
        val slots=listOf("stopOrigin","queueCancel","queueExecute","steer")
        same(a.getJSONArray("remainingSlots"),JSONArray(slots));same(r.getJSONArray("slots"),JSONArray(slots))
        val text=links.getString("zeroProofJson");val proofRef=links.getJSONObject("zeroProofRef");checkRef(proofRef)
        require(text.toByteArray(Charsets.UTF_8).size.toLong()==integer(proofRef,"bytes")&&digest(text)==proofRef.getString("sha256"))
        val z=JSONObject(text)
        require(z.getString("kind")=="root-confirmed-zero-before-first-native-v1"&&z.opt("rootConfirmed")==true)
        same(z.get("taskId"),p.get("taskId"))
        for(k in listOf("executionAuthorized","recoveryAuthorized","freshAuthorized","unknownOrPopenRecoveryAllowed"))require(z.opt(k)==false)
        for(k in listOf("run","leaseId","target","threadId"))same(z.get(k),a.get(k))
        for((k,doc) in listOf("originalReservationRef" to once,"originalAuthorityRef" to old,"originalPlanRef" to oldPlan))same(z.getJSONObject(k),doc.ref)
        same(z.getJSONObject("originalClaimRef"),a.getJSONObject("originalClaimRef"))
        val counts=z.getJSONObject("knownZero")
        val keys=setOf("firstNativeClientPopen","newModelOrQueueTextSubmissions","newIndependentTurns","newChildStarts","queueCancelOrExecuteOrSteerOrStop")
        require(counts.keys().asSequence().toSet()==keys);keys.forEach {require(integer(counts,it)==0L)}
        val effects=z.getJSONObject("notZeroSideEffects")
        for(k in listOf("fixedChildCreatedAndRetained","phoneNineJSONStagedAndRemoved","phoneOwnerLockRetained","originalProfileNotRestoredByNative"))require(effects.opt(k)==true)
        require(integer(effects,"controllerStartedPID")>0&&integer(effects,"controllerExit")==1L)
        for(k in listOf("sourceAuditRef","rawReferenceIndexRef","actorReleaseHandoffRef"))checkRef(z.getJSONObject(k))
        require(integer(z,"rawRefsVerified")>0&&z.opt("originalFilesUnchanged")==true&&z.opt("rootReadSourceGuardAndRunOnceOrder")==true)
        // The immutable known-zero fact is not fresh execution authority. Check the separate current Root seal.
        val current=links.getJSONObject("currentAdmission")
        same(current.getJSONObject("reviewRef"),a.getJSONObject("freshReviewRef"));require(integer(current,"observedAtEpochMs")==fresh)
        same(current.getJSONObject("target"),p.getJSONObject("target"));same(current.get("threadId"),p.get("threadId"))
        same(current.getJSONArray("turnIds"),p.getJSONObject("continuation").getJSONArray("freshBaselineTurnIds"))
        val turns=current.getJSONArray("turns");require(turns.length()==2)
        for(i in 0 until 2)require(turns.getJSONObject(i).getString("id")==current.getJSONArray("turnIds").getString(i)&&turns.getJSONObject(i).getString("status")=="completed")
        same(current.getJSONArray("childIds"),p.getJSONObject("continuation").getJSONArray("allowedHistoricalChildIds"))
        require(integer(current,"pendingRequests")==0L&&integer(current,"knownQ04Inputs")==1L&&integer(current,"hostBindings")==12L&&
            integer(current,"unreviewedInputs")==0L&&current.getString("runtimeState")=="idle"&&current.opt("originalOwnerVerified")==true)
        for(k in listOf("noFirstNativeIntent","noNativePopen","noRemainingReceipts","profileUnchanged","bindingContinuityVerified","ownerLockVerifiedAndReused"))require(current.opt(k)==true)
        require(current.getJSONArray("newControlTurns").length()==0&&current.getJSONArray("remainingOperationIds").length()==0)
        require(current.getString("profileInventorySha256")==digest(easyCanonical(profile.getJSONArray("inventory"))))
        val raw=current.getJSONArray("rawRefs");require(raw.length() in 1..32);for(i in 0 until raw.length())checkRef(raw.getJSONObject(i))
    }
}

/** Explicit successor after one confirmed failed Native admission, never a pre-Native-zero retry. */
internal object Q04KnownNativeFailureRecoveryAdmission {
    const val AUTHORITY_KIND="root-q04-known-native-failed-before-business-authority-v1"
    const val FACT_KIND="root-confirmed-native-failed-before-profile-open-no-business-v1"
    const val ATTEMPT_KIND="canonical-admission-successor-v1"
    private val mutablePlanFields=Q04PreNativeRecoveryAdmission.mutablePlanFields+"appSha256"
    private val stableAuthorityFields=listOf("target","threadId","originalOrigin","remainingSlots","originalClaimRef","existingProfileRef","storageOwnerRun",
        "originalBudgetRef","settledOriginRef","originalLeaseReleaseRef","toolBudgetReviewRef","hostRuntimeSessionSha256","hostImplementationRef")
    private fun same(a: Any?,b: Any?) {require(easyCanonical(a)==easyCanonical(b))}
    private fun integer(o: JSONObject,k: String): Long {val v=o.get(k);require(v is Int||v is Long);return (v as Number).toLong()}
    private fun digest(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun ref(o: JSONObject)=Q04PreNativeRecoveryAdmission.checkRef(o)
    private fun codes(a: JSONArray)=List(a.length()){i->val v=a.get(i);require(v is Int||v is Long);(v as Number).toLong()}
    fun validate(a: JSONObject,p: JSONObject,profile: JSONObject,budget: JSONObject,
        once: Q04PreNativeRecoveryAdmission.Document,old: Q04PreNativeRecoveryAdmission.Document,oldPlan: Q04PreNativeRecoveryAdmission.Document,now: Long) {
        require(a.getString("kind")==AUTHORITY_KIND&&a.opt("executionAuthorized")==true&&a.opt("preparedOnly")==false)
        require(integer(a,"notBeforeMs")<=now&&now<integer(a,"expiresAtMs"))
        require(integer(p,"startsAtEpochMs")==integer(a,"notBeforeMs")&&integer(p,"expiresAtEpochMs")==integer(a,"expiresAtMs"))
        val fresh=integer(a,"freshReviewEpochMs");require(fresh<=now&&now-fresh<=30_000);ref(a.getJSONObject("freshReviewRef"))
        require(!a.has("preNativeRecovery")) // The3575B Popen0 fact is history, never this phase's current eligibility.
        val links=a.getJSONObject("knownNativeFailureRecovery")
        require(links.getString("attemptKind")==ATTEMPT_KIND&&integer(links,"attemptNumber")==2L)
        for((k,doc)in listOf("originalReservationRef" to once,"originalAuthorityRef" to old,"originalPlanRef" to oldPlan)) {ref(doc.ref);same(links.getJSONObject(k),doc.ref)}
        require(old.value.getString("kind")=="root-q04-remaining-four-authority-v1"&&old.value.opt("executionAuthorized")==true&&old.value.opt("preparedOnly")==false)
        require(integer(old.value,"expiresAtMs")<now)
        val r=once.value;require(r.getString("kind")=="remaining-four-on-second-turn-v1")
        same(r.getJSONObject("authorityRef"),old.ref);same(r.getJSONObject("originalClaimRef"),a.getJSONObject("originalClaimRef"))
        require(oldPlan.value.getString("rootAuthoritySha256")==old.ref.getString("sha256")&&oldPlan.value.getString("childOnceSha256")==once.ref.getString("sha256"))
        require(p.getString("childOnceSha256")==once.ref.getString("sha256"))
        for(k in listOf("run","leaseId","budgetOperationId")) {same(a.get(k),p.get(k));same(a.get(k),r.get(k));same(a.get(k),old.value.get(k));same(a.get(k),oldPlan.value.get(k))}
        val suffix="/runtime/q04-execution-claims-v1/"+a.getString("budgetOperationId")
        require(once.ref.getString("path").endsWith(suffix+"/continuations/remaining-four-on-second-turn-v1/intent.json"))
        require(a.getJSONObject("originalClaimRef").getString("path").endsWith(suffix+"/intent.json"))
        stableAuthorityFields.forEach {same(a.get(it),old.value.get(it))}
        val oldKeys=oldPlan.value.keys().asSequence().toSet()-mutablePlanFields
        require(p.keys().asSequence().toSet()-mutablePlanFields==oldKeys);oldKeys.forEach {same(p.get(it),oldPlan.value.get(it))}
        for(k in listOf("budgetOperationId","controlHostId","target","threadId","settings","nativeSpeedSelection"))same(p.get(k),budget.get(k))
        same(p.getJSONObject("slots"),budget.getJSONObject("q04").getJSONObject("slots"))
        val remaining=JSONArray(listOf("stopOrigin","queueCancel","queueExecute","steer"));same(a.getJSONArray("remainingSlots"),remaining);same(r.getJSONArray("slots"),remaining)
        for((k,v)in mapOf("maxNewIndependentTurns" to 1L,"maxTotalQ04Turns" to 2L,"childStartsMax" to 0L,"processSeconds" to 40L,"terminalWindowMs" to 45000L,"perProcessPollMax" to 1L)) {
            require(integer(a,k)==v);same(a.get(k),old.value.get(k))
        }
        require(integer(p,"maxTextSubmissions")==5L&&integer(p,"maxIndependentOriginTurns")==2L&&integer(p,"maxChildStarts")==0L&&integer(p,"maxToolSeconds")==40L&&integer(p,"maxOriginalSessionPolls")==1L)
        same(p.getJSONObject("nativeManifestRef"),a.getJSONObject("nativeManifestRef"));ref(a.getJSONObject("nativeManifestRef"))
        val text=links.getString("failureProofJson");val proofRef=links.getJSONObject("failureProofRef");ref(proofRef)
        require(text.toByteArray(Charsets.UTF_8).size.toLong()==integer(proofRef,"bytes")&&digest(text)==proofRef.getString("sha256"))
        val facts=JSONObject(text);require(facts.getString("kind")==FACT_KIND&&facts.opt("rootConfirmed")==true)
        for(k in listOf("executionAuthorized","recoveryAuthorized","freshAuthorized","replayAllowed"))require(facts.opt(k)==false)
        for(k in listOf("run","leaseId","target","threadId"))same(facts.get(k),a.get(k))
        for(k in listOf("originalReservationRef","originalAuthorityRef","originalPlanRef"))same(facts.getJSONObject(k),links.getJSONObject(k))
        same(facts.getJSONObject("originalClaimRef"),a.getJSONObject("originalClaimRef"))
        for(k in listOf("firstRecoveryAuthorityRef","firstRecoveryIntentRef","firstNativeIntentRef")) {ref(links.getJSONObject(k));same(facts.getJSONObject(k),links.getJSONObject(k))}
        ref(links.getJSONObject("firstRecoveryPlanRef"));ref(links.getJSONObject("firstNativeManifestRef"))
        val native=facts.getJSONObject("nativeAttempt")
        for((k,v)in mapOf("clientPopen" to 1L,"methods" to 1L,"failures" to 1L,"unknown" to 0L))require(integer(native,k)==v)
        require(codes(native.getJSONArray("JNI"))==listOf(1L,-2L)&&codes(native.getJSONArray("final"))==listOf(-1L))
        require(native.getString("exactMethod")=="com.agenthub.android.MobileGenerationCommandRealDevR2Test#q04ContinueRemainingControlsOnExistingOwnedProfileOnce")
        require(native.getString("failureSite")=="Q04ExistingOwnedProfile.directory:57 via admit:107")
        for(k in listOf("beforeOwnedProfileFileLock","beforeOwnedProfileKeyStore","beforeInspectSessionActivityAndBusiness"))require(native.opt(k)==true)
        val counts=facts.getJSONObject("knownBusinessZero")
        val keys=setOf("fourRemainingBodySubmissions","newIndependentTurns","newChildStarts","queueCancelOrExecuteOrSteerOrStopFromThisAttempt")
        require(counts.keys().asSequence().toSet()==keys);keys.forEach {require(integer(counts,it)==0L)}
        for(k in listOf("firstNativeResultRef","nativeRawJNIRef","controllerExitRef","afterFailureResourceProofRef","afterFailureBusinessAuditRef","rootIndependentReviewRef","officialQASnapshotRef","historicalPreFirstNativeZeroProofRef"))ref(facts.getJSONObject(k))
        same(facts.getJSONArray("originalCompletedTurns"),p.getJSONObject("continuation").getJSONArray("freshBaselineTurnIds"))
        same(facts.getJSONArray("originalChildOnly"),p.getJSONObject("continuation").getJSONArray("allowedHistoricalChildIds"))
        for(k in listOf("original7eccCompleted","existingProfile11Unchanged","hostBindings12Unchanged","appOps37Restored","oldFilesMustRemainImmutable"))require(facts.opt(k)==true)
        for(k in listOf("aliasKeyUsabilityVerified","uniquePathCanonicalCauseVerified","currentAdmissionOrSecondNativeAllowed"))require(facts.opt(k)==false)
        val releaseRef=links.getJSONObject("firstAttemptReleaseRef");ref(releaseRef)
        val releaseText=links.getString("firstAttemptReleaseJson")
        require(releaseText.toByteArray(Charsets.UTF_8).size.toLong()==integer(releaseRef,"bytes")&&digest(releaseText)==releaseRef.getString("sha256"))
        val release=JSONObject(releaseText)
        require(release.getString("kind")=="root-q04-first-native-attempt-release-v1"&&release.opt("executionOperationReleased")==true&&release.opt("continuationExecutionAuthorized")==false)
        same(release.get("run"),a.get("run"));same(release.get("leaseId"),a.get("leaseId"));same(release.getJSONObject("RootFailureFactsRef"),proofRef)
        for(k in listOf("firstNativeMarkerRetained","originalReservationRetained","controllerExited","pumpJoined"))require(release.opt(k)==true)
        val app=links.getJSONObject("appFingerprintChange")
        require(app.getString("kind")=="root-reviewed-canonical-admission-app-change-v1"&&app.opt("rootReviewed")==true)
        require(app.getString("reviewedFix")=="Q04ExistingOwnedProfile-canonical-directory-source-v1");ref(app.getJSONObject("reviewRef"));ref(app.getJSONObject("sourcePatchRef"))
        require(app.getString("oldAppSha256")==oldPlan.value.getString("appSha256"))
        require(app.getString("newAppSha256")==p.getString("appSha256"))
        require(app.getString("newAppSha256").matches(Regex("[a-f0-9]{64}")))
        require(app.getString("package")=="com.easyai.agenthub.android.acceptance"&&integer(app,"uid")==integer(profile,"uid")&&integer(app,"api")==integer(p,"api"))
        require(app.getString("signerSha256")==p.getString("signerSha256")&&p.getString("signerSha256")==oldPlan.value.getString("signerSha256"))
        require(app.getString("installationAndroidIdSha256")==p.getString("installationAndroidIdSha256"))
        same(app.getJSONObject("currentNativeManifestRef"),a.getJSONObject("nativeManifestRef"));same(app.getJSONObject("failedNativeManifestRef"),links.getJSONObject("firstNativeManifestRef"))
        val current=links.getJSONObject("currentAdmission")
        require(!current.has("noFirstNativeIntent")&&!current.has("noNativePopen"))
        same(current.getJSONObject("reviewRef"),a.getJSONObject("freshReviewRef"));require(integer(current,"observedAtEpochMs")==fresh)
        same(current.getJSONObject("target"),p.getJSONObject("target"));same(current.get("threadId"),p.get("threadId"))
        require(current.opt("firstNativeMarkerExists")==true);same(current.getJSONObject("firstNativeMarkerRef"),links.getJSONObject("firstNativeIntentRef"))
        require(integer(current,"knownRemainingNativePopenCount")==1L&&integer(current,"unknownNativeOutcomeCount")==0L&&current.opt("secondNativeMarkerAbsent")==true)
        same(current.getJSONArray("turnIds"),p.getJSONObject("continuation").getJSONArray("freshBaselineTurnIds"))
        val turns=current.getJSONArray("turns");require(turns.length()==2)
        for(i in 0 until 2)require(turns.getJSONObject(i).getString("id")==current.getJSONArray("turnIds").getString(i)&&turns.getJSONObject(i).getString("status")=="completed")
        same(current.getJSONArray("childIds"),p.getJSONObject("continuation").getJSONArray("allowedHistoricalChildIds"))
        require(integer(current,"pendingRequests")==0L&&integer(current,"knownQ04Inputs")==1L&&integer(current,"hostBindings")==12L&&integer(current,"unreviewedInputs")==0L&&current.getString("runtimeState")=="idle")
        for(k in listOf("originalOwnerVerified","noRemainingReceipts","profileUnchanged","bindingContinuityVerified","ownerLockVerifiedAndReused"))require(current.opt(k)==true)
        require(current.opt("readOnlyProfileAdmissionPassed")==true);ref(current.getJSONObject("readOnlyProfileAdmissionReviewRef"))
        require(current.getJSONArray("newControlTurns").length()==0&&current.getJSONArray("remainingOperationIds").length()==0)
        require(current.getString("profileInventorySha256")==digest(easyCanonical(profile.getJSONArray("inventory"))))
        val raw=current.getJSONArray("rawRefs");require(raw.length() in 1..32);for(i in 0 until raw.length())ref(raw.getJSONObject(i))
    }
}


/** Pure JSON counterexamples only. Does not issue Root authority or touch Android state. */
private data class Q04KnownFailureFixture(val a: JSONObject,val p: JSONObject,val profile: JSONObject,val budget: JSONObject,
    val once: Q04PreNativeRecoveryAdmission.Document,val old: Q04PreNativeRecoveryAdmission.Document,val oldPlan: Q04PreNativeRecoveryAdmission.Document) {
    fun validate()=Q04KnownNativeFailureRecoveryAdmission.validate(a,p,profile,budget,once,old,oldPlan,1_000_000L)
}
private fun q04KnownFailureFixture(): Q04KnownFailureFixture {
    fun clone(v: JSONObject)=JSONObject(v.toString())
    fun sha(v: String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    fun doc(name: String,v: JSONObject): Q04PreNativeRecoveryAdmission.Document {
        val text=v.toString();return Q04PreNativeRecoveryAdmission.Document(JSONObject().put("path","/synthetic-known-failure/$name.json").put("bytes",text.toByteArray(Charsets.UTF_8).size).put("sha256",sha(text)),v)
    }
    val base=Q04PreNativeRecoveryAdmission.syntheticFixture();val raw=doc("raw",JSONObject().put("synthetic",true)).ref
    val profile=clone(base.profile).put("uid",10123)
    val oldValue=clone(base.old.value).put("existingProfileRef",doc("profile",profile).ref)
    val old=doc("old-authority",oldValue)
    val onceValue=clone(base.once.value).put("authorityRef",old.ref)
    val once=doc("once",onceValue);once.ref.put("path",base.once.ref.getString("path"))
    val oldPlanValue=clone(base.oldPlan.value).put("rootAuthoritySha256",old.ref.getString("sha256")).put("childOnceSha256",once.ref.getString("sha256"))
        .put("api",33).put("installationAndroidIdSha256",sha("synthetic-installation"))
    val oldPlan=doc("old-plan",oldPlanValue);val p=clone(oldPlanValue).put("startsAtEpochMs",999000L).put("expiresAtEpochMs",1060000L).put("appSha256",sha("synthetic-reviewed-canonical-app"))
    val a=clone(base.authority).put("kind",Q04KnownNativeFailureRecoveryAdmission.AUTHORITY_KIND).put("existingProfileRef",oldValue.getJSONObject("existingProfileRef"))
    val current=clone(a.getJSONObject("preNativeRecovery").getJSONObject("currentAdmission"));a.remove("preNativeRecovery")
    current.remove("noFirstNativeIntent");current.remove("noNativePopen")
    val priorAuthority=doc("previous-recovery-authority",base.authority).ref
    val priorPlan=doc("previous-recovery-plan",base.plan).ref
    val priorIntent=doc("previous-recovery-intent",JSONObject().put("synthetic",true).put("authorityRef",priorAuthority)).ref
    val first=doc("first-native-marker",JSONObject().put("synthetic",true).put("authorityRef",priorAuthority)).ref
    current.put("firstNativeMarkerExists",true).put("firstNativeMarkerRef",first).put("knownRemainingNativePopenCount",1).put("unknownNativeOutcomeCount",0).put("secondNativeMarkerAbsent",true)
    val facts=JSONObject().put("kind",Q04KnownNativeFailureRecoveryAdmission.FACT_KIND).put("rootConfirmed",true)
        .put("taskId",p.get("taskId")).put("executionAuthorized",false).put("recoveryAuthorized",false).put("freshAuthorized",false).put("replayAllowed",false)
    for(k in listOf("run","leaseId","target","threadId","budgetOperationId"))facts.put(k,a.get(k))
    facts.put("originalReservationRef",once.ref).put("originalAuthorityRef",old.ref).put("originalPlanRef",oldPlan.ref).put("originalClaimRef",a.get("originalClaimRef"))
    val links=JSONObject().put("attemptKind",Q04KnownNativeFailureRecoveryAdmission.ATTEMPT_KIND).put("attemptNumber",2)
        .put("originalReservationRef",once.ref).put("originalAuthorityRef",old.ref).put("originalPlanRef",oldPlan.ref)
    for((k,v)in listOf("firstRecoveryAuthorityRef" to priorAuthority,"firstRecoveryIntentRef" to priorIntent,"firstNativeIntentRef" to first)) {facts.put(k,v);links.put(k,v)}
    links.put("firstRecoveryPlanRef",priorPlan).put("firstNativeManifestRef",raw)
    facts.put("nativeAttempt",JSONObject().put("clientPopen",1).put("methods",1).put("failures",1).put("unknown",0)
        .put("JNI",JSONArray(listOf(1,-2))).put("final",JSONArray(listOf(-1)))
        .put("exactMethod","com.agenthub.android.MobileGenerationCommandRealDevR2Test#q04ContinueRemainingControlsOnExistingOwnedProfileOnce")
        .put("failureSite","Q04ExistingOwnedProfile.directory:57 via admit:107")
        .put("beforeOwnedProfileFileLock",true).put("beforeOwnedProfileKeyStore",true).put("beforeInspectSessionActivityAndBusiness",true))
    val counts=JSONObject();for(k in listOf("fourRemainingBodySubmissions","newIndependentTurns","newChildStarts","queueCancelOrExecuteOrSteerOrStopFromThisAttempt"))counts.put(k,0)
    facts.put("knownBusinessZero",counts).put("originalCompletedTurns",current.getJSONArray("turnIds")).put("originalChildOnly",current.getJSONArray("childIds"))
    for(k in listOf("firstNativeResultRef","nativeRawJNIRef","controllerExitRef","afterFailureResourceProofRef","afterFailureBusinessAuditRef","rootIndependentReviewRef","officialQASnapshotRef","historicalPreFirstNativeZeroProofRef"))facts.put(k,raw)
    for(k in listOf("original7eccCompleted","existingProfile11Unchanged","hostBindings12Unchanged","appOps37Restored","oldFilesMustRemainImmutable"))facts.put(k,true)
    for(k in listOf("aliasKeyUsabilityVerified","uniquePathCanonicalCauseVerified","currentAdmissionOrSecondNativeAllowed"))facts.put(k,false)
    val factDoc=doc("failure-proof",facts)
    val release=JSONObject().put("kind","root-q04-first-native-attempt-release-v1").put("run",a.get("run")).put("leaseId",a.get("leaseId"))
        .put("executionOperationReleased",true).put("continuationExecutionAuthorized",false).put("RootFailureFactsRef",factDoc.ref)
    for(k in listOf("firstNativeMarkerRetained","originalReservationRetained","controllerExited","pumpJoined"))release.put(k,true)
    links.put("firstAttemptReleaseRef",doc("release",release).ref).put("firstAttemptReleaseJson",release.toString())
    current.put("readOnlyProfileAdmissionPassed",true).put("readOnlyProfileAdmissionReviewRef",raw)
    val app=JSONObject().put("kind","root-reviewed-canonical-admission-app-change-v1").put("rootReviewed",true)
        .put("reviewedFix","Q04ExistingOwnedProfile-canonical-directory-source-v1").put("reviewRef",raw).put("sourcePatchRef",raw)
        .put("oldAppSha256",oldPlanValue.getString("appSha256")).put("newAppSha256",p.getString("appSha256"))
        .put("package","com.easyai.agenthub.android.acceptance").put("uid",10123).put("api",33).put("signerSha256",p.getString("signerSha256"))
        .put("installationAndroidIdSha256",p.getString("installationAndroidIdSha256")).put("currentNativeManifestRef",a.getJSONObject("nativeManifestRef")).put("failedNativeManifestRef",raw)
    links.put("failureProofRef",factDoc.ref).put("failureProofJson",facts.toString()).put("appFingerprintChange",app).put("currentAdmission",current)
    a.put("knownNativeFailureRecovery",links)
    return Q04KnownFailureFixture(a,p,profile,base.budget,once,old,oldPlan)
}
