package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID

/** Fixed production client plus native encrypted records / Keystore. Lease required.
 * Only fresh synthetic namespaces and the public RFC7748 identity are used.
 */
@RunWith(AndroidJUnit4::class)
class NativeEasyWorkAuthorizationFenceTest {
    @get:Rule val testName=TestName()
    private class Device(val storage: NativeSessionStorage,val identity: EasyWorkIdentity,val namespace: String,val alias: String,val code: String) {
        val auth get()=EasyWorkDeviceAuthorization(NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,namespace,alias))
        suspend fun saved(ref: String)=withContext(Dispatchers.IO) {storage.read(ref)?.let(::JSONObject)}
        suspend fun save(ref: String,value: JSONObject)=withContext(Dispatchers.IO) {storage.write(ref,value.toString())}
        suspend fun fence(ref: String)=requireNotNull(saved(EasyWorkAuthorizationCoordinator.fenceKey(ref)))
        suspend fun expire(ref: String) {val value=requireNotNull(saved(ref));value.getJSONObject("grant").put("accessExpiresAt",Instant.now().minusSeconds(5).toString());save(ref,value)}
    }
    private class Fixture(val testMethod: String) {
        val config=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8AuthorizationFenceConfig")) {"Exact leased fence fixture config is required"})
        val caseId=UUID.randomUUID().toString()
        private val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").use {it.readBytes()}
        private val vector=JSONObject(String(bytes,Charsets.UTF_8)).getJSONObject("credential")
        val origin=origin("origin");private val control=origin("controlOrigin")
        val resourceUrl get()=origin+"/v2/remote-access/bindings"
        init {require(config.getInt("version")==1&&config.getString("fixture")=="p8-synthetic-authorization-fence");UUID.fromString(config.getString("instanceId"));require(config.getString("sourceFixtureSha256")==easyHash(bytes));require(URI(origin).port!=URI(control).port);require(vector.getString("privateKey")=="dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo")}
        private fun origin(key: String): String {val raw=config.getString(key);val u=URI(raw);require(u.scheme=="http"&&u.host=="127.0.0.1"&&u.port in 1..65535&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null&&u.rawPath in listOf("","/"));return raw.trimEnd('/')}
        suspend fun api(path: String,body: JSONObject?=null): JSONObject=withContext(Dispatchers.IO) {
            require(path in setOf("/health","/begin-case","/stats","/release-held","/end-case"));val c=URL(control+path).openConnection() as HttpURLConnection
            try {c.connectTimeout=1000;c.readTimeout=1000;c.instanceFollowRedirects=false;c.setRequestProperty("X-P8-Fixture",config.getString("instanceId"));if(body!=null){val b=body.toString().toByteArray();c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(b.size);c.outputStream.use {it.write(b)}};check(c.responseCode==200);val b=c.inputStream.use {it.readBytes()};require(b.size<=65536);JSONObject(String(b,Charsets.UTF_8))}finally {c.disconnect()}
        }
        suspend fun begin(mode: String) {val h=api("/health");for(k in listOf("fixture","instanceId","sourceFixtureSha256","origin","controlOrigin"))require(h.getString(k)==config.getString(k));require(h.getBoolean("onlyLoopback")&&!h.getBoolean("realDesktop")&&!h.getBoolean("realModel"));api("/begin-case",JSONObject().put("caseId",caseId).put("mode",mode).put("testMethod",testMethod))}
        suspend fun stats()=api("/stats").also {assertEquals(caseId,it.getString("caseId"));assertEquals(testMethod,it.getString("testMethod"));assertEquals(0,it.getJSONArray("errors").length());assertEquals(0,it.getInt("realDesktopRequests"));assertEquals(0,it.getInt("realModelInputs"))}
        suspend fun rows(path: String?=null)=stats().getJSONArray("requests").objects().filter {path==null||it.getString("path").endsWith(path)}
        suspend fun held()=withTimeout(2000) {while(stats().getInt("held")!=1)delay(20)}
        suspend fun release() {api("/release-held",JSONObject())}
        suspend fun device(suffix: String="a"): Device=withContext(Dispatchers.IO) {require(suffix in listOf("a","b"));val context=InstrumentationRegistry.getInstrumentation().targetContext;val namespace="p8-auth-fence-$caseId-$suffix";val alias="p8-auth-fence-storage-$caseId-$suffix";require(!java.io.File(context.noBackupFilesDir,namespace).exists());val storage=NativeSessionStorage(context,namespace,alias);val identity=EasyWorkIdentity("p8-fence-$caseId-$suffix",vector.getString("privateKey"),vector.getJSONObject("binding").getString("mobilePublicKey"));storage.write("easywork-device-identity",identity.json().toString());assertEquals(identity,EasyWorkIdentity.load(storage));Device(storage,identity,namespace,alias,if(suffix=="a")"0123456789ABCDEF" else "123456789ABCDEF0")}
        suspend fun pair(d: Device,code: String=d.code)=d.auth.pair(EasyWorkDeviceOffer(origin,code))
        suspend fun resource(d: Device,b: EasyWorkBinding,r: String,auth: EasyWorkDeviceAuthorization=d.auth): JSONObject {val t=RelayFrameTransport(AgentCandidate("p8-fence-v2","relay-http",origin),b.id,{error("No legacy fallback")},{method,url,body->auth.headers(r,b,method,url,body)});return try {t.api("/bindings")}finally {t.close()}}
    }
    private suspend fun scenario(mode: String="normal",block: suspend (Fixture)->Unit) {val f=Fixture(testName.methodName);try {withTimeout(8000) {f.begin(mode);block(f)}}finally {withContext(NonCancellable) {withTimeout(1500) {f.api("/end-case",JSONObject().put("caseId",f.caseId))}}}}
    private fun missing(value: JSONObject?) {assertTrue("Revoked grant record must remain absent",value==null)}
    private fun blocked(error: Throwable?,confirmed: Boolean) {assertTrue("Expected explicit local authority refusal",error is EasyWorkAuthorizationBlocked);assertEquals(confirmed,(error as EasyWorkAuthorizationBlocked).confirmed)}
    private fun lost(error: Throwable?) {assertTrue(error is HostReadError);assertFalse(error is CancellationException);assertTrue(generateSequence(error) {it.cause}.take(10).any {it is IOException})}
    private fun sameDelete(rows: List<JSONObject>) {assertEquals(2,rows.size);for(k in listOf("operationId","bodyHash","tokenHash","grantId","deviceId","thumbprint"))assertEquals(rows[0].getString(k),rows[1].getString(k));assertEquals(easyCanonical(rows[0].getJSONObject("target")),easyCanonical(rows[1].getJSONObject("target")));assertNotEquals(rows[0].getString("jti"),rows[1].getString("jti"))}
    private suspend fun race(cross: Boolean,unknown: Boolean)=scenario(if(unknown)"hold-refresh-drop-revoke" else "hold-refresh") {f->
        val d=f.device();val (b,r)=f.pair(d);d.expire(r);val auth=d.auth
        coroutineScope {val waiting=async {runCatching {auth.headers(r,b,"GET",f.resourceUrl)}};try {
            f.held();val original=requireNotNull(d.saved(r)).getJSONObject("refreshOperation");val revoker=if(cross)d.auth else auth
            val removal=runCatching {revoker.revoke(r,b)};if(unknown)lost(removal.exceptionOrNull())else removal.getOrThrow()
            f.release();blocked(waiting.await().exceptionOrNull(),!unknown)
            val fence=d.fence(r);val revoke=fence.getJSONObject("revoke");val saved=JSONObject(revoke.getString("saved"))
            assertEquals(original.getString("operationId"),saved.getJSONObject("refreshOperation").getString("operationId"));assertEquals(easyHash(original.getString("token").toByteArray()),easyHash(saved.getJSONObject("refreshOperation").getString("token").toByteArray()))
            if(unknown){assertTrue(d.saved(r)!=null);blocked(runCatching {d.auth.headers(r,b,"GET",f.resourceUrl)}.exceptionOrNull(),false);d.auth.revoke(r,b);sameDelete(f.rows().filter {it.getString("method")=="DELETE"})}
            missing(d.saved(r));assertEquals("confirmed",d.fence(r).getJSONObject("revoke").getString("state"));assertEquals(1,f.rows("/refresh").size);assertEquals(0,f.rows("/bindings").size)
        }finally {waiting.cancelAndJoin()}}
    }
    @Test(timeout=12_000) fun sameClientConfirmedRevokeRejectsLateRefreshWithoutRestoringGrant()=runBlocking {race(false,false)}
    @Test(timeout=12_000) fun separateClientConfirmedRevokeRejectsLateRefreshWithoutRestoringGrant()=runBlocking {race(true,false)}
    @Test(timeout=12_000) fun unknownRevokeFencesLateRefreshAndResumesOriginalDeleteAcrossClients()=runBlocking {race(true,true)}
    @Test(timeout=12_000) fun unknownRevokeBlocksFreshHeadersAfterStorageReopenUntilOriginalResume()=runBlocking {scenario("drop-revoke") {f->val d=f.device();val (b,r)=f.pair(d);lost(runCatching {d.auth.revoke(r,b)}.exceptionOrNull());val id=d.fence(r).getJSONObject("revoke").getString("operationId");blocked(runCatching {d.auth.headers(r,b,"GET",f.resourceUrl)}.exceptionOrNull(),false);d.auth.revoke(r,b);missing(d.saved(r));sameDelete(f.rows().filter {it.getString("method")=="DELETE"});assertEquals(id,d.fence(r).getJSONObject("revoke").getString("operationId"));assertEquals(0,f.rows("/refresh").size)}}
    @Test(timeout=12_000) fun concurrentClientsOnOneGrantShareOneActualRefreshOperation()=runBlocking {scenario("hold-refresh") {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r);coroutineScope {val first=async {d.auth.headers(r,b,"GET",f.resourceUrl)};var second: Deferred<Map<String,String>>?=null;try {f.held();second=async {d.auth.headers(r,b,"GET",f.resourceUrl)};delay(75);assertEquals(1,f.rows("/refresh").size);f.release();val values=listOf(first.await(),requireNotNull(second).await());assertEquals(values[0].getValue("Authorization"),values[1].getValue("Authorization"));assertNotEquals(values[0].getValue("DPoP"),values[1].getValue("DPoP"));assertEquals(1,f.rows("/refresh").size)}finally {first.cancelAndJoin();second?.cancelAndJoin()}}}}
    @Test(timeout=12_000) fun separateGrantsOnOneStorageRootRemainUsableDuringAnotherRevoke()=runBlocking {scenario("hold-refresh") {f->val d=f.device();val (a,ar)=f.pair(d);val (b,br)=f.pair(d,"123456789ABCDEF0");assertNotEquals(a.target,b.target);d.expire(ar);val auth=d.auth;coroutineScope {val waiting=async {runCatching {auth.headers(ar,a,"GET",f.resourceUrl)}};try {f.held();assertTrue(f.resource(d,b,br,auth).getBoolean("synthetic"));auth.revoke(ar,a);f.release();blocked(waiting.await().exceptionOrNull(),true);missing(d.saved(ar));assertTrue(d.saved(br)!=null);assertTrue(f.resource(d,b,br).getBoolean("synthetic"));assertEquals(1,f.rows("/refresh").size)}finally {waiting.cancelAndJoin()}}}}
    @Test(timeout=12_000) fun identicalRefNamesAcrossNativeStorageRootsKeepTheirOwnAuthority()=runBlocking {scenario {f->val a=f.device();val b=f.device("b");val (ab,ar)=f.pair(a);val (bb,br)=f.pair(b);b.save(ar,requireNotNull(b.saved(br)));a.auth.revoke(ar,ab);missing(a.saved(ar));assertTrue(f.resource(b,bb,ar).getBoolean("synthetic"));assertTrue(b.saved(ar)!=null);assertTrue(b.saved(br)!=null)}}
    @Test(timeout=12_000) fun refreshKeepsUnknownRootGrantAndPendingFieldsInNativeAudit()=runBlocking {scenario("hold-refresh") {f->val d=f.device();val (b,r)=f.pair(d);d.expire(r);coroutineScope {val waiting=async {d.auth.headers(r,b,"GET",f.resourceUrl)};try {f.held();val saved=requireNotNull(d.saved(r));val pending=saved.getJSONObject("refreshOperation");val id=pending.getString("operationId");pending.put("futurePending","keep");saved.put("refreshReceipts",JSONObject().put("ownedElsewhere","keep")).getJSONObject("grant").put("futureGrant","keep");d.save(r,saved);f.release();waiting.await();val current=requireNotNull(d.saved(r));assertEquals("keep",current.getJSONObject("refreshReceipts").getString("ownedElsewhere"));assertEquals("keep",current.getJSONObject("grant").getString("futureGrant"));assertEquals("keep",d.fence(r).getJSONObject("refreshResults").getJSONObject(id).getString("futurePending"))}finally {waiting.cancelAndJoin()}}}}
    @Test(timeout=12_000) fun oldApprovedClaimCannotReinstallItsConfirmedRevokedGrant()=runBlocking {scenario {f->val d=f.device();val (b,r)=f.pair(d);d.auth.revoke(r,b);blocked(runCatching {f.pair(d)}.exceptionOrNull(),true);missing(d.saved(r));assertEquals(1,f.rows("/pairing/claims").size);assertEquals(2,f.rows().count {it.getString("path").contains("/pairing/claims/")})}}
    @Test(timeout=12_000) fun persistedUnknownFenceResumesItsOriginalDeleteWhenGrantFileIsMissing()=runBlocking {scenario("drop-revoke") {f->val d=f.device();val (b,r)=f.pair(d);lost(runCatching {d.auth.revoke(r,b)}.exceptionOrNull());withContext(Dispatchers.IO) {d.storage.remove(r)};d.auth.revoke(r,b);missing(d.saved(r));sameDelete(f.rows().filter {it.getString("method")=="DELETE"});assertEquals("confirmed",d.fence(r).getJSONObject("revoke").getString("state"))}}
    @Test(timeout=12_000) fun legacyPendingRevokeIdIsFencedAndAdoptedWithoutReplacement()=runBlocking {scenario {f->val d=f.device();val (b,r)=f.pair(d);val saved=requireNotNull(d.saved(r));val id=UUID.randomUUID().toString();saved.put("revokeOperationId",id).put("futureOwner","keep");d.save(r,saved);blocked(runCatching {d.auth.headers(r,b,"GET",f.resourceUrl)}.exceptionOrNull(),false);d.auth.revoke(r,b);missing(d.saved(r));val audit=d.fence(r).getJSONObject("revoke");assertEquals(id,audit.getString("operationId"));assertEquals("keep",JSONObject(audit.getString("saved")).getString("futureOwner"));assertEquals(id,f.rows().single {it.getString("method")=="DELETE"}.getString("operationId"))}}
    @Test(timeout=12_000) fun malformedFenceScopeIsRefusedWithoutNetworkOrRecordLoss()=runBlocking {scenario {f->val d=f.device();val (b,r)=f.pair(d);val before=easyHash(easyCanonical(requireNotNull(d.saved(r))).toByteArray());val original=d.fence(r);val corrupt=listOf(JSONObject(original.toString()).put("ref","wrong-synthetic-ref"),JSONObject(original.toString()).put("revoke",JSONObject.NULL),JSONObject(original.toString()).put("revoke",JSONObject().put("state","unsupported")));for(bad in corrupt){d.save(EasyWorkAuthorizationCoordinator.fenceKey(r),bad);val error=runCatching {d.auth.headers(r,b,"GET",f.resourceUrl)}.exceptionOrNull();assertTrue(error is IllegalArgumentException||error is org.json.JSONException);assertEquals(before,easyHash(easyCanonical(requireNotNull(d.saved(r))).toByteArray()))};assertEquals(0,f.rows("/refresh").size);assertEquals(0,f.rows("/bindings").size)}}
}
