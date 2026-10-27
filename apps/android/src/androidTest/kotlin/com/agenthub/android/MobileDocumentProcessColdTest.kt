package com.agenthub.android

import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Independent phases: seed exits before the real system chooser; verification never launches it. */
class MobileDocumentProcessColdTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val nonce get()=checkNotNull(args.getString("documentNonce")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
    private val proof get()=File(context.filesDir,"document-cold-$nonce-seed.json")
    private fun scope() { require(Build.HARDWARE in listOf("ranchu","goldfish"));require(context.packageName=="com.easyai.agenthub.android.acceptance.documents") }
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun waitFor(condition: ()->Boolean) { runBlocking { withTimeout(30_000) { while(!main { condition() })delay(100) } } }
    @Test fun seedFreshIsolatedOriginalDraftBeforeSystemChooser() {
        scope();val store=NativeSessionStorage(context);require(store.metadata()==null&&!proof.exists()) { "Existing isolated draft must be reconciled, never reseeded" }
        val endpoint=checkNotNull(args.getString("documentEndpoint"));require(endpoint.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{4,5}")))
        val session=main { MobileSession(store).also { it.resetAcceptance();it.changeLanguage("en");it.changeAppearance("light") } }
        main { session.connect("Document cold fixture",endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="original-$nonce 100% %@" }
        waitFor { session.canCompose&&!session.historyLoading }
        main { session.addAttachment("existing-$nonce.txt","text/plain","existing-$nonce\n".toByteArray()) }
        val original=main { session.attachmentImportDestination() };assertTrue(main { session.confirmAttachmentOrigin(original) })
        main { session.selectThread(session.threads.first { it.id=="second" });session.text="second-$nonce";session.selectThread(session.threads.first { it.id=="first" }) }
        waitFor { !session.historyLoading }
        // Synthetic prior attempt only excludes unrelated permission UI. It is not permission evidence.
        val authorization=NativePhotoAuthorization(store.child(context,"photo-permission"),NativeRecentPhotos(context).permissions())
        main { authorization.claim("document-fixture",automatic=true);authorization.complete();session.leaveForeground() }
        runBlocking { store.awaitQueuedWrites() };val metadata=checkNotNull(store.metadata())
        assertTrue(session.operationRecords.isEmpty())
        proof.writeText(JSONObject().put("version",1).put("nonce",nonce).put("writerPid",Process.myPid()).put("key",original.key).put("generation",original.generation)
            .put("metadata",metadata).put("priorReadGrants",org.json.JSONArray(context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }))
            .put("syntheticPriorPhotoAttempt",true).put("modelInputs",0).toString(2))
    }
    @Test fun readOriginalConsumedResultWithoutLaunchingOrSending() {
        scope();val seed=JSONObject(proof.readText());assertNotEquals(seed.getInt("writerPid"),Process.myPid())
        val store=NativeSessionStorage(context);val metadata=checkNotNull(store.metadata());val before=seed.getJSONObject("metadata");val key=seed.getString("key")
        for(name in listOf("drafts","generation","generationPreferences","language","appearance","attachmentImportGenerations"))assertEquals(name,before.get(name).toString(),metadata.get(name).toString())
        val journal=NativeDocumentJournal(store.child(context,"document-results"));val record=runBlocking { journal.restore().single() }
        assertEquals("consumed",record.phase);assertEquals(key,record.destination.key);assertEquals(seed.getString("generation"),record.destination.generation)
        assertEquals(listOf("content://${context.packageName}.test.owned-documents/document/document-cold-$nonce.txt"),record.sources)
        val rows=metadata.getJSONObject("draftFiles").getJSONArray(key);assertEquals(2,rows.length())
        val names=mutableListOf<String>();val hashes=JSONObject();val fileIds=JSONObject()
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index);val name=row.getString("name");names+=name
            val manifest=CreationFile(row.getString("id"),name,row.getString("mediaType"),row.getLong("sizeBytes"),row.getString("sha256"))
            assertEquals("text/plain",manifest.mediaType)
            val bytes=runBlocking { NativeDraftFiles(store).bytes(manifest) }
            val expected=if(index==0)"existing-$nonce\n" else "ANDROID_DOCUMENT_PROCESS_COLD_$nonce\n原文件 100% %@\n"
            assertArrayEquals(expected.toByteArray(),bytes);hashes.put(name,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) });fileIds.put(name,manifest.id)
        }
        assertEquals(listOf("existing-$nonce.txt","document-cold-$nonce.txt"),names)
        val payload=runBlocking { NativeAttachmentInboxStore(store.child(context,"document-results").child(context,"payloads")).snapshot().records.single() }
        assertEquals(record.id,payload.id);assertEquals("consumed",payload.phase);assertTrue(payload.readyFiles.isEmpty())
        val grants=context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.sorted()
        val previous=seed.getJSONArray("priorReadGrants");assertEquals((0 until previous.length()).map { previous.getString(it) }.sorted(),grants)
        val phase=args.getString("documentReadPhase")?:"returned"
        require(phase in listOf("returned","reopened"))
        if(phase=="reopened") {
            val returned=JSONObject(File(context.filesDir,"document-cold-$nonce-returned.json").readText())
            assertEquals(returned.getString("requestId"),record.id)
            assertEquals(returned.getJSONObject("hashes").toString(),hashes.toString())
            assertEquals(returned.getJSONObject("fileIds").toString(),fileIds.toString())
        }
        File(context.filesDir,"document-cold-$nonce-$phase.json").writeText(JSONObject().put("status","passed").put("readerPid",Process.myPid()).put("requestId",record.id)
            .put("originalKeyAndGeneration",true).put("draftTextAndSettingsUnchanged",true).put("oneExistingAndOneOriginalResult",true).put("hashes",hashes).put("fileIds",fileIds)
            .put("payloadConsumed",true).put("readGrantsRestored",true).put("modelInputs",0).put("pickerLaunches",0).toString(2))
    }
}
