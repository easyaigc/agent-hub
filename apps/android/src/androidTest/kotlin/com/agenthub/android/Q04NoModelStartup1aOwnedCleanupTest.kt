package com.agenthub.android

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
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

/** Fixed successful no-model run1a only. QUERY is not cleanup; original diagnostic evidence is never deleted. */
class Q04NoModelStartup1aOwnedCleanupTest {
    private val run="1a587a56-f862-45ee-bd74-042d84f80a37"
    private val alias="q02-real-$run"
    private val diagnosticLease="4c2c1a17-47ad-4c2a-bc56-e4c6cd9dd541"
    private val diagnosticOperation="a9933edb-4e71-48fb-a2ce-e4e4bb01ae41"
    private val sourceSha="57808a4cf9b4a1f7503b71d2864f6ac463e64a839df14156b4445b6278281087"
    private val originalApp="32d51a1135134deda3fbefb2bf259ac21f20a344259430b7fde2a21fc219b289"
    private val evidenceNames=setOf("root-review.json","probe-once.json","environment-before.json","prepare-result.json","launch-intent.json","registration-result.json","startup-observation.json","finish-request.json","probe-final.json","startup-permission-state.json")
    private val originalLeafPins=mapOf(
        "environment-before.json" to (141L to "08c6f70e8993ad21ab586b5027b4bc9ca431c8e7cc87e215a4835619a4555836"),
        "finish-request.json" to (144L to "362eeba35bfd53186ff90dafae3c45f84fe9e83df5cdfa7772cf7f92d8be5340"),
        "launch-intent.json" to (281L to "9c537776ad0d0c1bd3efee8ffd4295ae05402718f8f92690851e941b6775eefb"),
        "prepare-result.json" to (317L to "f1cbe4264eb12f3d72a1e55da30f37d98754e4516b5451867318fa786a9b532a"),
        "probe-final.json" to (4063L to "d72dbc3af6095663f7a051686f56b3ed56f19833fcac0aa682b8ee45bd5506c9"),
        "probe-once.json" to (224L to "3557eabf00f0aace95f18096e1d3ef80483e3968d722f42a929e0cdfed16f52a"),
        "registration-result.json" to (118L to "aeb74237046c9a6111bde43d528f1582437608bb0b07e77ca62a704f1ed58c04"),
        "root-review.json" to (1628L to "eaefd7db7396eb1cf14ae6f2d520f1775fe0d2c738b00216e30e39c4dde9b320"),
        "startup-observation.json" to (2594L to "2be14760c46634d72baaf49879a3f74b19caf31469726e5c339e95643c1f0b1c"),
        "startup-permission-state.json" to (982L to "89263010c4b544d74d238ab5e9a473d8fb6c0364fccefbe06ed9d3e8e8d40a76")
    )
    private val directories=setOf("","no-backup","no-backup/q02-session","files","cache","cache/migration-ui-v1","code-cache")
    private fun sha(b: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
    private fun text(v: JSONObject,k: String)=(v.get(k) as? String) ?: error("Non-string $k")
    private fun bool(v: JSONObject,k: String)=(v.get(k) as? Boolean) ?: error("Non-boolean $k")
    private fun number(v: JSONObject,k: String): Long {val n=v.get(k);require(n is Int||n is Long);return (n as Number).toLong()}
    private fun exact(v: JSONObject,vararg names: String) {require(v.keys().asSequence().toSet()==names.toSet())}
    private fun hash(h: String) {require(h.matches(Regex("[a-f0-9]{64}")))}
    private fun objects(a: JSONArray)=(0 until a.length()).map {a.getJSONObject(it)}
    private fun same(a: Any?,b: Any?) {require(easyCanonical(a)==easyCanonical(b))}
    private fun ref(v: JSONObject) {exact(v,"path","bytes","sha256");require(text(v,"path").startsWith("/")&&number(v,"bytes")>0);hash(text(v,"sha256"))}
    private fun fileKey(s: android.system.StructStat)="${s.st_dev}:${s.st_ino}:${s.st_mode}:${s.st_uid}:${s.st_gid}:${s.st_nlink}:${s.st_size}:${s.st_mtim.tv_sec}:${s.st_mtim.tv_nsec}:${s.st_ctim.tv_sec}:${s.st_ctim.tv_nsec}"
    private fun fileStat(path: Path): android.system.StructStat {
        val s=Os.lstat(path.toString());require(s.st_uid==Process.myUid()&&s.st_gid==Process.myUid()&&s.st_nlink==1L&&OsConstants.S_ISREG(s.st_mode)&&s.st_mode and 0xfff==0x180);return s
    }
    private fun directoryKey(path: Path): String {
        val s=Os.lstat(path.toString());require(s.st_uid==Process.myUid()&&s.st_gid==Process.myUid()&&OsConstants.S_ISDIR(s.st_mode)&&s.st_mode and 0xfff==0x1c0);return "${s.st_dev}:${s.st_ino}"
    }
    private fun read(path: Path,max: Int,expectedKey: String?=null): ByteArray {
        val named=fileStat(path);if(expectedKey!=null)require(fileKey(named)==expectedKey)
        val fd=Os.open(path.toString(),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try {
            val before=Os.fstat(fd);require(fileKey(before)==fileKey(named)&&before.st_size in 1..max.toLong())
            val b=ByteArray(before.st_size.toInt());var offset=0
            while(offset<b.size){val n=Os.read(fd,b,offset,b.size-offset);require(n>0);offset+=n}
            require(Os.read(fd,ByteArray(1),0,1)==0&&fileKey(Os.fstat(fd))==fileKey(before)&&fileKey(fileStat(path))==fileKey(before));return b
        }finally{Os.close(fd)}
    }
    private fun artifact(input: Path,v: JSONObject,name: String): Pair<ByteArray,JSONObject> {
        exact(v,"name","bytes","sha256");require(text(v,"name")==name);val b=read(input.resolve(name),65_536)
        require(b.size.toLong()==number(v,"bytes")&&sha(b)==text(v,"sha256"));return b to JSONObject(String(b,Charsets.UTF_8))
    }
    private fun archive(v: JSONObject) {
        exact(v,"evidenceId","bytes","sha256","readbackSha256","outsideProfile","rootVerified")
        require(text(v,"evidenceId").matches(Regex("[A-Za-z0-9._-]{1,128}"))&&number(v,"bytes")>0)
        hash(text(v,"sha256"));require(text(v,"sha256")==text(v,"readbackSha256")&&bool(v,"outsideProfile")&&bool(v,"rootVerified"))
    }
    private fun installation(context: Context,v: JSONObject) {
        exact(v,"package","uid","api","deviceSha256","appSha256","runnerSha256","signerSha256")
        require(text(v,"package")==context.packageName&&number(v,"uid")==Process.myUid().toLong()&&number(v,"api")==android.os.Build.VERSION.SDK_INT.toLong()&&android.os.Build.VERSION.SDK_INT==33)
        for(k in listOf("deviceSha256","appSha256","runnerSha256","signerSha256"))hash(text(v,k))
        require(sha(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID).toByteArray(Charsets.UTF_8))==text(v,"deviceSha256"))
        fun fileSha(file: File): String {val d=java.security.MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
        require(fileSha(File(context.applicationInfo.sourceDir))==text(v,"appSha256"))
        require(fileSha(File(context.packageManager.getApplicationInfo(context.packageName+".test",0).sourceDir))==text(v,"runnerSha256"))
        for(p in listOf(context.packageName,context.packageName+".test")) {
            val s=checkNotNull(context.packageManager.getPackageInfo(p,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).apkContentsSigners
            require(s.size==1&&sha(s.single().toByteArray())==text(v,"signerSha256"))
        }
    }
    private data class Diagnostic(val snapshot: JSONObject,val documents: Map<String,JSONObject>)
    private fun diagnostic(context: Context): Diagnostic {
        val snapshot=JSONArray();val docs=linkedMapOf<String,JSONObject>()
        val path=context.filesDir.canonicalFile.toPath().resolve("acceptance/q04-no-model-q02-startup-v1/$run")
        var key=""
        Startup1aCleanupDirectory.openApplicationFiles(context).use {f->f.directory("acceptance").use {a->a.directory("q04-no-model-q02-startup-v1").use {p->
            val expected=p.stat(run);require(expected.directory&&!expected.symlink);key=expected.key
            p.directory(run).use {d->
                val names=d.names();require(names==evidenceNames)
                for(name in names.sorted()) {
                    val s=d.stat(name);require(s.regular&&!s.symlink&&s.bytes in 1..65536)
                    val b=read(path.resolve(name),65_536,s.key);require(d.stat(name)==s&&d.hash(name,s)==sha(b))
                    val original=checkNotNull(originalLeafPins[name]);require(b.size.toLong()==original.first&&sha(b)==original.second)
                    docs[name]=JSONObject(String(b,Charsets.UTF_8));snapshot.put(JSONObject().put("name",name).put("bytes",s.bytes).put("sha256",sha(b)).put("statKey",s.key))
                }
                require(d.names()==names)
            };require(p.stat(run)==expected)
        }}}
        return Diagnostic(JSONObject().put("directoryStatKey",key).put("leaves",snapshot),docs)
    }
    private fun noHostSnapshot(snapshot: JSONObject?): Boolean {
        if(snapshot?.opt("mainResponded")!=true)return false
        val v=snapshot.optJSONObject("value") ?: return false;val p=v.optJSONObject("profile") ?: return false
        return p.opt("run")==run&&p.opt("pairingAttempted")==false&&v.opt("realClientCount")==0&&v.opt("operationCount")==0
    }
    private fun positiveClosure(d: Diagnostic,facts: JSONObject): Boolean {
        if(text(facts,"diagnosticOutcome")!="passed"||!bool(facts,"diagnosticJUnitPassed")||d.documents.keys!=evidenceNames)return false
        val startup=d.documents["startup-observation.json"] ?: return false
        val final=d.documents["probe-final.json"] ?: return false
        val permission=d.documents["startup-permission-state.json"] ?: return false
        val callback=final.optJSONObject("callbackRemoval") ?: return false
        val closed=final.optJSONObject("mainSnapshot")?.optJSONObject("value")?.optJSONObject("profile") ?: return false
        val identity=permission.opt("run")==run&&permission.opt("leaseId")==diagnosticLease&&permission.opt("operationId")==diagnosticOperation&&permission.opt("pid")==24705
        val states=listOf("before" to -1,"during" to 0,"after" to -1).all {(key,value)->
            val state=permission.optJSONObject(key)
            state!=null&&state.opt("shellPermission")==0&&state.opt("selfPermission")==value
        }
        val restored=identity&&states&&permission.opt("permission")=="android.permission.START_ACTIVITIES_FROM_BACKGROUND"&&
            permission.opt("readbackScope")=="specified-permission-only-public-api"&&permission.isNull("fullAdoptedPermissionSet")&&
            easyCanonical(permission.optJSONArray("adoptCallArguments"))==easyCanonical(JSONArray(listOf("android.permission.START_ACTIVITIES_FROM_BACKGROUND")))&&
            listOf("adoptCallEntered","adoptCallReturned","adoptCallEnded","dropCallClaimed","dropCallReturned","dropCallFinished","specifiedPermissionRestored").all {permission.opt(it)==true}&&
            permission.isNull("dropErrorClass")&&permission.opt("persistentPermissionOrAppOpsSettingsMutation")==false&&
            easyCanonical(final.optJSONObject("temporaryPermission"))==easyCanonical(permission)
        return restored&&startup.opt("resumedObserved")==true&&startup.opt("noHostMainSnapshotConfirmed")==true&&noHostSnapshot(startup.optJSONObject("mainSnapshot"))&&
            noHostSnapshot(final.optJSONObject("mainSnapshot"))&&closed.opt("run")==run&&
            listOf("created","destroyed","closed","claimed").all {closed.opt(it)==true}&&closed.opt("pairingAttempted")==false&&
            final.opt("run")==run&&listOf("resumedObserved","destroyedObserved","writerCloseReturned","noHostMainSnapshotConfirmed","startRequestReturned","profilePreparedObserved","noModels","noHostOperations").all {final.opt(it)==true}&&
            listOf("lateStartWorkerStillLive","lateCloseWorkerStillLive","latePermissionCleanupWorkerStillLive","profileDeletionAttempted","businessControlsAccepted").all {final.opt(it)==false}&&
            listOf("startWorkerStack","closeWorkerStack").all {final.optJSONObject(it)?.opt("alive")==false}&&
            final.isNull("originalFailureClass")&&final.isNull("closeErrorClass")&&callback.opt("mainResponded")==true&&
            callback.opt("callbackMayStillBeInFlight")==false&&callback.optJSONObject("value")?.opt("callbackUnregistered")==true
    }
    private fun noWriter(context: Context,pid: Int) {
        require(pid>1&&pid!=Process.myPid())
        try {Os.stat("/proc/$pid");error("Original diagnostic PID exists or was reused: preserve")}
        catch(e: ErrnoException){require(e.errno==OsConstants.ENOENT)}
        val all=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
        require(all!=null&&all.any {it.uid==Process.myUid()&&it.pid==Process.myPid()}&&all.none {it.uid==Process.myUid()&&it.pid!=Process.myPid()})
    }
    private fun snapshot(context: Context): JSONObject {
        val rows=JSONArray();var present=false;var count=0;var bytes=0L
        Startup1aCleanupDirectory.openApplicationFiles(context).use {f->f.directory("q02-real-dev-profiles-v1").use {p->
            if(run in p.names()) {
                present=true;val root=p.stat(run);require(root.directory&&!root.symlink)
                fun walk(d: FreshQ04CleanupTree.Directory,path: String,key: String) {
                    require(path in directories);rows.put(JSONObject().put("path",path).put("kind","directory").put("bytes",0).put("sha256","").put("statKey",key))
                    val names=d.names();require(names.size<=32)
                    for(name in names.sorted()) {
                        val full=if(path.isEmpty())name else "$path/$name";val s=d.stat(name);require(!s.symlink)
                        if(s.directory){require(full in directories);d.directory(name).use {walk(it,full,s.key)}}
                        else {
                            require(s.regular&&full.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin"))&&s.bytes in 29..1048576)
                            count++;bytes+=s.bytes;require(count<=32&&bytes<=1048576)
                            rows.put(JSONObject().put("path",full).put("kind","file").put("bytes",s.bytes).put("sha256",d.hash(name,s)).put("statKey",s.key))
                        }
                        require(d.stat(name)==s)
                    };require(d.names()==names)
                }
                p.directory(run).use {walk(it,"",root.key)};require(p.stat(run)==root)
            }
        }}
        return JSONObject().put("profilePresent",present).put("inventory",rows).put("cipherFiles",count).put("cipherBytes",bytes)
    }
    private fun validateSnapshot(v: JSONObject) {
        exact(v,"profilePresent","inventory","cipherFiles","cipherBytes")
        val rows=objects(v.getJSONArray("inventory"));require(rows.size<=39&&rows.map {text(it,"path")}.distinct().size==rows.size)
        val dirs=rows.filter {text(it,"kind")=="directory"}.map {text(it,"path")}.toSet();var count=0L;var total=0L
        for(r in rows) {
            exact(r,"path","kind","bytes","sha256","statKey");require(text(r,"statKey").matches(Regex("[0-9:]{1,256}")))
            val path=text(r,"path");require(path.isEmpty()||path.substringBeforeLast('/',"") in dirs)
            if(text(r,"kind")=="directory")require(path in directories&&number(r,"bytes")==0L&&text(r,"sha256").isEmpty())
            else {require(text(r,"kind")=="file"&&path.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin"))&&number(r,"bytes") in 29..1048576);hash(text(r,"sha256"));count++;total+=number(r,"bytes")}
        }
        require(count==number(v,"cipherFiles")&&total==number(v,"cipherBytes")&&count<=32&&total<=1048576)
        require(if(bool(v,"profilePresent")) "" in dirs else rows.isEmpty())
    }
    private fun pinnedTree(context: Context,v: JSONObject): FreshQ04CleanupTree {
        val expected=objects(v.getJSONArray("inventory")).associateBy {text(it,"path")}
        fun wrap(d: FreshQ04CleanupTree.Directory,prefix: String?): FreshQ04CleanupTree.Directory {
            fun full(n: String)=if(prefix==null){require(n==run);""}else if(prefix.isEmpty())n else "$prefix/$n"
            return object: FreshQ04CleanupTree.Directory {
                override fun names()=d.names()
                override fun stat(name: String): FreshQ04CleanupTree.Stat {val row=expected[full(name)] ?: error("Unknown material: preserve");return d.stat(name).also {require(it.key==text(row,"statKey"))}}
                override fun directory(name: String): FreshQ04CleanupTree.Directory {stat(name);return wrap(d.directory(name),full(name))}
                override fun hash(name: String,expected: FreshQ04CleanupTree.Stat)=d.hash(name,expected)
                override fun unlink(name: String,expected: FreshQ04CleanupTree.Stat){require(stat(name)==expected);d.unlink(name,expected)}
                override fun rmdir(name: String,expected: FreshQ04CleanupTree.Stat){require(stat(name)==expected);d.rmdir(name,expected)}
                override fun close()=d.close()
            }
        }
        return FreshQ04CleanupTree({wrap(Startup1aCleanupDirectory.openApplicationFiles(context).use {it.directory("q02-real-dev-profiles-v1")},null)},run)
    }
    private fun plan(v: JSONObject)=FreshQ04CleanupPlan(run,objects(v.getJSONArray("inventory")).map {FreshQ04CleanupEntry(text(it,"path"),text(it,"kind")=="directory",number(it,"bytes"),text(it,"sha256"))})
    private fun marker(p: Path): String? {
        if(Files.notExists(p,NOFOLLOW_LINKS))return null
        val b=read(p,65);require(b.size==65&&b.last()==10.toByte());return String(b,Charsets.US_ASCII).trimEnd('\n').also {hash(it)}
    }
    private fun claim(p: Path,h: String,stable: ()->Unit) {
        stable();FileChannel.open(p,setOf(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,NOFOLLOW_LINKS),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))).use {c->val b=ByteBuffer.wrap((h+"\n").toByteArray(Charsets.US_ASCII));while(b.hasRemaining())require(c.write(b)>0);c.force(true)}
        val fd=Os.open(p.parent.toString(),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try{Os.fsync(fd)}finally{Os.close(fd)};stable();require(marker(p)==h)
    }
    @Test fun queryOrDeleteOnlyRootReviewedStartup1aProfileOnce() {
        val args=InstrumentationRegistry.getArguments();require(args.getString("q04Startup1aCleanupReady")=="1") {"Disabled; no KeyStore access"}
        require(args.getString("nativeForeground")=="0"&&args.getString("nativeThreadSnapshots")=="0")
        require(args.keySet().none {it.startsWith("w3")||it.startsWith("freshQ04")||it.startsWith("q04StartupDiag")})
        val mode=checkNotNull(args.getString("q04Startup1aCleanupMode"));require(mode in setOf("query","delete"))
        val operation=checkNotNull(args.getString("cleanupOperationId"));require(java.util.UUID.fromString(operation).toString()==operation&&operation!=diagnosticOperation&&operation!=run)
        val manifest=checkNotNull(args.getString("rootCleanupManifestSha256"));hash(manifest)
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance"&&instrumentation.context.packageName==context.packageName+".test"&&context.applicationInfo.uid==Process.myUid()&&Process.myUid()==10249)
        Startup1aCleanupDirectory.openApplicationFiles(context).use { }
        val base=context.filesDir.canonicalFile.toPath();val staging=base.resolve("acceptance/q04-no-model-startup1a-owned-cleanup-v1");val input=staging.resolve(operation)
        val dirs=listOf(base.resolve("acceptance"),staging,input,staging.resolve("once"));val pins=dirs.map {directoryKey(it)}
        fun stable(){require(dirs.map {directoryKey(it)}==pins)}
        val raw=read(input.resolve("root-review.json"),131072);require(sha(raw)==manifest);val v=JSONObject(String(raw,Charsets.UTF_8))
        exact(v,"version","kind","enabled","rootReviewed","allowedMode","operationId","issuedAtEpochMs","expiresAtEpochMs","diagnosticRun","rootAlias","diagnosticLeaseId","diagnosticOperationId","currentInstallation","sourceReviewRef","rootFacts","preDeleteQuery","deletePermit","archive")
        require(number(v,"version")==1L&&text(v,"kind")=="root-q04-no-model-startup1a-owned-cleanup-review-v1"&&bool(v,"enabled")&&bool(v,"rootReviewed"))
        require(text(v,"operationId")==operation&&text(v,"diagnosticRun")==run&&text(v,"rootAlias")==alias&&text(v,"diagnosticLeaseId")==diagnosticLease&&text(v,"diagnosticOperationId")==diagnosticOperation)
        val allowed=text(v,"allowedMode");require(allowed in setOf("query","delete")&&(mode!="delete"||allowed=="delete"))
        val issued=number(v,"issuedAtEpochMs");val expires=number(v,"expiresAtEpochMs");val now=System.currentTimeMillis();require(issued in now-60000..now&&expires in issued+1..issued+300000&&now<expires)
        installation(context,v.getJSONObject("currentInstallation"));ref(v.getJSONObject("sourceReviewRef"))
        val (factsBytes,facts)=artifact(input,v.getJSONObject("rootFacts"),"startup1a-cleanup-root-facts.json")
        exact(facts,"kind","rootReviewed","diagnosticRun","diagnosticLeaseId","diagnosticOperationId","originalPid","observedAtEpochMs","originalPidAbsent","appUidEmptyBeforeCleanup","allOtherProfilesAndEvidencePreserved","noModelsNoHostIndependentlyReviewed","diagnosticOutcome","diagnosticJUnitPassed","diagnosticSourceRef","diagnosticNativeRawAuditRef","originalReviewRef","probeOnceRef","probeFinalRef","freshProcessObservationRef","noModelsNoHostReviewRef","protectionReviewRef","startupSuccessReviewRef","originalFullProtectionReviewRef")
        require(text(facts,"kind")=="root-q04-no-model-startup1a-cleanup-facts-v1"&&bool(facts,"rootReviewed"))
        require(text(facts,"diagnosticRun")==run&&text(facts,"diagnosticLeaseId")==diagnosticLease&&text(facts,"diagnosticOperationId")==diagnosticOperation)
        require(number(facts,"observedAtEpochMs") in issued-60000..issued)
        require(listOf("originalPidAbsent","appUidEmptyBeforeCleanup","allOtherProfilesAndEvidencePreserved","noModelsNoHostIndependentlyReviewed").all {bool(facts,it)})
        require(text(facts,"diagnosticOutcome") in setOf("passed","failed","lost","unknown"));require(bool(facts,"diagnosticJUnitPassed")== (text(facts,"diagnosticOutcome")=="passed"))
        for(k in listOf("diagnosticSourceRef","diagnosticNativeRawAuditRef","originalReviewRef","probeOnceRef","freshProcessObservationRef","noModelsNoHostReviewRef","protectionReviewRef","startupSuccessReviewRef","originalFullProtectionReviewRef"))ref(facts.getJSONObject(k))
        require(number(facts.getJSONObject("diagnosticSourceRef"),"bytes")==31922L&&text(facts.getJSONObject("diagnosticSourceRef"),"sha256")==sourceSha)
        require(number(facts.getJSONObject("startupSuccessReviewRef"),"bytes")==8615L&&text(facts.getJSONObject("startupSuccessReviewRef"),"sha256")=="c0104a970e889b088640465a8564514a7781895dc13f6677110a474b06e5b11d")
        require(number(facts.getJSONObject("originalFullProtectionReviewRef"),"bytes")==1984L&&text(facts.getJSONObject("originalFullProtectionReviewRef"),"sha256")=="123b6c0cc7deb35e5e03980d648bcb7a3f93b1c9e1110a5ff07ca9c61b272e96")
        val d=diagnostic(context);val original=checkNotNull(d.documents["root-review.json"]);val onceDoc=checkNotNull(d.documents["probe-once.json"])
        fun leaf(name: String)=objects(d.snapshot.getJSONArray("leaves")).single {text(it,"name")==name}
        fun match(name: String,r: JSONObject){ref(r);val actual=leaf(name);require(number(r,"bytes")==number(actual,"bytes")&&text(r,"sha256")==text(actual,"sha256"))}
        match("root-review.json",facts.getJSONObject("originalReviewRef"));match("probe-once.json",facts.getJSONObject("probeOnceRef"))
        if(d.documents.containsKey("probe-final.json"))match("probe-final.json",facts.getJSONObject("probeFinalRef"))else require(facts.isNull("probeFinalRef"))
        require(original.get("kind")=="root-q04-no-model-q02-startup-review-v2"&&original.opt("enabled")==true&&original.opt("rootConfirmed")==true&&original.opt("executionAuthorized")==true&&original.opt("preparedOnly")==false)
        require(original.get("diagnosticRun")==run&&original.get("leaseId")==diagnosticLease&&original.get("operationId")==diagnosticOperation&&original.get("acceptancePackage")==context.packageName&&original.get("appSha256")==originalApp)
        require(listOf("noModels","noHostOperations","noPairing","noProviderInjection","temporarySinglePermissionDelegationApproved").all {original.opt(it)==true})
        require(original.get("temporaryPermissionName")=="android.permission.START_ACTIVITIES_FROM_BACKGROUND"&&original.get("runnerSha256")=="c917763efcf3ef9bcc60369fe604d1a955495a04d265570fbbc94cc7b262d024")
        exact(onceDoc,"kind","run","operationId","pid","startedAtElapsedMs","noModels","noHostOperations")
        require(text(onceDoc,"kind")=="q04-no-model-startup-attempt-v1"&&text(onceDoc,"run")==run&&text(onceDoc,"operationId")==diagnosticOperation&&bool(onceDoc,"noModels")&&bool(onceDoc,"noHostOperations"))
        val pid=number(onceDoc,"pid");require(pid==24705L&&pid==number(facts,"originalPid"))
        val closure=if(positiveClosure(d,facts))"positive-closed" else "failure-or-unknown"
        stable();noWriter(context,pid.toInt())
        val oncePath=staging.resolve("once").resolve("$run.intent");val prior=marker(oncePath);require(prior==null||prior==manifest)
        val current=snapshot(context);validateSnapshot(current);var approved: JSONObject?=null
        if(allowed=="query")require(prior==null&&v.isNull("preDeleteQuery")&&v.isNull("deletePermit")&&v.isNull("archive"))
        else {
            val (queryBytes,q)=artifact(input,v.getJSONObject("preDeleteQuery"),"pre-delete-query.json")
            exact(q,"kind","diagnosticRun","operationId","manifestSha256","mode","allowedMode","closureClass","diagnosticOutcome","processQuiescentNow","originalPid","originalEvidence","intentPresent","snapshot","rootAliasState","aliasFailureClass","completeAtPublicApiObservation","absoluteHardwareErasureProven","allUnnamedDerivedKeysProvenAbsent","otherAliasesEnumerated","originalDiagnosticOutcomeUnchanged")
            require(text(q,"kind")=="q04-no-model-startup1a-cleanup-native-observation-v1"&&text(q,"diagnosticRun")==run&&text(q,"mode")=="query"&&text(q,"allowedMode")=="query"&&text(q,"operationId")!=operation)
            require(java.util.UUID.fromString(text(q,"operationId")).toString()==text(q,"operationId"));hash(text(q,"manifestSha256"))
            require(text(q,"closureClass")==closure&&text(q,"diagnosticOutcome")==text(facts,"diagnosticOutcome")&&number(q,"originalPid")==pid&&bool(q,"processQuiescentNow"))
            require(!bool(q,"intentPresent")&&!bool(q,"completeAtPublicApiObservation")&&!bool(q,"absoluteHardwareErasureProven")&&!bool(q,"allUnnamedDerivedKeysProvenAbsent")&&!bool(q,"otherAliasesEnumerated")&&bool(q,"originalDiagnosticOutcomeUnchanged"))
            require(text(q,"rootAliasState") in setOf("present","absent")&&q.isNull("aliasFailureClass"));same(q.getJSONObject("originalEvidence"),d.snapshot)
            val s=q.getJSONObject("snapshot");validateSnapshot(s);require(bool(s,"profilePresent"));approved=s
            val permit=v.getJSONObject("deletePermit")
            exact(permit,"rootReviewed","querySha256","closureClass","allowFailureOrUnknownClosureDeletion","onlyOwnedRoot","onlyExactRootAlias","allowEmptyProfileDeletion","deleteRootAliasIfPresent","noReplayAfterUnknown","rawArchiveComplete","sourceReviewRef")
            require(listOf("rootReviewed","onlyOwnedRoot","onlyExactRootAlias","noReplayAfterUnknown","rawArchiveComplete").all {bool(permit,it)}&&text(permit,"querySha256")==sha(queryBytes)&&text(permit,"closureClass")==closure);ref(permit.getJSONObject("sourceReviewRef"))
            require(closure=="positive-closed"&&!bool(permit,"allowFailureOrUnknownClosureDeletion")) // No hypothetical failure/unknown DELETE scope for this fixed successful diagnostic.
            require(number(s,"cipherFiles")>0||bool(permit,"allowEmptyProfileDeletion"));require(bool(permit,"deleteRootAliasIfPresent")== (text(q,"rootAliasState")=="present"))
            val a=v.getJSONObject("archive");exact(a,"query","metadata","rootFacts","originalDiagnosticLeaves","profileFiles")
            for(k in listOf("query","metadata","rootFacts"))archive(a.getJSONObject(k))
            for(k in listOf("query","metadata"))require(text(a.getJSONObject(k),"sha256")==sha(queryBytes)&&number(a.getJSONObject(k),"bytes")==queryBytes.size.toLong())
            require(text(a.getJSONObject("rootFacts"),"sha256")==sha(factsBytes)&&number(a.getJSONObject("rootFacts"),"bytes")==factsBytes.size.toLong())
            fun archiveRows(rows: JSONArray,expected: List<JSONObject>,field: String) {
                val values=objects(rows);require(values.map {text(it,field)}.distinct().size==values.size&&values.map {text(it,field)}.toSet()==expected.map {text(it,field)}.toSet())
                for(row in values){exact(row,field,"ref");val r=row.getJSONObject("ref");archive(r);val e=expected.single {text(it,field)==text(row,field)};require(number(r,"bytes")==number(e,"bytes")&&text(r,"sha256")==text(e,"sha256"))}
            }
            archiveRows(a.getJSONArray("originalDiagnosticLeaves"),objects(d.snapshot.getJSONArray("leaves")),"name")
            archiveRows(a.getJSONArray("profileFiles"),objects(s.getJSONArray("inventory")).filter {text(it,"kind")=="file"},"path")
            if(prior==null)same(current,s)else {
                val expected=objects(s.getJSONArray("inventory")).associateBy {text(it,"path")}
                for(r in objects(current.getJSONArray("inventory")))same(r,expected[text(r,"path")] ?: error("Unknown partial material: preserve"))
            }
            if(mode=="delete")require(prior==null)
        }
        require(System.currentTimeMillis()<expires)
        val keys=KeyStore.getInstance("AndroidKeyStore");var state="unknown";var failure: String?=null
        try{keys.load(null);state=if(keys.containsAlias(alias))"present" else "absent"}catch(e: Exception){failure=e.javaClass.name.take(160)}
        if(mode=="delete") {
            require(state!="unknown");val q=JSONObject(String(read(input.resolve("pre-delete-query.json"),65_536),Charsets.UTF_8));require(state==text(q,"rootAliasState"))
            stable();noWriter(context,pid.toInt());require(System.currentTimeMillis()<expires);same(diagnostic(context).snapshot,d.snapshot);same(snapshot(context),checkNotNull(approved))
            claim(oncePath,manifest,::stable)
            pinnedTree(context,checkNotNull(approved)).remove(plan(checkNotNull(approved)))
            if(state=="present")keys.deleteEntry(alias)
            state=if(keys.containsAlias(alias))"present" else "absent"
        }
        val after=if(mode=="delete")snapshot(context) else current;same(diagnostic(context).snapshot,d.snapshot);val intent=marker(oncePath)!=null
        val complete=mode=="delete"&&allowed=="delete"&&intent&&!bool(after,"profilePresent")&&state=="absent"
        val report=JSONObject().put("kind","q04-no-model-startup1a-cleanup-native-observation-v1").put("diagnosticRun",run).put("operationId",operation).put("manifestSha256",manifest).put("mode",mode).put("allowedMode",allowed)
            .put("closureClass",closure).put("diagnosticOutcome",text(facts,"diagnosticOutcome")).put("processQuiescentNow",true).put("originalPid",pid).put("originalEvidence",d.snapshot).put("intentPresent",intent).put("snapshot",after).put("rootAliasState",state).put("aliasFailureClass",failure ?: JSONObject.NULL)
            .put("completeAtPublicApiObservation",complete).put("absoluteHardwareErasureProven",false).put("allUnnamedDerivedKeysProvenAbsent",false).put("otherAliasesEnumerated",false).put("originalDiagnosticOutcomeUnchanged",true)
        instrumentation.sendStatus(73,Bundle().apply {putString("q04Startup1aCleanupObservation",report.toString())})
        require(state!="unknown") {"Exact alias state unknown: preserve"}
        if(mode=="delete")require(complete) {"Partial/unknown cleanup: retain once; query exact DELETE manifest only while Root scope valid, never replay"}
    }
}

private class Startup1aCleanupDirectory private constructor(private val held: ParcelFileDescriptor,private val device: Long,
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
    override fun directory(name: String): Startup1aCleanupDirectory {
        val before=stat(name);require(before.directory && !before.symlink)
        val fd=Os.open(path(name),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        val dup=try {ParcelFileDescriptor.dup(fd)}finally{Os.close(fd)}
        try {require(checked(Os.fstat(dup.fileDescriptor),true)==before && stat(name)==before);return Startup1aCleanupDirectory(dup,device)}
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
        fun openApplicationFiles(context: Context): Startup1aCleanupDirectory {
            require(context===InstrumentationRegistry.getInstrumentation().targetContext)
            require(BuildConfig.DEBUG && context.packageName=="com.easyai.agenthub.android.acceptance" && context.applicationInfo.uid==Process.myUid())
            require(!Files.isSymbolicLink(context.filesDir.toPath()))
            val path=context.filesDir.canonicalFile.toPath()
            val before=Os.lstat(path.toString())
            require(F73FoundationDirectoryMode.admits(before.st_mode,before.st_uid,before.st_gid,Process.myUid(),OsConstants.S_ISDIR(before.st_mode),OsConstants.S_ISLNK(before.st_mode),true))
            val fd=Os.open(path.toString(),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            val dup=try {ParcelFileDescriptor.dup(fd)}finally{Os.close(fd)}
            val result=Startup1aCleanupDirectory(dup,before.st_dev,applicationFilesFoundation=true)
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

