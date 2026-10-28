package com.agenthub.android

import com.agenthub.core.TargetScope
import org.json.JSONObject

/** Closed-domain and named-port auxiliary checks; no Android Activity, Keystore or process claim. */
suspend fun coldVectors(): Int {
    var count = 0
    suspend fun test(name: String, action: suspend () -> Unit) { action(); count++; println("PASS $name") }
    val lease = "17c625d2-30f1-4a9a-9236-2a7b17b36121"
    val base = NativeChatNotificationColdPlan(lease, NativeChatNotificationColdCase.ORIGINAL, "29783051-e88d-430c-8629-cd74b0b23214", 123, "4a868f6c-9091-4822-b0f0-b83ee4531aee", 1_793_180_000_000L,
        "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
    val target = TargetScope("codex", null, null, base.target.hostId, base.target.workspaceId, base.target.runtimeId)
    val client = MobileClient(base.target.hostId, "Fixture", target, base.endpoint, mode = "desktop-runtime", requiresReadTarget = true)
    val source = NativeChatNotificationSavedConnection.capture(client, base.token)
    val plan = base.copy(authority = source.snapshot.credentialEpoch)
    val event = NativeChatNotificationEvent("e".repeat(64), plan.target, NativeChatNoticeKind.COMPLETED, "2026-10-28T12:00:00Z", plan.originalThread)
    test("cold plan closed canonical roundtrip preserves all five named scenarios") {
        for (case in NativeChatNotificationColdCase.entries) { val value = plan.copy(case = case); check(NativeChatNotificationColdPlan.decode(value.json()) == value) }
    }
    test("cold plan rejects arbitrary namespace path transport and method fields") {
        for (key in listOf("namespace", "path", "transport", "method")) check(runCatching { NativeChatNotificationColdPlan.decode(JSONObject(plan.json().toString()).put(key, "ignored")) }.isFailure)
    }
    test("cold plan rejects noncanonical UUID and unknown scenario instead of choosing another lease") {
        for (raw in listOf(plan.json().put("lease", "1-1-1-1-1"), plan.json().put("ticket", "not-a-ticket"), plan.json().put("case", "arbitrary-route")))
            check(runCatching { NativeChatNotificationColdPlan.decode(raw) }.isFailure)
    }
    test("cold plan rejects malformed PID and source hash") {
        for (raw in listOf(plan.json().put("writerPid", 0), plan.json().put("writerPid", 1.5), plan.json().put("appHash", "x".repeat(64))))
            check(runCatching { NativeChatNotificationColdPlan.decode(raw) }.isFailure)
    }
    test("cold locator reads twelve actual named pages and returns original row") {
        val ports = NativeChatNotificationColdPorts(plan, false); val locator = NativeChatNotificationThreadLocator { ports.locatorReads(it) }
        val actual = locator.locate(source, event) { true }
        check(actual?.thread?.id == plan.originalThread && actual.workspace === ports.lastLocatorPage)
        check(ports.locatorCursors == listOf("first") + (2..12).map { "cold-page-$it" } && ports.forbiddenCommands.get() == 0)
    }
    test("cold missing original thread returns no invented row after complete named directory") {
        val ports = NativeChatNotificationColdPorts(plan.copy(case = NativeChatNotificationColdCase.MISSING_THREAD), false)
        check(NativeChatNotificationThreadLocator { ports.locatorReads(it) }.locate(source, event) { true } == null && ports.locatorCursors.size == 12)
    }
    test("cold named locator refuses replacement credentials before fetching metadata") {
        val ports = NativeChatNotificationColdPorts(plan, false); val changed = NativeChatNotificationSavedConnection.capture(client, "f".repeat(64))
        check(runCatching { ports.locatorReads(changed) }.isFailure && ports.locatorCursors.isEmpty())
    }
    test("cold named ports reject foreign runtime and unknown cursor") {
        val ports = NativeChatNotificationColdPorts(plan, false)
        val foreign = client.copy(scope = TargetScope("codex", null, null, target.hostId, target.workspaceId, "foreign"))
        check(runCatching { ports.sessionReads.workspace(foreign) }.isFailure)
        check(runCatching { ports.locatorReads(source).workspace(client, threadCursor = "cold-page-0002") }.isFailure)
        check(ports.locatorCursors.isEmpty())
    }
    test("cold fixed command facade rejects execution without a transport") {
        val ports = NativeChatNotificationColdPorts(plan, false); check(ports.commands.models(client).isEmpty())
        check(runCatching { ports.commands.send(client, ReplyIntent(client.id, plan.originalThread, lease, "fixture", emptyList(), "follow-up")) }.isFailure)
        check(ports.forbiddenCommands.get() == 1 && ports.notificationCommands.get() == 0)
    }
    fun files(action: (java.io.File) -> Unit) {
        val parent = java.nio.file.Files.createTempDirectory("p9-cold-router-vector-").toFile()
        try { action(parent) } finally { java.nio.file.Files.walk(parent.toPath()).use { rows -> rows.sorted(java.util.Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) } } }
    }
    fun marker(parent: java.io.File) = java.io.File(java.io.File(parent, NativeChatNotificationColdRouterFiles.NAMESPACE), NativeChatNotificationWire.hash(NativeChatNotificationColdRouterFiles.RECORD) + ".bin")
    test("cold router preflight leaves missing and empty namespaces untouched") {
        files { parent ->
            check(!NativeChatNotificationColdRouterFiles.hasReadableMarker(parent) && parent.listFiles()!!.isEmpty())
            val root = java.io.File(parent, NativeChatNotificationColdRouterFiles.NAMESPACE); root.mkdir()
            check(!NativeChatNotificationColdRouterFiles.hasReadableMarker(parent) && root.listFiles()!!.isEmpty())
        }
    }
    test("cold router accepts only the bounded original file without changing its bytes") {
        files { parent ->
            val original = marker(parent); original.parentFile.mkdirs(); val bytes = ByteArray(48) { 3 }; original.writeBytes(bytes)
            check(NativeChatNotificationColdRouterFiles.hasReadableMarker(parent) && original.readBytes().contentEquals(bytes))
            original.writeBytes(ByteArray(65_537)); check(runCatching { NativeChatNotificationColdRouterFiles.hasReadableMarker(parent) }.isFailure)
            check(original.length() == 65_537L)
        }
    }
    test("cold router rejects extra and AtomicFile sidecars without restoring or deleting originals") {
        for (suffix in listOf(".bak", ".new", ".foreign")) files { parent ->
            val original = marker(parent); original.parentFile.mkdirs(); original.writeBytes(ByteArray(48) { 1 })
            val other = java.io.File(original.path + suffix); other.writeBytes(ByteArray(64) { 2 })
            check(runCatching { NativeChatNotificationColdRouterFiles.hasReadableMarker(parent) }.isFailure)
            check(original.readBytes().contentEquals(ByteArray(48) { 1 }) && other.readBytes().contentEquals(ByteArray(64) { 2 }))
            original.delete(); check(runCatching { NativeChatNotificationColdRouterFiles.hasReadableMarker(parent) }.isFailure && other.exists())
        }
    }
    test("cold router rejects directory and marker symlinks while preserving their targets") {
        files { parent ->
            val target = java.io.File(parent, "owned-witness"); target.mkdir(); val root = java.io.File(parent, NativeChatNotificationColdRouterFiles.NAMESPACE)
            java.nio.file.Files.createSymbolicLink(root.toPath(), target.toPath())
            check(runCatching { NativeChatNotificationColdRouterFiles.hasReadableMarker(parent) }.isFailure && target.isDirectory)
            java.nio.file.Files.delete(root.toPath()); root.mkdir()
            val data = java.io.File(target, "witness"); data.writeBytes(ByteArray(48) { 7 })
            java.nio.file.Files.createSymbolicLink(marker(parent).toPath(), data.toPath())
            check(runCatching { NativeChatNotificationColdRouterFiles.hasReadableMarker(parent) }.isFailure && data.readBytes().contentEquals(ByteArray(48) { 7 }))
        }
    }
    val claimNow = plan.issuedAt
    val completion = event.copy(occurredAt = java.time.Instant.ofEpochMilli(claimNow).toString())
    test("enabled production defaults still reject completion claims without changing original bytes") {
        val storage = NativeSessionStorage(); val notices = NativeChatNotificationStore(storage)
        notices.update { it.copy(enabled = true) }; val lease = notices.bind(plan.target, plan.authority)
        check(NativeChatNoticeKind.COMPLETED !in notices.preferences().kinds && !notices.preferences().permits(completion))
        val before = storage.read(NativeChatNotificationStore.RECORD)
        check(notices.claim(completion, lease, claimNow) == null && storage.read(NativeChatNotificationStore.RECORD) == before)
        check(NativeChatNotificationPreferences().kinds == NativeChatNoticeKind.entries.take(7).toSet())
    }
    test("cold seed explicitly enables completion and claims once without fabricating an OS post") {
        val storage = NativeSessionStorage(); val notices = NativeChatNotificationStore(storage)
        notices.update(NativeChatNotificationColdPlan::seedPreferences); val lease = notices.bind(plan.target, plan.authority)
        check(notices.preferences().kinds == setOf(NativeChatNoticeKind.COMPLETED) && notices.preferences().permits(completion))
        val receipt = checkNotNull(notices.claim(completion, lease, claimNow))
        check(receipt.event == completion && receipt.state == "claimed" && !receipt.consumed && receipt.lease == lease)
        val before = storage.read(NativeChatNotificationStore.RECORD)
        check(notices.claim(completion, lease, claimNow) == null && storage.read(NativeChatNotificationStore.RECORD) == before)
        check(listOf(NativeChatNotificationIdentity.RECORD, NativeChatNotificationForegroundStore.RECORD, NativeChatNotificationRecoveryStore.RECORD).all { storage.read(it) == null })
        check(NativeChatNoticeKind.COMPLETED !in NativeChatNotificationPreferences().kinds)
    }
    test("explicit cold completion preference preserves expiry runtime credential and preference lease rejection") {
        val storage = NativeSessionStorage(); val notices = NativeChatNotificationStore(storage)
        notices.update(NativeChatNotificationColdPlan::seedPreferences); val lease = notices.bind(plan.target, plan.authority)
        val before = storage.read(NativeChatNotificationStore.RECORD)
        for(bad in listOf(completion.copy(target = plan.target.copy(runtimeId = "foreign")),
            completion.copy(occurredAt = java.time.Instant.ofEpochMilli(claimNow - NativeChatNotificationStore.TTL_MILLIS).toString()),
            completion.copy(occurredAt = java.time.Instant.ofEpochMilli(claimNow + 60_001).toString()))) {
            check(notices.claim(bad, lease, claimNow) == null && storage.read(NativeChatNotificationStore.RECORD) == before)
        }
        notices.bind(plan.target, "e".repeat(64)); val rotated = storage.read(NativeChatNotificationStore.RECORD)
        check(notices.claim(completion, lease, claimNow) == null && storage.read(NativeChatNotificationStore.RECORD) == rotated)
        val current = notices.bind(plan.target, plan.authority); notices.update(NativeChatNotificationColdPlan::seedPreferences)
        val revised = storage.read(NativeChatNotificationStore.RECORD)
        check(notices.claim(completion, current, claimNow) == null && storage.read(NativeChatNotificationStore.RECORD) == revised)
    }
    return count
}
