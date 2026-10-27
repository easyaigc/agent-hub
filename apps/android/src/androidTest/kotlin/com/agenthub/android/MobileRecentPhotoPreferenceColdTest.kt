package com.agenthub.android

import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Separate instrumentation processes, same device Keystore; synthetic photo IDs and owned namespace only. */
class MobileRecentPhotoPreferenceColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var a: RecentPhotoHintAcceptance
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };a=RecentPhotoHintAcceptance(ui.activity,{ condition -> ui.waitUntil(20_000,condition) })
    }
    @After fun finish() { if(::a.isInitialized)a.finish() }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun encrypted(store: NativeSessionStorage)=File(store.coordinationKey,hash(NativeRecentPhotoLibrary.PREFERENCES.toByteArray())+".bin")
    @Test fun writeIgnoredPreferenceForSeparateProcessRead() {
        a.publish(2);ui.onNodeWithTag("recent-attachment-dismiss").performClick()
        ui.waitUntil(20_000) { !a.library.saving&&a.preferences.read(NativeRecentPhotoLibrary.PREFERENCES)?.contains("/2")==true }
        a.owned.verifyDefault();val nonce=JSONObject(File(a.owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        val receipt=JSONObject().put("version",1).put("namespace",nonce).put("writerPid",Process.myPid()).put("ignoredId","content://media/external/images/media/2").put("encryptedSha256",hash(encrypted(a.preferences).readBytes()))
        File(a.owned.folder,"recent-preference-cold-writer.json").writeText(receipt.toString())
        assertTrue(a.session.attachments.isEmpty());assertEquals("最近图片原稿 100% %@ 😀",a.session.text)
    }
    @Test fun readSameEncryptedIgnoredPreferenceInFreshProcessWithoutOverwritingIt() {
        val name=InstrumentationRegistry.getArguments().getString("recentPreferencesFixture")?:error("Original owned preference fixture required")
        require(name.matches(Regex("recent-pref-[a-f0-9]{16}\\.json")))
        val value=JSONObject(File(ui.activity.filesDir,"acceptance/$name").readText())
        require(value.keys().asSequence().toSet()==setOf("version","namespace","writerPid","ignoredId","encryptedSha256")&&value.getInt("version")==1)
        val namespace=value.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        assertNotEquals(value.getInt("writerPid"),Process.myPid());require(value.getString("ignoredId")=="content://media/external/images/media/2")
        val store=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace").child(ui.activity,"recent-photos")
        assertTrue(store.storedBytes(NativeRecentPhotoLibrary.PREFERENCES)>13);assertEquals(value.getString("encryptedSha256"),hash(encrypted(store).readBytes()))
        val source=HintPhotoSource(ui.activity);lateinit var library: NativeRecentPhotoLibrary;var created: NativeRecentPhotoLibrary?=null
        try {
            a.main { library=NativeRecentPhotoLibrary(source,store);created=library;library.setActive(true) }
            ui.waitUntil(20_000) { library.ready&&!library.loading&&library.revision>0 }
            a.main { source.publish(listOf(source.photo(2))) }
            ui.waitUntil(20_000) { !library.loading&&library.recent.size==1 };assertNull(library.suggestion)
            assertEquals(value.getString("encryptedSha256"),hash(encrypted(store).readBytes()));assertEquals(value.getString("ignoredId"),JSONObject(store.read(NativeRecentPhotoLibrary.PREFERENCES)!!).getJSONArray("ignored").getString(0))
            File(a.owned.folder,"recent-preference-cold-reader.json").writeText(JSONObject().put("differentProcess",true).put("writerPid",value.getInt("writerPid")).put("readerPid",Process.myPid()).put("sameDeviceKeystoreRead",true)
                .put("originalEncryptedBytesUnchanged",true).put("ignoredPhotoNotSuggested",true).put("photoSourceSynthetic",true).put("noUploadOrSubmit",true).toString())
        }finally { a.main { created?.setActive(false) };source.cleanup() }
    }
}
