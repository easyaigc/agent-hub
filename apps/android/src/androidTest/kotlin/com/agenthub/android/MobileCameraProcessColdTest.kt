package com.agenthub.android

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Seed exits before the external camera; later reads never launch or replay an operation. */
class MobileCameraProcessColdTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val nonce get()=checkNotNull(args.getString("cameraColdNonce")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
    private val seed get()=File(context.filesDir,"camera-cold-$nonce-seed.json")
    private fun scope() { require(Build.HARDWARE in listOf("ranchu","goldfish"));require(context.packageName=="com.easyai.agenthub.android.acceptance.cameras") }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun waitFor(condition: ()->Boolean)=runBlocking { withTimeout(30_000) { while(!main { condition() })delay(100) } }
    @Test fun seedFreshOriginalCameraDraftWithoutOpeningCamera() {
        scope();val store=NativeSessionStorage(context);require(store.metadata()==null&&!seed.exists()) { "Original isolated draft must be reconciled, never reseeded" }
        val endpoint=checkNotNull(args.getString("cameraColdEndpoint"));require(endpoint.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{4,5}")))
        val session=main { MobileSession(store).also { it.resetAcceptance();it.changeLanguage("en");it.changeAppearance("light") } }
        main { session.connect("Camera cold fixture",endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="camera-original-$nonce 100% %@" }
        waitFor { session.canCompose&&!session.historyLoading }
        main { session.addAttachment("existing-$nonce.txt","text/plain","camera-existing-$nonce\n".toByteArray()) }
        val original=main { session.attachmentImportDestination() };assertTrue(main { session.confirmAttachmentOrigin(original) })
        main { session.selectThread(session.threads.first { it.id=="second" });session.text="camera-second-$nonce";session.selectThread(session.threads.first { it.id=="first" }) };waitFor { !session.historyLoading }
        val authorization=NativePhotoAuthorization(store.child(context,"photo-permission"),NativeRecentPhotos(context).permissions())
        main { authorization.claim("camera-cold-fixture",automatic=true);authorization.complete();session.leaveForeground() }
        runBlocking { store.awaitQueuedWrites() };assertTrue(session.operationRecords.isEmpty())
        seed.writeText(JSONObject().put("version",1).put("nonce",nonce).put("writerPid",Process.myPid()).put("key",original.key).put("generation",original.generation)
            .put("metadata",checkNotNull(store.metadata())).put("syntheticPriorGalleryAttempt",true).put("galleryPermissionEvidence",false).put("modelInputs",0).toString(2))
    }
    @Test fun readOriginalCameraResultAndPrivateOutputWithoutOpeningOrSending() {
        scope();val before=JSONObject(seed.readText());assertNotEquals(before.getInt("writerPid"),Process.myPid())
        val observed=JSONObject(File(context.filesDir,"camera-cold-$nonce-observed.json").readText());assertEquals("passed",observed.getString("status"))
        val id=observed.getString("requestId");val store=NativeSessionStorage(context);val metadata=checkNotNull(store.metadata());val prior=before.getJSONObject("metadata");val key=before.getString("key")
        for(name in listOf("drafts","generation","generationPreferences","language","appearance","attachmentImportGenerations"))assertEquals(name,prior.get(name).toString(),metadata.get(name).toString())
        val camera=store.child(context,"camera-results");val record=runBlocking { NativeDocumentJournal(camera).restore().single() }
        assertEquals(id,record.id);assertEquals("consumed",record.phase);assertEquals(key,record.destination.key);assertEquals(before.getString("generation"),record.destination.generation)
        val output=NativeCameraOutputs(context,camera.child(context,"outputs")).output(id)
        assertEquals(listOf(output.uri.toString()),record.sources);assertFalse(output.file.exists());assertNull(camera.child(context,"outputs").read("output-v1-$id"))
        val cameraUid=observed.getInt("cameraUid")
        for(flag in listOf(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,context.checkUriPermission(output.uri,-1,cameraUid,flag))
        val files=metadata.getJSONObject("draftFiles");assertEquals(prior.getJSONObject("draftFiles").keys().asSequence().toSet(),files.keys().asSequence().toSet())
        for(other in files.keys())if(other!=key)assertEquals(prior.getJSONObject("draftFiles").get(other).toString(),files.get(other).toString())
        val rows=files.getJSONArray(key);assertEquals(2,rows.length());val first=rows.getJSONObject(0);val image=rows.getJSONObject(1)
        fun manifest(row: JSONObject)=CreationFile(row.getString("id"),row.getString("name"),row.getString("mediaType"),row.getLong("sizeBytes"),row.getString("sha256"))
        assertEquals(prior.getJSONObject("draftFiles").getJSONArray(key).getJSONObject(0).toString(),first.toString())
        assertArrayEquals("camera-existing-$nonce\n".toByteArray(),runBlocking { NativeDraftFiles(store).bytes(manifest(first)) })
        assertEquals("photo-$id.jpg",image.getString("name"));assertEquals("image/jpeg",image.getString("mediaType"))
        val bytes=runBlocking { NativeDraftFiles(store).bytes(manifest(image)) };assertEquals(image.getString("sha256"),hash(bytes))
        val bitmap=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size));val width=bitmap.width;val height=bitmap.height
        try { assertTrue(width>0&&height>0) }finally { bitmap.recycle() }
        val batch=runBlocking { NativeAttachmentInboxStore(camera.child(context,"payloads")).snapshot().records.single() };assertEquals(id,batch.id);assertEquals("consumed",batch.phase);assertTrue(batch.slots.isEmpty())
        val phase=args.getString("cameraColdReadPhase")?:"returned";require(phase in listOf("returned","reopened"))
        if(phase=="reopened") {
            val original=JSONObject(File(context.filesDir,"camera-cold-$nonce-returned.json").readText())
            assertEquals(original.getString("requestId"),id);assertEquals(original.getString("fileId"),image.getString("id"));assertEquals(original.getString("fileSha256"),hash(bytes))
        }
        File(context.filesDir,"camera-cold-$nonce-$phase.json").writeText(JSONObject().put("status","passed").put("readerPid",Process.myPid()).put("requestId",id)
            .put("originalKeyGenerationTextSettingsAndOtherDraftUnchanged",true).put("oneOriginalCameraPhoto",true).put("fileId",image.getString("id")).put("fileSha256",hash(bytes))
            .put("width",width).put("height",height).put("payloadConsumed",true).put("ownedRawOutputAndClaimRemoved",true).put("originalCameraUriReadWriteGrantsRevoked",true).put("modelInputs",0).put("cameraLaunches",0).toString(2))
    }
    @Test fun inspectOriginalCameraLocalReceiptWithoutResumingOrOpening() {
        scope();val store=NativeSessionStorage(context);val camera=store.child(context,"camera-results")
        val record=runBlocking { NativeDocumentJournal(camera).restore().single() }
        val batch=runBlocking { NativeAttachmentInboxStore(camera.child(context,"payloads")).snapshot().records.singleOrNull() }
        val output=NativeCameraOutputs(context,camera.child(context,"outputs")).output(record.id)
        File(context.filesDir,"camera-cold-$nonce-inspected.json").writeText(JSONObject().put("phase",record.phase).put("requestId",record.id)
            .put("sourceCount",record.sources.size).put("rawOutputExists",output.file.exists()).put("rawOutputBytes",if(output.file.exists())output.file.length() else 0)
            .put("payloadPhase",batch?.phase).put("payloadFiles",batch?.readyFiles?.size).put("parentFileCount",store.metadata()?.optJSONObject("draftFiles")?.optJSONArray(record.destination.key)?.length())
            .put("cameraLaunches",0).put("modelInputs",0).toString(2))
    }
}
