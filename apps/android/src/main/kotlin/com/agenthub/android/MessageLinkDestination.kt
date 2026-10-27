package com.agenthub.android

import com.agenthub.core.MessageLinkRules
import java.net.URI
import java.net.URLDecoder

internal data class MessageLinkDestination(val kind: String, val attachmentId: String? = null) {
    companion object {
        private val canonicalUUID=Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        fun sameAttachmentId(actual: String,reference: String?): Boolean = actual==reference ||
            reference!=null&&canonicalUUID.matches(actual)&&canonicalUUID.matches(reference)&&actual.equals(reference,ignoreCase=true)
        fun resolve(reference: String): MessageLinkDestination {
            val uri=runCatching { URI(reference) }.getOrNull()
            val host=uri?.host ?: if(uri!=null) android.net.Uri.parse(reference).host else null
            val authority=uri?.rawAuthority?.substringAfterLast('@')
            val port=if(authority?.startsWith("[")==true) authority.substringAfter(']',"").removePrefix(":")
                else authority?.substringAfter(':',"")
            val validPort=uri?.scheme?.lowercase() !in listOf("http","https") || port.orEmpty().all { it in '0'..'9' }
            val id=if(uri?.scheme.equals("codex-attachment",ignoreCase=true)) {
                if(host=="open") uri?.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=')=="id" }
                    ?.substringAfter('=',"")?.let { runCatching { URLDecoder.decode(it.replace("+","%2B"),"UTF-8") }.getOrNull() }
                else host
            } else null
            val kind=MessageLinkRules.destination(uri?.scheme,host,uri?.rawUserInfo!=null || uri?.rawAuthority?.contains('@')==true,id,
                Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(reference),uri!=null&&validPort)
            return MessageLinkDestination(kind,id)
        }
    }
}
