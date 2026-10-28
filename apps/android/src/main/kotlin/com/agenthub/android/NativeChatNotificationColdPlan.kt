package com.agenthub.android

import org.json.JSONObject
import java.util.UUID

/** Closed acceptance intent: no namespace, path, transport, method or business input is configurable. */
internal enum class NativeChatNotificationColdCase(val wire: String) {
    ORIGINAL("original"), EXPIRED("expired"), WRONG_RUNTIME("wrong-runtime"), WRONG_CREDENTIAL("wrong-credential"), MISSING_THREAD("missing-thread");
    companion object { fun decode(value: String) = entries.single { it.wire == value } }
}
internal data class NativeChatNotificationColdPlan(val lease: String, val case: NativeChatNotificationColdCase,
    val ticket: String, val writerPid: Int, val writerNonce: String, val issuedAt: Long,
    val appHash: String, val runnerHash: String, val receiptHash: String, val authority: String) {
    val target get() = NativeChatNoticeTarget("p9-cold-$lease", "cold-workspace", "cold-runtime")
    val originalThread get() = "cold-original-$lease"
    val otherThread get() = "cold-other-$lease"
    val originalDraft get() = "Owned cold original draft $lease"
    val otherDraft get() = "Owned cold other draft $lease"
    val endpoint get() = "https://p9-notification-cold.example.invalid"
    val token get() = NativeChatNotificationWire.hash("fixture-cold-token:$lease")
    val storedToken get() = if(case == NativeChatNotificationColdCase.WRONG_CREDENTIAL) NativeChatNotificationWire.hash("fixture-cold-replacement:$lease") else token
    fun json() = JSONObject().put("lease", lease).put("case", case.wire).put("ticket", ticket).put("writerPid", writerPid)
        .put("writerNonce", writerNonce).put("issuedAt", issuedAt).put("appHash", appHash).put("runnerHash", runnerHash)
        .put("receiptHash", receiptHash).put("authority", authority)
    val fingerprint get() = NativeChatRecoveryCanonical.hash(json())
    companion object {
        const val TTL = 15 * 60_000L
        fun seedPreferences(previous: NativeChatNotificationPreferences) = previous.copy(enabled = true,
            kinds = setOf(NativeChatNoticeKind.COMPLETED), soundEnabled = false, badgeEnabled = false, foregroundBanner = false)
        fun uuid(value: String) = value.also { require(UUID.fromString(it).toString() == it) }
        fun decode(raw: JSONObject): NativeChatNotificationColdPlan {
            NativeChatNotificationWire.keys(raw, setOf("lease", "case", "ticket", "writerPid", "writerNonce", "issuedAt", "appHash", "runnerHash", "receiptHash", "authority"))
            fun text(key: String) = NativeChatNotificationWire.text(raw, key)
            fun hash(key: String) = text(key).also { require(chatNoticeHash(it)) }
            val pid = NativeChatNotificationWire.long(raw, "writerPid"); require(pid in 1..Int.MAX_VALUE.toLong())
            return NativeChatNotificationColdPlan(uuid(text("lease")), NativeChatNotificationColdCase.decode(text("case")), uuid(text("ticket")), pid.toInt(),
                uuid(text("writerNonce")), NativeChatNotificationWire.long(raw, "issuedAt"), hash("appHash"), hash("runnerHash"), hash("receiptHash"), hash("authority"))
        }
    }
}
