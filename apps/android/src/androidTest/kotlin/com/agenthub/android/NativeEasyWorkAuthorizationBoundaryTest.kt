package com.agenthub.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID

/** Production EWDAP client / storage / AndroidKeystore, only a leased synthetic loopback peer.
 * Dedicated storage namespace and key aliases; never reads default storage.
 * The named late-refresh regression is deliberately asserted, never ignored or marked passed.
 */
@RunWith(AndroidJUnit4::class)
class NativeEasyWorkAuthorizationBoundaryTest {
    @get:Rule val testName=TestName()
    private class Device(val storage: NativeSessionStorage,val identity: EasyWorkIdentity,val signer: EasyWorkDPoP,val code: String,val namespace: String,val keyAlias: String) {
        val auth get()=EasyWorkDeviceAuthorization(NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,namespace,keyAlias))
        suspend fun saved(ref: String)=withContext(Dispatchers.IO) { storage.read(ref)?.let(::JSONObject) }
        suspend fun save(ref: String,value: JSONObject)=withContext(Dispatchers.IO) { storage.write(ref,value.toString()) }
        suspend fun expire(ref: String,field: String="accessExpiresAt") { val saved=requireNotNull(saved(ref));saved.getJSONObject("grant").put(field,Instant.now().minusSeconds(5).toString());save(ref,saved) }
        fun claimKey(origin: String)="easywork-device-claim:"+easyHash((origin+"\n"+signer.thumbprint+"\n"+code).toByteArray())
    }
    private class Fixture(val testMethod: String) {
        val config=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8AuthorizationConfig")) { "Exact leased authorization fixture config is required" })
        val caseId=UUID.randomUUID().toString()
        private val asset=InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").use { it.readBytes() }
        private val vector=JSONObject(String(asset,Charsets.UTF_8)).getJSONObject("credential")
        val origin=origin("origin");val control=origin("controlOrigin")
        val resourceUrl get()=origin+"/v2/remote-access/bindings"
        init {
            require(config.getInt("version")==1&&config.getString("fixture")=="p8-synthetic-ewdap-authorization")
            UUID.fromString(config.getString("instanceId"));require(config.getString("sourceFixtureSha256")==easyHash(asset));require(URI(origin).port!=URI(control).port)
            require(vector.getString("privateKey")=="dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo")
        }
        private fun origin(key: String): String {
            val raw=config.getString(key);val u=URI(raw)
            require(u.scheme=="http"&&u.host=="127.0.0.1"&&u.port in 1..65535&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null&&u.rawPath in listOf("","/")) { "Only synthetic loopback origins are accepted" }
            return raw.trimEnd('/')
        }
        suspend fun api(path: String,body: JSONObject?=null): JSONObject=withContext(Dispatchers.IO) {
            require(path in setOf("/health","/begin-case","/stats","/release-held","/end-case"))
            val c=URL(control+path).openConnection() as HttpURLConnection
            try {
                c.connectTimeout=1000;c.readTimeout=1000;c.instanceFollowRedirects=false;c.setRequestProperty("X-P8-Fixture",config.getString("instanceId"))
                if(body!=null) {val bytes=body.toString().toByteArray();c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.size);c.outputStream.use { it.write(bytes) }}
                check(c.responseCode==200);val bytes=c.inputStream.use { it.readBytes() };require(bytes.size<=65536);JSONObject(String(bytes,Charsets.UTF_8))
            } finally {c.disconnect()}
        }
        suspend fun begin(mode: String) {
            val h=api("/health");for(k in listOf("fixture","instanceId","sourceFixtureSha256","origin","controlOrigin"))require(h.getString(k)==config.getString(k))
            require(h.getBoolean("onlyLoopback")&&!h.getBoolean("realDesktop")&&!h.getBoolean("realModel"))
            assertEquals(caseId,api("/begin-case",JSONObject().put("caseId",caseId).put("mode",mode).put("testMethod",testMethod)).getString("caseId"))
        }
        suspend fun stats()=api("/stats").also { assertEquals(caseId,it.getString("caseId"));assertEquals(testMethod,it.getString("testMethod"));assertEquals(0,it.getJSONArray("errors").length());assertEquals(0,it.getInt("realDesktopRequests"));assertEquals(0,it.getInt("realModelInputs")) }
        suspend fun rows(path: String?=null)=stats().getJSONArray("requests").objects().filter { path==null||it.getString("path").endsWith(path) }
        suspend fun waitHeld(closed: Boolean=false)=withTimeout(2000) { while(true) {val s=stats();if(s.getInt(if(closed)"heldClosed" else "held")==1)break;delay(20)} }
        suspend fun device(suffix: String="a"): Device=withContext(Dispatchers.IO) {
            require(suffix in listOf("a","b"))
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val namespace="p8-auth-$caseId-$suffix";val keyAlias="p8-auth-storage-$caseId-$suffix"
            require(!java.io.File(context.noBackupFilesDir,namespace).exists()) { "Synthetic namespace must be fresh" }
            val storage=NativeSessionStorage(context,namespace,keyAlias)
            val identity=EasyWorkIdentity("p8-auth-$caseId-$suffix",vector.getString("privateKey"),vector.getJSONObject("binding").getString("mobilePublicKey"))
            storage.write("easywork-device-identity",identity.json().toString())
            assertEquals(identity,EasyWorkIdentity.load(storage))
            Device(storage,identity,EasyWorkDPoP.load(identity),if(suffix=="a")"0123456789ABCDEF" else "123456789ABCDEF0",namespace,keyAlias)
        }
        suspend fun pair(d: Device,auth: EasyWorkDeviceAuthorization=d.auth)=auth.pair(EasyWorkDeviceOffer(origin,d.code))
        suspend fun resource(d: Device,binding: EasyWorkBinding,ref: String,auth: EasyWorkDeviceAuthorization=d.auth): JSONObject {
            val transport=RelayFrameTransport(AgentCandidate("p8-auth-loopback","relay-http",origin),binding.id,{ error("Device authorization must not downgrade to legacy token") },{method,url,body->auth.headers(ref,binding,method,url,body)})
            return try { transport.api("/bindings") } finally { transport.close() }
        }
    }
    private suspend fun scenario(mode: String="normal",block: suspend (Fixture)->Unit) {
        val f=Fixture(testName.methodName)
        try { withTimeout(8000) { f.begin(mode);block(f) } }
        finally {withContext(NonCancellable) {withTimeout(1500) {f.api("/end-case",JSONObject().put("caseId",f.caseId))}}}
    }
    private fun transportLoss(error: Throwable?) {
        assertTrue(error is HostReadError);assertFalse(error is CancellationException)
        assertTrue(generateSequence(error) { it.cause }.take(10).any { it is IOException })
    }
    private fun sameIntent(rows: List<JSONObject>) {
        assertEquals(2,rows.size)
        for(k in listOf("operationId","bodyHash","tokenHash","deviceId","grantId","thumbprint"))assertEquals(rows[0].getString(k),rows[1].getString(k))
        assertEquals(easyCanonical(rows[0].getJSONObject("target")),easyCanonical(rows[1].getJSONObject("target")))
        assertNotEquals(rows[0].getString("jti"),rows[1].getString("jti"))
    }
    @Test(timeout=12_000) fun registrationPersistsVerifiedGrantUsingActualKeystoreDpop()=runBlocking {
        scenario { f->val d=f.device();var pending=0;val (binding,ref)=d.auth.pair(EasyWorkDeviceOffer(f.origin,d.code)) {pending++}
            assertEquals(1,pending);val saved=requireNotNull(d.saved(ref));EasyWorkDeviceGrant(saved.getJSONObject("grant").toString()).verify(d.identity,d.signer,binding)
            assertEquals(ref,requireNotNull(d.saved(d.claimKey(f.origin))).getString("grantRef"));assertEquals(d.signer.thumbprint,EasyWorkDPoP.load(d.identity).thumbprint)
            val rows=f.rows();assertEquals(listOf("POST","GET"),rows.map {it.getString("method")});assertTrue(rows.all {it.getString("thumbprint")==d.signer.thumbprint})
        }
    }
    @Test(timeout=12_000) fun lostClaimPostReplyResumesExactPersistedOperationWithFreshProof()=runBlocking {
        scenario("drop-claim-post") {f->val d=f.device();transportLoss(runCatching {f.pair(d)}.exceptionOrNull());val before=requireNotNull(d.saved(d.claimKey(f.origin)));assertFalse(before.has("claimId"))
            f.pair(d,d.auth);sameIntent(f.rows("/pairing/claims"));assertEquals(before.getString("body"),requireNotNull(d.saved(d.claimKey(f.origin))).getString("body"))
        }
    }
    @Test(timeout=12_000) fun savedPendingClaimResumesPollingWithoutAnotherRegistrationPost()=runBlocking {
        scenario("drop-claim-poll") {f->val d=f.device();transportLoss(runCatching {f.pair(d)}.exceptionOrNull());val before=requireNotNull(d.saved(d.claimKey(f.origin)));assertTrue(before.has("claimId"));f.pair(d,d.auth)
            assertEquals(1,f.rows("/pairing/claims").size);val polls=f.rows().filter {it.getString("method")=="GET"};assertEquals(2,polls.size);assertEquals(polls[0].getString("path"),polls[1].getString("path"));assertNotEquals(polls[0].getString("jti"),polls[1].getString("jti"))
        }
    }
    private suspend fun terminalClaim(mode: String,text: String)=scenario(mode) {f->val d=f.device();val error=runCatching {f.pair(d)}.exceptionOrNull();assertTrue(error is IllegalStateException);assertTrue(error?.message?.contains(text)==true);assertNull(d.saved(d.claimKey(f.origin)));assertEquals(2,f.rows().size)}
    @Test(timeout=12_000) fun rejectedClaimRemovesOnlyItsSyntheticPendingRecord()=runBlocking {terminalClaim("claim-rejected","拒绝")}
    @Test(timeout=12_000) fun explicitlyExpiredClaimRemovesItsSyntheticPendingRecord()=runBlocking {terminalClaim("claim-expired","过期或撤销")}
    @Test(timeout=12_000) fun freshGrantAuthorizesActualV2RelayWithoutRefreshOrLegacyToken()=runBlocking {
        scenario {f->val d=f.device();val (b,r)=f.pair(d);assertTrue(f.resource(d,b,r).getBoolean("synthetic"));assertEquals(0,f.rows("/refresh").size);assertEquals(1,f.rows("/bindings").size)}
    }
    @Test(timeout=12_000) fun refreshRotationPersistsVerifiedGrantAndClearsOriginalIntent()=runBlocking {
        scenario {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r);val old=requireNotNull(d.saved(r)).getJSONObject("grant").getString("accessToken");f.resource(d,b,r)
            val saved=requireNotNull(d.saved(r));assertFalse(saved.has("refreshOperation"));assertNotEquals(old,saved.getJSONObject("grant").getString("accessToken"));assertEquals(1,f.rows("/refresh").size);EasyWorkDeviceGrant(saved.getJSONObject("grant").toString()).verify(d.identity,d.signer,b)
        }
    }
    @Test(timeout=12_000) fun concurrentRequestsOnOneAuthorizationClientShareOneRefresh()=runBlocking {
        scenario {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r);val auth=d.auth;coroutineScope {List(4) {async {f.resource(d,b,r,auth)}}.awaitAll()};assertEquals(1,f.rows("/refresh").size);assertEquals(4,f.rows("/bindings").size)}
    }
    @Test(timeout=12_000) fun lostRefreshReplyResumesOriginalTokenAndOperationAfterClientRecreation()=runBlocking {
        scenario("drop-refresh") {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r);transportLoss(runCatching {d.auth.headers(r,b,"GET",f.resourceUrl)}.exceptionOrNull());val before=requireNotNull(d.saved(r));assertTrue(before.has("refreshOperation"))
            f.resource(d,b,r,d.auth);sameIntent(f.rows("/refresh"));assertFalse(requireNotNull(d.saved(r)).has("refreshOperation"))
        }
    }
    @Test(timeout=12_000) fun cancelledRefreshKeepsIntentAndClosedLateReplyCannotOverwriteRecovery()=runBlocking {
        scenario("hold-refresh") {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r)
            coroutineScope {val waiting=async {d.auth.headers(r,b,"GET",f.resourceUrl)};try {f.waitHeld();waiting.cancelAndJoin();f.waitHeld(true);f.resource(d,b,r,d.auth);val saved=easyCanonical(requireNotNull(d.saved(r)));val released=f.api("/release-held",JSONObject());assertEquals(1,released.getInt("lateClosed"));assertEquals(saved,easyCanonical(requireNotNull(d.saved(r))));sameIntent(f.rows("/refresh"))} finally {waiting.cancelAndJoin()}}
        }
    }
    @Test(timeout=12_000) fun wrongRefreshTargetIsRefusedWithoutReplacingGrantOrOriginalIntent()=runBlocking {
        scenario("bad-refresh-target") {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r);val old=easyCanonical(requireNotNull(d.saved(r)).getJSONObject("grant"));val error=runCatching {d.auth.headers(r,b,"GET",f.resourceUrl)}.exceptionOrNull();assertTrue(error is IllegalArgumentException);val saved=requireNotNull(d.saved(r));assertEquals(old,easyCanonical(saved.getJSONObject("grant")));assertTrue(saved.has("refreshOperation"));assertEquals(0,f.rows("/bindings").size)}
    }
    @Test(timeout=12_000) fun grantValidationRejectsChangedIdentityMetadataScopesAndTokens()=runBlocking {
        scenario {f->val d=f.device();val (b,r)=f.pair(d);val grant=requireNotNull(d.saved(r)).getJSONObject("grant")
            val changes=listOf<(JSONObject)->Unit>({it.getJSONObject("target").put("runtimeId","wrong-runtime")},{it.getJSONObject("binding").getJSONObject("deviceAuthorization").put("authKeyThumbprint","wrong-thumbprint")},{it.put("deviceId","wrong-device")},{it.put("keyVersion",0)},{it.put("scopes",JSONArray(listOf("relay:read","relay:read","runtime:read","runtime:control")))},{it.put("accessToken","short")})
            for(change in changes){val value=JSONObject(grant.toString());change(value);assertThrows(IllegalArgumentException::class.java) {EasyWorkDeviceGrant(value.toString()).verify(d.identity,d.signer,b)}}
            assertEquals(2,f.rows().size)
        }
    }
    @Test(timeout=12_000) fun expiredRefreshCredentialIsRefusedBeforeAnyNetworkRequest()=runBlocking {
        scenario {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r,"refreshExpiresAt");val error=runCatching {d.auth.headers(r,b,"GET",f.resourceUrl)}.exceptionOrNull();assertTrue(error is IllegalArgumentException);assertTrue(error?.message?.contains("设备授权已过期")==true);assertEquals(0,f.rows("/refresh").size);assertFalse(requireNotNull(d.saved(r)).has("refreshOperation"))}
    }
    @Test(timeout=12_000) fun serverRevocationRefusalRetainsPendingIntentAndNeverDowngrades()=runBlocking {
        scenario("refresh-revoked") {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r);val error=runCatching {f.resource(d,b,r)}.exceptionOrNull();assertTrue(error is EasyWorkDeviceAuthorizationError);error as EasyWorkDeviceAuthorizationError;assertEquals(403,error.status);assertEquals("grant_revoked",error.code);assertTrue(requireNotNull(d.saved(r)).has("refreshOperation"));assertEquals(0,f.rows("/bindings").size)}
    }
    @Test(timeout=12_000) fun confirmedRevokeRemovesGrantAndRefusesFurtherAuthorization()=runBlocking {
        scenario {f->val d=f.device();val (b,r)=f.pair(d);d.auth.revoke(r,b);assertNull(d.saved(r));val error=runCatching {d.auth.headers(r,b,"GET",f.resourceUrl)}.exceptionOrNull();assertTrue(error is IllegalStateException);assertEquals(1,f.rows().count {it.getString("method")=="DELETE"});assertEquals(0,f.rows("/bindings").size)}
    }
    @Test(timeout=12_000) fun lostRevokeReplyResumesSameDeletionOperationWithFreshProof()=runBlocking {
        scenario("drop-revoke") {f->val d=f.device();val (b,r)=f.pair(d);transportLoss(runCatching {d.auth.revoke(r,b)}.exceptionOrNull());val id=requireNotNull(d.saved(r)).getString("revokeOperationId");d.auth.revoke(r,b);assertNull(d.saved(r));val rows=f.rows().filter {it.getString("method")=="DELETE"};sameIntent(rows);assertEquals(id,rows[1].getString("operationId"))}
    }
    @Test(timeout=12_000) fun independentGrantRefreshesKeepDeviceTargetAndProofKeysIsolated()=runBlocking {
        scenario {f->val a=f.device();val b=f.device("b");val (ab,ar)=f.pair(a);val (bb,br)=f.pair(b);assertNotEquals(ab.target,bb.target);a.expire(ar);b.expire(br);coroutineScope {listOf(async {f.resource(a,ab,ar)},async {f.resource(b,bb,br)}).awaitAll()};val rows=f.rows("/refresh");assertEquals(2,rows.size);assertEquals(2,rows.map {it.getString("operationId")}.toSet().size);assertEquals(2,rows.map {it.getString("thumbprint")}.toSet().size);assertEquals(setOf(a.identity.deviceId,b.identity.deviceId),rows.map {it.getString("deviceId")}.toSet())}
    }
    @Test(timeout=12_000) fun offerParsingRejectsUntrustedRecipientDuplicateFieldsAndInvalidCode()=runBlocking {
        scenario {f->val d=f.device();val link="easywork://pair?version=1&relay="+Uri.encode(f.origin)+"&code="+d.code;assertEquals(EasyWorkDeviceOffer(f.origin,d.code),EasyWorkDeviceOffer.parse(link))
            for(invalid in listOf(link+"&version=1",link+"&extra=x",link+"#fragment",link.replace(Uri.encode(f.origin),Uri.encode("https://untrusted.example"))))assertThrows(IllegalArgumentException::class.java) {EasyWorkDeviceOffer.parse(invalid)}
            assertThrows(IllegalArgumentException::class.java) {EasyWorkDeviceOffer.code("IIIIIIIIIIIIIIII")};assertThrows(IllegalArgumentException::class.java) {d.signer.proof("GET","http://untrusted.example/path")};assertEquals(0,f.rows().size)
        }
    }
    /** Source review indicates no revoke/refresh shared fence. Keep this as an explicit regression. */
    @Test(timeout=12_000) fun lateRefreshAfterConfirmedRevokeMustNotRestoreRemovedGrant()=runBlocking {
        scenario("hold-refresh") {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r);val auth=d.auth
            coroutineScope {val waiting=async {auth.headers(r,b,"GET",f.resourceUrl)};try {f.waitHeld();auth.revoke(r,b);assertNull(d.saved(r));f.api("/release-held",JSONObject());waiting.await();assertNull("A completed revoke must fence a late refresh save",d.saved(r))} finally {waiting.cancelAndJoin()}}
        }
    }
}
