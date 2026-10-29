package com.agenthub.android

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Pure proposals confer no storage/Activity capability. The debug durable store authenticates the
 * existing device-key seal, locks, commits and reads back; no natural Activity route is connected.
 * No JSON parser differences, caller-selected namespace, endpoint, method or business input is accepted. */
internal object NativeChatNotificationNaturalPermit {
    const val PACKAGE = "com.easyai.agenthub.android.acceptance"
    const val MAX_BYTES = 8192
    const val TTL = 15 * 60_000L
    enum class Phase { ARMED, CLAIMED, COMPLETE }
    data class Original(val lease: String, val attempt: String, val ticket: String, val planHash: String,
        val receiptHash: String, val authority: String, val originalTokenHash: String,
        val appHash: String, val runnerHash: String, val signerSetHash: String, val deviceChallengeHash: String,
        val publishedOsHash: String, val uid: Int, val bootCount: Int, val issuedWall: Long,
        val issuedElapsed: Long, val expiresElapsed: Long, val writerPid: Int, val writerNonce: String) {
        val host get() = "p9-cold-$lease"
        val workspace get() = "cold-workspace"
        val runtime get() = "cold-runtime"
    }
    data class Consumer(val pid: Int, val nonce: String, val claimedElapsed: Long)
    data class State(val original: Original, val phase: Phase = Phase.ARMED, val consumer: Consumer? = null,
        val completedElapsed: Long? = null, val draftHash: String? = null)
    /** Trusted observations are seams, not SDK/KeyStore evidence produced by this reducer. */
    data class Observation(val original: Original, val originalCase: String, val writerExited: Boolean, val packageName: String, val debug: Boolean,
        val existingDeviceKeyAuthenticated: Boolean, val sealChallengeHash: String,
        val materialComplete: Boolean, val routerArmed: Boolean, val osPublished: Boolean,
        val originalReceiptUnconsumed: Boolean, val strictOriginalTicket: Boolean,
        val currentCanPost: Boolean, val instrumentedReceiver: Boolean,
        val activityEntries: Int, val defaultSelections: Int, val retainedSession: Boolean,
        val bootCount: Int, val wall: Long, val elapsed: Long, val pid: Int, val nonce: String)
    data class Completion(val pid: Int, val nonce: String, val elapsed: Long, val ticket: String,
        val normalizedReceiptHash: String, val host: String, val workspace: String, val runtime: String,
        val receiptConsumed: Boolean, val activityEntries: Int, val defaultSelections: Int,
        val businessCommands: Int, val notificationCommands: Int, val draftHash: String)

