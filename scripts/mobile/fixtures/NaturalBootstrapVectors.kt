package com.agenthub.android

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runners.model.Statement

class BootstrapBeforeFailureVector {
    companion object { var bodyEntered = false; var emitted = 0; var seen: Throwable? = null }
    @get:Rule(order = 0) internal val evidence: TestRule = object: TestRule {
        override fun apply(base: Statement, description: Description) = object: Statement() {
            override fun evaluate() = NativeNaturalEvidenceBoundary.evaluate({ base.evaluate() }) { emitted++; seen = it }
        }
    }
    @get:Rule(order = 1) val timeout: TestRule = org.junit.rules.Timeout.seconds(5)
    @Before fun missingKeyPreflight() { throw IllegalStateException("synthetic missing existing key") }
    @Test fun storeBody() { bodyEntered = true }
}
class BootstrapInventoryFailureVector {
    companion object { lateinit var root: File; var bodyEntered = false; var emitted = 0; internal var report: NativeNaturalFamilyInventory.Report? = null }
    @get:Rule(order = 0) internal val evidence: TestRule = object: TestRule {
        override fun apply(base: Statement, description: Description) = object: Statement() {
            override fun evaluate() = NativeNaturalEvidenceBoundary.evaluate({ base.evaluate() }) { check(it != null); emitted++ }
        }
    }
    @Test fun inventoryBeforeStoreBody() { report = NativeNaturalFamilyInventory.scan(root, true); require(report!!.complete); bodyEntered = true }
}

