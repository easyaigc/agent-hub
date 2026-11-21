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

/** Separate finite Codex cleanup. Installing a runner enables neither QUERY nor DELETE. */
class FreshQ04OwnedProfileCleanupTest {
    private val run=FreshQ04SingleOriginCase.RUN
    private val rootAlias="q02-real-"+FreshQ04SingleOriginCase.RUN
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
    @Test fun queryOrDeleteOnlyRootReviewedFreshQ04ProfileOnce() {
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("freshQ04OwnedCleanupReady")=="1") {"Default disabled; no KeyStore access"}
        require(args.getString("nativeForeground")=="0"&&args.getString("nativeThreadSnapshots")=="0")
        val mode=args.getString("freshQ04OwnedCleanupMode") ?: "query";require(mode in setOf("query","delete"))
        val operation=checkNotNull(args.getString("cleanupOperationId"));require(java.util.UUID.fromString(operation).toString()==operation)
        val manifestSha=checkNotNull(args.getString("rootCleanupManifestSha256"));hash(manifestSha)
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance"&&instrumentation.context.packageName==context.packageName+".test")
        require(context.applicationInfo.uid==Process.myUid()&&context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        val base=context.filesDir.canonicalFile.toPath()
        FreshQ04CleanupDirectory.openApplicationFiles(context).use { }
        val staging=base.resolve("acceptance/fresh-q04-owned-cleanup-v1");val input=staging.resolve(operation)
        listOf(base.resolve("acceptance"),staging,input,staging.resolve("once")).forEach {owned(it,true)}
        val raw=bytes(input.resolve("root-review.json"),131072);require(sha(raw)==manifestSha)
        val v=JSONObject(String(raw,Charsets.UTF_8))
        exact(v,"version","kind","enabled","rootReviewed","allowedMode","taskId","operationId","issuedAtEpochMs","expiresAtEpochMs",
            "run","rootAlias","budgetOperationId","leaseId","target","installation","nativePrepared","nativeClosed","originalProcess",
            "bindingBefore","bindingAfter","revokeReview","archive","inventory","scopeEvidence","preDeleteQuery")
        require(number(v,"version")==1L&&string(v,"kind")=="root-fresh-q04-owned-cleanup-review-v1"&&bool(v,"enabled")&&bool(v,"rootReviewed"))
        require(string(v,"taskId")=="android-parallel-20261112-01a1cd5e"&&string(v,"operationId")==operation)
        require(string(v,"run")==run&&string(v,"rootAlias")==rootAlias&&string(v,"budgetOperationId")==FreshQ04SingleOriginCase.BUDGET&&string(v,"leaseId")==FreshQ04SingleOriginCase.LEASE)
        target(v.getJSONObject("target"))
        val allowedMode=string(v,"allowedMode");require(allowedMode in setOf("query","delete"));if(mode=="delete")require(allowedMode=="delete")
        val now=System.currentTimeMillis();val issued=number(v,"issuedAtEpochMs");val expires=number(v,"expiresAtEpochMs")
        require(issued<=now&&expires in issued+1..issued+300000)
        if(mode=="delete")require(issued in now-60000..now&&expires>now)
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
        val prepared=artifact(input,v.getJSONObject("nativePrepared"),"profile-prepared.json")
        val closed=artifact(input,v.getJSONObject("nativeClosed"),"profile-resource-state.json")
        val parent=base.resolve("q02-real-dev-profiles-v1");owned(parent,true);val root=parent.resolve(run)
        val allowedFields=setOf("storageOwnerRun","existingOwnedResume","run","leaseId","root","alias","pid","processNonce","newBindingIntent","created","destroyed","pairingAttempted","closed","controlHostId","localMappingSha256","target","ordinaryStorageMounted","runtimeNoBackupRoot","activityNoBackupRoot","sessionBoundToOwnedStorage","controlHostMappingExternal","fixturePortsInstalled","nativeSystemIntegrations","retentionReason","retainUntil")
        for(proof in listOf(prepared,closed)) {
            require(proof.keys().asSequence().toSet().all {it in allowedFields})
            require(string(proof,"storageOwnerRun")==run&&string(proof,"run")==run&&string(proof,"alias")==rootAlias&&string(proof,"leaseId")==FreshQ04SingleOriginCase.LEASE)
            target(proof.getJSONObject("target"));hash(string(proof,"localMappingSha256"))
            require(string(proof,"controlHostId")=="13c81542-48e3-4df2-ab46-d45357b631a2")
            require(listOf("existingOwnedResume","ordinaryStorageMounted","fixturePortsInstalled","nativeSystemIntegrations").all {!bool(proof,it)})
            require(File(string(proof,"root")).canonicalFile.toPath()==root&&File(string(proof,"root")).absoluteFile.toPath()==root)
            require(File(string(proof,"runtimeNoBackupRoot")).canonicalFile.toPath()==root.resolve("no-backup"))
            require(File(string(proof,"activityNoBackupRoot")).canonicalFile.toPath()==root.resolve("no-backup"))
        }
        require(listOf("created","destroyed","pairingAttempted","closed").all {!bool(prepared,it)&&bool(closed,it)})
        require(bool(closed,"sessionBoundToOwnedStorage")&&bool(closed,"controlHostMappingExternal"))
        require(prepared.get("pid")==closed.get("pid")&&prepared.get("processNonce")==closed.get("processNonce")&&prepared.get("localMappingSha256")==closed.get("localMappingSha256"))
        val original=v.getJSONObject("originalProcess");exact(original,"pid","processNonce","writerExited","instrumentationExited","processExited","independentObservationSha256")
        require(number(original,"pid")==number(closed,"pid")&&string(original,"processNonce")==string(closed,"processNonce"))
        require(listOf("writerExited","instrumentationExited","processExited").all {bool(original,it)})
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
        val beforeRows=before.getJSONArray("bindings").objects();val afterRows=after.getJSONArray("bindings").objects()
        require(beforeRows.size==afterRows.size)
        val old=beforeRows.single {string(it,"publicKeySha256")==publicSha};val fresh=afterRows.single {string(it,"publicKeySha256")==publicSha}
        target(old.getJSONObject("target"));target(fresh.getJSONObject("target"));require(!bool(old,"revoked")&&bool(fresh,"revoked"))
        val restored=JSONObject(after.toString());restored.getJSONArray("bindings").objects().single {string(it,"publicKeySha256")==publicSha}.put("revoked",false)
        // sourceRef identifies two different actual files; the complete binding rows themselves must be identical after reversing the one bit.
        same(restored.getJSONArray("bindings"),before.getJSONArray("bindings"))
        val revoke=artifact(input,v.getJSONObject("revokeReview"),"revoke-root-review.json")
        exact(revoke,"kind","rootReviewed","target","mobileDeviceId","mobilePublicKeySha256","pairingIntentSha256","persistedBeforeRef","persistedAfterRef","allOtherRowsPreserved","onlyChangedField","revocationConfirmed","phoneProfileAndKeyDeleted")
        require(string(revoke,"kind")=="root-fresh-q04-codex-revoke-review-v1"&&bool(revoke,"rootReviewed")&&bool(revoke,"allOtherRowsPreserved")&&bool(revoke,"revocationConfirmed")&&!bool(revoke,"phoneProfileAndKeyDeleted"))
        target(revoke.getJSONObject("target"));require(string(revoke,"onlyChangedField")=="revoked"&&string(revoke,"mobileDeviceId")==mobile&&string(revoke,"mobilePublicKeySha256")==publicSha&&revoke.get("pairingIntentSha256")==intent.get("pairingIntentSha256"))
        same(revoke.getJSONObject("persistedBeforeRef"),before.getJSONObject("sourceRef"));same(revoke.getJSONObject("persistedAfterRef"),after.getJSONObject("sourceRef"))
        val scope=v.getJSONObject("scopeEvidence");exact(scope,"allOtherProfilesPreserved","nativeEvidencePreserved","rootReviewed","sourceGraphSha256")
        require(listOf("allOtherProfilesPreserved","nativeEvidencePreserved","rootReviewed").all {bool(scope,it)});hash(string(scope,"sourceGraphSha256"))
        val archive=v.getJSONObject("archive");exact(archive,"allFilesReadBackOutsideProfile","originalNativeEvidence","scopeEvidence","profileFiles")
        require(bool(archive,"allFilesReadBackOutsideProfile"));archiveRef(archive.getJSONObject("originalNativeEvidence"));archiveRef(archive.getJSONObject("scopeEvidence"))
        val archiveRows=archive.getJSONArray("profileFiles").objects()
        val archived=archiveRows.associate {exact(it,"path","ref");archiveRef(it.getJSONObject("ref"));string(it,"path") to it.getJSONObject("ref")};require(archived.size==archiveRows.size)
        val inventory=v.getJSONArray("inventory").objects().map {entry->
            exact(entry,"path","kind","bytes","sha256");val path=string(entry,"path");val dir=string(entry,"kind")=="directory";require(dir||string(entry,"kind")=="file")
            val size=number(entry,"bytes");val h=string(entry,"sha256")
            if(dir)require(size==0L&&h.isEmpty())else {hash(h);require(size in 29..1048576);require(archived[path]?.let {number(it,"bytes")==size&&string(it,"sha256")==h}==true)}
            FreshQ04CleanupEntry(path,dir,size,h)
        }
        require(inventory.map {it.path}.distinct().size==inventory.size)
        require(inventory.filter {it.directory}.map {it.path}.toSet()==setOf("","no-backup","no-backup/q02-session","files","cache","cache/migration-ui-v1","code-cache"))
        val files=inventory.filterNot {it.directory};require(files.size in 1..32&&files.sumOf {it.bytes} in 1..1048576)
        require(files.all {it.path.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin"))})
        require(files.none {it.path.endsWith(sha("migration-active-generation-v1".toByteArray(Charsets.UTF_8))+".bin")})
        require(archived.keys==files.map {it.path}.toSet())
        val plan=FreshQ04CleanupPlan(run,inventory)
        val tree=FreshQ04CleanupTree({FreshQ04CleanupDirectory.openApplicationFiles(context).use {it.directory("q02-real-dev-profiles-v1")}},run)
        val once=Once(staging.resolve("once").resolve("$run.intent"));val prior=once.prior();require(prior==null||prior==manifestSha)
        if(allowedMode=="delete") {
            val query=artifact(input,v.getJSONObject("preDeleteQuery"),"pre-delete-query.json")
            exact(query,"kind","run","operationId","manifestSha256","mode","allowedMode","intentPresent","profileAbsent","remainingFiles","rootAliasPresent","completeAtPublicApiObservation","absoluteHardwareErasureProven","allUnnamedDerivedKeysProvenAbsent","otherAliasesEnumerated")
            require(string(query,"kind")=="fresh-q04-owned-cleanup-native-observation-v1"&&string(query,"run")==run&&string(query,"mode")=="query")
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
        val intentPresent=once.prior()!=null;val complete=intentPresent&&remaining.isEmpty()&&!aliasPresent
        val report=JSONObject().put("kind","fresh-q04-owned-cleanup-native-observation-v1").put("run",run).put("operationId",operation).put("manifestSha256",manifestSha)
            .put("mode",mode).put("allowedMode",allowedMode).put("intentPresent",intentPresent).put("profileAbsent",remaining.isEmpty())
            .put("remainingFiles",JSONArray(remaining.filterNot {it.directory}.map {it.path})).put("rootAliasPresent",aliasPresent).put("completeAtPublicApiObservation",complete)
            .put("absoluteHardwareErasureProven",false).put("allUnnamedDerivedKeysProvenAbsent",false).put("otherAliasesEnumerated",false)
        instrumentation.sendStatus(73,Bundle().apply {putString("freshQ04OwnedCleanupObservation",report.toString())})
        if(mode=="delete")require(complete) {"Partial/unknown delete: query this exact manifest and original run; never replay"}
    }

}

internal data class FreshQ04CleanupEntry(val path: String,val directory: Boolean,val bytes: Long=0,val sha256: String="")
internal data class FreshQ04CleanupPlan(val run: String,val inventory: List<FreshQ04CleanupEntry>)
internal interface FreshQ04CleanupProfile {
    fun remaining(plan: FreshQ04CleanupPlan,partial: Boolean): List<FreshQ04CleanupEntry>
    fun remove(plan: FreshQ04CleanupPlan)
}
internal class FreshQ04CleanupTree(private val parent: ()->Directory, private val run: String): FreshQ04CleanupProfile {
    data class Stat(val key: String, val directory: Boolean, val regular: Boolean, val symlink: Boolean, val bytes: Long)
    interface Directory: AutoCloseable {
        fun names(): Set<String>
        fun stat(name: String): Stat
        fun directory(name: String): Directory
        fun hash(name: String, expected: Stat): String
        fun unlink(name: String, expected: Stat)
        fun rmdir(name: String, expected: Stat)
    }
    private fun leaf(path: String)=path.substringAfterLast('/')
    private fun children(plan: FreshQ04CleanupPlan,prefix: String)=plan.inventory.filter {
        it.path.isNotEmpty() && it.path.substringBeforeLast('/',"")==prefix
    }
    override fun remaining(plan: FreshQ04CleanupPlan, partial: Boolean): List<FreshQ04CleanupEntry> {
        require(plan.run==run)
        return parent().use { holder ->
            if(run !in holder.names()) {require(partial);return@use emptyList()}
            val root=holder.stat(run);require(root.directory && !root.symlink)
            val actual=mutableListOf<FreshQ04CleanupEntry>()
            fun walk(dir: Directory,prefix: String) {
                actual+=plan.inventory.single {it.path==prefix && it.directory}
                for(name in dir.names()) {
                    require(name.matches(Regex("[a-zA-Z0-9._-]+")) && name !in setOf(".",".."))
                    val approved=children(plan,prefix).singleOrNull {leaf(it.path)==name} ?: error("Unknown material: preserve profile")
                    val st=dir.stat(name);require(!st.symlink)
                    if(approved.directory) {require(st.directory);dir.directory(name).use {walk(it,approved.path)}}
                    else {require(st.regular && st.bytes==approved.bytes);require(dir.hash(name,st)==approved.sha256);actual+=approved}
                    require(dir.stat(name)==st)
                }
            }
            holder.directory(run).use {walk(it,"")};require(holder.stat(run)==root)
            if(!partial)require(actual.toSet()==plan.inventory.toSet())
            actual
        }
    }
    override fun remove(plan: FreshQ04CleanupPlan) {
        require(remaining(plan,false).toSet()==plan.inventory.toSet())
        parent().use {holder ->
            val root=holder.stat(run);require(root.directory && !root.symlink)
            fun remove(dir: Directory,prefix: String) {
                val expected=children(plan,prefix);require(dir.names()==expected.map {leaf(it.path)}.toSet())
                for(entry in expected.sortedBy {it.path}) {
                    val name=leaf(entry.path);val st=dir.stat(name);require(!st.symlink)
                    if(entry.directory) {
                        require(st.directory);dir.directory(name).use {remove(it,entry.path)};dir.rmdir(name,st)
                    } else {
                        require(st.regular && st.bytes==entry.bytes && dir.hash(name,st)==entry.sha256)
                        dir.unlink(name,st)
                    }
                }
                require(dir.names().isEmpty())
            }
            holder.directory(run).use {remove(it,"")};holder.rmdir(run,root)
        }
    }
}

private class FreshQ04CleanupDirectory private constructor(private val held: ParcelFileDescriptor,private val device: Long,
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
    override fun directory(name: String): FreshQ04CleanupDirectory {
        val before=stat(name);require(before.directory && !before.symlink)
        val fd=Os.open(path(name),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        val dup=try {ParcelFileDescriptor.dup(fd)}finally{Os.close(fd)}
        try {require(checked(Os.fstat(dup.fileDescriptor),true)==before && stat(name)==before);return FreshQ04CleanupDirectory(dup,device)}
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
        fun openApplicationFiles(context: Context): FreshQ04CleanupDirectory {
            require(context===InstrumentationRegistry.getInstrumentation().targetContext)
            require(BuildConfig.DEBUG && context.packageName=="com.easyai.agenthub.android.acceptance" && context.applicationInfo.uid==Process.myUid())
            require(!Files.isSymbolicLink(context.filesDir.toPath()))
            val path=context.filesDir.canonicalFile.toPath()
            val before=Os.lstat(path.toString())
            require(F73FoundationDirectoryMode.admits(before.st_mode,before.st_uid,before.st_gid,Process.myUid(),OsConstants.S_ISDIR(before.st_mode),OsConstants.S_ISLNK(before.st_mode),true))
            val fd=Os.open(path.toString(),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            val dup=try {ParcelFileDescriptor.dup(fd)}finally{Os.close(fd)}
            val result=FreshQ04CleanupDirectory(dup,before.st_dev,applicationFilesFoundation=true)
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

/** Review-only sibling: an original unpaired/lost launch is never re-labelled closed. */
class FreshQ04UnpairedStartupCleanupTest {
    private val run="d5d1d682-2b22-44e5-89a8-bc37f643384b"
    private val alias="q02-real-$run"
    private val lease="a89619e2-8cc7-408e-85f3-2998186f1ca5"
    private val budget="b7253462-e867-4f09-8518-429e8d620e8b"
    private val nonce="417d0a8a4d0e496b"
    private val originalPid=21757
    private val originalNonce="7ae99746-fc43-4cd0-a3c2-e9be19ce66cd"
    private val permittedDirectories=setOf("","no-backup","no-backup/q02-session","files","cache","cache/migration-ui-v1","code-cache")
    private fun sha(b: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
    private fun hash(s: String) {require(s.matches(Regex("[a-f0-9]{64}")))}
    private fun text(v: JSONObject,k: String)=(v.get(k) as? String) ?: error("Non-string $k")
    private fun bool(v: JSONObject,k: String)=(v.get(k) as? Boolean) ?: error("Non-boolean $k")
    private fun number(v: JSONObject,k: String): Long {val n=v.get(k);require(n is Int||n is Long);return (n as Number).toLong()}
    private fun exact(v: JSONObject,vararg keys: String) {require(v.keys().asSequence().toSet()==keys.toSet())}
    private fun objects(a: JSONArray)=(0 until a.length()).map {a.getJSONObject(it)}
    private fun same(a: Any?,b: Any?) {require(easyCanonical(a)==easyCanonical(b))}
    private fun target(v: JSONObject) {same(v,JSONObject().put("agentId","codex").put("hostId","13c81542-48e3-4df2-ab46-d45357b631a2").put("workspaceId","13c81542-48e3-4df2-ab46-d45357b631a2").put("runtimeId","desktop-runtime"))}
    private fun ref(v: JSONObject) {exact(v,"path","bytes","sha256");require(text(v,"path").startsWith("/")&&number(v,"bytes")>0);hash(text(v,"sha256"))}
    private fun fileStat(path: Path): android.system.StructStat {
        val s=Os.lstat(path.toString());require(s.st_uid==Process.myUid()&&s.st_gid==Process.myUid()&&s.st_nlink==1L&&OsConstants.S_ISREG(s.st_mode)&&s.st_mode and 0xfff==0x180);return s
    }
    private fun statKey(s: android.system.StructStat)="${s.st_dev}:${s.st_ino}:${s.st_mode}:${s.st_uid}:${s.st_gid}:${s.st_nlink}:${s.st_size}:${s.st_mtim.tv_sec}:${s.st_mtim.tv_nsec}:${s.st_ctim.tv_sec}:${s.st_ctim.tv_nsec}"
    private fun directoryKey(path: Path): String {
        val s=Os.lstat(path.toString());require(s.st_uid==Process.myUid()&&s.st_gid==Process.myUid()&&OsConstants.S_ISDIR(s.st_mode)&&s.st_mode and 0xfff==0x1c0);return "${s.st_dev}:${s.st_ino}"
    }
    private fun read(path: Path,maximum: Int): ByteArray {
        val named=fileStat(path);val fd=Os.open(path.toString(),OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try {
            val before=Os.fstat(fd);require(statKey(before)==statKey(named)&&before.st_size in 1..maximum.toLong())
            val b=ByteArray(before.st_size.toInt());var offset=0
            while(offset<b.size){val n=Os.read(fd,b,offset,b.size-offset);require(n>0);offset+=n}
            require(Os.read(fd,ByteArray(1),0,1)==0&&statKey(Os.fstat(fd))==statKey(before)&&statKey(fileStat(path))==statKey(before));return b
        } finally {Os.close(fd)}
    }
    private fun artifact(input: Path,v: JSONObject,name: String): Pair<ByteArray,JSONObject> {
        exact(v,"name","bytes","sha256");require(text(v,"name")==name);val b=read(input.resolve(name),65_536)
        require(b.size.toLong()==number(v,"bytes")&&sha(b)==text(v,"sha256"))
        return b to JSONObject(String(b,Charsets.UTF_8))
    }
    private fun archive(v: JSONObject) {
        exact(v,"evidenceId","bytes","sha256","readbackSha256","outsideProfile","rootVerified")
        require(text(v,"evidenceId").matches(Regex("[A-Za-z0-9._-]{1,128}"))&&number(v,"bytes")>0)
        hash(text(v,"sha256"));require(text(v,"sha256")==text(v,"readbackSha256")&&bool(v,"outsideProfile")&&bool(v,"rootVerified"))
    }
    private fun noOriginalOrOtherWriter(context: Context) {
        require(Process.myPid()!=originalPid)
        try {Os.stat("/proc/$originalPid");error("Original PID exists or was reused: preserve")}
        catch(e: ErrnoException){require(e.errno==OsConstants.ENOENT)}
        val processes=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
        require(processes!=null&&processes.any {it.pid==Process.myPid()&&it.uid==Process.myUid()}&&processes.none {it.uid==Process.myUid()&&it.pid!=Process.myPid()})
    }
    private fun installation(context: Context,v: JSONObject) {
        exact(v,"package","uid","api","deviceSha256","appSha256","runnerSha256","signerSha256")
        require(text(v,"package")==context.packageName&&number(v,"uid")==Process.myUid().toLong()&&number(v,"api")==android.os.Build.VERSION.SDK_INT.toLong())
        for(k in listOf("deviceSha256","appSha256","runnerSha256","signerSha256"))hash(text(v,k))
        require(sha(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID).toByteArray(Charsets.UTF_8))==text(v,"deviceSha256"))
        fun fileSha(file: File): String {val d=java.security.MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
        require(fileSha(File(context.applicationInfo.sourceDir))==text(v,"appSha256"))
        require(fileSha(File(context.packageManager.getApplicationInfo(context.packageName+".test",0).sourceDir))==text(v,"runnerSha256"))
        for(name in listOf(context.packageName,context.packageName+".test")) {
            val signatures=checkNotNull(context.packageManager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).apkContentsSigners
            require(signatures.size==1&&sha(signatures.single().toByteArray())==text(v,"signerSha256"))
        }
    }
    private fun snapshot(context: Context): JSONObject {
        val rows=JSONArray();var present=false;var total=0L;var files=0
        FreshQ04CleanupDirectory.openApplicationFiles(context).use {foundation->
            foundation.directory("q02-real-dev-profiles-v1").use {parent->
                if(run in parent.names()) {
                    present=true;val root=parent.stat(run);require(root.directory&&!root.symlink)
                    fun walk(dir: FreshQ04CleanupTree.Directory,path: String,key: String) {
                        require(path in permittedDirectories);rows.put(JSONObject().put("path",path).put("kind","directory").put("bytes",0).put("sha256","").put("statKey",key))
                        val names=dir.names();require(names.size<=32)
                        for(name in names.sorted()) {
                            val p=if(path.isEmpty())name else "$path/$name";val s=dir.stat(name);require(!s.symlink)
                            if(s.directory){require(p in permittedDirectories);dir.directory(name).use {walk(it,p,s.key)}}
                            else {
                                require(s.regular&&p.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin")))
                                require(name!=sha("migration-active-generation-v1".toByteArray(Charsets.UTF_8))+".bin")
                                require(s.bytes in 29..1_048_576);files++;total+=s.bytes;require(files<=32&&total<=1_048_576)
                                rows.put(JSONObject().put("path",p).put("kind","file").put("bytes",s.bytes).put("sha256",dir.hash(name,s)).put("statKey",s.key))
                            }
                            require(dir.stat(name)==s)
                        }
                        require(dir.names()==names)
                    }
                    parent.directory(run).use {walk(it,"",root.key)};require(parent.stat(run)==root)
                }
            }
        }
        // No root or zero cipher files is a factual observation, never a cleanup success.
        return JSONObject().put("profilePresent",present).put("inventory",rows).put("cipherFiles",files).put("cipherBytes",total)
    }
    private fun plan(snapshot: JSONObject): FreshQ04CleanupPlan=FreshQ04CleanupPlan(run,objects(snapshot.getJSONArray("inventory")).map {
        FreshQ04CleanupEntry(text(it,"path"),text(it,"kind")=="directory",number(it,"bytes"),text(it,"sha256"))
    })
    private fun validateSnapshot(v: JSONObject) {
        exact(v,"profilePresent","inventory","cipherFiles","cipherBytes")
        val rows=objects(v.getJSONArray("inventory"));require(rows.size<=39&&rows.map {text(it,"path")}.distinct().size==rows.size)
        var count=0L;var total=0L
        for(r in rows) {
            exact(r,"path","kind","bytes","sha256","statKey");require(text(r,"statKey").matches(Regex("[0-9:]{1,256}")))
            val path=text(r,"path")
            if(text(r,"kind")=="directory")require(path in permittedDirectories&&number(r,"bytes")==0L&&text(r,"sha256").isEmpty())
            else {require(text(r,"kind")=="file"&&path.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin"))&&number(r,"bytes") in 29..1_048_576);hash(text(r,"sha256"));count++;total+=number(r,"bytes")}
        }
        require(count==number(v,"cipherFiles")&&total==number(v,"cipherBytes")&&count<=32&&total<=1_048_576)
        require(if(bool(v,"profilePresent")) rows.any {text(it,"path").isEmpty()&&text(it,"kind")=="directory"} else rows.isEmpty())
        val directories=rows.filter {text(it,"kind")=="directory"}.map {text(it,"path")}.toSet()
        require(rows.all {text(it,"path").isEmpty()||text(it,"path").substringBeforeLast('/',"") in directories})
    }
    private fun pinnedTree(context: Context,approved: JSONObject): FreshQ04CleanupTree {
        val expected=objects(approved.getJSONArray("inventory")).associateBy {text(it,"path")}
        fun wrap(dir: FreshQ04CleanupTree.Directory,prefix: String?): FreshQ04CleanupTree.Directory {
            fun full(name: String): String=if(prefix==null){require(name==run);""}else if(prefix.isEmpty())name else "$prefix/$name"
            return object: FreshQ04CleanupTree.Directory {
                override fun names()=dir.names()
                override fun stat(name: String): FreshQ04CleanupTree.Stat {
                    val row=expected[full(name)] ?: error("Unknown material: preserve")
                    return dir.stat(name).also {require(it.key==text(row,"statKey"))}
                }
                override fun directory(name: String): FreshQ04CleanupTree.Directory {stat(name);return wrap(dir.directory(name),full(name))}
                override fun hash(name: String,expected: FreshQ04CleanupTree.Stat)=dir.hash(name,expected)
                override fun unlink(name: String,expected: FreshQ04CleanupTree.Stat){require(stat(name)==expected);dir.unlink(name,expected)}
                override fun rmdir(name: String,expected: FreshQ04CleanupTree.Stat){require(stat(name)==expected);dir.rmdir(name,expected)}
                override fun close()=dir.close()
            }
        }
        return FreshQ04CleanupTree({wrap(FreshQ04CleanupDirectory.openApplicationFiles(context).use {it.directory("q02-real-dev-profiles-v1")},null)},run)
    }
    private fun marker(path: Path): String? {
        if(Files.notExists(path,NOFOLLOW_LINKS))return null
        val b=read(path,65);require(b.size==65&&b.last()==10.toByte());return String(b,Charsets.US_ASCII).trimEnd('\n').also {hash(it)}
    }
    private fun claim(path: Path,manifest: String,stable: ()->Unit) {
        stable()
        FileChannel.open(path,setOf(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,NOFOLLOW_LINKS),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))).use {channel->
            val b=ByteBuffer.wrap((manifest+"\n").toByteArray(Charsets.US_ASCII));while(b.hasRemaining())require(channel.write(b)>0);channel.force(true)
        }
        val fd=Os.open(path.parent.toString(),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try {Os.fsync(fd)}finally{Os.close(fd)}
        stable();require(marker(path)==manifest)
    }
    @Test fun queryOrDeleteOnlyRootReviewedUnpairedFreshQ04StartupOnce() {
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("freshQ04PrepairStartupCleanupReady")=="1") {"Default disabled; no KeyStore access"}
        require(args.getString("freshQ04OwnedCleanupReady")==null&&args.getString("nativeForeground")=="0"&&args.getString("nativeThreadSnapshots")=="0")
        val mode=checkNotNull(args.getString("freshQ04PrepairStartupCleanupMode"));require(mode in setOf("query","delete"))
        val op=checkNotNull(args.getString("cleanupOperationId"));require(java.util.UUID.fromString(op).toString()==op)
        val manifestSha=checkNotNull(args.getString("rootCleanupManifestSha256"));hash(manifestSha)
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance"&&instrumentation.context.packageName==context.packageName+".test")
        require(context.applicationInfo.uid==Process.myUid()&&context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        val base=context.filesDir.canonicalFile.toPath();FreshQ04CleanupDirectory.openApplicationFiles(context).use { }
        val staging=base.resolve("acceptance/fresh-q04-prepair-startup-cleanup-v1");val input=staging.resolve(op)
        val dirs=listOf(base.resolve("acceptance"),staging,input,staging.resolve("once"));val pins=dirs.map {directoryKey(it)}
        fun stable(){require(dirs.map {directoryKey(it)}==pins)}
        val raw=read(input.resolve("root-review.json"),131_072);require(sha(raw)==manifestSha);val v=JSONObject(String(raw,Charsets.UTF_8))
        exact(v,"version","kind","enabled","rootReviewed","allowedMode","operationId","issuedAtEpochMs","expiresAtEpochMs","run","rootAlias","leaseId","budgetOperationId","caseNonce","target","installation","nativePrepared","rootFacts","deletePermit","preDeleteQuery","archive")
        require(number(v,"version")==1L&&text(v,"kind")=="root-fresh-q04-prepair-startup-cleanup-review-v1"&&bool(v,"enabled")&&bool(v,"rootReviewed"))
        require(text(v,"run")==run&&text(v,"rootAlias")==alias&&text(v,"leaseId")==lease&&text(v,"budgetOperationId")==budget&&text(v,"caseNonce")==nonce&&text(v,"operationId")==op)
        target(v.getJSONObject("target"));installation(context,v.getJSONObject("installation"))
        val allowed=text(v,"allowedMode");require(allowed in setOf("query","delete")&&(mode!="delete"||allowed=="delete"))
        val issued=number(v,"issuedAtEpochMs");val expires=number(v,"expiresAtEpochMs");val now=System.currentTimeMillis()
        require(issued in now-60_000..now&&expires in issued+1..issued+300_000&&now<expires)
        val (preparedBytes,prepared)=artifact(input,v.getJSONObject("nativePrepared"),"profile-prepared.json")
        // Original, not reconstructed JSON. This pins the actual 4bcca controlHostId metadata too.
        require(preparedBytes.size==1294&&sha(preparedBytes)=="ebba0c7ba3b0e023bc32d8fa289bbac56d69d5b9e54e20c0545ef62e5f1e69ed")
        require(text(prepared,"run")==run&&text(prepared,"storageOwnerRun")==run&&text(prepared,"alias")==alias&&text(prepared,"leaseId")==lease)
        require(number(prepared,"pid")==originalPid.toLong()&&text(prepared,"processNonce")==originalNonce)
        require(listOf("created","destroyed","pairingAttempted","closed","existingOwnedResume","ordinaryStorageMounted","sessionBoundToOwnedStorage","fixturePortsInstalled","nativeSystemIntegrations").all {!bool(prepared,it)})
        require((!prepared.has("newBindingIntent")||prepared.isNull("newBindingIntent"))&&bool(prepared,"controlHostMappingExternal"));target(prepared.getJSONObject("target"))
        val root=base.resolve("q02-real-dev-profiles-v1").resolve(run)
        require(File(text(prepared,"root")).canonicalFile.toPath()==root&&File(text(prepared,"root")).absoluteFile.toPath()==root)
        val (_,facts)=artifact(input,v.getJSONObject("rootFacts"),"startup-failure-root-facts.json")
        exact(facts,"kind","rootReviewed","run","leaseId","budgetOperationId","caseNonce","target","threadId","originalPid","processNonce","nativeAttemptCount","originalNativeState","originalNativeTerminal","originalRemoteOutcomeUnknown","failedBeforePair","activityLaunchTimeoutMs","newInputs","newTurns","newChildStarts","bindingsCount","bindingsUnchanged","noNewBinding","independentAppUidEmpty","originalPidAbsent","observedAtEpochMs","planRef","preparedRef","failureStackRef","abortPersistedRef","hostPostAuditRef","officialPostAuditRef","bindingsBeforeRef","bindingsAfterRef","processObservationRef","sourceReviewRef")
        require(text(facts,"kind")=="root-fresh-q04-prepair-startup-facts-v1"&&bool(facts,"rootReviewed"))
        for(k in listOf("run","leaseId","budgetOperationId","caseNonce"))require(text(facts,k)==text(v,k))
        target(facts.getJSONObject("target"));require(text(facts,"threadId")=="01a1d0bb-e7b3-7c51-92d9-98ab96329fb7")
        require(number(facts,"originalPid")==originalPid.toLong()&&text(facts,"processNonce")==originalNonce)
        require(number(facts,"nativeAttemptCount")==1L&&text(facts,"originalNativeState")=="lost"&&!bool(facts,"originalNativeTerminal")&&bool(facts,"originalRemoteOutcomeUnknown"))
        require(bool(facts,"failedBeforePair")&&number(facts,"activityLaunchTimeoutMs")==600_000L)
        require(listOf("newInputs","newTurns","newChildStarts").all {number(facts,it)==0L}&&number(facts,"bindingsCount")==12L)
        require(listOf("bindingsUnchanged","noNewBinding","independentAppUidEmpty","originalPidAbsent").all {bool(facts,it)})
        require(number(facts,"observedAtEpochMs") in issued-60_000..issued)
        for(k in listOf("planRef","preparedRef","failureStackRef","abortPersistedRef","hostPostAuditRef","officialPostAuditRef","bindingsBeforeRef","bindingsAfterRef","processObservationRef","sourceReviewRef"))ref(facts.getJSONObject(k))
        require(number(facts.getJSONObject("planRef"),"bytes")==15382L&&text(facts.getJSONObject("planRef"),"sha256")=="669073def3d2608ec12ce4c8dafbf645471762736e17714f2911067ce7d2017a")
        require(number(facts.getJSONObject("preparedRef"),"bytes")==1294L&&text(facts.getJSONObject("preparedRef"),"sha256")==sha(preparedBytes))
        for(k in listOf("bindingsBeforeRef","bindingsAfterRef"))require(number(facts.getJSONObject(k),"bytes")==3806L&&text(facts.getJSONObject(k),"sha256")=="54b1f6841bc8f0c3a75295c4b0ca0be26164083b8506bb09676c1e07be748be6")
        // Root independently checks referenced Host/raw artifacts; Native never accesses those Host paths.
        stable();noOriginalOrOtherWriter(context)
        val once=staging.resolve("once").resolve("$run.intent");val prior=marker(once);require(prior==null||prior==manifestSha)
        val current=snapshot(context);validateSnapshot(current)
        var approved: JSONObject?=null
        if(allowed=="query")require(prior==null&&v.isNull("deletePermit")&&v.isNull("preDeleteQuery")&&v.isNull("archive"))
        else {
            val (queryBytes,query)=artifact(input,v.getJSONObject("preDeleteQuery"),"pre-delete-query.json")
            exact(query,"kind","run","operationId","manifestSha256","mode","allowedMode","intentPresent","snapshot","rootAliasState","aliasFailureClass","completeAtPublicApiObservation","absoluteHardwareErasureProven","allUnnamedDerivedKeysProvenAbsent","otherAliasesEnumerated","originalNativeStateUnchanged")
            require(text(query,"kind")=="fresh-q04-prepair-startup-cleanup-native-observation-v1"&&text(query,"run")==run&&text(query,"mode")=="query"&&text(query,"allowedMode")=="query")
            require(!bool(query,"intentPresent")&&!bool(query,"completeAtPublicApiObservation")&&!bool(query,"absoluteHardwareErasureProven")&&!bool(query,"allUnnamedDerivedKeysProvenAbsent")&&!bool(query,"otherAliasesEnumerated")&&bool(query,"originalNativeStateUnchanged"))
            require(text(query,"operationId")!=op&&java.util.UUID.fromString(text(query,"operationId")).toString()==text(query,"operationId"));hash(text(query,"manifestSha256"))
            require(text(query,"rootAliasState") in setOf("present","absent")&&query.isNull("aliasFailureClass"))
            val approvedSnapshot=query.getJSONObject("snapshot");approved=approvedSnapshot;validateSnapshot(approvedSnapshot);require(bool(approvedSnapshot,"profilePresent"))
            val permit=v.getJSONObject("deletePermit")
            exact(permit,"rootReviewed","querySha256","onlyOwnedRoot","onlyExactRootAlias","allowEmptyProfileDeletion","deleteRootAliasIfPresent","noReplayAfterUnknown","rawArchiveComplete","sourceReviewRef")
            require(listOf("rootReviewed","onlyOwnedRoot","onlyExactRootAlias","noReplayAfterUnknown","rawArchiveComplete").all {bool(permit,it)}&&text(permit,"querySha256")==sha(queryBytes));ref(permit.getJSONObject("sourceReviewRef"))
            require(number(approvedSnapshot,"cipherFiles")>0||bool(permit,"allowEmptyProfileDeletion"))
            require(bool(permit,"deleteRootAliasIfPresent")== (text(query,"rootAliasState")=="present"))
            val a=v.getJSONObject("archive");exact(a,"query","originalNativeEvidence","rootFacts","metadata","profileFiles")
            for(k in listOf("query","originalNativeEvidence","rootFacts","metadata"))archive(a.getJSONObject(k))
            for(k in listOf("query","metadata"))require(text(a.getJSONObject(k),"sha256")==sha(queryBytes)&&number(a.getJSONObject(k),"bytes")==queryBytes.size.toLong())
            val rootFactsBytes=read(input.resolve("startup-failure-root-facts.json"),65_536)
            require(text(a.getJSONObject("rootFacts"),"sha256")==sha(rootFactsBytes)&&number(a.getJSONObject("rootFacts"),"bytes")==rootFactsBytes.size.toLong())
            val archived=objects(a.getJSONArray("profileFiles"));require(archived.size<=32)
            val files=objects(approvedSnapshot.getJSONArray("inventory")).filter {text(it,"kind")=="file"}
            require(archived.map {text(it,"path")}.distinct().size==archived.size&&archived.map {text(it,"path")}.toSet()==files.map {text(it,"path")}.toSet())
            for(row in archived){exact(row,"path","ref");val r=row.getJSONObject("ref");archive(r);val file=files.single {text(it,"path")==text(row,"path")};require(number(r,"bytes")==number(file,"bytes")&&text(r,"sha256")==text(file,"sha256"))}
            if(prior==null)same(current,approvedSnapshot)
            else {
                val expected=objects(approvedSnapshot.getJSONArray("inventory")).associateBy {text(it,"path")}
                for(row in objects(current.getJSONArray("inventory")))same(row,expected[text(row,"path")] ?: error("Unknown partial material: preserve"))
            }
            if(mode=="delete")require(prior==null)
        }
        // No alias enumeration and no provider/storage/session construction.
        require(System.currentTimeMillis()<expires)
        val keys=KeyStore.getInstance("AndroidKeyStore")
        var aliasState="unknown";var aliasFailure: String?=null
        try {keys.load(null);aliasState=if(keys.containsAlias(alias))"present" else "absent"}
        catch(e: Exception){aliasFailure=e.javaClass.name.take(160)}
        if(mode=="delete") {
            require(aliasState!="unknown");val query=JSONObject(String(read(input.resolve("pre-delete-query.json"),65_536),Charsets.UTF_8))
            require(aliasState==text(query,"rootAliasState"));stable();noOriginalOrOtherWriter(context);require(System.currentTimeMillis()<expires)
            same(snapshot(context),checkNotNull(approved))
            claim(once,manifestSha,::stable) // Permanent per-run marker before either file or exact-alias mutation.
            pinnedTree(context,checkNotNull(approved)).remove(plan(checkNotNull(approved)))
            if(aliasState=="present")keys.deleteEntry(alias)
            aliasState=if(keys.containsAlias(alias))"present" else "absent"
        }
        val after=if(mode=="delete")snapshot(context) else current
        val intentPresent=marker(once)!=null
        val complete=allowed=="delete"&&intentPresent&&!bool(after,"profilePresent")&&aliasState=="absent"
        val report=JSONObject().put("kind","fresh-q04-prepair-startup-cleanup-native-observation-v1").put("run",run).put("operationId",op).put("manifestSha256",manifestSha)
            .put("mode",mode).put("allowedMode",allowed).put("intentPresent",intentPresent).put("snapshot",after).put("rootAliasState",aliasState).put("aliasFailureClass",aliasFailure ?: JSONObject.NULL)
            .put("completeAtPublicApiObservation",complete).put("absoluteHardwareErasureProven",false).put("allUnnamedDerivedKeysProvenAbsent",false).put("otherAliasesEnumerated",false).put("originalNativeStateUnchanged",true)
        instrumentation.sendStatus(73,Bundle().apply {putString("freshQ04PrepairStartupCleanupObservation",report.toString())})
        require(aliasState!="unknown") {"Exact alias observation unknown: preserve; no DELETE admission"}
        if(mode=="delete")require(complete) {"Partial/unknown: preserve original marker and query only this exact DELETE manifest; never replay"}
    }
}
