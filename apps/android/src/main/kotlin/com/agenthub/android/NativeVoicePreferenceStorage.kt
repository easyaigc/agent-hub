package com.agenthub.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One user preference, never a capability, permission, recording or automatic-start instruction. */
internal object NativeVoicePreferenceRecord {
    const val RECORD = "native-voice-preference-v1"
    const val MAX_UTF8_BYTES = 256
    fun decode(raw: String): VoiceInputController.Provider {
        val bytes=raw.toByteArray(Charsets.UTF_8);require(bytes.size<=MAX_UTF8_BYTES)
        val value=StrictMigrationJson.parse(bytes)
        require(value.keys().asSequence().toSet()==setOf("version","defaultProvider")&&value.get("version")==1)
        return when(value.get("defaultProvider") as String) {
            "desktop"->VoiceInputController.Provider.DESKTOP
            "system"->VoiceInputController.Provider.SYSTEM
            else->error("Unsupported voice preference; original record preserved")
        }
    }
    fun encode(value: VoiceInputController.Provider): String=JSONObject().put("version",1).put("defaultProvider",when(value) {
        VoiceInputController.Provider.DESKTOP->"desktop"
        VoiceInputController.Provider.SYSTEM->"system"
    }).toString().also {decode(it)}
    fun validate(records: Map<String,String>): Set<String> {
        records[RECORD]?.let(::decode);return setOf(RECORD).intersect(records.keys)
    }
    /** An existing destination record is an explicit choice; absence is not an explicit desktop. */
    fun preserveDestination(storage: NativeSessionStorage): Map<String,String> = storage.migrationReadTextRecords(setOf(RECORD)).also {validate(it)}
}

/** Bind once to the restored Session's exact storage. Existing generation fences reject stale handles. */
internal class NativeEncryptedVoicePreferenceStore(private val storage: NativeSessionStorage): NativeVoicePreferenceStore {
    override suspend fun load(): VoiceInputController.Provider?=withContext(Dispatchers.IO) {
        val caller=currentCoroutineContext()
        storage.operationTransaction {
            caller.ensureActive()
            storage.migrationReadTextRecords(setOf(NativeVoicePreferenceRecord.RECORD))[NativeVoicePreferenceRecord.RECORD]?.let(NativeVoicePreferenceRecord::decode)
        }
    }
    override suspend fun save(value: VoiceInputController.Provider)=withContext(Dispatchers.IO) {
        val caller=currentCoroutineContext()
        storage.operationTransaction {
            caller.ensureActive()
            val raw=storage.migrationReadTextRecords(setOf(NativeVoicePreferenceRecord.RECORD))[NativeVoicePreferenceRecord.RECORD]
            // A new selection does not silently repair unknown/corrupt stored state. Same value is a read-only retry.
            val prior=raw?.let(NativeVoicePreferenceRecord::decode)
            caller.ensureActive()
            if(prior!=value)storage.write(NativeVoicePreferenceRecord.RECORD,NativeVoicePreferenceRecord.encode(value))
        }
    }
}
