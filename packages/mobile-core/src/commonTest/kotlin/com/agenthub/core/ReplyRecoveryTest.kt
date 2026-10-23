package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class ReplyRecoveryTest {
    private val vectors = Json.parseToJsonElement(Base64.decode(replyRecoveryVectorBase64).decodeToString()).jsonObject
    private val canonical = vectors.getValue("fingerprints").jsonArray
    private val first = canonical[0].jsonObject
    private val target = first.getValue("target").toString()
    private val fingerprint = first.getValue("fingerprint").jsonPrimitive.content
    private val now = vectors.getValue("nowEpochMillis").jsonPrimitive.long

    @Test fun orderedPayloadMatchesActualHostProducer() {
        for (entry in canonical) {
            val test = entry.jsonObject
            assertEquals(test.getValue("payload").jsonPrimitive.content,
                ReplyRecoveryRules.canonicalPayload(test.getValue("target").toString(), test.getValue("reply").toString()), test.getValue("name").toString())
        }
    }
    @Test fun invalidOrChangedCapturedIntentCannotAliasOriginal() {
        val reply = first.getValue("reply").jsonObject
        assertNull(ReplyRecoveryRules.canonicalPayload(target, JsonObject(reply + ("expectedTurnId" to JsonNull)).toString()))
        assertNull(ReplyRecoveryRules.canonicalPayload(target, JsonObject(reply + ("hostId" to JsonPrimitive("other"))).toString()))
        assertNull(ReplyRecoveryRules.canonicalPayload(target, JsonObject(reply + ("unexpected" to JsonPrimitive(true))).toString()))
        assertNull(ReplyRecoveryRules.canonicalPayload("{", reply.toString()))
        assertNotEquals(first.getValue("payload").jsonPrimitive.content,
            ReplyRecoveryRules.canonicalPayload(target, JsonObject(reply + ("text" to JsonPrimitive("changed"))).toString()))
    }
    @Test fun successorProofRequiresEveryAuthorityAndFreshnessBoundary() {
        for (entry in vectors.getValue("proofs").jsonArray) {
            val test = entry.jsonObject
            assertEquals(test.getValue("expected").jsonPrimitive.boolean,
                ReplyRecoveryRules.strongNotAccepted(test.getValue("value").toString(), target, "host", "thread", "operation", fingerprint, now),
                test.getValue("name").toString())
        }
        val numeric = vectors.getValue("proofs").jsonArray[0].jsonObject.getValue("value").toString()
            .replace(":100,", ":100.0,").replace(":200,", ":200.0,").replace(":2,", ":2.0,")
        assertTrue(ReplyRecoveryRules.strongNotAccepted(numeric, target, "host", "thread", "operation", fingerprint, now))
    }
    @Test fun machineEqualityDoesNotAuthorizeLegacyAccountPayloadChanges() {
        val legacy = canonical[1].jsonObject.getValue("target").jsonObject
        val legacyFingerprint = canonical[1].jsonObject.getValue("fingerprint").jsonPrimitive.content
        val changed = JsonObject(legacy + ("account" to buildJsonObject { put("authority", "issuer"); put("subject", "new-subject") }))
        val proof = vectors.getValue("proofs").jsonArray[0].jsonObject.getValue("value").jsonObject
        val evidence = JsonObject(proof.getValue("evidence").jsonObject + ("fingerprint" to JsonPrimitive(legacyFingerprint)))
        val captured = JsonObject(proof + ("target" to legacy) + ("capturedTarget" to legacy) + ("fingerprint" to JsonPrimitive(legacyFingerprint)) + ("evidence" to evidence))
        assertTrue(ReplyRecoveryRules.strongNotAccepted(captured.toString(), legacy.toString(), "host", "thread", "operation", legacyFingerprint, now))
        assertFalse(ReplyRecoveryRules.strongNotAccepted(captured.toString(), changed.toString(), "host", "thread", "operation", legacyFingerprint, now))
    }
    @Test fun explicitRetryAuthorityIsConsumedAndUnknownStaysQueryOnly() {
        assertTrue(ReplyRecoveryRules.mayRetry("not-written", false, true, false))
        assertTrue(ReplyRecoveryRules.mayRetry("strong-not-accepted", false, true, true))
        for (phase in listOf("uncertain", "received", "failed", "rejected", "unavailable", "confirming"))
            assertFalse(ReplyRecoveryRules.mayRetry(phase, false, true, true))
        assertFalse(ReplyRecoveryRules.mayRetry("strong-not-accepted", true, true, true))
        assertFalse(ReplyRecoveryRules.mayRetry("strong-not-accepted", false, false, true))
        assertFalse(ReplyRecoveryRules.mayRetry("strong-not-accepted", false, true, false))
    }
    @Test fun firstInputContinuationRequiresPreparedOrAuthoritativePreflight() {
        assertTrue(ReplyRecoveryRules.mayContinueCreation("prepared", false, null))
        assertFalse(ReplyRecoveryRules.mayContinueCreation("prepared", true, "retry-original-preflight"))
        assertTrue(ReplyRecoveryRules.mayContinueCreation("outcome-unknown", true, "retry-original-preflight", "input-retryable", "prepared-thread"))
        assertFalse(ReplyRecoveryRules.mayContinueCreation("outcome-unknown", true, "retry-original-preflight", "input-retryable", null))
        assertFalse(ReplyRecoveryRules.mayContinueCreation("outcome-unknown", true, "observe-only", "input-retryable", "prepared-thread"))
        assertFalse(ReplyRecoveryRules.mayContinueCreation("outcome-unknown", true, "retry-original-preflight", "input-submitting", "prepared-thread"))
        assertFalse(ReplyRecoveryRules.mayContinueCreation("prepared", true, null))
        assertFalse(ReplyRecoveryRules.mayContinueCreation("prepared", true, "observe-only"))
        for (phase in listOf("submitting", "submitted", "confirmed", "outcome-unknown", "rejected"))
            assertFalse(ReplyRecoveryRules.mayContinueCreation(phase, false, "retry-original-preflight"))
    }
}
