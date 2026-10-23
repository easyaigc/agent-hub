package com.agenthub.android

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Real isolated TLS reads and cancellation; no claim, model task or saved user credential. */
@RunWith(AndroidJUnit4::class)
class MobileConnectionBudgetTest {
    @get:Rule val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val args get()=InstrumentationRegistry.getArguments()
    private val endpoint get()=args.getString("privateEndpoint")!!
    private val control get()=args.getString("privateControl")!!
    private fun mode(value: String) {
        val connection=URL(control+"/mode").openConnection() as HttpURLConnection
        try { connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use { it.write(JSONObject().put("mode",value).toString().toByteArray()) };assertEquals(200,connection.responseCode);connection.inputStream.close()
        } finally { connection.disconnect() }
    }
    private fun stats(): JSONObject {
        val connection=URL(control+"/stats").openConnection() as HttpURLConnection
        return try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) } finally { connection.disconnect() }
    }
    private fun assertNoClaimOrCredential() {
        val value=stats();assertEquals(0,value.getInt("bindingCount"))
        assertTrue(value.getJSONArray("requests").objects().all { it.getString("method")=="GET" && !it.getBoolean("authorizationPresent") })
    }
    private suspend fun source(): CodexPublicSource {
        val descriptor=CodexDiscoveryAPI.discover(endpoint)
        return CodexPublicSource(descriptor.target,descriptor.fingerprint,listOf(endpoint))
    }
    private fun client(source: CodexPublicSource)=MobileClient(source.target.hostId,"synthetic connection budget",source.target,endpoint,true,"desktop-runtime",false,null,true,
        PairedCodexConnection(source.target,source.fingerprint,listOf(CodexRoute(endpoint))))
    private suspend fun deadline(maximum: Long,minimum: Long=1_400,block: suspend ()->Any) {
        val start=SystemClock.elapsedRealtime();val failure=runCatching { block() }.exceptionOrNull();val elapsed=SystemClock.elapsedRealtime()-start
        assertTrue("Read must fail transiently at its own budget, not succeed after it: $failure",failure is HostReadError && failure.transient)
        assertTrue("Read budget includes queue/network/dispatch: $elapsed ms",elapsed in minimum..maximum)
    }
    @Before fun reset()=mode("normal")
    @Test fun backgroundDescriptorHasAnAbsoluteTwoSecondBudget()=runBlocking {
        mode("slow-host");deadline(3_500) { CodexDiscoveryAPI.discover(endpoint) };assertNoClaimOrCredential()
    }
    @Test fun explicitPublicDescriptorAllowsSlowResponseWithinEightSeconds()=runBlocking {
        mode("slow-host");val start=SystemClock.elapsedRealtime();val descriptor=CodexDiscoveryAPI.discoverForConnection(endpoint)
        assertEquals("um04-private-fixture",descriptor.target.hostId);assertTrue(SystemClock.elapsedRealtime()-start>=3_000);assertNoClaimOrCredential()
    }
    @Test fun explicitKnownDescriptorKeepsItsPinnedFullTarget()=runBlocking {
        val source=source();mode("slow-host");val descriptor=CodexDiscoveryAPI.discoverForConnection(endpoint,source)
        assertEquals(source.fingerprint,descriptor.fingerprint);assertTrue(sameScope(source.target,descriptor.target));assertNoClaimOrCredential()
    }
    @Test fun publicDirectoryRetainsItsIndependentSixSecondBudget()=runBlocking {
        val source=source();mode("slow-peers");val start=SystemClock.elapsedRealtime()
        assertTrue(CodexDiscoveryAPI.peers(source,endpoint).isEmpty());assertTrue(SystemClock.elapsedRealtime()-start>=3_000);assertNoClaimOrCredential()
        mode("over-budget-peers");deadline(7_500,5_000) { CodexDiscoveryAPI.peers(source,endpoint) };assertNoClaimOrCredential()
    }
    @Test fun savedLegacyDirectoryKeepsTargetAndSixSecondBudget()=runBlocking {
        val source=source();mode("slow-peers");val start=SystemClock.elapsedRealtime()
        assertTrue(CodexDiscoveryAPI.legacyPeers(client(source),"fixture-legacy-token-".repeat(2)).isEmpty());assertTrue(SystemClock.elapsedRealtime()-start>=3_000)
        mode("over-budget-peers");deadline(7_500,5_000) { CodexDiscoveryAPI.legacyPeers(client(source),"fixture-legacy-token-".repeat(2)) }
        val value=stats();assertEquals(0,value.getInt("bindingCount"));assertTrue(value.getJSONArray("requests").objects().all { it.getString("method")=="GET" && it.getBoolean("authorizationPresent") })
    }
    @Test fun parentCancellationRemainsCancellationAndNeverClaims()=runBlocking {
        mode("slow-host");var caught: Throwable?=null
        val job=launch { try { CodexDiscoveryAPI.discover(endpoint) } catch(failure: Throwable) { caught=failure;throw failure } }
        withTimeout(5_000) { while(stats().getJSONArray("requests").length()==0)delay(20) }
        val start=SystemClock.elapsedRealtime();job.cancelAndJoin()
        assertTrue(job.isCancelled);assertTrue(caught is CancellationException);assertTrue("Cancellation does not wait for the remote response",SystemClock.elapsedRealtime()-start<1_500)
        assertNoClaimOrCredential()
    }
    @Test fun blockedCallerCannotReturnSuccessPastAbsoluteDeadline()=runBlocking {
        deadline(3_500) { codexConnectionRead(CodexConnectionReadBudget.BACKGROUND_DISCOVERY) { Thread.sleep(2_200);"expired" } }
        assertTrue(stats().getJSONArray("requests").length()==0)
    }
    @Test fun explicitDescriptorStillStopsAtEightSeconds()=runBlocking {
        mode("over-budget-host");deadline(9_500,7_000) { CodexDiscoveryAPI.discoverForConnection(endpoint) };assertNoClaimOrCredential()
    }
    @Test fun explicitBudgetNeverMasksWrongCertificateOrTarget()=runBlocking {
        val source=source()
        for(value in listOf("bad-fingerprint","wrong-host")) {
            mode(value);val failure=runCatching { CodexDiscoveryAPI.discoverForConnection(endpoint,source) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException);assertTrue(failure!!.message!!.contains("电脑证书或运行时身份不匹配"));assertNoClaimOrCredential()
        }
        mode("normal");val wrongPin=source.copy(fingerprint="0".repeat(64))
        val failure=runCatching { CodexDiscoveryAPI.discoverForConnection(endpoint,wrongPin) }.exceptionOrNull()
        assertNotNull(failure);assertTrue(generateSequence(failure) { it.cause }.take(8).any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException })
        assertFalse(failure is HostReadError && failure.transient);assertNoClaimOrCredential()
    }
    @Test fun legacyAuthenticationFailureIsNotMaskedAsTimeoutOrRetried()=runBlocking {
        val source=source();val failure=runCatching { CodexDiscoveryAPI.legacyPeers(client(source),"synthetic-wrong-reader") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException);assertTrue(failure!!.message!!.contains("401"))
        val value=stats();assertEquals(0,value.getInt("bindingCount"))
        assertEquals(1,value.getJSONArray("requests").objects().count { it.getString("path")=="/v1/discovery/peers" })
        assertTrue(value.getJSONArray("requests").objects().all { it.getString("method")=="GET" })
    }
    @Test fun savedRouteBackgroundDescriptorRemainsTwoSeconds()=runBlocking {
        val source=source();mode("slow-host");deadline(3_500) { NativeHostReads {""}.descriptor(client(source)) };assertNoClaimOrCredential()
    }
    @Test fun explicitQRDescriptorGetsEightSecondsWithoutChangingTrust()=runBlocking {
        val source=source();mode("slow-host");val descriptor=NativeHostReads {""}.connectionDescriptor(client(source))
        assertEquals(source.fingerprint,descriptor.fingerprint);assertTrue(sameScope(source.target,descriptor.target));assertNoClaimOrCredential()
    }
    @Test fun queuedBackgroundReadsShareTheSameWallClockBound()=runBlocking {
        mode("slow-host");val start=SystemClock.elapsedRealtime()
        val outcomes=(0 until 5).map { async { runCatching { CodexDiscoveryAPI.discover(endpoint) }.exceptionOrNull() } }.awaitAll()
        assertTrue(outcomes.all { it is HostReadError && it.transient });assertTrue(SystemClock.elapsedRealtime()-start<3_500);assertNoClaimOrCredential()
    }
    @Test fun drippingResponseCannotExtendBackgroundBudget()=runBlocking {
        val source=source()
        mode("drip-host");deadline(3_200) { CodexDiscoveryAPI.discover(endpoint) };assertNoClaimOrCredential()
        mode("drip-host");deadline(3_200) { NativeHostReads {""}.descriptor(client(source)) };assertNoClaimOrCredential()
    }
}
