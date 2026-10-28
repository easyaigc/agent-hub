package com.agenthub.android

import org.json.JSONObject
import java.util.Base64

/** Binary values are canonical base64 inside the authenticated archive, never arbitrary paths. */
internal data class NativeMigrationRecords(val text: Map<String,String>,val binary: Map<String,String> = emptyMap()) {
    val size get()=text.size+binary.size
    init { require(text.keys.intersect(binary.keys).isEmpty()) }
    fun json(applicationId: String)=JSONObject().put("format","agent-hub/android-migration")
        .put("version",if(binary.isEmpty())1 else 2).put("applicationId",applicationId).put("records",JSONObject(text))
        .also {if(binary.isNotEmpty())it.put("binaries",JSONObject(binary))}
    companion object {
        fun bytes(encoded: String): ByteArray {
            require(encoded.length<=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
            val value=Base64.getDecoder().decode(encoded)
            try {require(Base64.getEncoder().encodeToString(value)==encoded);return value}
            catch(failure: Throwable){value.fill(0);throw failure}
        }
    }
}
