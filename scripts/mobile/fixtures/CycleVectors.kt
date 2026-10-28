package com.agenthub.android

import org.json.JSONObject
import java.util.UUID

/** New fixture instances model process-local backend loss; this is not an Android process or real Host proof. */
suspend fun cycleVectors(): Int {
    var passed = 0
    suspend fun test(name: String, action: suspend () -> Unit) { action(); passed++; println("PASS $name") }
    suspend fun writer(): Pair<String, RecoveryNativeFixture> {
        val runId = UUID.randomUUID().toString(); val f = RecoveryNativeFixture(NativeSessionStorage())
        f.epoch = NativeChatNotificationCycleEvidence.epoch(runId); f.events.add(f.event(1)); f.active = setOf(f.event(1).id)
        check(f.sync()?.state == "foreground-synchronized"); return runId to f
    }
    val writerNonce = UUID.randomUUID().toString(); val readerNonce = UUID.randomUUID().toString()
    fun identityHash(f: RecoveryNativeFixture) = NativeChatNotificationWire.hash(checkNotNull(f.storage.read(NativeChatNotificationIdentity.RECORD)))
    test("new fixture loses in-memory receipt but keeps epoch; shipping restore stays unknown without replay") {
        val (_, first) = writer(); val bytes = first.storage.read(NativeChatNotificationRecoveryStore.RECORD)
        val restored = RecoveryNativeFixture(first.storage, false); restored.epoch = first.epoch
        restored.events.addAll(listOf(first.event(1), first.event(2)))
        check(restored.receipts.isEmpty() && restored.sync()?.state == "mutation-unknown-original-query")
        check(restored.submitted.isEmpty() && restored.delivered.isEmpty() && restored.queries.isEmpty())
        check(restored.storage.read(NativeChatNotificationRecoveryStore.RECORD) == bytes && restored.view().after == 1L)
    }
    test("independent cycle restores exact writer-generated response and delivers only new event without submit") {
        val (runId, first) = writer(); val lease = UUID.randomUUID().toString(); val operation = first.view().operations.single()
        val generated = checkNotNull(first.receipts[operation.original.operationId])
        val raw = NativeChatNotificationCycleEvidence.capture(runId, lease, 7, writerNonce, identityHash(first), operation, generated)
        first.storage.write("recorded-synthetic-response-v2", raw)
        val restored = RecoveryNativeFixture(first.storage, false); restored.epoch = first.epoch
        val saved = restored.view().operations.single()
        restored.receipts[saved.original.operationId] = NativeChatNotificationCycleEvidence.restore(checkNotNull(restored.storage.read("recorded-synthetic-response-v2")), runId, lease, 8, readerNonce, identityHash(restored), saved)
        restored.events.addAll(listOf(first.event(1), first.event(2))); restored.active = restored.events.map { it.id }.toSet()
        check(restored.sync()?.state == "foreground-synchronized")
        check(restored.submitted.isEmpty() && restored.queried.map { it.operationId } == listOf(saved.original.operationId))
        check(restored.delivered == listOf(first.event(2).id) && restored.view().after == 2L)
        check(restored.view().outcomes.single { it.event.id == first.event(1).id }.state == "suppressed")
        check(restored.view().operations.single().original.encoded() == operation.original.encoded())
        check(NativeChatRecoveryCanonical.value(checkNotNull(restored.view().operations.single().receipt).json()) == NativeChatRecoveryCanonical.value(generated.json()))
        check(restored.storage.read("recorded-synthetic-response-v2") == raw)
    }
    test("recorded fixture restore rejects altered response lease process epoch and identity without journal changes") {
        val (runId, first) = writer(); val lease = UUID.randomUUID().toString(); val operation = first.view().operations.single()
        val raw = NativeChatNotificationCycleEvidence.capture(runId, lease, 7, writerNonce, identityHash(first), operation, checkNotNull(operation.receipt))
        val before = first.storage.read(NativeChatNotificationRecoveryStore.RECORD)
        val variants = listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("fixtureResponse").getJSONObject("status").put("revision", 999) },
            { it.put("lease", UUID.randomUUID().toString()) }, { it.put("writerPid", 8) }, { it.put("writerNonce", readerNonce) },
            { it.put("fixtureEpoch", UUID.randomUUID().toString()) }, { it.put("identityHash", "f".repeat(64)) }, { it.put("arbitraryBackend", "forbidden") })
        for(change in variants) {
            val modified = JSONObject(raw).also(change).toString()
            check(runCatching { NativeChatNotificationCycleEvidence.restore(modified, runId, lease, 8, readerNonce, identityHash(first), operation) }.isFailure)
            check(first.storage.read(NativeChatNotificationRecoveryStore.RECORD) == before)
        }
    }
    test("read-only legacy inspection hashes recorded response without loading identity or altering original bytes") {
        val (runId, first) = writer(); val operation = first.view().operations.single()
        val identity = checkNotNull(first.storage.read(NativeChatNotificationIdentity.RECORD)); val journal = checkNotNull(first.storage.read(NativeChatNotificationRecoveryStore.RECORD))
        val marker = JSONObject().put("version", 1).put("stage", "denied").put("runId", runId).put("originalHash", NativeChatNotificationWire.hash(operation.original.encoded())).put("identityHash", NativeChatNotificationWire.hash(identity)).toString()
        val result = NativeChatNotificationCycleEvidence.inspectLegacy(marker, identity, journal, runId)
        check(result.getBoolean("identityHashMatches") && result.getBoolean("originalHashMatches") && result.getBoolean("epochMatchesDeterministicFixture"))
        check(result.getLong("after") == 1L && result.getString("recordedResponseStage") == "applied")
        check(result.getString("recordedResponseHash") == NativeChatRecoveryCanonical.hash(checkNotNull(operation.receipt).json()))
        check(first.storage.read(NativeChatNotificationRecoveryStore.RECORD) == journal && first.storage.read(NativeChatNotificationIdentity.RECORD) == identity)
    }
    test("unknown local operation cannot manufacture an applied fixture response marker") {
        val runId = UUID.randomUUID().toString(); val first = RecoveryNativeFixture(NativeSessionStorage()); first.epoch = NativeChatNotificationCycleEvidence.epoch(runId); first.loseAck = true
        check(first.sync()?.state == "mutation-unknown-original-query")
        val operation = first.view().operations.single(); val before = first.storage.read(NativeChatNotificationRecoveryStore.RECORD)
        check(operation.receipt == null && operation.state == "unknown")
        check(runCatching { NativeChatNotificationCycleEvidence.capture(runId, UUID.randomUUID().toString(), 7, writerNonce, identityHash(first), operation, checkNotNull(first.receipts[operation.original.operationId])) }.isFailure)
        check(first.storage.read(NativeChatNotificationRecoveryStore.RECORD) == before)
    }
    fun files(action: (java.io.File) -> Unit) {
        val parent = java.nio.file.Files.createTempDirectory("p9-cycle-ownership-vector-").toFile()
        try { action(parent) } finally { java.nio.file.Files.walk(parent.toPath()).use { rows -> rows.sorted(java.util.Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) } } }
    }
    test("cycle writer refuses every existing directory or symlink before acquiring cleanup ownership") {
        files { parent ->
            val id = UUID.randomUUID().toString(); NativeChatNotificationCycleFiles.requireFresh(parent, id)
            val root = java.io.File(parent, "P9-native-system-$id"); root.mkdir()
            check(runCatching { NativeChatNotificationCycleFiles.requireFresh(parent, id) }.isFailure && root.isDirectory)
            root.delete(); java.nio.file.Files.createSymbolicLink(root.toPath(), java.io.File(parent, "missing-witness").toPath())
            check(runCatching { NativeChatNotificationCycleFiles.requireFresh(parent, id) }.isFailure && java.nio.file.Files.isSymbolicLink(root.toPath()))
        }
    }
    test("cycle reader physical preflight rejects extra partial and symlink files without changing originals") {
        files { parent ->
            val id = UUID.randomUUID().toString(); val root = java.io.File(parent, "P9-native-system-$id"); root.mkdir()
            val names = listOf(NativeChatNotificationStore.RECORD, NativeChatNotificationIdentity.RECORD, NativeChatNotificationRecoveryStore.RECORD, NativeChatNotificationCycleEvidence.RECORD)
            for(name in names)java.io.File(root, NativeChatNotificationWire.hash(name) + ".bin").writeBytes(ByteArray(48) { 3 })
            val original = NativeChatNotificationCycleFiles.existing(parent, id)
            val first = root.listFiles()!!.first(); val extra = java.io.File(first.path + ".bak"); extra.writeBytes(ByteArray(48) { 7 })
            check(runCatching { NativeChatNotificationCycleFiles.existing(parent, id) }.isFailure && extra.readBytes().contentEquals(ByteArray(48) { 7 }))
            extra.delete(); check(NativeChatNotificationCycleFiles.existing(parent, id) == original)
            val witness = java.io.File(parent, "witness"); witness.writeBytes(first.readBytes()); first.delete(); java.nio.file.Files.createSymbolicLink(first.toPath(), witness.toPath())
            check(runCatching { NativeChatNotificationCycleFiles.existing(parent, id) }.isFailure && witness.readBytes().contentEquals(ByteArray(48) { 3 }))
        }
    }
    test("cycle grant preflight parses the existing exact synthetic operation without identity or controller writes") {
        val (_, first) = writer(); val bytes = checkNotNull(first.storage.read(NativeChatNotificationRecoveryStore.RECORD))
        val operation = NativeChatNotificationCycleEvidence.recordedOperation(bytes)
        check(operation.original.encoded() == first.view().operations.single().original.encoded() && operation.state == "applied")
        check(NativeChatRecoveryCanonical.value(checkNotNull(operation.receipt).json()) == NativeChatRecoveryCanonical.value(checkNotNull(first.receipts[operation.original.operationId]).json()))
        check(first.storage.read(NativeChatNotificationRecoveryStore.RECORD) == bytes)
        check(runCatching { NativeChatNotificationCycleEvidence.recordedOperation(JSONObject(bytes).put("unknown", true).toString()) }.isFailure)
        check(first.storage.read(NativeChatNotificationRecoveryStore.RECORD) == bytes)
    }
    return passed
}
