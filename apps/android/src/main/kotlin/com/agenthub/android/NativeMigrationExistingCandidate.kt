package com.agenthub.android

import java.security.MessageDigest
import java.security.DigestOutputStream
import java.util.UUID

/** Fixed existing namespace content, not a generation-selected mutable CandidateStore. */
internal object NativeMigrationExistingCandidate {
    fun read(applicationId: String,stored: NativeMigrationStreamRecords,receipt: NativeMigrationCandidateStore.Receipt,consume: (NativeMigrationStreamRecords)->Unit) {
        val raw=stored.text.getValue("candidate-v1");val value=StrictMigrationJson.parse(raw.toByteArray())
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
