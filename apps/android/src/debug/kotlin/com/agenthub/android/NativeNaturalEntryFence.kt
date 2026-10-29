package com.agenthub.android

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

/** The same narrow lifecycle decision is exercised by JVM counterexamples and the debug facade. */
internal object NativeNaturalEntryPolicy {
    enum class Route { ORDINARY, LEGACY_ACTIVE, PREPARE_NATURAL, REJECT_NATURAL_RECREATION }
    fun route(eligible: Boolean,active: Boolean,naturalMode: Boolean,material: ()->Boolean): Route {
        if(!eligible)return Route.ORDINARY
        if(active)return if(naturalMode)Route.REJECT_NATURAL_RECREATION else Route.LEGACY_ACTIVE
        return if(material())Route.PREPARE_NATURAL else Route.ORDINARY
    }
    fun blocksIngress(ready: Boolean,naturalOwner: Boolean)=!ready || naturalOwner
    fun canRetain(ready: Boolean,naturalOwner: Boolean)=ready && !naturalOwner
}

/** Shared by the proposal runtime and JVM crash/race checks. An incomplete entry is terminal. */
internal object NativeNaturalEntryFence {
    const val PERMIT_ROOT="P9-notification-natural-permit-v1"
    const val ENTRY_ROOT="P9-notification-natural-entry-v1"
    fun hasMaterial(parent: File)=listOf(PERMIT_ROOT,ENTRY_ROOT).any{!Files.notExists(File(parent,it).toPath(),NOFOLLOW_LINKS)}
    fun claim(parent: File,bytes: ByteArray,sync: (File)->Unit): File {
        require(bytes.size in 1..32768 && Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS))
        val root=File(parent,ENTRY_ROOT)
        require(Files.notExists(root.toPath(),NOFOLLOW_LINKS)){"Original natural entry attempt is inspection-only"}
        Files.createDirectory(root.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        sync(parent)
        FileChannel.open(File(root,"entry-intent.json").toPath(),setOf<java.nio.file.OpenOption>(CREATE_NEW,WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use{out->
            val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())check(out.write(buffer)>0);out.force(true)
        }
        sync(root);return root
    }
}
