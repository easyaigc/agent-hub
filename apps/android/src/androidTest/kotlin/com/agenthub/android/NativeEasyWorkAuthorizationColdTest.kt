package com.agenthub.android

import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Two separately selected invocations with an externally verified force-stop/new PID between.
 * Reader never initializes an identity, namespace, key, claim, fixture or new authorization ID.
 */
@RunWith(AndroidJUnit4::class)
class NativeEasyWorkAuthorizationColdTest {
    private class Fixture(val phase: String) {
        private val instrumentation=InstrumentationRegistry.getInstrumentation()
        private val args=InstrumentationRegistry.getArguments()
        val config=JSONObject(requireNotNull(args.getString("p8ColdConfig")))
        val build=JSONObject(requireNotNull(args.getString("p8ColdBuild")))
        val context=instrumentation.targetContext
        val caseId=config.getString("caseId")
        val namespace="p8-auth-cold-$caseId"
        val alias="p8-auth-cold-storage-$caseId"
        val deviceId="p8-fence-$caseId-a"
        val dpopAlias="agent-hub-easywork-dpop-"+easyHash(deviceId.toByteArray())
        val origin=origin("origin");private val control=origin("controlOrigin")
        val configHash=hash(config)
        val writerKey="p8-cold-writer-receipt-v1"
        val freezeKey="p8-cold-writer-freeze-v1"
        val ticketKey="p8-cold-original-refresh-v1"
        val identityAsset=instrumentation.context.assets.open("easywork-crypto-vector.json").use {it.readBytes()}
        val expectedIdentity=JSONObject(String(identityAsset,Charsets.UTF_8)).getJSONObject("credential")
        val packageHashes=JSONObject().put("appSha256",fileHash(File(context.applicationInfo.sourceDir))).put("runnerSha256",fileHash(File(instrumentation.context.applicationInfo.sourceDir)))
        init {
            require(args.getString("p8ColdPhase")==phase) {"Select exactly the leased writer or reader method"}
            require(context.packageName=="com.easyai.agenthub.android.acceptance"&&instrumentation.context.packageName=="com.easyai.agenthub.android.acceptance.test")
            require(config.getInt("version")==1&&config.getString("fixture")=="p8-synthetic-authorization-cold-v1")
            require(UUID.fromString(caseId).toString()==caseId);UUID.fromString(config.getString("instanceId"));require(URI(origin).port!=URI(control).port)
            require(config.getString("sourceFixtureSha256")==easyHash(identityAsset))
            for(k in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(k).matches(Regex("[a-f0-9]{64}")))
            for(k in listOf("appSha256","runnerSha256"))require(build.getString(k)==packageHashes.getString(k)) {"Installed binary does not match frozen build"}
        }
        private fun origin(key: String): String {val raw=config.getString(key);val u=URI(raw);require(u.scheme=="http"&&u.host=="127.0.0.1"&&u.port in 1..65535&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null&&u.rawPath in listOf("","/"));return raw.trimEnd('/')}
        fun hash(v: JSONObject)=easyHash(easyCanonical(v).toByteArray())
        private fun fileHash(file: File): String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}};return digest.digest().joinToString("") {"%02x".format(it)}}
        suspend fun api(path: String,body: JSONObject?=null): JSONObject=withContext(Dispatchers.IO) {
            require(path in setOf("/health","/stats","/freeze-writer","/reader-begin","/late-delivery","/finish-reader"));val c=URL(control+path).openConnection() as HttpURLConnection
            try {c.connectTimeout=1000;c.readTimeout=1000;c.instanceFollowRedirects=false;c.setRequestProperty("X-P8-Fixture",config.getString("instanceId"));if(body!=null){val b=easyCanonical(body).toByteArray();c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(b.size);c.outputStream.use {it.write(b)}};check(c.responseCode==200);val b=c.inputStream.use {it.readBytes()};require(b.size<=65536);JSONObject(String(b,Charsets.UTF_8))}finally {c.disconnect()}
        }
        suspend fun health() {val h=api("/health");for(k in listOf("fixture","caseId","instanceId","origin","controlOrigin","sourceFixtureSha256","sourceGraphSha256","sidecarStartedAt"))require(h.getString(k)==config.getString(k));require(h.getInt("sidecarPid")==config.getInt("sidecarPid")&&h.getBoolean("onlyLoopback")&&!h.getBoolean("realDesktop")&&!h.getBoolean("realModel"))}
        suspend fun stats()=api("/stats").also {require(it.getString("caseId")==caseId&&it.getString("instanceId")==config.getString("instanceId"));assertEquals(0,it.getJSONArray("errors").length());assertEquals(0,it.getInt("realDesktopRequests"));assertEquals(0,it.getInt("realModelInputs"))}
        suspend fun waitHeld()=withTimeout(2000) {while(stats().getInt("held")!=1)delay(20)}
        suspend fun waitClosed()=withTimeout(2500) {while(!stats().getBoolean("oldSocketClosed"))delay(20)}
        fun keys()=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        fun storage()=NativeSessionStorage(context,namespace,alias)
        suspend fun read(s: NativeSessionStorage,key: String)=withContext(Dispatchers.IO) {s.read(key)?.let(::JSONObject)}
        suspend fun write(s: NativeSessionStorage,key: String,value: JSONObject)=withContext(Dispatchers.IO) {s.write(key,easyCanonical(value))}
        fun auth(s: NativeSessionStorage)=EasyWorkDeviceAuthorization(s)
        fun common(phase: String)=JSONObject().put("version",1).put("fixture",config.getString("fixture")).put("caseId",caseId).put("instanceId",config.getString("instanceId")).put("configSha256",configHash).put("sourceGraphSha256",config.getString("sourceGraphSha256")).put("phase",phase).put("pid",Process.myPid()).put("processStartElapsed",Process.getStartElapsedRealtime()).put("observedElapsed",SystemClock.elapsedRealtime()).put("sourceSha256",build.getString("sourceSha256")).put("appSha256",packageHashes.getString("appSha256")).put("runnerSha256",packageHashes.getString("runnerSha256"))
        fun publish(name: String,proof: JSONObject) {
            val folder=File(context.filesDir,"acceptance/p8-auth-cold/$caseId").apply {mkdirs()};val file=File(folder,name+".json");check(!file.exists()) {"Frozen evidence cannot be replaced"};val atomic=AtomicFile(file);val stream=atomic.startWrite()
            try {stream.write(easyCanonical(proof).toByteArray());atomic.finishWrite(stream)}catch(error: Throwable){atomic.failWrite(stream);throw error}
            android.util.Log.i("P8ColdEvidence","name=$name case=$caseId sha256=${hash(proof)} pid=${Process.myPid()}")
        }
    }
    private fun blocked(error: Throwable?,confirmed: Boolean) {assertTrue("Expected a persisted local authority fence",error is EasyWorkAuthorizationBlocked);assertEquals(confirmed,(error as EasyWorkAuthorizationBlocked).confirmed)}
    private fun lost(error: Throwable?) {assertTrue(error is HostReadError);assertFalse(error is CancellationException);assertTrue(generateSequence(error) {it.cause}.take(10).any {it is IOException})}

    @Test(timeout=60_000) fun writeFrozenUnknownRevokeForColdRestart(): Unit=runBlocking {
        val f=Fixture("writer");val background=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {withTimeout(12_000) {
            f.health();require(f.stats().getString("phase")=="writer-active");require(!File(f.context.noBackupFilesDir,f.namespace).exists());require(!f.keys().containsAlias(f.alias)&&!f.keys().containsAlias(f.dpopAlias))
            val s=f.storage();val identity=EasyWorkIdentity(f.deviceId,f.expectedIdentity.getString("privateKey"),f.expectedIdentity.getJSONObject("binding").getString("mobilePublicKey"));f.write(s,"easywork-device-identity",identity.json());assertEquals(identity,EasyWorkIdentity.load(s));val signer=EasyWorkDPoP.load(identity);val auth=f.auth(s)
            val (binding,ref)=auth.pair(EasyWorkDeviceOffer(f.origin,"0123456789ABCDEF"));val saved=requireNotNull(f.read(s,ref));saved.getJSONObject("grant").put("accessExpiresAt",Instant.now().minusSeconds(5).toString());f.write(s,ref,saved)
            val waiting=background.async {runCatching {auth.headers(ref,binding,"GET",f.origin+"/v2/remote-access/bindings")}};f.waitHeld()
            val before=requireNotNull(f.read(s,ref));val pending=before.getJSONObject("refreshOperation");val guard=requireNotNull(f.read(s,EasyWorkAuthorizationCoordinator.fenceKey(ref)))
            val ticket=JSONObject().put("root",s.coordinationKey).put("ref",ref).put("saved",s.read(ref)!!).put("epoch",guard.getString("epoch")).put("operationId",pending.getString("operationId")).put("token",pending.getString("token")).put("body",easyCanonical(JSONObject().put("operationId",pending.getString("operationId"))))
            f.write(s,f.ticketKey,ticket);lost(runCatching {auth.revoke(ref,binding)}.exceptionOrNull());require(!waiting.isCompleted) {"Original refresh must still be held at writer freeze"}
            val fence=requireNotNull(f.read(s,EasyWorkAuthorizationCoordinator.fenceKey(ref)));val record=requireNotNull(f.read(s,ref));val revoke=fence.getJSONObject("revoke");require(revoke.getString("state")=="pending");val original=JSONObject(revoke.getString("saved")).getJSONObject("grant")
            val proof=f.common("writer-frozen").put("namespace",f.namespace).put("keyAlias",f.alias).put("deviceId",f.deviceId).put("grantRef",ref).put("binding",binding.json).put("rootSha256",easyHash(s.coordinationKey.toByteArray())).put("signerThumbprint",signer.thumbprint).put("fenceSha256",f.hash(fence)).put("savedSha256",f.hash(record)).put("ticketSha256",f.hash(ticket)).put("revokeOperationId",revoke.getString("operationId")).put("revokeBodyHash",easyHash(revoke.getString("body").toByteArray())).put("revokeAuthorizationHash",easyHash(("DPoP "+original.getString("accessToken")).toByteArray())).put("refreshOperationId",pending.getString("operationId")).put("refreshInFlightAtFreeze",true)
            f.write(s,f.writerKey,proof);f.publish("writer-proof",proof)
            val ack=f.api("/freeze-writer",proof);assertEquals(f.hash(proof),ack.getString("writerSha256"));f.write(s,f.freezeKey,JSONObject().put("writerSha256",f.hash(proof)).put("instanceId",f.config.getString("instanceId")))
            f.publish("writer-ready",f.common("writer-held").put("writerSha256",f.hash(proof)))
        }
            // Keep the original target alive so the controller can prove force-stop killed it.
            // A timed-out hold is a failed attempt, never a successful writer JUnit result.
            withTimeout(45_000) { awaitCancellation() }
        }finally {background.cancel()}
    }

    @Test(timeout=20_000) fun readOriginalFenceAndResumeOnlyAfterNewProcessStarts()=runBlocking {
        withTimeout(12_000) {
            val f=Fixture("reader");f.health();val expected=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8ColdWriterProof")));require(expected.getString("caseId")==f.caseId&&expected.getString("configSha256")==f.configHash);require(expected.getInt("pid")!=Process.myPid()&&Process.getStartElapsedRealtime()>expected.getLong("processStartElapsed")) {"Actual new target PID/start is required"}
            require(expected.getString("namespace")==f.namespace&&expected.getString("keyAlias")==f.alias&&expected.getString("deviceId")==f.deviceId);for(k in listOf("sourceSha256","appSha256","runnerSha256"))require(expected.getString(k)==f.build.getString(k))
            require(File(f.context.noBackupFilesDir,f.namespace).isDirectory);val keys=f.keys();require(keys.getKey(f.alias,null) is javax.crypto.SecretKey&&keys.getEntry(f.dpopAlias,null) is KeyStore.PrivateKeyEntry) {"Reader may not recreate missing keys or storage"}
            val s=f.storage();val writer=requireNotNull(f.read(s,f.writerKey));assertEquals(f.hash(expected),f.hash(writer));val ack=requireNotNull(f.read(s,f.freezeKey));assertEquals(f.hash(writer),ack.getString("writerSha256"))
            val ref=writer.getString("grantRef");val binding=EasyWorkBinding(writer.getJSONObject("binding").toString());val fence=requireNotNull(f.read(s,EasyWorkAuthorizationCoordinator.fenceKey(ref)));val record=requireNotNull(f.read(s,ref));val ticket=requireNotNull(f.read(s,f.ticketKey))
            for((name,value) in listOf("fenceSha256" to fence,"savedSha256" to record,"ticketSha256" to ticket))assertEquals(writer.getString(name),f.hash(value))
            require(ticket.getString("root")==s.coordinationKey&&ticket.getString("ref")==ref);assertEquals(writer.getString("rootSha256"),easyHash(s.coordinationKey.toByteArray()));val revoke=fence.getJSONObject("revoke");require(revoke.getString("state")=="pending");assertEquals(writer.getString("revokeOperationId"),revoke.getString("operationId"));assertEquals(writer.getString("revokeBodyHash"),easyHash(revoke.getString("body").toByteArray()))
            val persistedIdentity=requireNotNull(f.read(s,"easywork-device-identity")) {"Reader requires the original identity record"};require(persistedIdentity.getString("deviceId")==f.deviceId&&persistedIdentity.getString("privateKey")==f.expectedIdentity.getString("privateKey"));val identity=EasyWorkIdentity.load(s);require(identity.deviceId==f.deviceId&&identity.privateKey==f.expectedIdentity.getString("privateKey"));val signer=EasyWorkDPoP.load(identity);assertEquals(writer.getString("signerThumbprint"),signer.thumbprint)
            f.waitClosed();val begin=f.common("reader-begin").put("writerSha256",f.hash(writer));f.api("/reader-begin",begin)
            val auth=f.auth(s);blocked(runCatching {auth.headers(ref,binding,"GET",f.origin+"/v2/remote-access/bindings")}.exceptionOrNull(),false)
            val late=f.api("/late-delivery");val clear=EasyWorkDeviceDelivery.open(identity,late.getString("sealedDelivery"),ticket.getString("operationId"));val grant=EasyWorkDeviceGrant(clear.toString()).verify(identity,signer,binding)
            // Rehydrate only the writer-captured immutable attempt, never a new refresh operation.
            val attempt=EasyWorkAuthorizationCoordinator.Refresh(ticket.getString("root"),ticket.getString("ref"),ticket.getString("saved"),ticket.getString("epoch"),ticket.getString("operationId"),ticket.getString("token"),ticket.getString("body"));val coordinator=EasyWorkAuthorizationCoordinator(s)
            blocked(runCatching {coordinator.refreshed(ref,attempt,grant.json)}.exceptionOrNull(),false);assertEquals(writer.getString("fenceSha256"),f.hash(requireNotNull(f.read(s,EasyWorkAuthorizationCoordinator.fenceKey(ref)))));assertEquals(writer.getString("savedSha256"),f.hash(requireNotNull(f.read(s,ref))))
            auth.revoke(ref,binding);assertTrue("Original revoked ref must remain absent",f.read(s,ref)==null);blocked(runCatching {coordinator.refreshed(ref,attempt,grant.json)}.exceptionOrNull(),true);assertTrue("Late save cannot recreate ref",f.read(s,ref)==null);assertEquals(f.hash(writer),f.hash(requireNotNull(f.read(s,f.writerKey))));assertEquals(writer.getString("ticketSha256"),f.hash(requireNotNull(f.read(s,f.ticketKey))))
            val proof=f.common("reader-complete").put("writerSha256",f.hash(writer)).put("writerPid",writer.getInt("pid")).put("signerThumbprint",signer.thumbprint).put("revokeOperationId",revoke.getString("operationId")).put("writerReceiptUnchanged",true).put("pendingLateSaveRejected",true).put("confirmedLateSaveRejected",true).put("grantRemainsAbsent",true).put("lateProbe","writer-captured attempt plus exact held encrypted reply; not a revived dead thread")
            f.write(s,"p8-cold-reader-receipt-v1",proof);f.publish("reader-proof",proof);val finished=f.api("/finish-reader",proof);assertEquals(f.hash(proof),finished.getString("readerSha256"));assertEquals("complete",finished.getString("phase"))
        }
    }
}
