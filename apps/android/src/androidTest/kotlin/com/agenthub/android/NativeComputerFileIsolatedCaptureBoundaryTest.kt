package com.agenthub.android

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.KeyStore
import java.util.UUID

/** One isolated production child/capture, not default data, UI, network or F59 acceptance.
 * Existing test provider is attached IN PROCESS to this fixture; never writes its installed registry.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=29)
class NativeComputerFileIsolatedCaptureBoundaryTest {
    @Test fun failureDiagnosticOmitsMessagesAndForeignSourceNames() {
        val secret="PRIVATE_URI_AND_TOKEN_MUST_NOT_APPEAR"
        val failure=IllegalStateException(secret,android.system.ErrnoException(secret,android.system.OsConstants.EACCES))
        failure.stackTrace=arrayOf(StackTraceElement("com.foreign.$secret",secret,secret,7),
            StackTraceElement("com.agenthub.android.NativeSessionStorage",secret,secret,151))
        val value=JSONObject(ComputerFileFailureDiagnostic.projection("journal-read",failure))
        check(!value.toString().contains(secret))
        check(value.getString("stage")=="journal-read"&&value.getJSONArray("causes").length()==2)
        val first=value.getJSONArray("causes").getJSONObject(0)
        check(first.getJSONArray("source").length()==1&&first.getJSONArray("source").getJSONObject(0).getInt("line")==151)
        check(value.getJSONArray("causes").getJSONObject(1).getInt("errno")==android.system.OsConstants.EACCES)
        val upload=CodexCommandError(secret,503,java.io.IOException(secret))
        upload.stackTrace=arrayOf(StackTraceElement("com.agenthub.android.NativeUploadCenter",secret,secret,176))
        val uploadValue=JSONObject(ComputerFileFailureDiagnostic.projection("upload-status",upload))
        check(!uploadValue.toString().contains(secret))
        val cause=uploadValue.getJSONArray("causes").getJSONObject(0)
        check(cause.getInt("httpStatus")==503&&cause.getJSONArray("source").getJSONObject(0).getString("class")=="NativeUploadCenter")
        check(!JSONObject(ComputerFileFailureDiagnostic.projection("upload-chunk",CodexCommandError(secret,99)))
            .getJSONArray("causes").getJSONObject(0).has("httpStatus"))
        check(runCatching {ComputerFileFailureDiagnostic.projection(secret,failure)}.isFailure)
    }

    private val sourceFrames=mapOf(
        "NativeSessionStorage" to setOf("<init>","computerFileStorage","prepareChild","child","requireComputerFileWritable","computerFilesPort","computerFileTransaction","requireWritable","requireCurrentGeneration","checkMigrationAccess","activeGeneration","requireRawWriterOwner"),
        "NativeComputerFileStore" to setOf("captureOriginal","transaction","installationWriter","rows","save","open"),
        "NativeComputerFileDurableFiles" to setOf("transaction","directory","sameDirectory","regular","lease","snapshot"),
        "NativeVoiceRawOwnership" to setOf("computerFilesWriter","requireIo"),
        "NativeVoiceRawGate" to setOf("computerFileWriter","openComputerFiles","requireWriter"))
    private fun diagnostic(failure: Throwable): JSONArray {
        val out=JSONArray();val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var cause: Throwable?=failure
        repeat(6) {
            val e=cause?:return out;if(!seen.add(e))return out
            val row=JSONObject().put("class",e.javaClass.name)
            if(e is ErrnoException)row.put("errno",e.errno)
            val frames=JSONArray()
            for(frame in e.stackTrace.take(24)) {
                val type=frame.className.removePrefix("com.agenthub.android.").substringBefore('$')
                val methods=sourceFrames[type].orEmpty()+setOf("invoke","invokeSuspend")
                if(frame.className.startsWith("com.agenthub.android.")&&type in sourceFrames&&
                    (frame.methodName in methods||frame.methodName.removePrefix("access$") in methods))
                    frames.put(JSONObject().put("class",frame.className).put("method",frame.methodName).put("line",frame.lineNumber))
                if(frames.length()==4)break
            }
            row.put("knownSourceFrames",frames);out.put(row);cause=e.cause
        };return out // No message, filename, locals, raw stack or suppressed body output.
    }
    @Test(timeout=30000) fun ordinaryChildThenOneSyntheticCapturePersistsReadyJournalAndChunk()=isolatedCapture(false)
    @Test(timeout=30000) fun aliasedContextDirectoryCapturesButSymlinkedChildCannotReadOrMutateTarget()=isolatedCapture(true)
    private fun isolatedCapture(aliasedDirectory: Boolean)=runBlocking(Dispatchers.IO) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val app=instrumentation.targetContext
        check(app.packageName=="com.easyai.agenthub.android.acceptance"&&app.applicationInfo.uid==Process.myUid())
        val run=UUID.randomUUID().toString() // Allocated only if Root later executes this NEW test.
        val parent=app.cacheDir.canonicalFile;val fixture=File(parent,"f59-c2-capture-$run")
        val noBackup=File(fixture,"no_backup");val cache=File(fixture,"cache");val localFiles=File(fixture,"files")
        val storageBase=if(aliasedDirectory)File(fixture,"./no_backup")else noBackup
        check(storageBase.canonicalFile==noBackup)
        check((storageBase.absoluteFile!=storageBase.canonicalFile)==aliasedDirectory)
        val alias="f59-c2-$run";val namespace="c2-installation"
        val childAlias=NativeMigrationExistingView.childAlias(alias,NativeComputerFileStore.SCOPE,null)
        val plannedAliases=setOf(alias,childAlias);var keys:KeyStore?=null
        val report=JSONObject().put("schema",1).put("ownedRun",run).put("captureCalls",0).put("HTTP",0).put("Session",0)
        var phase="RESERVE";var owned=false;var reserved=false;var succeeded=false;var cleaned=false
        var root:NativeSessionStorage?=null;var child:NativeSessionStorage?=null
        var original:Throwable?=null;val io=NativeMigrationIoWork();var ioIdle=false
        lateinit var resolver:ContentResolver
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir():File=storageBase
            override fun getCacheDir():File=cache
            override fun getFilesDir():File=localFiles
            override fun getContentResolver():ContentResolver=resolver
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences=error("C2_NO_PREFERENCES")
            override fun getSystemService(name:String):Any=if(name==Context.APP_OPS_SERVICE)checkNotNull(app.getSystemService(name))else error("C2_NO_SERVICE")
        }
        fun exists(file:File)=Files.exists(file.toPath(),NOFOLLOW_LINKS)
        fun noFixtureFDs() {
            for(fd in checkNotNull(File("/proc/self/fd").listFiles())) {
                val path=try {Os.readlink(fd.path)}catch(e:ErrnoException){if(e.errno==OsConstants.ENOENT)continue else throw e}
                val plain=path.removeSuffix(" (deleted)");check(plain!=fixture.path&&!plain.startsWith(fixture.path+"/")) {"C2_OWNED_FD_OPEN"}
            }
        }
        fun removeOwned(file:File) {
            check(file==fixture||file.path.startsWith(fixture.path+"/"));check(file.absoluteFile==file.canonicalFile)
            val s=Os.lstat(file.path);check(s.st_uid==Process.myUid()&&!OsConstants.S_ISLNK(s.st_mode))
            if(OsConstants.S_ISDIR(s.st_mode))checkNotNull(file.listFiles()).forEach(::removeOwned)
            else check(OsConstants.S_ISREG(s.st_mode)&&s.st_nlink==1L)
            Files.delete(file.toPath())
        }
        try {
            ComputerFileFdOwners.requireReleased()
            keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            check(plannedAliases.none {checkNotNull(keys).containsAlias(it)});reserved=true
            check(!exists(fixture)&&fixture.parentFile==parent&&fixture.mkdir());owned=true;Os.chmod(fixture.path,0x1c0)
            for(dir in listOf(noBackup,cache,localFiles)){check(dir.mkdir());Os.chmod(dir.path,0x1c0)}
            phase="SOURCE_PREPARE"
            val bytes="F59 C2 isolated synthetic content\n".repeat(129).toByteArray(Charsets.UTF_8)
            check(bytes.size in 1..65536);val expectedSHA=ComputerFileCodec.hash(bytes)
            val sourceName="c2-owned.bin";val registry=File(localFiles,"owned-documents.json")
            check(registry.createNewFile());registry.writeText(JSONObject().put("cases",JSONArray().put(JSONObject().put("fileName",sourceName).put("fileText",String(bytes,Charsets.UTF_8)))).toString());Os.chmod(registry.path,0x180)
            val authority=app.packageName+".c2.local."+run.replace("-","")
            val provider=OwnedAcceptanceDocuments()
            provider.attachInfo(context,ProviderInfo().apply {this.authority=authority;packageName=app.packageName;applicationInfo=app.applicationInfo;exported=true;grantUriPermissions=true;readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission})
            resolver=ContentResolver.wrap(provider) // Public API29+, local provider only; no grants/SAF/global registry.
            val uri=DocumentsContract.buildDocumentUri(authority,sourceName)
            phase="ROOT_CREATE"
            val storage=NativeSessionStorage(context,namespace,alias);root=storage
            storage.write("c2-test-marker","isolated") // Makes only this dedicated fixture key; no metadata/Session.
            val target=ComputerFileTarget(TargetScope("codex",null,null,"c2-host-$run","c2-workspace-$run","c2-runtime-$run"),null)
            val store=NativeComputerFileStore.open(context,storage,target)
            phase="ABSENT_LOAD";check(store.load().isEmpty())
            val childFolder=File(noBackup,"$namespace-"+NativeComputerFileStore.SCOPE);check(!exists(childFolder)&&!checkNotNull(keys).containsAlias(childAlias))
            phase="CHILD_CREATE";child=checkNotNull(storage.computerFileStorage(context,true))
            check(exists(childFolder)&&!checkNotNull(keys).containsAlias(childAlias));report.put("ordinaryChildCreated",true)
            phase="STORE_CAPTURE";report.put("captureCalls",1)
            val row=withContext(io){withTimeout(15000){store.capture(uri,65536L,{},{_,_->})}}
            phase="READY_VERIFY";check(row.phase==ComputerFilePhase.READY&&row.sizeBytes==bytes.size.toLong()&&row.sha256==expectedSHA&&row.chunks.size==1)
            check(store.load()==listOf(row))
            val expectedNames=setOf("computer-files-writer-v1.lock",ComputerFileCodec.hash(("computer-files-v1:"+target.key).toByteArray())+".bin",ComputerFileCodec.hash("computer-file-original:${row.id}:0".toByteArray())+".bin")
            check(checkNotNull(childFolder.listFiles()).map {it.name}.toSet()==expectedNames)
            check(checkNotNull(keys).containsAlias(alias)&&checkNotNull(keys).containsAlias(childAlias));report.put("readyJournalAndChunk",true).put("syntheticBytes",bytes.size).put("syntheticSHA256",expectedSHA)
            if(aliasedDirectory) {
                phase="SYMLINKED_CHILD_REJECT"
                ComputerFileFdOwners.requireReleased()
                val retained=File(noBackup,childFolder.name+"-owned-retained")
                check(!exists(retained));Os.rename(childFolder.path,retained.path)
                try {
                    Os.symlink(retained.path,childFolder.path)
                    val before=checkNotNull(retained.listFiles()).associate {it.name to ComputerFileCodec.hash(it.readBytes())}
                    val rejected=runCatching {store.load()}.exceptionOrNull()
                    check(rejected is IllegalArgumentException)
                    check(checkNotNull(retained.listFiles()).associate {it.name to ComputerFileCodec.hash(it.readBytes())}==before)
                    check(Os.readlink(childFolder.path)==retained.path)
                    report.put("aliasedContextAccepted",true).put("symlinkedChildRejectedTargetUnchanged",true)
                }finally {
                    if(Files.isSymbolicLink(childFolder.toPath()))Files.delete(childFolder.toPath())
                    check(!exists(childFolder));Os.rename(retained.path,childFolder.path)
                }
            }
            succeeded=true;phase="VERIFIED"
        }catch(e:Throwable){original=e;report.put("failurePhase",phase).put("failure",diagnostic(e))}
        finally {
            try {
                io.close();withContext(NonCancellable){withTimeout(5000){io.awaitIdle()}};ioIdle=true
                ComputerFileFdOwners.requireReleased();noFixtureFDs();child?.retireWriter();root?.retireWriter()
                if(reserved)for(key in plannedAliases){if(checkNotNull(keys).containsAlias(key))checkNotNull(keys).deleteEntry(key);check(!checkNotNull(keys).containsAlias(key))}
                if(owned){removeOwned(fixture);check(!exists(fixture))};cleaned=true
            }catch(cleanup:Throwable){report.put("cleanupFailure",diagnostic(cleanup))}
            report.put("phase",phase).put("passed",succeeded&&cleaned).put("ioIdle",ioIdle).put("fixtureCleaned",cleaned).put("fixtureRetained",owned&&!cleaned)
            instrumentation.sendStatus(2,Bundle().apply {putString("f59IsolatedCaptureBoundary",report.toString())})
        }
        // No raw original Throwable is attached to JUnit (which could print message/URI).
        if(original!=null||!succeeded||!cleaned)throw AssertionError("F59_C2_BOUNDARY_NOT_PASSED")
    }
}
