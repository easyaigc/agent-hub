package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import com.agenthub.core.TargetScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** A task-owned installation using the actual Main lifecycle, Session, providers and UI.
 * nativeRuntime=false excludes system integrations; no fixture ports or replacement UI.
 * No Intent, receiver or external service can prepare a profile.
 */
class Q02RealReadActivity : MainActivity() {
    internal lateinit var profile: Q02RealReadInstallation.Profile; private set

    override fun attachBaseContext(base: Context) {
        profile=Q02RealReadInstallation.claim(base)
        // Natural-entry admission sees this private installation too, never the ordinary root.
        super.attachBaseContext(profile.activityContext)
    }
    override fun runtimeContext(): Context=profile.runtimeContext
    override fun createInitialSession(context: Context): InitialSession {
        profile.checkCurrent()
        check(context===profile.runtimeContext&&applicationContext===profile.activityContext)
        check(context!==applicationContext)
        check(profile.storage==null&&profile.session==null)
        val storage=profile.existing?.openWriter(context) ?: NativeSessionStorage(context,"q02-session",profile.alias)
        profile.storage=storage
        profile.existing?.verifyWriteFences(storage,context)
        val actual=MobileSession(storage)
        profile.freshQ04ReplyDispatchFence?.let {actual.bindFreshQ04ReplyDispatchFence(storage,it)}
        profile.existing?.bindSession(actual)
        profile.session=actual
        return InitialSession(actual,needsRestore=true,nativeRuntime=false)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        profile.checkCurrent()
        check(savedInstanceState==null&&lastCustomNonConfigurationInstance==null)
        check(intent.action==Intent.ACTION_MAIN&&intent.data==null&&intent.clipData==null&&intent.selector==null&&intent.extras==null)
        check(intent.component?.className==javaClass.name)
        super.onCreate(null)
        check(session===profile.session&&session.discovery==null&&session.easyWorkDiscovery==null&&session.chatNotifications==null)
        profile.created=true
    }
    override fun onNewIntent(intent: Intent) { error("This finite read lease does not accept new intents") }
    @Suppress("DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any? = error("Recreation needs a new explicit lease")
    override fun onDestroy() {
        try {super.onDestroy()} finally {profile.destroyed=true}
    }
}

/** Process-local, once-only launch. No original storage reader, token import or generic command API. */
internal object Q02RealReadInstallation {
    data class Permit(val run: String,val leaseId: String,val deviceSha256: String,val appSha256: String,
        val runnerSha256: String,val signerSha256: String,val api: Int,val expiresAtElapsed: Long,
        val controlHostId: String,val localMappingSha256: String,val target: TargetScope,val endpoint: String,val allowedEndpoints: List<String>,val certificateSha256: String,
        val pairingIntentSha256: String,val desktopPublicKeySha256: String?)
    /** Finite successor opt-in; only the new Root old-to-new known-zero decision can construct it. */
    internal class KnownZeroSuccessorPermit private constructor(val run: String,val lease: String,val target: TargetScope,
        val mappingSha: String,val appSha: String,val runnerSha: String,val pairingSha: String) {
        companion object {
            private const val NEW_RUN="ba98e9cc-237a-48bf-8aea-78987d62b2f2";private const val NEW_LEASE="d7d94a63-12ee-4224-829c-5a0f8278fdc9"
            private const val OLD_RUN="d5d1d682-2b22-44e5-89a8-bc37f643384b";private const val BUDGET="b7253462-e867-4f09-8518-429e8d620e8b";private const val NONCE="6f6ab4fcb4874c89"
            private fun sha(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
            private fun same(a: Any,b: Any)=check(easyCanonical(a)==easyCanonical(b))
            private fun inline(value: JSONObject,name: String): JSONObject {
                val r=value.getJSONObject(name+"Ref");check(r.keys().asSequence().toSet()==setOf("path","bytes","sha256"))
                val text=value.getString(name+"Json");check(text.toByteArray(Charsets.UTF_8).size.toLong()==r.getLong("bytes")&&sha(text)==r.getString("sha256"))
                check(r.getString("path").startsWith("/"));return JSONObject(text)
            }
            private data class RecoveryIdentity(val run: String,val lease: String,val predecessor: String,val nonce: String,val postBa: Boolean=false)
            private fun recoveryIdentity(run: String): RecoveryIdentity = when(run) {
                NEW_RUN -> RecoveryIdentity(NEW_RUN,NEW_LEASE,OLD_RUN,NONCE)
                "82e905f8-a59a-4306-8d6c-564965a27538" -> RecoveryIdentity("82e905f8-a59a-4306-8d6c-564965a27538",
                    "6b532023-1a15-475c-8665-1f0457c3e127","ba98e9cc-237a-48bf-8aea-78987d62b2f2","17c6861ae795865d",true)
                else -> error("Unapproved finite successor case")
            }
            internal fun fromRoot(plan: JSONObject,a: JSONObject,nowUpper: Long): KnownZeroSuccessorPermit {
                val recoveryCase=recoveryIdentity(plan.getString("run"))
                check(plan.opt("enabled")==true&&plan.opt("executionAuthorized")==true&&plan.getString("caseKind")== (if(recoveryCase.postBa)"q04-post-ba-known-zero-successor-v1" else "q04-known-zero-business-successor-v1"))
                check(a.getString("kind")== (if(recoveryCase.postBa)"root-q04-post-ba-known-zero-successor-authority-v1" else "root-q04-known-zero-business-successor-authority-v1")&&a.opt("rootConfirmed")==true&&a.opt("executionAuthorized")==true&&a.opt("preparedOnly")==false)
                for(v in listOf(plan,a))check(v.getString("run")==recoveryCase.run&&v.getString("profileRun")==recoveryCase.run&&v.getString("leaseId")==recoveryCase.lease&&v.getString("budgetOperationId")==BUDGET&&v.getString("nonce")==recoveryCase.nonce)
                val core=JSONObject(plan.toString()).also {it.remove("rootAuthoritySha256")};val coreText=a.getString("planCoreJson")
                check(sha(coreText)==a.getString("planCoreSha256"));same(JSONObject(coreText),core)
                check(plan.opt("temporarySinglePermissionDelegationApproved")==true&&a.opt("temporarySinglePermissionDelegationApproved")==true)
                check(plan.getString("temporaryPermissionName")=="android.permission.START_ACTIVITIES_FROM_BACKGROUND"&&a.getString("temporaryPermissionName")==plan.getString("temporaryPermissionName"))
                val r=inline(a,"knownZeroRecovery");same(a.getJSONObject("knownZeroRecoveryRef"),plan.getJSONObject("knownZeroRecoveryRef"));check(a.getString("knownZeroRecoveryJson")==plan.getString("knownZeroRecoveryJson"))
                val recoveryKeys=setOf("version","kind","rootReviewed","recoveryAuthorized","executionAuthorized","preparedOnly","predecessorRun","successorRun","successorLeaseId","successorNonce","budgetOperationId","humanBudgetSha256","target","threadId","maxIndependentOrigins","maxActualTurns","maxAutomaticQueueFollowups","maxNewChildStarts","newCaseNativeAttempts","predecessorEdgeLeaf","predecessorEdgeAppendAuthorized","budgetBeforeRef","budgetBeforeJson","knownZeroReviewRef","knownZeroReviewJson","quiescenceReviewRef","quiescenceReviewJson","startupSuccessReviewRef","startupSuccessReviewJson","predecessorModelDirectoryAbsent","predecessorModelRemovalReviewRef")
                check(r.keys().asSequence().toSet()==if(recoveryCase.postBa)recoveryKeys+"originalBudgetOwnerRun" else recoveryKeys)
                check(r.getInt("version")==1&&r.getString("kind")== (if(recoveryCase.postBa)"root-q04-post-ba-known-zero-business-successor-recovery-v1" else "root-q04-known-zero-business-successor-recovery-v1")&&r.opt("rootReviewed")==true&&r.opt("recoveryAuthorized")==true&&r.opt("executionAuthorized")==false&&r.opt("preparedOnly")==false)
                check(r.getString("predecessorRun")==recoveryCase.predecessor&&r.getString("successorRun")==recoveryCase.run&&r.getString("successorLeaseId")==recoveryCase.lease&&r.getString("successorNonce")==recoveryCase.nonce&&r.getString("budgetOperationId")==BUDGET)
                check(r.getString("humanBudgetSha256")=="c53b308c61cdcb95ec942b5a288e0a82ff105f5185fbb0269cb00472d29b4172")
                same(r.getJSONObject("target"),plan.getJSONObject("target"));same(a.getJSONObject("target"),plan.getJSONObject("target"));check(r.getString("threadId")=="01a1d0bb-e7b3-7c51-92d9-98ab96329fb7"&&plan.getString("threadId")==r.getString("threadId"))
                check(r.getInt("maxIndependentOrigins")==1&&r.getInt("maxActualTurns")==2&&r.getInt("maxAutomaticQueueFollowups")==1&&r.getInt("maxNewChildStarts")==0&&r.getInt("newCaseNativeAttempts")==1&&a.getInt("maxNativeAttempts")==1)
                check(r.getString("predecessorEdgeLeaf")=="successor-edge.json"&&r.opt("predecessorEdgeAppendAuthorized")==true&&r.opt("predecessorModelDirectoryAbsent")==true)
                val removed=r.getJSONObject("predecessorModelRemovalReviewRef");check(removed.keys().asSequence().toSet()==setOf("path","bytes","sha256"))
                check(removed.getString("path").startsWith("/")&&removed.getLong("bytes")>0&&removed.getString("sha256").matches(Regex("[a-f0-9]{64}")))
                val before=inline(r,"budgetBefore");check(before.getString("predecessorRun")==recoveryCase.predecessor&&before.getString("budgetOperationId")==BUDGET&&before.opt("globalOriginProofAbsent")==true&&before.getInt("eventCount")==0)
                if(recoveryCase.postBa) {
                    check(r.getString("originalBudgetOwnerRun")==OLD_RUN&&before.getString("originalBudgetOwnerRun")==OLD_RUN)
                    for(name in listOf("originalLedgerSourceRef","originalSuccessorEdgeRef")) {
                        val anchor=before.getJSONObject(name)
                        check(anchor.keys().asSequence().toSet()==setOf("path","bytes","sha256"))
                        check(anchor.getString("path").startsWith("/")&&anchor.getLong("bytes")>0&&anchor.getString("sha256").matches(Regex("[a-f0-9]{64}")))
                    }
                }
                val ledger=before.getJSONObject("ledger");check(ledger.getString("budgetOperationId")==BUDGET&&ledger.getInt("maxIndependentOrigins")==1&&ledger.getInt("maxActualTurns")==2&&ledger.get("acceptedOrigin")===JSONObject.NULL&&ledger.opt("originBlocked")==false&&ledger.getJSONArray("turns").length()==0&&ledger.getJSONArray("unknownOriginIds").length()==0)
                val zero=inline(r,"knownZeroReview");val quiet=inline(r,"quiescenceReview");val startup=inline(r,"startupSuccessReview")
                for(v in listOf(zero,quiet,startup)) {check(v.opt("rootReviewed")==true&&v.getString("budgetOperationId")==BUDGET&&v.getString("threadId")==r.getString("threadId"));same(v.getJSONObject("target"),r.getJSONObject("target"))}
                check(zero.getString("kind")=="root-q04-predecessor-known-zero-budget-review-v1"&&zero.getString("predecessorRun")==recoveryCase.predecessor&&zero.opt("officialTypedJoinComplete")==true&&zero.getInt("newActualUserInputs")==0&&zero.getInt("newActualTurns")==0&&zero.getInt("newChildStarts")==0&&zero.getInt("unknownOperations")==0&&zero.getInt("newBindings")==0)
                check(quiet.getString("kind")=="root-q04-predecessor-write-quiescence-review-v1"&&quiet.getString("predecessorRun")==recoveryCase.predecessor)
                for(k in listOf("oldOwnerExited","oldNodeExited","oldAdbExited","oldAndroidPidAbsent","noOldUidWriter","oldAbortRetained","budgetLockAvailable"))check(quiet.opt(k)==true)
                check(quiet.getInt("acceptedPendingQueues")==0&&quiet.getInt("unknownOperations")==0)
                val observed=quiet.getLong("observedAtEpochMs");check(nowUpper>=observed&&nowUpper-observed<=30000)
                check(startup.getString("kind")=="root-q04-no-model-startup-success-review-v1"&&startup.getString("diagnosticRun")=="1a587a56-f862-45ee-bd74-042d84f80a37")
                for(k in listOf("realResumed","realDestroyed","writerCloseReturned","noHostOperations","noModels","specifiedPermissionRestored","callbackUnregistered","startWorkerExited","closeWorkerExited","sourceMechanismAssociationReviewed"))check(startup.opt(k)==true)
                return KnownZeroSuccessorPermit(recoveryCase.run,recoveryCase.lease,checkNotNull(HostReadCodec.target(plan.getJSONObject("target"))),plan.getString("localMappingSha256"),plan.getString("appSha256"),plan.getString("runnerSha256"),plan.getString("pairingIntentSha256"))
            }
        }
    }
    /** A distinct human-approved budget; spent predecessor facts never provide fresh credit. */
    internal class IndependentBudgetPermit private constructor(val run: String,val lease: String,val target: TargetScope,
        val mappingSha: String,val appSha: String,val runnerSha: String,val pairingSha: String) {
        companion object {
            private const val RUN="b96290a9-db93-4371-ba76-730dff5acddb"
            private const val LEASE="9752c6f6-66ba-41f0-8932-a81bfdfc38de"
            private const val BUDGET="968cdb30-1c47-429d-af92-1f60dc156829"
            private const val NONCE="978504734a763fd9"
            private const val OLD_BUDGET="b7253462-e867-4f09-8518-429e8d620e8b"
            private const val HUMAN_SHA="eba0d7ed8c6470ca6c4d450458a836cce698eb425e02738a7daa6d90d87afa79"
            private const val THREAD="01a1d0bb-e7b3-7c51-92d9-98ab96329fb7"
            private fun exact(v: JSONObject,want: Set<String>)=check(v.keys().asSequence().toSet()==want)
            private fun n(v: JSONObject,k: String): Long {val x=v.get(k);check(x is Int||x is Long);return (x as Number).toLong()}
            private fun sha(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
            private fun same(a: Any,b: Any)=check(easyCanonical(a)==easyCanonical(b))
            private fun inline(v: JSONObject,name: String): JSONObject {
                val r=v.getJSONObject(name+"Ref");exact(r,setOf("path","bytes","sha256"))
                val text=v.get(name+"Json");check(text is String)
                check(r.getString("path").startsWith("/")&&n(r,"bytes")>0&&r.getString("sha256").matches(Regex("[a-f0-9]{64}")))
                check(text.toByteArray(Charsets.UTF_8).size.toLong()==n(r,"bytes")&&sha(text)==r.getString("sha256"))
                return JSONObject(text)
            }
            private fun target(v: JSONObject) {
                exact(v,setOf("agentId","hostId","workspaceId","runtimeId"))
                check(v.get("agentId")=="codex"&&v.get("hostId")=="13c81542-48e3-4df2-ab46-d45357b631a2"&&v.get("workspaceId")==v.get("hostId")&&v.get("runtimeId")=="desktop-runtime")
            }
            private fun identity(v: JSONObject) {
                check(v.get("run")==RUN&&v.get("profileRun")==RUN&&v.get("leaseId")==LEASE&&v.get("budgetOperationId")==BUDGET&&v.get("nonce")==NONCE)
                target(v.getJSONObject("target"));check(v.get("threadId")==THREAD)
            }
            internal fun fromRoot(plan: JSONObject,a: JSONObject,nowUpper: Long): IndependentBudgetPermit {
                check(plan.get("caseKind")=="q04-new-independent-budget-v1"&&plan.opt("enabled")==true&&plan.opt("executionAuthorized")==true)
                check(n(a,"version")==1L&&a.get("kind")=="root-q04-new-independent-budget-authority-v1"&&a.opt("rootConfirmed")==true&&a.opt("executionAuthorized")==true&&a.opt("preparedOnly")==false)
                identity(plan);identity(a)
                for(k in listOf("freshProfileAuthorized","newBindingAuthorized","modelBudgetApproved","businessChatAuthorized","nativeAiSharingApproved"))check(a.opt(k)==true)
                check(a.opt("oldCaseMutationAuthorized")==false)
                val bounds=mapOf("maxNewIndependentOriginTurns" to 1L,"maxNewActualTurns" to 2L,"maxAutomaticQueueFollowups" to 1L,"maxNewChildStarts" to 0L,"maximumPendingTestQueueItems" to 1L,"terminalWindowMs" to 45000L,"maxOriginalSessionPolls" to 1L,"watchdogToolAgeSeconds" to 25L,"maxTextSubmissions" to 4L)
                for(v in listOf(plan,a))for((k,want)in bounds)check(n(v,k)==want)
                check(n(plan,"maxChildStarts")==0L&&n(plan,"maxToolSeconds")==40L&&n(a,"maxOriginalToolProcessSeconds")==40L)
                val settings=plan.getJSONObject("settings");exact(settings,setOf("model","effort","permissionMode"))
                check(settings.get("model")=="gpt-6.1-sol"&&settings.get("effort")=="xhigh"&&settings.get("permissionMode")=="full-access")
                val forbidden=listOf("continuation","postSecondKnownZero","existingOwnedProfile","knownZeroRecoveryRef","knownZeroRecoveryJson","nativeSpeedSelection","speed","serviceTier","service_tier")
                for(v in listOf(plan,a))check(forbidden.none {v.has(it)})
                val core=JSONObject(plan.toString()).also {it.remove("rootAuthoritySha256")};val coreText=a.getString("planCoreJson")
                check(sha(coreText)==a.get("planCoreSha256"));same(JSONObject(coreText),core)
                for(v in listOf(plan,a))check(v.opt("temporarySinglePermissionDelegationApproved")==true&&v.get("temporaryPermissionName")=="android.permission.START_ACTIVITIES_FROM_BACKGROUND")
                val human=inline(a,"humanBudget")
                check(a.getJSONObject("humanBudgetRef").get("sha256")==HUMAN_SHA&&n(a.getJSONObject("humanBudgetRef"),"bytes")==2343L)
                check(human.get("kind")=="human-new-independent-q04-case-budget-authorized-v1"&&human.opt("humanApproved")==true&&human.opt("executionAuthorized")==false&&human.opt("thisFileIsExecutionSeal")==false)
                check(human.get("caseRun")==RUN&&human.get("profileRun")==RUN&&human.get("leaseId")==LEASE&&human.get("budgetOperationId")==BUDGET&&human.get("nonce")==NONCE)
                target(human.getJSONObject("target"));check(human.get("threadId")==THREAD)
                check(human.get("previousBudgetOperationId")==OLD_BUDGET&&human.opt("previousUniqueOriginConsumed")==true)
                for(k in listOf("initialAcceptedOrigins","initialActualTurns","initialModelChildren"))check(n(human,k)==0L)
                check(human.opt("newBodiesAndIdsRequired")==true&&human.opt("oldCasesAndMarkersImmutable")==true&&human.opt("localDeveloperHostDispatchAuthorized")==false&&human.opt("iPhoneProbeAuthorized")==false)
                val r=inline(a,"independentBudgetAdmission")
                same(a.getJSONObject("independentBudgetAdmissionRef"),plan.getJSONObject("independentBudgetAdmissionRef"));check(a.get("independentBudgetAdmissionJson")==plan.get("independentBudgetAdmissionJson"))
                exact(r,setOf("version","kind","rootReviewed","executionAuthorized","preparedOnly","run","profileRun","leaseId","nonce","budgetOperationId","humanBudgetSha256","target","threadId","maxIndependentOrigins","maxActualTurns","maxAutomaticQueueFollowups","maxNewChildStarts","maxNativeAttempts","observedAtEpochMs","newBudgetBeforeRef","newBudgetBeforeJson","previousBudgetSpentRef","previousBudgetSpentJson","quiescenceReviewRef","quiescenceReviewJson","startupSuccessReviewRef","startupSuccessReviewJson"))
                identity(r)
                check(n(r,"version")==1L&&r.get("kind")=="root-q04-new-independent-budget-admission-v1"&&r.opt("rootReviewed")==true&&r.opt("executionAuthorized")==false&&r.opt("preparedOnly")==false&&r.get("humanBudgetSha256")==HUMAN_SHA)
                for((k,v) in mapOf("maxIndependentOrigins" to 1L,"maxActualTurns" to 2L,"maxAutomaticQueueFollowups" to 1L,"maxNewChildStarts" to 0L,"maxNativeAttempts" to 1L))check(n(r,k)==v)
                check(n(a,"maxNativeAttempts")==1L)
                val observed=n(r,"observedAtEpochMs");check(nowUpper>=observed&&nowUpper-observed<=30000L)
                val before=inline(r,"newBudgetBefore")
                exact(before,setOf("version","kind","rootReviewed","run","budgetOperationId","target","threadId","eventCount","globalOriginProofAbsent","ledger"))
                check(n(before,"version")==1L&&before.get("kind")=="root-q04-independent-budget-initial-ledger-v1"&&before.opt("rootReviewed")==true&&before.get("run")==RUN&&before.get("budgetOperationId")==BUDGET&&before.get("threadId")==THREAD)
                target(before.getJSONObject("target"));check(n(before,"eventCount")==0L&&before.opt("globalOriginProofAbsent")==true)
                val ledger=before.getJSONObject("ledger")
                exact(ledger,setOf("budgetOperationId","maxIndependentOrigins","maxActualTurns","acceptedOrigin","originBlocked","turns","unknownOriginIds"))
                check(ledger.get("budgetOperationId")==BUDGET&&n(ledger,"maxIndependentOrigins")==1L&&n(ledger,"maxActualTurns")==2L&&ledger.get("acceptedOrigin")===JSONObject.NULL&&ledger.opt("originBlocked")==false&&ledger.getJSONArray("turns").length()==0&&ledger.getJSONArray("unknownOriginIds").length()==0)
                val spent=inline(r,"previousBudgetSpent")
                exact(spent,setOf("version","kind","rootReviewed","previousBudgetOperationId","uniqueOriginConsumed","originOperationId","originTurnId","state","officialTypedJoinComplete"))
                check(n(spent,"version")==1L&&spent.get("kind")=="root-q04-previous-budget-spent-review-v1"&&spent.opt("rootReviewed")==true&&spent.get("previousBudgetOperationId")==OLD_BUDGET&&spent.opt("uniqueOriginConsumed")==true&&spent.opt("officialTypedJoinComplete")==true)
                check(spent.get("originOperationId")=="bd770b5a-4971-48bb-b60e-7284ad9456e2"&&spent.get("originTurnId")=="01a1de07-d92c-7f12-92c6-a32c0ccdf3ba"&&spent.get("state")=="interrupted")
                val quiet=inline(r,"quiescenceReview")
                exact(quiet,setOf("version","kind","rootReviewed","budgetOperationId","target","threadId","observedAtEpochMs","ownerIdle","noActiveOriginalTurn","acceptedPendingQueues","unknownOperations","oldBudgetMarkersRetained","newProfileAbsent","nativeAliasAbsenceGateReviewed","budgetLockAvailable"))
                check(n(quiet,"version")==1L&&quiet.get("kind")=="root-q04-independent-budget-quiescence-review-v1"&&quiet.opt("rootReviewed")==true&&quiet.get("budgetOperationId")==BUDGET&&quiet.get("threadId")==THREAD)
                target(quiet.getJSONObject("target"))
                for(k in listOf("ownerIdle","noActiveOriginalTurn","oldBudgetMarkersRetained","newProfileAbsent","nativeAliasAbsenceGateReviewed","budgetLockAvailable"))check(quiet.opt(k)==true)
                check(n(quiet,"acceptedPendingQueues")==0L&&n(quiet,"unknownOperations")==0L)
                val quietAt=n(quiet,"observedAtEpochMs");check(nowUpper>=quietAt&&nowUpper-quietAt<=30000L)
                val startup=inline(r,"startupSuccessReview")
                check(startup.get("kind")=="root-q04-no-model-startup-success-review-v1"&&startup.get("diagnosticRun")=="1a587a56-f862-45ee-bd74-042d84f80a37"&&startup.opt("rootReviewed")==true&&startup.get("budgetOperationId")==OLD_BUDGET&&startup.get("threadId")==THREAD)
                target(startup.getJSONObject("target"))
                for(k in listOf("realResumed","realDestroyed","writerCloseReturned","noHostOperations","noModels","specifiedPermissionRestored","callbackUnregistered","startWorkerExited","closeWorkerExited","sourceMechanismAssociationReviewed"))check(startup.opt(k)==true)
                return IndependentBudgetPermit(RUN,LEASE,checkNotNull(HostReadCodec.target(plan.getJSONObject("target"))),plan.getString("localMappingSha256"),plan.getString("appSha256"),plan.getString("runnerSha256"),plan.getString("pairingIntentSha256"))
            }
        }
    }
    class Profile internal constructor(val permit: Permit,val root: File,actual: Context,
        val existing: Q04ExistingOwnedProfile?=null) {
        val storageOwnerRun=existing?.ownerRun ?: permit.run
        val alias="q02-real-"+storageOwnerRun
        // Distinct objects satisfy Main's existing private-installation contract. Both roots
        // are genuinely private; neither object forwards storage to the ordinary installation.
        val activityContext=FilesContext(actual,root)
        val runtimeContext=FilesContext(actual,root)
        var storage: NativeSessionStorage?=null;internal set
        var session: MobileSession?=null;internal set
        var claimed=false;internal set
        var created=false;internal set
        var destroyed=false;internal set
        var pairingAttempted=false;internal set
        var closed=false;internal set
        internal var sealedNewBindingIntent: JSONObject?=null
        internal var freshQ04ReplyDispatchFence: ((NativeOriginalReplyDispatch)->Unit)?=null;private set
        internal var freshQ04BindingIntentObserver: ((JSONObject)->Unit)?=null;private set
        internal fun bindFreshQ04ReplyDispatchFence(checker: (NativeOriginalReplyDispatch)->Unit,
            bindingIntentObserver: (JSONObject)->Unit) {
            checkCurrent()
            check(permit.run=="d5d1d682-2b22-44e5-89a8-bc37f643384b" && existing==null)
            check(!claimed && !created && !pairingAttempted && storage==null && session==null)
            check(freshQ04ReplyDispatchFence==null&&freshQ04BindingIntentObserver==null)
            freshQ04ReplyDispatchFence=checker
            freshQ04BindingIntentObserver=bindingIntentObserver
        }
        internal fun bindKnownZeroSuccessorReplyDispatchFence(admission: KnownZeroSuccessorPermit,
            checker: (NativeOriginalReplyDispatch)->Unit,bindingIntentObserver: (JSONObject)->Unit) {
            checkCurrent();check(existing==null&&permit.run==admission.run&&permit.leaseId==admission.lease)
            check(sameScope(permit.target,admission.target)&&permit.localMappingSha256==admission.mappingSha&&permit.appSha256==admission.appSha&&permit.runnerSha256==admission.runnerSha&&permit.pairingIntentSha256==admission.pairingSha)
            check(!claimed&&!created&&!pairingAttempted&&storage==null&&session==null)
            check(freshQ04ReplyDispatchFence==null&&freshQ04BindingIntentObserver==null)
            freshQ04ReplyDispatchFence=checker;freshQ04BindingIntentObserver=bindingIntentObserver
        }
        internal fun bindIndependentBudgetReplyDispatchFence(admission: IndependentBudgetPermit,
            checker: (NativeOriginalReplyDispatch)->Unit,bindingIntentObserver: (JSONObject)->Unit) {
            checkCurrent();check(existing==null&&permit.run==admission.run&&permit.leaseId==admission.lease)
            check(sameScope(permit.target,admission.target)&&permit.localMappingSha256==admission.mappingSha&&permit.appSha256==admission.appSha&&permit.runnerSha256==admission.runnerSha&&permit.pairingIntentSha256==admission.pairingSha)
            check(!claimed&&!created&&!pairingAttempted&&storage==null&&session==null)
            check(freshQ04ReplyDispatchFence==null&&freshQ04BindingIntentObserver==null)
            freshQ04ReplyDispatchFence=checker;freshQ04BindingIntentObserver=bindingIntentObserver
        }
        fun checkCurrent() {
            check(current===this&&!closed&&SystemClock.elapsedRealtime()<permit.expiresAtElapsed)
            directory(root);existing?.assertProtected()
        }
        fun checkOnlyApprovedTarget(): MobileClient {
            checkCurrent()
            val value=checkNotNull(session)
            val client=value.clients.filterNot {it.isDemo}.single()
            checkApprovedClient(client,permit.target)
            check(value.selectedClient.id==client.id&&sameScope(value.selectedClient.scope,permit.target))
            check(value.discovery==null&&value.easyWorkDiscovery==null&&value.chatNotifications==null)
            return client
        }
        fun proof()=JSONObject().put("storageOwnerRun",storageOwnerRun).put("existingOwnedResume",existing!=null).put("run",permit.run).put("leaseId",permit.leaseId).put("root",root.path).put("alias",alias)
            .put("pid",android.os.Process.myPid()).put("processNonce",processNonce)
            .put("newBindingIntent",sealedNewBindingIntent).put("created",created).put("destroyed",destroyed).put("pairingAttempted",pairingAttempted).put("closed",closed)
            .put("controlHostId",permit.controlHostId).put("localMappingSha256",permit.localMappingSha256)
            .put("target",CodexCommandCodec.target(permit.target)).put("ordinaryStorageMounted",false).put("runtimeNoBackupRoot",runtimeContext.noBackupFilesDir.path)
            .put("activityNoBackupRoot",activityContext.noBackupFilesDir.path)
            .put("sessionBoundToOwnedStorage",storage!=null&&session?.migrationStorage===storage)
            .put("controlHostMappingExternal",true).put("fixturePortsInstalled",false).put("nativeSystemIntegrations",false)
    }
    private val processNonce=UUID.randomUUID().toString()
    private var prepared=false
    private var current: Profile?=null
    private var attemptedRoot: File?=null
    fun preparationProof()=JSONObject().put("preparedAttempt",prepared).put("attemptedOwnedRoot",attemptedRoot?.path).put("profile",current?.proof())
    private fun main()=check(android.os.Looper.myLooper()===android.os.Looper.getMainLooper())
    private fun uuid(value: String)=check(UUID.fromString(value).toString()==value)
    private fun checkApprovedClient(client: MobileClient,target: TargetScope) {
        check(sameScope(client.scope,target))
        val expectedId=when(target.agentId) {
            "codex"->target.hostId
            "easywork"->"easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(target)).toByteArray(Charsets.UTF_8))
            else->error("Unsupported authorized Agent")
        }
        check(client.id==expectedId)
    }
    private fun hash(value: String)=check(value.matches(Regex("[a-f0-9]{64}")))
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun fileDigest(file: File): String {
        val sha=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {stream->val bytes=ByteArray(65536);while(true){val n=stream.read(bytes);if(n<0)break;sha.update(bytes,0,n)}}
        return sha.digest().joinToString(""){"%02x".format(it)}
    }
    private fun directory(file: File) {
        check(Files.isDirectory(file.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file.toPath()))
        val stat=android.system.Os.lstat(file.path)
        check(stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0x1ff==0x1c0&&file.canonicalFile==file.absoluteFile)
    }
    private fun mkdir(file: File) {
        Files.createDirectory(file.toPath());android.system.Os.chmod(file.path,0x1c0);directory(file)
    }
    private fun validateInstallation(context: Context,permit: Permit) {
        check(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance")
        check(context.applicationInfo.uid==android.os.Process.myUid()&&context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        uuid(permit.run);uuid(permit.leaseId);uuid(permit.controlHostId)
        for(value in listOf(permit.deviceSha256,permit.appSha256,permit.runnerSha256,permit.signerSha256,permit.certificateSha256,permit.pairingIntentSha256,permit.localMappingSha256))hash(value)
        check(permit.api==android.os.Build.VERSION.SDK_INT&&permit.api>=28)
        val now=SystemClock.elapsedRealtime();check(permit.expiresAtElapsed in now+60_000..now+1_800_000)
        check(digest(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID).toByteArray())==permit.deviceSha256)
        check(permit.target.agentId in listOf("codex","easywork"))
        if(permit.target.agentId=="codex") {
            // A task-owned DEV Host has its own identity; the controller mapping is frozen separately.
            uuid(permit.target.hostId)
            check(permit.target.workspaceId==permit.target.hostId&&permit.target.runtimeId=="desktop-runtime")
        } else hash(checkNotNull(permit.desktopPublicKeySha256))
        check(com.agenthub.core.DomainContract.validate(CodexCommandCodec.target(permit.target).toString(),"target")==null)
        check(CodexEndpoints.local(permit.endpoint)==permit.endpoint)
        check(permit.allowedEndpoints.size in 1..32&&permit.allowedEndpoints.distinct().size==permit.allowedEndpoints.size)
        check(permit.endpoint in permit.allowedEndpoints&&permit.allowedEndpoints.all {CodexEndpoints.local(it)==it})
        check(fileDigest(File(context.applicationInfo.sourceDir))==permit.appSha256)
        val runner=context.packageManager.getApplicationInfo(context.packageName+".test",0)
        check(fileDigest(File(runner.sourceDir))==permit.runnerSha256)
        for(name in listOf(context.packageName,context.packageName+".test")) {
            val info=context.packageManager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES)
            val signatures=checkNotNull(info.signingInfo).apkContentsSigners
            check(signatures.size==1&&digest(signatures.single().toByteArray())==permit.signerSha256)
        }
    }
    fun prepare(context: Context,permit: Permit): Profile {
        main();check(!prepared&&current==null);prepared=true // Failed/unknown preparation cannot be replayed in this process.
        validateInstallation(context,permit)
        val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        check(!keys.containsAlias("q02-real-"+permit.run))
        val parent=File(context.filesDir.canonicalFile,"q02-real-dev-profiles-v1")
        if(!Files.exists(parent.toPath(),NOFOLLOW_LINKS))mkdir(parent) else directory(parent)
        val root=File(parent,permit.run);check(Files.notExists(root.toPath(),NOFOLLOW_LINKS));attemptedRoot=root;mkdir(root)
        for(name in listOf("no-backup","files","cache","code-cache"))mkdir(File(root,name))
        return Profile(permit.copy(allowedEndpoints=permit.allowedEndpoints.toList()),root,context).also {current=it}
    }

    internal fun prepareExistingQ04(context: Context,permit: Permit,manifest: JSONObject,readingPermit: Q04ExistingOwnedReadingPermit?=null): Profile {
        main();check(!prepared&&current==null);prepared=true
        validateInstallation(context,permit)
        val existing=Q04ExistingOwnedProfile.admit(context,permit,manifest,readingPermit)
        try {
            existing.inspect(FilesContext(context,existing.root)) // Existing read-only decoder; no Session or ordinary storage.
            return Profile(permit.copy(allowedEndpoints=permit.allowedEndpoints.toList()),existing.root,context,existing).also {
                it.sealedNewBindingIntent=JSONObject(existing.protectedRecords.getValue("q02-new-binding-attempt-v1"))
                current=it
            }
        }catch(error: Throwable){try{existing.release()}catch(close: Throwable){error.addSuppressed(close)};throw error}
    }
    fun claim(context: Context): Profile {
        main();check(context.packageName=="com.easyai.agenthub.android.acceptance")
        return checkNotNull(current).also {it.checkCurrent();check(!it.claimed);it.claimed=true}
    }
    /** New device claim/binding only. The input must be freshly supplied by the controller;
     * it is never taken from any saved installation, hosts.json, or existing grant.
     */
    suspend fun pairFreshTarget(profile: Profile,input: String): MobileClient {
        main();profile.checkCurrent();check(profile.existing==null&&profile.created&&!profile.pairingAttempted)
        val value=checkNotNull(profile.session)
        check(!value.restoring&&value.clients.none { !it.isDemo }&&value.operationRecords.isEmpty())
        check(digest(input.toByteArray(Charsets.UTF_8))==profile.permit.pairingIntentSha256)
        val permit=profile.permit
        if(permit.target.agentId=="easywork") {
            // A LAN offer exposes the complete target before the first binding request.
            // Opaque relay offers need a separately reviewed source of target authorization.
            val offer=EasyWorkLANOffer.parse(input)
            check(sameScope(offer.host.target.scope(),permit.target)&&offer.host.fingerprint==permit.certificateSha256)
            check(digest(easyBytes(offer.host.publicKey))==permit.desktopPublicKeySha256)
            check(offer.value.optString("connectionMode")=="automatic"||offer.host.endpoints.size==1) {
                "A manual multi-route LAN offer can replay an uncertain claim; request a single-route official offer"
            }
            check(offer.host.endpoints.isNotEmpty()&&offer.host.endpoints.all {CodexEndpoints.local(it) in permit.allowedEndpoints})
        } else check(input.isEmpty()||input.length in 32..512&&input.none(Char::isWhitespace))
        profile.pairingAttempted=true // Preserve an uncertain attempt; never retry the new claim automatically.
        val storage=checkNotNull(profile.storage)
        val identity=EasyWorkIdentity.load(storage) // Real factory, new owned namespace/alias only.
        val publicProof=JSONObject().put("target",CodexCommandCodec.target(permit.target))
            .put("mobileDeviceId",identity.deviceId).put("mobilePublicKeySha256",digest(easyBytes(identity.publicKey)))
            .put("pairingIntentSha256",permit.pairingIntentSha256).put("attemptedOnce",true)
        if(permit.target.agentId=="easywork") {
            val offer=EasyWorkLANOffer.parse(input)
            publicProof.put("offerId",offer.value.optString("offerId")).put("remoteLookupKey","offerId + mobileDeviceId/publicKey")
        } else publicProof.put("remoteLookupKey","Codex privateClaim target + publicKey; original stored intentId")
        profile.sealedNewBindingIntent=publicProof
        profile.freshQ04BindingIntentObserver?.let {observer ->
            val snapshot=profile.proof() // Main captures only this owned profile's existing public facts.
            withContext(Dispatchers.IO){observer(snapshot)} // Publication failure propagates before the only HTTP claim.
        }
        withContext(Dispatchers.IO){storage.write("q02-new-binding-attempt-v1",publicProof.toString())}
        val client=if(permit.target.agentId=="codex") {
            value.connectCodexDiscovered(DiscoveredCodexHost(permit.target.hostId,"Q02 owned Codex",permit.endpoint,false,false,
                target=permit.target,fingerprint=permit.certificateSha256),input)
        } else value.connectEasyWorkQR(input,"Q02 owned EasyWork")
        checkApprovedClient(client,permit.target)
        profile.checkOnlyApprovedTarget()
        return client
    }
    /** The caller closes the Activity first; use the existing Session cancellation barrier,
     * then drain its own writer. No migration operation or original installation is opened.
     */
    suspend fun close(profile: Profile) {
        main();check(current===profile&&!profile.closed&&profile.destroyed)
        try {
            profile.session?.abandonMigrationRestore()
            profile.storage?.awaitQueuedWrites()
        } finally {
            profile.storage?.retireWriter()
            try {profile.existing?.release()}finally {profile.closed=true}
        }
        // Keep the owned profile/new binding until controller evidence/revocation review.
    }

    /** File paths always stay under the new profile. Android UI/services are real;
     * unsupported storage APIs fail instead of falling back to the ordinary installation.
     */
    class FilesContext internal constructor(actual: Context,private val root: File): ContextWrapper(actual) {
        override fun getApplicationContext(): Context=this
        override fun getApplicationInfo(): android.content.pm.ApplicationInfo =
            android.content.pm.ApplicationInfo(super.getApplicationInfo()).apply {
                // Keep the real package/UID/APK/signing identity. Every advertised data path
                // describes this owned installation; none points at the ordinary data root.
                dataDir=root.path
                deviceProtectedDataDir=File(root,"device-protected-disabled").path
            }
        private fun child(name: String)=File(root,name).also {directory(it)}
        override fun getDataDir()=root
        override fun getNoBackupFilesDir()=child("no-backup")
        override fun getFilesDir()=child("files")
        override fun getCacheDir()=child("cache")
        override fun getCodeCacheDir()=child("code-cache")
        override fun createConfigurationContext(configuration: Configuration): Context=FilesContext(baseContext.createConfigurationContext(configuration),root)
        override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences=error("SharedPreferences outside this owned installation are not permitted")
        override fun deleteSharedPreferences(name: String): Boolean=error("SharedPreferences are outside this profile")
        override fun moveSharedPreferencesFrom(sourceContext: Context,name: String): Boolean=error("Profile imports are not permitted")
        override fun moveDatabaseFrom(sourceContext: Context,name: String): Boolean=error("Profile imports are not permitted")
        override fun getDatabasePath(name: String): File=error("Database access is outside this read surface")
        override fun databaseList(): Array<String> = error("Database enumeration is outside this profile")
        override fun deleteDatabase(name: String): Boolean=error("Database access is outside this profile")
        override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?): android.database.sqlite.SQLiteDatabase=error("Database access is outside this profile")
        override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,handler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase=error("Database access is outside this profile")
        override fun deleteFile(name: String): Boolean=error("Unregistered file removal")
        override fun fileList(): Array<String> = filesDir.list() ?: emptyArray()
        override fun openFileInput(name: String): java.io.FileInputStream=error("Use the production encrypted profile store")
        override fun openFileOutput(name: String,mode: Int): java.io.FileOutputStream=error("Use the production encrypted profile store")
        override fun getDir(name: String,mode: Int): File=error("Unregistered directory")
        override fun getExternalFilesDir(type: String?): File?=error("External storage is outside this read surface")
        override fun getExternalCacheDir(): File?=error("External storage is outside this read surface")
        override fun getExternalFilesDirs(type: String?): Array<File> = error("External storage is outside this profile")
        override fun getExternalCacheDirs(): Array<File> = error("External storage is outside this profile")
        override fun getExternalMediaDirs(): Array<File> = error("External storage is outside this profile")
        override fun getObbDir(): File=error("OBB storage is outside this profile")
        override fun getObbDirs(): Array<File> = error("OBB storage is outside this profile")
        override fun createDeviceProtectedStorageContext(): Context=error("Device-protected storage is outside this profile")
        override fun createPackageContext(packageName: String,flags: Int): Context=error("Other package storage is outside this profile")
    }
}
