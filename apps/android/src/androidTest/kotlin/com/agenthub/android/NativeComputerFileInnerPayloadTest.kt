package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** UNEXECUTED candidate. In-memory inner framing and read-only fixture assets;
 * no keystore, private files, Activity, authenticated container or migration.
 */
@RunWith(AndroidJUnit4::class)
class NativeComputerFileInnerPayloadTest {
    private val budget=ComputerFileInnerBudget(4096,4L*1024*1024*1024,1024L*1024,5L*1024*1024*1024)
    private data class Known(val id: String,val entries: List<ComputerFileInnerDescriptor>,val bodies: List<ByteArray>,
        val wire: ByteArray,val receipt: NativeComputerFileInnerPayload.Receipt)
    private fun unhex(value: String): ByteArray {
        require(value.length%2==0&&value.matches(Regex("[a-f0-9]*")))
        return ByteArray(value.length/2) {value.substring(it*2,it*2+2).toInt(16).toByte()}
    }
    private fun vectors(): List<Known> {
        val root=InstrumentationRegistry.getInstrumentation().context.assets.open("computer-file-inner-payload-v1.json")
            .use {JSONObject(String(it.readBytes(),Charsets.UTF_8))}
        val rows=root.getJSONArray("vectors")
        return (0 until rows.length()).map {index->
            val row=rows.getJSONObject(index);val list=row.getJSONArray("entries");val bodies=mutableListOf<ByteArray>()
            val entries=(0 until list.length()).map {n->val entry=list.getJSONObject(n);bodies.add(unhex(entry.getString("bodyHex")))
                ComputerFileInnerDescriptor(if(entry.isNull("generation"))null else entry.getString("generation"),
                    when(entry.getString("kind")){"journal"->ComputerFileInnerKind.JOURNAL;"chunk"->ComputerFileInnerKind.CHUNK;else->error("fixture kind")},
                    ComputerFileMigrationEntry(entry.getString("name"),entry.getInt("size"),entry.getString("sha256")))}
            val expected=row.getJSONObject("expected")
            Known(row.getString("id"),entries,bodies,unhex(row.getString("wireHex")),NativeComputerFileInnerPayload.Receipt(
                expected.getInt("recordCount"),expected.getLong("payloadBytes"),expected.getLong("manifestBytes"),expected.getLong("fileBytes"),
                expected.getString("manifestSha256"),expected.getString("payloadSha256"),expected.getString("fileSha256")))
        }
    }
    private fun rejected(work: ()->Unit): Throwable {
        val error=try {work();null}catch(error: Throwable){error}
        assertTrue("Expected codec refusal, got ${error?.javaClass?.name}",
            error is IllegalArgumentException||error is IllegalStateException||error is IOException)
        return requireNotNull(error)
    }
    private fun drain(input: InputStream): ByteArray {
        val result=ByteArrayOutputStream();val buffer=ByteArray(7)
        while(true) {val count=input.read(buffer);if(count<0)break;result.write(buffer,0,count)}
        return result.toByteArray()
    }

    @Test fun independentContractBytesRoundTripWithoutTreatingTheSyntheticRecordsAsACompleteGraph() {
        for(vector in vectors()) {
            val destination=ByteArrayOutputStream()
            val written=NativeComputerFileInnerPayload.writeInnerPayload(destination,vector.entries,budget) {entry,out->
                val bytes=vector.bodies[vector.entries.indexOf(entry)];bytes.forEach {out.write(it.toInt() and 255)}
            }
            assertEquals(vector.receipt,written);assertArrayEquals(vector.wire,destination.toByteArray())
            var consumed=0
            val restored=NativeComputerFileInnerPayload.readInnerPayload(ByteArrayInputStream(vector.wire),vector.receipt,budget) {entry,input->
                assertEquals(vector.entries[consumed],entry);assertArrayEquals(vector.bodies[consumed],drain(input));consumed++
            }
            assertEquals(vector.entries,restored);assertEquals(vector.entries.size,consumed)
        }
        val orphan=vectors().first {it.id=="chunk-abc-root"}.entries.single()
        assertEquals("orphan-or-changed-original",ComputerFileMigrationRules.validate(emptyList(),emptyList(),listOf(orphan.entry),1,1,3))
    }

