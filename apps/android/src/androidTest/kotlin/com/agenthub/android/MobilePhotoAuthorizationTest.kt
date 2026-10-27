package com.agenthub.android

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Real encrypted storage and native sheet. Permission decisions are synthetic except in the system class. */
class MobilePhotoAuthorizationTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var a: RecentPhotoHintAcceptance
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        a=RecentPhotoHintAcceptance(ui.activity,{ condition -> ui.waitUntil(20_000,condition) })
    }
    @After fun finish() { if(::a.isInitialized)a.finish() }
    private val permissions get()=arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    private fun file(s: NativeSessionStorage,name: String)=File(s.coordinationKey,MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString(""){"%02x".format(it)}+".bin")
    @Test fun firstClaimIsDurableBeforeLaunchAndReconstructedOwnerNeverAutomaticallyRepeatsIt() {
        val storage=a.owned.storage.child(ui.activity,"authorization-policy")
        val p=NativePhotoAuthorization(storage,permissions)
        main {
            assertTrue(p.claim("first",true));assertTrue(p.busy)
            assertFalse(p.claim("replacement",false));p.requestLaunch("wrong");assertNull(p.launchId)
            p.requestLaunch("first");assertArrayEquals(permissions,p.takeLaunch("first"));assertNull(p.takeLaunch("first"))
            p.complete();assertFalse(p.busy)
            val restored=NativePhotoAuthorization(storage,permissions)
            assertFalse(restored.claim("after-cold",true));assertFalse(restored.busy)
            assertTrue(restored.claim("explicit-manage",false));restored.complete()
        }
        val value=JSONObject(storage.read("attempt-images")!!);assertTrue(value.getBoolean("attempted"))
        assertFalse(file(storage,"attempt-images").readBytes().toString(Charsets.ISO_8859_1).contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(a.session.attachments.isEmpty());assertTrue(a.session.operationRecords.isEmpty())
    }
    @Test fun corruptOrFuturePermissionRecordCannotBeOverwrittenOrTreatedAsNeverAsked() {
        val storage=a.owned.storage.child(ui.activity,"authorization-corrupt")
        val originals=listOf("not-json", "{\"version\":2,\"permissions\":[],\"attempted\":true}", "{\"version\":1,\"permissions\":[],\"attempted\":true}")
        for(original in originals) {
            storage.write("attempt-images",original);val bytes=file(storage,"attempt-images").readBytes()
            val p=NativePhotoAuthorization(storage,permissions)
            main { assertFalse(p.claim("auto",true));assertTrue(p.failed);assertFalse(p.busy);assertFalse(p.claim("explicit",false)) }
            assertArrayEquals(bytes,file(storage,"attempt-images").readBytes());assertEquals(original,storage.read("attempt-images"))
        }
    }
    @Test fun lateLaunchCannotUnlockOrDuplicateTheCurrentPermissionRequest() {
        val storage=a.owned.storage.child(ui.activity,"authorization-owner");val p=NativePhotoAuthorization(storage,permissions)
        main {
            assertTrue(p.claim("original",true));p.complete()
            assertTrue(p.claim("current",false));p.requestLaunch("original");assertNull(p.launchId)
            p.requestLaunch("current");assertNull(p.takeLaunch("original"));assertNotNull(p.takeLaunch("current"));assertNull(p.takeLaunch("current"))
            p.launchFailed();assertTrue(p.failed);assertFalse(p.busy)
            assertFalse(p.claim("reopen",true));assertTrue(p.claim("manual-retry",false));p.complete()
        }
    }
    @Test fun openingReadableLibraryNeverRequestsPermissionOrChangesOriginalDraft() {
        val before=a.session.text;val authorization=a.session.photoAuthorization!!
        ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("attachment-panel").assertIsDisplayed()
        ui.waitForIdle();assertFalse(authorization.busy);assertNull(authorization.launchId)
        // The synthetic adapter is already FULL, so no OS decision is necessary.
        // This fixture exercises display-only flow; real native permissions are covered separately.
        ui.onNodeWithTag("attachment-panel-close").performClick();assertEquals(before,a.session.text)
        assertTrue(a.session.attachments.isEmpty());assertTrue(a.session.operationRecords.isEmpty())
    }
}
