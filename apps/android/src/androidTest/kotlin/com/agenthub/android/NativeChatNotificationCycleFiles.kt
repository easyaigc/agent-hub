package com.agenthub.android

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** Closed permission-cycle file preflight; never creates storage, follows symlinks or recovers sidecars. */
internal object NativeChatNotificationCycleFiles {
    private fun root(parent: File, runId: String): File {
        require(UUID.fromString(runId).toString() == runId)
        return File(parent, "P9-native-system-$runId")
    }
    fun requireFresh(parent: File, runId: String) {
        require(Files.notExists(root(parent, runId).toPath(), LinkOption.NOFOLLOW_LINKS)) { "Fresh independent cycle directory required; original material preserved" }
    }
    fun existing(parent: File, runId: String): Map<String, String> {
        val root = root(parent, runId)
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == parent.canonicalFile) { "Existing original cycle directory required" }
        val rows = checkNotNull(root.listFiles())
        val expected = setOf(NativeChatNotificationStore.RECORD, NativeChatNotificationIdentity.RECORD,
            NativeChatNotificationRecoveryStore.RECORD, NativeChatNotificationCycleEvidence.RECORD).map { NativeChatNotificationWire.hash(it) + ".bin" }.toSet()
        require(rows.map { it.name }.toSet() == expected && rows.size == expected.size && rows.sumOf { it.length() } <= 1_048_576) { "Partial/unknown cycle material preserved" }
        require(rows.all { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() in 29..1_048_576 }) { "Partial/unknown cycle files preserved" }
        return rows.associate { it.name to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) } }
    }
}
