package com.agenthub.android

import com.agenthub.core.TargetScope
import org.json.JSONObject
import java.time.Instant

/** Adapter contract fixtures, not a logged-in EasyWork service or push-delivery acceptance. */
fun main() {
    val a = TargetScope("easywork", "https://fixture.invalid", "subject-a", "fixture-host", "fixture-workspace", "fixture-runtime")
    val b = TargetScope("easywork", "https://fixture.invalid", "subject-b", "fixture-host", "fixture-workspace", "fixture-runtime")
    val raw = JSONObject().put("version", 1).put("id", "a".repeat(64)).put("target", CodexCommandCodec.target(a))
        .put("kind", "question").put("occurredAt", Instant.now().toString()).put("threadId", "fixture-thread")
    val event = NativeTaskNotificationCodec.notice(raw, a)
    check(event.target.agentId == "easywork" && event.target.accountSubject == "subject-a")
    check(runCatching { NativeTaskNotificationCodec.notice(raw, b) }.isFailure)
    check(runCatching { NativeChatNotificationCodec.event(raw) }.isFailure)
    val malformed = JSONObject(raw.toString()).put("readPosition", JSONObject().put("revision", 1).put("messageId", "fixture-message").put("contentVersion", "b".repeat(64)))
    check(runCatching { NativeTaskNotificationCodec.notice(malformed, a) }.isFailure)
    val status = JSONObject().put("version", 1).put("target", CodexCommandCodec.target(a)).put("delivery", "fcm").put("state", "ready")
        .put("pushProvider", "fcm").put("deviceTokenSha256", "c".repeat(64))
    check(NativeTaskNotificationCodec.status(status, a).delivery == "fcm")
    check(runCatching { NativeTaskNotificationCodec.status(JSONObject(status.toString()).put("pushProvider", "apns"), a) }.isFailure)
    check(runCatching { NativeTaskNotificationCodec.status(status, b) }.isFailure)
    println("PASS complete EasyWork account target / Codex separation / malformed read position / provider status")
}
