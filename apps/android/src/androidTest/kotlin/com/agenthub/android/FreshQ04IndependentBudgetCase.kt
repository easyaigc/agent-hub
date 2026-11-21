package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Finite new human budget. The earlier interrupted Origin is a spent historical fact. */
internal object FreshQ04IndependentBudgetCase {
    const val RUN="b96290a9-db93-4371-ba76-730dff5acddb"
    const val LEASE="9752c6f6-66ba-41f0-8932-a81bfdfc38de"
    const val BUDGET="968cdb30-1c47-429d-af92-1f60dc156829"
    const val NONCE="978504734a763fd9"
    const val CASE_KIND="q04-new-independent-budget-v1"
    const val AUTHORITY_KIND="root-q04-new-independent-budget-authority-v1"
    private const val THREAD="01a1d0bb-e7b3-7c51-92d9-98ab96329fb7"
    private const val HUMAN_SHA="eba0d7ed8c6470ca6c4d450458a836cce698eb425e02738a7daa6d90d87afa79"
    // Observed completed runtime continuation: no business input and no Host receipt.
    private const val RESTORED_EMPTY_RESUME_TURN="01a1e33d-18a3-7dc3-9971-b40ceab31d67"
    private val target=JSONObject().put("agentId","codex").put("hostId","13c81542-48e3-4df2-ab46-d45357b631a2")
        .put("workspaceId","13c81542-48e3-4df2-ab46-d45357b631a2").put("runtimeId","desktop-runtime")
    private val slots=setOf("origin","queueCancel","queueExecute","steer")
    private val bounds=mapOf("maxNewIndependentOriginTurns" to 1L,"maxNewActualTurns" to 2L,
        "maxAutomaticQueueFollowups" to 1L,"maxNewChildStarts" to 0L,"maximumPendingTestQueueItems" to 1L,
        "terminalWindowMs" to 45000L,"maxOriginalSessionPolls" to 1L,"watchdogToolAgeSeconds" to 25L)
    private data class HistoricalInput(val turn: String,val operation: String,val textSha: String,
        val state: String="completed",val queueSource: String="host",val external: Boolean=false) {
        fun user()=JSONObject().put("turnId",turn).put("operationId",operation).put("textSha256",textSha)
        fun receipt()=user().put("state",state).put("queueSource",queueSource).put("queuePresent",false)
        fun turnState()=JSONObject().put("id",turn).put("status",state)
    }
    private val historical=listOf(
        HistoricalInput("01a1d2d9-63c5-7440-b6c6-226564f95e0a","fd39352d-f236-4672-96b6-fd5b03174653","4cfb5ae23b419f504fd906bbc91138b72691aa71a0fa5c6e62f7a2a70634f224"),
        HistoricalInput("01a1d4d6-3f16-7952-af21-90339446bdf0","7ecc640d-a02b-45a5-b0be-e7431abeb4b8","1c0832914d40fc15cefcd2bc858df5d8b1f4b8bfc9728d16d949bdf92347a73b"),
        HistoricalInput("01a1d92f-e2f2-7560-bfb0-a3b977d315e9","d379be50-1c31-43a2-9048-376356451e8e","2dc6bded5cf9640d40c38e6dc69924628221269bcc62a02c0194e44c6c197064"),
        HistoricalInput("01a1d930-ae8a-7002-9c46-9c281ce79f15","a8767ed1-cf8a-4718-8227-503e68d92c77","f85a511366eb4e3246ba8e5e50f201df53b05c364c26cc14fe7ef06a4771d6b8",queueSource="native"),
        HistoricalInput("01a1dd60-c774-7253-95d7-65fa897b6955","d1a758ba-c4cc-4ada-9ac8-853add3bc8af","1b31ff7fb28aafbfbe4ec965bcd5fcb59fac332aedee6f1af4a8c982df45e886",external=true),
        HistoricalInput("01a1de07-d92c-7f12-92c6-a32c0ccdf3ba","bd770b5a-4971-48bb-b60e-7284ad9456e2","5319e85579c50b02450683ed65749916cbce421f101cc4a09b7cabcacca10252",state="interrupted"))
    private fun exact(v: JSONObject,want: Set<String>) {require(v.keys().asSequence().toSet()==want)}
    private fun n(v: JSONObject,k: String): Long {val x=v.get(k);require(x is Int||x is Long);return (x as Number).toLong()}
    private fun hash(v: ByteArray)=MessageDigest.getInstance("SHA-256").digest(v).joinToString("") {"%02x".format(it)}
    private fun same(a: Any,b: Any) {require(easyCanonical(a)==easyCanonical(b))}
    private fun uuid(s: String) {require(UUID.fromString(s).toString()==s)}
    private fun ref(v: JSONObject) {
        exact(v,setOf("path","bytes","sha256"));require(v.get("path") is String&&v.getString("path").startsWith("/"))
        require(n(v,"bytes")>0&&v.getString("sha256").matches(Regex("[a-f0-9]{64}")))
    }
    private fun inline(a: JSONObject,key: String): JSONObject {
        val r=a.getJSONObject(key+"Ref");ref(r)
        val text=a.get(key+"Json");require(text is String)
        val raw=text.toByteArray(Charsets.UTF_8);require(raw.size.toLong()==n(r,"bytes")&&hash(raw)==r.getString("sha256"))
        return JSONObject(text)
    }
    private fun identity(a: JSONObject) {
        require(a.get("run")==RUN&&a.get("profileRun")==RUN&&a.get("leaseId")==LEASE&&a.get("budgetOperationId")==BUDGET&&a.get("nonce")==NONCE)
        same(a.getJSONObject("target"),target);require(a.get("threadId")==THREAD)
    }
    fun validate(plan: JSONObject,a: JSONObject,nowUpper: Long): Q02RealReadInstallation.IndependentBudgetPermit {
        require(plan.get("caseKind")==CASE_KIND&&plan.opt("enabled")==true&&plan.opt("executionAuthorized")==true)
        require(n(a,"version")==1L&&a.get("kind")==AUTHORITY_KIND)
        identity(plan);identity(a)
        for(k in listOf("rootConfirmed","executionAuthorized","freshProfileAuthorized","newBindingAuthorized",
            "modelBudgetApproved","businessChatAuthorized","nativeAiSharingApproved"))require(a.opt(k)==true)
        require(a.opt("preparedOnly")==false&&a.opt("oldCaseMutationAuthorized")==false&&n(a,"maxNativeAttempts")==1L)
        for((k,v) in bounds)require(n(plan,k)==v&&n(a,k)==v)
        require(n(plan,"maxTextSubmissions")==4L&&n(plan,"maxChildStarts")==0L&&n(plan,"maxToolSeconds")==40L)
        require(n(a,"maxTextSubmissions")==4L&&n(a,"maxOriginalToolProcessSeconds")==40L)
        for(v in listOf(plan,a))require(listOf("continuation","postSecondKnownZero","existingOwnedProfile","knownZeroRecoveryRef","knownZeroRecoveryJson","nativeSpeedSelection","speed","serviceTier","service_tier").none {v.has(it)})
        val settings=plan.getJSONObject("settings");exact(settings,setOf("model","effort","permissionMode"))
        require(settings.get("model")=="gpt-6.1-sol"&&settings.get("effort")=="xhigh"&&settings.get("permissionMode")=="full-access")
        val coreText=a.get("planCoreJson");require(coreText is String)
        require(hash(coreText.toByteArray(Charsets.UTF_8))==a.get("planCoreSha256"))
        val core=JSONObject(plan.toString());core.remove("rootAuthoritySha256");same(JSONObject(coreText),core)
        val human=inline(a,"humanBudget")
        require(a.getJSONObject("humanBudgetRef").get("sha256")==HUMAN_SHA&&n(a.getJSONObject("humanBudgetRef"),"bytes")==2343L)
        require(human.get("kind")=="human-new-independent-q04-case-budget-authorized-v1"&&human.opt("executionAuthorized")==false)
        require(human.get("budgetOperationId")==BUDGET)
        for((k,v)in bounds)require(n(human,k)==v)
        require(n(human,"maxOriginalToolProcessSeconds")==40L&&n(human,"maxNativeAttempts")==1L&&n(human,"maxTextSubmissions")==4L)
        val rows=plan.getJSONObject("slots");exact(rows,slots)
        val hashes=a.getJSONObject("slotHashes");exact(hashes,slots)
        val ids=mutableSetOf<String>();val texts=mutableSetOf<String>()
        for(name in slots) {
            val row=rows.getJSONObject(name);val h=hashes.getJSONObject(name);exact(h,setOf("slotId","textSha256"))
            val id=row.getString("slotId");uuid(id);require(ids.add(id)&&historical.none {it.operation==id})
            val text=row.getString("text");val textSha=hash(text.toByteArray(Charsets.UTF_8))
            require(text.contains(NONCE)&&texts.add(textSha)&&historical.none {it.textSha==textSha})
            require(textSha==row.get("textSha256")&&row.get("slotId")==h.get("slotId")&&row.get("textSha256")==h.get("textSha256"))
            require(row.getJSONArray("attachments").length()==0&&listOf("operationId","clientMessageId").none {row.has(it)})
            require(row.get("mode")==when(name){"origin"->"next-turn";"steer"->"steer";else->"queue"})
        }
        require(plan.get("unsentDraftSha256")==hash(("LOCAL_UNSENT_DRAFT_"+RUN+"_"+NONCE).toByteArray(Charsets.UTF_8)))
        val b=inline(a,"businessBaseline")
        same(a.getJSONObject("businessBaselineRef"),plan.getJSONObject("businessBaselineRef"))
        require(a.get("businessBaselineJson")==plan.get("businessBaselineJson"))
        exact(b,setOf("version","kind","rootReviewed","caseRun","budgetOperationId","target","threadId","observedAtEpochMs",
            "officialComplete","ownerVerified","ready","canControl","state","turnId","turnIds","userInputs","receipts","approvals",
            "pendingAsyncQuestionCount","questionsListFieldAvailable","rawRefs","desktopRuntimeFingerprint","desktopExecutableSha256",
            "externalUserInputs","turnStates","officialTypedSummary","lifecycleNotices","officialBaselineRef"))
        require(n(b,"version")==3L&&b.get("kind")=="root-fresh-q04-business-baseline-v3"&&b.get("caseRun")==RUN&&b.get("budgetOperationId")==BUDGET)
        same(b.getJSONObject("target"),target);require(b.get("threadId")==THREAD)
        for(k in listOf("rootReviewed","officialComplete","ownerVerified","ready","canControl"))require(b.opt(k)==true)
        require(b.get("state")=="idle"&&b.get("turnId")===JSONObject.NULL&&b.getJSONArray("approvals").length()==0)
        require(n(b,"pendingAsyncQuestionCount")==0L&&b.opt("questionsListFieldAvailable")==false)
        require(nowUpper>=n(b,"observedAtEpochMs")&&nowUpper-n(b,"observedAtEpochMs")<=30000L)
        require(b.get("desktopRuntimeFingerprint")==plan.get("desktopRuntimeFingerprint")&&b.get("desktopExecutableSha256")==plan.get("desktopExecutableSha256"))
        same(b.getJSONArray("turnIds"),JSONArray(historical.map {it.turn}+RESTORED_EMPTY_RESUME_TURN));same(b.getJSONArray("turnIds"),plan.getJSONArray("baselineTurnIds"))
        same(b.getJSONArray("turnStates"),JSONArray(historical.map {it.turnState()}+JSONObject().put("id",RESTORED_EMPTY_RESUME_TURN).put("status","completed")))
        same(b.getJSONArray("userInputs"),JSONArray(historical.map {it.user()}))
        same(b.getJSONArray("externalUserInputs"),JSONArray(historical.filter {it.external}.map {it.user()}))
        same(b.getJSONArray("receipts"),JSONArray(historical.filterNot {it.external}.map {it.receipt()}))
        same(b.getJSONObject("officialTypedSummary"),JSONObject().put("actualUserTextRecords",6).put("lifecycleNoticeRecords",1).put("unclassifiedRecords",0))
        same(b.getJSONArray("lifecycleNotices"),JSONArray().put(JSONObject().put("turnId",historical.last().turn).put("type","generic.turn_aborted")))
        val official=b.getJSONObject("officialBaselineRef");ref(official)
        require(n(official,"bytes")==559276L&&official.get("sha256")=="326584273ace9e5d0cc6f4b22476c821d5842b8c79a941c7b70041af83376868")
        require(official.getString("path").endsWith("/rollout-2026-11-13T01-08-57-"+THREAD+".jsonl"))
        val raw=b.getJSONArray("rawRefs").objects();require(raw.isNotEmpty());raw.forEach(::ref)
        require(raw.any {easyCanonical(it)==easyCanonical(official)})
        return Q02RealReadInstallation.IndependentBudgetPermit.fromRoot(plan,a,nowUpper)
    }
}
