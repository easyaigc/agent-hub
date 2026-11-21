package com.agenthub.android

import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** SOURCE CANDIDATE ONLY: one actual Compose menu gesture against the existing default
 * MainActivity/Session. No reset, ActivityScenario/new Session, input, Origin, pairing,
 * fixture, command substitution, or stop. Root must separately bind the installed runner,
 * fresh complete external ciphertext inventory and a sole future AVD/Host-open window.
 * Normal Main navigation performs real reads and local cache/metadata writes.
 */
@RunWith(AndroidJUnit4::class)
class NativeDefaultDesktopOpenMenuTest {
    private val app = "com.easyai.agenthub.android.acceptance"
    private val host = "13c81542-48e3-4df2-ab46-d45357b631a2"
    private val thread = "01a1ed56-a3f7-7ad1-b1ab-768fa9fceac8"
    private val targetHash = "4edc39b469ffdfebdbf64b04e9a672decd1aa5037646336018bfb63581ebbf9b"
    private val selector = "com.agenthub.android.NativeDefaultDesktopOpenMenuTest#currentDefaultMenuOpensOriginalP1WithoutInput"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var run: String
    private lateinit var plan: JSONObject
    private lateinit var root: File
    private lateinit var initial: JSONObject
    private lateinit var session: MobileSession
    private lateinit var client: MobileClient
    private lateinit var originalClient: MobileClient
    private var originalThread = ""
    private var originalChat: MobileThread? = null
    private var originalPresentation: String? = null
    private var clickedPresentation: String? = null
    private var descriptor = ""
    private var credential = ""
    private var operationTargets = emptySet<String>()
    private var beforeOperations = emptyMap<String, SavedOperation>()
    private var seenOperationId: String? = null
    private var originalPending: SavedOperation? = null
    private var expectedRecent = emptyMap<String, List<String>>()
    private var borrowed = false
    private var navigated = false
    private var clickMayHaveDispatched = false
    private var restored = false
    private var protected = false
    private var passed = false
    private var phase = "plan-before-main"
    private val evidence = JSONObject()
    private fun bound(ok: Boolean, code: String) { check(ok) { "F28_MENU_$code" } }
    private fun hash(value: Any) = NativeChatRecoveryCanonical.hash(value)
    private fun hex(value: String) { bound(value.matches(Regex("[a-f0-9]{64}")), "HASH") }
    private fun exact(value: JSONObject, names: Set<String>) = bound(value.keys().asSequence().toSet() == names, "SCHEMA")
    private fun secure(file: File, directory: Boolean) {
        val s = Os.lstat(file.path)
        bound(s.st_uid == android.os.Process.myUid() && (s.st_mode and 511) == if (directory) 448 else 384, "PRIVATE_OWNER_MODE")
        bound(if (directory) OsConstants.S_ISDIR(s.st_mode) else OsConstants.S_ISREG(s.st_mode), "PRIVATE_TYPE")
    }
    private fun readPlan(): JSONObject {
        secure(root, true)
        val file = File(root, "$run.json"); secure(file, false)
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        val raw = FileInputStream(fd).use { input ->
            val a = Os.fstat(fd)
            bound(a.st_size in 1..16384 && a.st_nlink == 1L, "PLAN_BOUND")
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(2048)
            while (true) { val n = input.read(buffer); if (n < 0) break; bound(out.size() + n <= 16384, "PLAN_BOUND"); out.write(buffer, 0, n) }
            val z = Os.fstat(fd); val named = Os.lstat(file.path)
            bound(out.size().toLong() == a.st_size && a.st_dev == z.st_dev && a.st_ino == z.st_ino && a.st_size == z.st_size &&
                a.st_mtime == z.st_mtime && a.st_ctime == z.st_ctime && z.st_dev == named.st_dev && z.st_ino == named.st_ino && z.st_size == named.st_size, "PLAN_STABLE_FD")
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        }
        return JSONObject(raw).also { bound(NativeChatRecoveryCanonical.value(it) == raw, "PLAN_CANONICAL") }
    }
    private fun writeOnce(name: String, value: JSONObject) {
        secure(root, true)
        val fd = Os.open(File(root, name).path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
        FileOutputStream(fd).use { output ->
            val bytes = NativeChatRecoveryCanonical.value(value).toByteArray(); bound(bytes.size <= 65536, "EVIDENCE_BOUND")
            output.write(bytes); output.fd.sync()
        }
        // O_DIRECTORY is not in the public SDK: retain no-follow, avoid a FIFO open wait,
        // and require the held descriptor to be a directory before syncing it.
        val dir = Os.open(root.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        try {
            bound(OsConstants.S_ISDIR(Os.fstat(dir).st_mode), "EVIDENCE_DIR")
            Os.fsync(dir)
        } finally { Os.close(dir) }
    }
    private fun metadata(): JSONObject = NativeSessionStorage.ExistingReader.open(context, "mobile-v1", "agent-hub-mobile-v1").use {
        JSONObject(checkNotNull(it.optionalText("metadata", 1048576)))
    }
    private fun clients(value: JSONObject) = value.getJSONArray("clients").objects().map(::decodeClient)
    private fun operationKey(row: SavedOperation) = row.target + "\u0000" + row.id
    private fun readOperations(): Map<String, SavedOperation> = NativeSessionStorage.ExistingReader.open(context, "mobile-v1", "agent-hub-mobile-v1").use { reader ->
        operationTargets.flatMap { target ->
            reader.optionalText("operations:$target", 1048576)?.let { NativeOperationJournal.validateExistingMigrationRecords(target, JSONObject(it)) }.orEmpty()
        }.associateBy(::operationKey)
    }
    private fun userValues(value: JSONObject): String {
        val allowedNavigation = setOf("selected", "selectedThreads", "workspaces", "recentProjects")
        return hash(JSONObject().apply { for (key in value.keys()) if (key !in allowedNavigation) put(key, value.get(key)) })
    }
    private fun presentation(): String = hash(JSONObject().put("client", session.selectedClient.id)
        .put("thread", session.selectedThread).put("targetKey", session.targetKey).put("textHash", hash(session.text))
        .put("attachments", JSONArray(session.attachments.map { it.manifest.body() })))

    /** ExistingReader only before normal Main activation; no externally supplied plaintext metadata hash. */
    @get:Rule(order = 0) val inputBeforeMain: TestRule = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate(): Unit {
                val args = InstrumentationRegistry.getArguments()
                bound(description.methodName == "currentDefaultMenuOpensOriginalP1WithoutInput" && context.packageName == app, "METHOD")
                bound(args.keySet().all { it in setOf("class", "nativeForeground", "f28MenuOpenRun", "debug", "log", "timeout_msec", "disableAnalytics") }, "ARGS")
                bound(args.getString("class") == selector && args.getString("nativeForeground") == "1", "ARGS")
                run = checkNotNull(args.getString("f28MenuOpenRun")); bound(UUID.fromString(run).toString() == run, "RUN")
                root = File(context.filesDir, "f28-default-menu-open-v1"); plan = readPlan()
                exact(plan, setOf("version", "action", "run", "selector", "package", "appSha256", "runnerSha256", "signerSetSha256", "sourceSetSha256", "target", "threadId", "externalCipherInventorySha256"))
                bound(plan.getInt("version") == 1 && plan.getString("action") == "f28-current-default-mobile-menu-open" && plan.getString("run") == run &&
                    plan.getString("selector") == selector && plan.getString("package") == app && plan.getString("threadId") == thread, "PLAN")
                for (key in listOf("appSha256", "runnerSha256", "signerSetSha256", "sourceSetSha256", "externalCipherInventorySha256")) hex(plan.getString(key))
                bound(hash(plan.getJSONObject("target")) == targetHash && com.agenthub.core.DomainContract.validate(plan.getJSONObject("target").toString(), "target") == null, "TARGET")
                bound(plan.getString("signerSetSha256") == "4f719bb23e1366a472b42507d73147e69b005aaf84f8d12ae020208dc1618965", "SIGNER")
                val installed = NativeChatNotificationNaturalStore.installed(context)
                bound(installed.appHash == plan.getString("appSha256") && installed.runnerHash == plan.getString("runnerSha256") && installed.signerSetHash == plan.getString("signerSetSha256"), "INSTALLED")
                bound(!File(root, "menu-open-P1.once.json").exists(), "ORIGINAL_ONCE_CONSUMED")
                initial = metadata(); bound(initial.getInt("version") == 1, "METADATA")
                val originals = clients(initial); bound(originals.size in 2..16, "CLIENTS")
                bound(originals.count { it.id == host && it.scope?.let { scope -> hash(CodexCommandCodec.target(scope)) } == targetHash && it.codexDirect != null && it.migrationEasyWork == null } == 1, "NORMAL_H13_PAIR_REQUIRED")
                operationTargets = originals.flatMap { c ->
                    c.operationTargets(null) + c.operationTargets(initial.getJSONObject("selectedThreads").optString(c.cacheKey(null), "").takeIf { it.isNotEmpty() }) +
                        if (c.id == host) c.operationTargets(thread) else emptyList()
                }.toSet()
                bound(operationTargets.size <= 128, "OPERATION_TARGET_BOUND")
                beforeOperations = readOperations()
                evidence.put("initialMetadataCanonicalSha256", hash(initial)).put("externalCipherInventorySha256", plan.getString("externalCipherInventorySha256"))
                base.evaluate()
            }
        }
    }
    // Compose test ownership is installed before normal Main activation; no second Activity launcher.
    @get:Rule(order = 1) val ui = createEmptyComposeRule()
    @get:Rule(order = 2) val foreground: TestRule = NativeTestForeground()

