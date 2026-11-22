package com.agenthub.android

import org.json.JSONArray

/** A presentation identity, never a download selector or an authorization token. */
internal object PreviewReadingIdentity {
    private fun key(parts: List<String>)=readingSHA(JSONArray(parts).toString())
    fun message(target: String,message: String,reference: String)=key(listOf("message-preview-v1",target,message,reference))
    fun attachment(file: MobileFile)="attachment:"+key(listOf("message-attachment-v1",file.name,file.mediaType,file.sizeBytes.toString(),file.sha256))
    fun child(parent: String,reference: String)=key(listOf("document-preview-v1",parent,reference.substringBefore('#')))
    fun content(identity: String,digest: String)=key(listOf("preview-content-v1",identity,digest))
}
