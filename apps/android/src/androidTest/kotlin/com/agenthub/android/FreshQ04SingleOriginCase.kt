package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** New case only. Root sealing supplies real inline proofs; disabled templates cannot admit execution. */
internal object FreshQ04SingleOriginCase {
    const val RUN="d5d1d682-2b22-44e5-89a8-bc37f643384b"
    const val LEASE="a89619e2-8cc7-408e-85f3-2998186f1ca5"
    const val BUDGET="b7253462-e867-4f09-8518-429e8d620e8b"
    const val NONCE="417d0a8a4d0e496b"
    const val CASE_KIND="q04-fresh-single-origin-v1"
    const val AUTHORITY_KIND="root-q04-fresh-single-origin-authority-v1"
    private const val HUMAN_SHA="c53b308c61cdcb95ec942b5a288e0a82ff105f5185fbb0269cb00472d29b4172"
    private val target=JSONObject().put("agentId","codex").put("hostId","13c81542-48e3-4df2-ab46-d45357b631a2")
        .put("workspaceId","13c81542-48e3-4df2-ab46-d45357b631a2").put("runtimeId","desktop-runtime")
    private const val THREAD="01a1d0bb-e7b3-7c51-92d9-98ab96329fb7"
    private val slots=setOf("origin","queueCancel","queueExecute","steer")
    private val bounds=mapOf("maxNewIndependentOriginTurns" to 1L,"maxNewActualTurns" to 2L,
        "maxAutomaticQueueFollowups" to 1L,"maxNewChildStarts" to 0L,"maximumPendingTestQueueItems" to 1L,
        "terminalWindowMs" to 45000L,"maxOriginalSessionPolls" to 1L)
    private fun keys(v: JSONObject)=v.keys().asSequence().toSet()
    private fun exact(v: JSONObject,want: Set<String>) {require(keys(v)==want)}
    private fun n(v: JSONObject,k: String): Long {val x=v.get(k);require(x is Int||x is Long);return (x as Number).toLong()}
    private fun hash(v: ByteArray)=MessageDigest.getInstance("SHA-256").digest(v).joinToString("") {"%02x".format(it)}
    private fun same(a: Any,b: Any) {require(easyCanonical(a)==easyCanonical(b))}
    private fun uuid(s: String) {require(UUID.fromString(s).toString()==s)}
    private fun ref(v: JSONObject) {exact(v,setOf("path","bytes","sha256"));require(v.get("path") is String&&v.getString("path").startsWith("/"));require(n(v,"bytes")>0);require(v.getString("sha256").matches(Regex("[a-f0-9]{64}")))}
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
    fun validate(plan: JSONObject,a: JSONObject,nowUpper: Long) {
        require(plan.get("caseKind")==CASE_KIND&&plan.opt("enabled")==true&&plan.opt("executionAuthorized")==true)
        require(n(a,"version")==1L&&a.get("kind")==AUTHORITY_KIND)
        identity(plan);identity(a)
        for(k in listOf("rootConfirmed","executionAuthorized","freshProfileAuthorized","newBindingAuthorized",
            "modelBudgetApproved","businessChatAuthorized","nativeAiSharingApproved"))require(a.opt(k)==true)
        require(a.opt("preparedOnly")==false&&a.opt("oldCaseMutationAuthorized")==false&&n(a,"maxNativeAttempts")==1L)
        for((k,v) in bounds)require(n(plan,k)==v&&n(a,k)==v)
        require(n(plan,"maxTextSubmissions")==4L&&n(plan,"maxChildStarts")==0L&&n(plan,"maxToolSeconds")==40L)
        require(n(a,"maxTextSubmissions")==4L&&n(a,"maxOriginalToolProcessSeconds")==40L)
        require(listOf("continuation","postSecondKnownZero","existingOwnedProfile","nativeSpeedSelection","speed","serviceTier","service_tier").none {plan.has(it)})
        val settings=plan.getJSONObject("settings");exact(settings,setOf("model","effort","permissionMode"))
        require(settings.get("model")=="gpt-6.1-sol"&&settings.get("effort")=="xhigh"&&settings.get("permissionMode")=="full-access")
        val coreText=a.get("planCoreJson");require(coreText is String)
        require(hash(coreText.toByteArray(Charsets.UTF_8))==a.get("planCoreSha256"))
        val core=JSONObject(plan.toString());core.remove("rootAuthoritySha256");same(JSONObject(coreText),core)
        val human=inline(a,"humanBudget")
        require(a.getJSONObject("humanBudgetRef").get("sha256")==HUMAN_SHA&&n(a.getJSONObject("humanBudgetRef"),"bytes")==1951L)
        require(human.get("kind")=="human-new-independent-q04-case-budget-authorized-v1"&&human.opt("executionAuthorized")==false)
        require(human.get("budgetOperationId")==BUDGET)
        for((k,v)in bounds)require(n(human,k)==v)
        require(n(human,"maxOriginalToolProcessSeconds")==40L&&human.opt("newBodiesAndIdsRequired")==true&&human.opt("oldCasesAndMarkersImmutable")==true)
        val rows=plan.getJSONObject("slots");exact(rows,slots)
        val hashes=a.getJSONObject("slotHashes");exact(hashes,slots)
        val ids=mutableSetOf<String>();val texts=mutableSetOf<String>()
        for(name in slots) {
            val row=rows.getJSONObject(name);val h=hashes.getJSONObject(name);exact(h,setOf("slotId","textSha256"))
            val id=row.getString("slotId");uuid(id);require(ids.add(id))
            val text=row.getString("text");require(text.contains(NONCE)&&texts.add(hash(text.toByteArray(Charsets.UTF_8))))
            require(hash(text.toByteArray(Charsets.UTF_8))==row.get("textSha256"));require(row.get("slotId")==h.get("slotId")&&row.get("textSha256")==h.get("textSha256"))
            require(row.getJSONArray("attachments").length()==0&&listOf("operationId","clientMessageId").none {row.has(it)})
            require(row.get("mode")==when(name){"origin"->"next-turn";"steer"->"steer";else->"queue"})
        }
        require(plan.get("unsentDraftSha256")==hash(("LOCAL_UNSENT_DRAFT_"+RUN+"_"+NONCE).toByteArray(Charsets.UTF_8)))
        val b=inline(a,"businessBaseline")
        same(a.getJSONObject("businessBaselineRef"),plan.getJSONObject("businessBaselineRef"))
        require(a.get("businessBaselineJson")==plan.get("businessBaselineJson"))
        exact(b,setOf("version","kind","rootReviewed","caseRun","budgetOperationId","target","threadId","observedAtEpochMs",
            "officialComplete","ownerVerified","ready","canControl","state","turnId","turnIds","userInputs","receipts","approvals",
            "pendingAsyncQuestionCount","questionsListFieldAvailable","rawRefs","desktopRuntimeFingerprint","desktopExecutableSha256"))
        require(n(b,"version")==1L&&b.get("kind")=="root-fresh-q04-business-baseline-v1"&&b.get("caseRun")==RUN&&b.get("budgetOperationId")==BUDGET)
        same(b.getJSONObject("target"),target);require(b.get("threadId")==THREAD)
        for(k in listOf("rootReviewed","officialComplete","ownerVerified","ready","canControl"))require(b.opt(k)==true)
        require(b.get("state")=="idle"&&b.get("turnId")===JSONObject.NULL&&b.getJSONArray("approvals").length()==0)
        require(n(b,"pendingAsyncQuestionCount")==0L&&b.opt("questionsListFieldAvailable")==false)
        require(nowUpper>=n(b,"observedAtEpochMs")&&nowUpper-n(b,"observedAtEpochMs")<=30000L)
        require(b.get("desktopRuntimeFingerprint")==plan.get("desktopRuntimeFingerprint")&&b.get("desktopExecutableSha256")==plan.get("desktopExecutableSha256"))
        val turns=b.getJSONArray("turnIds").strings();require(turns.size==4&&turns.distinct().size==turns.size&&turns.all {it.isNotBlank()})
        same(b.getJSONArray("turnIds"),plan.getJSONArray("baselineTurnIds"))
        val users=b.getJSONArray("userInputs").objects();val receipts=b.getJSONArray("receipts").objects()
        require(users.size==4&&receipts.size==4&&users.map {it.getString("turnId")}.toSet()==turns.toSet())
        require(users.map {it.getString("operationId")}.distinct().size==4)
        for(u in users) {
            exact(u,setOf("turnId","operationId","textSha256"));uuid(u.getString("operationId"))
            require(u.getString("textSha256").matches(Regex("[a-f0-9]{64}")))
            val receipt=receipts.single {it.get("operationId")==u.get("operationId")}
            exact(receipt,setOf("operationId","turnId","state","queueSource","queuePresent","textSha256"))
            require(receipt.get("turnId")==u.get("turnId")&&receipt.get("textSha256")==u.get("textSha256")&&receipt.get("state")=="completed"&&receipt.opt("queuePresent")==false)
        }
        val raw=b.getJSONArray("rawRefs").objects();require(raw.isNotEmpty());raw.forEach(::ref)
    }
}

