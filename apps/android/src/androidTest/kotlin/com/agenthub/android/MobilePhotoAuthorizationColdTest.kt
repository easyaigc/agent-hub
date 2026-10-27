package com.agenthub.android

import android.os.Build
import android.os.Process
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Read the original real OS-request journal in a different process. No permission request is repeated. */
class MobilePhotoAuthorizationColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun originalSystemAttemptSurvivesProcessDeathWithoutReopeningPermissionOrChangingCiphertext() {
        require(Build.HARDWARE in listOf("ranchu","goldfish"))
        val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val fixture=InstrumentationRegistry.getArguments().getString("photoAuthorizationFixture")?:error("Original scoped authorization fixture required")
        require(fixture.matches(Regex("photo-auth-[a-f0-9]{16}\\.json")))
        val value=JSONObject(File(i.targetContext.filesDir,"acceptance/$fixture").readText())
        require(value.keys().asSequence().toSet()==setOf("version","namespace","writerPid","encryptedSha256","record" )&&value.getInt("version")==1)
        val namespace=value.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        val record=value.getString("record");require(record==if(Build.VERSION.SDK_INT>=34)"attempt-selected" else "attempt-external")
        assertNotEquals(value.getInt("writerPid"),Process.myPid())
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        val a=RecentPhotoHintAcceptance(ui.activity,{ condition -> ui.waitUntil(20_000,condition) })
        try {
            val store=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace").child(ui.activity,"photo-permission")
            fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val encrypted=File(store.coordinationKey,hash(record.toByteArray())+".bin")
            val original=encrypted.readBytes();assertTrue(original.size>13);assertEquals(value.getString("encryptedSha256"),hash(original))
            val before=store.read(record)!!;val source=NativeRecentPhotos(ui.activity);assertEquals(NativePhotoAccess.NONE,source.access())
            val restored=NativePhotoAuthorization(store,source.permissions())
            a.main {
                assertFalse(restored.claim("cold-original-read",automatic=true));assertFalse(restored.busy);assertFalse(restored.failed);assertNull(restored.launchId)
            }
            assertEquals(before,store.read(record));assertArrayEquals(original,encrypted.readBytes())
            assertTrue(JSONObject(before).getBoolean("attempted"));assertEquals(NativePhotoAccess.NONE,source.access())
            assertTrue(a.session.attachments.isEmpty());assertTrue(a.session.operationRecords.isEmpty());assertEquals("最近图片原稿 100% %@ 😀",a.session.text)
            File(a.owned.folder,"original-photo-authorization-cold-proof.json").writeText(JSONObject().put("writerPid",value.getInt("writerPid")).put("readerPid",Process.myPid())
                .put("originalRealOsRequestRecord",true).put("differentProcess",true).put("sameDeviceKeystore",true).put("originalEncryptedBytesUnchanged",true)
                .put("automaticRequestNotClaimed",true).put("nativePermissionLaunches",0).put("modelInputs",0).put("notOriginalAttachmentResultColdProof",true).toString())
        }finally { a.finish() }
    }
}
