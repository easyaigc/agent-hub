package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** New-delta boundaries only. Synthetic projections do not certify real desktop/native interaction. */
class NativeRemainingMediaProjectionTest {
    private fun event(type: String)=JSONObject().put("type",type).put("compressionOperationId","op")
    private fun entry(event: JSONObject)=JSONObject().put("createdAt","2026-11-21T00:00:00Z").put("event",event)
    private fun snapshot(latest: JSONObject?,pending: JSONArray=JSONArray())=JSONObject()
        .put("thread",JSONObject().put("id","t").put("branchRevision",3))
        .put("activeRun",JSONObject().put("threadId","t").put("runId","r").put("branchRevision",3))
        .put("contextCompressionState",JSONObject().put("threadId","t").put("runId","r").put("branchRevision",3)
            .put("cursor",JSONObject().put("source","protocol").put("sequence",5)).put("pending",pending).put("latestEvent",latest))
    @Test fun compressionWatermarkReplacesStartedAtSamePositionAndRejectsLateBranch() {
        val old=MobileMessage("before","assistant","body")
        val tail=MobileMessage("after","assistant","body")
        val started=EasyWorkCompressionProjection.applying(snapshot(null,JSONArray().put(entry(event("context_compression_started")))),"t",3,listOf(old)).last()
        val completed=entry(event("context_compression_block_created").put("blockRef","block").put("logicalStatus","installed").put("persistenceState","durable").put("generationStatus","failed"))
        val result=EasyWorkCompressionProjection.applying(snapshot(completed),"t",3,listOf(old,started,tail))
        assertEquals(listOf("before",started.id,"after"),result.map { it.id })
        assertEquals("completed",result[1].status) // structural fallback is installed even if generation failed
        val late=snapshot(completed);late.getJSONObject("contextCompressionState").put("branchRevision",2)
        assertEquals(result,EasyWorkCompressionProjection.applying(late,"t",3,result))
        val wrongRun=snapshot(completed);wrongRun.getJSONObject("contextCompressionState").put("runId","other")
        assertEquals(result,EasyWorkCompressionProjection.applying(wrongRun,"t",3,result))
    }
    @Test fun compressionFailureUnknownAndEmptyPendingAreAuthoritative() {
        assertEquals("failed",EasyWorkCompressionProjection.status(event("context_compression_block_created").put("blockRef","b").put("persistenceState","degraded")))
        assertEquals("unknown",EasyWorkCompressionProjection.status(event("context_compression_block_created")))
        assertEquals("cancelled",EasyWorkCompressionProjection.status(event("context_compression_block_rejected").put("status","cancelled")))
        assertEquals("failed",EasyWorkCompressionProjection.status(event("context_compression_block_rejected")))
        val started=EasyWorkCompressionProjection.applying(snapshot(null,JSONArray().put(entry(event("context_compression_started")))),"t",3,emptyList())
        assertEquals(1,started.size)
        assertTrue(EasyWorkCompressionProjection.applying(snapshot(entry(event("context_compression_started"))),"t",3,started).isEmpty())
        val malformed=snapshot(null,JSONArray().put(entry(event("context_compression_started"))).put(entry(event("context_compression_started"))))
        assertEquals(started,EasyWorkCompressionProjection.applying(malformed,"t",3,started))
    }
    private fun run(actual: String?="fast",requested: String="fast",status: String="completed")=JSONObject().put("id","r").put("status",status)
        .put("settings",JSONObject().put("speed",requested)).put("generationSpeed",JSONObject().put("version",1).put("runId","r").put("requested",requested).put("actual",actual).put("status","reported"))
    @Test fun recordedRequestedAndActualAreIndependentAndMissingConflictingReportsStayUnknown() {
        assertEquals("fast",EasyWorkTaskSpeed.project(run())?.actual)
        val slower=EasyWorkTaskSpeed.project(run("standard"))!!
        assertEquals("fast",slower.requested);assertEquals("standard",slower.actual)
        assertNull(EasyWorkTaskSpeed.project(run(null))?.actual)
        assertNull(EasyWorkTaskSpeed.project(run(status="running"))?.actual)
        val wrong=run();wrong.getJSONObject("generationSpeed").put("runId","another")
        assertNull(EasyWorkTaskSpeed.project(wrong)?.actual)
        val mismatch=run();mismatch.getJSONObject("generationSpeed").put("requested","ultrafast")
        assertNull(EasyWorkTaskSpeed.project(mismatch)?.actual)
        assertNull(EasyWorkTaskSpeed.project(JSONObject().put("id","legacy")))
        assertEquals(slower,EasyWorkTaskSpeed.decode(JSONObject(slower.json().toString())))
    }
    @Test fun lateThumbnailCannotPublishAfterCloseOwnerChangeOrSupersededPreview() = runBlocking {
        for(change in listOf("close","owner","view","cancel")) {
            var owner=Any();val source=owner
            var view=Any();val opened=view
            var open=true;var publications=0
            val started=CompletableDeferred<Unit>();val finish=CountDownLatch(1)
            val job=launch {
                NativeMessageMediaThumbnails.publishOpened(
                    decode={ started.complete(Unit);check(finish.await(5,TimeUnit.SECONDS));"bitmap" },
                    current={ open&&owner===source&&view===opened },publish={ publications++ })
            }
            try {
                withTimeout(5000) { started.await() }
                when(change) { "close"->open=false;"owner"->owner=Any();"view"->view=Any();else->job.cancel() }
            } finally { finish.countDown() }
            job.join()
            assertEquals(change,0,publications)
        }
    }
    @Test fun thumbnailFailureIsOptionalAndCurrentCompletionPublishesOnce() = runBlocking {
        var publications=0
        NativeMessageMediaThumbnails.publishOpened<String>(decode={ throw OutOfMemoryError("synthetic decode failure") },current={ true },publish={ publications++ })
        NativeMessageMediaThumbnails.publishOpened<String>(decode={ throw IllegalArgumentException("bad image") },current={ true },publish={ publications++ })
        NativeMessageMediaThumbnails.publishOpened<String>(decode={ null },current={ true },publish={ publications++ })
        assertEquals(0,publications)
        NativeMessageMediaThumbnails.publishOpened(decode={ "bitmap" },current={ true },publish={ publications++ })
        assertEquals(1,publications)
        NativeMessageMediaThumbnails.publishOpened(decode={ error("closed before decode") },current={ false },publish={ _: String -> publications++ })
        assertEquals(1,publications)
    }
    @Test fun runtimeDecoderKeepsObservationInWireAndIgnoresMalformedOptionalExtension() {
        val raw=JSONObject().put("state","idle").put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())
            .put("generationSpeed",EasyWorkTaskSpeed.project(run("standard"))!!.json())
        assertEquals("standard",MobileRuntime.decode(JSONObject(raw.toString()),"h","t").generationSpeed?.actual)
        raw.put("generationSpeed","future extension")
        assertNull(MobileRuntime.decode(raw,"h","t").generationSpeed)
    }
    @Test fun mediaTypesUseDestinationsAndImageAllocationIsBounded() {
        assertEquals(NativeMessageMediaKind.Video,NativeMessageMedia.kind("/media/movie.MP4?x=1"))
        assertEquals(NativeMessageMediaKind.Audio,NativeMessageMedia.kind("/media/audio.mp3"))
        assertEquals(NativeMessageMediaKind.Image,NativeMessageMedia.kind("/media/photo.png"))
        assertNull(NativeMessageMedia.kind("https://user:pass@example.invalid/p.png"))
        assertNull(NativeMessageMedia.kind("javascript:alert(1)"))
        val doc=NativeMarkdown.parse("`![code](/private/no.png)`\n\n![photo](/media/photo.png) ![movie](/media/movie.mp4)")
        assertEquals(2,NativeMessageMedia.images(doc).size)
        assertNull(NativeMessageMediaThumbnails.sample(0,100))
        assertNull(NativeMessageMediaThumbnails.sample(32768,32768))
        for((w,h) in listOf(640 to 640,641 to 100,9999 to 9999,32768 to 1)) {
            val sample=NativeMessageMediaThumbnails.sample(w,h)!!
            assertTrue((maxOf(w,h)+sample-1)/sample<=640)
        }
    }
}
