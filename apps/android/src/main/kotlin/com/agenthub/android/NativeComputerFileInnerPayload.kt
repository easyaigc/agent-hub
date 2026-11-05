package com.agenthub.android

import com.agenthub.core.ComputerFileInnerBudget
import com.agenthub.core.ComputerFileInnerDescriptor
import com.agenthub.core.ComputerFileInnerKind
import com.agenthub.core.ComputerFileInnerMetrics
import com.agenthub.core.ComputerFileInnerPayloadRules as Rules
import com.agenthub.core.ComputerFileMigrationEntry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** No implementation is supplied here. Implementers must authenticate the
 * immutable expected receipt and complete all AEAD/EOF/owned-close checks
 * before returning a result. A pass-through/plain-file implementation is not
 * an authenticated boundary and must never be wired to product migration. */
internal interface NativeComputerFileAuthenticatedPayloadBoundary {
    fun sealInnerPayload(encode: (OutputStream)->NativeComputerFileInnerPayload.Receipt): NativeComputerFileInnerPayload.Receipt
    fun <T> openInnerPayload(decode: (InputStream,NativeComputerFileInnerPayload.Receipt)->T): T
}

/** Independent inner payload only: no encryption, password, File/URI API,
 * export, key/child creation, source cleanup, activation or retry path.
 * A future reviewed authenticated-encryption owner must supply/private-stage
 * these streams and independently authenticate the expected immutable receipt.
 * Hashes alone never authenticate an attacker-supplied payload or receipt.
 */
internal object NativeComputerFileInnerPayload {
    private val magic="AHF59I1\n".toByteArray(Charsets.US_ASCII)
    private val footerMagic="AHF59END".toByteArray(Charsets.US_ASCII)
    private val domain=Rules.DOMAIN.toByteArray(Charsets.US_ASCII)
    data class Receipt(val recordCount: Int,val payloadBytes: Long,val manifestBytes: Long,val fileBytes: Long,
        val manifestSha256: String,val payloadSha256: String,val fileSha256: String)

