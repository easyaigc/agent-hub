package com.agenthub.android

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions

/** Test preparation only: one immutable intent, one normal-write callback, one completion. Never replay.
 * A fresh directory is the global fence. No key, normal storage or permit operation exists here. */
internal class NativeNaturalBootstrapFence(private val root: File, private val sync: (File) -> Unit) {
    companion object { const val LOCK = "owner.lock"; const val INTENT = "write-intent.json"; const val COMPLETE = "completed.json" }
    private fun file(name: String): File {
        val path = File(root, name).toPath()
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------"))
        require((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L)
        return path.toFile()
    }
    private fun directory() { require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(root.toPath()) && root.parentFile.canonicalFile == root.parentFile.absoluteFile)
        require(Files.getPosixFilePermissions(root.toPath(), NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------")) }
    private fun append(name: String, bytes: ByteArray) {
        require(bytes.size in 1..32768)
        FileChannel.open(File(root, name).toPath(), setOf<java.nio.file.OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { out ->
            sync(root); val buffer = ByteBuffer.wrap(bytes); while(buffer.hasRemaining())check(out.write(buffer) > 0)
            out.force(true); sync(root)
        }
        require(read(name).contentEquals(bytes))
    }
    private fun read(name: String): ByteArray {
        val path = file(name).toPath(); val size = Files.size(path); require(size in 1..32768)
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == size); val buffer = ByteBuffer.allocate(size.toInt()); while(buffer.hasRemaining())check(channel.read(buffer) > 0)
            require(channel.read(ByteBuffer.allocate(1)) == -1 && Files.size(path) == size); buffer.array()
        }
    }
    fun initialize(intent: ByteArray, normalWriteOnce: () -> ByteArray): ByteArray {
        require(root.parentFile.canonicalFile == root.parentFile.absoluteFile)
        require(Files.notExists(root.toPath(), NOFOLLOW_LINKS)) { "Bootstrap already attempted; inspect the immutable original receipt" }
        Files.createDirectory(root.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        FileChannel.open(File(root, LOCK).toPath(), setOf<java.nio.file.OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { it.force(true) }
        sync(root); sync(root.parentFile)
        return FileChannel.open(file(LOCK).toPath(), READ, WRITE, NOFOLLOW_LINKS).use { channel ->
            checkNotNull(channel.tryLock()).use {
                require(checkNotNull(root.list()).toSet() == setOf(LOCK)); append(INTENT, intent)
                val completed = normalWriteOnce(); append(COMPLETE, completed); completed
            }
        }
    }
    fun readCompleted(): Pair<ByteArray, ByteArray> {
        directory(); require(checkNotNull(root.list()).toSet() == setOf(LOCK, INTENT, COMPLETE)); require(file(LOCK).length() == 0L)
        return FileChannel.open(file(LOCK).toPath(), READ, WRITE, NOFOLLOW_LINKS).use { channel -> checkNotNull(channel.tryLock()).use { read(INTENT) to read(COMPLETE) } }
    }
}
