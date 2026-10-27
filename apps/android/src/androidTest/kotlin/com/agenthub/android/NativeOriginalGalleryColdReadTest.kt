package com.agenthub.android

import android.graphics.BitmapFactory
import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Read only one original sealed two-image input after its native process has ended. No command interface. */
class NativeOriginalGalleryColdReadTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun originalUnknownIntentPhotoOriginsAndEncryptedFilesSurviveFreshProcessWithoutReplay()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val name=InstrumentationRegistry.getArguments().getString("originalGalleryFixture")?:error("Original gallery fixture required")
        require(name.matches(Regex("gallery-original-[a-f0-9]{16}\\.json")))
        val file=File(ui.activity.filesDir,"acceptance/$name");require(file.length() in 1..100_000)
        val fixture=JSONObject(file.readText());require(fixture.getInt("version")==1&&fixture.getBoolean("originalProcessEnded"))
        val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        ui.waitUntil(30_000){!ui.activity.session.restoring};lateinit var owned: OwnedControlAcceptance
        ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"original-gallery-cold")}
        try {
            val original=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val before=tree(original)
            val expected=fixture.getJSONObject("record");val operationId=expected.getString("id")
            val record=NativeOperationJournal(original).records(expected.getString("target")).single {it.id==operationId}
            assertEquals("creation",record.kind);assertEquals("outcome-unknown",record.phase);assertEquals(expected.getString("intent"),record.intent)
            assertEquals(expected.getString("intentSha256"),record.intentSha256);assertEquals(expected.getString("sourceKey"),record.sourceKey);assertEquals(expected.getString("sourceVersion"),record.sourceVersion)
            assertTrue(record.submissionAttempted);assertFalse(record.detached)
            val input=JSONObject(record.intent);val source=JSONObject(original.read("submission:$operationId")?:error("Original submission remains required"))
            assertEquals(input.getString("hostId"),source.getString("hostId"));assertEquals(record.sourceKey,source.getString("sourceKey"));assertEquals(record.sourceVersion,source.getString("sourceVersion"))
            val manifests=source.getJSONArray("files");assertEquals(2,manifests.length());assertEquals(input.getJSONArray("attachments").toString(),manifests.toString())
            val expectedSources=fixture.getJSONArray("photoSourceIds");val expectedPixels=fixture.getJSONArray("pixelSha256s");assertEquals(2,expectedSources.length());assertEquals(2,expectedPixels.length())
            val files=(0 until manifests.length()).map {index->
                val value=manifests.getJSONObject(index);CodexCommandCodec.validate(value,"creationFile")
                val local=NativePhotoOrigins.attach(LocalDraftFile(CreationFile(value.getString("id"),value.getString("name"),value.getString("mediaType"),value.getLong("sizeBytes"),value.getString("sha256"))),source.optJSONObject("photoSources"))
                assertEquals(expectedSources.getString(index),local.photoSourceId)
                val bytes=NativeDraftFiles(original).bytes(local.manifest);assertEquals(local.manifest.sha256,hash(bytes))
                val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                try {val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height);val buffer=ByteBuffer.allocate(pixels.size*4);pixels.forEach(buffer::putInt);assertEquals(expectedPixels.getString(index),hash(buffer.array()))}finally{image.recycle()}
                local
            }
            NativePhotoOrigins.validate(source.optJSONObject("photoSources"),files)
            assertEquals(before,tree(original));assertTrue(owned.session.operationRecords.isEmpty())
            File(owned.folder,"original-gallery-cold-read-proof.json").writeText(JSONObject().put("freshProcessAfterOriginalExit",true).put("readerPid",Process.myPid()).put("originalIntentAndFingerprintPreserved",true)
                .put("originalUnknownReceiptPreserved",true).put("originalPhotoSourceOrderPreserved",true).put("originalTwoFileBytesAndPixelsPreserved",true).put("allOriginalEncryptedBytesUnchanged",true)
                .put("originalEncryptedFileCount",before.size).put("sourceUriReads",0).put("uploadOrSubmit",false).toString())
        } finally {ui.runOnIdle {owned.session.leaveForeground();owned.verifyDefault()}}
    }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->root.walkTopDown().filter{it.isFile}.associate {it.relativeTo(root).path to hash(it.readBytes())}}
}
