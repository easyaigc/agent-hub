package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Real encrypted serial destination writer; only the in-memory view/recognizer is synthetic. */
internal class NativeSavedVoiceDestinationFixture(val storage: NativeSessionStorage,val draft: VoiceDraft,private val filled: (String)->Unit={}) {
    var checkpoint: (Int)->Unit={}
    private var revision=0L
    fun enqueueUnchanged() {revision++;storage.enqueue("metadata",memory){}}
    var memory: String
    init {
        val value=storage.read("metadata")?.let(::JSONObject) ?: JSONObject().put("clients",JSONArray().put(encodeClient(draft.client))).put("drafts",JSONObject()).put("draftVersions",JSONObject())
        val key=NativeVoiceDestinationKeys.owner(draft.key)
        value.getJSONObject("drafts").put(key,draft.text);(value.optJSONObject("draftVersions") ?: JSONObject().also {value.put("draftVersions",it)}).put(key,draft.version)
        memory=value.toString();storage.write("metadata",memory)
    }
    val port: NativeVoiceDraftDestinationPort=NativeEncryptedVoiceDraftDestinationPort(storage,object: NativeVoiceDraftOwner {
        private var preparedRevision=-1L
        override fun prepare(request: NativeVoiceDraftRequest): NativeVoiceDraftWrite {preparedRevision=revision;return NativeVoiceDraftWrite.prepare(request,memory)}
        override fun publish(write: NativeVoiceDraftWrite): Boolean {
            if(preparedRevision!=revision||memory!=write.before)return false
            memory=write.after;filled(write.request.text);return true
        }
    },{checkpoint(it)})
}
