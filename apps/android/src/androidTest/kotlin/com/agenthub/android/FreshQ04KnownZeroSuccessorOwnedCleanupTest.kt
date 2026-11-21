package com.agenthub.android

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyStore

/** Finite paired-profile cases only; no prepair/not_found/empty-profile cleanup. */
class FreshQ04KnownZeroSuccessorOwnedCleanupTest {
    private data class CleanupCase(val run: String,val lease: String,val nonce: String,
        val caseKind: String,val authorityKind: String,val originalPid: Int?=null,
        val budget: String=FreshQ04KnownZeroSuccessorCase.BUDGET,
        val nativeMethod: String="com.agenthub.android.MobileGenerationCommandRealDevR2Test#q04KnownZeroSuccessorSingleOriginControlsOnce",
        val nativeFlag: String="w3FreshKnownZeroSuccessor",val apiReceiptProof: Boolean=false)
    private fun sha(b: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") {"%02x".format(it)}
    private fun hash(s: String) {require(s.matches(Regex("[a-f0-9]{64}")))}
    private fun same(a: Any?,b: Any?) {require(easyCanonical(a)==easyCanonical(b))}
    private fun target(v: JSONObject) {same(v,JSONObject().put("agentId","codex").put("hostId","13c81542-48e3-4df2-ab46-d45357b631a2").put("workspaceId","13c81542-48e3-4df2-ab46-d45357b631a2").put("runtimeId","desktop-runtime"))}
    private fun ref(v: JSONObject) {exact(v,"path","bytes","sha256");require(string(v,"path").startsWith("/"));require(number(v,"bytes")>0);hash(string(v,"sha256"))}
    private fun exact(v: JSONObject, vararg names: String) { require(v.keys().asSequence().toSet()==names.toSet()) }
    private fun string(v: JSONObject, name: String): String = (v.get(name) as? String) ?: error("Non-string $name")
    private fun bool(v: JSONObject, name: String): Boolean = (v.get(name) as? Boolean) ?: error("Non-boolean $name")
    private fun number(v: JSONObject,name: String): Long {
        val raw=v.get(name);require(raw is Int || raw is Long);return (raw as Number).toLong()
    }
    private fun owned(path: Path, directory: Boolean) {
        val st=Os.lstat(path.toString())
        require(st.st_uid==Process.myUid() && st.st_gid==Process.myUid())
        require(if(directory) OsConstants.S_ISDIR(st.st_mode) else OsConstants.S_ISREG(st.st_mode))
        require(st.st_mode and 0xfff==if(directory) 0x1c0 else 0x180)
        if(!directory)require(st.st_nlink==1L)
        require(!Files.isSymbolicLink(path))
    }
    private fun bytes(path: Path, maximum: Int): ByteArray {
        owned(path,false)
        val fd=Os.open(path.toString(),OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0)
        try {
            val before=Os.fstat(fd);require(before.st_size in 1..maximum.toLong())
            val value=ByteArray(before.st_size.toInt());var offset=0
            while(offset<value.size) { val n=Os.read(fd,value,offset,value.size-offset);require(n>0);offset+=n }
            require(Os.read(fd,ByteArray(1),0,1)==0)
            val after=Os.fstat(fd);val current=Os.lstat(path.toString())
            require(listOf(after,current).all { it.st_dev==before.st_dev && it.st_ino==before.st_ino && it.st_size==before.st_size && it.st_mtime==before.st_mtime && it.st_mode==before.st_mode && it.st_uid==before.st_uid && it.st_nlink==1L })
            return value
        } finally { Os.close(fd) }
    }
    private fun shaFile(file: File): String {
        val md=java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;md.update(buffer,0,n)} }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun artifact(base: Path, v: JSONObject, expectedName: String): JSONObject {
        exact(v,"name","bytes","sha256")
        require(string(v,"name")==expectedName);val raw=bytes(base.resolve(expectedName),65_536)
        require(raw.size.toLong()==number(v,"bytes") && sha(raw)==string(v,"sha256"))
        return JSONObject(String(raw,Charsets.UTF_8))
    }
    private fun archiveRef(v: JSONObject) {
        exact(v,"evidenceId","bytes","sha256","readbackSha256","outsideProfile","rootVerified")
        require(string(v,"evidenceId").matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(number(v,"bytes")>0);hash(string(v,"sha256"));require(string(v,"readbackSha256")==string(v,"sha256"))
        require(bool(v,"outsideProfile") && bool(v,"rootVerified"))
    }
    private fun strings(v: JSONArray): List<String> = (0 until v.length()).map { (v.get(it) as? String) ?: error("Non-string list item") }
    private fun canonical(v: Any?): String=when(v) {
        null,JSONObject.NULL->"null"
        is JSONObject->v.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(v.get(it))}
        is JSONArray->(0 until v.length()).joinToString(",","[","]"){canonical(v.get(it))}
        is String->JSONObject.quote(v)
        is Boolean,is Number->v.toString()
        else->error("Unsupported proof JSON")
    }
    private fun noOriginalPid(pid: Int) {
        require(pid>1 && pid!=Process.myPid())
        try { Os.stat("/proc/$pid");error("Original PID exists or was reused; preserve") }
        catch(e: ErrnoException) { require(e.errno==OsConstants.ENOENT) }
    }
    private class Once(private val file: Path) {
        private fun hashValue(s: String) {require(s.matches(Regex("[a-f0-9]{64}")))}
        fun prior(): String? {
            if(Files.notExists(file,NOFOLLOW_LINKS))return null
            require(!Files.isSymbolicLink(file) && Files.isRegularFile(file,NOFOLLOW_LINKS))
            val st=Os.lstat(file.toString());require(st.st_uid==Process.myUid() && st.st_gid==Process.myUid() && st.st_nlink==1L && st.st_mode and 0xfff==0x180 && st.st_size==65L)
            val raw=Files.newByteChannel(file,setOf(StandardOpenOption.READ,NOFOLLOW_LINKS)).use { channel ->
                val b=ByteBuffer.allocate(66);while(channel.read(b)>0){};b.flip();ByteArray(b.remaining()).also {b.get(it)}
            }
            require(raw.size==65 && raw.last()==10.toByte());return String(raw,Charsets.US_ASCII).trimEnd('\n').also { hashValue(it) }
        }
        fun claim(manifestSha256: String) {
            // This marker is per run, not per operation: changing operation ID cannot replay a partial attempt.
            FileChannel.open(file,setOf(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,NOFOLLOW_LINKS),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))).use { channel ->
                val b=ByteBuffer.wrap((manifestSha256+"\n").toByteArray(Charsets.US_ASCII));while(b.hasRemaining())channel.write(b);channel.force(true)
            }
            val fd=Os.open(file.parent.toString(),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
            try {Os.fsync(fd)}finally{Os.close(fd)}
            require(prior()==manifestSha256)
        }
    }
    @Test fun queryOrDeleteOnlyRootReviewedSuccessorBAProfileOnce() {
        queryOrDeleteOnlyRootReviewedPairedSuccessorProfileOnce(CleanupCase(
            FreshQ04KnownZeroSuccessorCase.RUN,FreshQ04KnownZeroSuccessorCase.LEASE,FreshQ04KnownZeroSuccessorCase.NONCE,
            FreshQ04KnownZeroSuccessorCase.CASE_KIND,FreshQ04KnownZeroSuccessorCase.AUTHORITY_KIND))
    }
    @Test fun queryOrDeleteOnlyRootReviewedSuccessorC11ProfileOnce() {
        queryOrDeleteOnlyRootReviewedPairedSuccessorProfileOnce(CleanupCase(
            FreshQ04KnownZeroSuccessorCase.NEXT_RUN,"6b532023-1a15-475c-8665-1f0457c3e127","17c6861ae795865d",
            "q04-post-ba-known-zero-successor-v1","root-q04-post-ba-known-zero-successor-authority-v1",8616))
    }
    @Test fun queryOrDeleteOnlyRootReviewedIndependent968ProfileOnce() {
        queryOrDeleteOnlyRootReviewedPairedSuccessorProfileOnce(CleanupCase(
            FreshQ04IndependentBudgetCase.RUN,FreshQ04IndependentBudgetCase.LEASE,FreshQ04IndependentBudgetCase.NONCE,
            FreshQ04IndependentBudgetCase.CASE_KIND,FreshQ04IndependentBudgetCase.AUTHORITY_KIND,
            budget=FreshQ04IndependentBudgetCase.BUDGET,
            nativeMethod="com.agenthub.android.MobileGenerationCommandRealDevR2Test#q04NewIndependentBudgetSingleOriginControlsOnce",
            nativeFlag="w3FreshIndependentBudget"))
    }
    @Test fun queryOrDeleteOnlyRootReviewedIndependent968ApiReceiptProfileOnce() {
        queryOrDeleteOnlyRootReviewedPairedSuccessorProfileOnce(CleanupCase(
            FreshQ04IndependentBudgetCase.RUN,FreshQ04IndependentBudgetCase.LEASE,FreshQ04IndependentBudgetCase.NONCE,
            FreshQ04IndependentBudgetCase.CASE_KIND,FreshQ04IndependentBudgetCase.AUTHORITY_KIND,17139,
            budget=FreshQ04IndependentBudgetCase.BUDGET,
            nativeMethod="com.agenthub.android.MobileGenerationCommandRealDevR2Test#q04NewIndependentBudgetSingleOriginControlsOnce",
            nativeFlag="w3FreshIndependentBudget",apiReceiptProof=true))
    }
    /** This case has real per-binding API before/after, but no complete pre-revoke new-row snapshot. */
    private fun verifyIndependent968ApiReceipt(before: JSONObject,after: JSONObject,revoke: JSONObject,
        intent: JSONObject,nativeCase: JSONObject,cleanupCase: CleanupCase,mobile: String,publicSha: String) {
        require(cleanupCase.run==FreshQ04IndependentBudgetCase.RUN&&cleanupCase.budget==FreshQ04IndependentBudgetCase.BUDGET&&cleanupCase.originalPid==17139)
        require(publicSha=="0d4e15cf8095d3e36a2e9fdfd1634e7c695251def98495572d29b047540a10e8")
        val old=before.getJSONArray("bindings").objects();val current=after.getJSONArray("bindings").objects()
        require(old.size==13&&current.size==14&&old.none {string(it,"publicKeySha256")==publicSha})
        val fresh=current.single {string(it,"publicKeySha256")==publicSha}
        same(JSONArray(current.filter {string(it,"publicKeySha256")!=publicSha}),before.getJSONArray("bindings"))
        target(fresh.getJSONObject("target"));require(bool(fresh,"revoked")&&bool(fresh,"hostKeyAuthorized"))
        require(string(before.getJSONObject("sourceRef"),"path")==string(after.getJSONObject("sourceRef"),"path"))
        exact(revoke,"kind","rootReviewed","target","mobileDeviceId","mobilePublicKeySha256","pairingIntentSha256",
            "persistedBeforeRef","persistedAfterRef","allOtherRowsPreserved","onlyChangedField","revocationConfirmed","phoneProfileAndKeyDeleted",
            "run","budgetOperationId","leaseId","planSha256","authoritySha256","clientOperationId","operationReceiptRef","nativeTerminalRef","resourceConfirmationRef",
            "evidenceMode","fullNewBindingBeforeSnapshotObserved","beforeStatusRef","beforeStatusJson","operationReadbackRef","operationReadbackJson")
        require(string(revoke,"kind")=="root-fresh-q04-successor-codex-api-revoke-review-v1"&&bool(revoke,"rootReviewed"))
        require(string(revoke,"evidenceMode")=="old13-baseline-new14-and-original-binding-api-receipts"&&!bool(revoke,"fullNewBindingBeforeSnapshotObserved"))
        require(bool(revoke,"allOtherRowsPreserved")&&bool(revoke,"revocationConfirmed")&&!bool(revoke,"phoneProfileAndKeyDeleted"))
        require(revoke.get("run")==cleanupCase.run&&revoke.get("budgetOperationId")==cleanupCase.budget&&revoke.get("leaseId")==cleanupCase.lease)
        require(revoke.get("planSha256")==nativeCase.get("planSha256")&&revoke.get("authoritySha256")==nativeCase.get("authoritySha256"))
        target(revoke.getJSONObject("target"));require(string(revoke,"mobileDeviceId")==mobile&&string(revoke,"mobilePublicKeySha256")==publicSha&&revoke.get("pairingIntentSha256")==intent.get("pairingIntentSha256"))
        require(string(revoke,"onlyChangedField")=="revoked") // Only this field changed in the actual single-binding API DTO, not in a claimed full-file snapshot.
        val operation=string(revoke,"clientOperationId");require(operation=="b9613a7f-fcad-4dd6-a748-53468db702ce")
        for(k in listOf("operationReceiptRef","nativeTerminalRef","resourceConfirmationRef"))ref(revoke.getJSONObject(k))
        same(revoke.getJSONObject("persistedBeforeRef"),before.getJSONObject("sourceRef"));same(revoke.getJSONObject("persistedAfterRef"),after.getJSONObject("sourceRef"))
        fun inline(refName: String,jsonName: String): JSONObject {
            val pinned=revoke.getJSONObject(refName);ref(pinned);val text=string(revoke,jsonName);val raw=text.toByteArray(Charsets.UTF_8)
            require(raw.size in 1..65536&&raw.size.toLong()==number(pinned,"bytes")&&sha(raw)==string(pinned,"sha256"));return JSONObject(text)
        }
        val prior=inline("beforeStatusRef","beforeStatusJson");val results=inline("operationReadbackRef","operationReadbackJson")
        same(revoke.getJSONObject("operationReceiptRef"),revoke.getJSONObject("operationReadbackRef"))
        fun response(reply: JSONObject): JSONObject {
            exact(reply,"status","value","rawSha256","observedAtEpochMs");require(number(reply,"status")==200L&&number(reply,"observedAtEpochMs")>0)
            hash(string(reply,"rawSha256"));return reply.getJSONObject("value")
        }
        fun binding(value: JSONObject,revoked: Boolean) {
            exact(value,"target","publicKeySha256","revoked","hostKeyAuthorized");target(value.getJSONObject("target"))
            require(string(value,"publicKeySha256")==publicSha&&bool(value,"revoked")==revoked&&bool(value,"hostKeyAuthorized"))
        }
        val priorBinding=response(prior);binding(priorBinding,false)
        exact(results,"submitted","readback");val submitted=results.getJSONObject("submitted");val readback=results.getJSONObject("readback");exact(readback,"operation","binding")
        val persisted=readback.getJSONObject("operation");val finalStatus=readback.getJSONObject("binding")
        val submittedValue=response(submitted);val persistedValue=response(persisted);val finalBinding=response(finalStatus)
        for(value in listOf(submittedValue,persistedValue)) {
            exact(value,"clientOperationId","inputSha256","state","binding")
            require(string(value,"clientOperationId")==operation&&string(value,"state")=="confirmed");hash(string(value,"inputSha256"));binding(value.getJSONObject("binding"),true)
        }
        same(submittedValue,persistedValue);binding(finalBinding,true);same(finalBinding,persistedValue.getJSONObject("binding"))
        val expected=JSONObject(priorBinding.toString()).put("revoked",true);same(expected,finalBinding)
        require(number(prior,"observedAtEpochMs")<=number(submitted,"observedAtEpochMs")&&number(submitted,"observedAtEpochMs")<=number(persisted,"observedAtEpochMs")&&number(persisted,"observedAtEpochMs")<=number(finalStatus,"observedAtEpochMs"))
    }
    private fun queryOrDeleteOnlyRootReviewedPairedSuccessorProfileOnce(cleanupCase: CleanupCase) {
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("freshQ04SuccessorOwnedCleanupReady")=="1") {"Default disabled; no KeyStore access"}
        require(args.getString("nativeForeground")=="0"&&args.getString("nativeThreadSnapshots")=="0")
        require(args.keySet().none {it.startsWith("w3")||it.startsWith("q04Startup")||it.startsWith("freshQ04Owned")})
        val mode=checkNotNull(args.getString("freshQ04SuccessorOwnedCleanupMode"));require(mode in setOf("query","delete"))
        val operation=checkNotNull(args.getString("cleanupOperationId"));require(java.util.UUID.fromString(operation).toString()==operation)
        val manifestSha=checkNotNull(args.getString("rootCleanupManifestSha256"));hash(manifestSha)
        val run=cleanupCase.run;val rootAlias="q02-real-"+run
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance"&&instrumentation.context.packageName==context.packageName+".test")
        require(context.applicationInfo.uid==Process.myUid()&&context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        val base=context.filesDir.canonicalFile.toPath()
        SuccessorBACleanupDirectory.openApplicationFiles(context).use { }
        val staging=base.resolve("acceptance/fresh-q04-successor-owned-cleanup-v1");val input=staging.resolve(operation)
        listOf(base.resolve("acceptance"),staging,input,staging.resolve("once")).forEach {owned(it,true)}
        val raw=bytes(input.resolve("root-review.json"),131072);require(sha(raw)==manifestSha)
        val v=JSONObject(String(raw,Charsets.UTF_8))
        exact(v,"version","kind","enabled","rootReviewed","allowedMode","taskId","operationId","issuedAtEpochMs","expiresAtEpochMs",
            "run","rootAlias","budgetOperationId","leaseId","target","installation","nativePrepared","nativeClosed","originalProcess",
            "bindingBefore","bindingAfter","revokeReview","archive","inventory","scopeEvidence","preDeleteQuery","nativeCase","nativePlan","nativeAuthority","sourceReviewRef")
        require(number(v,"version")==1L&&string(v,"kind")=="root-fresh-q04-successor-owned-cleanup-review-v1"&&bool(v,"enabled")&&bool(v,"rootReviewed"))
        require(string(v,"taskId")=="android-parallel-20261112-01a1cd5e"&&string(v,"operationId")==operation)
        require(string(v,"run")==run&&string(v,"rootAlias")==rootAlias&&string(v,"budgetOperationId")==cleanupCase.budget&&string(v,"leaseId")==cleanupCase.lease)
        target(v.getJSONObject("target"));ref(v.getJSONObject("sourceReviewRef"))
        val allowedMode=string(v,"allowedMode");require(allowedMode in setOf("query","delete"));if(mode=="delete")require(allowedMode=="delete")
        val now=System.currentTimeMillis();val issued=number(v,"issuedAtEpochMs");val expires=number(v,"expiresAtEpochMs")
        require(issued<=now&&expires in issued+1..issued+300000)
        require(issued in now-60000..now&&expires>now) // Both QUERY and DELETE require a fresh finite Root scope.
        val install=v.getJSONObject("installation");exact(install,"package","uid","api","deviceSha256","appSha256","runnerSha256","signerSha256")
        require(string(install,"package")==context.packageName&&number(install,"uid")==Process.myUid().toLong()&&number(install,"api")==android.os.Build.VERSION.SDK_INT.toLong())
        for(name in listOf("deviceSha256","appSha256","runnerSha256","signerSha256"))hash(string(install,name))
        require(sha(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID).toByteArray(Charsets.UTF_8))==string(install,"deviceSha256"))
        require(shaFile(File(context.applicationInfo.sourceDir))==string(install,"appSha256"))
        require(shaFile(File(context.packageManager.getApplicationInfo(context.packageName+".test",0).sourceDir))==string(install,"runnerSha256"))
        for(name in listOf(context.packageName,context.packageName+".test")) {
            val signatures=checkNotNull(context.packageManager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).apkContentsSigners
            require(signatures.size==1&&sha(signatures.single().toByteArray())==string(install,"signerSha256"))
        }
        val originalPlan=artifact(input,v.getJSONObject("nativePlan"),"original-plan.json")
        val originalAuthority=artifact(input,v.getJSONObject("nativeAuthority"),"original-root-authority.json")
        val case=v.getJSONObject("nativeCase")
        exact(case,"caseKind","authorityKind","nativeMethod","nativeFlag","nonce","planSha256","authoritySha256")
        require(string(case,"caseKind")==cleanupCase.caseKind&&string(case,"authorityKind")==cleanupCase.authorityKind)
        require(string(case,"nativeMethod")==cleanupCase.nativeMethod&&string(case,"nativeFlag")==cleanupCase.nativeFlag&&string(case,"nonce")==cleanupCase.nonce)
        require(string(case,"planSha256")==string(v.getJSONObject("nativePlan"),"sha256")&&string(case,"authoritySha256")==string(v.getJSONObject("nativeAuthority"),"sha256"))
        require(originalPlan.get("caseKind")==cleanupCase.caseKind&&originalAuthority.get("kind")==cleanupCase.authorityKind)
        for(doc in listOf(originalPlan,originalAuthority)) {
            require(doc.get("run")==run&&doc.get("profileRun")==run&&doc.get("leaseId")==cleanupCase.lease&&doc.get("budgetOperationId")==cleanupCase.budget&&doc.get("nonce")==cleanupCase.nonce)
            require(doc.opt("executionAuthorized")==true);target(doc.getJSONObject("target"));require(doc.get("threadId")=="01a1d0bb-e7b3-7c51-92d9-98ab96329fb7")
        }
        require(originalAuthority.opt("rootConfirmed")==true&&originalAuthority.opt("preparedOnly")==false&&originalAuthority.getInt("maxNativeAttempts")==1)
        require(originalPlan.get("rootAuthoritySha256")==string(case,"authoritySha256"))
        val core=JSONObject(originalPlan.toString()).also {it.remove("rootAuthoritySha256")};val coreText=string(originalAuthority,"planCoreJson")
        require(sha(coreText.toByteArray(Charsets.UTF_8))==originalAuthority.get("planCoreSha256"));same(JSONObject(coreText),core)
        val prepared=artifact(input,v.getJSONObject("nativePrepared"),"profile-prepared.json")
        val closed=artifact(input,v.getJSONObject("nativeClosed"),"profile-resource-state.json")
        val parent=base.resolve("q02-real-dev-profiles-v1");owned(parent,true);val root=parent.resolve(run)
        val allowedFields=setOf("storageOwnerRun","existingOwnedResume","run","leaseId","root","alias","pid","processNonce","newBindingIntent","created","destroyed","pairingAttempted","closed","controlHostId","localMappingSha256","target","ordinaryStorageMounted","runtimeNoBackupRoot","activityNoBackupRoot","sessionBoundToOwnedStorage","controlHostMappingExternal","fixturePortsInstalled","nativeSystemIntegrations","retentionReason","retainUntil","writerCloseReturned","startupSpecifiedPermissionRestored")
        for(proof in listOf(prepared,closed)) {
            require(proof.keys().asSequence().toSet().all {it in allowedFields})
            require(string(proof,"storageOwnerRun")==run&&string(proof,"run")==run&&string(proof,"alias")==rootAlias&&string(proof,"leaseId")==cleanupCase.lease)
            target(proof.getJSONObject("target"));hash(string(proof,"localMappingSha256"))
            require(string(proof,"controlHostId")=="4bcca14d-211e-41ee-bfef-49698d9ce5ca") // Actual approved control host differs from the paired target Host.
            require(listOf("existingOwnedResume","ordinaryStorageMounted","fixturePortsInstalled","nativeSystemIntegrations").all {!bool(proof,it)})
            require(File(string(proof,"root")).canonicalFile.toPath()==root&&File(string(proof,"root")).absoluteFile.toPath()==root)
            require(File(string(proof,"runtimeNoBackupRoot")).canonicalFile.toPath()==root.resolve("no-backup"))
            require(File(string(proof,"activityNoBackupRoot")).canonicalFile.toPath()==root.resolve("no-backup"))
        }
        require(listOf("created","destroyed","pairingAttempted","closed").all {!bool(prepared,it)&&bool(closed,it)})
        require(bool(closed,"sessionBoundToOwnedStorage")&&bool(closed,"controlHostMappingExternal")&&bool(closed,"writerCloseReturned")&&bool(closed,"startupSpecifiedPermissionRestored"))
        require(prepared.get("pid")==closed.get("pid")&&prepared.get("processNonce")==closed.get("processNonce")&&prepared.get("localMappingSha256")==closed.get("localMappingSha256"))
        val original=v.getJSONObject("originalProcess");exact(original,"pid","processNonce","writerExited","instrumentationExited","processExited","independentObservationSha256")
        require(number(original,"pid")==number(closed,"pid")&&string(original,"processNonce")==string(closed,"processNonce"))
        require(listOf("writerExited","instrumentationExited","processExited").all {bool(original,it)})
        cleanupCase.originalPid?.let { require(number(original,"pid")==it.toLong()) }
        hash(string(original,"independentObservationSha256"));noOriginalPid(number(original,"pid").toInt())
        val processes=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
        require(processes!=null&&processes.any {it.uid==Process.myUid()&&it.pid==Process.myPid()}&&processes.none {it.uid==Process.myUid()&&it.pid!=Process.myPid()})
        val intent=closed.getJSONObject("newBindingIntent")
        target(intent.getJSONObject("target"));require(bool(intent,"attemptedOnce"))
        val mobile=string(intent,"mobileDeviceId");require(mobile.startsWith("mobile-")&&java.util.UUID.fromString(mobile.removePrefix("mobile-")).toString()==mobile.removePrefix("mobile-"))
        val publicSha=string(intent,"mobilePublicKeySha256");hash(publicSha);hash(string(intent,"pairingIntentSha256"))
        val before=artifact(input,v.getJSONObject("bindingBefore"),"bindings-before-redacted.json")
        val after=artifact(input,v.getJSONObject("bindingAfter"),"bindings-after-redacted.json")
        for(document in listOf(before,after)) {
            exact(document,"kind","rootReviewed","complete","sourceRef","bindings")
            require(string(document,"kind")=="root-codex-bindings-redacted-projection-v1"&&bool(document,"rootReviewed")&&bool(document,"complete"));ref(document.getJSONObject("sourceRef"))
            val rows=document.getJSONArray("bindings").objects();require(rows.size in 1..256)
            require(rows.map {string(it,"publicKeySha256")}.distinct().size==rows.size)
            for(row in rows) {
                exact(row,"target","publicKeySha256","tokenSha256","revoked","hostKeyAuthorized")
                com.agenthub.core.DomainContract.validate(row.getJSONObject("target").toString(),"target").let {require(it==null)}
                hash(string(row,"publicKeySha256"));hash(string(row,"tokenSha256"));bool(row,"revoked")
                require(row.get("hostKeyAuthorized")==JSONObject.NULL||row.get("hostKeyAuthorized") is Boolean)
            }
        }
        if(cleanupCase.apiReceiptProof) {
            verifyIndependent968ApiReceipt(before,after,artifact(input,v.getJSONObject("revokeReview"),"revoke-root-review.json"),intent,case,cleanupCase,mobile,publicSha)
        } else {
            val beforeRows=before.getJSONArray("bindings").objects();val afterRows=after.getJSONArray("bindings").objects()
            require(beforeRows.size==afterRows.size)
            val old=beforeRows.single {string(it,"publicKeySha256")==publicSha};val fresh=afterRows.single {string(it,"publicKeySha256")==publicSha}
            target(old.getJSONObject("target"));target(fresh.getJSONObject("target"));require(!bool(old,"revoked")&&bool(fresh,"revoked"))
            val restored=JSONObject(after.toString());restored.getJSONArray("bindings").objects().single {string(it,"publicKeySha256")==publicSha}.put("revoked",false)
            // sourceRef identifies two different actual files; the complete binding rows themselves must be identical after reversing the one bit.
            same(restored.getJSONArray("bindings"),before.getJSONArray("bindings"))
            val revoke=artifact(input,v.getJSONObject("revokeReview"),"revoke-root-review.json")
            exact(revoke,"kind","rootReviewed","target","mobileDeviceId","mobilePublicKeySha256","pairingIntentSha256","persistedBeforeRef","persistedAfterRef","allOtherRowsPreserved","onlyChangedField","revocationConfirmed","phoneProfileAndKeyDeleted","run","budgetOperationId","leaseId","planSha256","authoritySha256","clientOperationId","operationReceiptRef","nativeTerminalRef","resourceConfirmationRef")
            require(string(revoke,"kind")=="root-fresh-q04-successor-codex-revoke-review-v1"&&bool(revoke,"rootReviewed")&&bool(revoke,"allOtherRowsPreserved")&&bool(revoke,"revocationConfirmed")&&!bool(revoke,"phoneProfileAndKeyDeleted"))
            require(revoke.get("run")==run&&revoke.get("budgetOperationId")==cleanupCase.budget&&revoke.get("leaseId")==cleanupCase.lease&&revoke.get("planSha256")==case.get("planSha256")&&revoke.get("authoritySha256")==case.get("authoritySha256"))
            val revokeOperation=string(revoke,"clientOperationId");require(java.util.UUID.fromString(revokeOperation).toString()==revokeOperation)
            for(k in listOf("operationReceiptRef","nativeTerminalRef","resourceConfirmationRef"))ref(revoke.getJSONObject(k))
            target(revoke.getJSONObject("target"));require(string(revoke,"onlyChangedField")=="revoked"&&string(revoke,"mobileDeviceId")==mobile&&string(revoke,"mobilePublicKeySha256")==publicSha&&revoke.get("pairingIntentSha256")==intent.get("pairingIntentSha256"))
            same(revoke.getJSONObject("persistedBeforeRef"),before.getJSONObject("sourceRef"));same(revoke.getJSONObject("persistedAfterRef"),after.getJSONObject("sourceRef"))
        }
        val scope=v.getJSONObject("scopeEvidence");exact(scope,"allOtherProfilesPreserved","nativeEvidencePreserved","rootReviewed","sourceGraphSha256")
        require(listOf("allOtherProfilesPreserved","nativeEvidencePreserved","rootReviewed").all {bool(scope,it)});hash(string(scope,"sourceGraphSha256"))
        val archived=linkedMapOf<String,JSONObject>()
        if(allowedMode=="delete") {
            val archive=v.getJSONObject("archive");exact(archive,"allFilesReadBackOutsideProfile","originalNativeEvidence","scopeEvidence","profileFiles")
            require(bool(archive,"allFilesReadBackOutsideProfile"));archiveRef(archive.getJSONObject("originalNativeEvidence"));archiveRef(archive.getJSONObject("scopeEvidence"))
            val archiveRows=archive.getJSONArray("profileFiles").objects()
            for(row in archiveRows){exact(row,"path","ref");archiveRef(row.getJSONObject("ref"));require(archived.put(string(row,"path"),row.getJSONObject("ref"))==null)}
        } else require(v.isNull("archive")) // First QUERY does not claim that an archive or DELETE authority already exists.
        val inventory=v.getJSONArray("inventory").objects().map {entry->
            exact(entry,"path","kind","bytes","sha256");val path=string(entry,"path");val dir=string(entry,"kind")=="directory";require(dir||string(entry,"kind")=="file")
            val size=number(entry,"bytes");val h=string(entry,"sha256")
            if(dir)require(size==0L&&h.isEmpty())else {hash(h);require(size in 29..1048576);if(allowedMode=="delete")require(archived[path]?.let {number(it,"bytes")==size&&string(it,"sha256")==h}==true)}
            FreshQ04CleanupEntry(path,dir,size,h)
        }
        require(inventory.map {it.path}.distinct().size==inventory.size)
        require(inventory.filter {it.directory}.map {it.path}.toSet()==setOf("","no-backup","no-backup/q02-session","files","cache","cache/migration-ui-v1","code-cache"))
        val files=inventory.filterNot {it.directory};require(files.size in 1..32&&files.sumOf {it.bytes} in 1..1048576)
        require(files.all {it.path.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin"))})
        require(files.none {it.path.endsWith(sha("migration-active-generation-v1".toByteArray(Charsets.UTF_8))+".bin")})
        if(allowedMode=="delete")require(archived.keys==files.map {it.path}.toSet())
        val plan=FreshQ04CleanupPlan(run,inventory)
        val tree=FreshQ04CleanupTree({SuccessorBACleanupDirectory.openApplicationFiles(context).use {it.directory("q02-real-dev-profiles-v1")}},run)
        val once=Once(staging.resolve("once").resolve("$run.intent"));val prior=once.prior();require(prior==null||prior==manifestSha)
        if(allowedMode=="delete") {
            val query=artifact(input,v.getJSONObject("preDeleteQuery"),"pre-delete-query.json")
            exact(query,"kind","run","operationId","manifestSha256","mode","allowedMode","intentPresent","profileAbsent","remainingFiles","rootAliasPresent","completeAtPublicApiObservation","absoluteHardwareErasureProven","allUnnamedDerivedKeysProvenAbsent","otherAliasesEnumerated")
            require(string(query,"kind")=="fresh-q04-successor-owned-cleanup-native-observation-v1"&&string(query,"run")==run&&string(query,"mode")=="query")
            require(!bool(query,"intentPresent")&&!bool(query,"profileAbsent")&&bool(query,"rootAliasPresent")&&!bool(query,"completeAtPublicApiObservation"))
            require(strings(query.getJSONArray("remainingFiles")).toSet()==files.map {it.path}.toSet())
            hash(string(query,"manifestSha256"));require(string(query,"operationId")!=operation)
            if(mode=="delete")require(prior==null&&tree.remaining(plan,false).toSet()==inventory.toSet())
        } else require(v.get("preDeleteQuery")==JSONObject.NULL)
        tree.remaining(plan,prior!=null) // Actual descriptor inventory/hash validation precedes KeyStore access.
        // Everything above is validated before the sole finite KeyStore alias is observed.
        val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        if(mode=="delete") {
            require(keys.containsAlias(rootAlias)&&System.currentTimeMillis()<expires)
            once.claim(manifestSha) // Permanent CREATE_NEW marker before any file/key mutation.
            tree.remove(plan)
            keys.deleteEntry(rootAlias) // No enumeration, derived alias scan, or arbitrary alias parameter.
        }
        val remaining=tree.remaining(plan,prior!=null||mode=="delete");val aliasPresent=keys.containsAlias(rootAlias)
        val intentPresent=once.prior()!=null;val complete=mode=="delete"&&allowedMode=="delete"&&intentPresent&&remaining.isEmpty()&&!aliasPresent
        val report=JSONObject().put("kind","fresh-q04-successor-owned-cleanup-native-observation-v1").put("run",run).put("operationId",operation).put("manifestSha256",manifestSha)
            .put("mode",mode).put("allowedMode",allowedMode).put("intentPresent",intentPresent).put("profileAbsent",remaining.isEmpty())
            .put("remainingFiles",JSONArray(remaining.filterNot {it.directory}.map {it.path})).put("rootAliasPresent",aliasPresent).put("completeAtPublicApiObservation",complete)
            .put("absoluteHardwareErasureProven",false).put("allUnnamedDerivedKeysProvenAbsent",false).put("otherAliasesEnumerated",false)
        instrumentation.sendStatus(73,Bundle().apply {putString("freshQ04SuccessorOwnedCleanupObservation",report.toString())})
        if(mode=="delete")require(complete) {"Partial/unknown delete: query this exact manifest and original run; never replay"}
    }

}

