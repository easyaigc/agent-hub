package com.agenthub.android

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Coordination/fault evidence only; no Android storage, Keystore, HTTP, device or service. */
class AuthorizationCoordinatorTest {
    private class Root(val records: MutableMap<String,String> = mutableMapOf()) {
        val id="public-synthetic-root-"+UUID.randomUUID();val lock=ReentrantLock();var failWrite: String?=null;var failRemove=false
    }
    private class Store(val state: Root): EasyWorkAuthorizationRecords {
        override val root get()=state.id
        override fun <T> transaction(block: ()->T)=state.lock.withLock(block)
        override fun read(key: String)=state.lock.withLock {state.records[key]}
        override fun write(key: String,value: String)=state.lock.withLock {if(state.failWrite==key){state.failWrite=null;error("public synthetic write fault")};state.records[key]=value}
        override fun remove(key: String)=state.lock.withLock {if(state.failRemove){state.failRemove=false;error("public synthetic remove fault")};state.records.remove(key);Unit}
    }
    private val ref="easywork-device-grant:public-coordinator-test"
    private fun grant(token: Char='A')=JSONObject().put("protocol","EWDAP/1").put("grantId","public-grant").put("deviceId","public-device").put("binding",JSONObject().put("bindingId","public-binding").put("target",JSONObject().put("runtimeId","public-runtime"))).put("target",JSONObject().put("runtimeId","public-runtime")).put("keyVersion",1).put("scopes",org.json.JSONArray(listOf("relay:read"))).put("accessToken",token.toString().repeat(43)).put("refreshToken",(token+1).toString().repeat(43)).put("accessExpiresAt","2099-01-01T00:00:00Z").put("refreshExpiresAt","2099-02-01T00:00:00Z")
    private fun setup(): Pair<Store,EasyWorkAuthorizationCoordinator> {
        val s=Store(Root());s.write(ref,JSONObject().put("origin","https://public-synthetic.invalid").put("grant",grant()).put("futureRoot",JSONObject().put("keep",true)).toString());return s to EasyWorkAuthorizationCoordinator(s)
    }
    private fun saved(s: Store)=JSONObject(requireNotNull(s.read(ref)))
    private fun fence(s: Store)=JSONObject(requireNotNull(s.read(EasyWorkAuthorizationCoordinator.fenceKey(ref))))
    private fun pending(c: EasyWorkAuthorizationCoordinator)=requireNotNull(c.access(ref) {null}.refresh)
    private fun revoke(c: EasyWorkAuthorizationCoordinator)=requireNotNull(c.revocation(ref) {})

