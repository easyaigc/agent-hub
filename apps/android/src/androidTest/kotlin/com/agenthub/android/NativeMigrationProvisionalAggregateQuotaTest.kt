package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Large synthetic originals: real fixed quota, no reduced limit, recorder/Host/UI or body-sized arrays. */
class NativeMigrationProvisionalAggregateQuotaTest {
    private companion object {
        const val PCM_BYTES=9_600_000
        const val WAV_BYTES=PCM_BYTES+44
        const val LOW_HEAP_BYTES=48L*1024*1024
        const val MIN_FREE_BYTES=2L*1024*1024*1024
        const val MAX_SUCCESS_ENVELOPE=200L*1024*1024
        const val PASSWORD="owned source82 aggregate migration"
        const val PROOF_CAP=1024*1024
    }
    private enum class Scenario(val runArgument:String,val proofKind:String,val rootPrefix:String,val aliasPrefix:String,val proofPrefix:String,val identityPrefix:String,val originalLabel:String,val keyAad:String,val require48MiB:Boolean) {
        SOURCE82("source82QuotaRun","real-aggregate-provisional-quota-v1","P7-aggregate-quota","p7-aggregate-quota-source","source82","source82","Source82 original","source82-key-continuity-v1",false),
        ACTUAL_48_MIB("lowHeapQuotaRun","actual-at-most-48mib-real-aggregate-provisional-quota-v1","P7-low-heap-aggregate-quota","p7-low-heap-aggregate-quota-source","low-heap-quota","low-heap-quota","P7 low-heap original","low-heap-quota-key-continuity-v1",true)
    }
    private class Fixture(val scenario:Scenario) {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val run=requireNotNull(InstrumentationRegistry.getArguments().getString(scenario.runArgument)).also {require(UUID.fromString(it).toString()==it)}
        val requireLowHeap=scenario.require48MiB || (InstrumentationRegistry.getArguments().getString("source82Require48MiB")?:"false").also {require(it in setOf("true","false"))}=="true"
        val maxMemory=Runtime.getRuntime().maxMemory()
        val root=File(app.filesDir,"acceptance/${scenario.rootPrefix}-$run")
        val sourceBase=File(root,"source")
        val alias="${scenario.aliasPrefix}-$run"
        val operations=listOf("under","over").associateWith {UUID.nameUUIDFromBytes(((if(scenario.require48MiB)scenario.proofKind+":" else "")+run+":export:"+it).toByteArray(Charsets.UTF_8)).toString()}
        private val calls=mutableSetOf<String>()
        init {
            require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance"&&app.packageName==BuildConfig.APPLICATION_ID&&app.applicationInfo.uid==Process.myUid())
            require(InstrumentationRegistry.getInstrumentation().context.packageName==app.packageName+".test")
            if(scenario.require48MiB) require(!InstrumentationRegistry.getArguments().containsKey("source82QuotaRun")&&!InstrumentationRegistry.getArguments().containsKey("source82Require48MiB")) {"Low-heap scenario has its own immutable run argument"}
            NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(app)
            require(maxMemory>0&&(!requireLowHeap||maxMemory<=LOW_HEAP_BYTES)) {"Actual Native heap exceeds the explicit 48MiB run; no fixture created"}
        }
        fun fresh() {
            val parent=requireNotNull(root.parentFile);require(Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(parent.toPath())&&parent.canonicalFile==File(app.filesDir.canonicalFile,"acceptance"))
            val st=Os.lstat(parent.path);require(st.st_uid==Process.myUid()&&OsConstants.S_ISDIR(st.st_mode)&&st.st_mode and 0x3f==0)
            require(app.filesDir.usableSpace>=MIN_FREE_BYTES) {"Fresh quota executor needs at least 2GiB free before writing"}
            require(!KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias(alias)) {"Original alias retained; never replay this run"}
            require(Files.notExists(root.toPath(),NOFOLLOW_LINKS));check(root.mkdir());Os.chmod(root.path,0x1c0)
            check(sourceBase.mkdir());Os.chmod(sourceBase.path,0x1c0)
        }
        fun context(): Context=object:ContextWrapper(app){override fun getNoBackupFilesDir()=sourceBase;override fun getApplicationContext(): Context=this}
        fun output()=File(root,"preserved-under-quota.migration")
        fun publish(name:String,value:JSONObject) {
            require(name in setOf("intent","under-published","before-over","over-rejected"))
            if(scenario.require48MiB) value.put("scenario",scenario.proofKind).put("targetPackage",app.packageName).put("targetUid",Process.myUid()).put("pid",Process.myPid()).put("heapCeilingBytes",LOW_HEAP_BYTES)
            val bytes=value.toString().toByteArray(Charsets.UTF_8);require(bytes.size in 1..PROOF_CAP)
            val file=File(root,"${scenario.proofPrefix}-$name.json");val fd=Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,0x180)
            FileOutputStream(fd).use {it.write(bytes);it.fd.sync()}
        }
        fun export(label:String,storage:NativeSessionStorage,beforePublication:()->Unit) {
            require(label in operations&&calls.add(label)) {"Each original export is submitted once"}
            val operation=operations.getValue(label)
            NativeVoiceRawOwnership.freeze(storage,operation).use {held->
                NativeMigrationExport(context(),storage).sealFile(output(),PASSWORD,NativeMigrationOwnerLease(storage,operation,held){true},beforePublication)
            }
        }
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun fileSha(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(65_536)
        file.inputStream().use {input->while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
        bytes.fill(0);return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun stamp(file:File)=Os.lstat(file.path).let {s->listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_nlink,s.st_uid.toLong(),s.st_gid.toLong(),s.st_size,s.st_mtime,s.st_ctime)}
    private fun tree(root:File):JSONObject=JSONObject().also {all->
        root.walkTopDown().forEach {file->
            require(!Files.isSymbolicLink(file.toPath()));val st=Os.lstat(file.path)
            require(st.st_uid==Process.myUid()&&st.st_gid==Process.myUid()&&(OsConstants.S_ISDIR(st.st_mode)||OsConstants.S_ISREG(st.st_mode)))
            val row=JSONObject().put("stat",JSONArray(stamp(file)))
            if(OsConstants.S_ISREG(st.st_mode)){require(st.st_nlink==1L&&st.st_mode and 0x1ff==0x180);row.put("sha256",fileSha(file))}
            else require(st.st_mode and 0x1ff==0x1c0)
            all.put(file.relativeTo(root).path.ifEmpty{"."},row)
        }
    }
    private fun same(a:JSONObject,b:JSONObject)=assertEquals(NativeChatRecoveryCanonical.value(a),NativeChatRecoveryCanonical.value(b))
    private fun underSourcePreserved(before:JSONObject,after:JSONObject) {
        val created="installation-migration-candidates";val names=before.keys().asSequence().toSet()
        require(created !in names);assertEquals(names+created,after.keys().asSequence().toSet())
        (names-".").forEach {name->same(before.getJSONObject(name),after.getJSONObject(name))}
        val old=before.getJSONObject(".").getJSONArray("stat");val now=after.getJSONObject(".").getJSONArray("stat")
        for(i in listOf(0,1,2,4,5,6))assertEquals(old.getLong(i),now.getLong(i))
        assertEquals(old.getLong(3)+1,now.getLong(3));assertTrue(now.getLong(7)>=old.getLong(7));assertTrue(now.getLong(8)>=old.getLong(8))
        val added=after.getJSONObject(created);assertEquals(setOf("stat"),added.keys().asSequence().toSet());val st=added.getJSONArray("stat")
        assertEquals((OsConstants.S_IFDIR or 0x1c0).toLong(),st.getLong(2));assertEquals(2L,st.getLong(3));assertEquals(Process.myUid().toLong(),st.getLong(4));assertEquals(Process.myUid().toLong(),st.getLong(5))
    }
    private fun recordPath(base:File,name:String)=File(File(base,"installation"),sha(name.toByteArray(Charsets.UTF_8))+".bin")
    private suspend fun seed(f:Fixture,storage:NativeSessionStorage,client:MobileClient,number:Int):PendingVoiceRecording {
        val store=VoiceRecordingStore(f.context(),storage)
        val thread="${f.scenario.identityPrefix}-thread-$number"
        val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey(thread),"${f.scenario.identityPrefix}-draft-$number",thread,"${f.scenario.originalLabel} $number 中文 😀")
        val file=store.file(pending.id);require(Files.notExists(file.toPath(),NOFOLLOW_LINKS))
        RandomAccessFile(file,"rw").use {out->
            out.write(WavePCM.header(PCM_BYTES));val buffer=ByteArray(65_536){((it+number*13)%251).toByte()};var remaining=PCM_BYTES
            while(remaining>0){val n=minOf(buffer.size,remaining);out.write(buffer,0,n);remaining-=n};buffer.fill(0);out.fd.sync()
        }
        Os.chmod(file.path,0x180);assertEquals(WAV_BYTES.toLong(),file.length())
        storage.writeBinary("voice-audio:${pending.id}",file)
        val row=NativeVoiceRecordingRecord.decode(JSONObject(requireNotNull(storage.read("voice-recording:${pending.id}"))))
        assertNull(row.manifest);assertFalse(row.prepareAttempted||row.audioAttempted||row.migrationReadOnly)
        val rawSha=fileSha(file);val probe=NativeMigrationProbe();storage.migrationCopyBinaryTo("voice-audio:${pending.id}",WAV_BYTES,probe)
        assertEquals(WAV_BYTES.toLong(),probe.size);assertEquals(rawSha,probe.sha256())
        return pending
    }
    @Test fun tenSeededPairsExportThenEleventhCrossesRealAggregateQuotaWithoutReplacingOriginal()=exercise(Scenario.SOURCE82)

