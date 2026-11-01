package com.agenthub.android

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.profileinstaller.ProfileVerifier
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Fresh owned AVD only. Initializes the real public cache API before the quota baseline. */
class NativeMigrationProfilePrewarmTest {
    private companion object {
        const val HEAP_CEILING=48L*1024*1024
        const val STABILITY_MS=8000L
        const val NO_EMBEDDED_PROFILE=327680
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun stat(file:File)=Os.lstat(file.path).let {s->listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_nlink,s.st_uid.toLong(),s.st_gid.toLong(),s.st_size,s.st_mtime,s.st_ctime)}
    private fun directory(file:File) {
        require(Files.isDirectory(file.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file.toPath()))
        val s=Os.lstat(file.path)
        require(s.st_uid==Process.myUid()&&s.st_gid==Process.myUid()&&s.st_mode and 0x1ff==0x1c0)
    }
    private data class Cache(val bytes:ByteArray,val identity:List<Long>,val schema:Int,val resultCode:Int,val packageUpdateTime:Long,val currentProfileSize:Long) {
        fun json()=JSONObject().put("stat",JSONArray(identity)).put("sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
            .put("schema",schema).put("resultCode",resultCode).put("packageLastUpdateTime",packageUpdateTime).put("installedCurrentProfileSize",currentProfileSize)
    }
    private fun cache(file:File):Cache {
        require(!Files.isSymbolicLink(file.toPath()))
        val before=stat(file)
        val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0)
        val bytes=ByteArray(24)
        FileInputStream(fd).use {input->
            val s=Os.fstat(fd)
            require(OsConstants.S_ISREG(s.st_mode)&&s.st_size==24L&&s.st_nlink==1L&&s.st_mode and 0x1ff==0x180&&s.st_uid==Process.myUid()&&s.st_gid==Process.myUid())
            require(listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_nlink,s.st_uid.toLong(),s.st_gid.toLong(),s.st_size,s.st_mtime,s.st_ctime)==before)
            DataInputStream(input).apply {readFully(bytes);require(read()==-1)}
        }
        require(stat(file)==before)
        return DataInputStream(ByteArrayInputStream(bytes)).use {input->
            val result=Cache(bytes,before,input.readInt(),input.readInt(),input.readLong(),input.readLong())
            require(input.read()==-1&&result.schema==1&&result.currentProfileSize>=0);result
        }
    }
    private fun publish(root:File,name:String,value:JSONObject) {
        require(name in setOf("attempt","complete"))
        val bytes=value.toString().toByteArray(Charsets.UTF_8);require(bytes.size in 1..16384)
        val file=File(root,"$name.json")
        val fd=Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,0x180)
        FileOutputStream(fd).use {it.write(bytes);it.fd.sync()}
    }

    @Test fun publicVerifierSettlesFreshAcceptanceCacheBeforeLowHeapQuotaBaseline() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val app=instrumentation.targetContext
        require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance"&&app.packageName==BuildConfig.APPLICATION_ID&&app.applicationInfo.uid==Process.myUid())
        require(instrumentation.context.packageName==app.packageName+".test")
        require(Looper.myLooper()!=Looper.getMainLooper()) {"Public ProfileVerifier API is WorkerThread"}
        val run=requireNotNull(InstrumentationRegistry.getArguments().getString("lowHeapProfileBootstrapRun")).also {require(UUID.fromString(it).toString()==it)}
        require(!InstrumentationRegistry.getArguments().containsKey("source82QuotaRun")&&!InstrumentationRegistry.getArguments().containsKey("source82Require48MiB"))
        val maxMemory=Runtime.getRuntime().maxMemory()
        val gate=JSONObject().put("version",1).put("scenario","fresh-public-profile-cache-before-low-heap-v1").put("phase","before-bootstrap-files")
            .put("run",run).put("actualMaxMemoryBytes",maxMemory).put("heapCeilingBytes",HEAP_CEILING).put("pid",Process.myPid())
            .put("targetPackage",app.packageName).put("targetUid",app.applicationInfo.uid).put("processUid",Process.myUid()).put("testPackage",instrumentation.context.packageName)
            .put("largeHeapDeclared",app.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP!=0)
        println("P7_LOW_HEAP_BOOTSTRAP_GATE "+gate)
        instrumentation.sendStatus(2,Bundle().apply {putString("p7LowHeapBootstrapGate",gate.toString())})
        require(app.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP==0&&maxMemory>0&&maxMemory<=HEAP_CEILING) {"Actual ordinary app heap must be at most 50331648 before bootstrap"}
        // Direct and target-context lookups must agree; SDK review separately rejects test-APK duplicates.
        require(Class.forName("androidx.profileinstaller.ProfileVerifier",false,app.classLoader)===ProfileVerifier::class.java)
        // The operator creates these exact 0700 directories before any instrumentation.
        // applicationInfo avoids getFilesDir silently creating a default-mode directory here.
        val files=File(app.applicationInfo.dataDir,"files");val acceptance=File(files,"acceptance")
        directory(files);directory(acceptance)
        require(files.canonicalFile==File(File(app.applicationInfo.dataDir).canonicalFile,"files")&&acceptance.canonicalFile==File(files.canonicalFile,"acceptance"))
        val beforeNames=requireNotNull(files.list()).toSet()
        require(beforeNames.all {it in setOf("acceptance","profileInstalled")}&&requireNotNull(acceptance.list()).isEmpty()) {"Fresh owned acceptance data required; never touch Source82 or other fixtures"}
        require(app.assets.list("dexopt").orEmpty().isEmpty()) {"This finite cache protocol is for the current no-embedded-profile APK"}
        require(Files.notExists(File(app.applicationInfo.dataDir,"no_backup").toPath(),NOFOLLOW_LINKS)) {"No existing backup originals are permitted in this fresh fixture"}
        val updateTime=app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime
        val marker=File(files,"profileInstalled")
        // Shipping initialization can finish before @Test. Accept only its exact current-install cache.
        val old=if(Files.notExists(marker.toPath(),NOFOLLOW_LINKS))null else cache(marker).also {
            assertEquals(NO_EMBEDDED_PROFILE,it.resultCode);assertEquals(updateTime,it.packageUpdateTime)
        }
        val skip=File(files,"profileinstaller_profileWrittenFor_lastUpdateTime.dat")
        require(Files.notExists(skip.toPath(),NOFOLLOW_LINKS))
        val root=File(acceptance,"P7-low-heap-profile-bootstrap-$run")
        require(Files.notExists(root.toPath(),NOFOLLOW_LINKS));Os.mkdir(root.path,0x1c0);directory(root)
        publish(root,"attempt",JSONObject(gate.toString()).put("phase","before-public-api").put("packageLastUpdateTime",updateTime)
            .put("cacheBefore",old?.json()?:JSONObject.NULL).put("oldCacheCreationAttributionAccepted",false).put("HostOrModelCalls",0))
        // Public 1.4.0 API; no reflected invocation, private overload, skipfile API or initializer change.
        val immediate=ProfileVerifier.writeProfileVerification(app)
        val completed=ProfileVerifier.getCompilationStatusAsync().get(10,TimeUnit.SECONDS)
        assertEquals(NO_EMBEDDED_PROFILE,immediate.profileInstallResultCode)
        assertEquals(immediate.profileInstallResultCode,completed.profileInstallResultCode)
        val stable=cache(marker)
        assertEquals(NO_EMBEDDED_PROFILE,stable.resultCode);assertEquals(updateTime,stable.packageUpdateTime)
        val start=SystemClock.elapsedRealtime();var samples=1
        while(SystemClock.elapsedRealtime()-start<STABILITY_MS) {
            SystemClock.sleep(250)
            val next=cache(marker);assertEquals(stable.identity,next.identity);assertArrayEquals(stable.bytes,next.bytes);samples++
        }
        val sampledMs=SystemClock.elapsedRealtime()-start;require(sampledMs>=STABILITY_MS)
        require(Files.notExists(skip.toPath(),NOFOLLOW_LINKS))
        require(Files.notExists(File(app.applicationInfo.dataDir,"no_backup").toPath(),NOFOLLOW_LINKS))
        assertEquals(beforeNames+"profileInstalled",requireNotNull(files.list()).toSet())
        assertEquals(setOf(root.name),requireNotNull(acceptance.list()).toSet())
        assertEquals(setOf("attempt.json"),requireNotNull(root.list()).toSet())
        assertEquals(updateTime,app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime)
        assertEquals(maxMemory,Runtime.getRuntime().maxMemory())
        val result=JSONObject(gate.toString()).put("phase","complete").put("packageLastUpdateTime",updateTime).put("publicApi","ProfileVerifier.writeProfileVerification(Context)")
            .put("publicFutureResultCode",completed.profileInstallResultCode).put("cacheAfter",stable.json()).put("cacheSamplingWindowMs",sampledMs).put("cacheSamples",samples).put("allObservedCacheBytesAndNineStatsEqual",true)
            .put("cacheExistedBeforePublicCall",old!=null).put("cacheCreationStackOrCallerAttributionAccepted",false)
            .put("crossProcessOrArtCacheStabilityAccepted",false).put("skipFilePresent",false).put("initializerDisabled",false)
            .put("HostOrModelCalls",0).put("UiOrPhysicalDevAccepted",false).put("quotaBaselineCaptured",false).put("fullP7Accepted",false)
        publish(root,"complete",result)
        println("P7_LOW_HEAP_PROFILE_BOOTSTRAP "+result)
        instrumentation.sendStatus(2,Bundle().apply {putString("p7LowHeapProfileBootstrap",result.toString())})
    }
}
