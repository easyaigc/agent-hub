package com.agenthub.android

import org.json.JSONObject
import java.util.Base64

/** One manifest owns one immutable original payload; legacy inline records are read, never rewritten. */
internal object NativeMigrationAttachments {
    fun validate(name: String, value: JSONObject, binaries: Map<String,String>, streams: Map<String,NativeMigrationBlob>?): String? {
        val record=NativeDraftFiles.record(value);val file=record.file
        require(name=="draft-file:${file.id}")
        if(record.legacy) {
            val encoded=value.getString("bytes");val bytes=Base64.getDecoder().decode(encoded)
            try {
                require(Base64.getEncoder().encodeToString(bytes)==encoded&&bytes.size<=NativeDraftFiles.MAXIMUM_BYTES)
                val probe=NativeMigrationProbe();probe.write(bytes)
                require(probe.size==file.sizeBytes&&probe.sha256()==file.sha256)
            }finally {bytes.fill(0)}
            return null
        }
        val payload=record.payload?:return null
        val blob=if(streams!=null)streams.getValue(payload)else {
            val bytes=NativeMigrationRecords.bytes(binaries.getValue(payload))
            try {val probe=NativeMigrationProbe();probe.write(bytes);NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()){error("Legacy validation descriptor")}}
            finally {bytes.fill(0)}
        }
        require(blob.size.toLong()==file.sizeBytes&&blob.sha256==file.sha256)
        return payload
    }
}