    @Test fun unknownRevokeFencesLateRefreshAndKeepsBothOriginalIntents() {
        val (s,c)=setup();val refresh=pending(c);val removal=revoke(c)
        assertThrows(EasyWorkAuthorizationBlocked::class.java) {c.refreshed(ref,refresh,grant('C'))}
        val current=saved(s);assertEquals(refresh.operationId,current.getJSONObject("refreshOperation").getString("operationId"));assertEquals(removal.operationId,current.getString("revokeOperationId"));assertEquals(refresh.token,current.getJSONObject("refreshOperation").getString("token"))
        assertEquals(removal.intentHash,fence(s).getJSONObject("revoke").getString("intentHash"));assertThrows(EasyWorkAuthorizationBlocked::class.java) {c.assertAuthorized(ref,grant().getJSONObject("binding"))}
    }
    @Test fun confirmedRevokeNeverRestoresGrantAndRetainsAnImmutableAudit() {
        val (s,c)=setup();val refresh=pending(c);val removal=revoke(c);c.revoked(ref,removal);assertNull(s.read(ref));assertThrows(EasyWorkAuthorizationBlocked::class.java) {c.refreshed(ref,refresh,grant('C'))}
        val audit=fence(s).getJSONObject("revoke");assertEquals("confirmed",audit.getString("state"));assertEquals(removal.saved,audit.getString("saved"));assertTrue(audit.getJSONObject("localReceipt").getJSONObject("futureRoot").getBoolean("keep"))
    }
    @Test fun restoredRecordsFenceWithoutAnOldCoordinatorOrAnOldMemoryLock() {
        val (s,c)=setup();val removal=revoke(c);val cold=Store(Root(s.state.records.toMutableMap()));val recovered=EasyWorkAuthorizationCoordinator(cold)
        assertThrows(EasyWorkAuthorizationBlocked::class.java) {recovered.access(ref) {it.getJSONObject("grant").getString("accessToken")}}
        val same=revoke(recovered);assertEquals(removal.operationId,same.operationId);assertEquals(removal.body,same.body);assertEquals(removal.intentHash,same.intentHash);assertEquals(removal.saved,same.saved)
    }
    @Test fun sharedRefreshLockSerializesDifferentClientsForTheExactRootAndRef()=runBlocking {
        val (s,a)=setup();val b=EasyWorkAuthorizationCoordinator(Store(s.state));val release=CompletableDeferred<Unit>();var requests=0
        val first=async(start=CoroutineStart.UNDISPATCHED) {a.withRefresh(ref) {val ticket=pending(a);requests++;release.await();a.refreshed(ref,ticket,grant('C'))}}
        var entered=false
        val second=async(start=CoroutineStart.UNDISPATCHED) {b.withRefresh(ref) {entered=true;val value=b.access(ref) {if(it.getJSONObject("grant").getString("accessToken")=="C".repeat(43))"C".repeat(43)else null};if(value.refresh!=null)requests++;value.token}}
        assertFalse(entered);release.complete(Unit);withTimeout(2000) {assertEquals("C".repeat(43),first.await());assertEquals("C".repeat(43),second.await())};assertEquals(1,requests)
    }
    @Test fun revokeCanFenceWhileAnotherClientStillWaitsForRefresh()=runBlocking {
        val (s,a)=setup();val b=EasyWorkAuthorizationCoordinator(Store(s.state));val release=CompletableDeferred<Unit>()
        val first=async(start=CoroutineStart.UNDISPATCHED) {a.withRefresh(ref) {val ticket=pending(a);release.await();runCatching {a.refreshed(ref,ticket,grant('C'))}.exceptionOrNull()}}
        val removal=b.withRevoke(ref) {revoke(b).also {b.revoked(ref,it)}};release.complete(Unit);assertTrue(withTimeout(2000) {first.await()} is EasyWorkAuthorizationBlocked);assertNull(s.read(ref));assertEquals(removal.operationId,fence(s).getJSONObject("revoke").getString("operationId"))
    }
    @Test fun identicalRefsInDifferentRootsHaveIndependentLocksAndFences()=runBlocking {
        val (s,a)=setup();val (other,b)=setup();val release=CompletableDeferred<Unit>();val first=async(start=CoroutineStart.UNDISPATCHED) {a.withRefresh(ref) {release.await()}}
        val second=async(start=CoroutineStart.UNDISPATCHED) {b.withRefresh(ref) {pending(b)}};assertTrue(second.isCompleted);val foreign=second.await();revoke(a);assertThrows(IllegalArgumentException::class.java) {a.refreshed(ref,foreign,grant('C'))};assertEquals("C".repeat(43),b.refreshed(ref,foreign,grant('C')));assertNotNull(other.read(ref));assertNotNull(s.read(ref));release.complete(Unit);first.await()
    }
    @Test fun pendingRefreshResumeKeepsItsIdTokenBodyAndUnknownMetadata() {
        val (s,a)=setup();val first=pending(a);val current=saved(s);current.getJSONObject("refreshOperation").put("futurePending",JSONObject().put("keep",true));current.getJSONObject("grant").put("futureGrant","keep");s.write(ref,current.toString())
        val b=EasyWorkAuthorizationCoordinator(Store(s.state));val second=pending(b);assertEquals(first.operationId,second.operationId);assertEquals(first.token,second.token);assertEquals(first.body,second.body);b.refreshed(ref,second,grant('C'))
        val next=saved(s);assertTrue(next.getJSONObject("futureRoot").getBoolean("keep"));assertEquals("keep",next.getJSONObject("grant").getString("futureGrant"));assertTrue(fence(s).getJSONObject("refreshResults").getJSONObject(first.operationId).getJSONObject("futurePending").getBoolean("keep"));assertFalse(next.has("refreshOperation"))
    }
    @Test fun legacyUnknownRevokeIsAdoptedWithoutChangingItsOriginalIdOrSnapshot() {
        val (s,c)=setup();val current=saved(s);val id=UUID.randomUUID().toString();current.put("revokeOperationId",id).put("refreshReceipts",JSONObject().put("unownedField","keep"));s.write(ref,current.toString());assertThrows(EasyWorkAuthorizationBlocked::class.java) {c.assertAuthorized(ref,grant().getJSONObject("binding"))}
        val removal=revoke(c);assertEquals(id,removal.operationId);assertEquals(current.toString(),removal.saved);assertEquals(removal.body,revoke(EasyWorkAuthorizationCoordinator(Store(s.state))).body)
    }
    @Test fun fenceSurvivesFailureBeforeGrantMarkerWriteAndResumesTheSameDelete() {
        val (s,c)=setup();s.state.failWrite=ref;assertThrows(IllegalStateException::class.java) {revoke(c)};val audit=fence(s).getJSONObject("revoke");assertEquals("pending",audit.getString("state"));assertFalse(saved(s).has("revokeOperationId"));assertThrows(EasyWorkAuthorizationBlocked::class.java) {c.assertAuthorized(ref,grant().getJSONObject("binding"))}
        val resumed=revoke(EasyWorkAuthorizationCoordinator(Store(s.state)));assertEquals(audit.getString("operationId"),resumed.operationId);assertEquals(audit.getString("intentHash"),resumed.intentHash)
    }
    @Test fun confirmedFenceSurvivesFailedRemovalAndResumeOnlyFinishesLocalCleanup() {
        val (s,c)=setup();val removal=revoke(c);s.state.failRemove=true;assertThrows(IllegalStateException::class.java) {c.revoked(ref,removal)};assertNotNull(s.read(ref));assertEquals("confirmed",fence(s).getJSONObject("revoke").getString("state"));assertThrows(EasyWorkAuthorizationBlocked::class.java) {c.assertAuthorized(ref,grant().getJSONObject("binding"))};assertNull(c.revocation(ref) {});assertNull(s.read(ref))
    }
    @Test fun latePairingCannotResurrectRevokedRefOrReplaceExistingRotatedFields() {
        val (s,c)=setup();c.install(ref,"https://public-synthetic.invalid",grant('C'));assertEquals("A".repeat(43),saved(s).getJSONObject("grant").getString("accessToken"));val removal=revoke(c);c.revoked(ref,removal);assertThrows(EasyWorkAuthorizationBlocked::class.java) {c.install(ref,"https://public-synthetic.invalid",grant())};assertNull(s.read(ref))
    }
    @Test fun aChangedPendingOperationCannotBeOverwrittenByTheOldResponse() {
        val (s,c)=setup();val old=pending(c);val current=saved(s);current.getJSONObject("refreshOperation").put("operationId",UUID.randomUUID().toString());s.write(ref,current.toString());assertThrows(IllegalStateException::class.java) {c.refreshed(ref,old,grant('C'))};assertEquals(current.toString(),s.read(ref))
    }
    @Test fun wrongFenceScopeAndForeignGrantArePreservedAndRefused() {
        val (s,c)=setup();val removal=revoke(c);val foreign=saved(s);foreign.getJSONObject("grant").put("grantId","different-grant");s.write(ref,foreign.toString());assertThrows(IllegalArgumentException::class.java) {c.revoked(ref,removal)};assertEquals(foreign.toString(),s.read(ref))
        val bad=fence(s).put("ref","different-ref");s.write(EasyWorkAuthorizationCoordinator.fenceKey(ref),bad.toString());assertThrows(IllegalArgumentException::class.java) {c.assertAuthorized(ref,grant().getJSONObject("binding"))};assertEquals(bad.toString(),s.read(EasyWorkAuthorizationCoordinator.fenceKey(ref)))
    }
}
