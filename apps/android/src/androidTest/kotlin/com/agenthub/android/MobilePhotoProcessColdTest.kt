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
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Separate seed/read phases around an uninstrumented recipient and an independent system-picker driver. */
class MobilePhotoProcessColdTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val nonce get()=checkNotNull(args.getString("photoColdNonce")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
    private val seed get()=File(context.filesDir,"photo-cold-$nonce-seed.json")
    private fun scope() { require(Build.HARDWARE in listOf("ranchu","goldfish"));require(context.packageName=="com.easyai.agenthub.android.acceptance.photos") }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun waitFor(condition: ()->Boolean) { runBlocking { withTimeout(30_000) { while(!main { condition() })delay(100) } } }
    @Test fun seedFreshIsolatedOriginalPhotoDraft() {
        scope();val store=NativeSessionStorage(context);require(store.metadata()==null&&!seed.exists()) { "Existing recipient must be reconciled, never reseeded" }
        val source=JSONObject(File(context.filesDir,"photo-cold-$nonce-source.json").readText())
        require(source.getString("status")=="prepared"&&source.getString("nonce")==nonce&&source.getString("sourcePackage")==context.packageName+".test")
        assertNotEquals(Process.myUid(),source.getInt("sourceUid"));assertFalse(source.getBoolean("photoUriExplicitlyGranted"));require(source.getString("pixelSha256").matches(Regex("[a-f0-9]{64}")))
        val endpoint=checkNotNull(args.getString("photoColdEndpoint"));require(endpoint.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{4,5}")))
        val session=main { MobileSession(store).also { it.resetAcceptance();it.changeLanguage("en");it.changeAppearance("light") } }
        main { session.connect("Photo cold fixture",endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="photo-original-$nonce 100% %@" }
        waitFor { session.canCompose&&!session.historyLoading }
        main { session.addAttachment("existing-$nonce.txt","text/plain","photo-existing-$nonce\n".toByteArray()) }
        val original=main { session.attachmentImportDestination() };assertTrue(main { session.confirmAttachmentOrigin(original) })
        main { session.selectThread(session.threads.first { it.id=="second" });session.text="photo-second-$nonce";session.selectThread(session.threads.first { it.id=="first" }) }
        waitFor { !session.historyLoading }
        val authorization=NativePhotoAuthorization(store.child(context,"photo-permission"),NativeRecentPhotos(context).permissions())
        main { authorization.claim("photo-cold-fixture",automatic=true);authorization.complete();session.leaveForeground() }
        runBlocking { store.awaitQueuedWrites() };assertTrue(session.operationRecords.isEmpty())
        seed.writeText(JSONObject().put("version",1).put("nonce",nonce).put("writerPid",Process.myPid()).put("key",original.key).put("generation",original.generation)
            .put("metadata",checkNotNull(store.metadata())).put("source",source).put("priorReadGrants",org.json.JSONArray(context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }))
            .put("syntheticPriorPhotoAttempt",true).put("modelInputs",0).toString(2))
    }
    @Test fun readOriginalNormalizedPhotoResultWithoutOpeningOrSending() {
        scope();val before=JSONObject(seed.readText());assertNotEquals(before.getInt("writerPid"),Process.myPid());val source=before.getJSONObject("source")
        val store=NativeSessionStorage(context);val metadata=checkNotNull(store.metadata());val prior=before.getJSONObject("metadata");val key=before.getString("key")
        for(name in listOf("drafts","generation","generationPreferences","language","appearance","attachmentImportGenerations"))assertEquals(name,prior.get(name).toString(),metadata.get(name).toString())
        val record=runBlocking { NativeDocumentJournal(store.child(context,"photo-results")).restore().single() }
        assertEquals("consumed",record.phase);assertEquals(key,record.destination.key);assertEquals(before.getString("generation"),record.destination.generation);assertEquals(1,record.sources.size)
        val rows=metadata.getJSONObject("draftFiles").getJSONArray(key);assertEquals(2,rows.length());val first=rows.getJSONObject(0);val image=rows.getJSONObject(1)
        fun manifest(row: JSONObject)=CreationFile(row.getString("id"),row.getString("name"),row.getString("mediaType"),row.getLong("sizeBytes"),row.getString("sha256"))
        assertEquals("existing-$nonce.txt",first.getString("name"));assertArrayEquals("photo-existing-$nonce\n".toByteArray(),runBlocking { NativeDraftFiles(store).bytes(manifest(first)) })
        val privacyName=android.net.Uri.parse(source.getString("uri")).lastPathSegment+".png"
        assertTrue(image.getString("name") in listOf(source.getString("fileName"),privacyName));assertEquals("image/png",image.getString("mediaType"))
        val bytes=runBlocking { NativeDraftFiles(store).bytes(manifest(image)) };assertEquals(image.getString("sha256"),hash(bytes));val bitmap=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        try {
            assertEquals(source.getInt("width"),bitmap.width);assertEquals(source.getInt("height"),bitmap.height)
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);val buffer=ByteBuffer.allocate(pixels.size*4);pixels.forEach(buffer::putInt)
            assertEquals(source.getString("pixelSha256"),hash(buffer.array()))
        }finally { bitmap.recycle() }
        val batch=runBlocking { NativeAttachmentInboxStore(store.child(context,"photo-results").child(context,"payloads")).snapshot().records.single() }
        assertEquals(record.id,batch.id);assertEquals("consumed",batch.phase);assertTrue(batch.slots.isEmpty())
        val grants=before.getJSONArray("priorReadGrants");assertEquals((0 until grants.length()).map { grants.getString(it) }.sorted(),context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.sorted())
        val phase=args.getString("photoColdReadPhase")?:"returned";require(phase in listOf("returned","reopened"))
        if(phase=="reopened") {
            val original=JSONObject(File(context.filesDir,"photo-cold-$nonce-returned.json").readText())
            assertEquals(original.getString("requestId"),record.id);assertEquals(original.getString("fileId"),image.getString("id"));assertEquals(original.getString("fileSha256"),hash(bytes))
        }
        File(context.filesDir,"photo-cold-$nonce-$phase.json").writeText(JSONObject().put("status","passed").put("readerPid",Process.myPid()).put("requestId",record.id)
            .put("originalKeyAndGeneration",true).put("originalTextSettingsAndExistingFileUnchanged",true).put("normalizedPixelsMatchIndependentSource",true).put("oneOriginalPhoto",true)
            .put("fileId",image.getString("id")).put("fileSha256",hash(bytes)).put("payloadConsumed",true).put("readGrantsRestored",true).put("modelInputs",0).put("pickerLaunches",0).toString(2))
    }
}
