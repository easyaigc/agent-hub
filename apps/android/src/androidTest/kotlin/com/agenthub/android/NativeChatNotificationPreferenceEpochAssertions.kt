package com.agenthub.android

import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual preference storage and shared presentation/configuration epochs. The holder's storage
 * monitor proves that update passed its early invalidation but has not loaded/committed yet.
 * Native supplies encrypted storage; the host runner's storage port is explicitly synthetic. */
internal object NativeChatNotificationPreferenceEpochAssertions {
    fun run(storage: NativeSessionStorage) {
        val target=NativeChatNoticeTarget("preference-host","workspace","runtime")
        val notices=NativeChatNotificationStore(storage)
        for(mode in listOf("disable","exclude-host","mute-kind","hide-preview")) {
            notices.update { NativeChatNotificationPreferences(enabled=true) }
            var presentation: (() -> Boolean)?=null;var configuration: (() -> Boolean)?=null
            val failure=AtomicReference<Throwable?>()
            val writer=Thread({
                try {
                    NativeChatNotificationStore(storage).update { latest -> when(mode) {
                        "disable" -> latest.copy(enabled=false)
                        "exclude-host" -> latest.copy(excludedHosts=setOf(target.hostId))
                        "mute-kind" -> latest.copy(kinds=emptySet())
                        else -> latest.copy(showThreadTitle=false)
                    } }
                }catch(error: Throwable) { failure.set(error) }
            },"notification-preference-commit-$mode")
            try {
                storage.snapshotTransaction {
                    writer.start()
                    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                    while(true) {
                        if(writer.state==Thread.State.BLOCKED && writer.stackTrace.any {
                            it.className==NativeSessionStorage::class.java.name && it.methodName.substringBefore('$')=="snapshotTransaction"
                        })break
                        failure.get()?.let { throw it }
                        check(writer.isAlive && System.nanoTime()<deadline) { "Update did not enter the owned storage queue" }
                        Thread.yield()
                    }
                    // A delivery may start after the early invalidation while the write is queued.
                    presentation=notices.presentationLease(target);configuration=notices.configurationLease()
                    assertTrue(checkNotNull(presentation).invoke());assertTrue(checkNotNull(configuration).invoke())
                }
            } finally {
                writer.join(5_000)
                check(!writer.isAlive) { "Owned preference writer exit unknown" }
            }
            failure.get()?.let { throw it }
            val saved=notices.preferences()
            when(mode) {
                "disable" -> assertFalse(saved.enabled)
                "exclude-host" -> assertTrue(target.hostId in saved.excludedHosts)
                "mute-kind" -> assertTrue(saved.kinds.isEmpty())
                else -> assertFalse(saved.showThreadTitle)
            }
            val stillPresent=checkNotNull(presentation).invoke();val stillConfigured=checkNotNull(configuration).invoke()
            println("OBSERVE preference-$mode committed=true queuedPresentationCurrent=$stillPresent queuedConfigurationCurrent=$stillConfigured")
            assertFalse("A committed preference must revoke deliveries captured during its wait",stillPresent)
            assertFalse("A committed preference must revoke monitors captured during its wait",stillConfigured)
            assertTrue(notices.presentationLease(target).invoke());assertTrue(notices.configurationLease().invoke())
            println("PASS preference-commit-$mode")
        }
    }
}
