package com.agenthub.android

import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Memory-only production graph checks. No storage, recorder, callback, key or command is constructed. */
class NativeMigrationProvisionalContractTest {
    private val client=MobileClient("provisional-host","Synthetic",TargetScope("codex",null,null,"provisional-host","workspace","runtime"),endpoint="https://fixture.invalid")
    private val id="11111111-1111-4111-8111-111111111111"
    private val row get()=PendingVoiceRecording(id,AISharingScope(client.scope!!,"codex-dictation"),null,client.cacheKey("thread"),"draft","thread","原稿 😀 % @",42).json().toString()
    private fun catalog(kind: String="raw",bytes: ByteArray=byteArrayOf(0,9,8,7))=JSONObject().put("version",1).put("originals",JSONArray().put(
        NativeMigrationProvisionalVoice.Original(id,JSONArray(listOf(id)).toString(),row,kind,if(kind=="raw")bytes.size else 0,NativeMigrationProvisionalVoice.sha(if(kind=="raw")bytes else byteArrayOf())).json()))
    private fun records(value: JSONObject)=mapOf(NativeMigrationProvisionalVoice.RECORD to value.toString(),"voice-index-v1" to "[]")
    private fun blob(bytes: ByteArray): NativeMigrationBlob {val p=NativeMigrationProbe();p.write(bytes);return NativeMigrationBlob(bytes.size,p.sha256(),p.prefix()){it.write(bytes)}}
    @Test fun completePartialBytesAndAbsentEmptyStatesAreDistinctInertOriginals() {
        val bytes=byteArrayOf(0,9,8,7);val name=NativeMigrationProvisionalVoice.PREFIX+id
        assertEquals(setOf(name),NativeMigrationProvisionalVoice.validate(records(catalog()),mapOf(name to blob(bytes)),listOf(client)))
        for(kind in listOf("absent","empty"))assertTrue(NativeMigrationProvisionalVoice.validate(records(catalog(kind)),emptyMap(),listOf(client)).isEmpty())
        assertNotEquals(catalog("absent").toString(),catalog("empty").toString())
        val out=java.io.ByteArrayOutputStream();blob(bytes).verifiedCopy(out);assertArrayEquals(bytes,out.toByteArray())
    }
    @Test fun unknownSchemaChangedIdentityOrAuthorityAndIncompleteInventoryReject() {
        val name=NativeMigrationProvisionalVoice.PREFIX+id;val binary=mapOf(name to blob(byteArrayOf(0,9,8,7)))
        val changes=listOf<(JSONObject)->Unit>(
            {it.put("version",2)},{it.put("resume",true)},
            {it.getJSONArray("originals").getJSONObject(0).put("id",UUID.randomUUID().toString())},
            {it.getJSONArray("originals").getJSONObject(0).put("index","[]")},
            {it.getJSONArray("originals").getJSONObject(0).put("size",5)},
            {it.getJSONArray("originals").getJSONObject(0).put("sha256","a".repeat(64))},
            {it.getJSONArray("originals").getJSONObject(0).put("kind","resume")},
            {val r=it.getJSONArray("originals").getJSONObject(0);r.put("row",JSONObject(row).put("prepareAttempted",true).toString())},
            {val r=it.getJSONArray("originals").getJSONObject(0);r.put("row",JSONObject(row).put("migrationReadOnly",true).toString())},
            {val r=it.getJSONArray("originals").getJSONObject(0);r.put("row",JSONObject(row).put("unknown",1).toString())},
            {val a=it.getJSONArray("originals");a.put(a.getJSONObject(0))})
        for(change in changes) {val v=catalog();change(v);assertThrows(Exception::class.java){NativeMigrationProvisionalVoice.validate(records(v),binary,listOf(client))}}
        assertThrows(Exception::class.java){NativeMigrationProvisionalVoice.validate(records(catalog()),emptyMap(),listOf(client))}
        assertThrows(Exception::class.java){NativeMigrationProvisionalVoice.validate(records(catalog())+("voice-index-v1" to JSONArray(listOf(id)).toString()),binary,listOf(client))}
        assertThrows(Exception::class.java){NativeMigrationProvisionalVoice.validate(records(catalog())+("voice-recording:$id" to row),binary,listOf(client))}
        assertThrows(Exception::class.java){NativeMigrationProvisionalVoice.validate(records(catalog()),binary+(NativeMigrationProvisionalVoice.PREFIX+UUID.randomUUID() to blob(byteArrayOf(1))),listOf(client))}
        assertThrows(Exception::class.java){NativeMigrationProvisionalVoice.validate(records(catalog()),binary,emptyList())}
    }
    @Test fun currentLiveIdsAreOnlyUnsealedIndexedOriginalsAndFramesAuthenticateEveryByte() {
        assertEquals(listOf(id),NativeMigrationProvisionalVoice.liveIds(mapOf("voice-index-v1" to JSONArray(listOf(id)).toString(),"voice-recording:$id" to row)))
        val good=blob(byteArrayOf(0,9,8,7));val changed=good.copy(copy={it.write(byteArrayOf(0,9,8,6))})
        assertThrows(Exception::class.java){changed.verifiedCopy(java.io.ByteArrayOutputStream())}
    }
}
