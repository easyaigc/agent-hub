package com.agenthub.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import java.io.Closeable
import java.util.UUID

/** Non-exported, same-UID, fixed fixture operations. Never addresses the global natural permit. */
internal class NativeNaturalStoreProbeService : Service() {
    companion object {
        const val ACTION = "com.agenthub.android.P9_NATURAL_STORE_FIXTURE"
        const val DESCRIPTOR = "com.agenthub.android.p9NaturalStoreFixture.v1"
        const val HOLD = 1; const val RELEASE = 2; const val CLAIM = 3
    }
    private lateinit var run: String
    private var held: Closeable? = null
    private val nonce = UUID.randomUUID().toString()
    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            fun diagnostic(reason: String)="P9_NATURAL_PROBE_$reason code=$code flags=$flags pos=${data.dataPosition()} size=${data.dataSize()} avail=${data.dataAvail()}"
            require(Binder.getCallingUid() == Process.myUid()) {diagnostic("UID")}
            require(NativeNaturalProbeTransportFlags.admitted(Build.VERSION.SDK_INT, flags)) {diagnostic("FLAGS")}
            require(reply != null) {diagnostic("REPLY")}
            require(!data.hasFileDescriptors()) {diagnostic("FDS")}
            try {data.enforceInterface(DESCRIPTOR)}catch(_: SecurityException){throw SecurityException(diagnostic("INTERFACE"))}
            require(code in setOf(HOLD, RELEASE, CLAIM)) {diagnostic("OPERATION")}
            // The platform consumes its complete version-specific interface header. Never skip
            // purported padding/WorkSource bytes or accept an extra application payload.
            require(data.dataAvail() == 0) {diagnostic("PAYLOAD")}
            try {
                val value = synchronized(this@NativeNaturalStoreProbeService) {
                    val store = NativeChatNotificationNaturalStore.forNativeFixture(this@NativeNaturalStoreProbeService, run)
                    when (code) {
                        HOLD -> { check(held == null); held = store.holdNativeFixtureLock(); "held" }
                        RELEASE -> { checkNotNull(held).close(); held = null; "released" }
                        else -> { check(held == null); val state = store.inspect(); store.claim(NativeNaturalStoreFixture.observation(state.original, nonce)).state.phase.name }
                    }
                }
                reply.writeNoException(); reply.writeInt(Process.myPid()); reply.writeString(nonce); reply.writeString(value)
            } catch (error: Exception) { reply.writeException(error) }
            return true
        }
    }
    override fun onBind(intent: Intent): IBinder {
        NativeChatNotificationNaturalStore.guard(this)
        require(intent.action == ACTION && intent.component?.packageName == packageName && intent.component?.className == javaClass.name)
        require(intent.extras?.keySet() == setOf("p9NaturalStoreRun"))
        run = checkNotNull(intent.getStringExtra("p9NaturalStoreRun")); require(UUID.fromString(run).toString() == run)
        return endpoint
    }
    override fun onUnbind(intent: Intent): Boolean { synchronized(this) { held?.close(); held = null }; return false }
    override fun onDestroy() { synchronized(this) { held?.close(); held = null }; super.onDestroy() }
}

/** Synthetic original/OS observations, actual installed pair/UID, clock, PID and device-key store.
 * Shared by native tests and the named fixture Service; never used by a production/natural route. */
internal object NativeNaturalStoreFixture {
    fun original(context: Context, run: String, challenge: ByteArray): NativeChatNotificationNaturalPermit.Original {
        val installed = NativeChatNotificationNaturalStore.installed(context); val wall = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
        return NativeChatNotificationNaturalPermit.Original(run, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64), installed.appHash, installed.runnerHash,
            installed.signerSetHash, NativeChatNotificationNaturalFiles.sha(challenge), "5".repeat(64), installed.uid,
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT),
            wall, elapsed, elapsed + NativeChatNotificationNaturalPermit.TTL, Int.MAX_VALUE - 1, UUID.randomUUID().toString())
    }
    fun observation(p: NativeChatNotificationNaturalPermit.Original, nonce: String) = NativeChatNotificationNaturalPermit.Observation(p,
        "original", true, NativeChatNotificationNaturalPermit.PACKAGE, true, true, p.deviceChallengeHash,
        true, true, true, true, true, true, false, 1, 0, false, p.bootCount,
        System.currentTimeMillis(), SystemClock.elapsedRealtime(), Process.myPid(), nonce)
}