    @Test fun unknownManifestTypesOrdinalsLengthsTruncationAndTrailingBytesNeverReturnSuccess() {
        val vector=vectors().first {it.id=="journal-and-chunk-root"}
        val corruptions=mutableListOf<ByteArray>()
        for(position in listOf(11,14,56,57,vector.receipt.manifestBytes.toInt()+7,vector.receipt.manifestBytes.toInt()+15,
            vector.receipt.manifestBytes.toInt()+16,vector.wire.size-1)) {
            corruptions.add(vector.wire.copyOf().also {it[position]=(it[position].toInt() xor 3).toByte()})
        }
        corruptions.add(vector.wire.copyOf(vector.wire.size-1));corruptions.add(vector.wire+byteArrayOf(0))
        for(bytes in corruptions) {
            var consumed=0
            rejected {NativeComputerFileInnerPayload.readInnerPayload(ByteArrayInputStream(bytes),vector.receipt,budget) {_,input->drain(input);consumed++}}
            assertTrue(consumed<=vector.entries.size)
        }
    }

    @Test fun invalidDescriptorOrBudgetIsRejectedBeforeTheFirstDestinationByte() {
        val vector=vectors().first();val entry=vector.entries.single()
        for(entries in listOf(listOf(entry,entry),listOf(entry.copy(entry=entry.entry.copy(name="file:///synthetic"))),
            listOf(entry.copy(entry=entry.entry.copy(size=ComputerFileMigrationRules.CHUNK_BYTES+1))))) {
            val destination=ByteArrayOutputStream();var called=false
            rejected {NativeComputerFileInnerPayload.writeInnerPayload(destination,entries,budget) {_,_->called=true}}
            assertEquals(0,destination.size());assertFalse(called)
        }
        var sourceReads=0
        rejected {NativeComputerFileInnerPayload.readInnerPayload(object: InputStream(){override fun read(): Int {sourceReads++;return -1}},
            vector.receipt,budget.copy(maximumFileBytes=vector.receipt.fileBytes-1)) {_,_->error("must not consume")}}
        assertEquals(0,sourceReads)
    }

    private fun wrongThread(work: ()->Unit): Throwable? {
        val result=FutureTask<Throwable?> {try {work();null}catch(error: Throwable){error}}
        val worker=Thread(result,"f59-test-foreign-borrower").apply {isDaemon=true;start()}
        return try {result.get(5,TimeUnit.SECONDS)} finally {
            worker.join(5_000)
            assertFalse("Foreign borrower did not exit",worker.isAlive)
        }
    }

    @Test fun scopedStreamsExpireAndPartialConsumptionCannotBeConvertedIntoAReceipt() {
        val vector=vectors().first();lateinit var lateOutput: OutputStream
        val output=ByteArrayOutputStream()
        val receipt=NativeComputerFileInnerPayload.writeInnerPayload(output,vector.entries,budget) {_,sink->
            lateOutput=sink
            val before=output.size()
            assertEquals("inner-output-expired",wrongThread {sink.write(0)}?.message)
            assertEquals(before,output.size())
            sink.write(vector.bodies.single())
        }
        val complete=output.toByteArray()
        assertEquals(vector.receipt,receipt);assertArrayEquals(vector.wire,complete)
        rejected {lateOutput.write(0)};assertArrayEquals(complete,output.toByteArray())
        val sourceBytes=ByteArrayInputStream(vector.wire)
        val observed=NativeComputerFileInnerPayload.readInnerPayload(sourceBytes,vector.receipt,budget) {_,source->
            val before=sourceBytes.available()
            assertEquals("inner-input-expired",wrongThread {source.read()}?.message)
            assertEquals(before,sourceBytes.available())
            assertArrayEquals(vector.bodies.single(),drain(source))
        }
        assertEquals(vector.entries,observed)
        lateinit var lateInput: InputStream
        rejected {NativeComputerFileInnerPayload.readInnerPayload(ByteArrayInputStream(vector.wire),vector.receipt,budget) {_,source->
            lateInput=source;assertTrue(source.read()>=0)
        }}
        rejected {lateInput.read()}
    }

