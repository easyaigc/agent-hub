package com.agenthub.android

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Actual preview/Keystore/consent restoration, synthetic image and credentials only. */
class NativeMigrationAncillaryTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var client: MobileClient
    private lateinit var file: CreationFile
    private lateinit var scope: AISharingScope
    private lateinit var originalConsent: String
    private val thumbnail get()="draft-thumbnail-v1:${file.id}"
    @Before fun setup(){
        ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-ancillary")}
        val payload=MigrationActivationFixtures.payload();client=MigrationActivationFixtures.client(payload);val records=payload.getJSONObject("records");val metadata=JSONObject(records.getString("metadata"));val key=client.cacheKey("synthetic-thread")
        val prior=metadata.getJSONObject("draftFiles").getJSONArray(key).getJSONObject(0)
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.CYAN);val output=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,output);bitmap.recycle();val bytes=output.toByteArray()
        file=CreationFile(prior.getString("id"),"owned-image.png","image/png",bytes.size.toLong(),MigrationActivationFixtures.sha(bytes))
        metadata.getJSONObject("draftFiles").getJSONArray(key).put(0,file.body());records.put("metadata",metadata.toString())
        records.put("draft-file:${file.id}",JSONObject().put("file",file.body()).put("bytes",java.util.Base64.getEncoder().encodeToString(bytes)).toString())
        val source=records.keys().asSequence().single{it.startsWith("submission:")};val sourceBody=JSONObject(records.getString(source));sourceBody.getJSONArray("files").put(0,file.body());records.put(source,sourceBody.toString())
        records.keys().forEach{owned.storage.write(it,records.getString(it))};DraftAttachmentThumbnails.storeIfMissing(owned.storage,file,bytes)
        scope=AISharingScope(client.scope!!,"owned-model")
        originalConsent=JSONObject().put("version",1).put("grants",JSONArray().put(AISharingGrant(UUID.randomUUID().toString(),scope,"原电脑","Codex","旧模型",1234).json())).toString();owned.storage.write("ai-sharing-v1",originalConsent)
    }
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun capture()=NativeMigrationExport(ui.activity,owned.storage).capture()
    private fun imported(): NativeSessionStorage {
        val encrypted=NativeMigrationExport(ui.activity,owned.storage).seal("owned ancillary password");val destination=owned.storage.child(ui.activity,"ancillary-import")
        val receipt=NativeMigrationCandidateStore(ui.activity,destination).stage(UUID.randomUUID().toString(),encrypted,"owned ancillary password");NativeMigrationActivation(ui.activity,destination).activate(receipt);return destination.reopen(ui.activity)
    }
    private fun reject(name: String,text: String){owned.storage.write(name,text);val before=MigrationActivationFixtures.tree(owned.storage);assertTrue(runCatching{capture()}.isFailure);assertEquals(before,MigrationActivationFixtures.tree(owned.storage))}
    @Test fun exactPreviewAndConsentArchiveRestoresImageButRequiresFreshSharingPermission()=runBlocking {
        val source=AISharingConsent(owned.storage);source.restore();assertTrue(source.permits(scope));val before=MigrationActivationFixtures.tree(owned.storage);val originalThumbnail=owned.storage.read(thumbnail)!!
        val destination=imported();assertEquals(originalThumbnail,destination.read(thumbnail));val preview=DraftAttachmentThumbnails.read(destination,file);assertTrue(preview.found);assertNotNull(preview.png)
        val image=android.graphics.BitmapFactory.decodeByteArray(preview.png!!,0,preview.png!!.size);assertEquals(2,image.width);assertEquals(android.graphics.Color.CYAN,image.getPixel(0,0));image.recycle()
        val consent=AISharingConsent(destination);consent.restore();assertTrue(consent.grants.isEmpty());assertFalse(consent.permits(scope));assertNull(consent.failure)
        assertEquals(originalConsent,owned.storage.read("ai-sharing-v1"));assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun nullPreviewRemainsAValidOptionalCacheAndNeverSuppliesAttachmentBytes(){
        val cache=JSONObject(owned.storage.read(thumbnail)!!).put("png",JSONObject.NULL).put("thumbnailSha256",JSONObject.NULL);owned.storage.write(thumbnail,cache.toString())
        val destination=imported();val preview=DraftAttachmentThumbnails.read(destination,file);assertTrue(preview.found);assertNull(preview.png);assertEquals(owned.storage.read("draft-file:${file.id}"),destination.read("draft-file:${file.id}"))
    }
    @Test fun previewDigestTypeBoundsAndUnknownFieldsCannotBypassArchiveChecks(){
        val original=owned.storage.read(thumbnail)!!
        for(change in listOf<(JSONObject)->Unit>({it.put("thumbnailSha256","f".repeat(64))},{it.put("version","1")},{it.put("edge",129)},{it.put("future",true)},{it.put("png",it.getString("png")+"\n")})){
            val value=JSONObject(original);change(value);reject(thumbnail,value.toString());assertFalse(DraftAttachmentThumbnails.read(owned.storage,file).found)
        }
    }
    @Test fun anotherAttachmentManifestOrOrphanPreviewCannotBeImported(){
        val original=JSONObject(owned.storage.read(thumbnail)!!);val altered=JSONObject(original.toString());altered.getJSONObject("file").put("sha256","b".repeat(64));reject(thumbnail,altered.toString());owned.storage.write(thumbnail,original.toString())
        val orphan="draft-thumbnail-v1:${UUID.randomUUID()}";reject(orphan,original.toString())
    }
    @Test fun consentUnknownFieldsWrongTypesDuplicateIdsAndInvalidScopeRejectWithoutReplacingSource(){
        for(change in listOf<(JSONObject)->Unit>({it.put("future",true)},{it.put("version",2)},{it.getJSONArray("grants").getJSONObject(0).put("grantedAt","1234")},{it.getJSONArray("grants").getJSONObject(0).put("grantedAt",-1)},{it.getJSONArray("grants").put(it.getJSONArray("grants").getJSONObject(0))},{it.getJSONArray("grants").getJSONObject(0).getJSONObject("scope").put("version","1")})){
            val value=JSONObject(originalConsent);change(value);reject("ai-sharing-v1",value.toString())
        }
    }
    @Test fun oldGrantForAnAbsentTargetRemainsArchiveOnlyAndNeverAuthorizesTheNewInstallation()=runBlocking {
        val value=JSONObject(originalConsent);value.getJSONArray("grants").getJSONObject(0).getJSONObject("scope").getJSONObject("target").put("runtimeId","historical-other-runtime");owned.storage.write("ai-sharing-v1",value.toString());val original=owned.storage.read("ai-sharing-v1")
        val clear=capture();try{assertEquals(original,NativeMigrationCandidateStore(ui.activity,owned.storage).validate(clear)["ai-sharing-v1"])}finally{clear.fill(0)}
        val destination=imported();val consent=AISharingConsent(destination);consent.restore();assertFalse(consent.permits(scope));assertTrue(consent.grants.isEmpty());assertEquals(original,owned.storage.read("ai-sharing-v1"))
    }
}
