package com.agenthub.android

import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Debug acceptance only. A separate Root permit never rewrites the historical six-record manifest.
 * Construction validates the sealed new protocol; the original encrypted leaf is checked again before
 * the existing writer policy is frozen. This type cannot be constructed by ordinary Session restore.
 */
internal class Q04ExistingOwnedReadingPermit private constructor(
    private val run: String, private val lease: String, private val owner: String,
    private val target: TargetScope, private val thread: String, private val manifestCanonical: String,
    private val cipher: JSONObject, private val payloadBytes: Long, private val payloadSha256: String
) {
    internal fun validateForAdmission(permit: Q02RealReadInstallation.Permit,manifest: JSONObject) {
        require(run==permit.run&&lease==permit.leaseId&&sameScope(target,permit.target))
        require(owner==manifest.getString("storageOwnerRun")&&thread==manifest.getString("threadId"))
        require(manifestCanonical==easyCanonical(manifest)) {"Reading permit profile changed after validation"}
    }
    internal fun inspectBaseline(actualCipher: ByteArray,payload: String): NativeExistingOwnedReadingScope {
        require(actualCipher.size.toLong()==integer(cipher,"bytes")&&digest(actualCipher)==cipher.getString("sha256"))
        val encoded=payload.toByteArray(Charsets.UTF_8)
        try {require(encoded.size.toLong()==payloadBytes&&digest(encoded)==payloadSha256) {"Reading baseline differs from the independent current Native proof"}}
        finally {encoded.fill(0)}
        return NativeExistingOwnedReadingScope(target,thread,payload)
    }
    internal val cipherPath: String get()=cipher.getString("path")

    companion object {
        const val AUTHORITY_KIND="root-q04-post-second-known-zero-authority-v1"
        const val PERMIT_KIND="root-q04-post-second-reading-ledger-write-permit-v1"
        internal fun integer(o: JSONObject,k: String): Long {
            val v=o.get(k);require(v is Int||v is Long) {"Strict integer required: $k"}
            return (v as Number).toLong().also {require(it in 0..9_007_199_254_740_991L)}
        }
        internal fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        internal fun digest(text: String)=digest(text.toByteArray(Charsets.UTF_8))
        internal fun same(a: Any?,b: Any?) {require(easyCanonical(a)==easyCanonical(b))}
        internal fun checkRef(r: JSONObject) {
            require(r.keys().asSequence().toSet()==setOf("path","bytes","sha256"))
            val p=r.getString("path")
            require(p.startsWith("/")&&!p.contains('\u0000')&&p.split('/').drop(1).all {it.isNotEmpty()&&it!="."&&it!=".."})
            require(integer(r,"bytes") in 1..4_194_304&&r.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        }
        /** The courier carries original UTF-8 text, including its newline; never reserialize it to verify a Ref. */
        internal fun document(ref: JSONObject,text: String): JSONObject {
            checkRef(ref);val raw=text.toByteArray(Charsets.UTF_8)
            require(raw.size.toLong()==integer(ref,"bytes")&&digest(raw)==ref.getString("sha256"))
            return JSONObject(text)
        }
        private fun fields(value: JSONObject,keys: Set<String>) {require(value.keys().asSequence().toSet()==keys)}
        internal fun fromRootPostSecond(a: JSONObject,p: JSONObject,m: JSONObject): Q04ExistingOwnedReadingPermit {
            require(a.getString("kind")==AUTHORITY_KIND&&a.opt("executionAuthorized")==true&&a.opt("preparedOnly")==false)
            require(!a.has("preNativeRecovery")&&!a.has("knownNativeFailureRecovery"))
            val links=a.getJSONObject("postSecondKnownZero")
            require(links.getString("phaseKind")=="post-second-known-zero-v1"&&integer(links,"newProtocolNativeLaunchMax")==1L&&links.opt("retryAllowed")==false)
            val value=document(links.getJSONObject("readingLedgerWritePermitRef"),links.getString("readingLedgerWritePermitJson"))
            fields(value,setOf("kind","rootReviewed","executionAuthorized","run","leaseId","target","threadId","storageOwnerRun","logicalName","scope",
                "currentProfileRef","currentReadOnlyReviewRef","baselineNativeResultRef","cipherRecord","payloadBaseline","allowedAdditionalMutableRecords",
                "foreignRowsPolicy","beforeHttpTargetCheck","sourceAdoptionRef"))
            require(value.getString("kind")==PERMIT_KIND&&value.opt("rootReviewed")==true&&value.opt("executionAuthorized")==false)
            for(k in listOf("run","leaseId","target","threadId","storageOwnerRun"))same(value.get(k),a.get(k))
            for(k in listOf("run","leaseId","target","threadId"))same(value.get(k),p.get(k))
            same(value.get("storageOwnerRun"),m.get("storageOwnerRun"))
            val targetJson=value.getJSONObject("target");fields(targetJson,setOf("agentId","hostId","workspaceId","runtimeId"))
            val target=checkNotNull(HostReadCodec.target(CodexCommandCodec.validate(targetJson,"target")))
            require(target.agentId=="codex"&&target.runtimeId=="desktop-runtime")
            val thread=value.getString("threadId");require(java.util.UUID.fromString(thread).toString()==thread)
            require(value.getString("logicalName")==NativeReadingLedger.STORAGE_NAME&&value.getString("scope")=="one-owned-qa-thread-only")
            same(value.getJSONArray("allowedAdditionalMutableRecords"),JSONArray(listOf(NativeReadingLedger.STORAGE_NAME)))
            require(value.getString("foreignRowsPolicy")=="preserve-all-non-qa-rows-and-scopes"&&
                value.getString("beforeHttpTargetCheck")=="exact-full-target-and-qa-thread-before-http")
            same(value.getJSONObject("currentProfileRef"),a.getJSONObject("existingProfileRef"))
            require(value.getJSONObject("currentProfileRef").getString("sha256")==p.getString("existingProfileSha256"))
            same(value.getJSONObject("currentReadOnlyReviewRef"),links.getJSONObject("currentReadOnlyReviewRef"))
            val ro=document(links.getJSONObject("currentReadOnlyReviewRef"),links.getString("currentReadOnlyReviewJson"))
            require(ro.getString("kind")=="root-q04-post-second-current-inventory-readonly-review-v1"&&ro.opt("rootIndependentlyReviewed")==true&&ro.opt("executionAuthorized")==false)
            same(ro.getJSONObject("currentProfileRef"),value.getJSONObject("currentProfileRef"))
            same(ro.get("target"),targetJson);same(ro.get("threadId"),thread)
            same(value.getJSONObject("baselineNativeResultRef"),ro.getJSONObject("nativeResultRef"))
            val native=document(ro.getJSONObject("nativeResultRef"),ro.getString("nativeResultJson"))
            require(native.getString("kind")=="q04-post-second-current-inventory-readonly-native-result-v1"&&native.getString("methodStatus")=="passed")
            for((k,n)in mapOf("nativeMethods" to 1L,"nativeFailures" to 0L,"nativeSkips" to 0L,"unknown" to 0L,"inventoryCount" to 11L,"productionDecodedRecords" to 11L))require(integer(native,k)==n)
            for(k in listOf("productionLedgerParseCompatible","ownedLedgerConstructorCompatible","actualProductionSaveRoundtripPassed",
                "roundtripForeignCanonicalRowsPreserved","productionPendingReadProjectionPassed","memoryOnlyQaDeltaAccepted","compatibleForFutureLedgerPermit"))require(native.opt(k)==true)
            for(k in listOf("diagnosticFailureStage","diagnosticFailureClass"))require(!native.has(k)||native.isNull(k))
            same(native.getJSONObject("currentProfileRef"),value.getJSONObject("currentProfileRef"))
            for(k in listOf("appSha256","runnerSha256","signerSha256"))same(native.get(k),p.get(k))
            val baseline=native.getJSONObject("readingLedgerBaseline")
            for(k in listOf("productionAuthenticated","productionDecoded","present"))require(baseline.opt(k)==true)
            for(k in listOf("target","threadId","storageOwnerRun","logicalName"))same(baseline.get(k),value.get(k))
            val cipher=value.getJSONObject("cipherRecord");fields(cipher,setOf("path","bytes","sha256"))
            require(cipher.getString("path")=="no-backup/q02-session/"+digest(NativeReadingLedger.STORAGE_NAME)+".bin")
            require(integer(cipher,"bytes") in 1..1_048_576&&cipher.getString("sha256").matches(Regex("[a-f0-9]{64}")))
            val actual=m.getJSONArray("inventory").objects().filter {it.getString("path")==cipher.getString("path")}
            require(actual.size==1);same(actual.single(),cipher);same(baseline.getJSONObject("cipherRecord"),cipher)
            val payload=value.getJSONObject("payloadBaseline");fields(payload,setOf("utf8Bytes","sha256"))
            require(integer(payload,"utf8Bytes") in 1..1_048_576&&payload.getString("sha256").matches(Regex("[a-f0-9]{64}")))
            same(baseline.getJSONObject("payloadBaseline"),payload)
            same(value.getJSONObject("sourceAdoptionRef"),native.getJSONObject("sourceAdoptionRef"));checkRef(value.getJSONObject("sourceAdoptionRef"))
            val key=com.agenthub.core.RuntimeIdentity.cacheKey(target.hostId,target,thread,null)
            val six=setOf("metadata","operations:$key","submission:q04-remaining","ai-sharing-v1","history:$key","history:$key:parts")
            val mutable=m.getJSONArray("mutableRecords").strings();require(mutable.size==6&&mutable.toSet()==six)
            return Q04ExistingOwnedReadingPermit(value.getString("run"),value.getString("leaseId"),value.getString("storageOwnerRun"),target,thread,
                easyCanonical(m),JSONObject(cipher.toString()),integer(payload,"utf8Bytes"),payload.getString("sha256"))
        }
    }
}
