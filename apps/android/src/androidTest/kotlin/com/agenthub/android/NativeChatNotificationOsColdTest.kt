package com.agenthub.android

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Three separately scheduled fresh-process roles. Real Platform/NotificationManager/SystemUI/PendingIntent;
 * synthetic cold event and local-only Host ports. Never direct-launches an Activity or mutates permissions.
 * The fresh reader is an instrumented app process with zero Activities, not proof OS spawned the process. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class NativeChatNotificationOsColdTest {
    @get:Rule(order = 0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(150)
    @get:Rule(order = 1) val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun lease(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("p9OsColdLease")).also { NativeChatNotificationColdPlan.uuid(it) }
    private fun <T> io(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.IO) { action() } }
    private fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { action() } }
    private fun noActivity() = instrumentation.runOnMainSync {
        check(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity })
        check(NativeChatNotificationColdStart.activityEntries.get() == 0 && NativeChatNotificationColdStart.defaultSelections.get() == 0 && NativeChatNotificationColdStart.active == null)
    }
    private fun activity(action: (MainActivity) -> Unit) = instrumentation.runOnMainSync {
        val values = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
        check(values.size == 1); action(values.single())
    }
    private fun permission() = JSONObject().put("granted", Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        .put("appEnabled", manager.areNotificationsEnabled())
    @Before fun exactParentLease() {
        Assume.assumeTrue("Parent must schedule an explicit fresh OS cold lease; skip is not acceptance", InstrumentationRegistry.getArguments().getString("p9OsColdLease") != null)
        lease(); NativeChatNotificationColdStart.requireHooks(context); noActivity()
    }
    @Test fun writerPostsOriginalWithProductionPlatformWithoutActivity() {
        // Permission is parent-owned. Refuse before creating fixture/arm when not ready.
        val beforePermission = permission(); check(beforePermission.getBoolean("granted") && beforePermission.getBoolean("appEnabled"))
        io { NativeChatNotificationCycleFiles.requireFresh(context.noBackupFilesDir, lease()) }
        val plan = io { NativeChatNotificationColdStart.seedWriter(context, lease(), NativeChatNotificationColdCase.ORIGINAL) }
        val scope = NativeChatNotificationAcceptanceScope.create(context, plan.lease); val storage = scope.storage(context)
        val receipt = io { NativeChatNotificationColdStart.originalReceipt(storage, plan) }
        val text = NativeL10n(context, "en"); val computer = "P9 OS ${plan.lease}"
        val presentation = NativeChatNoticePresentation.create(receipt.event, computer, true) { text.tr(it) }
        val platform = NativeChatNotificationPlatform.forAcceptance(context, text, scope, storage)
        val channel = checkNotNull(main { platform.prepareAsync(receipt.event.kind, "none", false) { true } })
        val tag = OsColdEvidence.tag(scope, receipt)
        check(manager.activeNotifications.none { it.tag == tag && it.id == 1 })
        val original = JSONObject().put("plan", plan.json()).put("planHash", plan.fingerprint).put("tag", tag).put("id", 1).put("channel", channel)
            .put("title", presentation.title).put("summary", presentation.summary).put("metadata", presentation.metadata)
            .put("baseFiles", JSONObject(io { OsColdEvidence.inventory(context, plan.lease, excludeRecord = true) }))
        val marker = JSONObject().put("version", 1).put("phase", "post-unknown").put("original", original).put("published", JSONObject.NULL).put("reader", JSONObject.NULL)
        io { check(storage.read(OsColdEvidence.RECORD) == null); storage.write(OsColdEvidence.RECORD, marker.toString()) }
        val preferences = io { NativeChatNotificationStore(storage).preferences() }
        var failure: Throwable? = null
        try {
            // One submission only. The write-ahead post-unknown marker is never an automatic retry instruction.
            main { platform.post(receipt, preferences, computer, 0, channel) }
            var posted: StatusBarNotification? = null
            io { withTimeout(10_000) { while(posted == null) { posted = manager.activeNotifications.singleOrNull { it.tag == tag && it.id == 1 }; if(posted == null)delay(20) } } }
            val row = checkNotNull(posted); OsColdEvidence.validateNotification(context, original, receipt, row)
            check(!row.isOngoing && row.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            val observed = JSONObject().put("postTime", row.postTime).put("creatorPackage", row.notification.contentIntent.creatorPackage)
                .put("creatorUid", row.notification.contentIntent.creatorUid).put("immutable", Build.VERSION.SDK_INT >= 31 && row.notification.contentIntent.isImmutable)
                .put("writerPid", Process.myPid()).put("writerNonce", NativeChatNotificationColdStart.processNonce)
            marker.put("phase", "published").put("published", observed)
            io { check(OsColdEvidence.inventory(context, plan.lease, true) == OsColdEvidence.hashMap(original.getJSONObject("baseFiles"))); storage.write(OsColdEvidence.RECORD, marker.toString()) }
            noActivity(); check(permission().toString() == beforePermission.toString())
        } catch(error: Throwable) { failure = error; throw error }
        finally { io { OsColdEvidence.proof(context, plan, "os-writer", JSONObject().put("passed", failure == null).put("phase", marker.getString("phase"))
            .put("notificationManagerObserved", marker.getString("phase") == "published").put("receiptState", receipt.state)
            .put("receiptHandling", "Production Platform.post only; original claimed receipt unchanged, no Delivery.finish or backend implied")
            .put("failureType", failure?.javaClass?.name?.take(128) ?: JSONObject.NULL)) } }
        // The notification and original immutable PendingIntent intentionally outlive this writer process.
    }
    @Test fun systemUiClickCreatesFirstActivityAndOriginalPendingIntentRedeliveryKeepsNewDraft() {
        io { OsColdEvidence.inventory(context, lease(), false) }
        val plan = io { NativeChatNotificationColdStart.prepareReader(context, lease()) }
        val storage = NativeChatNotificationAcceptanceScope.create(context, plan.lease).storage(context)
        val marker = io { OsColdEvidence.loadPublished(context, storage, plan) }; val original = marker.getJSONObject("original")
        val receipt = io { NativeChatNotificationColdStart.originalReceipt(storage, plan) }
        val row = manager.activeNotifications.single { it.tag == original.getString("tag") && it.id == 1 }
        OsColdEvidence.validateNotification(context, original, receipt, row)
        check(row.postTime == marker.getJSONObject("published").getLong("postTime"))
        val pending = row.notification.contentIntent // Hold this exact system-returned original across auto-cancel.
        val beforeOthers = OsColdEvidence.otherNotifications(manager, original.getString("tag"))
        // Seal one reader attempt before any SystemUI action. Even a click with no observed
        // Activity cannot be retried as a fresh operation after this process disappears.
        val readerAttempt = JSONObject().put("pid", Process.myPid()).put("nonce", NativeChatNotificationColdStart.processNonce)
            .put("operationId", UUID.randomUUID().toString()).put("startedAt", System.currentTimeMillis())
        marker.put("phase", "reader-attempted").put("reader", readerAttempt)
        io { storage.write(OsColdEvidence.RECORD, marker.toString()) }
        val beforePermission = permission(); val automation = instrumentation.uiAutomation
        val service = automation.serviceInfo; val oldFlags = service.flags
        var clicked = false; var duplicateSent = false; var completed = false; var failure: Throwable? = null
        var selector = JSONObject().put("state", "not-started"); var attempt = "prepared"
        try {
            service.flags = oldFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = service
            noActivity()
            attempt = "requesting-notification-shade"
            check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) { "SystemUI notification shade action unavailable; no fallback" }
            attempt = "awaiting-exact-systemui-row"
            val selected = OsColdEvidence.awaitOwnRow(automation, original)
            selector = OsColdEvidence.nodeProof(selected, original)
            OsColdEvidence.cropOwnRow(context, plan, automation, selected)
            check(selected.refresh() && OsColdEvidence.isOwnRow(selected, original)) { "Own SystemUI row changed; no click/retry" }
            // Exactly one SystemUI action. Never fall back to PendingIntent.send, coordinates or launchActivity.
            attempt = "one-systemui-click"
            clicked = selected.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            check(clicked) { "Own SystemUI ACTION_CLICK rejected; no alternate route" }
            ui.waitUntil(20_000) { NativeChatNotificationColdStart.active?.coordinator?.openState == "opened" }
            attempt = "original-route-opened"
            val binding = checkNotNull(NativeChatNotificationColdStart.active)
            activity { a ->
                check(a.session === binding.session && !a.session.restoring && !a.session.localRecoveryRequired)
                check(NativeChatNotificationColdStart.activityEntries.get() == 1 && NativeChatNotificationColdStart.defaultSelections.get() == 0)
                check(NativeChatNotificationColdStart.strictTicket(context, a.intent) == plan.ticket)
                check(binding.session.selectedThread == plan.originalThread && binding.session.text == plan.originalDraft)
                check(sameScope(binding.session.selectedClient.scope, binding.ports.scope) && binding.session.operationRecords.isEmpty())
                check(binding.ports.locatorCursors.size == 12 && binding.ports.lastLocatorPage?.threads?.single()?.id == plan.originalThread)
                check(NativeChatNoticeTarget.from(checkNotNull(binding.ports.lastLocatorPage?.scope)) == plan.target)
            }
            ui.onNodeWithTag("product-surface").assertIsDisplayed(); ui.onNodeWithTag("message-input").assertTextContains(plan.originalDraft)
            io { check(NativeChatNotificationColdStart.originalReceipt(storage, plan).consumed) }
            ui.waitUntil(10_000) { manager.activeNotifications.none { it.tag == original.getString("tag") && it.id == 1 } }
            activity { it.session.text = "Owned OS cold new draft ${plan.lease}" }
            // Separate SDK duplicate delivery after the proven SystemUI first open. Same retained object;
            // no new Intent, no new PendingIntent, no second post and no second SystemUI claim.
            attempt = "one-original-sdk-redelivery"
            pending.send(); duplicateSent = true
            ui.waitUntil(10_000) { binding.newIntentHandled.get() == 1 }
            activity {
                check(it.session.selectedThread == plan.originalThread && it.session.text == "Owned OS cold new draft ${plan.lease}")
                check(it.session.operationRecords.isEmpty() && NativeChatNotificationColdStart.activityEntries.get() == 1)
                check(NativeChatNotificationColdStart.defaultSelections.get() == 0 && binding.ports.locatorCursors.size == 12)
            }
            ui.onNodeWithTag("message-input").assertTextContains("Owned OS cold new draft ${plan.lease}")
            check(binding.ports.forbiddenCommands.get() == 0 && binding.ports.notificationCommands.get() == 0)
            io {
                val after = NativeChatNotificationColdStart.originalReceipt(storage, plan)
                check(after.consumed && after.lease.credentialEpoch == plan.authority)
                check(NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.receipt(after.copy(consumed = false))) == plan.receiptHash)
                NativeChatNotificationColdStart.completeReader(context, binding)
                storage.awaitQueuedWrites()
                marker.put("phase", "reader-complete").put("reader", readerAttempt.put("systemUiClicks", 1).put("sdkOriginalPendingIntentRedeliveries", 1).put("defaultSelections", 0).put("activityEntries", 1).put("cleanupPassed", false)
                    .put("draftHash", NativeChatNotificationWire.hash("Owned OS cold new draft ${plan.lease}"))
                    .put("files", JSONObject(OsColdEvidence.inventory(context, plan.lease, true))))
                storage.write(OsColdEvidence.RECORD, marker.toString())
            }
            completed = true; attempt = "reader-complete"
        } catch(error: Throwable) {
            failure = error
            if(NativeChatNotificationColdStart.active != null)runCatching { runBlocking { NativeChatNotificationColdStart.recordReaderFailure(context) } }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally {
            var cleanupFailure: Throwable? = null; var otherUnchanged = false; var permissionUnchanged = false
            try {
                val restore = automation.serviceInfo; restore.flags = oldFlags; automation.serviceInfo = restore
                // Match actual row + original PendingIntent before cancelling only its exact tag/id.
                val remaining = manager.activeNotifications.filter { it.tag == original.getString("tag") && it.id == 1 }
                check(remaining.size <= 1)
                remaining.singleOrNull()?.let { live -> OsColdEvidence.validateNotification(context, original, receipt, live); check(live.notification.contentIntent == pending); manager.cancel(live.tag, live.id) }
                otherUnchanged = OsColdEvidence.otherNotifications(manager, original.getString("tag")) == beforeOthers
                permissionUnchanged = permission().toString() == beforePermission.toString()
                check(otherUnchanged && permissionUnchanged)
                if(completed)io { marker.getJSONObject("reader").put("cleanupPassed", true); storage.write(OsColdEvidence.RECORD, marker.toString()) }
            } catch(error: Throwable) { cleanupFailure = error }
            try { io { OsColdEvidence.proof(context, plan, "os-reader", JSONObject().put("passed", completed && failure == null && cleanupFailure == null)
                .put("attempt", attempt).put("readerAttemptId", readerAttempt.getString("operationId")).put("systemUiClickAccepted", clicked).put("sdkDuplicateSent", duplicateSent).put("selector", selector)
                .put("failureType", failure?.javaClass?.name?.take(128) ?: JSONObject.NULL).put("cleanupFailureType", cleanupFailure?.javaClass?.name?.take(128) ?: JSONObject.NULL)
                .put("defaultSelections", NativeChatNotificationColdStart.defaultSelections.get()).put("activityEntries", NativeChatNotificationColdStart.activityEntries.get())
                .put("incompleteRouter", !completed).put("otherOwnNotificationsHashUnchanged", otherUnchanged).put("permissionUnchanged", permissionUnchanged)) }
            } catch(error: Throwable) { if(cleanupFailure == null)cleanupFailure = error else cleanupFailure!!.addSuppressed(error) }
            cleanupFailure?.let { error -> if(failure != null)failure!!.addSuppressed(error) else throw error }
        }
    }
    @Test fun observerReleasesOnlyCompletedOsColdLeaseAfterReaderExit() {
        val plan = io {
            OsColdEvidence.inventory(context, lease(), false)
            val store = NativeChatNotificationAcceptanceScope.create(context, lease()).storage(context)
            val value = OsColdEvidence.load(context, store, lease()); check(value.getString("phase") == "reader-complete")
            val plan = NativeChatNotificationColdPlan.decode(value.getJSONObject("original").getJSONObject("plan"))
            OsColdEvidence.validateOriginal(value.getJSONObject("original"), plan)
            val result = value.getJSONObject("reader")
            NativeChatNotificationWire.keys(result, setOf("pid", "nonce", "operationId", "startedAt", "systemUiClicks", "sdkOriginalPendingIntentRedeliveries", "defaultSelections", "activityEntries", "cleanupPassed", "draftHash", "files"))
            NativeChatNotificationWire.uuid(result.getString("operationId")); NativeChatNotificationWire.long(result, "startedAt")
            check(result.getInt("systemUiClicks") == 1 && result.getInt("sdkOriginalPendingIntentRedeliveries") == 1 && result.getBoolean("cleanupPassed"))
            check(result.getInt("pid") != Process.myPid() && result.getString("nonce") != NativeChatNotificationColdStart.processNonce)
            check(OsColdEvidence.inventory(context, plan.lease, true).keys == OsColdEvidence.hashMap(result.getJSONObject("files")).keys)
            val after = NativeChatNotificationColdStart.originalReceipt(store, plan)
            check(after.consumed && after.lease.credentialEpoch == plan.authority)
            check(NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.receipt(after.copy(consumed = false))) == plan.receiptHash)
            val metadata = checkNotNull(store.metadata()); val clients = metadata.getJSONArray("clients"); check(clients.length() == 1)
            val client = decodeClient(clients.getJSONObject(0)); check(client.id == plan.target.hostId && NativeChatNoticeTarget.from(checkNotNull(client.scope)) == plan.target)
            check(client.endpoint == plan.endpoint && store.token(client.id) == plan.storedToken)
            val drafts = metadata.getJSONObject("drafts"); val texts = drafts.keys().asSequence().map { drafts.getString(it) }.toList()
            check(texts.any { NativeChatNotificationWire.hash(it) == result.getString("draftHash") } && plan.otherDraft in texts)
            for(name in listOf(NativeChatNotificationIdentity.RECORD, NativeChatNotificationForegroundStore.RECORD, NativeChatNotificationRecoveryStore.RECORD, NativeChatNotificationAuthorizations.RECORD))check(store.read(name) == null)
            check(manager.activeNotifications.none { it.tag == value.getJSONObject("original").getString("tag") && it.id == 1 })
            NativeChatNotificationColdStart.disarmCompleted(context, plan.lease)
            OsColdEvidence.proof(context, plan, "os-observer", JSONObject().put("passed", true).put("encryptedOriginalsRetained", true).put("routerReleased", true))
            plan
        }
        check(plan.lease == lease()); noActivity()
    }
}

