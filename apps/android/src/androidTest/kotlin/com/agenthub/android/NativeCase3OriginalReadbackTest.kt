package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKey

/** Opt-in, fixed synthetic Source55 case3 diagnostic. Never included in normal acceptance execution.
 * Root owns worker/lock outcome reconciliation; timeout is not permission to invoke either method again. */
class NativeCase3OriginalReadbackTest {
    private val p=NativeCase3ReadbackContract
    private data class Input(val target: Context,val app: String,val runner: String,val ack: ByteArray?)
    private fun input(phase: String): Input {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit Root scheduling",args.getString("p7Case3Phase")==phase)
        val names=setOf("p7Case3Phase","p7Case3AppSha256","p7Case3RunnerSha256","p7Case3ParentQuiescent")+
            if(phase=="cleanup")setOf("p7Case3CleanupAckBase64")else emptySet()
        require(args.keySet().filter {it.startsWith("p7Case3")}.toSet()==names)
        val method=if(phase=="read")"readOnlyOriginal" else "cleanupAfterRootFullCaptureAck"
        require(args.getString("class")==javaClass.name+"#"+method)
        require(args.getString("p7Case3ParentQuiescent")=="original-worker-exited-no-active-store")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext;val runner=instrumentation.context
        require(target.packageName==p.PACKAGE&&runner.packageName==p.PACKAGE+".test")
        require(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0)
        require(Process.myUid()==p.UID&&Build.VERSION.SDK_INT==26&&Process.is64Bit()&&Build.SUPPORTED_ABIS.first()=="arm64-v8a")
        require(Build.FINGERPRINT=="google/sdk_gphone_arm64/generic_arm64:8.0.0/OSR1.180418.028.A3/10734686:userdebug/dev-keys")
        val app=requireNotNull(args.getString("p7Case3AppSha256"));val test=requireNotNull(args.getString("p7Case3RunnerSha256"))
        require(p.hash(app)&&p.hash(test)&&apkSha(File(target.applicationInfo.sourceDir))==app&&apkSha(File(runner.applicationInfo.sourceDir))==test)
        val ack=if(phase=="cleanup") {
            val encoded=requireNotNull(args.getString("p7Case3CleanupAckBase64"));require(encoded.length<=5500)
            Base64.getDecoder().decode(encoded).also {require(Base64.getEncoder().encodeToString(it)==encoded)}
        }else null
        return Input(target,app,test,ack)
    }
    private fun apkSha(file: File): String {
        val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
        try {
            val before=Os.fstat(fd);require(OsConstants.S_ISREG(before.st_mode)&&before.st_nlink==1L&&before.st_size in 1..268_435_456L)
            val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);var bytes=0L
            while(true){val count=Os.read(fd,buffer,0,buffer.size);if(count==0)break;require(count>0);digest.update(buffer,0,count);bytes+=count}
            require(bytes==before.st_size)
            for(s in listOf(Os.fstat(fd),Os.lstat(file.path)))require(s.st_dev==before.st_dev&&s.st_ino==before.st_ino&&s.st_size==before.st_size&&s.st_mtime==before.st_mtime&&s.st_ctime==before.st_ctime&&s.st_mode==before.st_mode&&s.st_nlink==1L)
            return digest.digest().joinToString(""){"%02x".format(it)}
        }finally {Os.close(fd)}
    }
    private fun canonical(v: Any?): String=when(v) {
        null,JSONObject.NULL->"null"
        is JSONObject->v.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(v.get(it))}
        is JSONArray->(0 until v.length()).joinToString(",","[","]"){canonical(v.get(it))}
        is String->JSONObject.quote(v)
        is Boolean,is Int,is Long->v.toString()
        else->error("Unsupported receipt value")
    }
    private fun bytes(v: JSONObject)=canonical(v).toByteArray(Charsets.UTF_8)
    private fun base(i: Input)=JSONObject().put("schema",1).put("profile",p.PROFILE).put("package",p.PACKAGE).put("uid",p.UID)
        .put("sdk",26).put("appSha256",i.app).put("runnerSha256",i.runner).put("sourceArchiveSha256",p.ARCHIVE_SHA)
        .put("sourceIndexSha256",p.INDEX_SHA).put("inventorySha256",p.inventorySha).put("originalProofSha256",p.ORIGINAL_SHA)
    private fun intent(i: Input)=bytes(base(i).put("phase","read").put("sourceMutationAuthorized",false))
    private data class Readback(val wav: ByteArray,val receipt: ByteArray)
    private fun readback(i: Input,f: NativeCase3ReadbackFiles): Readback {
        val proofBytes=f.source(p.FIXTURE+"/original.json")
        val proof=try {StrictMigrationJson.parse(proofBytes)}finally{proofBytes.fill(0)}
        require(proof.keys().asSequence().toSet()==setOf("canonicalOriginal","persistedRow","metadata","fixtureInputs"))
        val expectedRow=proof.getString("persistedRow");val expectedMetadata=proof.getString("metadata");val expectedCanonical=proof.getString("canonicalOriginal")
        require(p.sha(expectedRow.toByteArray())==p.ROW_SHA&&p.sha(expectedMetadata.toByteArray())==p.METADATA_SHA&&p.sha(expectedCanonical.toByteArray())==p.CANONICAL_SHA)
        val local=object: ContextWrapper(i.target) {
            override fun getApplicationContext(): Context=this
            override fun getNoBackupFilesDir()=File(f.fixture,"no-backup") // existing only; no mkdir/recovery
        }
        val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        require(keys.getKey(p.ALIAS,null) is SecretKey) // never getEncoded/export/generate a key
        NativeSessionStorage.ExistingReader.open(local,"original",p.ALIAS).use {reader->
            val row=reader.text("voice-recording:"+p.RECORDING,8192);require(row==expectedRow)
            val metadata=reader.text("metadata",8192);require(metadata==expectedMetadata)
            val current=NativeVoiceRecordingRecord.decode(StrictMigrationJson.parse(row.toByteArray()))
            require(canonical(current.json())==expectedCanonical&&current.id==p.RECORDING)
            val index=reader.text("voice-index-v1",1024)
            val ids=StrictMigrationJson.value(index.toByteArray()) as JSONArray
            require(ids.length()==1&&ids.get(0)==p.RECORDING)
            val catalog=reader.text(NativeVoiceDiscardCatalog.RECORD,8192)
            val markers=NativeVoiceDiscardCatalog.decode(catalog);require(markers.keys==setOf(p.RECORDING))
            val marker=markers.getValue(p.RECORDING)
            require(p.sha(marker.auditName.toByteArray())+".bin"==p.AUDIT_FILE&&marker.manifestKnown&&marker.evidence.kind=="sealed")
            val audit=reader.text(marker.auditName,8192)
            val original=NativeVoiceDiscardCatalog.original(marker,audit,current)
            require(canonical(original.json())==expectedCanonical&&marker.fingerprint==NativeVoiceDiscardRequest(current).fingerprint)
            val manifest=requireNotNull(current.manifest)
            require(manifest.sizeBytes==p.WAV_BYTES&&manifest.sha256==p.WAV_SHA)
            val wav=reader.binary("voice-audio:"+p.RECORDING,p.WAV_BYTES)
            try {
                require(wav.size==p.WAV_BYTES&&p.sha(wav)==p.WAV_SHA)
                f.inventory()
                val receipt=base(i).put("phase","read-complete").put("wavBytes",wav.size).put("wavSha256",p.sha(wav))
                    .put("original",proof).put("index",index).put("catalog",catalog).put("audit",audit)
                    .put("catalogMarker",marker.json()).put("discardFingerprint",marker.fingerprint)
                    .put("inventory",JSONArray(p.entries.map {it.line()})).put("inventoryUnchanged",true)
                    .put("readOnlyExistingKey",true).put("sourceFileWrites",0).put("sourceDeletes",0).put("keysCreated",0)
                    .put("originalRawWavStillPresent",false).put("nativeReadbackWavProduced",true)
                    .put("parentFullWavCaptured",false).put("parentIndependentDecryption",false)
                    .put("hostCalls",0).put("microphoneCalls",0).put("modelCalls",0).put("discardReplays",0)
                return Readback(wav,bytes(receipt))
            }catch(t: Throwable){wav.fill(0);throw t}
        }
    }
    @Test fun readOnlyOriginal() {
        val i=input("read");val f=NativeCase3ReadbackFiles(i.target)
        f.newOutput();f.writeNew("read-intent.json",intent(i));f.pinAncestors()
        f.frozen {
            val value=readback(i,f)
            try {
                f.writeNew("original.wav",value.wav);f.inventory()
                f.writeNew("read-receipt.json",value.receipt);f.inventory()
            }finally {value.wav.fill(0);value.receipt.fill(0)}
        }
        // No @After fixture/key cleanup. Root must independently capture every WAV byte first.
    }
    @Test fun cleanupAfterRootFullCaptureAck() {
        val i=input("cleanup");val f=NativeCase3ReadbackFiles(i.target)
        f.existingOutput(setOf("read-intent.json","original.wav","read-receipt.json"));f.pinAncestors()
        require(f.outputBytes("read-intent.json",8192).contentEquals(intent(i)))
        val receipt=f.outputBytes("read-receipt.json",65536);val receiptSha=p.sha(receipt)
        val wav=f.outputBytes("original.wav",p.WAV_BYTES)
        try {require(wav.size==p.WAV_BYTES&&p.sha(wav)==p.WAV_SHA)}finally{wav.fill(0)}
        val ack=requireNotNull(i.ack);val parsed=NativeCase3ReadbackContract.CleanupAck.parse(ack,receiptSha)
        // Persistent once-only guard is written before any source/key access in this phase.
        f.writeNew("cleanup-intent.json",bytes(base(i).put("phase","cleanup").put("readReceiptSha256",receiptSha)
            .put("rootAck",ack.toString(Charsets.US_ASCII)).put("rootAckSha256",p.sha(ack)).put("rootCaptureSha256",parsed.rootCaptureSha)))
        f.frozen {
            val verified=readback(i,f)
            try {require(verified.receipt.contentEquals(receipt));f.inventory()}
            finally {verified.wav.fill(0);verified.receipt.fill(0);receipt.fill(0)}
            f.removeExactFixture()
            val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            require(keys.getKey(p.ALIAS,null) is SecretKey);keys.deleteEntry(p.ALIAS)
            require(!keys.containsAlias(p.ALIAS));f.requireFixtureRemoved()
            f.writeNew("cleanup-receipt.json",bytes(base(i).put("phase","cleanup-complete").put("readReceiptSha256",receiptSha)
                .put("rootAckSha256",p.sha(ack)).put("rootCaptureSha256",parsed.rootCaptureSha)
                .put("exactFixtureRemoved",true).put("exactAliasRemoved",true).put("methodIntentAndResultRetained",true)
                .put("nativeReadbackOutputRetained",true).put("parentFullWavCapturedByAck",true).put("parentIndependentDecryption",false)))
        }
        ack.fill(0)
    }
}
