package com.agenthub.android

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Contract tests only: no channel, network, clipboard, file permission or model task. */
class EasyWorkDocumentFileResolutionTest {
    private val client=MobileClient("owned-host","Owned",isDemo=false)
    private val message="00000000-0000-4000-8000-000000000001"
    private val digest="a".repeat(64)
    private val target=JSONObject().put("desktopDeviceId",client.id).put("workspaceId","owned-workspace")
        .put("runtimeId","owned-runtime").put("account",JSONObject().put("authority","owned").put("subject","one"))
    private fun attachment(id: String="child")=JSONObject().put("resourceId",id).put("name","guide.md")
        .put("contentType","text/markdown").put("size",42).put("sha256",digest)
        .put("documentSource",JSONObject().put("grantId","grant").put("version","v1").put("messageId",message))
    private fun source()=EasyWorkDocumentFileResolution.decode(attachment("source"),client,"thread")
    private fun response(params: JSONObject)=JSONObject().put("version",1).put("target",target)
        .put("threadId","thread").put("source",params.getJSONObject("source"))
        .put("reference",params.getString("reference")).put("attachment",attachment())
    private suspend fun rejected(block: suspend ()->Unit) {
        try { block();fail("Mismatched or invalid resource accepted") } catch(_: IllegalArgumentException) { }
    }
    @Test fun messageResolutionUsesOriginalIdAndPreservesExactReferenceAcrossNewResourceIds()=runBlocking {
        val reference="docs/guide.md#part"
        suspend fun resolve(id: String)=EasyWorkDocumentFileResolution.message(client,"thread","$message:2",reference) { method,p ->
            assertEquals("resource/resolve",method);assertEquals(message,p.getString("messageId"))
            JSONObject().put("version",1).put("threadId","thread").put("messageId",message)
                .put("reference",reference).put("attachment",attachment(id))
        }
        val first=resolve("one");val next=resolve("two")
        assertNotEquals(first.id,next.id);assertEquals(first.documentMessageSource,next.documentMessageSource)
        assertEquals("named:2",EasyWorkDocumentFileResolution.originalMessageId("named:2"))
        assertEquals("$message:-1",EasyWorkDocumentFileResolution.originalMessageId("$message:-1"))
        rejected { EasyWorkDocumentFileResolution.message(client,"thread",message,reference) { _,_ ->
            JSONObject().put("version",1).put("threadId","other").put("messageId",message)
                .put("reference",reference).put("attachment",attachment())
        } }
    }
    @Test fun documentRelativeReferenceAndAnchorAreExactAndEveryBindingIsChecked()=runBlocking {
        val ref="../sibling/guide.md#part"
        val child=EasyWorkDocumentFileResolution.document(client,target,source(),ref,digest) { method,p ->
            assertEquals("resource/resolve-document-link",method);assertEquals(ref,p.getString("reference"));response(p)
        }
        assertEquals(message,child.documentSource?.messageId)
        for(field in listOf("target","threadId","reference","source","version","childMessage","resourceId","size","hash")) {
            rejected { EasyWorkDocumentFileResolution.document(client,target,source(),ref,digest) { _,p ->
                response(p).apply {
                    when(field) {
                        "target" -> put("target",JSONObject(target.toString()).put("runtimeId","other"))
                        "threadId","reference" -> put(field,"other")
                        "source" -> put(field,JSONObject(p.getJSONObject("source").toString()).put("grantId","other"))
                        "version" -> put(field,true)
                        "childMessage" -> getJSONObject("attachment").getJSONObject("documentSource").put("messageId","other")
                        "resourceId" -> getJSONObject("attachment").put("resourceId","../outside")
                        "size" -> getJSONObject("attachment").put("size",1.5)
                        "hash" -> getJSONObject("attachment").put("sha256","bad")
                    }
                }
            } }
        }
    }
    @Test fun emptyAndUnsupportedReferencesNeverReachPeerAndTraversalRejectionPropagates()=runBlocking {
        for(ref in listOf(""," ","#","?query","https://example.com/file.md","javascript:alert(1)","a\u0000b")) {
            assertFalse(ref,MessageLinkDestination.documentReference(ref))
            rejected { EasyWorkDocumentFileResolution.document(client,target,source(),ref,digest) { _,_ ->
                fail("Invalid reference reached peer");JSONObject()
            } }
        }
        var called=false
        rejected { EasyWorkDocumentFileResolution.document(client,target,source(),"../../outside.md",digest) { _,p ->
            called=true;assertEquals("../../outside.md",p.getString("reference"));throw IllegalArgumentException("outside grant")
        } }
        assertTrue(called)
    }
    @Test fun cancelledNonCooperativeLookupNeverPublishesChild()=runBlocking {
        val job=Job()
        try {
            withContext(job) {
                EasyWorkDocumentFileResolution.document(client,target,source(),"child.md",digest) { _,p ->
                    job.cancel();response(p)
                }
                fail("Cancelled child was published")
            }
            fail("Cancellation was lost")
        } catch(_: CancellationException) { }
    }
    @Test fun attachmentCandidateIsUniqueAndNeverAnAuthority() {
        val file=source()
        assertEquals("docs/guide.md",MessageLinkDestination.originalDocumentReference("[Guide](docs/guide.md)",file))
        assertNull(MessageLinkDestination.originalDocumentReference("[One](one/guide.md) [Two](two/guide.md)",file))
        assertNull(MessageLinkDestination.originalDocumentReference("[Web](https://example.com/guide.md)",file))
        assertNull(MessageLinkDestination.originalDocumentReference("[Other](docs/other.md)",file))
        assertTrue(MessageLinkDestination.documentReference("C:\\docs\\guide.md"))
        val easywork=com.agenthub.core.TargetScope("easywork","authority","one","host","workspace","runtime")
        assertNotEquals(ThreadReadingIdentity.key(easywork,"thread"),ThreadReadingIdentity.key(com.agenthub.core.TargetScope("easywork","authority","two","host","workspace","runtime"),"thread"))
        val codex=com.agenthub.core.TargetScope("codex","authority","one","host","workspace","runtime")
        assertEquals(ThreadReadingIdentity.key(codex,"thread"),ThreadReadingIdentity.key(com.agenthub.core.TargetScope("codex","authority","two","host","workspace","runtime"),"thread"))
    }
    @Test fun readingIdentitySurvivesReparseButSeparatesTargetMessageContentAndChild() {
        val original=source()
        assertEquals(PreviewReadingIdentity.attachment(original),PreviewReadingIdentity.attachment(original.copy(id="fresh-resource")))
        assertNotEquals(PreviewReadingIdentity.attachment(original),PreviewReadingIdentity.attachment(original.copy(sha256="b".repeat(64))))
        val first=PreviewReadingIdentity.message("target","message","docs/guide.md")
        assertEquals(first,PreviewReadingIdentity.message("target","message","docs/guide.md"))
        assertNotEquals(first,PreviewReadingIdentity.message("other-target","message","docs/guide.md"))
        assertNotEquals(first,PreviewReadingIdentity.message("target","other-message","docs/guide.md"))
        assertNotEquals(PreviewReadingIdentity.content(first,digest),PreviewReadingIdentity.content(first,"b".repeat(64)))
        assertEquals(PreviewReadingIdentity.child(first,"child.md#a"),PreviewReadingIdentity.child(first,"child.md#b"))
        assertNotEquals(PreviewReadingIdentity.child(first,"one.md"),PreviewReadingIdentity.child(first,"two.md"))
    }
}
