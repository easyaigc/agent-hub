package com.agenthub.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Closed API33 Main-subclass contract. Source/tool pins are Host facts; installed bytes,
 * current signers, UID, boot and original process tuple are measured independently here.
 * No source pin or local rejected port is represented as network/desktop proof. */
internal object NativeP8MainAdmission {
    const val PROFILE="p8-main-preference"
    const val MODE="main-subclass-menu"
    const val PACKAGE="com.easyai.agenthub.android.acceptance"
    const val TEST_PACKAGE="$PACKAGE.test"
    const val METHOD="savedPreferenceThroughMainSubclassMenuPersistsWithoutNativeRuntime"
    const val SELECTOR="com.agenthub.android.NativeP8MainPreferenceTest#$METHOD"
    private const val SCHEMA="p8-main-preference-admission-v1"
    private val argumentKeys=setOf("class","p8Mode","p8Run","p8AdmissionSha256","p8AdmissionJson")
    private val hashes=setOf("sourceGuardSha256","reviewedStateSha256","sdkSealSha256","adapterSourceSha256",
        "captureToolSha256","sourceToolSha256","testSourceSha256","contractSourceSha256","activitySourceSha256",
        "scopeBoundarySourceSha256","appApkSha256","testApkSha256","certificateSha256","adbSha256","serialSha256")
    private val fixedKeys=setOf("schema","profile","mode","run","selector","package","testPackage","uid","testUid",
        "bootUuid","deviceApi","cases","nativeRuntime","shippingMainCovered")
    private fun exact(value: JSONObject,keys: Set<String>) {check(value.keys().asSequence().toSet()==keys)}
    private fun string(value: JSONObject,key: String,cap: Int=512)=(value.get(key) as String).also {
        check(it.length in 1..cap&&it.none {c->c.code<32})
    }
    private fun uuid(value: String)=value.also {check(UUID.fromString(it).toString()==it)}
    private fun hash(value: String)=value.also {check(it.matches(Regex("[a-f0-9]{64}")))}
    private fun sha(value: ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it)}
    private fun number(value: JSONObject,key: String)=(value.get(key) as Int).also {check(it>0)}

    // Each actual descriptor is retained before fallible acquisition/close. UNKNOWN is never retried,
    // converted to a software hold, or removed by a later read; original process exit is the boundary.
    private class ReadOwner(val run: String,val path: String) {
        val pid=Process.myPid();val nonce=NativeChatNotificationColdStart.processNonce
        var descriptor: FileDescriptor?=null;var openAttempted=false;var closeAttempted=false;var closeConfirmed=false
        var failure: Throwable?=null
    }
    private val reads=CopyOnWriteArrayList<ReadOwner>()
    private fun noUnknown() {check(reads.isEmpty()) {"Unconfirmed original admission read; no retry"}}
    private fun <T> read(run: String,path: String,body: (FileDescriptor)->T): T {
        noUnknown();val owner=ReadOwner(run,path);reads.add(owner);owner.openAttempted=true
        val fd=try {Os.open(path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0).also {owner.descriptor=it}}
            catch(error: Throwable){owner.failure=error;throw error}
        var first: Throwable?=null
        try {return body(fd)}catch(error: Throwable){first=error;owner.failure=error;throw error}
        finally {
            owner.closeAttempted=true
            try {Os.close(fd);owner.closeConfirmed=true;reads.remove(owner)}catch(close: Throwable){
                owner.failure=first?:close
                if(first==null)throw close
                if(first!==close)first.addSuppressed(close)
            }
        }
    }
    private fun proc(run: String,path: String): ByteArray=read(run,path){fd->
        val output=ByteArrayOutputStream();val buffer=ByteArray(1024)
        while(true){val size=Os.read(fd,buffer,0,buffer.size);check(size>=0);if(size==0)break
            check(output.size()+size<=4096);output.write(buffer,0,size)}
        output.toByteArray().also {check(it.isNotEmpty())}
    }
    private fun apk(run: String,path: String): String {
        check(path.startsWith("/data/app/")&&path.endsWith("/base.apk")&&File(path).isAbsolute)
        val before=Os.lstat(path)
        check(OsConstants.S_ISREG(before.st_mode)&&before.st_nlink>=1L&&before.st_size in 1L..268435456L)
        fun identity(row: android.system.StructStat)=listOf(row.st_dev,row.st_ino,row.st_mode.toLong(),row.st_uid.toLong(),row.st_nlink,row.st_size,row.st_mtime,row.st_ctime)
        return read(run,path){fd->
            check(identity(Os.fstat(fd))==identity(before));val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);var total=0L
            while(true){check(identity(Os.fstat(fd))==identity(before));val size=Os.read(fd,buffer,0,buffer.size);check(size>=0)
                if(size==0)break;total+=size;check(total<=before.st_size);digest.update(buffer,0,size)}
            check(total==before.st_size&&identity(Os.fstat(fd))==identity(before)&&identity(Os.lstat(path))==identity(before))
            digest.digest().joinToString(""){"%02x".format(it)}
        }
    }
    private fun packageFacts(context: Context,run: String,name: String): JSONObject {
        @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES)
        val app=checkNotNull(info.applicationInfo);val signing=checkNotNull(info.signingInfo)
        check(info.packageName==name&&app.packageName==name&&app.splitSourceDirs.isNullOrEmpty()&&!signing.hasMultipleSigners())
        val certificate=signing.apkContentsSigners.single()
        return JSONObject().put("uid",app.uid).put("apkSha256",apk(run,app.sourceDir)).put("certificateSha256",sha(certificate.toByteArray()))
    }
    private fun facts(context: Context,run: String): JSONObject {
        val pid=Process.myPid();val boot=uuid(proc(run,"/proc/sys/kernel/random/boot_id").toString(Charsets.UTF_8).trim())
        val stat=proc(run,"/proc/$pid/stat").toString(Charsets.UTF_8);check(stat.startsWith("$pid ("))
        val ticks=stat.substringAfterLast(") ").trim().split(Regex("\\s+"))[19];check(ticks.matches(Regex("[0-9]{1,24}")))
        val argv=proc(run,"/proc/$pid/cmdline");check(argv.last()==0.toByte()&&argv.dropLastWhile {it==0.toByte()}.toByteArray().contentEquals(PACKAGE.toByteArray(Charsets.UTF_8)))
        val app=packageFacts(context,run,PACKAGE);val test=packageFacts(context,run,TEST_PACKAGE)
        check(app.getInt("uid")==Process.myUid()&&test.getInt("uid")>0&&app.getString("certificateSha256")==test.getString("certificateSha256"))
        val finalStat=proc(run,"/proc/$pid/stat").toString(Charsets.UTF_8)
        check(finalStat.startsWith("$pid (")&&finalStat.substringAfterLast(") ").trim().split(Regex("\\s+"))[19]==ticks)
        check(uuid(proc(run,"/proc/sys/kernel/random/boot_id").toString(Charsets.UTF_8).trim())==boot)
        return JSONObject().put("package",PACKAGE).put("uid",Process.myUid()).put("testUid",test.getInt("uid"))
            .put("bootUuid",boot).put("pid",pid).put("startTicks",ticks).put("argvSha256",sha(argv))
            .put("processNonce",NativeChatNotificationColdStart.processNonce).put("deviceApi",android.os.Build.VERSION.SDK_INT)
            .put("appApkSha256",app.getString("apkSha256")).put("testApkSha256",test.getString("apkSha256"))
            .put("certificateSha256",app.getString("certificateSha256"))
    }
    class Accepted internal constructor(val body: JSONObject,val admissionSha256: String,val initialFacts: JSONObject) {
        val run: String get()=body.getString("run")
        fun case(index: Int)=body.getJSONArray("cases").getJSONObject(index)
        fun emit(phase: String,details: JSONObject) {
            check(phase in setOf("admission-accepted","before-scope-acquisition","scope-created-before-key-and-Main","inherited-Main-restored",
                "typed-port-proof-before-owner-release","owners-confirmed-or-retained-before-scope-cleanup","complete-bounded-scope-inventory-before-delete",
                "retained-to-original-process-exit","complete"))
            val evidence=JSONObject().put("schema","p8-main-preference-evidence-v1").put("rootRun",run)
                .put("admissionSha256",admissionSha256).put("phase",phase).put("body",details)
            val text=evidence.toString();check(text.toByteArray(Charsets.UTF_8).size<=65536)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {putString("p8Evidence",text)})
        }
        fun finish(cases: JSONArray) {
            check(cases.length()==2);val now=NativeP8MainAdmission.facts(InstrumentationRegistry.getInstrumentation().targetContext,run)
            exact(now,initialFacts.keys().asSequence().toSet());now.keys().asSequence().forEach {check(now.get(it)==initialFacts.get(it))}
            NativeP8MainAdmission.noUnknown()
            emit("complete",JSONObject().put("nativeFacts",now).put("cases",cases).put("nativeRuntime",false).put("shippingMainCovered",false))
        }
    }
    fun admit(): Accepted {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments();check(args.keySet()==argumentKeys)
        check(args.getString("class")==SELECTOR&&args.getString("p8Mode")==MODE)
        val run=uuid(checkNotNull(args.getString("p8Run")));val raw=checkNotNull(args.getString("p8AdmissionJson")).toByteArray(Charsets.UTF_8)
        check(raw.size in 1..8192);val pin=hash(checkNotNull(args.getString("p8AdmissionSha256")));check(sha(raw)==pin)
        val body=StrictMigrationJson.parse(raw);exact(body,fixedKeys+hashes)
        check(string(body,"schema")==SCHEMA&&string(body,"profile")==PROFILE&&string(body,"mode")==MODE&&uuid(string(body,"run"))==run)
        check(string(body,"selector")==SELECTOR&&string(body,"package")==PACKAGE&&string(body,"testPackage")==TEST_PACKAGE)
        hashes.forEach {hash(string(body,it,64))};uuid(string(body,"bootUuid"));number(body,"uid");number(body,"testUid")
        check(body.get("deviceApi")==33&&body.get("nativeRuntime")==false&&body.get("shippingMainCovered")==false)
        val cases=body.getJSONArray("cases");check(cases.length()==2)
        val ids=mutableSetOf(run)
        listOf("zh-Hans" to "light","en" to "dark").forEachIndexed {index,(language,appearance)->
            val row=cases.getJSONObject(index);exact(row,setOf("run","language","appearance"))
            check(ids.add(uuid(string(row,"run")))&&string(row,"language")==language&&string(row,"appearance")==appearance)
        }
        check(android.os.Build.VERSION.SDK_INT==33&&BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID==PACKAGE&&context.packageName==PACKAGE)
        check(instrumentation.context.packageName==TEST_PACKAGE&&context.applicationInfo.uid==Process.myUid()&&context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        check(NativeChatNotificationColdStart.activityEntries.get()==0) {"Fresh ordinary target process required"}
        val actual=facts(context,run)
        for(key in listOf("package","uid","testUid","bootUuid","deviceApi","appApkSha256","testApkSha256","certificateSha256"))check(actual.get(key)==body.get(key))
        return Accepted(body,pin,actual).also {it.emit("admission-accepted",JSONObject().put("binding",body).put("nativeFacts",actual))}
    }
}