    private val fields = listOf("version", "package", "phase", "lease", "attempt", "ticket", "planHash", "receiptHash",
        "authority", "originalTokenHash", "appHash", "runnerHash", "signerSetHash", "deviceChallengeHash", "publishedOsHash",
        "host", "workspace", "runtime", "uid", "bootCount", "issuedWall", "issuedElapsed", "expiresElapsed", "writerPid",
        "writerNonce", "consumerPid", "consumerNonce", "claimedElapsed", "completedElapsed", "draftHash")
    private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
    private fun hash(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
    private fun valid(original: Original) = original.apply {
        listOf(lease, attempt, ticket, writerNonce).forEach(::uuid)
        listOf(planHash, receiptHash, authority, originalTokenHash, appHash, runnerHash, signerSetHash,
            deviceChallengeHash, publishedOsHash).forEach(::hash)
        require(uid > 0 && writerPid in 1 until Int.MAX_VALUE && bootCount >= 0 && issuedWall > 0 && issuedElapsed >= 0)
        require(expiresElapsed > issuedElapsed && expiresElapsed - issuedElapsed <= TTL)
    }
    private fun valid(state: State) = state.apply {
        valid(original)
        consumer?.let { uuid(it.nonce); require(it.pid > 0 && it.pid != original.writerPid && it.nonce != original.writerNonce)
            require(it.claimedElapsed in original.issuedElapsed until original.expiresElapsed) }
        when (phase) {
            Phase.ARMED -> require(consumer == null && completedElapsed == null && draftHash == null)
            Phase.CLAIMED -> require(consumer != null && completedElapsed == null && draftHash == null)
            Phase.COMPLETE -> {
                require(consumer != null && completedElapsed != null && draftHash != null)
                require(completedElapsed >= consumer.claimedElapsed && completedElapsed < original.expiresElapsed)
                hash(draftHash)
            }
        }
    }
    /** Canonical fixed-order UTF-8 schema inside a future authenticated encrypted record, not a plaintext permit. */
    fun encode(state: State): ByteArray {
        valid(state)
        val p = state.original; val c = state.consumer
        val values = listOf("1", PACKAGE, state.phase.name.lowercase(), p.lease, p.attempt, p.ticket, p.planHash,
            p.receiptHash, p.authority, p.originalTokenHash, p.appHash, p.runnerHash, p.signerSetHash,
            p.deviceChallengeHash, p.publishedOsHash, p.host, p.workspace, p.runtime, p.uid, p.bootCount,
            p.issuedWall, p.issuedElapsed, p.expiresElapsed, p.writerPid, p.writerNonce,
            c?.pid ?: "-", c?.nonce ?: "-", c?.claimedElapsed ?: "-", state.completedElapsed ?: "-", state.draftHash ?: "-")
        return fields.zip(values).joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }
            .toByteArray(StandardCharsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): State {
        require(bytes.size in 1..MAX_BYTES)
        val text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val lines = text.split('\n'); require(lines.size == fields.size + 1 && lines.last().isEmpty())
        val v = fields.mapIndexed { i, key -> require(lines[i].startsWith("$key=")); lines[i].substring(key.length + 1) }
        fun value(key: String) = v[fields.indexOf(key)]
        fun number(key: String) = value(key).also { require(it.matches(Regex("0|[1-9][0-9]{0,18}"))) }.toLong()
        fun int(key: String) = number(key).also { require(it <= Int.MAX_VALUE) }.toInt()
        require(value("version") == "1" && value("package") == PACKAGE)
        val p = Original(value("lease"), value("attempt"), value("ticket"), value("planHash"), value("receiptHash"),
            value("authority"), value("originalTokenHash"), value("appHash"), value("runnerHash"), value("signerSetHash"),
            value("deviceChallengeHash"), value("publishedOsHash"), int("uid"), int("bootCount"), number("issuedWall"),
            number("issuedElapsed"), number("expiresElapsed"), int("writerPid"), value("writerNonce"))
        require(value("host") == p.host && value("workspace") == p.workspace && value("runtime") == p.runtime)
        val phase = Phase.entries.single { it.name.lowercase() == value("phase") }
        val consumer = if (value("consumerPid") == "-") {
            require(value("consumerNonce") == "-" && value("claimedElapsed") == "-"); null
        } else Consumer(int("consumerPid"), value("consumerNonce"), number("claimedElapsed"))
        val state = State(p, phase, consumer, if (value("completedElapsed") == "-") null else number("completedElapsed"),
            value("draftHash").takeUnless { it == "-" })
        valid(state); require(encode(state).contentEquals(bytes)); return state
    }
    /** Only prepares a claim. Caller must exclusively commit/read back it before opening any storage/Session. */
    fun proposeClaim(state: State, observed: Observation): State {
        valid(state); val p = state.original; val o = observed
        require(state.phase == Phase.ARMED && o.original == p)
        require(o.originalCase == "original" && o.writerExited)
        require(o.debug && o.packageName == PACKAGE && o.existingDeviceKeyAuthenticated && o.sealChallengeHash == p.deviceChallengeHash)
        require(o.materialComplete && o.routerArmed && o.osPublished && o.originalReceiptUnconsumed && o.strictOriginalTicket)
        require(o.currentCanPost && !o.instrumentedReceiver && o.activityEntries == 1 && o.defaultSelections == 0 && !o.retainedSession)
        uuid(o.nonce); require(o.pid > 0 && o.pid != p.writerPid && o.nonce != p.writerNonce)
        require(o.bootCount == p.bootCount && o.elapsed in p.issuedElapsed until p.expiresElapsed)
        // Reject wall rollback/forward drift; monotonic time and unchanged boot remain primary.
        require(o.wall >= p.issuedWall)
        val wallAge = o.wall - p.issuedWall; val elapsedAge = o.elapsed - p.issuedElapsed
        require(wallAge <= TTL && kotlin.math.abs(wallAge - elapsedAge) <= 60_000)
        return State(p, Phase.CLAIMED, Consumer(o.pid, o.nonce, o.elapsed)).also(::valid)
    }
    /** Read-only reconciliation may inspect CLAIMED/COMPLETE; it never makes either ARMED again. */
    fun proposeComplete(state: State, result: Completion): State {
        valid(state); require(state.phase == Phase.CLAIMED)
        val p = state.original; val c = checkNotNull(state.consumer)
        require(result.pid == c.pid && result.nonce == c.nonce && result.elapsed >= c.claimedElapsed && result.elapsed < p.expiresElapsed)
        require(result.ticket == p.ticket && result.normalizedReceiptHash == p.receiptHash)
        require(result.host == p.host && result.workspace == p.workspace && result.runtime == p.runtime && result.receiptConsumed)
        require(result.activityEntries == 1 && result.defaultSelections == 0 && result.businessCommands == 0 && result.notificationCommands == 0)
        hash(result.draftHash)
        return state.copy(phase = Phase.COMPLETE, completedElapsed = result.elapsed, draftHash = result.draftHash).also(::valid)
    }
}