    @Test fun actualAtMost48MiBHeapTenPairsPublishAndEleventhRejectsPreservingOriginal() {
        // Emit the small gate before any Fixture, source body, archive or key is created.
        val maxMemory=Runtime.getRuntime().maxMemory()
        val instrumentation=InstrumentationRegistry.getInstrumentation();val app=instrumentation.targetContext
        require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance"&&app.packageName==BuildConfig.APPLICATION_ID&&app.applicationInfo.uid==Process.myUid())
        require(instrumentation.context.packageName==app.packageName+".test")
        val arguments=InstrumentationRegistry.getArguments()
        val run=requireNotNull(arguments.getString("lowHeapQuotaRun")).also {require(UUID.fromString(it).toString()==it)}
        require(!arguments.containsKey("source82QuotaRun")&&!arguments.containsKey("source82Require48MiB")) {"Low-heap scenario has its own immutable run argument"}
        val gate=JSONObject().put("version",1).put("scenario",Scenario.ACTUAL_48_MIB.proofKind).put("phase","before-fixture")
            .put("run",run)
            .put("actualMaxMemoryBytes",maxMemory).put("heapCeilingBytes",LOW_HEAP_BYTES).put("pid",Process.myPid()).put("targetPackage",app.packageName)
            .put("targetUid",app.applicationInfo.uid).put("processUid",Process.myUid()).put("testPackage",instrumentation.context.packageName)
            .put("largeHeapDeclared",app.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP!=0).toString()
        println("P7_LOW_HEAP_GATE "+gate)
        instrumentation.sendStatus(2,Bundle().apply {putString("p7LowHeapGate",gate)})
        require(app.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP==0) {"Expected the shipping ordinary heap manifest"}
        require(maxMemory>0&&maxMemory<=LOW_HEAP_BYTES) {"Actual Native heap exceeds 50331648 bytes; no fixture created"}
        exercise(Scenario.ACTUAL_48_MIB,maxMemory)
    }