/** Real local filesystem/fence checks. No NativeSessionStorage, Android key or device is invoked. */
fun main(args: Array<String>) {
    fun fence(root: File) = NativeNaturalBootstrapFence(root) { dir -> FileChannel.open(dir.toPath(), READ).use { it.force(true) } }
    if(args.isNotEmpty()) {
        require(args.size == 5 && args[0] == "once-child")
        val root = File(args[1]); val ready = File(args[2]); val gate = File(args[3]); val calls = File(args[4]); check(ready.createNewFile())
        val end = System.nanoTime() + 10_000_000_000L; while(!gate.exists()) { check(System.nanoTime() < end); Thread.sleep(10) }
        try { fence(root).initialize("explicit-intent".toByteArray()) { check(File(calls, ProcessHandle.current().pid().toString()).createNewFile()); "completed".toByteArray() }; println("INITIALIZED") }
        catch(_: IllegalArgumentException) { println("REFUSED") } catch(_: java.nio.file.FileAlreadyExistsException) { println("REFUSED") }
        return
    }
    val base = File(".build/p9-natural-bootstrap-fixtures").apply { check(mkdirs() || isDirectory) }.canonicalFile
    fun fresh() = File(base, UUID.randomUUID().toString()).apply { check(mkdir()) }
    var count = 0
    fun test(name: String, body: () -> Unit) { body(); ++count; println("PASS $name") }
    test("empty-family-inspection-creates-no-default") {
        val parent = fresh(); val report = NativeNaturalFamilyInventory.scan(parent, true)
        check(report.complete && report.emptyRootsOnly && report.families.isEmpty() && parent.list()!!.isEmpty())
    }
    test("empty-root-families-are-completely-enumerated") {
        val parent = fresh(); File(parent, "mobile-v1").mkdir(); File(parent, "mobile-v1-voice").mkdir(); File(parent, "unrelated").mkdir()
        val report = NativeNaturalFamilyInventory.scan(parent, true); check(report.complete && report.emptyRootsOnly && report.families.size == 2 && report.directories == 2)
    }
    test("side-family-data-rejects-empty-and-content-change-breaks-baseline") {
        val parent = fresh(); val family = File(parent, "mobile-v1-child").apply { mkdir() }; val file = File(family, "private-cached-title.bin").apply { writeText("PRIVATE_CACHED_CONTENT") }
        val first = NativeNaturalFamilyInventory.scan(parent, true); check(first.complete && !first.emptyRootsOnly)
        file.writeText("DIFFERENT_CACHED_TEXT"); val second = NativeNaturalFamilyInventory.scan(parent, true); check(first.digest != second.digest)
        val summary = first.summary().toString(); check("private-cached-title" !in summary && "PRIVATE_CACHED_CONTENT" !in summary && "mobile-v1-child" !in summary)
    }
    test("non-family-fixture-change-keeps-family-baseline") {
        val parent = fresh(); File(parent, "mobile-v1").mkdir(); val first = NativeNaturalFamilyInventory.scan(parent, true)
        File(parent, "P9-natural-store-fixture-owned").mkdir(); check(NativeNaturalFamilyInventory.scan(parent, true).digest == first.digest)
    }
    test("file129-remains-refused-with-counts-not-content") {
        val parent = fresh(); val family = File(parent, "mobile-v1").apply { mkdir() }; repeat(129) { File(family, "cached-$it").writeText("SECRET_$it") }
        val report = NativeNaturalFamilyInventory.scan(parent, true)
        check(!report.complete && report.reason == "file-limit" && report.files == 129 && report.digest == null && !report.emptyRootsOnly)
        check("SECRET_" !in report.summary().toString() && "cached-" !in report.summary().toString())
    }
    test("depth-and-root-entry-limits-stay-bounded") {
        val parent = fresh(); var dir = File(parent, "mobile-v1").apply { mkdir() }; repeat(9) { dir = File(dir, "d").apply { mkdir() } }
        check(NativeNaturalFamilyInventory.scan(parent, true).reason == "depth-limit")
        val wide = fresh(); repeat(257) { File(wide, "not-family-$it").mkdir() }; check(NativeNaturalFamilyInventory.scan(wide, true).reason == "root-entry-limit")
    }
    test("oversized-family-file-stops-before-content-read") {
        val parent = fresh(); val dir = File(parent, "mobile-v1").apply { mkdir() }; java.io.RandomAccessFile(File(dir, "sparse"), "rw").use { it.setLength(NativeNaturalFamilyInventory.BYTES + 1) }
        val report = NativeNaturalFamilyInventory.scan(parent, true); check(!report.complete && report.reason == "byte-limit" && report.bytes == NativeNaturalFamilyInventory.BYTES + 1)
    }
    test("family-symlink-never-follows-original") {
        val parent = fresh(); val outside = fresh(); val original = File(outside, "untouched").apply { writeText("KEEP") }
        Files.createSymbolicLink(File(parent, "mobile-v1-link").toPath(), outside.toPath())
        check(NativeNaturalFamilyInventory.scan(parent, true).reason == "invalid-family-root" && original.readText() == "KEEP")
    }
    test("fence-calls-normal-writer-once-and-readback-is-read-only") {
        val parent = fresh(); val root = File(parent, "bootstrap"); var calls = 0; val intent = "intent".toByteArray(); val result = "complete".toByteArray()
        fence(root).initialize(intent) { calls++; result }; val before = root.listFiles()!!.associate { it.name to it.readBytes().toList() }
        check(runCatching { fence(root).initialize(intent) { calls++; result } }.isFailure && calls == 1)
        val (a, b) = fence(root).readCompleted(); check(a.contentEquals(intent) && b.contentEquals(result))
        check(root.listFiles()!!.associate { it.name to it.readBytes().toList() } == before)
    }
    test("unknown-product-write-result-does-not-replay") {
        val parent = fresh(); val root = File(parent, "bootstrap"); var calls = 0
        check(runCatching { fence(root).initialize("intent".toByteArray()) { calls++; throw IllegalStateException("synthetic failure after attempted write") } }.isFailure)
        check(File(root, NativeNaturalBootstrapFence.INTENT).readText() == "intent" && !File(root, NativeNaturalBootstrapFence.COMPLETE).exists())
        check(runCatching { fence(root).initialize("new-intent".toByteArray()) { calls++; "new".toByteArray() } }.isFailure && calls == 1)
        check(runCatching { fence(root).readCompleted() }.isFailure)
    }
    test("partial-existing-bootstrap-is-never-repaired") {
        val parent = fresh(); val root = File(parent, "bootstrap").apply { mkdir() }; File(root, "partial").writeText("KEEP"); var called = false
        check(runCatching { fence(root).initialize("intent".toByteArray()) { called = true; "complete".toByteArray() } }.isFailure)
        check(!called && File(root, "partial").readText() == "KEEP")
    }
    test("two-real-PIDs-invoke-only-one-normal-writer") {
        val parent = fresh(); val root = File(parent, "bootstrap"); val gate = File(parent, "gate"); val calls = File(parent, "calls").apply { mkdir() }
        val ready = (1..2).map { File(parent, "ready$it") }; val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val processes = ready.map { ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.agenthub.android.NaturalBootstrapVectorsKt", "once-child", root.path, it.path, gate.path, calls.path).redirectErrorStream(true).start() }
        val end = System.nanoTime() + 10_000_000_000L; while(!ready.all { it.exists() }) { check(System.nanoTime() < end); Thread.sleep(10) }; gate.createNewFile()
        val output = processes.map { check(it.waitFor(15, TimeUnit.SECONDS)); val value = it.inputStream.bufferedReader().readText(); check(it.exitValue() == 0) { value }; value }
        check(output.count { "INITIALIZED" in it } == 1 && output.count { "REFUSED" in it } == 1 && calls.listFiles()!!.size == 1)
        println("PID-boundary parent=${ProcessHandle.current().pid()} contenders=${processes.map { it.pid() }} soleWriter=${calls.list()!!.single()}")
    }
    test("actual-JUnit-Before-failure-is-emitted-with-no-store-body") {
        BootstrapBeforeFailureVector.bodyEntered = false; BootstrapBeforeFailureVector.emitted = 0
        val result = JUnitCore.runClasses(BootstrapBeforeFailureVector::class.java)
        check(result.runCount == 1 && result.failureCount == 1 && !BootstrapBeforeFailureVector.bodyEntered && BootstrapBeforeFailureVector.emitted == 1)
        check(BootstrapBeforeFailureVector.seen is IllegalStateException)
    }
    test("actual-JUnit-inventory-failure-is-emitted-before-store-body") {
        val parent = fresh(); val family = File(parent, "mobile-v1").apply { mkdir() }; repeat(129) { File(family, "entry-$it").writeText("private") }
        BootstrapInventoryFailureVector.root = parent; BootstrapInventoryFailureVector.bodyEntered = false; BootstrapInventoryFailureVector.emitted = 0
        val result = JUnitCore.runClasses(BootstrapInventoryFailureVector::class.java)
        check(result.failureCount == 1 && !BootstrapInventoryFailureVector.bodyEntered && BootstrapInventoryFailureVector.emitted == 1)
        check(BootstrapInventoryFailureVector.report?.files == 129 && BootstrapInventoryFailureVector.report?.reason == "file-limit")
    }
    test("report-failure-does-not-hide-original-precondition-error") {
        val original = IllegalArgumentException("precondition"); val proof = IllegalStateException("proof")
        val result = runCatching { NativeNaturalEvidenceBoundary.evaluate({ throw original }) { throw proof } }.exceptionOrNull()
        check(result === original && original.suppressed.single() === proof)
    }
    println("Natural bootstrap preparation: $count passed; real filesystem/fence/PIDs; product storage/Android KeyStore/native NOT RUN")
}