/** Closed fixture evidence/selector. No arbitrary path, shell, target, view-id or method argument. */
private object OsColdEvidence {
    const val RECORD = "native-chat-notification-os-cold-v1"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val ROW = "$SYSTEM_UI:id/expandableNotificationRow"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun tag(scope: NativeChatNotificationAcceptanceScope, receipt: NativeChatNoticeReceipt) = scope.prefix + NativeChatNotificationWire.hash(NativeChatNotificationCodec.target(receipt.event.target).toString()) + "." + receipt.event.id
    fun inventory(context: Context, lease: String, excludeRecord: Boolean): Map<String,String> {
        NativeChatNotificationColdPlan.uuid(lease); val root = File(context.noBackupFilesDir, "P9-native-system-$lease")
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        val rows = checkNotNull(root.listFiles()); check(rows.size in 1..16 && rows.sumOf { it.length() } <= 2_097_152)
        check(rows.all { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.matches(Regex("[a-f0-9]{64}\\.bin")) && it.length() in 29..524_288 }) { "Unknown/partial fixture material preserved" }
        return rows.filterNot { excludeRecord && it.name == NativeChatNotificationWire.hash(RECORD) + ".bin" }.associate { it.name to sha(it.readBytes()) }
    }
    fun hashMap(value: JSONObject): Map<String,String> {
        check(value.length() in 1..15)
        return value.keys().asSequence().associateWith { key -> check(key.matches(Regex("[a-f0-9]{64}\\.bin"))); value.getString(key).also { check(chatNoticeHash(it)) } }
    }
    fun load(context: Context, storage: NativeSessionStorage, lease: String): JSONObject {
        inventory(context, lease, false)
        return JSONObject(checkNotNull(storage.read(RECORD)).also { check(it.toByteArray().size <= 32_768) }).also {
            NativeChatNotificationWire.keys(it, setOf("version", "phase", "original", "published", "reader")); check(it.getInt("version") == 1)
            check(it.getString("phase") in setOf("post-unknown", "published", "reader-attempted", "reader-complete"))
        }
    }
    fun validateOriginal(original: JSONObject, plan: NativeChatNotificationColdPlan) {
        NativeChatNotificationWire.keys(original, setOf("plan", "planHash", "tag", "id", "channel", "title", "summary", "metadata", "baseFiles"))
        check(plan.case == NativeChatNotificationColdCase.ORIGINAL && original.getString("planHash") == plan.fingerprint)
        check(original.getInt("id") == 1 && original.getString("metadata").contains(plan.lease))
        check(original.getString("channel").startsWith("native-chat-acceptance-v1.${plan.lease}."))
    }
    fun loadPublished(context: Context, storage: NativeSessionStorage, plan: NativeChatNotificationColdPlan): JSONObject = load(context, storage, plan.lease).also {
        check(it.getString("phase") == "published" && it.isNull("reader")); val original = it.getJSONObject("original"); validateOriginal(original, plan)
        val published = it.getJSONObject("published")
        NativeChatNotificationWire.keys(published, setOf("postTime", "creatorPackage", "creatorUid", "immutable", "writerPid", "writerNonce"))
        check(published.getInt("writerPid") == plan.writerPid && published.getString("writerNonce") == plan.writerNonce)
        check(published.getString("creatorPackage") == context.packageName && published.getInt("creatorUid") == context.applicationInfo.uid && published.getBoolean("immutable"))
        check(NativeChatNotificationColdPlan.decode(original.getJSONObject("plan")).fingerprint == plan.fingerprint)
        check(inventory(context, plan.lease, true) == hashMap(original.getJSONObject("baseFiles")))
    }
    fun validateNotification(context: Context, original: JSONObject, receipt: NativeChatNoticeReceipt, row: StatusBarNotification) {
        val plan = NativeChatNotificationColdPlan.decode(original.getJSONObject("plan")); validateOriginal(original, plan)
        check(row.packageName == context.packageName && row.uid == context.applicationInfo.uid && row.tag == original.getString("tag") && row.id == 1)
        check(row.tag == tag(NativeChatNotificationAcceptanceScope.create(context, plan.lease), receipt))
        val notice = row.notification; check(notice.channelId == original.getString("channel") && notice.visibility == Notification.VISIBILITY_PRIVATE)
        check(notice.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString() == original.getString("metadata"))
        check(notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == original.getString("title"))
        check(notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == original.getString("summary"))
        val pending = checkNotNull(notice.contentIntent); check(pending.creatorPackage == context.packageName && pending.creatorUid == context.applicationInfo.uid)
        val canonical = PendingIntent.getActivity(context, 0, NativeChatNotificationPlatform.openIntent(context, plan.ticket), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        check(canonical != null && pending == canonical)
        if(Build.VERSION.SDK_INT >= 31)check(pending.isActivity && pending.isImmutable)
    }
    fun otherNotifications(manager: NotificationManager, exactTag: String): String = NativeChatNotificationWire.hash(manager.activeNotifications.filterNot { it.tag == exactTag && it.id == 1 }
        .map { "${it.tag}:${it.id}:${it.postTime}" }.sorted().joinToString("\n"))
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(); val todo = ArrayDeque<Pair<AccessibilityNodeInfo,Int>>(); todo.add(root to 0)
        while(todo.isNotEmpty()) {
            val (node, depth) = todo.removeFirst(); check(result.size < 2048 && depth <= 48) { "SystemUI tree exceeds bounded selector; stop" }; result.add(node)
            for(index in 0 until node.childCount)node.getChild(index)?.let { todo.add(it to depth + 1) }
        }
        return result
    }
    fun isOwnRow(row: AccessibilityNodeInfo, original: JSONObject): Boolean {
        if(row.packageName?.toString() != SYSTEM_UI || row.viewIdResourceName != ROW || !row.isVisibleToUser || !row.isEnabled || !row.isClickable)return false
        if(row.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK })return false
        val visible = nodes(row).filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }
        return listOf("metadata", "title", "summary").all { original.getString(it) in visible }
    }
    fun awaitOwnRow(automation: android.app.UiAutomation, original: JSONObject): AccessibilityNodeInfo = runBlocking {
        var lastOwner = "none"; var rowCount = 0
        try { withTimeout(15_000) {
            while(true) {
                if(Build.VERSION.SDK_INT >= 33)automation.clearCache()
                val root = automation.rootInActiveWindow
                lastOwner = root?.packageName?.toString()?.take(96) ?: "none"
                if(lastOwner == SYSTEM_UI && root != null) {
                    val candidates = nodes(root).filter { it.viewIdResourceName == ROW && isOwnRow(it, original) }; rowCount = candidates.size
                    if(candidates.size == 1)return@withTimeout candidates.single()
                    check(candidates.size <= 1) { "Ambiguous own UUID SystemUI rows; no click" }
                }
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } } catch(failure: TimeoutCancellationException) { throw AssertionError("Blocked SystemUI: no unique validated UUID row; owner=$lastOwner candidates=$rowCount; no fallback", failure) }
    }
    fun nodeProof(row: AccessibilityNodeInfo, original: JSONObject): JSONObject {
        check(isOwnRow(row, original)); val bounds = Rect(); row.getBoundsInScreen(bounds); check(!bounds.isEmpty)
        return JSONObject().put("state", "exact-row-validated").put("owner", SYSTEM_UI).put("resourceId", ROW).put("bounds", bounds.flattenToString())
            .put("uuidMetadataHash", NativeChatNotificationWire.hash(original.getString("metadata"))).put("titleAndSummaryMatched", true)
    }
    fun cropOwnRow(context: Context, plan: NativeChatNotificationColdPlan, automation: android.app.UiAutomation, row: AccessibilityNodeInfo) {
        val bounds = Rect(); row.getBoundsInScreen(bounds); val full = checkNotNull(automation.takeScreenshot())
        try {
            check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= full.width && bounds.bottom <= full.height && !bounds.isEmpty)
            val cropped = Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width(), bounds.height())
            try { val bytes = ByteArrayOutputStream().use { out -> check(cropped.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }; check(bytes.size <= 2_097_152)
                save(context, plan, "own-systemui-row-${UUID.randomUUID()}.png", bytes)
            } finally { if(cropped !== full)cropped.recycle() }
        } finally { full.recycle() }
    }
    private fun save(context: Context, plan: NativeChatNotificationColdPlan, filename: String, bytes: ByteArray) {
        val folder = File(context.filesDir, "acceptance/P9-os-cold/${plan.lease}"); check(folder.mkdirs() || folder.isDirectory)
        check(folder.canonicalFile == File(context.filesDir.canonicalFile, "acceptance/P9-os-cold/${plan.lease}")); android.system.Os.chmod(folder.absolutePath, 448)
        val file = File(folder, filename); check(file.createNewFile()); android.system.Os.chmod(file.absolutePath, 384)
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    fun proof(context: Context, plan: NativeChatNotificationColdPlan, role: String, details: JSONObject) {
        check(!Looper.getMainLooper().isCurrentThread)
        save(context, plan, "$role-${UUID.randomUUID()}.json", JSONObject().put("version", 1).put("lease", plan.lease).put("role", role).put("planHash", plan.fingerprint)
            .put("appHash", plan.appHash).put("runnerHash", plan.runnerHash).put("pid", Process.myPid()).put("nonce", NativeChatNotificationColdStart.processNonce).put("api", Build.VERSION.SDK_INT)
            .put("details", details).put("eventBackend", "Synthetic local-only event/Host ports; no real push/vendor/Host/model confirmation")
            .put("processBoundary", "Fresh instrumented app reader with zero prior Activities; OS first Activity PendingIntent, not proof OS spawned process")
            .toString().toByteArray())
    }
}
