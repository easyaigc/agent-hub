package com.agenthub.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/** Test-only diagnostics. Proxy snapshots are read-only; the fixed GET never substitutes WebView acceptance. */
internal object NativeHtmlNetworkProbe {
    private const val URL_VALUE = "https://cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.min.css"
    private const val HOST = "cdnjs.cloudflare.com"
    private const val MAX_BYTES = 4095
    private const val TOTAL_MS = 7500L
    private const val DNS_MS = 2000L
    private const val HTTP_MS = 4500L

    /** One public default-proxy read only: no requests, LinkProperties, settings, or session access. */
    fun proxySnapshot(context: Context): JSONObject {
        val started=SystemClock.elapsedRealtime()
        fun unsupported(): Nothing=throw UnsupportedOperationException("unsupported-system-proxy-snapshot")
        if(context.packageName!="com.easyai.agenthub.android.acceptance"||
            context.applicationInfo.uid!=Process.myUid()||Build.VERSION.SDK_INT!=37||
            Build.HARDWARE !in setOf("ranchu","goldfish"))unsupported()
        val connectivity=context.getSystemService(ConnectivityManager::class.java) ?: unsupported()
        val proxy=connectivity.defaultProxy
        val value=if(proxy==null)JSONObject.NULL else {
            val host=proxy.host ?: unsupported()
            val exclusions=proxy.exclusionList ?: unsupported()
            val pac=proxy.pacFileUrl ?: unsupported()
            if(!host.matches(Regex("[A-Za-z0-9.\\[\\]:-]{1,253}"))||proxy.port !in 1..65535||exclusions.size>32||
                exclusions.any { !it.matches(Regex("[A-Za-z0-9.*_\\[\\]:-]{0,253}")) })unsupported()
            JSONObject().put("host",host).put("port",proxy.port).put("pacIsEmpty",pac==android.net.Uri.EMPTY)
                .put("exclusionList",JSONArray(exclusions.toList()))
        }
        return JSONObject().put("schemaVersion",1).put("package",context.packageName)
            .put("uid",Process.myUid()).put("packageUid",context.applicationInfo.uid).put("pid",Process.myPid())
            .put("sdk",Build.VERSION.SDK_INT).put("elapsedRealtimeMs",SystemClock.elapsedRealtime())
            .put("elapsedMs",SystemClock.elapsedRealtime()-started).put("proxy",value)
    }
    /** Opt-in gate on an existing selector, persisted before rethrowing its original failure. */
    fun captureProxy(context: Context,expectation: String?,requiredExpectation: String,evidence: HtmlPreviewEvidence): Unit {
        if(expectation==null)return
        val record=JSONObject().put("schemaVersion",1)
            .put("expectation",if(expectation in setOf("task-direct","none"))expectation else "[unsupported]")
            .put("requiredExpectation",requiredExpectation).put("status","unsupported")
        var originalFailure: Throwable?=null
        try {
            if(expectation !in setOf("task-direct","none")||expectation!=requiredExpectation)
                throw UnsupportedOperationException("unsupported-html-system-proxy-expectation")
            val snapshot=proxySnapshot(context);record.put("snapshot",snapshot)
            val proxy=snapshot.optJSONObject("proxy")
            val matches=if(expectation=="none")snapshot.isNull("proxy") else proxy!=null&&
                proxy.getString("host")=="10.0.2.2"&&proxy.getInt("port")==12334&&
                proxy.getBoolean("pacIsEmpty")&&proxy.getJSONArray("exclusionList").length()==0
            record.put("status",if(matches)"matched" else "mismatch")
            if(!matches)throw AssertionError("system-proxy-expectation-mismatch-before-html")
        } catch(failure: Throwable) {
            originalFailure=failure
            if(failure !is Exception&&failure !is AssertionError)throw failure
            record.put("failureType",failure.javaClass.name)
            throw failure
        } finally {
            try { evidence.json("system-proxy-readback.json",record) }
            catch(saveFailure: Exception) {
                val original=originalFailure
                if(original==null)throw saveFailure
                if(original!==saveFailure)original.addSuppressed(saveFailure)
            }
        }
    }
    fun capture(context: Context): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + TOTAL_MS
        val record = JSONObject().put("schemaVersion", 1).put("fixedUrl", URL_VALUE)
            .put("pid", Process.myPid()).put("uid", Process.myUid()).put("package", context.packageName)
            .put("packageUid", context.applicationInfo.uid).put("sdk", Build.VERSION.SDK_INT)
            .put("processName", if (Build.VERSION.SDK_INT >= 28) Application.getProcessName().orEmpty().take(160) else "unavailable-api<28")
            .put("onMainThread", Looper.myLooper() == Looper.getMainLooper())
            .put("limits", JSONObject().put("totalWaitMs", TOTAL_MS).put("dnsWaitMs", DNS_MS)
                .put("connectMs", 2000).put("readMs", 2000).put("httpWaitMs", HTTP_MS).put("maximumBodyBytes", MAX_BYTES))
            .put("requestPolicy", JSONObject().put("redirects", false)
                .put("cookiesOrAuthSupplied", false).put("customTrustOrHostnameVerifier", false)
                .put("processBindingOrProxyChanged", false).put("nativeResultCannotReplaceWebViewGate", true)
                .put("retryLimit", "One fresh OkHttp client, one synchronous Call/GET; retryOnConnectionFailure(false), redirects and SSL redirects disabled; authenticators NONE and CookieJar.NO_COOKIES. Normal system DNS/TLS are retained. Library/OS connection internals are not packet-level attempt counts.")
                .put("dnsLimit", "Future wait is bounded. Network.getAllByName has no timeout/cancellation API; interrupt does not prove netd lookup ended. Worker exit is reported separately. HTTP uses its unchanged normal resolver and may perform its own DNS lookup."))
        if (record.getBoolean("onMainThread")) return record.put("outcome", "not-run-main-thread")
        val threads = CopyOnWriteArrayList<Thread>()
        val prefix = "native-html-network-" + UUID.randomUUID().toString().take(8)
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "$prefix-${threads.size}").apply { isDaemon = true; threads.add(this) }
        }
        val connection = AtomicReference<Call?>()
        val cancelled = AtomicBoolean()
        val applicationGetAttempts = AtomicInteger()
        val dnsStarted = AtomicBoolean(); val dnsExited = AtomicBoolean()
        val httpStarted = AtomicBoolean(); val httpExited = AtomicBoolean()
        var dns: Future<JSONObject>? = null
        var http: Future<JSONObject>? = null
        fun remaining(maximum: Long) = minOf(maximum, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        fun await(future: Future<JSONObject>, maximum: Long): JSONObject = try {
            val wait = remaining(maximum)
            if (wait == 0L) throw java.util.concurrent.TimeoutException("Probe total wait budget exhausted")
            future.get(wait, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            future.cancel(true)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            JSONObject().put("outcome", if (failure is java.util.concurrent.TimeoutException) "wait-deadline" else "failed")
                .put("exceptionChain", exceptions(failure))
        }
        try {
            record.put("selinuxContext", try {
                File("/proc/self/attr/current").inputStream().use { input ->
                    val bytes = ByteArray(256); val count = input.read(bytes)
                    if (count <= 0) "unavailable" else String(bytes, 0, count, Charsets.UTF_8).trim().trimEnd('\u0000').take(256)
                }
            } catch (failure: Exception) { JSONObject().put("exceptionChain", exceptions(failure)) })
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val active = connectivity.activeNetwork
            val bound = connectivity.boundNetworkForProcess
            record.put("activeNetworkHandle", active?.networkHandle ?: JSONObject.NULL)
                .put("existingProcessBoundNetworkHandle", bound?.networkHandle ?: JSONObject.NULL)
            val capabilities = active?.let(connectivity::getNetworkCapabilities)
            val transports = JSONArray()
            for ((name, value) in listOf("wifi" to NetworkCapabilities.TRANSPORT_WIFI, "cellular" to NetworkCapabilities.TRANSPORT_CELLULAR,
                "ethernet" to NetworkCapabilities.TRANSPORT_ETHERNET, "vpn" to NetworkCapabilities.TRANSPORT_VPN,
                "bluetooth" to NetworkCapabilities.TRANSPORT_BLUETOOTH)) if (capabilities?.hasTransport(value) == true) transports.put(name)
            val flags = JSONObject()
            for ((name, value) in listOf("internet" to NetworkCapabilities.NET_CAPABILITY_INTERNET, "validated" to NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                "captivePortal" to NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL, "notRestricted" to NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
                "trusted" to NetworkCapabilities.NET_CAPABILITY_TRUSTED, "notVpn" to NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) flags.put(name, capabilities?.hasCapability(value) ?: false)
            record.put("activeCapabilities", JSONObject().put("available", capabilities != null).put("transports", transports).put("flags", flags))
            // No NetworkCapabilities/LinkProperties.toString(): those can disclose SSID/specifiers/accounts.
            val dnsNetwork = bound ?: active
            record.put("dnsNetworkSelection", if (bound != null) "existing-process-bound-network" else "active-network")
            dns = executor.submit(Callable {
                dnsStarted.set(true); val began = SystemClock.elapsedRealtime()
                try {
                    if (dnsNetwork == null) throw IllegalStateException("No current Network for fixed-host DNS")
                    val addresses = dnsNetwork.getAllByName(HOST)
                    JSONObject().put("outcome", "resolved").put("host", HOST).put("elapsedMs", SystemClock.elapsedRealtime() - began)
                        .put("addressCount", addresses.size).put("addresses", JSONArray(addresses.take(8).map { it.hostAddress.orEmpty().take(80) }))
                } catch (failure: Exception) {
                    JSONObject().put("outcome", "failed").put("elapsedMs", SystemClock.elapsedRealtime() - began).put("exceptionChain", exceptions(failure))
                } finally { dnsExited.set(true) }
            })
            record.put("dns", await(requireNotNull(dns), DNS_MS))
            http = executor.submit(Callable {
                httpStarted.set(true); val began = SystemClock.elapsedRealtime()
                val result = JSONObject().put("fixedUrl", URL_VALUE).put("getAttempts", 0)
                try {
                    check(!cancelled.get() && SystemClock.elapsedRealtime() < deadline) { "Probe cancelled before GET" }
                    // Fresh diagnostic client: never inherits business interceptors, pinning, tokens or credentials.
                    val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
                        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                        .connectTimeout(2000, TimeUnit.MILLISECONDS).readTimeout(2000, TimeUnit.MILLISECONDS)
                        .callTimeout(remaining(HTTP_MS).coerceAtLeast(1), TimeUnit.MILLISECONDS).build()
                    try {
                        val owned = client.newCall(Request.Builder().url(URL_VALUE).get().build())
                        connection.set(owned)
                        check(!cancelled.get()) { "Probe cancelled before connection" }
                        // No custom DNS, proxy, SSL factory, hostname verifier, auth/cookie headers or request body.
                        applicationGetAttempts.incrementAndGet()
                        result.put("getAttempts", 1)
                        owned.execute().use { response ->
                            result.put("httpStatus", response.code).put("finalUrlUnchanged", response.request.url.toString() == URL_VALUE)
                                .put("redirectLocationPresent", response.header("Location") != null)
                                .put("cipherSuite", response.handshake?.cipherSuite?.javaName ?: JSONObject.NULL)
                            val headers = JSONObject()
                            for (name in listOf("Content-Type", "Content-Length", "Access-Control-Allow-Origin", "Content-Encoding"))
                                response.header(name)?.let { headers.put(name, safe(it).take(160)) }
                            result.put("headers", headers)
                            val digest = MessageDigest.getInstance("SHA-256"); var count = 0; var eof = false
                            val stream = response.body.byteStream()
                            stream.use { input ->
                                val bytes = ByteArray(1024)
                                while (count < MAX_BYTES) {
                                    check(!cancelled.get() && SystemClock.elapsedRealtime() < deadline) { "Probe deadline during response read" }
                                    val n = input.read(bytes, 0, minOf(bytes.size, MAX_BYTES - count))
                                    if (n < 0) { eof = true; break }
                                    digest.update(bytes, 0, n); count += n
                                }
                            }
                            result.put("outcome", "response").put("bytesRead", count).put("bodyComplete", eof)
                                .put("bodyAtLimit", count == MAX_BYTES).put("sha256OfReadBytes", digest.digest().joinToString("") { "%02x".format(it) })
                        }
                    } finally { client.connectionPool.evictAll() }
                } catch (failure: Exception) {
                    result.put("outcome", "failed").put("exceptionChain", exceptions(failure))
                } finally {
                    connection.getAndSet(null)?.cancel()
                    httpExited.set(true)
                }
                result.put("elapsedMs", SystemClock.elapsedRealtime() - began)
            })
            record.put("https", await(requireNotNull(http), HTTP_MS))
            record.put("outcome", "diagnosed-not-a-webview-verdict")
        } catch (failure: Exception) {
            record.put("outcome", "probe-failed").put("exceptionChain", exceptions(failure))
        } finally {
            cancelled.set(true); dns?.cancel(true); http?.cancel(true)
            connection.getAndSet(null)?.cancel()
            executor.shutdownNow()
            // Cleanup waiting is inside the same total budget. No Thread.stop or false 'cancelled == exited'.
            val cleanupWait = remaining(100)
            if (cleanupWait > 0) try { executor.awaitTermination(cleanupWait, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            record.put("resources", JSONObject().put("dnsWorkerStarted", dnsStarted.get()).put("dnsWorkerExited", dnsExited.get())
                .put("httpWorkerStarted", httpStarted.get()).put("httpWorkerExited", httpExited.get()).put("executorTerminated", executor.isTerminated)
                .put("liveOwnedThreads", JSONArray(threads.filter { it.isAlive }.map { it.name }))
                .put("exitLimit", "Uninterruptible platform DNS/TLS may outlive observation budget; live daemon workers are reported until their own finally exits. Synchronous Call only; no asynchronous dispatcher work. Owned connection pool is evicted in finally; library shared daemon infrastructure is not stopped. No worker-exit claim is made from Future cancellation."))
            record.getJSONObject("requestPolicy").put("applicationGetAttempts", applicationGetAttempts.get())
            record.put("elapsedMs", SystemClock.elapsedRealtime() - started)
        }
        return record
    }

    private fun safe(value: String): String = value
        .replace(Regex("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@"), "$1[redacted]@")
        .replace(Regex("(?i)(authorization|proxy-authorization|cookie|set-cookie|access_token|refresh_token|token)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")

    private fun exceptions(failure: Throwable): JSONObject {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val chain = JSONArray(); var current: Throwable? = failure; var depth = 0
        while (current != null && depth < 8 && seen.add(current)) {
            val suppressed = JSONArray(current.suppressed.take(2).map {
                JSONObject().put("type", it.javaClass.name.take(160)).put("message", safe(it.message.orEmpty()).take(320))
            })
            chain.put(JSONObject().put("type", current.javaClass.name.take(160)).put("message", safe(current.message.orEmpty()).take(384)).put("suppressed", suppressed).put("suppressedTruncated", current.suppressed.size > 2))
            current = current.cause; depth++
        }
        return JSONObject().put("causes", chain).put("truncatedOrCycle", current != null)
    }
}