/** Queue/steer in this UI scenario inherit the independently checked original Turn settings. */
internal object FreshQ04GenerationFence {
    fun accepts(slot: String,settings: GenerationSettings): Boolean = when(slot) {
        "origin" -> settings.model=="gpt-6.1-sol"&&settings.effort=="xhigh"&&settings.permissionMode=="full-access"
        "queueCancel","queueExecute","steer" -> settings==GenerationSettings()
        else -> false
    }
}

/** Dispatch is consumed before evidence IO/HTTP; unknown outcomes never create a second dispatch. */
internal class FreshQ04DispatchLedger {
    private data class Entry(val operation: String,val intent: String,var sent: Boolean=false)
    private val entries=linkedMapOf<String,Entry>()
    @Synchronized fun consume(slot: String,operation: String,intent: String,stage: String) {
        require(slot in setOf("origin","queueCancel","queueExecute","steer"))
        require(UUID.fromString(operation).toString()==operation&&intent.matches(Regex("[a-f0-9]{64}")))
        when(stage) {
            "before-mark-submitting"->{require(entries.size<4&&!entries.containsKey(slot)&&entries.values.none {it.operation==operation});entries[slot]=Entry(operation,intent)}
            "before-send"->{val old=checkNotNull(entries[slot]);require(old.operation==operation&&old.intent==intent&&!old.sent);old.sent=true}
            else->error("Fresh dispatch retries are forbidden; query original ID")
        }
    }
    @Synchronized fun dispatchedSlots(): Set<String> = entries.filterValues {it.sent}.keys.toSet()
}
