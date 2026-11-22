package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Exercises the production owner resolver and session download/document chain; no network/model. */
class EasyWorkSubagentHistoryResourceOwnerTest {
    @Test fun mainPreviewSurvivesStatusAndTextButNotResourceOrOwnerChanges() {
        val file=MobileFile("resource","file.md","text/markdown",3,"host","parent","a".repeat(64))
        val original=MobileMessage("row","assistant","original",replyMessageId="original-message",attachments=listOf(file))
        val owner=MobileSession.MessageLinkContext("target",1,2,original,"parent")
        val updated=original.copy(text="updated",status="completed")
        fun resolve(rows: List<MobileMessage>,key: String="target",selection: Long=1,connection: Long=2,thread: String="parent",immutable: Boolean=true)=
            MessageResourceOwnerResolver.resolve(owner,key,selection,connection,thread,rows,immutable)
        assertEquals(updated,resolve(listOf(updated))!!.message)
        assertNull(resolve(listOf(updated),immutable=false))
        assertNull(resolve(listOf(updated.copy(attachments=listOf(file.copy(sha256="b".repeat(64)))))))
        assertNull(resolve(listOf(updated.copy(replyMessageId="different-original"))))
        assertNull(resolve(emptyList()));assertNull(resolve(listOf(updated),key="other"))
        assertNull(resolve(listOf(updated),selection=3));assertNull(resolve(listOf(updated),connection=4))
        assertNull(resolve(listOf(updated),thread="other"))
        assertEquals(MessageResourceOwnerResolver.readingIdentity(owner,file),MessageResourceOwnerResolver.readingIdentity(owner.copy(message=updated),file))
        assertNotEquals(MessageResourceOwnerResolver.readingIdentity(owner,file),MessageResourceOwnerResolver.readingIdentity(owner,file.copy(threadId="child")))
    }

    @Test fun downloadedChildCanPreviewRetryAndResolveDocumentUntilOriginalPageRetires()=runBlocking {
        withContext(Dispatchers.Main) {
            val content="# Original\n".toByteArray();val hash=easyHash(content)
            val cache=Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),"r2-owner-").toFile()
            var documentCalls=0;var downloads=0
            val commands=object: AgentCommands by NativeCodexCommands({ error("Network forbidden") }) {
                override suspend fun downloadResourceWithProgress(client: MobileClient,file: MobileFile,cache: java.io.File,progress: ResourceDownloadProgressHandler?): ResourceDownloadSnapshot {
                    downloads++;val local=java.io.File(cache,file.id+".md");local.writeBytes(content)
                    return ResourceDownloadSnapshot(file,local)
                }
                override suspend fun resolveDocumentFile(client: MobileClient,file: MobileFile,reference: String,sourceSha256: String): MobileFile {
                    documentCalls++;assertEquals("child-resource-thread",file.threadId);assertEquals(hash,sourceSha256)
                    return file.copy(id="linked",name="linked.md")
                }
            }
            val session=MobileSession(commandProvider=commands)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val host=session.selectedClient.id
            val file=MobileFile("source","source.md","text/markdown",content.size.toLong(),host,"child-resource-thread",hash)
            val message=MobileMessage("child-row:0","assistant","Original",replyMessageId="original-child-message",attachments=listOf(file))
            val target=TargetScope("codex",null,null,host,"workspace","runtime")
            fun page(child: String="child",cursor: String?="earlier")=MobileSubagentHistory(host,target,"parent","activity",
                listOf(MobileSubagentChild("child","Child","completed"),MobileSubagentChild("other","Other","completed")),child,"saved-history",listOf(message),cursor)
            var parentCurrent=true;var incoming=page()
            val model=NativeSubagentHistoryModel(scope,{parentCurrent}) { _,_->incoming }
            try {
                model.refresh()
                val owner=MessageResourceOwnerResolver.captureChild(session.messageLinkContext(message).copy(resourceThread=file.threadId),model,model.page) {parentCurrent}
                assertTrue(session.messages.none {it.id==message.id})
                val source=session.prepareMessageResource(owner,"codex-attachment://open?id=source",cache)
                // These are the SAME resolver entrypoints used by NativeResourcePreview current/retry/resolveDocument.
                val previewOwner=checkNotNull(session.resolveMessageResourceOwner(owner))
                assertSame(owner,previewOwner)
                val retry=session.prepareMessageResource(previewOwner,"codex-attachment://open?id=source",cache)
                assertEquals(source.readingIdentity,retry.readingIdentity)
                val targetKey=session.selectedClient.scope?.let {ThreadReadingIdentity.key(it,file.threadId)} ?: (session.selectedClient.id+":"+file.threadId)
                assertEquals(PreviewReadingIdentity.message(targetKey,"original-child-message",PreviewReadingIdentity.attachment(file)),source.readingIdentity)
                val linked=session.prepareDocumentResource(previewOwner,source,"linked.md",hash,cache)
                assertEquals("linked",linked.sourceFile!!.id);assertEquals(1,documentCalls);assertEquals(3,downloads)
                // No parent-message fallback even if a colliding row would otherwise be found.
                parentCurrent=false
                assertNull(MessageResourceOwnerResolver.resolve(owner,owner.key,owner.selection,owner.connection,"parent",listOf(message),true))
                assertTrue(runCatching {session.prepareDocumentResource(owner,source,"linked.md",hash,cache)}.isFailure)
                parentCurrent=true
                assertNotNull(session.resolveMessageResourceOwner(owner))
                incoming=page(cursor=null);model.earlier()
                assertNull(session.resolveMessageResourceOwner(owner))
                assertTrue(runCatching {session.prepareMessageResource(owner,"codex-attachment://open?id=source",cache)}.isFailure)
                assertEquals(1,documentCalls);assertEquals(3,downloads)
                val nextOwner=MessageResourceOwnerResolver.captureChild(session.messageLinkContext(message).copy(resourceThread=file.threadId),model,model.page) {true}
                incoming=page("other",null);model.choose("other")
                assertNull(session.resolveMessageResourceOwner(nextOwner))
                val closeOwner=MessageResourceOwnerResolver.captureChild(session.messageLinkContext(message).copy(resourceThread=file.threadId),model,model.page) {true}
                model.close();assertNull(session.resolveMessageResourceOwner(closeOwner))
            } finally {model.close();scope.cancel();session.leaveForeground();cache.deleteRecursively()}
        }
    }
}
