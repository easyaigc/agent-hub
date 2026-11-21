package com.agenthub.android

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Host JVM boundary vectors; no vendor traffic, server, phone or business/model operation. */
fun main() {
    val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "fixture-runtime")
    val now = Instant.parse("2026-11-18T00:00:00Z")
    val event = NativeChatNotificationEvent("a".repeat(64), target, NativeChatNoticeKind.QUESTION, now.toString(), threadId = "fixture-thread")
    val recipient = X25519PrivateKeyParameters(ByteArray(32) { 7 }, 0)
    val sender = X25519PrivateKeyParameters(ByteArray(32) { 19 }, 0)
    val shared = ByteArray(32); sender.generateSecret(recipient.generatePublicKey(), shared, 0)
    val binding = "fixture-binding"
    val key = ByteArray(32)
    HKDFBytesGenerator(SHA256Digest()).apply {
        init(HKDFParameters(shared, binding.toByteArray(), "codex-manage/notifications/v1".toByteArray()))
        generateBytes(key, 0, key.size)
    }
    val nonce = ByteArray(12) { it.toByte() }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(event.id.toByteArray())
    val sealed = cipher.doFinal(NativeChatNotificationCodec.event(event).toString().toByteArray())
    fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    val wire = JSONObject().put("version", 1).put("bindingId", binding).put("eventId", event.id)
        .put("nonce", b64(nonce)).put("ciphertext", b64(sealed.copyOfRange(0, sealed.size - 16)))
        .put("tag", b64(sealed.takeLast(16).toByteArray())).put("expiresAt", now.plusSeconds(60).toString())
        .put("sound", "default").put("timeSensitive", false)
    val envelope = NativeChatPushEnvelopeCodec.decode(wire, now)
    fun open(value: NativeChatPushEnvelope = envelope, expected: NativeChatNoticeTarget = target) =
        NativeChatPushEnvelopeCodec.open(value, recipient.encoded, b64(sender.generatePublicKey().encoded), expected)
    fun reject(action: () -> Unit) = check(runCatching(action).isFailure)
    check(open() == event)
    reject { open(expected = target.copy(runtimeId = "another-runtime")) }
    reject { open(envelope.copy(eventId = "b".repeat(64))) }
    reject { open(envelope.copy(tag = ByteArray(16))) }
    reject { NativeChatPushEnvelopeCodec.decode(wire, now.plusSeconds(60)) }
    reject { NativeChatPushEnvelopeCodec.decode(JSONObject(wire.toString()).put("expiresAt", now.plusSeconds(661).toString()), now) }
    reject { NativeChatPushEnvelopeCodec.decode(JSONObject(wire.toString()).put("badgeOnly", true), now) }
    reject { NativeChatPushEnvelopeCodec.decode(JSONObject(wire.toString()).put("plaintext", "forbidden"), now) }
    val status = JSONObject().put("version", 1).put("target", NativeChatNotificationCodec.target(target))
        .put("state", "ready").put("delivery", "fcm").put("pushProvider", "fcm")
        .put("deviceTokenSha256", "c".repeat(64)).put("bindingId", binding).put("publicKey", b64(sender.generatePublicKey().encoded))
    check(NativeChatNotificationWire.status(status, target).tokenSha256 == "c".repeat(64))
    check(!NativeChatNotificationWire.status(status, target).foreground("fixture"))
    reject { NativeChatNotificationWire.status(JSONObject(status.toString()).put("deviceTokenSha256", "invalid"), target) }
    val originalWire = JSONObject().put("version", 2).put("operationId", "b8d5811f-de70-4a78-9c5d-94e0e90f742f")
        .put("epoch", "ed116758-eb91-43ad-8f22-ae1c882cc965").put("deviceId", "android-fixture")
        .put("target", NativeChatNotificationCodec.target(target)).put("publicKey", b64(recipient.generatePublicKey().encoded))
        .put("preferences", NativeChatNotificationCodec.preferences(NativeChatNotificationPreferences(enabled = true, revision = "fixture-push")))
        .put("generation", 2L).put("kind", "register").put("pushProvider", "fcm").put("deviceToken", "fixture-token")
    originalWire.put("fingerprint", NativeChatRecoveryCanonical.hash(originalWire))
    val original = NativeChatRecoveryIntent.parse(originalWire)
    check(original.deviceToken == "fixture-token")
    reject { NativeChatRecoveryIntent.parse(JSONObject(originalWire.toString()).put("deviceToken", "another-token")) }
    val ackStatus = JSONObject(status.toString()).put("revision", "fixture-push").put("deviceTokenSha256", NativeChatNotificationWire.hash("fixture-token"))
    val receiptWire = JSONObject().put("version", 2).put("epoch", original.epoch).put("intent", original.json())
        .put("stage", "applied").put("status", ackStatus)
    check(NativeChatRecoveryWire.receipt(receiptWire, original).stage == "applied")
    reject { NativeChatRecoveryWire.receipt(JSONObject(receiptWire.toString()).put("status", JSONObject(ackStatus.toString()).put("deviceTokenSha256", "d".repeat(64))), original) }
    val wrong = JSONObject(originalWire.toString()).put("operationId", "25bb7fc2-953d-4611-9d35-63d0105872bf").apply { remove("fingerprint") }
    wrong.put("fingerprint", NativeChatRecoveryCanonical.hash(wrong))
    reject { NativeChatRecoveryWire.receipt(JSONObject(receiptWire.toString()).put("intent", wrong), original) }
    val caps = JSONObject().put("version", 2).put("epoch", original.epoch).put("target", NativeChatNotificationCodec.target(target))
        .put("operations", org.json.JSONArray(listOf("register", "preferences", "revoke", "feed-reset")))
        .put("limits", JSONObject(NativeChatRecoveryWire.limits))
    check(NativeChatRecoveryWire.capabilities(caps, target).pushProviders.isEmpty())
    check(NativeChatRecoveryWire.capabilities(JSONObject(caps.toString()).put("pushProviders", org.json.JSONArray(listOf("fcm"))), target, pushProviders = true).pushProviders == setOf("fcm"))
    reject { NativeChatRecoveryWire.capabilities(JSONObject(caps.toString()).put("pushProviders", org.json.JSONArray(listOf("fcm"))), target) }
    shared.fill(0); key.fill(0)
    println("PASS encrypted push/FCM status + exact v2 receipt/token fingerprint/capability boundary assertions (auxiliary only)")
}
