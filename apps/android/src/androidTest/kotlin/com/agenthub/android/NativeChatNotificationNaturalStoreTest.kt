package com.agenthub.android

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real installed pair/UID, AndroidKeyStore, filesystem and second PID. Original/OS flags are synthetic
 * component inputs. Never opens Main/default storage, mutates permission, posts or contacts a Host. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationNaturalStoreTest {
    @get:Rule(order = 0) internal val evidence = NativeNaturalPreflightEvidence("store")
    @get:Rule(order = 1) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(120)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    @Before fun freshExplicitFixture() {
        Assume.assumeTrue("Explicit lease/run required; a skip is not acceptance", args.getString("p9NaturalStoreLease") != null && args.getString("p9NaturalStoreRun") != null)
        evidence.stage = "ids"
        for (key in listOf("p9NaturalStoreLease", "p9NaturalStoreRun")) require(UUID.fromString(args.getString(key)).toString() == args.getString(key))
        evidence.lease = args.getString("p9NaturalStoreLease"); evidence.run = args.getString("p9NaturalStoreRun")
        evidence.stage = "scope"
        NativeChatNotificationNaturalStore.guard(context)
        evidence.stage = "existing-installation-key"
        require(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(NativeChatNotificationNaturalStore.KEY, null) is javax.crypto.SecretKey)
        evidence.stage = "cold-router"
        require(!NativeChatNotificationColdRouterFiles.hasReadableMarker(context.noBackupFilesDir)) { "Existing cold queue is inspection-only" }
        evidence.stage = "natural-router"
        require(Files.notExists(File(context.noBackupFilesDir, "P9-notification-natural-permit-v1").toPath(), NOFOLLOW_LINKS)) { "Do not mix with a natural permit queue" }
        evidence.stage = "no-activity"
        InstrumentationRegistry.getInstrumentation().runOnMainSync { check(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity }) }
    }
    private fun runId(suffix: String): String = UUID.nameUUIDFromBytes((checkNotNull(args.getString("p9NaturalStoreRun")) + ":" + suffix).toByteArray()).toString()
    private fun inventory(root: File): Map<String, String> {
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return mapOf("@root" to "absent")
        val result = mutableMapOf<String, String>(); var entries = 0; var bytes = 0L
        fun visit(file: File, depth: Int) {
            require(++entries <= 256 && depth <= 8 && !Files.isSymbolicLink(file.toPath()))
            if (file.isDirectory) checkNotNull(file.listFiles()).forEach { visit(it, depth + 1) }
            else { require(file.isFile && result.size < 128); bytes += file.length(); require(bytes <= 32L * 1024 * 1024)
                result[file.relativeTo(root).path] = NativeChatNotificationNaturalFiles.sha(file.readBytes()) }
        }
        visit(root, 0); return result
    }
    private fun seed(run: String, fault: NativeNaturalFault? = null): Pair<NativeChatNotificationNaturalStore, NativeChatNotificationNaturalPermit.Original> {
        require(Files.notExists(NativeChatNotificationNaturalStore.nativeFixtureRoot(context, run).toPath(), NOFOLLOW_LINKS))
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val store = NativeChatNotificationNaturalStore.forNativeFixture(context, run, fault)
        val p = NativeNaturalStoreFixture.original(context, run, challenge)
        try { store.arm(p, challenge) } finally { challenge.fill(0) }; return store to p
    }
    private fun runCase(name: String, body: () -> JSONObject): Unit = runBlocking { withContext(Dispatchers.IO) {
        evidence.stage = "default-families-preflight"
        val before = NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile, true); evidence.before = before
        require(before.complete) { "Default family inventory refused: ${before.reason}" }
        var failure: Throwable? = null
        evidence.stage = "fixture-body:$name"; evidence.bodyEntered = true
        try { evidence.details = body() } catch (error: Throwable) { failure = error }
        evidence.stage = if(failure == null) "default-families-compare" else "fixture-body-failed:$name"
        val after = NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile, true); evidence.after = after
        try { check(after.complete && after.digest == before.digest) } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        if(failure == null)evidence.stage = "case-complete"
        failure?.let { throw it }; Unit
    } }
    @Test fun existingKeySealsClaimAndRetainsOriginalCiphertext() = runCase("existing-key") {
        val run = runId("existing-key"); val (store, p) = seed(run); val root = NativeChatNotificationNaturalStore.nativeFixtureRoot(context, run); val before = inventory(root)
        val claimed = store.claim(NativeNaturalStoreFixture.observation(p, UUID.randomUUID().toString())).state
        check(claimed.phase == NativeChatNotificationNaturalPermit.Phase.CLAIMED && claimed.consumer?.pid == Process.myPid())
        check(before.all { (k, v) -> inventory(root)[k] == v }); check(store.inspect() == claimed)
        check(runCatching { store.claim(NativeNaturalStoreFixture.observation(p, UUID.randomUUID().toString())) }.isFailure)
        JSONObject().put("claimed", true).put("originalCiphertextsUnchanged", true).put("fixtureFiles", JSONObject(inventory(root)))
    }
    @Test fun missingKeyNeverCreatesAliasOrPermitDirectory() = runCase("missing-key") {
        val run = runId("missing-key"); val alias = "p9-natural-absent-$run"; val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        require(!keys.containsAlias(alias)); val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }; val p = NativeNaturalStoreFixture.original(context, run, challenge)
        check(runCatching { NativeChatNotificationNaturalStore.missingKeyNativeFixture(context, run).arm(p, challenge) }.isFailure); challenge.fill(0)
        check(!keys.containsAlias(alias) && Files.notExists(NativeChatNotificationNaturalStore.nativeFixtureRoot(context, run).toPath(), NOFOLLOW_LINKS))
        JSONObject().put("aliasCreated", false).put("directoryCreated", false)
    }
    @Test fun interruptedClaimAndReadbackNeverRearmOriginal() = runCase("interrupted") {
        val results = org.json.JSONArray()
        for (fault in listOf(NativeNaturalFault.AFTER_CLAIM_FENCE, NativeNaturalFault.AFTER_CLAIM_SYNC, NativeNaturalFault.BEFORE_CLAIM_READBACK)) {
            val run = runId(fault.name); val (store, p) = seed(run, fault); val root = NativeChatNotificationNaturalStore.nativeFixtureRoot(context, run); val before = inventory(root)
            check(runCatching { store.claim(NativeNaturalStoreFixture.observation(p, UUID.randomUUID().toString())) }.isFailure)
            check(File(root, NativeChatNotificationNaturalFiles.CLAIM).isFile && before.all { (k, v) -> inventory(root)[k] == v })
            val after = inventory(root); val fresh = NativeChatNotificationNaturalStore.forNativeFixture(context, run)
            check(runCatching { fresh.claim(NativeNaturalStoreFixture.observation(p, UUID.randomUUID().toString())) }.isFailure)
            check(inventory(root) == after)
            if (fault == NativeNaturalFault.AFTER_CLAIM_FENCE) check(runCatching { fresh.inspect() }.isFailure) else check(fresh.inspect().phase == NativeChatNotificationNaturalPermit.Phase.CLAIMED)
            results.put(JSONObject().put("fault", fault.name).put("rearmed", false).put("files", JSONObject(after)))
        }
        JSONObject().put("faults", results)
    }
    @Test fun unknownAndTamperedFilesRemainUnchangedOnRefusal() = runCase("unknown") {
        for (kind in listOf("unknown", "tamper")) {
            val run = runId(kind); val (store, p) = seed(run); val root = NativeChatNotificationNaturalStore.nativeFixtureRoot(context, run)
            if (kind == "unknown") File(root, "unknown.bin").writeText("owned fixture fault") else {
                val file = File(root, NativeChatNotificationNaturalFiles.PERMIT); val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            }
            val before = inventory(root); check(runCatching { store.inspect() }.isFailure)
            check(runCatching { store.claim(NativeNaturalStoreFixture.observation(p, UUID.randomUUID().toString())) }.isFailure); check(inventory(root) == before)
        }
        JSONObject().put("unknownAndTamperPreserved", true)
    }
    @Test fun otherPidLockAndSingleClaimKeepOriginal() = runCase("other-pid") {
        val run = runId("other-pid"); val (store, p) = seed(run); val root = NativeChatNotificationNaturalStore.nativeFixtureRoot(context, run); val before = inventory(root)
        val connected = CountDownLatch(1); val remote = AtomicReference<IBinder>(); var held = false; var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { remote.set(service); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null) }
        }
        var lastRequest = JSONObject()
        fun call(code: Int, descriptor: String = NativeNaturalStoreProbeService.DESCRIPTOR, extra: (Parcel)->Unit = {}): Triple<Int, String, String> {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            lastRequest = JSONObject().put("code", code).put("callerFlags", 0).put("descriptorSha256", NativeChatNotificationNaturalFiles.sha(descriptor.toByteArray()))
            try { data.writeInterfaceToken(descriptor); extra(data)
                lastRequest.put("requestBeforeTransact", NativeNaturalProbeDiagnostics.parcel(data))
                val returned = checkNotNull(remote.get()).transact(code, data, reply, 0)
                lastRequest.put("transactReturned", returned).put("replyBeforeReadException", NativeNaturalProbeDiagnostics.parcel(reply))
                check(returned); reply.readException()
                return Triple(reply.readInt(), checkNotNull(reply.readString()), checkNotNull(reply.readString()))
            } finally {
                lastRequest.put("requestBeforeRecycle", NativeNaturalProbeDiagnostics.parcel(data)).put("replyBeforeRecycle", NativeNaturalProbeDiagnostics.parcel(reply))
                data.recycle(); reply.recycle()
            }
        }
        val refusals = org.json.JSONArray(); val diagnostics = org.json.JSONArray()
        evidence.details = JSONObject().put("refusalDiagnosticsBeforeAssertions", diagnostics).put("originalFixtureInventory", JSONObject(before))
        fun refused(kind: String, guard: String, code: Int = NativeNaturalStoreProbeService.HOLD, descriptor: String = NativeNaturalStoreProbeService.DESCRIPTOR, extra: (Parcel)->Unit = {}) {
            val error = runCatching {call(code, descriptor, extra)}.exceptionOrNull()
            // Persist the actual exception and original inventories BEFORE either existing assertion.
            val observation = NativeNaturalProbeDiagnostics.observation(kind, guard, code, error, lastRequest, before, runCatching { inventory(root) })
            diagnostics.put(observation)
            NativeNaturalProbeDiagnostics.save(context, checkNotNull(evidence.lease), checkNotNull(evidence.run), observation)
            check(error != null && (if(guard == "INTERFACE")error is SecurityException else error is IllegalArgumentException))
            check(error.message?.contains("P9_NATURAL_PROBE_$guard code=$code flags=") == true)
            check(inventory(root) == before)
            refusals.put(JSONObject().put("kind", kind).put("guard", guard).put("guardObservation", error.message).put("synchronousRefusalObserved", true).put("originalFilesUnchanged", true))
        }
        var result = JSONObject()
        try {
            val intent = Intent(context, NativeNaturalStoreProbeService::class.java).setAction(NativeNaturalStoreProbeService.ACTION).putExtra("p9NaturalStoreRun", run)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE); check(bound && connected.await(15, TimeUnit.SECONDS))
            refused("extra-int", "PAYLOAD", extra = {it.writeInt(0x01020304)})
            refused("extra-string", "PAYLOAD", extra = {it.writeString("synthetic-extra")})
            refused("extra-byte", "PAYLOAD", extra = {it.writeByte(1.toByte())})
            refused("wrong-descriptor", "INTERFACE", descriptor = NativeNaturalStoreProbeService.DESCRIPTOR + ".wrong")
            refused("unknown-operation", "OPERATION", code = 4)
            val oneWayData = Parcel.obtain()
            val oneWayReturned = try {
                oneWayData.writeInterfaceToken(NativeNaturalStoreProbeService.DESCRIPTOR)
                checkNotNull(remote.get()).transact(NativeNaturalStoreProbeService.HOLD, oneWayData, null, IBinder.FLAG_ONEWAY)
            } finally {oneWayData.recycle()}
            // There is no remote exception/rejection acknowledgement for oneway. A transport
            // return and unchanged read-only inventory must never be called execution success.
            check(inventory(root) == before)
            val owner = call(NativeNaturalStoreProbeService.HOLD); held = true; check(owner.first != Process.myPid() && owner.third == "held")
            check(runCatching { store.claim(NativeNaturalStoreFixture.observation(p, UUID.randomUUID().toString())) }.exceptionOrNull() is NativeNaturalBusy)
            check(inventory(root) == before); call(NativeNaturalStoreProbeService.RELEASE); held = false
            val claimed = call(NativeNaturalStoreProbeService.CLAIM); val state = store.inspect()
            check(claimed.first == owner.first && state.consumer?.pid == claimed.first && state.consumer?.nonce == claimed.second)
            check(before.all { (k, v) -> inventory(root)[k] == v }); val after = inventory(root)
            check(runCatching { store.claim(NativeNaturalStoreFixture.observation(p, UUID.randomUUID().toString())) }.isFailure); check(inventory(root) == after)
            result = JSONObject().put("otherPid", claimed.first).put("otherNonce", claimed.second).put("contenderRefused", true).put("singleCommittedClaim", true)
                .put("refusalDiagnosticsBeforeAssertions", diagnostics).put("strictSynchronousRefusals", refusals).put("onewayTransactReturned", oneWayReturned)
                .put("onewayRemoteRefusalAcknowledged", false).put("onewayExecutionSuccessClaimed", false)
                .put("onewayObservation", "No reply by contract; read-only inventory unchanged at observation and original files retained after the normal synchronous lock/claim chain")
        } finally { try { if (held) call(NativeNaturalStoreProbeService.RELEASE) } finally { if (bound) context.unbindService(connection) } }
        result
    }
}
