package com.agenthub.android

import android.app.ActivityManager
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey

/** Independent fresh instrumentation after externally witnessed passive consumer exit. Never launches Activity/Intent. */
@RunWith(AndroidJUnit4::class)
class NativeNaturalPostConsumerInspectionV2Test {
    @get:Rule val timeout:org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun sha(b:ByteArray)=NativeChatNotificationNaturalFiles.sha(b)
    private fun hash(v:String)=v.also{require(it.matches(Regex("[0-9a-f]{64}")))}
    private fun uuid(v:String)=v.also{require(UUID.fromString(it).toString()==it)}
    private fun keys(v:JSONObject,k:Set<String>)=require(v.keys().asSequence().toSet()==k)
    private fun read(f:File,limit:Int=1048576,empty:Boolean=false):ByteArray {
        val p=f.toPath();require(Files.isRegularFile(p,NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p)&&(Files.getAttribute(p,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1)
        val before=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes::class.java,NOFOLLOW_LINKS);require(before.size() in (if(empty)0 else 1).toLong()..limit.toLong())
        val bytes=FileChannel.open(p,READ,NOFOLLOW_LINKS).use{c->require(c.size()==before.size());val b=ByteBuffer.allocate(before.size().toInt());while(b.hasRemaining())check(c.read(b)>0);require(c.read(ByteBuffer.allocate(1))==-1);b.array()}
        val after=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes::class.java,NOFOLLOW_LINKS);require(before.fileKey()==after.fileKey()&&before.size()==after.size()&&before.lastModifiedTime()==after.lastModifiedTime());return bytes
    }
    private fun fixedDir(parent:File,name:String):File {val d=File(parent,name);require(Files.isDirectory(d.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(d.toPath())&&d.canonicalFile.parentFile==parent.canonicalFile);return d}
    private fun rootHashes(parent:File,name:String):JSONObject {val d=fixedDir(parent,name);val names=checkNotNull(d.list()).sorted();require(names.size in 1..16);return JSONObject().also{v->for(n in names){require(n.matches(Regex("[a-f0-9]{64}\\.bin|owner\\.lock|device-challenge\\.bin|permit\\.bin|claim\\.bin|complete\\.bin|entry-intent\\.json|bound-claim\\.json")));v.put(n,sha(read(File(d,n),empty=n=="owner.lock")))}}}
    private fun equal(a:JSONObject,b:JSONObject){require(a.keys().asSequence().toSet()==b.keys().asSequence().toSet());for(k in a.keys())require(a.get(k)==b.get(k))}
    private fun source(v:JSONObject){keys(v,setOf("sourceSha256","uid","appSha256","runnerSha256","examinerSha256","signerSetSha256"));require(v.get("uid") is Int&&v.getInt("uid")>=10000);for(k in v.keys())if(k!="uid")hash(v.getString(k))}
    private fun installed(expected:JSONObject){
        val actual=NativeNaturalPreflightEvidence.installed(context);for(k in listOf("uid","appSha256","runnerSha256","signerSetSha256"))require(actual.get(k)==expected.get(k))
        val pm=context.packageManager;val name=NativeNaturalOsContract.EXAMINER;require(pm.checkSignatures(context.packageName,name)==android.content.pm.PackageManager.SIGNATURE_MATCH)
        val info=pm.getApplicationInfo(name,0);require(info.splitSourceDirs.isNullOrEmpty());val f=File(info.sourceDir);require(f.length() in 1..134217728)
        val digest=java.security.MessageDigest.getInstance("SHA-256");f.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        require(digest.digest().joinToString(""){"%02x".format(it)}==expected.getString("examinerSha256"))
    }
    private fun evidenceDir(lease:String,attempt:String):File {var p=context.filesDir.canonicalFile;for(n in listOf("acceptance","P9-postconsumer-inspection-v2",lease,attempt)) {val d=File(p,n);if(Files.notExists(d.toPath(),NOFOLLOW_LINKS))Files.createDirectory(d.toPath(),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));p=fixedDir(p,n)};return p}
    @Test fun originalClaimReceiptAndNewDraftSurviveConsumerExitReadOnly():Unit=runBlocking{withContext(Dispatchers.IO){
        val args=InstrumentationRegistry.getArguments();Assume.assumeTrue("Independent fixed post-consumer-exit plan required; skip is not acceptance",args.getString("p9PostConsumerV2PlanBase64")!=null&&args.getString("p9PostConsumerV2PlanSha256")!=null)
        NativeChatNotificationNaturalStore.guard(context);require(android.os.Build.VERSION.SDK_INT==33)
        val encoded=checkNotNull(args.getString("p9PostConsumerV2PlanBase64"));require(encoded.length in 1..22000&&encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
        val raw=java.util.Base64.getDecoder().decode(encoded);require(raw.size in 1..16384&&java.util.Base64.getEncoder().encodeToString(raw)==encoded);val planHash=hash(checkNotNull(args.getString("p9PostConsumerV2PlanSha256")));require(sha(raw)==planHash)
        val p=JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString())
        keys(p,setOf("consumerStarttimeTicks","consumerIdentitySha256","probeReadySha256","packageUids","defaultFamilyInventory","version","action","inspectionRun","lease","attempt","originalSource","inspectorSource","device","consumerPid","consumerNonce","exitObservationId","exitObservedElapsed","ticket","planHash","receiptHash","originalDraftHash","newDraftHash","firstProofSha256","newProofSha256","observerTerminalSha256","exitObservationSha256","exitPsSha256","exitAmSha256","actionLedgerSha256","stateSnapshotSha256","defaultFamilyDigest","roots"))
        require(p.getInt("version")==2&&p.getString("action")=="inspect-original-after-passive-consumer-exit")
        val lease=uuid(p.getString("lease"));val attempt=uuid(p.getString("attempt"));val run=uuid(p.getString("inspectionRun"));val nonce=uuid(p.getString("consumerNonce"));val observation=uuid(p.getString("exitObservationId"));val ticket=uuid(p.getString("ticket"));require(setOf(lease,attempt,run,nonce,observation,ticket).size==6)
        for(k in listOf("planHash","receiptHash","originalDraftHash","newDraftHash","firstProofSha256","newProofSha256","observerTerminalSha256","exitObservationSha256","exitPsSha256","exitAmSha256","actionLedgerSha256","stateSnapshotSha256","defaultFamilyDigest","consumerIdentitySha256","probeReadySha256"))hash(p.getString(k))
        val old=p.getJSONObject("originalSource");val current=p.getJSONObject("inspectorSource");source(old);source(current);require(old.getInt("uid")==current.getInt("uid")&&old.getString("signerSetSha256")==current.getString("signerSetSha256"));installed(current)
        val device=p.getJSONObject("device");keys(device,setOf("serial","avd","api","bootCount","bootId"));require(device.getInt("api")==33);uuid(device.getString("bootId"))
        val boot=android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT);require(boot==device.getInt("bootCount")&&p.getLong("exitObservedElapsed")<=SystemClock.elapsedRealtime())
        val consumerTicks=p.getLong("consumerStarttimeTicks");require(consumerTicks>0)
        val uids=p.getJSONObject("packageUids");keys(uids,setOf("app","runnerPackage","examiner","systemUi"));require(uids.getInt("app")==Process.myUid()&&uids.getInt("examiner")!=Process.myUid());for((role,name)in mapOf("app" to context.packageName,"runnerPackage" to i.context.packageName,"examiner" to NativeNaturalOsContract.EXAMINER,"systemUi" to "com.android.systemui"))require(context.packageManager.getApplicationInfo(name,0).uid==uids.getInt(role))
        val selfStat=File("/proc/self/stat").readText();require(selfStat.length in 1..16384);val fields=selfStat.substring(selfStat.lastIndexOf(')')+1).trim().split(Regex("\\s+"));val inspectorTicks=fields[19].toLong();require(inspectorTicks>0)
        val consumer=p.getInt("consumerPid");require(consumer>0&&consumer!=Process.myPid()&&nonce!=NativeChatNotificationColdStart.processNonce)
        i.runOnMainSync {require(Stage.values().flatMap{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.none{it is MainActivity})}
        val processes=checkNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses).filter{it.uid==Process.myUid()};require(processes.size==1&&processes.single().pid==Process.myPid())
        val root=context.noBackupFilesDir.canonicalFile;val roots=p.getJSONObject("roots");keys(roots,setOf("P9-native-system-$lease",NativeChatNotificationColdRouterFiles.NAMESPACE,NativeNaturalEntryFence.PERMIT_ROOT,NativeNaturalEntryFence.ENTRY_ROOT))
        fun snapshot(){for(name in roots.keys())equal(rootHashes(root,name),roots.getJSONObject(name))}
        snapshot();val defaults=NativeNaturalFamilyInventory.scan(root,true);require(defaults.complete&&defaults.contentHashes&&defaults.digest==p.getString("defaultFamilyDigest"));equal(JSONObject(defaults.summary()),p.getJSONObject("defaultFamilyInventory"))
        val proofRoot=fixedDir(fixedDir(fixedDir(fixedDir(context.filesDir.canonicalFile,"acceptance"),"P9-natural-os"),lease),attempt)
        val firstBytes=read(File(proofRoot,"first-open.json"),32768);val newBytes=read(File(proofRoot,"new-draft.json"),32768);require(sha(firstBytes)==p.getString("firstProofSha256")&&sha(newBytes)==p.getString("newProofSha256"))
        val first=JSONObject(firstBytes.toString(Charsets.UTF_8));val newer=JSONObject(newBytes.toString(Charsets.UTF_8))
        for(v in listOf(first,newer))require(v.getString("lease")==lease&&v.getString("attempt")==attempt&&v.getInt("pid")==consumer&&v.getString("nonce")==nonce&&v.getInt("bootCount")==boot&&v.getString("ticket")==ticket&&v.getInt("commands")==0)
        require(first.getString("phase")=="first-open-complete"&&newer.getString("phase")=="new-draft-observed"&&newer.getInt("newIntentHandled")==0&&first.getString("draftHash")==p.getString("originalDraftHash")&&newer.getString("draftHash")==p.getString("newDraftHash"))
        require(first.getString("appHash")==old.getString("appSha256")&&first.getString("runnerHash")==old.getString("runnerSha256")&&first.getLong("elapsed")<=newer.getLong("elapsed")&&newer.getLong("elapsed")<=p.getLong("exitObservedElapsed"))
        val folder=evidenceDir(lease,attempt);NativeNaturalOsContract.append(File(folder,"$run-intent.json"),JSONObject().put("planBase64",encoded).put("planSha256",planHash))
        val result=JSONObject().put("version",2).put("lease",lease).put("attempt",attempt).put("inspectionRun",run).put("planSha256",planHash).put("consumerPid",consumer).put("consumerNonce",nonce).put("consumerStarttimeTicks",consumerTicks).put("inspectorStarttimeTicks",inspectorTicks).put("consumerIdentitySha256",p.getString("consumerIdentitySha256")).put("probeReadySha256",p.getString("probeReadySha256")).put("inspectorPid",Process.myPid()).put("inspectorNonce",NativeChatNotificationColdStart.processNonce).put("bootCount",boot).put("kernelBootIdCheckedByNative",false).put("parentPrePostExactDeviceRequired",device).put("productWrites",0).put("stage","existing-key-authentication").put("passed",false).put("originalSource",old).put("inspectorSource",current).put("exitObservationSha256",p.getString("exitObservationSha256")).put("exitObservationId",observation).put("originalFirstProofSha256",p.getString("firstProofSha256")).put("originalNewProofSha256",p.getString("newProofSha256"))
        var storage:NativeSessionStorage?=null;var router:NativeSessionStorage?=null;var session:MobileSession?=null
        try {
            val keyStore=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};require(keyStore.aliases().asSequence().count()==1);val key=keyStore.getKey(NativeChatNotificationNaturalStore.KEY,null);require(key is SecretKey)
            val engine=NativeChatNotificationNaturalFiles(File(root,NativeNaturalEntryFence.PERMIT_ROOT),lease,{key},NativeNaturalOsContract::sync,{o->require(o.uid==old.getInt("uid")&&o.appHash==old.getString("appSha256")&&o.runnerHash==old.getString("runnerSha256")&&o.signerSetHash==old.getString("signerSetSha256"))})
            val state=engine.inspect();val original=state.original;require(state.phase==NativeChatNotificationNaturalPermit.Phase.COMPLETE&&original.attempt==attempt&&original.ticket==ticket&&original.bootCount==boot&&original.planHash==p.getString("planHash")&&original.receiptHash==p.getString("receiptHash"))
            require(state.consumer!!.pid==consumer&&state.consumer.nonce==nonce&&state.draftHash==p.getString("originalDraftHash")&&state.completedElapsed!!<=first.getLong("elapsed"))
            require(original.writerPid!=Process.myPid()&&original.writerNonce!=NativeChatNotificationColdStart.processNonce)
            result.put("stage","authenticated-local-cold-read")
            val store=NativeSessionStorage.inspectNaturalOriginal(context,lease);storage=store;val routing=NativeSessionStorage.inspectNaturalOriginal(context,lease,true);router=routing
            val marker=JSONObject(checkNotNull(routing.read(NativeChatNotificationColdRouterFiles.RECORD)));require(marker.getString("phase")=="complete");val plan=NativeChatNotificationColdPlan.decode(marker.getJSONObject("original"));require(plan.lease==lease&&plan.case==NativeChatNotificationColdCase.ORIGINAL&&plan.fingerprint==original.planHash&&plan.ticket==ticket)
            val reader=marker.getJSONObject("reader");require(reader.getInt("pid")==consumer&&reader.getString("nonce")==nonce)
            val published=JSONObject(checkNotNull(store.read(NativeNaturalOsContract.RECORD)));require(published.getString("phase")=="published");val publicationRaw=published.getString("publication");val publication=NativeNaturalOsContract.validate(publicationRaw)
            require(sha(publicationRaw.toByteArray())==original.publishedOsHash&&publication.getString("examinerHash")==old.getString("examinerSha256")&&publication.getInt("uid")==old.getInt("uid")&&publication.getString("signerHash")==old.getString("signerSetSha256"))
            require(plan.writerPid==original.writerPid&&plan.writerNonce==original.writerNonce&&plan.appHash==old.getString("appSha256")&&plan.runnerHash==old.getString("runnerSha256"))
            val receipt=NativeChatNotificationColdStart.originalReceipt(store,plan);require(receipt.consumed&&receipt.state=="posted"&&receipt.event.target==plan.target&&receipt.event.threadId==plan.originalThread&&receipt.lease.credentialEpoch==plan.authority)
            require(NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.receipt(receipt.copy(consumed=false)))==original.receiptHash)
            val notices=NativeChatNotificationStore(store);require(notices.ticket(ticket,System.currentTimeMillis())==null&&notices.consume(ticket,System.currentTimeMillis())==null) // Consumed path returns before save; read-only guard would fail any write.
            val metadata=checkNotNull(store.metadata());require(metadata.getJSONObject("draftFiles").length()==0)
            val forbidden=java.util.concurrent.atomic.AtomicInteger()
            fun <T> noTransport(type:Class<T>):T=type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader,arrayOf(type)){_,_,_->forbidden.incrementAndGet();error("Post-consumer inspection never calls transport or commands")})
            val restored=withContext(Dispatchers.Main.immediate){MobileSession(store,noTransport(HostReads::class.java),noTransport(CodexCommands::class.java)).also{session=it;it.restore()}}
            withTimeout(30000){while(withContext(Dispatchers.Main.immediate){restored.restoring})delay(20)}
            withContext(Dispatchers.Main.immediate){require(!restored.localRecoveryRequired&&restored.error==null&&restored.aiSharing.failure==null&&restored.localInspectionQuiescent&&restored.operationRecords.isEmpty());require(restored.selectedThread==plan.originalThread&&NativeChatNoticeTarget.from(checkNotNull(restored.selectedClient.scope))==plan.target&&sha(restored.text.toByteArray())==p.getString("newDraftHash"))}
            require(metadata.getJSONObject("drafts").getString(restored.selectedClient.cacheKey(plan.otherThread))==plan.otherDraft)
            require(forbidden.get()==0&&store.inspectionMutationAttempts.get()==0&&routing.inspectionMutationAttempts.get()==0);store.assertLocalInspectionUnchanged();routing.assertLocalInspectionUnchanged();snapshot();require(engine.inspect()==state)
            require(read(File(proofRoot,"first-open.json"),32768).contentEquals(firstBytes)&&read(File(proofRoot,"new-draft.json"),32768).contentEquals(newBytes))
            val after=NativeNaturalFamilyInventory.scan(root,true);require(after.complete&&after.contentHashes&&after.digest==defaults.digest);installed(current);require(android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT)==boot&&NativeNaturalPreflightEvidence.keyCounts().getInt("aliasCount")==1)
            result.put("stage","original-complete-and-new-draft-survived").put("passed",true).put("originalTicketReadoptionRejected",true).put("realSessionColdRestored",true).put("firstProofSha256",p.getString("firstProofSha256")).put("newProofSha256",p.getString("newProofSha256")).put("allOriginalBytesUnchanged",true).put("transportCalls",forbidden.get()).put("mutationAttempts",store.inspectionMutationAttempts.get()+routing.inspectionMutationAttempts.get())
        }catch(error:Throwable){result.put("failureType",error.javaClass.name);result.put("originalBytesUnchangedAfterFailure",runCatching{snapshot();true}.getOrDefault(false));throw error}
        finally {result.put("mutationAttempts",(storage?.inspectionMutationAttempts?.get() ?: 0)+(router?.inspectionMutationAttempts?.get() ?: 0));result.put("fullDefaultInventoryBefore",JSONObject(defaults.summary()));result.put("fullDefaultInventoryAfter",JSONObject(NativeNaturalFamilyInventory.scan(root,true).summary()));withContext(NonCancellable+Dispatchers.Main.immediate){session?.closeLocalInspection()};router?.retireWriter();storage?.retireWriter();NativeNaturalOsContract.append(File(folder,"$run-result.json"),result);i.sendStatus(2,android.os.Bundle().apply{putString("p9PostConsumerEvidence",result.toString())})}
        Unit
    }}
}
