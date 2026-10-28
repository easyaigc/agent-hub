package com.agenthub.android

import com.agenthub.core.TargetScope
import java.lang.ref.WeakReference

/** No page, wire, messages or transcript text. Exactly one current latest-page proof belongs to the session. */
internal data class NativeReadingProof(val target: TargetScope,val threadId: String,val state: ThreadReadState?,
    val legacy: NativeLegacyReading?,val selection: Long,val connectionRevision: Long,val epoch: Long)

/** Only the actual current body can be strongly referenced by a visible lease candidate. */
internal data class NativeReadingBody(val messageId: String,val text: String,val status: String?) {
    fun matches(value: MobileMessage)=value.role=="assistant" && value.id==messageId && value.text==text && value.status==status
}

/** Identity reuse never pins an old transcript. A new digest visits only the latest complete legacy reply. */
internal class NativeLegacyReadingFingerprint {
    private var key: String?=null
    private var messageId: String?=null
    private var status: String?=null
    private var body: WeakReference<String>?=null
    private var version: String?=null
    fun version(key: String,messageId: String,text: String,status: String): String {
        if(this.key==key && this.messageId==messageId && this.status==status && body?.get()===text)return requireNotNull(version)
        val digest=ThreadReadPosition.contentVersion(messageId,text,status)
        this.key=key;this.messageId=messageId;this.status=status;body=WeakReference(text);version=digest
        return digest
    }
    fun clear() { key=null;messageId=null;status=null;body=null;version=null }
}