private class SuccessorBACleanupDirectory private constructor(private val held: ParcelFileDescriptor,private val device: Long,
    private val applicationFilesFoundation: Boolean = false): FreshQ04CleanupTree.Directory {
    private val root get()="/proc/self/fd/${held.fd}"
    private fun path(name: String): String {
        require(name.matches(Regex("[a-zA-Z0-9._-]+")) && name !in setOf(".",".."));return "$root/$name"
    }
    private fun checked(st: android.system.StructStat,allowDirectory: Boolean,applicationFilesSelf: Boolean = false): FreshQ04CleanupTree.Stat {
        require(st.st_dev==device && st.st_uid==Process.myUid() && st.st_gid==Process.myUid())
        val dir=OsConstants.S_ISDIR(st.st_mode);val file=OsConstants.S_ISREG(st.st_mode)
        require(if(dir) allowDirectory && F73FoundationDirectoryMode.admits(st.st_mode,st.st_uid,st.st_gid,Process.myUid(),true,OsConstants.S_ISLNK(st.st_mode),applicationFilesSelf)
            else file && st.st_nlink==1L && st.st_mode and 0xfff==0x180)
        // Directory size/mtime naturally change during deletion; identity stays device+inode.
        return FreshQ04CleanupTree.Stat((if(dir) "${st.st_dev}:${st.st_ino}" else "${st.st_dev}:${st.st_ino}:${st.st_mode}:${st.st_uid}:${st.st_gid}:${st.st_nlink}:${st.st_size}:${st.st_mtim.tv_sec}:${st.st_mtim.tv_nsec}:${st.st_ctim.tv_sec}:${st.st_ctim.tv_nsec}"),dir,file,OsConstants.S_ISLNK(st.st_mode),if(dir)0 else st.st_size)
    }
    override fun names(): Set<String> {
        checked(Os.fstat(held.fileDescriptor),true,applicationFilesSelf=applicationFilesFoundation)
        Os.fsync(held.fileDescriptor) // Capability preflight before any signed-file/key deletion.
        val all=checkNotNull(File(root).list());require(all.distinct().size==all.size);return all.toSet()
    }
    override fun stat(name: String)=checked(Os.lstat(path(name)),true)
    override fun directory(name: String): SuccessorBACleanupDirectory {
        val before=stat(name);require(before.directory && !before.symlink)
        val fd=Os.open(path(name),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        val dup=try {ParcelFileDescriptor.dup(fd)}finally{Os.close(fd)}
        try {require(checked(Os.fstat(dup.fileDescriptor),true)==before && stat(name)==before);return SuccessorBACleanupDirectory(dup,device)}
        catch(e: Throwable){dup.close();throw e}
    }
    override fun hash(name: String,expected: FreshQ04CleanupTree.Stat): String {
        require(expected.regular && !expected.symlink && expected.bytes in 1..1_048_576)
        val fd=Os.open(path(name),OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try {
            require(checked(Os.fstat(fd),false)==expected)
            val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);var count=0L
            while(true){val n=Os.read(fd,buffer,0,buffer.size);if(n==0)break;require(n>0);count+=n;require(count<=expected.bytes);digest.update(buffer,0,n)}
            require(count==expected.bytes && checked(Os.fstat(fd),false)==expected && stat(name)==expected)
            return digest.digest().joinToString("") {"%02x".format(it)}
        } finally {Os.close(fd)}
    }
    override fun unlink(name: String,expected: FreshQ04CleanupTree.Stat) {
        require(expected.regular && !expected.symlink && stat(name)==expected);Os.remove(path(name));Os.fsync(held.fileDescriptor)
        require(name !in names())
    }
    override fun rmdir(name: String,expected: FreshQ04CleanupTree.Stat) {
        require(expected.directory && !expected.symlink && stat(name)==expected);Os.remove(path(name));Os.fsync(held.fileDescriptor)
        require(name !in names())
    }
    override fun close()=held.close()
    companion object {
        /** This is the sole foundation entry: no arbitrary Path can request broad permissions. */
        fun openApplicationFiles(context: Context): SuccessorBACleanupDirectory {
            require(context===InstrumentationRegistry.getInstrumentation().targetContext)
            require(BuildConfig.DEBUG && context.packageName=="com.easyai.agenthub.android.acceptance" && context.applicationInfo.uid==Process.myUid())
            require(!Files.isSymbolicLink(context.filesDir.toPath()))
            val path=context.filesDir.canonicalFile.toPath()
            val before=Os.lstat(path.toString())
            require(F73FoundationDirectoryMode.admits(before.st_mode,before.st_uid,before.st_gid,Process.myUid(),OsConstants.S_ISDIR(before.st_mode),OsConstants.S_ISLNK(before.st_mode),true))
            val fd=Os.open(path.toString(),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            val dup=try {ParcelFileDescriptor.dup(fd)}finally{Os.close(fd)}
            val result=SuccessorBACleanupDirectory(dup,before.st_dev,applicationFilesFoundation=true)
            try {
                val actual=Os.fstat(dup.fileDescriptor);val current=Os.lstat(path.toString())
                require(actual.st_dev==before.st_dev && actual.st_ino==before.st_ino && current.st_dev==before.st_dev && current.st_ino==before.st_ino)
                require(current.st_mode==actual.st_mode && current.st_uid==actual.st_uid && current.st_gid==actual.st_gid)
                require(result.checked(actual,true,applicationFilesSelf=true).directory)
                return result
            } catch(e: Throwable){dup.close();throw e}
        }
    }
}

