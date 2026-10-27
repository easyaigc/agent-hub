package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Native UI/storage and real provider reads; photo change/permission/time source is explicitly synthetic. */
class MobileRecentPhotoHintTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var acceptance: RecentPhotoHintAcceptance
    private val session get()=acceptance.session
    private val library get()=acceptance.library
    private val source get()=acceptance.fake!!
    private val independent=mutableListOf<Pair<NativeRecentPhotoLibrary,HintPhotoSource>>()
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        acceptance=RecentPhotoHintAcceptance(ui.activity,{ condition -> ui.waitUntil(20_000,condition) })
    }
    @After fun finish() {
        if(::acceptance.isInitialized) {
            acceptance.main { independent.forEach { it.first.setActive(false) } }
            ui.waitUntil(15_000) { independent.none { it.first.saving } }
            acceptance.finish();independent.forEach { it.second.cleanup() }
        }
    }
    private fun waitFor(condition: ()->Boolean) { ui.waitUntil(20_000,condition) }
    private fun encryptedFile(storage: NativeSessionStorage)=File(storage.coordinationKey,
        MessageDigest.getInstance("SHA-256").digest(NativeRecentPhotoLibrary.PREFERENCES.toByteArray()).joinToString(""){"%02x".format(it)}+".bin")
    @Test fun firstSnapshotLimitedAccessExpiredAndOldSurfaceCannotBecomeAnAutomaticSuggestionOrAction() {
        val store=acceptance.owned.storage.child(ui.activity,"policy-recent")
        val fake=HintPhotoSource(ui.activity);var clock=System.currentTimeMillis()/1000.0
        lateinit var policy: NativeRecentPhotoLibrary
        acceptance.main { fake.rows=listOf(fake.photo(10,clock));policy=NativeRecentPhotoLibrary(fake,store,{ clock });independent+=policy to fake;policy.claimSurface("first");policy.setActive(true) }
        waitFor { policy.ready&&!policy.loading&&policy.recent.size==1 };assertNull(policy.suggestion)
        acceptance.main { fake.publish(listOf(fake.photo(11,clock))+fake.rows) };waitFor { policy.suggestion!=null };val old=policy.suggestion!!
        acceptance.main { fake.permission=NativePhotoAccess.LIMITED;policy.refresh() };waitFor { !policy.loading&&policy.access==NativePhotoAccess.LIMITED&&policy.recent.size==2 };assertNull(policy.suggestion)
        acceptance.main { fake.permission=NativePhotoAccess.FULL;policy.refresh() };waitFor { !policy.loading&&policy.access==NativePhotoAccess.FULL&&policy.recent.size==2 };assertNull(policy.suggestion)
        acceptance.main { fake.publish(listOf(fake.photo(12,clock))+fake.rows) };waitFor { policy.suggestion!=null };val current=policy.suggestion!!
        acceptance.main { assertFalse(policy.dismiss(old,"first"));policy.claimSurface("second");assertFalse(policy.dismiss(current,"first"));clock+=601;policy.refresh() }
        waitFor { !policy.loading&&policy.suggestion==null };assertEquals(64,fake.lastLimit);assertEquals(0,fake.inputCalls.get());assertNull(store.read(NativeRecentPhotoLibrary.PREFERENCES))
    }
    @Test fun ignoredRecordsAreEncryptedBoundedAndPreservedOnAnUnreadableSchema() {
        val store=acceptance.owned.storage.child(ui.activity,"policy-recent")
        val initial=(1..256).map { "content://media/external/images/media/$it" }
        store.write(NativeRecentPhotoLibrary.PREFERENCES,JSONObject().put("version",1).put("ignored",JSONArray(initial)).toString())
        val fake=HintPhotoSource(ui.activity);lateinit var policy: NativeRecentPhotoLibrary
        acceptance.main { fake.rows=listOf(fake.photo(256));policy=NativeRecentPhotoLibrary(fake,store);independent+=policy to fake;policy.claimSurface("owned");policy.setActive(true) }
        waitFor { policy.ready&&!policy.loading&&policy.recent.size==1 }
        acceptance.main { fake.publish(listOf(fake.photo(258))+fake.rows) };waitFor { policy.suggestion!=null }
        acceptance.main { assertTrue(policy.dismiss(policy.suggestion!!,"owned")) };waitFor { !policy.saving }
        val raw=store.read(NativeRecentPhotoLibrary.PREFERENCES)!!;val ignored=JSONObject(raw).getJSONArray("ignored")
        assertEquals(256,ignored.length());assertEquals(initial[1],ignored.getString(0));assertEquals("content://media/external/images/media/258",ignored.getString(255))
        assertFalse(encryptedFile(store).readBytes().toString(Charsets.ISO_8859_1).contains("content://media"))
        acceptance.main { policy.markImported(fake.photo(2)) };waitFor { !policy.saving }
        assertEquals("A repeated old completion keeps iOS ignored-order/eviction semantics",ignored.toString(),JSONObject(store.read(NativeRecentPhotoLibrary.PREFERENCES)!!).getJSONArray("ignored").toString())
        acceptance.main { policy.setActive(false) }
        val fresh=HintPhotoSource(ui.activity);lateinit var reconstructed: NativeRecentPhotoLibrary
        acceptance.main { reconstructed=NativeRecentPhotoLibrary(fresh,store);independent+=reconstructed to fresh;reconstructed.setActive(true) }
        waitFor { reconstructed.ready&&reconstructed.revision>0&&!reconstructed.loading }
        acceptance.main { fresh.publish(listOf(fresh.photo(258))) };waitFor { !reconstructed.loading&&reconstructed.recent.size==1 };assertNull(reconstructed.suggestion)
        acceptance.main { reconstructed.setActive(false) }
        val corrupt="{\"version\":2,\"ignored\":[]}";store.write(NativeRecentPhotoLibrary.PREFERENCES,corrupt);val original=encryptedFile(store).readBytes()
        val last=HintPhotoSource(ui.activity);lateinit var failed: NativeRecentPhotoLibrary
        acceptance.main { failed=NativeRecentPhotoLibrary(last,store);independent+=failed to last;failed.setActive(true) }
        waitFor { failed.preferencesFailed };acceptance.main { failed.markImported(last.photo(259)) }
        assertFalse(failed.ready);assertEquals(0,last.snapshotCalls.get());assertEquals(corrupt,store.read(NativeRecentPhotoLibrary.PREFERENCES));assertArrayEquals(original,encryptedFile(store).readBytes())
    }
    @Test fun previewDoesNotReadOriginalAndExplicitAddPersistsOnlyTheLocalOriginalDraft() {
        assertNull(library.suggestion);acceptance.publish(2);ui.onNodeWithTag("recent-attachment-hint").assertIsDisplayed()
        acceptance.owned.nodeShot(ui.onNodeWithTag("product-surface"),"recent-new-hint")
        ui.onNodeWithTag("recent-attachment-preview-open").performClick();ui.onNodeWithTag("recent-attachment-preview").assertExists();assertEquals(0,source.inputCalls.get());assertTrue(session.attachments.isEmpty())
        acceptance.owned.dialogShot("recent-new-preview");ui.onNodeWithTag("attachment-image-done").performClick();assertEquals(0,source.inputCalls.get())
        ui.onNodeWithTag("recent-attachment-preview-open").performClick();ui.onNodeWithTag("recent-attachment-preview-add").assertIsDisplayed().performClick()
        waitFor { !session.importing&&session.attachments.size==1&&!library.saving&&library.suggestion==null }
        assertEquals(1,source.inputCalls.get());assertEquals("最近图片原稿 100% %@ 😀",session.text);acceptance.assertPixels(session.attachments.single().manifest,2)
        assertTrue(runBlocking { session.confirmInboxFiles(session.attachmentImportDestination(),session.attachments.map { it.manifest }) })
        ui.onAllNodesWithTag("recent-attachment-preview").assertCountEquals(0)
    }
    @Test fun lateOriginalImportKeepsItsDraftAndCannotDismissANewerPhotoHint() {
        acceptance.publish(2);source.gate=CompletableDeferred();ui.onNodeWithTag("recent-attachment-add").performClick();waitFor { source.inputCalls.get()==1 }
        acceptance.main { session.selectThread(session.threads.first { it.id=="second" });session.text="第二草稿 100% %@" }
        waitFor { !session.historyLoading&&session.canCompose }
        // iOS keeps the current hint until Add succeeds or the user explicitly dismisses it.
        // Dismissing the reminder does not cancel the already requested local import.
        ui.onNodeWithTag("recent-attachment-dismiss").performClick();waitFor { library.suggestion==null&&!library.saving }
        val beforeLateCompletion=encryptedFile(acceptance.preferences).readBytes()
        val newer=acceptance.publish(3)
        source.gate!!.complete(Unit)
        waitFor { !library.saving&&!encryptedFile(acceptance.preferences).readBytes().contentEquals(beforeLateCompletion) }
        assertEquals("second",session.selectedThread);assertEquals("第二草稿 100% %@",session.text);assertTrue(session.attachments.isEmpty());assertEquals(newer.id,library.suggestion!!.photo.id)
        acceptance.main { session.selectThread(session.threads.first { it.id=="first" }) };waitFor { !session.historyLoading&&session.attachments.size==1 }
        assertEquals("最近图片原稿 100% %@ 😀",session.text);acceptance.assertPixels(session.attachments.single().manifest,2);assertEquals(1,source.inputCalls.get())
    }
    @Test fun lateReadFailureCannotReplaceNewerErrorOrIgnoreTheUnimportedPhoto() {
        val photo=acceptance.publish(2);source.gate=CompletableDeferred();ui.onNodeWithTag("recent-attachment-add").performClick();waitFor { source.inputCalls.get()==1 }
        acceptance.main { session.error="newer raw 100% %@";source.failure=NativeFileIntakeFailure(NativePhotoIntake.UNREADABLE) };source.gate!!.complete(Unit)
        waitFor { !session.importing };assertEquals("newer raw 100% %@",session.error);assertTrue(session.attachments.isEmpty());assertEquals(photo.id,library.suggestion!!.photo.id);assertNull(acceptance.preferences.read(NativeRecentPhotoLibrary.PREFERENCES))
    }
    @Test fun incomingFileWaitsUntilRecentPhotoPreviewClosesWithoutChangingTheDraft() {
        acceptance.publish(2);ui.onNodeWithTag("recent-attachment-preview-open").performClick();ui.onNodeWithTag("recent-attachment-preview").assertExists()
        val directory=File(ui.activity.cacheDir,"resources/hint-inbox-"+java.util.UUID.randomUUID()).apply { mkdirs() }
        val file=File(directory,"received.txt").apply { writeText("received 100% %@\n") }
        try {
            val uri=androidx.core.content.FileProvider.getUriForFile(ui.activity,ui.activity.packageName+".resources",file)
            acceptance.main { assertTrue(session.externalInbox!!.receive(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM,uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),java.util.UUID.randomUUID().toString())) }
            waitFor { session.externalInbox!!.available&&!session.externalInbox!!.receiving&&!session.externalInbox!!.loading }
            ui.onNodeWithTag("recent-attachment-preview").assertExists();ui.onAllNodesWithTag("external-attachment-later").assertCountEquals(0);assertNull(session.externalInbox!!.presentation)
            ui.onNodeWithTag("attachment-image-done").performClick()
            waitFor { ui.onAllNodesWithTag("external-attachment-later").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("external-attachment-later").performClick();assertEquals(0,source.inputCalls.get());assertTrue(session.attachments.isEmpty());assertEquals("最近图片原稿 100% %@ 😀",session.text)
        }finally { assertTrue(!file.exists()||file.delete());assertTrue(!directory.exists()||directory.delete()) }
    }
    @Test fun backgroundRetiresTheOldHintAndManualPreferenceRetryPreservesTheDraft() {
        val photo=acceptance.publish(2);val old=library.suggestion!!
        acceptance.main { library.claimSurface("old");library.setActive(false);assertFalse(library.dismiss(old,"old"));library.setActive(true) }
        waitFor { !library.loading&&library.recent.any { it.id==photo.id } };assertNull(library.suggestion)
        acceptance.main { library.setActive(false) };val original="{\"version\":2,\"ignored\":[]}";acceptance.preferences.write(NativeRecentPhotoLibrary.PREFERENCES,original)
        acceptance.main { library.retryPreferences() };waitFor { library.preferencesFailed }
        ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("recent-preferences-retry").performScrollTo().performClick();waitFor { library.preferencesFailed }
        assertEquals(original,acceptance.preferences.read(NativeRecentPhotoLibrary.PREFERENCES));assertEquals("最近图片原稿 100% %@ 😀",session.text);assertNull(session.error)
        // Only this examiner-owned corrupt fixture is explicitly repaired, then the product Retry is used.
        val fence=acceptance.preferences.beginRestore();acceptance.preferences.finishRestore(fence) { }
        acceptance.preferences.write(NativeRecentPhotoLibrary.PREFERENCES,JSONObject().put("version",1).put("ignored",JSONArray()).toString())
        ui.onNodeWithTag("recent-preferences-retry").performScrollTo().performClick();waitFor { library.ready&&!library.preferencesFailed }
        ui.onNodeWithTag("attachment-panel-close").performClick();assertEquals("最近图片原稿 100% %@ 😀",session.text);assertTrue(session.attachments.isEmpty())
    }
}
