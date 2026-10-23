package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Independent repositories for the same namespace must not roll back atomic writes. */
@RunWith(AndroidJUnit4::class)
class NativeStorageConcurrencyTest {
    @get:Rule val limit=Timeout.seconds(120)
    @Test fun concurrentInstancesKeepEncryptedSnapshotsIntactAndLatest() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val namespace="storage-concurrency-"+UUID.randomUUID()
        val writer=NativeSessionStorage(context,namespace)
        val reader=NativeSessionStorage(context,namespace)
        val payload="boundary-content-".repeat(3072)
        val key="metadata";writer.write(key,"initial")
        val gate=CountDownLatch(1);val finished=AtomicBoolean(false);val observedWhileWriting=AtomicBoolean(false)
        val reads=AtomicInteger();val missing=AtomicInteger();val errors=ConcurrentLinkedQueue<String>()
        val workers=Executors.newFixedThreadPool(2)
        try {
            val writing=workers.submit {
                gate.await()
                try { for(index in 1..300) writer.write(key,"$index:$payload") }
                finally { finished.set(true) }
            }
            val reading=workers.submit {
                gate.await()
                while(!finished.get()||reads.get()<30) {
                    try { if(reader.read(key)==null)missing.incrementAndGet() }
                    catch(error: Exception) { if(errors.size<10)errors.add(error.javaClass.name) }
                    reads.incrementAndGet()
                    if(!finished.get())observedWhileWriting.set(true)
                    // Poll more often than the UI examiner, without a CPU busy loop.
                    Thread.sleep(5)
                }
            }
            gate.countDown();writing.get(90,TimeUnit.SECONDS);reading.get(90,TimeUnit.SECONDS)
            assertTrue("Actual concurrent reads must complete",reads.get()>=30)
            assertTrue("Read and write workloads must actually overlap",observedWhileWriting.get())
            assertEquals("Existing encrypted snapshot must not disappear during writes",0,missing.get())
            assertTrue("Concurrent snapshots must remain authenticated: $errors",errors.isEmpty())
            assertTrue("Independent reader must retain the final successful write",reader.read(key)=="300:$payload")
        } finally {
            finished.set(true);gate.countDown();workers.shutdownNow()
            workers.awaitTermination(5,TimeUnit.SECONDS)
            File(context.noBackupFilesDir,namespace).deleteRecursively()
        }
    }
}
