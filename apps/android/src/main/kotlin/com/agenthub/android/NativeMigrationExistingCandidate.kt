package com.agenthub.android

import java.security.MessageDigest
import java.security.DigestOutputStream
import java.io.OutputStream
import java.util.UUID

/** Fixed existing namespace content, not a generation-selected mutable CandidateStore. */
internal object NativeMigrationExistingCandidate {
    fun read(applicationId: String,stored: NativeMigrationStreamRecords,receipt: NativeMigrationCandidateStore.Receipt,consume: (NativeMigrationStreamRecords)->Unit) {
        val raw=stored.text.getValue("candidate-v1");val value=StrictMigrationJson.parse(raw.toByteArray())
        if(value.get("version") in setOf(1,2)) {
            val legacy=NativeMigrationCandidateStore.readExistingArchive(applicationId,value,receipt) {name,count->
                val blob=stored.binary.getValue(name);require(blob.size==count)
                val bytes=ByteArray(count);var offset=0
                val output=object: OutputStream() {
                    override fun write(value: Int) {require(offset<count);bytes[offset++]=value.toByte()}
                    override fun write(source: ByteArray,start: Int,length: Int) {
                        require(start>=0&&length>=0&&start<=source.size-length&&length<=count-offset)
                        source.copyInto(bytes,offset,start,start+length);offset+=length
                    }
                }
                try {blob.verifiedCopy(output);require(offset==count);bytes}
                catch(failure: Throwable){bytes.fill(0);throw failure}
            }
            consume(legacy.stream())
            return
        }
        require(value.keys().asSequence().toSet()==setOf("version","id","sha256","records","generation","header"))
        require(value.get("version") in setOf(3,4)&&value.getString("id")==receipt.id&&value.getString("sha256")==receipt.sha256&&value.get("records")==receipt.recordCount)
        require(UUID.fromString(receipt.id).toString()==receipt.id&&receipt.sha256.matches(Regex("[a-f0-9]{64}")))
        val generation=value.getString("generation");require(UUID.fromString(generation).toString()==generation)
        val header=value.getJSONObject("header");require(header.get("version")==value.get("version"))
        val names=(if(value.get("version")==4)header.getJSONObject("records").keys().asSequence().sorted().toList()else emptyList())+
            header.getJSONObject("binaries").keys().asSequence().sorted().toList()
        val records=NativeMigrationStreamArchive.header(header,applicationId){logical,size->{output->
            val blob=stored.binary.getValue("migration-stream:$generation:${names.indexOf(logical)}")
            require(blob.size==size);blob.verifiedCopy(output)
        }}
        require(records.size==receipt.recordCount)
        NativeMigrationArchiveValidation.stream(records)
        val digest=MessageDigest.getInstance("SHA-256")
        NativeMigrationStreamArchive.write(DigestOutputStream(nullOutputStreamCompat(),digest),records,applicationId)
        require(digest.digest().joinToString(""){"%02x".format(it)}==receipt.sha256)
        consume(records)
    }
}
