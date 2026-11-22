package com.agenthub.android

import com.agenthub.core.MessageLinkRules
import java.net.URI
import java.net.URLDecoder

internal data class MessageLinkDestination(val kind: String, val attachmentId: String? = null) {
    companion object {
        private val canonicalUUID=Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        /** Peer resolves traversal against its grant root; never normalize or drop the exact reference here. */
        fun documentReference(reference: String): Boolean = reference.toByteArray().size in 1..8192 &&
            reference.isNotBlank() && reference.none { it.isISOControl() } &&
            resolve(reference.replace(" ","%20")).kind=="computerFile" &&
            (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(reference) ||
                runCatching { URI(reference.replace(" ","%20")).rawPath?.isNotEmpty()==true }.getOrDefault(false))
        // This association is only a candidate. The adapter must revalidate it against the original message
        // and the immutable downloaded digest before using a fresh document grant.
        fun originalDocumentReference(text: String,file: MobileFile): String? {
            val found=mutableSetOf<String>()
            val pending=java.util.ArrayDeque<org.commonmark.node.Node>()
            pending.add(NativeMarkdown.parse(text))
            while(pending.isNotEmpty()) {
                val node=pending.removeLast()
                val reference=(node as? org.commonmark.node.Link)?.destination
                if(reference!=null&&documentReference(reference)&&
                    android.net.Uri.parse(reference).lastPathSegment==file.name)found.add(reference)
                var child=node.firstChild
                while(child!=null) { pending.add(child);child=child.next }
            }
            return found.singleOrNull()
        }
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
