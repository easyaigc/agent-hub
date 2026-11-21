package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** New phase after both known Native failures. Never call the old one-failure admission for this kind.
 * Root's owner verifies raw host proofs and holds the atomic phase fd. Native verifies the sealed
 * courier lineage and the independent current reading proof, then returns only its typed record permit.
 * No host path is read and no marker, operation ID, replacement Origin or authority is created here.
 */
internal object Q04PostSecondKnownZeroAdmission {
    const val AUTHORITY_KIND=Q04ExistingOwnedReadingPermit.AUTHORITY_KIND
    const val PHASE_KIND="post-second-known-zero-v1"
    private const val HUMAN_BOUNDARY_SHA256="c5114952220af6e758057a2aa844200df72e5fa2ebe4e26307eaea4488be2444"
    private val mutablePlanFields=Q04PreNativeRecoveryAdmission.mutablePlanFields+setOf("appSha256","existingProfileSha256")
    private val slots=listOf("stopOrigin","queueCancel","queueExecute","steer")
    private val falseOldFields=setOf("attemptNumber","knownRemainingNativePopenCount","secondNativeMarkerAbsent","noNativePopen","noFirstNativeIntent")
    private val anchorNames=listOf("originalAuthorityRef","originalPlanRef","originalReservationRef","originalProfileRef","originalBudgetRef",
        "firstNativeIntentRef","firstNativeResultRef","secondSuccessorIntentRef","secondNativeIntentRef","secondNativeResultRef")
    private fun same(a: Any?,b: Any?)=Q04ExistingOwnedReadingPermit.same(a,b)
    private fun integer(o: JSONObject,k: String)=Q04ExistingOwnedReadingPermit.integer(o,k)
    private fun ref(o: JSONObject)=Q04ExistingOwnedReadingPermit.checkRef(o)
    private fun document(parent: JSONObject,name: String)=Q04ExistingOwnedReadingPermit.document(
        parent.getJSONObject(name+"Ref"),parent.getString(name+"Json"))
    private fun digest(text: String)=Q04ExistingOwnedReadingPermit.digest(text)
    private fun zero(value: JSONObject) {
        val keys=setOf("fourRemainingBodySubmissions","newIndependentTurns","newChildStarts","queueCancelOrExecuteOrSteerOrStopFromThisAttempt")
        require(value.keys().asSequence().toSet()==keys);keys.forEach {require(integer(value,it)==0L)}
    }
    internal fun validate(a: JSONObject,p: JSONObject,profile: JSONObject,budget: JSONObject,
        once: Q04PreNativeRecoveryAdmission.Document,old: Q04PreNativeRecoveryAdmission.Document,
        oldPlan: Q04PreNativeRecoveryAdmission.Document,now: Long): Q04ExistingOwnedReadingPermit {
        require(a.getString("kind")==AUTHORITY_KIND&&a.opt("executionAuthorized")==true&&a.opt("preparedOnly")==false)
        require(!a.has("preNativeRecovery")&&!a.has("knownNativeFailureRecovery"))
        val start=integer(a,"notBeforeMs");val end=integer(a,"expiresAtMs");val fresh=integer(a,"freshReviewEpochMs")
        require(start<=now&&now<end&&end-start in 1..1_800_000&&fresh<=now&&now-fresh<=30_000)
        require(integer(p,"startsAtEpochMs")==start&&integer(p,"expiresAtEpochMs")==end);ref(a.getJSONObject("freshReviewRef"))
        val links=a.getJSONObject("postSecondKnownZero")
        require(links.getString("phaseKind")==PHASE_KIND&&integer(links,"newProtocolNativeLaunchMax")==1L&&links.opt("retryAllowed")==false)
        require(falseOldFields.none(links::has))
        for((k,n)in mapOf("priorBusinessNativePopenCount" to 2L,"priorKnownNativeFailures" to 2L,"unknownNativeOutcomeCount" to 0L))require(integer(links,k)==n)
        for(k in anchorNames)ref(links.getJSONObject(k))
        for((k,doc)in listOf("originalReservationRef" to once,"originalAuthorityRef" to old,"originalPlanRef" to oldPlan)) {
            ref(doc.ref);same(links.getJSONObject(k),doc.ref)
        }
        same(links.getJSONObject("originalProfileRef"),old.value.getJSONObject("existingProfileRef"))
        same(links.getJSONObject("originalBudgetRef"),a.getJSONObject("originalBudgetRef"))
        require(old.value.getString("kind")=="root-q04-remaining-four-authority-v1"&&old.value.opt("executionAuthorized")==true&&
            old.value.opt("preparedOnly")==false&&integer(old.value,"expiresAtMs")<now)
        val reserved=once.value;require(reserved.getString("kind")=="remaining-four-on-second-turn-v1")
        same(reserved.getJSONObject("authorityRef"),old.ref);same(reserved.getJSONObject("originalClaimRef"),a.getJSONObject("originalClaimRef"))
        require(p.getString("childOnceSha256")==once.ref.getString("sha256")&&oldPlan.value.getString("childOnceSha256")==once.ref.getString("sha256"))
        require(oldPlan.value.getString("rootAuthoritySha256")==old.ref.getString("sha256"))
        for(k in listOf("run","leaseId","budgetOperationId"))for(source in listOf(p,reserved,old.value,oldPlan.value))same(a.get(k),source.get(k))
        val suffix="/runtime/q04-execution-claims-v1/"+a.getString("budgetOperationId")
        require(once.ref.getString("path").endsWith(suffix+"/continuations/remaining-four-on-second-turn-v1/intent.json"))
        require(a.getJSONObject("originalClaimRef").getString("path").endsWith(suffix+"/intent.json"))
        for(k in listOf("target","threadId","originalOrigin","remainingSlots","originalClaimRef","storageOwnerRun","originalBudgetRef",
            "settledOriginRef","originalLeaseReleaseRef","toolBudgetReviewRef","hostRuntimeSessionSha256","hostImplementationRef"))same(a.get(k),old.value.get(k))
        val oldKeys=oldPlan.value.keys().asSequence().toSet()-mutablePlanFields
        require(p.keys().asSequence().toSet()-mutablePlanFields==oldKeys);oldKeys.forEach {same(p.get(it),oldPlan.value.get(it))}
        same(a.getJSONObject("nativeManifestRef"),p.getJSONObject("nativeManifestRef"));ref(a.getJSONObject("nativeManifestRef"))
        require(a.getJSONObject("existingProfileRef").getString("sha256")==p.getString("existingProfileSha256"))
        for(k in listOf("budgetOperationId","controlHostId","target","threadId","settings","nativeSpeedSelection"))same(p.get(k),budget.get(k))
        same(p.getJSONObject("slots"),budget.getJSONObject("q04").getJSONObject("slots"))
        val allSlots=p.getJSONObject("slots");require(allSlots.keys().asSequence().toSet()==setOf("origin")+slots)
        for(name in listOf("origin")+slots) {
            val slot=allSlots.getJSONObject(name)
            require(digest(slot.getString("text"))==slot.getString("textSha256"))
            require(java.util.UUID.fromString(slot.getString("slotId")).toString()==slot.getString("slotId"))
            require(slot.getJSONArray("attachments").length()==0&&!slot.has("clientMessageId")&&!slot.has("operationId"))
        }
        val settings=p.getJSONObject("settings")
        require(settings.getString("model")=="gpt-6.1-sol"&&settings.getString("effort")=="xhigh"&&settings.getString("permissionMode")=="full-access")
        require(listOf("speed","serviceTier","service_tier").none(settings::has))
        for((k,n)in mapOf("maxNewIndependentTurns" to 1L,"maxTotalQ04Turns" to 2L,"childStartsMax" to 0L,"processSeconds" to 40L,"terminalWindowMs" to 45000L,"perProcessPollMax" to 1L)) {
            require(integer(a,k)==n);same(a.get(k),old.value.get(k))
        }
        for((k,n)in mapOf("maxTextSubmissions" to 5L,"maxIndependentOriginTurns" to 2L,"maxChildStarts" to 0L,"maxToolSeconds" to 40L,"maxOriginalSessionPolls" to 1L))require(integer(p,k)==n)
        same(a.getJSONArray("remainingSlots"),JSONArray(slots));same(reserved.getJSONArray("slots"),JSONArray(slots))
        val continuation=p.getJSONObject("continuation");val origin=a.getJSONObject("originalOrigin")
        require(origin.getString("bodySha256")==allSlots.getJSONObject("origin").getString("textSha256")&&
            origin.getString("operationId")==continuation.getString("originalOperationId")&&origin.getString("turnId")==continuation.getString("originalTurnId"))
        val boundary=document(links,"businessAuthorizationBoundaryReview")
        require(links.getJSONObject("businessAuthorizationBoundaryReviewRef").getString("sha256")==HUMAN_BOUNDARY_SHA256)
        require(boundary.getString("kind")=="root-q04-local-desktop-business-authorization-boundary-review-v1"&&boundary.opt("rootReviewed")==true&&
            boundary.opt("explicitHumanClarificationReceived")==true&&boundary.opt("executionAuthorized")==false)
        require(boundary.getString("scope")=="android-product-business-input-to-local-original-desktop-qa")
        for(k in listOf("target","threadId","taskId"))same(boundary.get(k),p.get(k))
        same(boundary.getJSONArray("remainingSlots"),JSONArray(slots));ref(boundary.getJSONObject("humanMessageEvidenceRef"))
        val facts=document(links,"failureFacts")
        require(facts.getString("kind")=="root-confirmed-q04-two-known-native-failures-before-business-v1"&&facts.opt("rootConfirmed")==true)
        for(k in listOf("executionAuthorized","recoveryAuthorized","freshAuthorized","replayAllowed"))require(facts.opt(k)==false)
        for(k in listOf("run","leaseId","target","threadId"))same(facts.get(k),a.get(k))
        for((k,n)in mapOf("priorBusinessNativePopenCount" to 2L,"priorKnownNativeFailures" to 2L,"unknownNativeOutcomeCount" to 0L))require(integer(facts,k)==n)
        for(k in anchorNames)same(facts.getJSONObject(k),links.getJSONObject(k))
        val failures=facts.getJSONArray("priorFailures");require(failures.length()==2)
        for((i,label)in listOf("first","second").withIndex()) {
            val row=failures.getJSONObject(i)
            require(integer(row,"businessNativeOrdinal")==i+1L&&row.opt("workEntered")==false)
            same(row.getJSONObject("nativeIntentRef"),links.getJSONObject(label+"NativeIntentRef"))
            same(row.getJSONObject("nativeResultRef"),links.getJSONObject(label+"NativeResultRef"))
        }
        require(integer(facts,"secondTerminalAtEpochMs")<=start)
        val businessZero=document(links,"businessZeroReview")
        require(businessZero.getString("kind")=="root-q04-post-second-business-zero-review-v1"&&businessZero.opt("rootIndependentlyReviewed")==true&&
            businessZero.opt("controllerSelfReportOnly")==false&&businessZero.opt("executionAuthorized")==false)
        same(businessZero.getJSONObject("failureFactsRef"),links.getJSONObject("failureFactsRef"))
        same(businessZero.get("target"),a.get("target"));same(businessZero.get("threadId"),a.get("threadId"));zero(businessZero.getJSONObject("knownBusinessZero"))
        require(businessZero.getJSONArray("generatedRemainingOperationIds").length()==0&&businessZero.getJSONArray("newControlTurnIds").length()==0)
        for(k in listOf("officialTaskRecordAuditRef","hostReceiptAuditRef","nativeJournalAuditRef","sourceBoundaryReviewRef"))ref(businessZero.getJSONObject(k))
        val ro=document(links,"currentReadOnlyReview")
        require(ro.getString("kind")=="root-q04-post-second-current-inventory-readonly-review-v1"&&ro.opt("rootIndependentlyReviewed")==true&&ro.opt("executionAuthorized")==false)
        same(ro.getJSONObject("historicalProfileRef"),links.getJSONObject("originalProfileRef"))
        same(ro.getJSONObject("currentProfileRef"),a.getJSONObject("existingProfileRef"));same(ro.getJSONObject("secondNativeResultRef"),links.getJSONObject("secondNativeResultRef"))
        same(ro.get("target"),a.get("target"));same(ro.get("threadId"),a.get("threadId"))
        require(integer(ro,"startedAtEpochMs")>=integer(facts,"secondTerminalAtEpochMs")&&integer(ro,"startedAtEpochMs")<=integer(ro,"completedAtEpochMs")&&integer(ro,"completedAtEpochMs")<=fresh)
        val native=document(ro,"nativeResult")
        for((k,n)in mapOf("nativeMethods" to 1L,"nativeFailures" to 0L,"nativeSkips" to 0L,"unknown" to 0L,"productionDecodedRecords" to 11L,"inventoryCount" to 11L,"protectedRecordCount" to 3L))require(integer(native,k)==n)
        require(native.getJSONArray("unclassifiedPaths").length()==0)
        for(k in listOf("productionAuthenticationPassed","originalKeyReused","protectedRecordsUnchanged","originalIntentBytesUnchanged",
            "originalCompletedReceiptVerified","originalLockDevInodeUnchanged","overlappingAdmissionRejected","reAdmissionAfterRelease","closedInspectionRejected","inventoryBeforeAfterUnchanged"))require(native.opt(k)==true)
        for(k in listOf("modelOrQueueWrites","newJournalRecords","newGrantWrites","newKeyCreations"))require(integer(native,k)==0L)
        require(native.getString("originalOperationId")==origin.getString("operationId")&&native.getString("originalTurnId")==origin.getString("turnId"))
        val inventory=profile.getJSONArray("inventory");require(inventory.length()==11)
        val inventorySha=digest(easyCanonical(inventory));require(native.getString("currentInventorySha256")==inventorySha)
        for(k in listOf("rawJNIRef","sourceBindingRef","beforeAfterResourceRef"))ref(ro.getJSONObject(k))
        val current=links.getJSONObject("currentAdmission");require(falseOldFields.none(current::has))
        for((k,n)in mapOf("priorBusinessNativePopenCount" to 2L,"unknownNativeOutcomeCount" to 0L,"knownQ04Inputs" to 1L,"pendingRequests" to 0L,"hostBindings" to 12L,"unreviewedInputs" to 0L))require(integer(current,k)==n)
        require(current.getString("runtimeState")=="idle"&&current.opt("originalOwnerVerified")==true&&current.opt("noRemainingReceipts")==true&&current.opt("bindingContinuityVerified")==true)
        same(current.getJSONObject("reviewRef"),a.getJSONObject("freshReviewRef"));require(integer(current,"observedAtEpochMs")==fresh)
        same(current.getJSONObject("currentReadOnlyReviewRef"),links.getJSONObject("currentReadOnlyReviewRef"))
        require(current.getString("profileInventorySha256")==inventorySha)
        same(current.get("target"),p.get("target"));same(current.get("threadId"),p.get("threadId"))
        same(current.getJSONArray("turnIds"),continuation.getJSONArray("freshBaselineTurnIds"));require(current.getJSONArray("turnIds").length()==2)
        val turns=current.getJSONArray("turns");require(turns.length()==2)
        for(i in 0 until 2)require(turns.getJSONObject(i).getString("id")==current.getJSONArray("turnIds").getString(i)&&turns.getJSONObject(i).getString("status")=="completed")
        same(current.getJSONArray("childIds"),continuation.getJSONArray("allowedHistoricalChildIds"))
        require(current.getJSONArray("newControlTurns").length()==0&&current.getJSONArray("remainingOperationIds").length()==0)
        val raw=current.getJSONArray("rawRefs");require(raw.length() in 1..32);for(i in 0 until raw.length())ref(raw.getJSONObject(i))
        // Factory checks exact Ref+UTF8 text, actual current result compatibility, six-record invariance,
        // current cipher/payload baseline and the full one-QA target before profile/admission construction.
        return Q04ExistingOwnedReadingPermit.fromRootPostSecond(a,p,profile)
    }
}