    private suspend fun borrow(): Unit {
        withTimeout(30000) {
            while (!borrowed) {
                withContext(Dispatchers.Main) {
                    val main = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filter { it.javaClass == MainActivity::class.java }.filterIsInstance<MainActivity>().singleOrNull()
                    if (main != null && !main.session.restoring) {
                        session = main.session
                        bound(session.usageForeground && !session.localRecoveryRequired && session.attachmentStorageReady, "DEFAULT_READY")
                        bound(session.migrationStorage.coordinationKey == File(context.noBackupFilesDir, "mobile-v1").canonicalPath && !session.migrationStorage.localInspectionOnly, "DEFAULT_STORAGE")
                        session.migrationStorage.requireRawWriterOwner()
                        originalClient = session.selectedClient; originalThread = session.selectedThread; originalChat = session.currentChat
                        bound(!originalClient.isDemo, "ORIGINAL_REAL_DEFAULT")
                        bound(originalClient.id != host || originalThread.isNotEmpty(), "ORIGINAL_H13_EMPTY_CHAT_RESTORE_UNSUPPORTED")
                        bound(originalThread.isEmpty() || originalChat != null, "ORIGINAL_CHAT_NOT_AVAILABLE")
                        bound(originalClient.id == initial.getString("selected") && originalThread == initial.getJSONObject("selectedThreads").optString(originalClient.cacheKey(null), ""), "ORIGINAL_SELECTION_RESTORED_FROM_STORAGE")
                        originalPresentation = presentation()
                        client = session.clients.single { it.id == host && it.scope?.let { scope -> hash(CodexCommandCodec.target(scope)) } == targetHash }
                        bound(!client.isDemo && client.codexDirect != null && client.migrationEasyWork == null && client.agentConnection == null, "NORMAL_H13")
                        descriptor = hash(encodeClient(client)); credential = hash(session.migrationStorage.token(client.id)); bound(session.migrationStorage.token(client.id).isNotBlank(), "ORIGINAL_AUTH")
                        borrowed = true
                    }
                }
                if (!borrowed) delay(50)
            }
        }
        withContext(Dispatchers.IO) { session.migrationStorage.awaitQueuedWrites(); bound(userValues(metadata()) == userValues(initial) && readOperations().mapValues { hash(it.value.body()) } == beforeOperations.mapValues { hash(it.value.body()) }, "NORMAL_STARTUP_CHANGED_ORIGINALS") }
        expectedRecent = NativeRecentProjects.read(initial)
    }
    private fun recordProjectNavigation(c: MobileClient, t: MobileThread) {
        val scope = NativeRecentProjects.scope(c); val project = t.projectId
        if (scope != null && project != null) expectedRecent = expectedRecent + (scope to NativeRecentProjects.record(expectedRecent[scope].orEmpty(), project))
    }
    private suspend fun navigate(): Unit {
        phase = "normal-navigation-to-original-P1"
        withContext(Dispatchers.Main) { navigated = true; session.select(client) }
        withTimeout(30000) {
            while (true) {
                val ready = withContext(Dispatchers.Main) { session.threads.singleOrNull { it.id == thread && it.hostId == host && it.archived != true } }
                if (ready != null) { withContext(Dispatchers.Main) { recordProjectNavigation(client, ready); session.selectThread(ready) }; break }
                delay(100)
            }
            while (!withContext(Dispatchers.Main) { session.selectedClient.id == host && session.selectedThread == thread && session.supportsDesktopOpen && !session.desktopOpenBusy && !session.historyLoading }) delay(100)
        }
        withContext(Dispatchers.Main) { bound(hash(encodeClient(session.selectedClient)) == descriptor && hash(session.migrationStorage.token(client.id)) == credential, "TARGET_OR_AUTH_CHANGED"); clickedPresentation = presentation() }
        val candidates = beforeOperations.values.filter { it.kind == "open" && it.target in client.operationTargets(thread) && it.phase !in setOf("opened", "rejected", "failed") }
        bound(candidates.size <= 1, "MULTIPLE_UNKNOWN_OPEN"); originalPending = candidates.singleOrNull(); bound(originalPending?.migrationReadOnly != true, "MIGRATED_AUDIT_NOT_RUNTIME_AUTH")
        originalPending?.let { bound(JSONObject(it.intent).getString("hostId") == host && JSONObject(it.intent).getString("threadId") == thread, "FOREIGN_ORIGINAL_OPEN") }
        seenOperationId = originalPending?.id
    }
    private fun verifyOldOperations(now: Map<String, SavedOperation>) {
        for ((key, old) in beforeOperations) {
            val current = checkNotNull(now[key]) { "F28_MENU_OLD_OPERATION_REMOVED" }
            if (originalPending?.let { operationKey(it) } == key) {
                bound(hash(current.copy(phase = old.phase, result = old.result, error = old.error, submissionAttempted = old.submissionAttempted).body()) == hash(old.body()), "ORIGINAL_OPEN_INTENT_CHANGED")
            } else bound(hash(current.body()) == hash(old.body()), "OTHER_OLD_OPERATION_CHANGED")
        }
        val additions = now.filterKeys { it !in beforeOperations }.values
        bound(additions.size <= if (originalPending == null && clickMayHaveDispatched) 1 else 0, "EXTRA_OPERATION")
        for (row in additions) bound(row.kind == "open" && row.target in client.operationTargets(thread) && JSONObject(row.intent).getString("hostId") == host && JSONObject(row.intent).getString("threadId") == thread, "FOREIGN_OPERATION")
    }
    private suspend fun observeOriginal(): SavedOperation {
        phase = "one-menu-click-original-operation-observation"
        return withTimeout(45000) {
            while (true) {
                val now = withContext(Dispatchers.IO) { readOperations() }; verifyOldOperations(now)
                val selected = if (originalPending != null) now[operationKey(checkNotNull(originalPending))] else now.filterKeys { it !in beforeOperations }.values.singleOrNull()
                selected?.let { row ->
                    bound(seenOperationId == null || seenOperationId == row.id, "OP_ID_CHANGED"); seenOperationId = row.id
                    evidence.put("operationId", row.id).put("operationTarget", row.target).put("intentSha256", row.intentSha256).put("phase", row.phase).put("reusedOriginalUnknown", originalPending != null)
                }
                val busy = withContext(Dispatchers.Main) { bound(session.selectedClient.id == host && session.selectedThread == thread && presentation() == clickedPresentation, "DRAFT_OR_TARGET_CHANGED"); session.desktopOpenBusy }
                if (!busy) {
                    val row = checkNotNull(selected) { "F28_MENU_NO_OPEN_RECORD" }
                    bound(row.phase == "opened" && row.error == null, "UNKNOWN_OR_REJECTED_KEEP_ORIGINAL_ID")
                    val receipt = CodexCommandCodec.open(JSONObject(checkNotNull(row.result)))
                    bound(receipt.hostId == host && receipt.threadId == thread && receipt.operationId == row.id && receipt.phase == "opened" && receipt.error == null, "RECEIPT")
                    evidence.put("openReceipt", encodeOpen(receipt)); return@withTimeout row
                }
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") error("F28_MENU_UNREACHABLE")
        }
    }
    private suspend fun verifyFreshOwner(row: SavedOperation) {
        phase = "independent-live-owner-read-after-original-receipt"
        // Production GET ports only. No open/submit/query API is called by the test.
        val reads: HostReads = NativeHostReads { session.migrationStorage.token(it) }
        val workspace = withTimeout(15000) { reads.workspace(client) }
        val history = withTimeout(15000) { reads.history(client, thread) }
        bound(workspace.hostId == host && sameScope(workspace.scope, client.scope) && workspace.runtimeConnected != false &&
            workspace.capabilities.containsAll(listOf("desktop:open", "commands:target-bound:v1")), "FRESH_WORKSPACE")
        bound(history.hostId == host && history.threadId == thread && sameScope(history.scope, client.scope) && history.runtime?.canControl == true, "FRESH_OWNER")
        // Use the same production predicate; unlike a historical page alone, original opened receipt is mandatory.
        bound(NativeDesktopOpenResult(row, workspace, history).ownerConfirmed, "OWNER_NOT_CONFIRMED")
        withContext(Dispatchers.Main) { bound(session.selectedThread == thread && presentation() == clickedPresentation && hash(encodeClient(session.selectedClient)) == descriptor && hash(session.migrationStorage.token(client.id)) == credential, "LATE_TARGET_OR_DRAFT")
            bound(session.error == null && session.runtime?.canControl == true && session.supportsDesktopOpen, "LIVE_SESSION_OWNER_NOT_READY")
        }
        evidence.put("ownerConfirmed", true).put("freshWorkspaceTargetSha256", hash(CodexCommandCodec.target(checkNotNull(workspace.scope))))
            .put("freshHistoryTargetSha256", hash(CodexCommandCodec.target(checkNotNull(history.scope)))).put("freshCanControl", true).put("liveSessionCanControl", true).put("liveSessionErrorAbsent", true)
            .put("remoteTurnMayAlreadyBeRunning", true).put("remoteTurnStateObserved", history.runtime?.state)
    }
    private suspend fun restoreAndProtect() {
        if (!borrowed) return
        phase = "restore-visible-original-computer-and-chat"
        withContext(Dispatchers.Main) {
            // A still-running owner is never canceled or retargeted solely for cleanup.
            bound(!session.desktopOpenBusy, "OPEN_STILL_BUSY_PRESERVE_SCENE")
            if (navigated) {
                session.select(originalClient)
            }
        }
        if (navigated && originalClient.id == host && originalThread.isNotEmpty()) {
            withTimeout(20000) {
                while (!withContext(Dispatchers.Main) { session.threads.any { it.id == originalThread && it.hostId == originalClient.id } }) delay(100)
            }
            withContext(Dispatchers.Main) {
                if (session.selectedThread != originalThread) { val old = checkNotNull(originalChat); recordProjectNavigation(originalClient, old); session.selectThread(old) }
            }
        }
        withContext(Dispatchers.Main) { bound(session.selectedClient.id == originalClient.id && session.selectedThread == originalThread && presentation() == originalPresentation, "RESTORE_ORIGINAL_PRESENTATION"); restored = true }
        withContext(Dispatchers.IO) {
            session.migrationStorage.awaitQueuedWrites()
            val after = metadata(); val keys = initial.keys().asSequence().toSet()
            bound((after.keys().asSequence().toSet() - setOf("recentProjects")) == (keys - setOf("recentProjects")), "METADATA_KEYS")
            val navigation = setOf("selected", "selectedThreads", "workspaces", "recentProjects")
            for (key in keys - navigation) bound(hash(initial.get(key)) == hash(after.get(key)), "OLD_LOGICAL_FIELD_CHANGED")
            val a = initial.getJSONObject("selectedThreads"); val z = after.getJSONObject("selectedThreads"); val ownKey = client.cacheKey(null)
            for (key in a.keys()) if (key != ownKey) bound(z.has(key) && hash(a.get(key)) == hash(z.get(key)), "OTHER_CHAT_SELECTION_CHANGED")
            bound((z.keys().asSequence().toSet() - a.keys().asSequence().toSet()).all { it == ownKey }, "NEW_FOREIGN_CHAT_SELECTION")
            bound(after.getString("selected") == initial.getString("selected") && NativeRecentProjects.read(after) == expectedRecent, "VISIBLE_SELECTION_OR_RECENTS")
            val oldWorkspaces = initial.getJSONObject("workspaces"); val newWorkspaces = after.getJSONObject("workspaces")
            val allowedWorkspaces = setOf(client.cacheKey(null), originalClient.cacheKey(null))
            for (key in oldWorkspaces.keys()) if (key !in allowedWorkspaces) bound(newWorkspaces.has(key) && hash(oldWorkspaces.get(key)) == hash(newWorkspaces.get(key)), "FOREIGN_WORKSPACE_CHANGED")
            bound((newWorkspaces.keys().asSequence().toSet() - oldWorkspaces.keys().asSequence().toSet()).all { it in allowedWorkspaces }, "FOREIGN_WORKSPACE_ADDED")
            verifyOldOperations(readOperations()); protected = true
            evidence.put("finalMetadataCanonicalSha256", hash(after)).put("protectedLogicalFields", JSONArray((keys - navigation).sorted()))
        }
    }
    @Test fun currentDefaultMenuOpensOriginalP1WithoutInput(): Unit {
        var failure: Throwable? = null
        try {
            runBlocking { borrow(); navigate() }
            phase = "real-Compose-menu"
            ui.onNodeWithTag("chat-menu").assertIsDisplayed().assertHasClickAction().performClick()
            val text = NativeL10n(context, session.language).tr("在电脑上打开")
            bound(ui.onAllNodesWithTag("chat-open-on-desktop").fetchSemanticsNodes().size == 1, "NO_UNIQUE_SAFE_UI")
            val item = ui.onNodeWithTag("chat-open-on-desktop").assertIsDisplayed().assertIsEnabled().assertHasClickAction().assertTextContains(text)
            val bounds = item.fetchSemanticsNode().boundsInRoot
            evidence.put("menuUI", JSONObject().put("menuTag", "chat-menu").put("itemTag", "chat-open-on-desktop").put("text", text)
                .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom))
            writeOnce("menu-open-P1.once.json", JSONObject().put("run", run).put("selector", selector).put("threadId", thread)
                .put("targetSha256", targetHash).put("planSha256", hash(plan)).put("originalPendingId", originalPending?.id ?: JSONObject.NULL))
            clickMayHaveDispatched = true
            item.performClick() // The sole action; never invoke Session.open or a port directly.
            runBlocking { val row = observeOriginal(); verifyFreshOwner(row) }
            passed = true
        } catch (t: Throwable) { failure = t; evidence.put("failureClass", t.javaClass.name) }
        finally {
            try { runBlocking { withContext(NonCancellable) { withTimeout(35000) { restoreAndProtect() } } } }
            catch (t: Throwable) { passed = false; evidence.put("restoreFailureClass", t.javaClass.name); if (failure == null) failure = t }
            val safe = JSONObject().put("run", run).put("selector", selector).put("phase", phase).put("evidence", evidence)
                .put("menuClickMayHaveDispatched", clickMayHaveDispatched).put("maximumMenuOpenClicks", 1).put("operationId", seenOperationId ?: JSONObject.NULL)
                .put("originalSelectionRestored", restored).put("originalLogicalValuesProtected", protected).put("passed", passed && restored && protected)
                .put("noInputOrNewOriginByEntry", true).put("noStopOrPairingByEntry", true).put("externalFullCipherPostRequired", true)
                .put("unknownMustReconcileOriginalOperationNoRerun", true).put("sharedMainSessionOwnedByActivityNotClosed", true)
            try { writeOnce("$run.result.json", safe) } catch (_: Exception) { passed = false; safe.put("passed", false).put("evidenceSaveFailed", true) }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("f28DefaultMenuOpen", safe.toString()) })
        }
        bound(failure == null && passed && restored && protected, "NOT_CONFIRMED_KEEP_ORIGINAL_OPERATION")
    }
}
