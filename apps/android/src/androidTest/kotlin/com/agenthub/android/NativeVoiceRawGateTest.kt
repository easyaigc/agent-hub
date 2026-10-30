package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual production gate and host FileChannel locks; auxiliary, not Android microphone evidence. */
class NativeVoiceRawGateTest {
    private fun fixture(block: (File,NativeVoiceRawGate)->Unit) {
        val dir=Files.createTempDirectory("p7-raw-gate-").toFile();val file=File(dir,"coordination.lock")
        val gate=NativeVoiceRawGate {require(it.isFile&&it.length()==0L&&!Files.isSymbolicLink(it.toPath()))}
        try {block(file,gate)}finally {if(file.exists())check(file.delete());check(dir.list()!!.isEmpty());check(dir.delete())}
    }
    @Test fun activeProductionWritersExcludeFreezeUntilEveryWriterReallyCloses()=fixture {file,gate->
        val owner=Any();val a=gate.writer(file,"a");val b=gate.writer(file,"b")
        try {
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,owner,"one")}
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.writer(file,"a")}
            a.close();assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,owner,"two")};b.requireCurrent()
        }finally {a.close();b.close()}
        val frozen=gate.freeze(file,owner,"three")
        try {
            frozen.requireCurrent(owner,"three",file)
            assertThrows(IllegalStateException::class.java){frozen.requireCurrent(owner,"three",File(file.parentFile,"different.lock"))}
            val forged=NativeVoiceRawGate.Frozen(gate,file.canonicalPath,Any(),owner,"three")
            assertThrows(IllegalStateException::class.java){forged.requireCurrent(owner,"three")};forged.close();frozen.requireCurrent(owner,"three")
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.writer(file,"new")}
            assertThrows(IllegalStateException::class.java){frozen.requireCurrent(Any(),"three")}
            assertThrows(IllegalStateException::class.java){frozen.requireCurrent(owner,"different")}
        }finally {frozen.close()}
        val later=gate.freeze(file,owner,"four");frozen.close();later.requireCurrent(owner,"four");later.close()
        assertThrows(IllegalStateException::class.java){frozen.requireCurrent(owner,"three")}
        gate.writer(file,"new").use {it.requireCurrent()}
    }
    @Test fun kernelLockAndInvalidControlFileCannotBeBypassedByAnotherGate()=fixture {file,gate->
        val other=NativeVoiceRawGate {require(it.length()==0L)}
        gate.writer(file,"worker").use {
            assertThrows(NativeVoiceRawGate.Busy::class.java){other.freeze(file,Any(),"attempt")}
        }
        other.freeze(file,Any(),"held").use {
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.writer(file,"worker")}
        }
        file.writeBytes(byteArrayOf(3))
        assertThrows(IllegalArgumentException::class.java){gate.freeze(file,Any(),"invalid")}
        assertArrayEquals(byteArrayOf(3),file.readBytes());file.writeBytes(byteArrayOf())
        gate.freeze(file,Any(),"recovered").close()
    }
    @Test fun concurrentWriterAndFreezeNeverOverlapAndFailedFreezeDoesNotStopWriter()=fixture {file,gate->
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val progress=AtomicInteger();val failure=AtomicReference<Throwable?>()
        val thread=Thread {
            try {gate.writer(file,"actual-worker").use {writer->
                entered.countDown()
                while(!release.await(1,TimeUnit.MILLISECONDS)){writer.requireCurrent();progress.incrementAndGet()}
            }}catch(error: Throwable){failure.set(error)}finally{finished.countDown()}
        }
        thread.start()
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,Any(),"rejected")}
            val before=progress.get();val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(progress.get()<=before&&System.nanoTime()<deadline)Thread.yield()
            assertTrue(progress.get()>before);assertFalse(finished.await(1,TimeUnit.MILLISECONDS))
        }finally{release.countDown();thread.join(5_000)}
        assertFalse(thread.isAlive);assertNull(failure.get());gate.freeze(file,Any(),"after-worker").close()
    }
}
