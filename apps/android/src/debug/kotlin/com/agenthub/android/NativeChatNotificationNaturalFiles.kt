package com.agenthub.android

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class NativeNaturalFault { AFTER_CHALLENGE, AFTER_CLAIM_FENCE, AFTER_CLAIM_SYNC, BEFORE_CLAIM_READBACK }
internal class NativeNaturalBusy : IllegalStateException("Natural permit lock is owned; original preserved")

/** Fixed append-only records. No reset/rearm/delete/AtomicFile recovery path. Android factory supplies
 * existing-key-only access, directory fsync and installed binding validation; JVM uses explicit seams. */
internal class NativeChatNotificationNaturalFiles internal constructor(
    private val root: File, private val lease: String, private val key: () -> SecretKey,
    private val syncDirectory: (File) -> Unit, private val validateOriginal: (NativeChatNotificationNaturalPermit.Original) -> Unit,
    private val fault: NativeNaturalFault? = null,
) {
    companion object {
        const val LOCK = "owner.lock"
        const val CHALLENGE = "device-challenge.bin"
        const val PERMIT = "permit.bin"
        const val CLAIM = "claim.bin"
        const val COMPLETE = "complete.bin"
        private val allowed = setOf(LOCK, CHALLENGE, PERMIT, CLAIM, COMPLETE)
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    class CommittedClaim private constructor(val state: NativeChatNotificationNaturalPermit.State) {
        companion object { internal fun verified(state: NativeChatNotificationNaturalPermit.State) = CommittedClaim(state) }
    }
    init { require(java.util.UUID.fromString(lease).toString() == lease); require(root.parentFile.canonicalFile == root.parentFile.absoluteFile) }
    private fun failAt(point: NativeNaturalFault) { check(fault != point) { "Injected $point; preserve original attempt" } }
    private fun directory() {
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(root.toPath()))
        require(Files.getPosixFilePermissions(root.toPath(), NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
    }
    private fun file(name: String, maximum: Long = 9000): File {
        val f = File(root, name); require(name in allowed)
        require(Files.isRegularFile(f.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(f.toPath()))
        require(Files.getPosixFilePermissions(f.toPath(), NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------"))
        require((Files.getAttribute(f.toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L)
        require(Files.size(f.toPath()) in 0..maximum)
        return f
    }
    private fun inventory(): Set<String> {
        directory(); val names = checkNotNull(root.list()).toSet()
        require(names.all { it in allowed } && setOf(LOCK, CHALLENGE, PERMIT).all { it in names }) { "Partial/unknown natural permit material preserved" }
        names.forEach { file(it) }; require(file(LOCK).length() == 0L)
        require(COMPLETE !in names || CLAIM in names)
        return names
    }
    private fun openLock(): Closeable {
        directory(); val f = file(LOCK); require(f.length() == 0L)
        val channel = FileChannel.open(f.toPath(), READ, WRITE, NOFOLLOW_LINKS)
        val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null } catch (error: Throwable) { channel.close(); throw error }
        if (lock == null) { channel.close(); throw NativeNaturalBusy() }
        return Closeable { try { lock.release() } finally { channel.close() } }
    }
    /** Test-only Android fixture factory is the only native caller that exposes this handle. */
    internal fun holdFixtureLock(): Closeable { inventory(); return openLock().also { try { inventory() } catch (e: Throwable) { it.close(); throw e } } }
    private fun <T> locked(body: () -> T): T { inventory(); return openLock().use { inventory(); body() } }
    private fun aad(role: String) = "agent-hub/p9-natural-permit/v1\u0000${root.name}\u0000$lease\u0000$role".toByteArray(Charsets.UTF_8)
    private fun seal(role: String, clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad(role))
        require(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(clear)
    }
    private fun read(role: String): ByteArray {
        val f = file(role); val before = Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.size() in 29..9000)
        val bytes = FileChannel.open(f.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == before.size()); val out = ByteBuffer.allocate(before.size().toInt())
            while (out.hasRemaining()) { check(channel.read(out) > 0) }; require(channel.read(ByteBuffer.allocate(1)) == -1); out.array()
        }
        val after = Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime())
        require(bytes[0] == 1.toByte()); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13))); cipher.updateAAD(aad(role))
        return cipher.doFinal(bytes, 13, bytes.size - 13)
    }
    private fun append(role: String, bytes: ByteArray) {
        require(role in allowed && bytes.size <= 9000)
        val f = File(root, role)
        FileChannel.open(f.toPath(), setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            // Persist existence BEFORE content: even an interrupted/empty claim is a non-replay fence.
            syncDirectory(root)
            if (role == CLAIM) failAt(NativeNaturalFault.AFTER_CLAIM_FENCE)
            val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) { check(channel.write(buffer) > 0) }
            channel.force(true); syncDirectory(root)
            if (role == CLAIM) failAt(NativeNaturalFault.AFTER_CLAIM_SYNC)
        }
    }
    fun arm(original: NativeChatNotificationNaturalPermit.Original, challenge: ByteArray): NativeChatNotificationNaturalPermit.State {
        val state = NativeChatNotificationNaturalPermit.State(original); val clear = NativeChatNotificationNaturalPermit.encode(state)
        require(original.lease == lease && challenge.size == 32 && sha(challenge) == original.deviceChallengeHash)
        validateOriginal(original); key() // Must fail before even creating a root/lock when key is missing.
        require(Files.notExists(root.toPath(), NOFOLLOW_LINKS)) { "Existing lease material is inspection-only; never rearm" }
        Files.createDirectory(root.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        FileChannel.open(File(root, LOCK).toPath(), setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { it.force(true) }
        syncDirectory(root); syncDirectory(root.parentFile)
        return openLock().use {
            require(checkNotNull(root.list()).toSet() == setOf(LOCK))
            append(CHALLENGE, seal(CHALLENGE, challenge)); failAt(NativeNaturalFault.AFTER_CHALLENGE)
            append(PERMIT, seal(PERMIT, clear)); inspectLocked().also { require(it == state) }
        }
    }
    private fun inspectLocked(): NativeChatNotificationNaturalPermit.State {
        val names = inventory()
        val original = NativeChatNotificationNaturalPermit.decode(read(PERMIT)); require(original.phase == NativeChatNotificationNaturalPermit.Phase.ARMED && original.original.lease == lease)
        validateOriginal(original.original)
        val challenge = read(CHALLENGE)
        try { require(challenge.size == 32 && sha(challenge) == original.original.deviceChallengeHash) } finally { challenge.fill(0) }
        var state = original
        if (CLAIM in names) {
            state = NativeChatNotificationNaturalPermit.decode(read(CLAIM))
            require(state.original == original.original && state.phase == NativeChatNotificationNaturalPermit.Phase.CLAIMED)
        }
        if (COMPLETE in names) {
            val completed = NativeChatNotificationNaturalPermit.decode(read(COMPLETE))
            require(completed.original == original.original && completed.consumer == state.consumer && completed.phase == NativeChatNotificationNaturalPermit.Phase.COMPLETE)
            state = completed
        }
        return state
    }
    fun inspect(): NativeChatNotificationNaturalPermit.State = locked { inspectLocked() }
    fun claim(observed: NativeChatNotificationNaturalPermit.Observation): CommittedClaim = locked {
        val state = inspectLocked() // Authenticate existing permit/challenge, not just caller's Boolean.
        val proposed = NativeChatNotificationNaturalPermit.proposeClaim(state, observed)
        append(CLAIM, seal(CLAIM, NativeChatNotificationNaturalPermit.encode(proposed)))
        failAt(NativeNaturalFault.BEFORE_CLAIM_READBACK)
        val persisted = inspectLocked(); require(persisted == proposed)
        CommittedClaim.verified(persisted)
    }
    fun complete(result: NativeChatNotificationNaturalPermit.Completion): NativeChatNotificationNaturalPermit.State = locked {
        val proposed = NativeChatNotificationNaturalPermit.proposeComplete(inspectLocked(), result)
        append(COMPLETE, seal(COMPLETE, NativeChatNotificationNaturalPermit.encode(proposed)))
        inspectLocked().also { require(it == proposed) }
    }
}