    @Test fun partialOutputFailureIsKeptEvenIfTheSourceCatchesItAndAttemptsAnotherWrite() {
        val vector=vectors().first {it.id=="journal-and-chunk-root"};val sentinel=IOException("synthetic partial destination")
        var attempts=0;var written=0L;var flushes=0;var closes=0;var sourceCalls=0
        val failAt=vector.receipt.manifestBytes+16+1
        val output=object: OutputStream() {
            override fun write(value: Int) {attempts++;if(written==failAt)throw sentinel;written++}
            override fun flush(){flushes++};override fun close(){closes++}
        }
        val actual=try {NativeComputerFileInnerPayload.writeInnerPayload(output,vector.entries,budget) {entry,sink->
            sourceCalls++
            try {sink.write(vector.bodies[vector.entries.indexOf(entry)])}catch(error: Throwable){assertSame(sentinel,error)}
            val afterFailure=attempts
            assertSame(sentinel,rejected {sink.write(0)});assertEquals(afterFailure,attempts)
        };null}catch(error: Throwable){error}
        assertSame(sentinel,actual);assertEquals(1,sourceCalls);assertEquals(0,flushes);assertEquals(0,closes)
    }

    @Test fun partialInputFailureIsNotReadAgainOrHiddenByTheConsumer() {
        val vector=vectors().first {it.id=="journal-and-chunk-root"};val sentinel=IOException("synthetic partial source")
        var position=0;var attempts=0;var closes=0;var consumerCalls=0
        val failAt=(vector.receipt.manifestBytes+16+1).toInt()
        val input=object: InputStream() {
            override fun read(): Int {attempts++;if(position==failAt)throw sentinel;return if(position==vector.wire.size)-1 else vector.wire[position++].toInt() and 255}
            override fun close(){closes++}
        }
        val actual=try {NativeComputerFileInnerPayload.readInnerPayload(input,vector.receipt,budget) {_,source->
            consumerCalls++;try {drain(source)}catch(error: Throwable){assertSame(sentinel,error)}
            val afterFailure=attempts
            assertSame(sentinel,rejected {source.read()});assertEquals(afterFailure,attempts)
        };null}catch(error: Throwable){error}
        assertSame(sentinel,actual);assertEquals(1,consumerCalls);assertEquals(0,closes)
    }
    @Test fun firstPartialOutputErrorRemainsPrimaryWhenTheCallbackThenThrowsAnotherError() {
        val vector=vectors().first {it.id=="journal-and-chunk-root"}
        val first=IOException("first observable partial output")
        val later=IllegalStateException("source callback after observed IO failure")
        var writes=0;var position=0L;var flushes=0;var closes=0;var callbacks=0;var afterFirst: Int?=null
        val failAt=vector.receipt.manifestBytes+16+1
        val output=object: OutputStream() {
            override fun write(value: Int){writes++;if(position==failAt)throw first;position++}
            override fun flush(){flushes++};override fun close(){closes++}
        }
        val actual=try {NativeComputerFileInnerPayload.writeInnerPayload(output,vector.entries,budget) {entry,sink->
            callbacks++
            try {sink.write(vector.bodies[vector.entries.indexOf(entry)]);error("Expected a real partial output failure")}
            catch(error: IOException){assertSame(first,error);afterFirst=writes}
            throw later
        };null}catch(error: Throwable){error}
        assertSame(first,actual);assertTrue(first.suppressed.any {it===later})
        assertEquals(1,callbacks);assertEquals(requireNotNull(afterFirst),writes)
        assertEquals(0,flushes);assertEquals(0,closes)
    }

    @Test fun firstPartialInputErrorRemainsPrimaryWhenTheCallbackThenThrowsAnotherError() {
        val vector=vectors().first {it.id=="journal-and-chunk-root"}
        val first=IOException("first observable partial input")
        val later=IllegalStateException("consumer callback after observed IO failure")
        var position=0;var reads=0;var closes=0;var callbacks=0;var afterFirst: Int?=null
        val failAt=(vector.receipt.manifestBytes+16+1).toInt()
        val input=object: InputStream() {
            override fun read(): Int {reads++;if(position==failAt)throw first;return if(position==vector.wire.size)-1 else vector.wire[position++].toInt() and 255}
            override fun close(){closes++}
        }
        val actual=try {NativeComputerFileInnerPayload.readInnerPayload(input,vector.receipt,budget) {_,source->
            callbacks++
            try {drain(source);error("Expected a real partial input failure")}
            catch(error: IOException){assertSame(first,error);afterFirst=reads}
            throw later
        };null}catch(error: Throwable){error}
        assertSame(first,actual);assertTrue(first.suppressed.any {it===later})
        assertEquals(1,callbacks);assertEquals(requireNotNull(afterFirst),reads);assertEquals(0,closes)
    }

}
