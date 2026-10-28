package com.agenthub.android

import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

private class WorkerCloseMain : MainCoroutineDispatcher(), java.io.Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var thread: Thread? = null
    override val immediate get() = this
    override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread
    override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute { thread = Thread.currentThread(); Looper.setMainForTest(true); block.run() } }
    override fun close() { executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS)) }
}
@OptIn(ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
    val selected = args.singleOrNull() ?: "all"; require(selected in setOf("all", "entry-reentry", "normal-close", "sync-return-retire"))
    val dispatcher = WorkerCloseMain(); Dispatchers.setMain(dispatcher)
    try { runBlocking {
        for(case in listOf("entry-reentry", "normal-close", "sync-return-retire").filter { selected == "all" || it == selected }) {
            val fixture = withContext(Dispatchers.IO) { NativeChatNotificationWorkerCloseFixture(NativeSessionStorage()) }
            val result = fixture.run(case); println("PASS $case $result")
        }
        println("Deterministic supplied-coordinator worker checks passed; JVM Main/storage seams, no native result")
    } } finally { Dispatchers.resetMain(); dispatcher.close() }
}
