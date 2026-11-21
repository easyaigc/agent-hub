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

/** Not an Intent/receiver/service. No execution is enabled by installing this test runner. */
class F73OwnedProfileCleanupTest {
    private val p = F73OwnedCleanupPolicy
    private fun exact(v: JSONObject, vararg names: String) { require(v.keys().asSequence().toSet()==names.toSet()) }
    private fun string(v: JSONObject, name: String): String = (v.get(name) as? String) ?: error("Non-string $name")
    private fun bool(v: JSONObject, name: String): Boolean = (v.get(name) as? Boolean) ?: error("Non-boolean $name")
    private fun number(v: JSONObject,name: String): Long {
        val raw=v.get(name);require(raw is Int || raw is Long);return (raw as Number).toLong()
    }
    private fun scope(v: JSONObject): F73OwnedCleanupPolicy.Scope {
        exact(v,"agentId","hostId","workspaceId","runtimeId","account")
        val a=v.getJSONObject("account");exact(a,"authority","subject")
        return F73OwnedCleanupPolicy.Scope(string(v,"agentId"),string(v,"hostId"),string(v,"workspaceId"),
            string(v,"runtimeId"),string(a,"authority"),string(a,"subject"))
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
        require(raw.size.toLong()==number(v,"bytes") && p.sha(raw)==string(v,"sha256"))
        return JSONObject(String(raw,Charsets.UTF_8))
    }
    private fun archiveRef(v: JSONObject) {
        exact(v,"evidenceId","bytes","sha256","readbackSha256","outsideProfile","rootVerified")
        require(string(v,"evidenceId").matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(number(v,"bytes")>0);p.hash(string(v,"sha256"));require(string(v,"readbackSha256")==string(v,"sha256"))
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
    private fun lanScope(v: JSONObject): F73OwnedCleanupPolicy.Scope {
        exact(v,"account","desktopDeviceId","workspaceId","runtimeId");val a=v.getJSONObject("account");exact(a,"authority","subject")
        return F73OwnedCleanupPolicy.Scope("easywork",string(v,"desktopDeviceId"),string(v,"workspaceId"),string(v,"runtimeId"),string(a,"authority"),string(a,"subject"))
    }
    private fun noOriginalPid(pid: Int) {
        require(pid>1 && pid!=Process.myPid())
        try { Os.stat("/proc/$pid");error("Original PID exists or was reused; preserve") }
        catch(e: ErrnoException) { require(e.errno==OsConstants.ENOENT) }
    }
    private class Once(private val file: Path): F73OwnedCleanupPolicy.Journal {
        override fun prior(): String? {
            if(Files.notExists(file,NOFOLLOW_LINKS))return null
            require(!Files.isSymbolicLink(file) && Files.isRegularFile(file,NOFOLLOW_LINKS))
            val st=Os.lstat(file.toString());require(st.st_uid==Process.myUid() && st.st_gid==Process.myUid() && st.st_nlink==1L && st.st_mode and 0xfff==0x180 && st.st_size==65L)
            val raw=Files.newByteChannel(file,setOf(StandardOpenOption.READ,NOFOLLOW_LINKS)).use { channel ->
                val b=ByteBuffer.allocate(66);while(channel.read(b)>0){};b.flip();ByteArray(b.remaining()).also {b.get(it)}
            }
            require(raw.size==65 && raw.last()==10.toByte());return String(raw,Charsets.US_ASCII).trimEnd('\n').also { F73OwnedCleanupPolicy.hash(it) }
        }
        override fun claim(manifestSha256: String) {
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
    private fun validateProfileProofs(run: String, alias: String, prepared: JSONObject, closed: JSONObject,
        target: F73OwnedCleanupPolicy.Scope, root: Path) {
        val proofFields=setOf("storageOwnerRun","existingOwnedResume","run","leaseId","root","alias","pid","processNonce","newBindingIntent","created","destroyed","pairingAttempted","closed","controlHostId","localMappingSha256","target","ordinaryStorageMounted","runtimeNoBackupRoot","activityNoBackupRoot","sessionBoundToOwnedStorage","controlHostMappingExternal","fixturePortsInstalled","nativeSystemIntegrations","retentionReason")
        for(proof in listOf(prepared,closed)) {
            if(run !in setOf(p.KNOWN_THIRD_F73,p.KNOWN_FOURTH_F73)) require(proof.keys().asSequence().toSet().all {it in proofFields})
            require(string(proof,"run")==run && string(proof,"storageOwnerRun")==run && string(proof,"alias")==alias)
            require(!bool(proof,"existingOwnedResume") && !bool(proof,"ordinaryStorageMounted") && !bool(proof,"nativeSystemIntegrations") && !bool(proof,"fixturePortsInstalled"))
            require(File(string(proof,"root")).canonicalFile.toPath()==root)
            require(File(string(proof,"runtimeNoBackupRoot")).canonicalFile.toPath()==root.resolve("no-backup"))
            require(File(string(proof,"activityNoBackupRoot")).canonicalFile.toPath()==root.resolve("no-backup"))
            require(scope(proof.getJSONObject("target"))==target)
            p.uuid(string(proof,"leaseId"));p.uuid(string(proof,"processNonce"))
        }
        require(listOf("created","destroyed","pairingAttempted","closed").all { !bool(prepared,it) && bool(closed,it) })
        require(bool(closed,"sessionBoundToOwnedStorage"))
        require(prepared.get("pid")==closed.get("pid") && prepared.get("processNonce")==closed.get("processNonce") && prepared.get("leaseId")==closed.get("leaseId"))
        if(run in setOf(p.KNOWN_THIRD_F73,p.KNOWN_FOURTH_F73)) {
            exact(prepared,*(proofFields-setOf("newBindingIntent","retentionReason")).toTypedArray())
            require(!bool(prepared,"sessionBoundToOwnedStorage"))
            for(proof in listOf(prepared,closed)) require(bool(proof,"controlHostMappingExternal"))
            require(string(prepared,"controlHostId").isNotEmpty() && prepared.get("controlHostId")==closed.get("controlHostId"))
            p.hash(string(closed,"localMappingSha256"));require(prepared.get("localMappingSha256")==closed.get("localMappingSha256"))
            validateThirdClosed(closed,proofFields)
            val intent=closed.getJSONObject("newBindingIntent")
            exact(intent,"target","mobileDeviceId","mobilePublicKeySha256","pairingIntentSha256","attemptedOnce","offerId","remoteLookupKey")
            require(scope(intent.getJSONObject("target"))==target && bool(intent,"attemptedOnce"))
            val device=string(intent,"mobileDeviceId");require(device.startsWith("mobile-"));p.uuid(device.removePrefix("mobile-"))
            p.hash(string(intent,"mobilePublicKeySha256"));p.hash(string(intent,"pairingIntentSha256"))
            require(string(intent,"offerId").isNotEmpty() && string(intent,"remoteLookupKey")=="offerId + mobileDeviceId/publicKey")
        }
    }
    private fun validateThirdClosed(closed: JSONObject, profileFields: Set<String>) {
        // Exact fields emitted by F73UsageActivityLaunch.cleanup(), not a caller's closed=true summary.
        exact(closed,*(profileFields+setOf("observedElapsed","controllerBeganElapsed","workDeadlineElapsed","cleanupDeadlineElapsed",
            "profilePreparedObserved","ownedActivityObserved","lastOwnedStage","ambiguousOwnedActivity","finishRequested",
            "ownedResourcesClosed","destroyedObserved","writerCloseReturned","closeJobCompleted","lifecycleCallbackRemoved",
            "startWorkerAlive","temporaryPermission","cleanupErrors","workMain","cleanupMain","timeoutIsNotCancellation",
            "startRequestCancelled","profileDeletionAttempted")).toTypedArray())
        require(listOf("profilePreparedObserved","ownedActivityObserved","finishRequested","ownedResourcesClosed","destroyedObserved",
            "writerCloseReturned","closeJobCompleted","lifecycleCallbackRemoved","timeoutIsNotCancellation").all {bool(closed,it)})
        require(listOf("ambiguousOwnedActivity","startWorkerAlive","startRequestCancelled","profileDeletionAttempted").none {bool(closed,it)})
        require(string(closed,"lastOwnedStage")=="DESTROYED" && closed.getJSONArray("cleanupErrors").length()==0)
        require(string(closed,"retentionReason")=="Controller preserves private evidence and reconciles the exact binding/profile; no automatic replay or cleanup")
        val began=number(closed,"controllerBeganElapsed");val work=number(closed,"workDeadlineElapsed")
        val cleanup=number(closed,"cleanupDeadlineElapsed")
        require(began in 0..Long.MAX_VALUE-235_000 && work in began+1..began+210_000)
        require(cleanup==minOf(began+235_000,work+20_000) && number(closed,"observedElapsed")>=began)
        fun callbacks(name: String): Set<String> {
            val states=closed.getJSONArray(name);require(states.length() in 1..32)
            return (0 until states.length()).map {index ->
                val state=states.getJSONObject(index);exact(state,"label","phase","waitExpired")
                require(string(state,"label").isNotEmpty() && string(state,"phase")=="RETURNED" && !bool(state,"waitExpired"))
                string(state,"label")
            }.toSet()
        }
        callbacks("workMain")
        require(callbacks("cleanupMain").containsAll(setOf("finish-only-owned-activity","schedule-original-profile-close","capture-owned-profile","unregister-owned-callback")))
        val permission=closed.getJSONObject("temporaryPermission")
        exact(permission,"permission","adoptCallArguments","readbackScope","fullAdoptedPermissionSetReadback","fullAdoptedPermissionSet",
            "connected","adoptEntered","adoptEnded","adoptReturned","startEntered","startEnteredMeaning","startReturned","workerEnded",
            "newStartGateClosed","dropClaimed","dropReturned","dropEnded","specifiedPermissionRestored","before","during","after")
        val name="android.permission.START_ACTIVITIES_FROM_BACKGROUND"
        require(string(permission,"permission")==name && strings(permission.getJSONArray("adoptCallArguments"))==listOf(name))
        require(string(permission,"readbackScope")=="specified-permission-only-public-api" &&
            string(permission,"fullAdoptedPermissionSetReadback")=="unavailable-in-public-sdk" && permission.get("fullAdoptedPermissionSet")===JSONObject.NULL)
        require(string(permission,"startEnteredMeaning")=="atomic admission before Context.startActivity, not Binder acknowledgement")
        require(listOf("connected","adoptEntered","adoptEnded","adoptReturned","startEntered","startReturned","workerEnded",
            "newStartGateClosed","dropClaimed","dropReturned","dropEnded","specifiedPermissionRestored").all {bool(permission,it)})
        for(stage in listOf("before","during","after")) {
            val observation=permission.getJSONObject(stage);exact(observation,"shellGranted","selfGranted")
            require(bool(observation,"shellGranted") && bool(observation,"selfGranted")== (stage=="during"))
        }
    }
    private fun validateLanTransition(run: String, binding: JSONObject, before: JSONObject, after: JSONObject,
        revoke: JSONObject, target: F73OwnedCleanupPolicy.Scope) {
        for(document in listOf(before,after)) {
            exact(document,"version","enabled","target","bindings");require(number(document,"version")==1L && bool(document,"enabled") && lanScope(document.getJSONObject("target"))==target)
            val bindingCount=when(run){p.KNOWN_FOURTH_F73->4;p.KNOWN_THIRD_F73->3;else->2}
            val list=document.getJSONArray("bindings");require(list.length() in 1..bindingCount)
            if(run in setOf(p.KNOWN_THIRD_F73,p.KNOWN_FOURTH_F73)) {
                require(list.length()==bindingCount) // Historical tombstones plus exactly this new binding.
                require((0 until list.length()).map {string(list.getJSONObject(it),"bindingId")}.distinct().size==bindingCount)
                for(i in 0 until list.length()) {
                    val b=list.getJSONObject(i)
                    if(string(b,"bindingId")!=string(binding,"bindingId")) require(string(b,"status")=="revoked")
                }
            }
            val group=mutableListOf<String>()
            for(i in 0 until list.length()) {
                val b=list.getJSONObject(i)
                exact(b,"bindingId","account","desktopDeviceId","mobileDeviceId","desktopPublicKey","mobilePublicKey","target","status","createdAt","confirmedAt")
                val bScope=lanScope(b.getJSONObject("target"));require(bScope==target)
                require(string(b,"desktopDeviceId")==target.host && canonical(b.getJSONObject("account"))==canonical(b.getJSONObject("target").getJSONObject("account")))
                if(string(b,"mobileDeviceId")==string(binding,"mobileDeviceId")) {
                    group+=string(b,"bindingId")
                    val public=string(b,"mobilePublicKey");require(public.matches(Regex("[A-Za-z0-9_-]+")))
                    val decoded=android.util.Base64.decode(public,android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                    require(decoded.size==32 && p.sha(decoded)==string(binding,"mobilePublicKeySha256"))
                }
            }
            require(group==strings(binding.getJSONArray("groupBindingIds")))
        }
        val oldBindings=before.getJSONArray("bindings");val newBindings=after.getJSONArray("bindings")
        require(oldBindings.length()==newBindings.length() && number(revoke,"bindingsTotal")==newBindings.length().toLong())
        val restored=JSONObject(after.toString());var changed=0
        for(i in 0 until newBindings.length()) {
            val old=oldBindings.getJSONObject(i);val fresh=newBindings.getJSONObject(i)
            require(old.get("bindingId")==fresh.get("bindingId"))
            if(string(fresh,"bindingId")==string(binding,"bindingId")) {
                require(string(old,"status")=="active" && string(fresh,"status")=="revoked");restored.getJSONArray("bindings").getJSONObject(i).put("status","active");changed++
            }
        }
        require(changed==1 && canonical(restored)==canonical(before))
    }
    @Test fun removeOnlyRootReviewedNewF73ProfileOrQueryOriginalAttempt() {
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("f73OwnedCleanupReady")=="1") { "Default disabled; no KeyStore access" }
        require(args.getString("nativeForeground")=="0" && args.getString("nativeThreadSnapshots")=="0")
        val mode=args.getString("f73OwnedCleanupMode") ?: "query";require(mode in setOf("query","delete"))
        val operation=checkNotNull(args.getString("cleanupOperationId"));p.uuid(operation)
        val manifestSha=checkNotNull(args.getString("rootCleanupManifestSha256"));p.hash(manifestSha)
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        require(BuildConfig.DEBUG && context.packageName=="com.easyai.agenthub.android.acceptance")
        require(context.applicationInfo.uid==Process.myUid() && context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        val base=context.filesDir.canonicalFile.toPath()
        F73AndroidDirectory.openApplicationFiles(context).use { } // Validate only the trusted foundation itself.
        val acceptance=base.resolve("acceptance");val staging=acceptance.resolve("f73-owned-cleanup-v1");val input=staging.resolve(operation)
        listOf(acceptance,staging,input,staging.resolve("once")).forEach {owned(it,true)}
        val raw=bytes(input.resolve("root-review.json"),131_072);require(p.sha(raw)==manifestSha)
        val v=JSONObject(String(raw,Charsets.UTF_8))
        exact(v,"kind","enabled","taskId","operationId","issuedAtEpochMs","expiresAtEpochMs","run","rootAlias","allowlist","installation","target","nativePrepared","nativeClosed","revokeReview","persistedLanBefore","persistedLanAfter","originalProcess","binding","archive","inventory","scopeEvidence",*if(v.has("allowedMode")) arrayOf("allowedMode") else emptyArray())
        require(string(v,"kind")=="f73-owned-cleanup-root-review-v1" && bool(v,"enabled"))
        val allowedMode=if(v.has("allowedMode"))string(v,"allowedMode") else "query"
        require(allowedMode in setOf("query","delete"))
        if(mode=="delete")require(allowedMode=="delete") {"Signed query-only authorization cannot permit deletion"}
        require(string(v,"operationId")==operation)
        val now=System.currentTimeMillis();val issued=number(v,"issuedAtEpochMs");val expires=number(v,"expiresAtEpochMs")
        require(issued<=now && expires in issued+1..issued+300_000)
        // A timed-out delete remains queryable with its exact original, now-expired manifest.
        if(mode=="delete")require(issued in now-60_000..now && expires>now)
        val run=string(v,"run");p.uuid(run);val alias=string(v,"rootAlias")
        val target=scope(v.getJSONObject("target"));require(target.agent=="easywork")
        val install=v.getJSONObject("installation")
        exact(install,"package","uid","api","deviceSha256","appSha256","runnerSha256","signerSha256")
        require(string(install,"package")==context.packageName && number(install,"uid")==Process.myUid().toLong() && number(install,"api")==android.os.Build.VERSION.SDK_INT.toLong())
        for(name in listOf("deviceSha256","appSha256","runnerSha256","signerSha256"))p.hash(string(install,name))
        require(p.sha(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID).toByteArray())==string(install,"deviceSha256"))
        require(shaFile(File(context.applicationInfo.sourceDir))==string(install,"appSha256"))
        require(shaFile(File(context.packageManager.getApplicationInfo(context.packageName+".test",0).sourceDir))==string(install,"runnerSha256"))
        for(name in listOf(context.packageName,context.packageName+".test")) {
            val signatures=checkNotNull(context.packageManager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).apkContentsSigners
            require(signatures.size==1 && p.sha(signatures.single().toByteArray())==string(install,"signerSha256"))
        }
        val prepared=artifact(input,v.getJSONObject("nativePrepared"),"profile-prepared.json")
        val closed=artifact(input,v.getJSONObject("nativeClosed"),"profile-resource-state.json")
        val profileParent=base.resolve("q02-real-dev-profiles-v1");owned(profileParent,true)
        val root=profileParent.resolve(run)
        validateProfileProofs(run,alias,prepared,closed,target,root)
        val original=v.getJSONObject("originalProcess")
        exact(original,"pid","processNonce","writerExited","instrumentationExited","processExited","independentObservationSha256")
        require(number(original,"pid")==number(closed,"pid") && string(original,"processNonce")==string(closed,"processNonce"))
        p.hash(string(original,"independentObservationSha256"));noOriginalPid(number(original,"pid").toInt())
        val ownProcesses=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
        require(ownProcesses!=null && ownProcesses.any {it.pid==Process.myPid() && it.uid==Process.myUid()} && ownProcesses.none {it.uid==Process.myUid() && it.pid!=Process.myPid()})
        val newIntent=closed.getJSONObject("newBindingIntent")
        require(scope(newIntent.getJSONObject("target"))==target && bool(newIntent,"attemptedOnce"))
        val binding=v.getJSONObject("binding")
        exact(binding,"source","deviceAuthorization","bindingId","groupBindingIds","target","mobileDeviceId","mobilePublicKeySha256","offerId","pairingIntentSha256","status","revokeOutcome","persistedAfterSha256","rootRevokeReviewSha256","allOtherBindingsPreserved")
        require(string(binding,"source")=="lan" && binding.isNull("deviceAuthorization") && scope(binding.getJSONObject("target"))==target)
        for(name in listOf("mobileDeviceId","mobilePublicKeySha256","offerId","pairingIntentSha256"))require(binding.get(name)==newIntent.get(name))
        for(name in listOf("mobilePublicKeySha256","pairingIntentSha256","persistedAfterSha256","rootRevokeReviewSha256"))p.hash(string(binding,name))
        require(bool(binding,"allOtherBindingsPreserved"))
        val before=artifact(input,v.getJSONObject("persistedLanBefore"),"lan-before-revoke.json")
        val after=artifact(input,v.getJSONObject("persistedLanAfter"),"lan-after-revoke.json")
        val revoke=artifact(input,v.getJSONObject("revokeReview"),"revoke-root-review.json")
        require(string(v.getJSONObject("persistedLanAfter"),"sha256")==string(binding,"persistedAfterSha256"))
        require(string(v.getJSONObject("revokeReview"),"sha256")==string(binding,"rootRevokeReviewSha256"))
        val preservedFlag=if(revoke.has("allOtherBindingFieldsUnchanged"))"allOtherBindingFieldsUnchanged" else "allOtherBindingFieldsAndOldTombstoneUnchanged"
        exact(revoke,"kind","at","operationId","bindingId","bindingsTotal","onlyChangedField",preservedFlag,"normalGuiApiCallsReported","noReconnectProbe","persistedAfterRef","phoneProfileAndKeyDeleted")
        require(string(revoke,"kind")=="root-independent-persisted-revoke-review-v1" && string(revoke,"bindingId")==string(binding,"bindingId"))
        require(bool(revoke,preservedFlag) && bool(revoke,"noReconnectProbe") && !bool(revoke,"phoneProfileAndKeyDeleted") && number(revoke,"normalGuiApiCallsReported")==1L)
        val persistedRef=revoke.getJSONObject("persistedAfterRef");exact(persistedRef,"path","bytes","sha256")
        require(number(persistedRef,"bytes")==number(v.getJSONObject("persistedLanAfter"),"bytes") && string(persistedRef,"sha256")==string(binding,"persistedAfterSha256"))
        validateLanTransition(run,binding,before,after,revoke,target)
        val scopeEvidence=v.getJSONObject("scopeEvidence")
        exact(scopeEvidence,"originalScopeSha256","nativeInputsSha256","sourceGraphSha256","allOtherProfilesPreserved","nativeEvidencePreserved","sixInputsAlreadyAbsent")
        for(name in listOf("originalScopeSha256","nativeInputsSha256","sourceGraphSha256"))p.hash(string(scopeEvidence,name))
        require(listOf("allOtherProfilesPreserved","nativeEvidencePreserved","sixInputsAlreadyAbsent").all {bool(scopeEvidence,it)})
        val archives=v.getJSONObject("archive");exact(archives,"allFilesReadBackOutsideProfile","originalNativeEvidence","scopeEvidence","profileFiles")
        archiveRef(archives.getJSONObject("originalNativeEvidence"));archiveRef(archives.getJSONObject("scopeEvidence"))
        val archiveFiles=archives.getJSONArray("profileFiles")
        val archiveByPath=(0 until archiveFiles.length()).associate { i ->
            val a=archiveFiles.getJSONObject(i);exact(a,"path","ref");archiveRef(a.getJSONObject("ref"));string(a,"path") to a.getJSONObject("ref")
        };require(archiveByPath.size==archiveFiles.length())
        val inventory=v.getJSONArray("inventory")
        val entries=(0 until inventory.length()).map { i ->
            val e=inventory.getJSONObject(i);exact(e,"path","kind","bytes","sha256")
            val dir=string(e,"kind")=="directory";require(string(e,"kind") in setOf("directory","file"))
            val path=string(e,"path");val n=number(e,"bytes");val h=string(e,"sha256")
            if(dir)require(n==0L && h.isEmpty()) else require(archiveByPath[path]?.let {number(it,"bytes")==n && string(it,"sha256")==h}==true)
            F73OwnedCleanupPolicy.Entry(path,dir,n,h,if(dir) "" else string(archiveByPath.getValue(path),"readbackSha256"))
        }
        require(archiveByPath.keys==entries.filterNot {it.directory}.map {it.path}.toSet())
        val allow=v.getJSONArray("allowlist")
        val allowed=(0 until allow.length()).associate { i -> val a=allow.getJSONObject(i);exact(a,"run","rootAlias");string(a,"run") to string(a,"rootAlias") }
        require(allowed.size==allow.length())
        val plan=F73OwnedCleanupPolicy.Plan(string(v,"taskId"),operation,run,alias,allowed,target,scope(closed.getJSONObject("target")),
            string(binding,"mobileDeviceId"),string(newIntent,"mobileDeviceId"),bool(closed,"closed"),bool(original,"processExited"),bool(original,"writerExited"),bool(original,"instrumentationExited"),
            string(binding,"source")=="lan" && binding.isNull("deviceAuthorization"),string(binding,"status")=="revoked",string(binding,"revokeOutcome")=="confirmed",
            strings(binding.getJSONArray("groupBindingIds")),string(binding,"bindingId"),bool(archives,"allFilesReadBackOutsideProfile"),entries,manifestSha,allowedMode)
        p.validate(plan) // All authorization/identity/archive gates precede the first KeyStore operation.
        val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyPort=object: F73OwnedCleanupPolicy.Keys {
            override fun contains(alias: String)=keys.containsAlias(alias)
            override fun delete(alias: String) {require(alias==plan.alias);keys.deleteEntry(alias)}
        }
        val profile=F73ProfileTree({
            F73AndroidDirectory.openApplicationFiles(context).use {it.directory("q02-real-dev-profiles-v1")}
        },run)
        val once=Once(staging.resolve("once").resolve("$run.intent"))
        if(mode=="delete")require(System.currentTimeMillis()<expires)
        val result=if(mode=="delete")p.delete(plan,keyPort,profile,once) else p.query(plan,keyPort,profile,once)
        val report=JSONObject().put("kind","f73-owned-cleanup-native-observation-v1").put("operationId",operation).put("run",run).put("manifestSha256",manifestSha)
            .put("mode",mode).put("allowedMode",allowedMode).put("intentPresent",result.intentPresent).put("remainingFiles",JSONArray(result.remainingFiles)).put("profileAbsent",result.profileAbsent)
            .put("aliasObservations",JSONObject(result.aliases)).put("completeAtPublicApiObservation",result.completeAtApiObservation)
            .put("absoluteHardwareErasureProven",false).put("allUnnamedDerivedKeysProvenAbsent",false).put("otherAliasesEnumerated",false)
        instrumentation.sendStatus(73,Bundle().apply {putString("f73OwnedCleanupObservation",report.toString())})
        if(mode=="delete")require(result.completeAtApiObservation) {"Partial/unknown result: query this exact intent; never replay delete"}
    }
}

/** Public Android APIs only. /proc/self/fd resolves a held descriptor, not a mutable profile parent.
 * The SDK does not expose O_DIRECTORY/unlink/rmdir: fstat requires S_ISDIR immediately after open,
 * and public Os.remove follows an exact basename under the held directory descriptor.
 * No alias/key material is read here. A denied proc-fd operation fails closed, with no fallback.
 */
private class F73AndroidDirectory private constructor(private val held: ParcelFileDescriptor,private val device: Long,
    private val applicationFilesFoundation: Boolean = false): F73ProfileTree.Directory {
    private val root get()="/proc/self/fd/${held.fd}"
    private fun path(name: String): String {
        require(name.matches(Regex("[a-zA-Z0-9._-]+")) && name !in setOf(".",".."));return "$root/$name"
    }
    private fun checked(st: android.system.StructStat,allowDirectory: Boolean,applicationFilesSelf: Boolean = false): F73ProfileTree.Stat {
        require(st.st_dev==device && st.st_uid==Process.myUid() && st.st_gid==Process.myUid())
        val dir=OsConstants.S_ISDIR(st.st_mode);val file=OsConstants.S_ISREG(st.st_mode)
        require(if(dir) allowDirectory && F73FoundationDirectoryMode.admits(st.st_mode,st.st_uid,st.st_gid,Process.myUid(),true,OsConstants.S_ISLNK(st.st_mode),applicationFilesSelf)
            else file && st.st_nlink==1L && st.st_mode and 0xfff==0x180)
        // Directory size/mtime naturally change during deletion; identity stays device+inode.
        return F73ProfileTree.Stat((if(dir) "${st.st_dev}:${st.st_ino}" else "${st.st_dev}:${st.st_ino}:${st.st_mode}:${st.st_uid}:${st.st_gid}:${st.st_nlink}:${st.st_size}:${st.st_mtim.tv_sec}:${st.st_mtim.tv_nsec}:${st.st_ctim.tv_sec}:${st.st_ctim.tv_nsec}"),dir,file,OsConstants.S_ISLNK(st.st_mode),if(dir)0 else st.st_size)
    }
    override fun names(): Set<String> {
        checked(Os.fstat(held.fileDescriptor),true,applicationFilesSelf=applicationFilesFoundation)
        Os.fsync(held.fileDescriptor) // Capability preflight before any signed-file/key deletion.
        val all=checkNotNull(File(root).list());require(all.distinct().size==all.size);return all.toSet()
    }
    override fun stat(name: String)=checked(Os.lstat(path(name)),true)
    override fun directory(name: String): F73AndroidDirectory {
        val before=stat(name);require(before.directory && !before.symlink)
        val fd=Os.open(path(name),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        val dup=try {ParcelFileDescriptor.dup(fd)}finally{Os.close(fd)}
        try {require(checked(Os.fstat(dup.fileDescriptor),true)==before && stat(name)==before);return F73AndroidDirectory(dup,device)}
        catch(e: Throwable){dup.close();throw e}
    }
    override fun hash(name: String,expected: F73ProfileTree.Stat): String {
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
    override fun unlink(name: String,expected: F73ProfileTree.Stat) {
        require(expected.regular && !expected.symlink && stat(name)==expected);Os.remove(path(name));Os.fsync(held.fileDescriptor)
        require(name !in names())
    }
    override fun rmdir(name: String,expected: F73ProfileTree.Stat) {
        require(expected.directory && !expected.symlink && stat(name)==expected);Os.remove(path(name));Os.fsync(held.fileDescriptor)
        require(name !in names())
    }
    override fun close()=held.close()
    companion object {
        /** This is the sole foundation entry: no arbitrary Path can request broad permissions. */
        fun openApplicationFiles(context: Context): F73AndroidDirectory {
            require(context===InstrumentationRegistry.getInstrumentation().targetContext)
            require(BuildConfig.DEBUG && context.packageName=="com.easyai.agenthub.android.acceptance" && context.applicationInfo.uid==Process.myUid())
            require(!Files.isSymbolicLink(context.filesDir.toPath()))
            val path=context.filesDir.canonicalFile.toPath()
            val before=Os.lstat(path.toString())
            require(F73FoundationDirectoryMode.admits(before.st_mode,before.st_uid,before.st_gid,Process.myUid(),OsConstants.S_ISDIR(before.st_mode),OsConstants.S_ISLNK(before.st_mode),true))
            val fd=Os.open(path.toString(),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            val dup=try {ParcelFileDescriptor.dup(fd)}finally{Os.close(fd)}
            val result=F73AndroidDirectory(dup,before.st_dev,applicationFilesFoundation=true)
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

/** Only a trusted App files foundation self-check may admit its observed 0771 mode. */
internal object F73FoundationDirectoryMode {
    fun admits(mode: Int,uid: Int,gid: Int,expectedUid: Int,directory: Boolean,symlink: Boolean,applicationFilesSelf: Boolean): Boolean {
        val permissions=mode and 0xfff
        return directory && !symlink && uid==expectedUid && gid==expectedUid && permissions and 0x2==0 &&
            (permissions==0x1c0 || applicationFilesSelf && permissions==0x1f9)
    }
}
