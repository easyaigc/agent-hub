package com.agenthub.android

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/** Actual JVM AES-GCM/files/fsync/cross-PID FileLock; key/installed/OS observations are synthetic. */
fun main(args: Array<String>) {
    val model = NativeChatNotificationNaturalPermit
    fun engine(root: File, lease: String, fault: NativeNaturalFault? = null, missing: Boolean = false, wrong: Boolean = false) = NativeChatNotificationNaturalFiles(root, lease,
        { check(!missing); SecretKeySpec(ByteArray(32) { if (wrong) 9 else 7 }, "AES") },
        { dir -> FileChannel.open(dir.toPath(), READ).use { it.force(true) } }, {}, fault)
    fun observation(p: NativeChatNotificationNaturalPermit.Original) = NativeChatNotificationNaturalPermit.Observation(p, "original", true, model.PACKAGE,
        true, true, p.deviceChallengeHash, true, true, true, true, true, true, false, 1, 0, false, p.bootCount,
        p.issuedWall + 200, p.issuedElapsed + 200, ProcessHandle.current().pid().toInt(), UUID.randomUUID().toString())
    if (args.isNotEmpty()) {
        require(args.size == 5 && args[0] == "claim-child")
        val root = File(args[1]); val lease = args[2]; val gate = File(args[3]); val ready = File(args[4]); check(ready.createNewFile())
        val end = System.nanoTime() + 10_000_000_000L; while (!gate.exists()) { check(System.nanoTime() < end); Thread.sleep(10) }
        try { val store = engine(root, lease); val p = store.inspect().original; store.claim(observation(p)); println("COMMITTED:${ProcessHandle.current().pid()}") }
        catch (_: NativeNaturalBusy) { println("REFUSED:busy") }
        catch (_: IllegalArgumentException) { println("REFUSED:already-claimed") }
        return
    }
    val base = File(".build/p9-natural-store-fixtures").apply { check(mkdirs() || isDirectory) }.canonicalFile
    var count = 0
    fun test(name: String, body: () -> Unit) { body(); count++; println("PASS $name") }
    fun hashes(root: File) = root.listFiles()?.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }?.associate { it.name to NativeChatNotificationNaturalFiles.sha(it.readBytes()) } ?: emptyMap()
    fun seed(fault: NativeNaturalFault? = null): Triple<File, NativeChatNotificationNaturalFiles, NativeChatNotificationNaturalPermit.Original> {
        val lease = UUID.randomUUID().toString(); val root = File(base, "fixture-$lease"); val challenge = ByteArray(32) { it.toByte() }
        val p = NativeChatNotificationNaturalPermit.Original(lease, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64), "5".repeat(64), "6".repeat(64), "7".repeat(64), NativeChatNotificationNaturalFiles.sha(challenge), "8".repeat(64), 10234, 8, 1800000000000, 1000, 901000, Int.MAX_VALUE - 1, UUID.randomUUID().toString())
        val store = engine(root, lease, fault); store.arm(p, challenge); return Triple(root, store, p)
    }
    test("authenticated-permit-claim-complete-and-original-bytes-retained") {
        val (root, store, p) = seed(); val before = hashes(root); check(store.inspect().original == p)
        val observation = observation(p); val claim = store.claim(observation).state; check(claim.consumer?.pid == observation.pid)
        check(before.all { (k, v) -> hashes(root)[k] == v })
        val result = NativeChatNotificationNaturalPermit.Completion(observation.pid, observation.nonce, observation.elapsed + 1, p.ticket, p.receiptHash, p.host, p.workspace, p.runtime, true, 1, 0, 0, 0, "c".repeat(64))
        check(store.complete(result).phase == NativeChatNotificationNaturalPermit.Phase.COMPLETE)
        val completed = hashes(root); check(runCatching { store.claim(observation) }.isFailure); check(runCatching { store.arm(p, ByteArray(32)) }.isFailure); check(hashes(root) == completed)
    }
    test("missing-key-creates-no-directory-or-lock") {
        val (root, _, p) = seed(); val missing = File(base, "missing-${p.lease}")
        check(runCatching { engine(missing, p.lease, missing = true).arm(p, ByteArray(32) { it.toByte() }) }.isFailure)
        check(!missing.exists() && root.exists())
    }
    test("wrong-key-refuses-without-changing-ciphertext") {
        val (root, _, p) = seed(); val before = hashes(root); check(runCatching { engine(root, p.lease, wrong = true).inspect() }.isFailure); check(hashes(root) == before)
    }
    test("interrupted-arm-is-never-rearmed") {
        val (_, _, template) = seed(); val lease = UUID.randomUUID().toString(); val root = File(base, "arm-$lease"); val p = template.copy(lease = lease)
        check(runCatching { engine(root, lease, NativeNaturalFault.AFTER_CHALLENGE).arm(p, ByteArray(32) { it.toByte() }) }.isFailure)
        val before = hashes(root); check(runCatching { engine(root, lease).inspect() }.isFailure)
        check(runCatching { engine(root, lease).arm(p, ByteArray(32) { it.toByte() }) }.isFailure); check(hashes(root) == before)
    }
    test("copied-ciphertext-cannot-cross-root-or-lease") {
        val (a, _, _) = seed(); val (b, store, _) = seed(); val original = hashes(a)
        File(b, NativeChatNotificationNaturalFiles.PERMIT).writeBytes(File(a, NativeChatNotificationNaturalFiles.PERMIT).readBytes())
        val copied = hashes(b); check(runCatching { store.inspect() }.isFailure); check(hashes(a) == original && hashes(b) == copied)
    }
    test("permission-denial-creates-no-claim") {
        val (root, store, p) = seed(); val before = hashes(root)
        check(runCatching { store.claim(observation(p).copy(currentCanPost = false)) }.isFailure); check(hashes(root) == before)
    }
    for (fault in listOf(NativeNaturalFault.AFTER_CLAIM_FENCE, NativeNaturalFault.AFTER_CLAIM_SYNC, NativeNaturalFault.BEFORE_CLAIM_READBACK)) test("interrupted-$fault-never-rearms") {
        val (root, store, p) = seed(fault); val before = hashes(root); check(runCatching { store.claim(observation(p)) }.isFailure)
        check(File(root, NativeChatNotificationNaturalFiles.CLAIM).exists()); check(before.all { (k, v) -> hashes(root)[k] == v })
        val after = hashes(root); val fresh = engine(root, p.lease); check(runCatching { fresh.claim(observation(p)) }.isFailure); check(hashes(root) == after)
        if (fault != NativeNaturalFault.AFTER_CLAIM_FENCE) check(fresh.inspect().phase == NativeChatNotificationNaturalPermit.Phase.CLAIMED) else check(runCatching { fresh.inspect() }.isFailure)
    }
    for (kind in listOf("unknown", "sidecar", "symlink", "hardlink", "tamper", "partial")) test("preserve-$kind-material") {
        val (root, store, p) = seed()
        when (kind) {
            "unknown" -> File(root, "unknown.bin").writeText("owned fault")
            "sidecar" -> File(root, "permit.bin.bak").writeText("owned fault")
            "symlink" -> { val target = File(base, "outside-${p.lease}"); Files.move(File(root, NativeChatNotificationNaturalFiles.PERMIT).toPath(), target.toPath()); Files.createSymbolicLink(File(root, NativeChatNotificationNaturalFiles.PERMIT).toPath(), target.toPath()) }
            "hardlink" -> Files.createLink(File(base, "hard-${p.lease}").toPath(), File(root, NativeChatNotificationNaturalFiles.PERMIT).toPath())
            "tamper" -> { val f = File(root, NativeChatNotificationNaturalFiles.PERMIT); val bytes = f.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); f.writeBytes(bytes) }
            else -> File(root, NativeChatNotificationNaturalFiles.PERMIT).writeBytes(byteArrayOf(1))
        }
        val before = hashes(root); check(runCatching { store.inspect() }.isFailure); check(runCatching { store.claim(observation(p)) }.isFailure); check(hashes(root) == before)
    }
    test("same-process-lock-is-not-bypassed") {
        val (root, store, p) = seed(); val before = hashes(root)
        store.holdFixtureLock().use { check(runCatching { engine(root, p.lease).claim(observation(p)) }.exceptionOrNull() is NativeNaturalBusy) }
        check(hashes(root) == before); store.claim(observation(p))
    }
    test("two-real-processes-have-one-committed-claim") {
        val (root, store, p) = seed(); val before = hashes(root); val gate = File(base, "gate-${p.lease}")
        val ready = (1..2).map { File(base, "ready-$it-${p.lease}") }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val children = ready.map { r -> ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.agenthub.android.NaturalStoreVectorsKt", "claim-child", root.path, p.lease, gate.path, r.path).redirectErrorStream(true).start() }
        val end = System.nanoTime() + 10_000_000_000L
        while (!ready.all { it.exists() }) { check(System.nanoTime() < end); Thread.sleep(10) }; check(gate.createNewFile())
        val outputs = children.map { child -> check(child.waitFor(15, TimeUnit.SECONDS)); val output = child.inputStream.bufferedReader().readText(); check(child.exitValue() == 0) { output }; output }
        check(outputs.count { "COMMITTED:" in it } == 1 && outputs.count { "REFUSED:" in it } == 1) { outputs.joinToString() }
        val consumer = checkNotNull(store.inspect().consumer); check(children.any { it.pid() == consumer.pid.toLong() }); check(before.all { (k, v) -> hashes(root)[k] == v })
        check(runCatching { store.claim(observation(p)) }.isFailure)
    }
    println("Natural durable store: $count passed; real JVM crypto/files/lock. Android KeyStore/native/OS NOT RUN")
}
