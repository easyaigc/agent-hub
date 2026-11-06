package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production gate and actual FileChannel locks with controlled close failures.
 * Microphone, Host and full UI acceptance have separate scenarios. */
class NativeVoiceRawGateTest {
    private fun fixture(createGate: ()->NativeVoiceRawGate = {
        NativeVoiceRawGate {require(it.isFile&&it.length()==0L&&!Files.isSymbolicLink(it.toPath()))}
    },block: (File,NativeVoiceRawGate)->Unit) {
        val dir=Files.createTempDirectory("p7-raw-gate-").toFile();val file=File(dir,"coordination.lock")
        val gate=createGate()
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

    @Test fun failedFrozenReleaseRetainsFirstErrorAndBlocksEveryNewOwner() {
        val releaseFailure=IOException("original lock release failed")
        val closeFailure=IOException("original channel close failed")
        val releases=AtomicInteger();val closes=AtomicInteger()
        fixture({NativeVoiceRawGate(releaseLock={releases.incrementAndGet();throw releaseFailure},
            closeChannel={closes.incrementAndGet();it.close();throw closeFailure},validate={require(it.length()==0L)})}) {file,gate->
            val owner=Any();val frozen=gate.freeze(file,owner,"failed")
            assertSame(releaseFailure,assertThrows(IOException::class.java){frozen.close()})
            assertArrayEquals(arrayOf(closeFailure),releaseFailure.suppressed)
            repeat(2) {
                assertSame(releaseFailure,assertThrows(IOException::class.java){frozen.close()})
                assertSame(releaseFailure,assertThrows(IOException::class.java){frozen.requireCurrent(owner,"failed")})
                assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,Any(),"new")}
                assertThrows(NativeVoiceRawGate.Busy::class.java){gate.writer(file,"voice")}
                assertThrows(NativeVoiceRawGate.Busy::class.java){gate.computerFileWriter(file,"file")}
            }
            assertEquals(1,releases.get());assertEquals(1,closes.get())
            assertEquals(1,releaseFailure.suppressed.size)
            assertArrayEquals(byteArrayOf(),file.readBytes())
        }
    }

    @Test fun failedChannelCloseRemainsUnknownAfterTheActualLockWasReleased() {
        val failure=IOException("channel close receipt lost")
        val releases=AtomicInteger();val closes=AtomicInteger()
        fixture({NativeVoiceRawGate(releaseLock={releases.incrementAndGet();it.release()},
            closeChannel={closes.incrementAndGet();it.close();throw failure},validate={require(it.length()==0L)})}) {file,gate->
            val owner=Any();val frozen=gate.freeze(file,owner,"failed-channel")
            assertSame(failure,assertThrows(IOException::class.java){frozen.close()})
            assertSame(failure,assertThrows(IOException::class.java){frozen.close()})
            assertSame(failure,assertThrows(IOException::class.java){frozen.requireCurrent(owner,"failed-channel")})
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,Any(),"new")}
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.writer(file,"voice")}
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.computerFileWriter(file,"file")}
            assertEquals(1,releases.get());assertEquals(1,closes.get())
            assertArrayEquals(byteArrayOf(),file.readBytes())
        }
    }

    @Test fun concurrentFrozenCloseCallsObserveTheSameSingleAttempt() {
        val entered=CountDownLatch(1);val proceed=CountDownLatch(1);val secondStarted=CountDownLatch(1)
        val failure=IOException("original release is unknown")
        val closes=AtomicInteger();val firstResult=AtomicReference<Throwable?>();val secondResult=AtomicReference<Throwable?>()
        fixture({NativeVoiceRawGate(closeChannel={
            closes.incrementAndGet();it.close();entered.countDown()
            check(proceed.await(5,TimeUnit.SECONDS));throw failure
        },validate={require(it.length()==0L)})}) {file,gate->
            val owner=Any();val frozen=gate.freeze(file,owner,"concurrent-close")
            val first=Thread {try{frozen.close()}catch(error: Throwable){firstResult.set(error)}}
            val second=Thread {secondStarted.countDown();try{frozen.close()}catch(error: Throwable){secondResult.set(error)}}
            first.start()
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));second.start()
                assertTrue(secondStarted.await(5,TimeUnit.SECONDS))
            }finally {proceed.countDown();first.join(5_000);second.join(5_000)}
            assertFalse(first.isAlive);assertFalse(second.isAlive)
            assertSame(failure,firstResult.get());assertSame(failure,secondResult.get());assertEquals(1,closes.get())
            assertSame(failure,assertThrows(IOException::class.java){frozen.close()})
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,Any(),"new")}
            assertArrayEquals(byteArrayOf(),file.readBytes())
        }
    }

    @Test fun lastFileWriterKeepsTheSharedOriginalHandleAfterCloseFailure() {
        val failure=IOException("shared lock release failed")
        val secondary=IOException("shared channel close receipt lost")
        val releases=AtomicInteger();val closes=AtomicInteger()
        fixture({NativeVoiceRawGate(releaseLock={releases.incrementAndGet();throw failure},
            closeChannel={closes.incrementAndGet();it.close();throw secondary},validate={require(it.length()==0L)})}) {file,gate->
            val voice=gate.writer(file,"voice")
            val transfer=gate.computerFileWriter(file,"transfer")
            voice.close();voice.close();transfer.requireCurrent()
            assertEquals(0,releases.get());assertEquals(0,closes.get())
            assertThrows(IllegalStateException::class.java){voice.requireCurrent()}
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,Any(),"still-writing")}
            assertSame(failure,assertThrows(IOException::class.java){transfer.close()})
            repeat(2) {
                voice.close()
                assertSame(failure,assertThrows(IOException::class.java){transfer.close()})
                assertSame(failure,assertThrows(IOException::class.java){transfer.requireCurrent()})
                assertThrows(NativeVoiceRawGate.Busy::class.java){gate.writer(file,"new-voice")}
                assertThrows(NativeVoiceRawGate.Busy::class.java){gate.computerFileWriter(file,"new-transfer")}
                assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,Any(),"new-freeze")}
            }
            assertEquals(1,releases.get());assertEquals(1,closes.get())
            assertArrayEquals(arrayOf(secondary),failure.suppressed)
            assertArrayEquals(byteArrayOf(),file.readBytes())
        }
    }

    @Test fun lastVoiceWriterCannotConfirmAnUnknownChannelCloseByTryingAgain() {
        val failure=IOException("voice channel close receipt lost")
        val releases=AtomicInteger();val closes=AtomicInteger()
        fixture({NativeVoiceRawGate(releaseLock={releases.incrementAndGet();it.release()},
            closeChannel={closes.incrementAndGet();it.close();throw failure},validate={require(it.length()==0L)})}) {file,gate->
            val writer=gate.writer(file,"voice")
            assertSame(failure,assertThrows(IOException::class.java){writer.close()})
            assertSame(failure,assertThrows(IOException::class.java){writer.close()})
            assertSame(failure,assertThrows(IOException::class.java){writer.requireCurrent()})
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.writer(file,"another-voice")}
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.computerFileWriter(file,"another-transfer")}
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,Any(),"migration")}
            assertEquals(1,releases.get());assertEquals(1,closes.get())
            assertArrayEquals(byteArrayOf(),file.readBytes())
        }
    }

    @Test fun concurrentLastWriterClosesAndNewWriterCannotBypassTheOriginalFailure() {
        val entered=CountDownLatch(1);val proceed=CountDownLatch(1);val competing=CountDownLatch(2)
        val failure=IOException("last writer release is unknown")
        val closes=AtomicInteger();val firstResult=AtomicReference<Throwable?>()
        val secondResult=AtomicReference<Throwable?>();val newcomerResult=AtomicReference<Throwable?>()
        fixture({NativeVoiceRawGate(closeChannel={
            closes.incrementAndGet();it.close();entered.countDown()
            check(proceed.await(5,TimeUnit.SECONDS));throw failure
        },validate={require(it.length()==0L)})}) {file,gate->
            val writer=gate.writer(file,"original")
            val first=Thread {try{writer.close()}catch(error: Throwable){firstResult.set(error)}}
            val second=Thread {competing.countDown();try{writer.close()}catch(error: Throwable){secondResult.set(error)}}
            val newcomer=Thread {competing.countDown();try{gate.computerFileWriter(file,"new").use {it.requireCurrent()}}catch(error: Throwable){newcomerResult.set(error)}}
            first.start()
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));second.start();newcomer.start()
                assertTrue(competing.await(5,TimeUnit.SECONDS))
            }finally {proceed.countDown();first.join(5_000);second.join(5_000);newcomer.join(5_000)}
            assertFalse(first.isAlive);assertFalse(second.isAlive);assertFalse(newcomer.isAlive)
            assertSame(failure,firstResult.get());assertSame(failure,secondResult.get())
            assertTrue(newcomerResult.get() is NativeVoiceRawGate.Busy);assertEquals(1,closes.get())
            assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,Any(),"migration")}
            assertArrayEquals(byteArrayOf(),file.readBytes())
        }
    }

    @Test fun actualFileWriterHandleClosesOnlyAfterTheLastMixedWriterAndOldTokensStayRetired()=fixture {file,gate->
        val transfer=gate.computerFileWriter(file,"transfer")
        val voice=gate.writer(file,"voice")
        transfer.close();voice.requireCurrent()
        assertThrows(NativeVoiceRawGate.Busy::class.java){gate.freeze(file,Any(),"still-writing")}
        voice.close();ComputerFileFdOwners.requireReleased()
        val later=gate.writer(file,"transfer")
        transfer.close();voice.close();later.requireCurrent();later.close()
        val owner=Any();gate.freeze(file,owner,"migration").use {it.requireCurrent(owner,"migration")}
        ComputerFileFdOwners.requireReleased();assertArrayEquals(byteArrayOf(),file.readBytes())
    }
}