    private fun exercise(scenario:Scenario,checkedMaxMemory:Long?=null)=runBlocking(Dispatchers.IO) {
        val f=Fixture(scenario);val started=SystemClock.elapsedRealtime()
        if(checkedMaxMemory!=null) assertEquals(checkedMaxMemory,f.maxMemory)
        assertEquals(WAV_BYTES,NativeMigrationProvisionalVoice.MAX_RAW_BYTES)
        val underBytes=10L*2*WAV_BYTES;val overBytes=11L*2*WAV_BYTES;val quota=NativeMigrationStreamRecords.MAX_TOTAL_BYTES
        require(underBytes<quota&&overBytes>quota) {"Actual fixed quota changed; review this boundary before seeding"}
        if(scenario.require48MiB) {assertEquals(192000880L,underBytes);assertEquals(211200968L,overBytes);assertEquals(201326592L,quota)}
        f.fresh();f.publish("intent",JSONObject().put("version",1).put("kind",scenario.proofKind).put("run",f.run)
            .put("exportOperations",JSONObject(f.operations)).put("underPairs",10).put("overPairs",11).put("wavBytes",WAV_BYTES).put("shippingQuotaBytes",quota)
            .put("actualMaxMemoryAtStart",f.maxMemory).put("lowHeapRequested",f.requireLowHeap).put("noReplay",true).put("fixtureRetained",true))
        val storage=NativeSessionStorage(f.context(),"installation",f.alias)
        try {
            val payload=MigrationActivationFixtures.payload();val initial=payload.getJSONObject("records");val initialNames=initial.keys().asSequence().toSet();assertEquals(5,initialNames.size)
            initialNames.forEach {storage.write(it,initial.getString(it))};val client=MigrationActivationFixtures.client(payload)
            val protected=JSONObject().also {p->initialNames.sorted().forEach {name->val file=recordPath(f.sourceBase,name);p.put(file.name,JSONObject().put("stat",JSONArray(stamp(file))).put("sha256",fileSha(file)))}}
            val originals=(0 until 10).map {seed(f,storage,client,it)}.toMutableList()
            val underSourceBefore=tree(f.sourceBase)
            var underPublications=0;f.export("under",storage){underPublications++};assertEquals(1,underPublications)
            val underSourceAfter=tree(f.sourceBase);underSourcePreserved(underSourceBefore,underSourceAfter)
            val output=f.output();require(output.length() in (underBytes+1)..MAX_SUCCESS_ENVELOPE);assertTrue(NativeMigrationStreamCrypto.isEnvelope(output))
            val originalOutputStat=stamp(output);val originalOutputSha=fileSha(output)
            assertFalse(File(output.path+".new").exists());assertFalse(File(output.path+".bak").exists())
            f.publish("under-published",JSONObject().put("version",1).put("run",f.run).put("operationId",f.operations.getValue("under"))
                .put("recordingIds",JSONArray(originals.map {it.id})).put("binaryLogicalBytes",underBytes).put("publicationCalls",underPublications)
                .put("outputStat",JSONArray(originalOutputStat)).put("outputSha256",originalOutputSha).put("sourceBefore",underSourceBefore).put("sourceAfter",underSourceAfter).put("allUnderOriginalsPreserved",true).put("onlyEmptyValidationCandidateDirectoryCreated",true).put("realSealCalled",false).put("seededAudioWindow",true))
            originals.add(seed(f,storage,client,10));assertEquals(11,originals.size)
            val index=requireNotNull(storage.read("voice-index-v1"));assertEquals(originals.map {it.id},JSONArray(index).let {a->(0 until a.length()).map {a.getString(it)}})
            val expectedNames=initialNames+"voice-index-v1"+originals.flatMap {listOf("voice-recording:${it.id}","voice-audio:${it.id}")}
            val installed=File(f.sourceBase,"installation");assertEquals(28,expectedNames.size)
            assertEquals(expectedNames.map {sha(it.toByteArray(Charsets.UTF_8))+".bin"}.toSet(),requireNotNull(installed.list()).toSet())
            require(expectedNames.filterNot {it.startsWith("voice-audio:")}.sumOf {recordPath(f.sourceBase,it).length()}<=1024L*1024)
            val candidates=File(f.sourceBase,"installation-migration-candidates");require(candidates.isDirectory&&requireNotNull(candidates.list()).isEmpty())
            val rawDir=File(f.sourceBase,"voice-recordings-v1");assertEquals(originals.map {it.id+".wav"}.toSet(),requireNotNull(rawDir.list()).toSet())
            val before=tree(f.sourceBase);val rowHashes=JSONObject().also {p->originals.forEach {p.put(it.id,sha(requireNotNull(storage.read("voice-recording:${it.id}")).toByteArray()))}}
            val key=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey(f.alias,null) as SecretKey;require(key.encoded==null)
            val challenge=((if(scenario.require48MiB)"owned low-heap key continuity " else "owned source82 key continuity ")+f.run).toByteArray(Charsets.UTF_8)
            val aad=scenario.keyAad.toByteArray(Charsets.UTF_8);val encrypt=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key);updateAAD(aad)}
            val ciphertext=encrypt.doFinal(challenge);val iv=encrypt.iv
            f.publish("before-over",JSONObject().put("version",1).put("run",f.run).put("operationId",f.operations.getValue("over"))
                .put("sourceTree",before).put("voiceIndexSha256",sha(index.toByteArray())).put("voiceRowSha256",rowHashes)
                .put("sourceLogicalNames",JSONArray(expectedNames.sorted())).put("protectedBaseCiphertexts",protected).put("binaryLogicalBytes",overBytes)
                .put("outputStat",JSONArray(originalOutputStat)).put("outputSha256",originalOutputSha).put("keyChallengeCipherSha256",sha(ciphertext)))
            var overPublications=0
            val failure=try {f.export("over",storage){overPublications++};null}catch(error:Exception){error}
            val top=failure?.stackTrace?.firstOrNull()
            val aggregateFailure=failure is IllegalArgumentException&&top!=null&&top.className=="com.agenthub.android.NativeMigrationStreamRecords"&&top.methodName=="<init>"
            assertTrue("Exact shipping aggregate constructor must reject this original",aggregateFailure);assertEquals(0,overPublications)
            same(before,tree(f.sourceBase));assertEquals(originalOutputStat,stamp(output));assertEquals(originalOutputSha,fileSha(output))
            assertFalse(File(output.path+".new").exists());assertFalse(File(output.path+".bak").exists())
            assertEquals(index,storage.read("voice-index-v1"));originals.forEach {assertEquals(rowHashes.getString(it.id),sha(requireNotNull(storage.read("voice-recording:${it.id}")).toByteArray()))}
            initialNames.forEach {name->val file=recordPath(f.sourceBase,name);val old=protected.getJSONObject(file.name);assertEquals(old.getString("sha256"),fileSha(file));assertEquals(old.getJSONArray("stat").toString(),JSONArray(stamp(file)).toString())}
            val afterKey=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey(f.alias,null) as SecretKey;require(afterKey.encoded==null)
            val decrypted=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,afterKey,GCMParameterSpec(128,iv));updateAAD(aad)}.doFinal(ciphertext)
            try {assertArrayEquals(challenge,decrypted)}finally {decrypted.fill(0);challenge.fill(0);ciphertext.fill(0);iv.fill(0)}
            val maxAfter=Runtime.getRuntime().maxMemory();assertEquals(f.maxMemory,maxAfter);require(!f.requireLowHeap||maxAfter<=LOW_HEAP_BYTES)
            f.publish("over-rejected",JSONObject().put("version",1).put("run",f.run).put("pid",Process.myPid()).put("kind",scenario.proofKind)
                .put("exportOperations",JSONObject(f.operations)).put("recordingIds",JSONArray(originals.map {it.id})).put("underBinaryBytes",underBytes).put("overBinaryBytes",overBytes).put("shippingQuotaBytes",quota)
                .put("underPublishedOnce",true).put("overPublicationCalls",overPublications).put("aggregateConstructorRejected",true).put("sourceAllRegularsAndDirectoriesUnchanged",true)
                .put("outputStat",JSONArray(stamp(output))).put("outputSha256",fileSha(output)).put("noOutputSidecars",true).put("sourceKeyContinuityVerified",true)
                .put("actualMaxMemoryAtStart",f.maxMemory).put("actualMaxMemoryAtEnd",maxAfter).put("lowHeapRequested",f.requireLowHeap).put("lowHeapAccepted",f.requireLowHeap&&maxAfter<=LOW_HEAP_BYTES)
                .put("elapsedMs",SystemClock.elapsedRealtime()-started).put("seededAudioWindow",true).put("realSealCalled",false).put("realRowIoFailure",false).put("realProcessDeath",false)
                .put("candidateStagedOrActivated",false).put("originalOperationReplayed",false).put("HostOrModelCalls",0).put("UiOrPhysicalDevAccepted",false).put("fullP7Accepted",false).put("NativeAcceptance",false))
            val expectedPaths=setOf(".","source","source/installation","source/voice-recordings-v1","source/installation-migration-candidates","source/voice-recordings-writer-v1.lock","preserved-under-quota.migration")+
                setOf("intent","under-published","before-over","over-rejected").map {"${scenario.proofPrefix}-$it.json"}+
                expectedNames.map {"source/installation/"+sha(it.toByteArray(Charsets.UTF_8))+".bin"}+originals.map {"source/voice-recordings-v1/${it.id}.wav"}
            assertEquals(50,expectedPaths.size)
            assertEquals(expectedPaths,f.root.walkTopDown().map {file->require(!Files.isSymbolicLink(file.toPath()));file.relativeTo(f.root).path.ifEmpty{"."}}.toSet())
        } finally {storage.retireWriter()}
    }
}
