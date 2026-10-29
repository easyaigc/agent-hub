package com.agenthub.android

/** JVM auxiliary: all SDK/key/persistence observations below are explicit synthetic seams. */
fun main() {
    val model = NativeChatNotificationNaturalPermit
    val p = NativeChatNotificationNaturalPermit.Original("00000000-0000-4000-8000-000000000001",
        "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003",
        "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64), "5".repeat(64), "6".repeat(64),
        "7".repeat(64), "8".repeat(64), "9".repeat(64), 10234, 8, 1_800_000_000_000, 1000, 901000,
        450, "00000000-0000-4000-8000-000000000004")
    val armed = NativeChatNotificationNaturalPermit.State(p)
    val originalBytes = model.encode(armed)
    val observation = NativeChatNotificationNaturalPermit.Observation(p, "original", true, model.PACKAGE, true,
        true, p.deviceChallengeHash, true, true, true, true, true, true, false, 1, 0, false,
        p.bootCount, p.issuedWall + 100, p.issuedElapsed + 100, p.writerPid + 1,
        "00000000-0000-4000-8000-000000000099")
    var checks = 0
    fun test(name: String, body: () -> Unit) { body(); checks++; println("PASS $name") }
    fun rejected(name: String, body: () -> Unit) = test(name) {
        check(runCatching(body).isFailure) { "Expected refusal: $name" }
        check(model.encode(armed).contentEquals(originalBytes)) { "Rejected observation mutated the original" }
    }
    fun rejectObservation(name: String, value: NativeChatNotificationNaturalPermit.Observation) = rejected(name) { model.proposeClaim(armed, value) }
    fun replaceField(key: String, value: String) = originalBytes.toString(Charsets.UTF_8).lines().joinToString("\n") {
        if (it.startsWith("$key=")) "$key=$value" else it
    }.toByteArray()
    test("canonical-original-roundtrip") { check(model.decode(originalBytes) == armed) }
    rejected("extra-field") { model.decode(originalBytes + "endpoint=https://invalid\n".toByteArray()) }
    rejected("duplicate-field") { model.decode(originalBytes + "lease=${p.lease}\n".toByteArray()) }
    rejected("reordered-fields") { model.decode(originalBytes.toString(Charsets.UTF_8).replace("version=1\npackage=${model.PACKAGE}", "package=${model.PACKAGE}\nversion=1").toByteArray()) }
    rejected("unknown-version") { model.decode(replaceField("version", "2")) }
    rejected("noncanonical-integer") { model.decode(replaceField("uid", "010234")) }
    rejected("integer-overflow") { model.decode(replaceField("uid", "9223372036854775807")) }
    rejected("foreign-target") { model.decode(replaceField("runtime", "another-runtime")) }
    rejected("foreign-package") { model.decode(replaceField("package", "com.easyai.agenthub.android.debug")) }
    rejected("invalid-utf8") { model.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
    rejected("oversized-record") { model.decode(ByteArray(model.MAX_BYTES + 1)) }
    rejected("consumer-in-armed") { model.decode(replaceField("consumerPid", "700")) }
    for ((name, changed) in listOf(
        "lease" to p.copy(lease = "00000000-0000-4000-8000-000000000005"),
        "attempt" to p.copy(attempt = "00000000-0000-4000-8000-000000000006"),
        "ticket" to p.copy(ticket = "00000000-0000-4000-8000-000000000007"),
        "plan" to p.copy(planHash = "a".repeat(64)), "receipt" to p.copy(receiptHash = "a".repeat(64)),
        "authority" to p.copy(authority = "a".repeat(64)), "token" to p.copy(originalTokenHash = "a".repeat(64)),
        "app-apk" to p.copy(appHash = "a".repeat(64)), "runner-apk" to p.copy(runnerHash = "a".repeat(64)),
        "signer" to p.copy(signerSetHash = "a".repeat(64)), "uid" to p.copy(uid = 10235),
        "key-challenge" to p.copy(deviceChallengeHash = "a".repeat(64)), "published-os" to p.copy(publishedOsHash = "a".repeat(64)))) {
        rejectObservation("changed-$name", observation.copy(original = changed))
    }
    rejectObservation("non-debug", observation.copy(debug = false))
    rejectObservation("negative-cold-case-not-eligible", observation.copy(originalCase = "expired"))
    rejectObservation("original-writer-not-exited", observation.copy(writerExited = false))
    rejectObservation("device-key-authentication-failed", observation.copy(existingDeviceKeyAuthenticated = false))
    rejectObservation("device-challenge-mismatch", observation.copy(sealChallengeHash = "b".repeat(64)))
    rejectObservation("partial-or-sidecar-material", observation.copy(materialComplete = false))
    rejectObservation("old-or-unknown-router", observation.copy(routerArmed = false))
    rejectObservation("unknown-post", observation.copy(osPublished = false))
    rejectObservation("already-consumed-receipt", observation.copy(originalReceiptUnconsumed = false))
    rejectObservation("wrong-intent", observation.copy(strictOriginalTicket = false))
    rejectObservation("permission-denied", observation.copy(currentCanPost = false))
    rejectObservation("instrumented-receiver-not-natural", observation.copy(instrumentedReceiver = true))
    rejectObservation("second-activity", observation.copy(activityEntries = 2))
    rejectObservation("default-already-selected", observation.copy(defaultSelections = 1))
    rejectObservation("retained-session", observation.copy(retainedSession = true))
    rejectObservation("writer-pid-still-current", observation.copy(pid = p.writerPid))
    rejectObservation("writer-nonce-still-current", observation.copy(nonce = p.writerNonce))
    rejectObservation("reboot", observation.copy(bootCount = p.bootCount + 1))
    rejectObservation("monotonic-expiry", observation.copy(elapsed = p.expiresElapsed))
    rejectObservation("monotonic-rollback", observation.copy(elapsed = p.issuedElapsed - 1))
    rejectObservation("wall-rollback", observation.copy(wall = p.issuedWall - 1))
    rejectObservation("wall-forward-drift", observation.copy(wall = p.issuedWall + 61_000))
    val claimed = model.proposeClaim(armed, observation)
    test("fresh-consumer-claim-roundtrip") {
        check(model.decode(model.encode(claimed)) == claimed && claimed.original == p && claimed.consumer?.pid == observation.pid)
    }
    rejected("second-process-cannot-claim-existing-attempt") { model.proposeClaim(claimed, observation.copy(pid = observation.pid + 1)) }
    rejected("same-process-cannot-reclaim-after-unknown") { model.proposeClaim(model.decode(model.encode(claimed)), observation) }
    val result = NativeChatNotificationNaturalPermit.Completion(observation.pid, observation.nonce, observation.elapsed + 1,
        p.ticket, p.receiptHash, p.host, p.workspace, p.runtime, true, 1, 0, 0, 0, "c".repeat(64))
    rejected("another-consumer-cannot-complete") { model.proposeComplete(claimed, result.copy(pid = observation.pid + 1)) }
    rejected("another-nonce-cannot-complete") { model.proposeComplete(claimed, result.copy(nonce = p.writerNonce)) }
    rejected("another-target-cannot-complete") { model.proposeComplete(claimed, result.copy(runtime = "another-runtime")) }
    rejected("another-ticket-cannot-complete") { model.proposeComplete(claimed, result.copy(ticket = p.attempt)) }
    rejected("expired-consumer-cannot-complete") { model.proposeComplete(claimed, result.copy(elapsed = p.expiresElapsed)) }
    rejected("second-activity-cannot-complete") { model.proposeComplete(claimed, result.copy(activityEntries = 2)) }
    rejected("changed-receipt-cannot-complete") { model.proposeComplete(claimed, result.copy(normalizedReceiptHash = "d".repeat(64))) }
    rejected("unconsumed-cannot-complete") { model.proposeComplete(claimed, result.copy(receiptConsumed = false)) }
    rejected("business-command-cannot-complete") { model.proposeComplete(claimed, result.copy(businessCommands = 1)) }
    rejected("new-notification-command-cannot-complete") { model.proposeComplete(claimed, result.copy(notificationCommands = 1)) }
    rejected("default-store-cannot-complete") { model.proposeComplete(claimed, result.copy(defaultSelections = 1)) }
    val complete = model.proposeComplete(claimed, result)
    test("completed-original-is-retained") { check(model.decode(model.encode(complete)) == complete && complete.original == p) }
    rejected("permission-recovery-does-not-rearm-completed") { model.proposeClaim(complete, observation.copy(currentCanPost = true)) }
    rejected("complete-cannot-be-completed-again") { model.proposeComplete(complete, result) }
    println("Natural permit reducer: $checks passed; Android/KeyStore/durable claim/OS process creation NOT RUN")
}
