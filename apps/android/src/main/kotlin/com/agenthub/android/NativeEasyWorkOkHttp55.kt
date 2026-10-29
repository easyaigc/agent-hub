@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.agenthub.android

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.internal.concurrent.TaskRunner
import java.util.concurrent.TimeUnit

/** The Gradle-locked OkHttp 5.5 adapter owns its socket/writer/connection task runner.
 * Listener return and executor.shutdown() alone do not prove that TLS/writer work exited.
 * Keep these version-specific internal APIs isolated here; a library upgrade must revalidate it.
 */
internal class NativeEasyWorkOkHttp55 {
    private val backend=TaskRunner.RealBackend { task->Thread(task,"easywork-socket-work").apply {isDaemon=true} }
    private val runner=TaskRunner(backend)
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .taskRunner(runner)
        .connectionPool(ConnectionPool(5,5,TimeUnit.MINUTES,runner))
    fun drain() {
        runner.cancelAll()
        // A shutdown queue can retain a non-cancellable writer close. Wait for real exit.
        while(true) {
            val queues=runner.activeQueues()
            if(queues.isEmpty())break
            queues.forEach {it.idleLatch().await()}
        }
        backend.shutdown()
        while(!backend.executor.awaitTermination(1,TimeUnit.SECONDS)) { /* actual threads, not isDone */ }
    }
}
