package com.agenthub.android

import java.io.File
import java.nio.file.Files

/** Preflight before NativeSessionStorage can create directories or restore AtomicFile sidecars. */
internal object NativeChatNotificationColdRouterFiles {
    const val NAMESPACE = "P9-notification-cold-router-v1"
    const val RECORD = "armed-original-v1"
    private val filename = NativeChatNotificationWire.hash(RECORD) + ".bin"
    fun hasReadableMarker(parent: File): Boolean {
        val root = File(parent, NAMESPACE)
        if(Files.notExists(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))return false
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == parent.canonicalFile) { "Unknown cold router directory preserved" }
        val rows = checkNotNull(root.listFiles()) { "Unreadable cold router preserved" }
        if(rows.isEmpty())return false
        require(rows.size == 1) { "Partial/unknown cold router material preserved" }
        val file = rows.single()
        require(file.name == filename && file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 29..65_536) { "Partial/unknown cold router material preserved" }
        return true
    }
}
