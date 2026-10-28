package com.agenthub.android

import org.json.JSONObject
import java.util.UUID

/** Test backend evidence only. Never infers a Host response from an applied flag or generates a replacement. */
internal object NativeChatNotificationCycleEvidence {
    const val RECORD = "p9-native-system-permission-cycle-v1"
    val processNonce = UUID.randomUUID().toString()
    private fun uuid(value: String) = value.also { require(UUID.fromString(it).toString() == it) }
    fun epoch(runId: String) = UUID.nameUUIDFromBytes(("p9-system-cycle:" + uuid(runId)).toByteArray()).toString()
    fun recordedOperation(journalRaw: String): NativeChatRecoveryLocalOperation {
        require(journalRaw.toByteArray().size <= 262_144)
        NativeChatNotificationRecoveryStore.validateEncoded(journalRaw)
        val targets = JSONObject(journalRaw).getJSONArray("targets"); require(targets.length() == 1)
        val target = targets.getJSONObject(0)
        require(NativeChatNotificationCodec.target(target.getJSONObject("target")) == NativeChatNoticeTarget("fixture-host", "fixture-workspace", "desktop-runtime"))
        val operations = target.getJSONArray("operations"); require(operations.length() == 1)
        val row = operations.getJSONObject(0); val original = NativeChatRecoveryIntent.parse(row.getJSONObject("original"))
        return NativeChatRecoveryLocalOperation(original, row.getString("authorization"), row.getString("state"),
            if(row.isNull("receipt")) null else NativeChatRecoveryWire.receipt(row.getJSONObject("receipt"), original))
    }
    fun capture(runId: String, lease: String, pid: Int, nonce: String, identityHash: String,
        operation: NativeChatRecoveryLocalOperation, generated: NativeChatRecoveryReceipt): String {
        uuid(runId); uuid(lease); uuid(nonce); require(pid > 0 && chatNoticeHash(identityHash))
        val original = operation.original
        require(original.epoch == epoch(runId) && original.kind == "register" && operation.state == "applied")
        require(generated.stage == "applied" && generated.intent.encoded() == original.encoded())
        require(NativeChatRecoveryCanonical.value(checkNotNull(operation.receipt).json()) == NativeChatRecoveryCanonical.value(generated.json()))
        return NativeChatRecoveryCanonical.value(JSONObject().put("version", 2).put("stage", "denied").put("runId", runId).put("lease", lease)
            .put("writerPid", pid).put("writerNonce", nonce).put("fixtureEpoch", original.epoch)
            .put("identityHash", identityHash).put("originalHash", NativeChatNotificationWire.hash(original.encoded()))
            .put("fixtureResponse", generated.json()).put("fixtureResponseHash", NativeChatRecoveryCanonical.hash(generated.json())))
    }
    fun restore(raw: String, runId: String, lease: String, pid: Int, nonce: String, identityHash: String,
        operation: NativeChatRecoveryLocalOperation): NativeChatRecoveryReceipt {
        require(raw.toByteArray().size <= 32_768 && pid > 0); uuid(runId); uuid(lease); uuid(nonce)
        val marker = JSONObject(raw)
        NativeChatNotificationWire.keys(marker, setOf("version", "stage", "runId", "lease", "writerPid", "writerNonce", "fixtureEpoch", "identityHash", "originalHash", "fixtureResponse", "fixtureResponseHash"))
        require(marker.get("version") == 2 && marker.getString("stage") == "denied" && marker.getString("runId") == runId && marker.getString("lease") == lease)
        require(NativeChatNotificationWire.long(marker, "writerPid") in 1..Int.MAX_VALUE.toLong() && marker.getInt("writerPid") != pid)
        require(uuid(marker.getString("writerNonce")) != nonce && marker.getString("identityHash") == identityHash)
        require(marker.getString("fixtureEpoch") == epoch(runId) && operation.original.epoch == epoch(runId))
        require(marker.getString("originalHash") == NativeChatNotificationWire.hash(operation.original.encoded()))
        val response = marker.getJSONObject("fixtureResponse")
        require(marker.getString("fixtureResponseHash") == NativeChatRecoveryCanonical.hash(response))
        val decoded = NativeChatRecoveryWire.receipt(response, operation.original)
        require(operation.state == "applied" && operation.original.kind == "register" && decoded.stage == "applied")
        require(NativeChatRecoveryCanonical.value(checkNotNull(operation.receipt).json()) == NativeChatRecoveryCanonical.value(response))
        return decoded
    }
    /** Pure inspection of already recorded legacy bytes. No identity loader, controller, command or storage write. */
    fun inspectLegacy(markerRaw: String, identityRaw: String, journalRaw: String, runId: String): JSONObject {
        uuid(runId); require(markerRaw.toByteArray().size <= 16_384 && identityRaw.toByteArray().size <= 16_384 && journalRaw.toByteArray().size <= 262_144)
        val marker = JSONObject(markerRaw)
        NativeChatNotificationWire.keys(marker, setOf("version", "stage", "runId", "originalHash", "identityHash"))
        require(marker.get("version") == 1 && marker.getString("runId") == runId && marker.getString("stage") == "denied")
        NativeChatNotificationRecoveryStore.validateEncoded(journalRaw)
        val targets = JSONObject(journalRaw).getJSONArray("targets"); require(targets.length() == 1)
        val row = targets.getJSONObject(0); val operations = row.getJSONArray("operations"); require(operations.length() == 1)
        val operation = operations.getJSONObject(0); val original = NativeChatRecoveryIntent.parse(operation.getJSONObject("original"))
        val receipt = if(operation.isNull("receipt")) null else NativeChatRecoveryWire.receipt(operation.getJSONObject("receipt"), original)
        val outcomes = row.getJSONArray("outcomes")
        return JSONObject().put("markerSha256", NativeChatNotificationWire.hash(markerRaw)).put("journalSha256", NativeChatNotificationWire.hash(journalRaw))
            .put("identityHashMatches", marker.getString("identityHash") == NativeChatNotificationWire.hash(identityRaw))
            .put("originalHashMatches", marker.getString("originalHash") == NativeChatNotificationWire.hash(original.encoded()))
            .put("epochMatchesDeterministicFixture", original.epoch == epoch(runId)).put("operationId", original.operationId)
            .put("targetMatchesFixture", original.target == NativeChatNoticeTarget("fixture-host", "fixture-workspace", "desktop-runtime"))
            .put("targetHash", NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.target(original.target)))
            .put("operationState", operation.getString("state")).put("recordedResponseStage", receipt?.stage ?: JSONObject.NULL)
            .put("recordedResponseHash", receipt?.let { NativeChatRecoveryCanonical.hash(it.json()) } ?: JSONObject.NULL)
            .put("after", row.getLong("after")).put("outcomeCount", outcomes.length())
            .put("firstOutcomeState", if(outcomes.length() == 1) outcomes.getJSONObject(0).getString("state") else JSONObject.NULL)
            .put("responseProvenance", "App-recorded synthetic fixture response only; not independent Host confirmation")
    }
}
