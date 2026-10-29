package com.agenthub.android

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** Read-only, key-free mobile-v1* inventory. Existing limits stay 128 files/256 entries/depth8/32MiB.
 * Public proof contains counts/digests only; private paths are retained only for exact bootstrap checks. */
internal object NativeNaturalFamilyInventory {
    const val FILES = 128
    const val ENTRIES = 256
    const val DEPTH = 8
    const val BYTES = 32L * 1024 * 1024
    data class Report(val complete: Boolean, val reason: String?, val families: Set<String>, val filePaths: Set<String>,
        val directories: Int, val files: Int, val bytes: Long, val entries: Int, val rootEntries: Int,
        val maximumDepth: Int, val contentHashes: Boolean, val digest: String?) {
        val emptyRootsOnly get() = complete && files == 0 && entries == families.size && directories == families.size
        fun summary(): Map<String, Any?> = linkedMapOf("complete" to complete, "reason" to reason,
            "familyCount" to families.size, "directoryCount" to directories, "fileCountObserved" to files,
            "byteCountObserved" to bytes, "entriesObserved" to entries, "rootEntriesScanned" to rootEntries,
            "maximumDepth" to maximumDepth, "countsAreLowerBounds" to !complete,
            "kind" to if(contentHashes) "content-hash" else "counts-only", "familyDigest" to digest,
            "emptyFamilyRootsOnly" to emptyRootsOnly)
    }
    private class Stop(val code: String): Exception()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun scan(parent: File, contentHashes: Boolean): Report {
        val roots = mutableSetOf<String>(); val paths = mutableSetOf<String>(); val rows = mutableListOf<String>()
        var directories = 0; var files = 0; var bytes = 0L; var entries = 0; var rootEntries = 0; var maximumDepth = 0; var reason: String? = null
        fun visit(file: File, relative: String, depth: Int) {
            ++entries; maximumDepth = maxOf(maximumDepth, depth)
            if(entries > ENTRIES)throw Stop("entry-limit")
            if(depth > DEPTH)throw Stop("depth-limit")
            if(Files.isSymbolicLink(file.toPath()))throw Stop("symlink")
            if(Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
                ++directories; rows.add("d:${sha(relative.toByteArray())}")
                Files.newDirectoryStream(file.toPath()).use { stream -> for(child in stream)visit(child.toFile(), "$relative/${child.fileName}", depth + 1) }
            } else {
                if(!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))throw Stop("unsupported-entry")
                ++files; if(files > FILES)throw Stop("file-limit")
                val size = Files.size(file.toPath()); bytes += size; if(bytes > BYTES)throw Stop("byte-limit")
                paths.add(relative)
                val digest = if(contentHashes) {
                    val hash = MessageDigest.getInstance("SHA-256"); var observed = 0L
                    Files.newByteChannel(file.toPath(), setOf<java.nio.file.OpenOption>(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { input ->
                        val buffer = java.nio.ByteBuffer.allocate(65536)
                        while(true) { val n = input.read(buffer); if(n < 0)break; if(n == 0)throw Stop("unreadable")
                            observed += n; if(observed > size)throw Stop("changed-during-read"); hash.update(buffer.array(), 0, n); buffer.clear() }
                    }
                    if(observed != size || Files.size(file.toPath()) != size)throw Stop("changed-during-read")
                    hash.digest().joinToString("") { "%02x".format(it) }
                } else "counts-only"
                rows.add("f:${sha(relative.toByteArray())}:$size:$digest")
            }
        }
        try {
            if(!Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS) || Files.isSymbolicLink(parent.toPath()))throw Stop("invalid-parent")
            Files.newDirectoryStream(parent.toPath()).use { stream -> for(path in stream) {
                if(++rootEntries > ENTRIES)throw Stop("root-entry-limit")
                val name = path.fileName.toString(); if(!name.startsWith("mobile-v1"))continue
                roots.add(name)
                if(!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path))throw Stop("invalid-family-root")
                visit(path.toFile(), name, 0)
            } }
        } catch(error: Stop) { reason = error.code } catch(_: Exception) { reason = "unreadable" }
        return Report(reason == null, reason, roots.toSet(), paths.toSet(), directories, files, bytes, entries, rootEntries,
            maximumDepth, contentHashes, if(reason == null)sha(rows.sorted().joinToString("\n").toByteArray()) else null)
    }
}