    private fun newDigest()=MessageDigest.getInstance("SHA-256")
    private fun hex(bytes: ByteArray)=bytes.joinToString("") {"%02x".format(it)}
    private fun hash(value: String): ByteArray {
        require(value.matches(Regex("[a-f0-9]{64}"))) {"inner-receipt-hash"}
        return ByteArray(32) {index->value.substring(index*2,index*2+2).toInt(16).toByte()}
    }
    private fun bytes(input: DataInputStream,size: Int)=ByteArray(size).also {input.readFully(it)}
    private fun same(actual: ByteArray,expected: ByteArray) {require(MessageDigest.isEqual(actual,expected)) {"inner-digest-mismatch"}}
    private fun receipt(value: Receipt,budget: ComputerFileInnerBudget) {
        Rules.validateBudget(budget)
        require(value.recordCount in 1..budget.maximumRecords&&value.payloadBytes in 1..budget.maximumPayloadBytes&&
            value.manifestBytes in Rules.HEADER_BYTES..budget.maximumManifestBytes&&value.fileBytes in 1..budget.maximumFileBytes) {"inner-receipt-budget"}
        hash(value.manifestSha256);hash(value.payloadSha256);hash(value.fileSha256)
    }
    private fun snapshot(entries: List<ComputerFileInnerDescriptor>,budget: ComputerFileInnerBudget): List<ComputerFileInnerDescriptor> {
        Rules.validateBudget(budget);require(entries.size in 1..budget.maximumRecords)
        val result=ArrayList<ComputerFileInnerDescriptor>()
        for(value in entries) {require(result.size<budget.maximumRecords);result.add(value.copy(entry=value.entry.copy()))}
        return result
    }
    private fun manifest(output: OutputStream,entries: List<ComputerFileInnerDescriptor>,metrics: ComputerFileInnerMetrics) {
        val out=DataOutputStream(output)
        out.write(magic);out.writeInt(Rules.VERSION);out.writeShort(domain.size);out.write(domain)
        out.writeInt(entries.size);out.writeLong(metrics.payloadBytes)
        for(value in entries) {
            out.writeByte(if(value.generation==null)0 else 1)
            value.generation?.let {val uuid=UUID.fromString(it);out.writeLong(uuid.mostSignificantBits);out.writeLong(uuid.leastSignificantBits)}
            out.writeByte(value.kind.code)
            val name=value.entry.name.toByteArray(Charsets.US_ASCII);out.writeShort(name.size);out.write(name)
            out.writeLong(value.entry.size.toLong());out.write(hash(value.entry.sha256))
        }
    }
    private fun manifest(input: InputStream,budget: ComputerFileInnerBudget): Pair<List<ComputerFileInnerDescriptor>,ComputerFileInnerMetrics> {
        val data=DataInputStream(input)
        same(bytes(data,magic.size),magic);require(data.readInt()==Rules.VERSION) {"inner-version"}
        require(data.readUnsignedShort()==domain.size) {"inner-domain"};same(bytes(data,domain.size),domain)
        val count=data.readInt();require(count in 1..budget.maximumRecords) {"inner-record-count"}
        val payload=data.readLong();require(payload in 1..budget.maximumPayloadBytes) {"inner-payload-budget"}
        var metadata=Rules.HEADER_BYTES
        val entries=ArrayList<ComputerFileInnerDescriptor>()
        repeat(count) {
            val flag=data.readUnsignedByte();require(flag in 0..1) {"inner-generation-tag"}
            val generation=if(flag==0)null else UUID(data.readLong(),data.readLong()).toString()
            val kind=when(data.readUnsignedByte()) {1->ComputerFileInnerKind.JOURNAL;2->ComputerFileInnerKind.CHUNK;else->error("inner-kind")}
            val length=data.readUnsignedShort();require(length in 1..Rules.MAX_NAME_BYTES) {"inner-name-length"}
            metadata=Rules.add(metadata,1L+(if(flag==0)0L else 16L)+1L+2L+length+8L+32L,budget.maximumManifestBytes)
            val raw=bytes(data,length);require(raw.all {it.toInt() in 1..127}) {"inner-name-ascii"}
            val size=data.readLong();require(size in 1..Int.MAX_VALUE.toLong()) {"inner-entry-length"}
            val value=ComputerFileInnerDescriptor(generation,kind,ComputerFileMigrationEntry(String(raw,Charsets.US_ASCII),size.toInt(),hex(bytes(data,32))))
            Rules.validateDescriptor(value)
            if(entries.isNotEmpty())require(Rules.compare(entries.last(),value)<0) {"inner-duplicate-or-order"}
            entries.add(value)
        }
        val metrics=Rules.metrics(entries,budget)
        require(metrics.payloadBytes==payload&&metrics.manifestBytes==metadata) {"inner-manifest-total"}
        return entries to metrics
    }
    private class WireOutput(private val output: OutputStream,private val maximum: Long): OutputStream() {
        val digest=newDigest();var count=0L;private set
        var failed=false;private set
        override fun write(value: Int)=write(byteArrayOf(value.toByte()),0,1)
        override fun write(bytes: ByteArray,offset: Int,length: Int) {
            check(!failed);require(offset>=0&&length>=0&&offset<=bytes.size-length)
            val next=Rules.add(count,length.toLong(),maximum)
            try {output.write(bytes,offset,length);digest.update(bytes,offset,length);count=next}
            catch(error: Throwable){failed=true;throw error}
        }
        override fun flush() {check(!failed);try {output.flush()}catch(error: Throwable){failed=true;throw error}}
    }
    private class WireInput(private val input: InputStream,private val maximum: Long): InputStream() {
        val digest=newDigest();var count=0L;private set
        var failed=false;private set
        override fun read(): Int {val one=ByteArray(1);return if(read(one,0,1)==-1)-1 else one[0].toInt() and 255}
        override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
            check(!failed);require(offset>=0&&length>=0&&offset<=bytes.size-length);if(length==0)return 0
            // Read at most one byte beyond the immutable expected full-file
            // length. That byte refuses the input rather than being ignored.
            val allowed=minOf(length.toLong(),maximum-count+1L).toInt()
            return try {
                val got=input.read(bytes,offset,allowed)
                if(got==-1)-1 else {
                    require(got in 1..allowed) {"inner-input-no-progress-or-invalid-count"}
                    count=Rules.add(count,got.toLong(),maximum);digest.update(bytes,offset,got);got
                }
            }catch(error: Throwable){failed=true;throw error}
        }
    }
    private class RecordOutput(private val output: OutputStream,private val descriptor: ComputerFileInnerDescriptor,
        private val payload: MessageDigest): OutputStream() {
        private val thread=Thread.currentThread();private var active=true;private var failure: Throwable?=null
        private var count=0L;private val digest=newDigest()
        // Only the owner can read or mutate the scope state. Wrong-thread access
        // must fail before the exception latch, so it cannot poison the owner.
        private fun requireOwner(){check(Thread.currentThread()===thread&&active) {"inner-output-expired"}}
        private fun current(){requireOwner();failure?.let {throw it}}
        override fun write(value: Int)=write(byteArrayOf(value.toByte()),0,1)
        override fun write(bytes: ByteArray,offset: Int,length: Int) {
            requireOwner()
            try {
                current();require(offset>=0&&length>=0&&offset<=bytes.size-length)
                val next=Rules.add(count,length.toLong(),descriptor.entry.size.toLong())
                output.write(bytes,offset,length);digest.update(bytes,offset,length);payload.update(bytes,offset,length);count=next
            }catch(error: Throwable){if(failure==null)failure=error;throw error}
        }
        override fun flush(){current()} // Outer owner flushes exactly once after the complete footer.
        override fun close(){current();val error=IllegalStateException("inner-output-not-owned");failure=error;throw error}
        fun verify(){current();require(count==descriptor.entry.size.toLong()) {"inner-short-record"};same(digest.digest(),hash(descriptor.entry.sha256))}
        fun rethrowFirst(error: Throwable): Nothing {
            val primary=failure?:error
            if(primary!==error)primary.addSuppressed(error)
            throw primary
        }
        fun expire(){active=false}
    }
    private class RecordInput(private val input: InputStream,private val descriptor: ComputerFileInnerDescriptor,
        private val payload: MessageDigest): InputStream() {
        private val thread=Thread.currentThread();private var active=true;private var failure: Throwable?=null
        private var remaining=descriptor.entry.size.toLong();private val digest=newDigest()
        // Only the owner can read or mutate the scope state. Wrong-thread access
        // must fail before the exception latch, so it cannot poison the owner.
        private fun requireOwner(){check(Thread.currentThread()===thread&&active) {"inner-input-expired"}}
        private fun current(){requireOwner();failure?.let {throw it}}
        override fun read(): Int {val one=ByteArray(1);return if(read(one,0,1)==-1)-1 else one[0].toInt() and 255}
        override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
            requireOwner()
            return try {
                current();require(offset>=0&&length>=0&&offset<=bytes.size-length);if(length==0)return 0
                if(remaining==0L)return -1
                val requested=minOf(length.toLong(),remaining).toInt()
                val got=input.read(bytes,offset,requested)
                if(got<0)throw EOFException("inner-short-record")
                require(got in 1..requested) {"inner-input-no-progress-or-invalid-count"}
                remaining-=got;digest.update(bytes,offset,got);payload.update(bytes,offset,got);got
            }catch(error: Throwable){if(failure==null)failure=error;throw error}
        }
        override fun close(){current();val error=IllegalStateException("inner-input-not-owned");failure=error;throw error}
        fun verify(){current();require(remaining==0L) {"inner-unconsumed-record"};same(digest.digest(),hash(descriptor.entry.sha256))}
        fun rethrowFirst(error: Throwable): Nothing {
            val primary=failure?:error
            if(primary!==error)primary.addSuppressed(error)
            throw primary
        }
        fun expire(){active=false}
    }

    /** The caller supplies an already semantically validated frozen source and
     * the future authenticated sink. Exceptions/unknown writes are returned
     * unchanged; no second copy, automatic replay, close, delete or repair.
     */
    fun writeInnerPayload(output: OutputStream,descriptors: List<ComputerFileInnerDescriptor>,budget: ComputerFileInnerBudget,
        copy: (ComputerFileInnerDescriptor,OutputStream)->Unit): Receipt {
        val entries=snapshot(descriptors,budget);val metrics=Rules.metrics(entries,budget)
        val wire=WireOutput(output,metrics.fileBytes);val manifestDigest=newDigest()
        manifest(object: OutputStream() {
            override fun write(value: Int)=write(byteArrayOf(value.toByte()),0,1)
            override fun write(bytes: ByteArray,offset: Int,length: Int){wire.write(bytes,offset,length);manifestDigest.update(bytes,offset,length)}
        },entries,metrics)
        val manifestHash=manifestDigest.digest();require(wire.count==metrics.manifestBytes)
        val data=DataOutputStream(wire);val payload=newDigest()
        for((index,entry)in entries.withIndex()) {
            data.writeLong(index.toLong());data.writeLong(entry.entry.size.toLong())
            val scope=RecordOutput(wire,entry,payload)
            try {copy(entry,scope);scope.verify()}catch(error: Throwable){scope.rethrowFirst(error)}finally {scope.expire()}
        }
        val payloadHash=payload.digest()
        data.write(footerMagic);data.writeInt(metrics.recordCount);data.writeLong(metrics.payloadBytes)
        data.write(manifestHash);data.write(payloadHash);wire.flush()
        check(!wire.failed&&wire.count==metrics.fileBytes)
        return Receipt(metrics.recordCount,metrics.payloadBytes,metrics.manifestBytes,metrics.fileBytes,
            hex(manifestHash),hex(payloadHash),hex(wire.digest.digest()))
    }

    /** The expected receipt must come from an independently authenticated
     * boundary, never from this untrusted byte stream. Consumers may stage
     * inert data only. This function returns no accepted descriptor list
     * before all receipts, hashes, exact framing/EOF and scoped-stream checks
     * succeed; callback observations remain provisional. This codec
     * itself supplies no authenticated-encryption implementation or activation.
     */
    fun readInnerPayload(input: InputStream,expected: Receipt,budget: ComputerFileInnerBudget,
        consume: (ComputerFileInnerDescriptor,InputStream)->Unit): List<ComputerFileInnerDescriptor> {
        receipt(expected,budget)
        val wire=WireInput(input,expected.fileBytes);val manifestDigest=newDigest()
        val observed=manifest(object: InputStream() {
            override fun read(): Int {val one=ByteArray(1);return if(read(one,0,1)==-1)-1 else one[0].toInt() and 255}
            override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                val got=wire.read(bytes,offset,length);if(got>0)manifestDigest.update(bytes,offset,got);return got
            }
        },budget)
        val entries=observed.first;val metrics=observed.second;val manifestHash=manifestDigest.digest()
        require(metrics.recordCount==expected.recordCount&&metrics.payloadBytes==expected.payloadBytes&&
            metrics.manifestBytes==expected.manifestBytes&&metrics.fileBytes==expected.fileBytes&&wire.count==metrics.manifestBytes) {"inner-receipt-manifest"}
        same(manifestHash,hash(expected.manifestSha256))
        val data=DataInputStream(wire);val payload=newDigest()
        for((index,entry)in entries.withIndex()) {
            require(data.readLong()==index.toLong()&&data.readLong()==entry.entry.size.toLong()) {"inner-frame-ordinal-or-size"}
            val scope=RecordInput(wire,entry,payload)
            try {consume(entry,scope);scope.verify()}catch(error: Throwable){scope.rethrowFirst(error)}finally {scope.expire()}
        }
        val payloadHash=payload.digest()
        same(bytes(data,footerMagic.size),footerMagic)
        require(data.readInt()==metrics.recordCount&&data.readLong()==metrics.payloadBytes) {"inner-footer-total"}
        same(bytes(data,32),manifestHash);same(bytes(data,32),payloadHash)
        same(payloadHash,hash(expected.payloadSha256))
        require(wire.read()==-1&&!wire.failed&&wire.count==metrics.fileBytes) {"inner-trailing-or-length"}
        same(wire.digest.digest(),hash(expected.fileSha256))
        return entries.toList()
    }
}
