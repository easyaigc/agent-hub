package com.agenthub.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/**
 * A different, closed debug fixture family. It does not relax the legacy cold plan,
 * create a default installation, accept configuration from an Intent, or run transport.
 * Admission requires an already running instrumented reader. This is NOT a natural OS cold proof.
 */
internal object NativeChatNotificationGraphColdStart {
    private const val PACKAGE = "com.easyai.agenthub.android.acceptance"
    private const val ROOT = "P9-notification-main-graph-v1"
    private const val KIND = "existing-receipt-main-v1"
    private const val MAX_RECORD = 512 * 1024
    private const val MAX_MEMBERS = 24
    private const val MAX_NAMESPACE = 2 * 1024 * 1024
    private val nonce = UUID.randomUUID().toString()
    private data class Permit(val plan: Plan, val storage: NativeSessionStorage, val readerOperation: String)
    @Volatile private var permit: Permit? = null
    @Volatile private var selected: Permit? = null

    internal data class Plan(
        val base: NativeChatNotificationColdPlan,
        val writerOperation: String,
        val writerStartTicks: Long,
        val issuedElapsed: Long,
        val bootCount: Int,
        val intentHash: String,
        val pendingNonce: String,
        val terminalTicket: String,
        val terminalHash: String,
        val pendingAckHash: String,
        val metadataHash: String,
        val preferencesHash: String,
        val identityHash: String,
        val recoveryHash: String,
        val recoveryIntentHash: String,
        val aliasSetHash: String,
        val members: Map<String, String>,
    ) {
        val recoveryOperation get() = NativeChatNotificationGraphColdStart.uuidFor(base.lease, "unknown-recovery")
        val recoveryEpoch get() = NativeChatNotificationGraphColdStart.uuidFor(base.lease, "recovery-epoch")
        val pendingOperation get() = NativeChatNotificationGraphColdStart.uuidFor(base.lease, "pending-notice")
        val terminalOperation get() = NativeChatNotificationGraphColdStart.uuidFor(base.lease, "terminal-notice")
        val laterDraft get() = "Owned graph later other draft " + base.lease
        val fingerprint get() = NativeChatRecoveryCanonical.hash(json())
        fun json() = JSONObject().put("version", 1).put("kind", KIND).put("entryMode", "instrumented-first-main")
            .put("base", base.json()).put("writerOperation", writerOperation).put("writerStartTicks", writerStartTicks)
            .put("issuedElapsed", issuedElapsed).put("bootCount", bootCount).put("intentHash", intentHash)
            .put("pendingNonce", pendingNonce).put("terminalTicket", terminalTicket).put("terminalHash", terminalHash)
            .put("pendingAckHash", pendingAckHash).put("metadataHash", metadataHash).put("preferencesHash", preferencesHash).put("identityHash", identityHash)
            .put("recoveryHash", recoveryHash).put("recoveryIntentHash", recoveryIntentHash).put("aliasSetHash", aliasSetHash).put("members", JSONObject(members))

        companion object {
            fun decode(raw: JSONObject): Plan {
                NativeChatNotificationWire.keys(raw, setOf("version", "kind", "entryMode", "base", "writerOperation",
                    "writerStartTicks", "issuedElapsed", "bootCount", "intentHash", "pendingNonce", "terminalTicket",
                    "terminalHash", "pendingAckHash", "metadataHash", "preferencesHash", "identityHash", "recoveryHash", "recoveryIntentHash", "aliasSetHash", "members"))
                require(raw.get("version") == 1 && raw.getString("kind") == KIND && raw.getString("entryMode") == "instrumented-first-main")
                fun hash(key: String) = NativeChatNotificationWire.text(raw, key).also { require(chatNoticeHash(it)) }
                fun uuid(key: String) = NativeChatNotificationColdPlan.uuid(NativeChatNotificationWire.text(raw, key))
                val base = NativeChatNotificationColdPlan.decode(raw.getJSONObject("base"))
                require(base.case == NativeChatNotificationColdCase.ORIGINAL)
                val memberObject = raw.getJSONObject("members")
                require(memberObject.length() in 1..MAX_MEMBERS)
                val members = memberObject.keys().asSequence().associateWith { name ->
                    require(name.length == 68 && name.endsWith(".bin") && chatNoticeHash(name.removeSuffix(".bin")))
                    memberObject.get(name).also { require(it is String && chatNoticeHash(it)) } as String
                }
                val ticks = NativeChatNotificationWire.long(raw, "writerStartTicks")
                val elapsed = NativeChatNotificationWire.long(raw, "issuedElapsed")
                val boot = NativeChatNotificationWire.long(raw, "bootCount")
                require(ticks > 0 && elapsed >= 0 && boot in 0..Int.MAX_VALUE.toLong())
                return Plan(base, uuid("writerOperation"), ticks, elapsed, boot.toInt(), hash("intentHash"),
                    uuid("pendingNonce"), uuid("terminalTicket"), hash("terminalHash"), hash("pendingAckHash"),
                    hash("metadataHash"), hash("preferencesHash"), hash("identityHash"), hash("recoveryHash"), hash("recoveryIntentHash"), hash("aliasSetHash"), members).also {
                    require(it.terminalTicket != base.ticket &&
                        it.writerOperation !in setOf(it.recoveryOperation, it.pendingOperation, it.terminalOperation))
                }
            }
        }
    }

    internal enum class Screen(val filename: String) {
        FIRST_MAIN("first-main.png"), LATER_DRAFT("later-draft.png")
    }

    private fun guard(context: Context) {
        require(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == PACKAGE && context.packageName == PACKAGE)
        require(context.applicationInfo.uid == Process.myUid() &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val hook = NativeChatNotificationColdStart::class.java.getField("GRAPH_HOOK_VERSION")
        require(hook.type == Int::class.javaPrimitiveType && hook.getInt(null) == 1) {
            "The target App must contain the graph selector before any fixture mutation"
        }
    }
    private fun rawParent(context: Context) = File(File(context.applicationInfo.dataDir).canonicalFile, "no_backup")
    private fun directory(file: File) {
        val stat = Os.lstat(file.path)
        require(OsConstants.S_ISDIR(stat.st_mode) && !Files.isSymbolicLink(file.toPath()) &&
            stat.st_uid == Process.myUid() && stat.st_mode and 0x1ff == 0x1c0)
    }
    private fun systemParentDirectory(file: File) {
        val stat = Os.lstat(file.path)
        require(OsConstants.S_ISDIR(stat.st_mode) && !Files.isSymbolicLink(file.toPath()) &&
            stat.st_uid == Process.myUid() && stat.st_mode and 0xfff in setOf(0x1c0, 0x1f9))
    }
    private fun parent(context: Context): File {
        guard(context)
        return rawParent(context).also {
            // Android owns this existing parent; each fixture and storage namespace remains strictly 0700.
            // Check before the getter so a read cannot create a missing parent ahead of the write intent.
            systemParentDirectory(it)
            require(it.canonicalFile == it && context.noBackupFilesDir.canonicalFile == it)
        }
    }
    private fun root(context: Context) = File(parent(context), ROOT)
    private fun namespace(lease: String) = "P9-native-system-" + NativeChatNotificationColdPlan.uuid(lease)
    private fun alias(lease: String) = "p9-main-graph-" + NativeChatNotificationColdPlan.uuid(lease)
    private fun namespaceRoot(context: Context, lease: String) = File(parent(context), namespace(lease))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun aliases(): List<String> {
        val values = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases()
        val names = mutableListOf<String>()
        while (values.hasMoreElements()) { require(names.size < 512); names.add(values.nextElement()) }
        return names.sorted()
    }
    private fun aliasHash(names: List<String>) = sha(names.joinToString("") { it.length.toString() + ":" + it }.toByteArray(Charsets.UTF_8))
    private fun uuidFor(lease: String, purpose: String) = UUID.nameUUIDFromBytes((lease + ":graph:" + purpose).toByteArray(Charsets.UTF_8)).toString()
    private fun stamp(file: File) = Os.lstat(file.path).let { s ->
        listOf(s.st_dev, s.st_ino, s.st_mode.toLong(), s.st_nlink, s.st_uid.toLong(), s.st_gid.toLong(),
            s.st_size, s.st_mtime, s.st_ctime)
    }
    private fun read(file: File, maximum: Int = MAX_RECORD): ByteArray {
        val before = stamp(file); val stat = Os.lstat(file.path)
        require(OsConstants.S_ISREG(stat.st_mode) && !Files.isSymbolicLink(file.toPath()) &&
            stat.st_uid == Process.myUid() && stat.st_nlink == 1L && stat.st_mode and 0x1ff == 0x180 &&
            stat.st_size in 1..maximum.toLong())
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(out.size().toLong() + count <= maximum); out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        require(stamp(file) == before && bytes.size.toLong() == stat.st_size)
        return bytes
    }
    private fun syncDirectory(file: File, systemParent: Boolean = false) {
        fun checkedDirectory() { if (systemParent) systemParentDirectory(file) else directory(file) }
        checkedDirectory()
        val before = Os.lstat(file.path)
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        try {
            val opened = Os.fstat(fd)
            require(OsConstants.S_ISDIR(opened.st_mode) && opened.st_uid == Process.myUid() &&
                opened.st_mode == before.st_mode && opened.st_gid == before.st_gid &&
                opened.st_dev == before.st_dev && opened.st_ino == before.st_ino)
            Os.fsync(fd)
            checkedDirectory()
            val after = Os.lstat(file.path)
            require(after.st_dev == opened.st_dev && after.st_ino == opened.st_ino &&
                after.st_mode == opened.st_mode && after.st_gid == opened.st_gid)
        } finally { Os.close(fd) }
    }
    private fun append(context: Context, name: String, value: JSONObject) {
        require(name in setOf("intent.json", "armed.json", "selected.json", "complete.json", "failed.json", "release.json"))
        val folder = root(context); directory(folder)
        val bytes = value.toString().toByteArray(Charsets.UTF_8); require(bytes.size in 1..16_384)
        val fd = Os.open(File(folder, name).path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        FileOutputStream(fd).use { it.write(bytes); it.fd.sync() }
        syncDirectory(folder)
    }
    private fun names(context: Context) = checkNotNull(root(context).listFiles()).map { it.name }.toSet()
    private fun members(context: Context, lease: String): Map<String, String> {
        val folder = namespaceRoot(context, lease); directory(folder)
        val rows = checkNotNull(folder.listFiles()); require(rows.size in 1..MAX_MEMBERS)
        var total = 0L
        return rows.sortedBy { it.name }.associate { file ->
            require(file.name.length == 68 && file.name.endsWith(".bin") && chatNoticeHash(file.name.removeSuffix(".bin")))
            val bytes = read(file); total += bytes.size; require(total <= MAX_NAMESPACE)
            file.name to sha(bytes)
        }
    }
    private fun text(storage: NativeSessionStorage, record: String) =
        checkNotNull(storage.read(record)).also { require(it.toByteArray(Charsets.UTF_8).size in 1..MAX_RECORD) }
    private fun receipt(storage: NativeSessionStorage, ticket: String): NativeChatNoticeReceipt {
        val rows = JSONObject(text(storage, NativeChatNotificationStore.RECORD)).getJSONArray("receipts")
        require(rows.length() == 2)
        return (0 until rows.length()).map { NativeChatNotificationCodec.receipt(rows.getJSONObject(it)) }.single { it.ticket == ticket }
    }
    private fun receiptHash(value: NativeChatNoticeReceipt) = NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.receipt(value))
    private fun processTicks() = File("/proc/self/stat").readText().substringAfterLast(") ").trim().split(Regex("\\s+"))[19].toLong().also { require(it > 0) }
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    private fun apkHash(context: Context, runner: Boolean): String {
        guard(context)
        val app = if (runner) context.packageManager.getApplicationInfo(PACKAGE + ".test", 0).also {
            require(context.packageManager.checkSignatures(PACKAGE, it.packageName) == PackageManager.SIGNATURE_MATCH)
        } else context.applicationInfo
        val file = File(app.sourceDir).canonicalFile
        require(file.isFile && file.length() in 1..128L * 1024 * 1024)
        val hash = MessageDigest.getInstance("SHA-256"); var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= 128L * 1024 * 1024); hash.update(buffer, 0, count)
            }
        }
        require(total == file.length())
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun existingStorage(context: Context, lease: String, readOnly: Boolean = false): NativeSessionStorage {
        directory(namespaceRoot(context, lease))
        require(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias(lease)))
        return NativeSessionStorage(context, namespace(lease), alias(lease), existingKeyOnly = true,
            existingNamespaceOnly = true, existingReadOnly = readOnly)
    }
    private fun storedPlan(context: Context, lease: String): Plan {
        guard(context); directory(root(context))
        val original = read(File(root(context), "intent.json"), 16_384)
        val plan = Plan.decode(JSONObject(String(read(File(root(context), "armed.json"), 16_384), Charsets.UTF_8)))
        require(plan.base.lease == lease && sha(original) == plan.intentHash)
        val intent = JSONObject(String(original, Charsets.UTF_8))
        NativeChatNotificationWire.keys(intent, setOf("version", "kind", "entryMode", "lease", "writerOperation",
            "writerPid", "writerNonce", "writerStartTicks", "issuedAt", "issuedElapsed", "bootCount", "appHash", "runnerHash",
            "aliasCountBefore", "aliasSetBeforeHash"))
        fun text(key: String) = NativeChatNotificationWire.text(intent, key)
        fun number(key: String) = NativeChatNotificationWire.long(intent, key)
        require(intent.get("version") == 1 && text("kind") == KIND &&
            text("entryMode") == "instrumented-first-main" && text("lease") == lease &&
            text("writerOperation") == plan.writerOperation && number("writerPid") == plan.base.writerPid.toLong() &&
            text("writerNonce") == plan.base.writerNonce && number("writerStartTicks") == plan.writerStartTicks &&
            number("issuedAt") == plan.base.issuedAt && number("issuedElapsed") == plan.issuedElapsed &&
            number("bootCount") == plan.bootCount.toLong() && text("appHash") == plan.base.appHash &&
            text("runnerHash") == plan.base.runnerHash)
        require(number("aliasCountBefore") in 0L..511L && chatNoticeHash(text("aliasSetBeforeHash")))
        return plan
    }
    private fun armed(context: Context, lease: String): Plan {
        require(names(context) == setOf("intent.json", "armed.json")) { "Original attempt retained; never replay a partial, selected or completed graph" }
        return storedPlan(context, lease)
    }
    private fun currentClock(context: Context, plan: Plan) {
        require(boot(context) == plan.bootCount)
        require(SystemClock.elapsedRealtime() - plan.issuedElapsed in 0..NativeChatNotificationColdPlan.TTL)
        require(System.currentTimeMillis() - plan.base.issuedAt in 0..NativeChatNotificationColdPlan.TTL)
    }
    private fun verifyOriginal(context: Context, plan: Plan, storage: NativeSessionStorage) {
        require(aliasHash(aliases()) == plan.aliasSetHash)
        require(members(context, plan.base.lease) == plan.members)
        val pending = receipt(storage, plan.base.ticket); val terminal = receipt(storage, plan.terminalTicket)
        require(pending.consumed && pending.navigationClaim == plan.pendingNonce && receiptHash(pending) == plan.base.receiptHash)
        require(terminal.consumed && terminal.navigationClaim == null && receiptHash(terminal) == plan.terminalHash)
        require(pending.event.target == plan.base.target && terminal.event.target == plan.base.target &&
            pending.lease.target == plan.base.target &&
            pending.event.threadId == plan.base.originalThread && terminal.event.threadId == plan.base.originalThread &&
            pending.event.kind == NativeChatNoticeKind.UNCERTAIN && terminal.event.kind == NativeChatNoticeKind.APPROVAL &&
            pending.event.operationId == plan.pendingOperation && terminal.event.operationId == plan.terminalOperation &&
            pending.lease.credentialEpoch == plan.base.authority && terminal.lease == pending.lease)
        require(pending.expiresAt == plan.base.issuedAt + NativeChatNotificationStore.TTL_MILLIS &&
            terminal.expiresAt == pending.expiresAt &&
            java.time.Instant.parse(pending.event.occurredAt).toEpochMilli() == plan.base.issuedAt &&
            java.time.Instant.parse(terminal.event.occurredAt).toEpochMilli() == plan.base.issuedAt)
        require(NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.preferences(NativeChatNotificationStore(storage).preferences())) == plan.preferencesHash)
        require(sha(text(storage, "metadata").toByteArray(Charsets.UTF_8)) == plan.metadataHash &&
            sha(text(storage, NativeChatNotificationIdentity.RECORD).toByteArray(Charsets.UTF_8)) == plan.identityHash &&
            sha(text(storage, NativeChatNotificationRecoveryStore.RECORD).toByteArray(Charsets.UTF_8)) == plan.recoveryHash)
        val metadata = checkNotNull(storage.metadata()); val clients = metadata.getJSONArray("clients"); require(clients.length() == 1)
        val client = decodeClient(clients.getJSONObject(0))
        require(client.id == plan.base.target.hostId && NativeChatNoticeTarget.from(checkNotNull(client.scope)) == plan.base.target &&
            client.endpoint == plan.base.endpoint && storage.token(client.id) == plan.base.token)
        require(members(context, plan.base.lease) == plan.members) { "Original verification must remain read-only" }
        require(aliasHash(aliases()) == plan.aliasSetHash)
    }
    private suspend fun verifyUnknown(storage: NativeSessionStorage, plan: Plan) {
        val identityBefore = text(storage, NativeChatNotificationIdentity.RECORD)
        val recoveryBefore = text(storage, NativeChatNotificationRecoveryStore.RECORD)
        require(sha(identityBefore.toByteArray(Charsets.UTF_8)) == plan.identityHash &&
            sha(recoveryBefore.toByteArray(Charsets.UTF_8)) == plan.recoveryHash)
        val identity = NativeChatNotificationIdentity.load(storage)
        val operation = NativeChatNotificationRecoveryStore(storage).view(identity, plan.base.target).operations.single()
        require(operation.state == "unknown" && operation.receipt == null &&
            operation.original.operationId == plan.recoveryOperation && operation.original.epoch == plan.recoveryEpoch &&
            sha(operation.original.encoded().toByteArray(Charsets.UTF_8)) == plan.recoveryIntentHash)
        require(text(storage, NativeChatNotificationIdentity.RECORD) == identityBefore &&
            text(storage, NativeChatNotificationRecoveryStore.RECORD) == recoveryBefore)
    }

    fun hasMaterial(context: Context): Boolean {
        if (!BuildConfig.DEBUG || context.packageName != PACKAGE) return false
        val file = File(rawParent(context), ROOT)
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return false
        guard(context); parent(context); directory(file)
        return true // Empty/partial/unknown material must also block ordinary default selection.
    }

    /** New fixture only. Its raw immutable intent is durable before namespace/key creation. */
    suspend fun seedWriter(context: Context, lease: String, operation: String): Plan {
        guard(context); NativeChatNotificationColdPlan.uuid(lease); NativeChatNotificationColdPlan.uuid(operation)
        NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(context)
        require(permit == null && selected == null && !NativeChatNotificationColdStart.naturalModeEntered)
        val parent = parent(context)
        require(Files.notExists(root(context).toPath(), NOFOLLOW_LINKS) &&
            Files.notExists(namespaceRoot(context, lease).toPath(), NOFOLLOW_LINKS))
        require(Files.notExists(File(parent, NativeChatNotificationColdRouterFiles.NAMESPACE).toPath(), NOFOLLOW_LINKS))
        require(!NativeNaturalEntryFence.hasMaterial(parent))
        require(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias(lease)))
        val aliasesBefore = aliases()
        val actors = checkNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses).filter { it.uid == Process.myUid() }
        require(actors.size == 1 && actors.single().pid == Process.myPid())
        val appHash = apkHash(context, false); val runnerHash = apkHash(context, true)
        val issuedAt = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime(); val boot = boot(context); val ticks = processTicks()
        require(issuedAt > 0 && elapsed >= 0 && boot >= 0)
        require(operation !in setOf(uuidFor(lease, "unknown-recovery"), uuidFor(lease, "pending-notice"), uuidFor(lease, "terminal-notice")))
        val intent = JSONObject().put("version", 1).put("kind", KIND).put("entryMode", "instrumented-first-main")
            .put("lease", lease).put("writerOperation", operation).put("writerPid", Process.myPid()).put("writerNonce", nonce)
            .put("writerStartTicks", ticks).put("issuedAt", issuedAt).put("issuedElapsed", elapsed).put("bootCount", boot)
            .put("appHash", appHash).put("runnerHash", runnerHash)
            .put("aliasCountBefore", aliasesBefore.size).put("aliasSetBeforeHash", aliasHash(aliasesBefore))
        Os.mkdir(root(context).path, 0x1c0); syncDirectory(parent, systemParent = true); append(context, "intent.json", intent)
        val storage = NativeSessionStorage(context, namespace(lease), alias(lease))
        try {
            val template = NativeChatNotificationColdPlan(lease, NativeChatNotificationColdCase.ORIGINAL, UUID.randomUUID().toString(),
                Process.myPid(), nonce, issuedAt, appHash, runnerHash, "0".repeat(64), "0".repeat(64))
            val ports = NativeChatNotificationColdPorts(template, true)
            val client = withContext(Dispatchers.Main.immediate) {
                val session = MobileSession(storage, ports.sessionReads, ports.commands)
                session.resetAcceptance(ports.sessionReads, ports.commands)
                val client = session.connect("Owned graph fixture", template.endpoint, template.token)
                session.selectThread(ports.sessionReads.workspace(client).threads.single { it.id == template.originalThread })
                session.text = template.originalDraft
                session.selectThread(ports.sessionReads.workspace(client).threads.single { it.id == template.otherThread })
                session.text = template.otherDraft
                session.leaveForeground(); require(session.operationRecords.isEmpty()); client
            }
            storage.awaitQueuedWrites()
            val captured = NativeChatNotificationSavedConnection.capture(client, template.token)
            val notices = NativeChatNotificationStore(storage)
            val preferences = notices.update { it.copy(enabled = true, soundEnabled = false, badgeEnabled = false, showThreadTitle = false,
                kinds = setOf(NativeChatNoticeKind.UNCERTAIN, NativeChatNoticeKind.APPROVAL, NativeChatNoticeKind.COMPLETED)) }
            val noticeLease = notices.bind(template.target, captured.snapshot.credentialEpoch)
            fun event(label: String, kind: NativeChatNoticeKind, id: String) = NativeChatNotificationEvent(
                NativeChatNotificationWire.hash(lease + ":" + label), template.target, kind, java.time.Instant.ofEpochMilli(issuedAt).toString(),
                template.originalThread, operationId = id, threadTitle = "Owned graph private title", taskSummary = "Owned graph synthetic summary")
            val pending = checkNotNull(notices.claim(event("pending", NativeChatNoticeKind.UNCERTAIN, uuidFor(lease, "pending-notice")), noticeLease, issuedAt))
            notices.finish(pending.ticket, false)
            val pendingClaim = checkNotNull(notices.claimNavigation(pending.ticket, issuedAt) { true })
            val terminal = checkNotNull(notices.claim(event("terminal", NativeChatNoticeKind.APPROVAL, uuidFor(lease, "terminal-notice")), noticeLease, issuedAt))
            notices.finish(terminal.ticket, false)
            check(notices.acknowledgeNavigation(checkNotNull(notices.claimNavigation(terminal.ticket, issuedAt) { true })))
            val terminalClaim = receipt(storage, terminal.ticket)
            val identity = NativeChatNotificationIdentity.load(storage)
            val original = NativeChatNotificationRecoveryStore(storage).reserve(identity, captured.snapshot, uuidFor(lease, "recovery-epoch"),
                preferences, "register", { true }, operationId = uuidFor(lease, "unknown-recovery"))
            val aliasesAfter = aliases()
            require(aliasesAfter == (aliasesBefore + alias(lease)).sorted()) { "Only the new owned alias may be added; no old alias is removed" }
            val base = template.copy(ticket = pending.ticket, receiptHash = receiptHash(pendingClaim), authority = captured.snapshot.credentialEpoch)
            val graph = Plan(base, operation, ticks, elapsed, boot, sha(read(File(root(context), "intent.json"), 16_384)),
                checkNotNull(pendingClaim.navigationClaim), terminal.ticket, receiptHash(terminalClaim),
                receiptHash(pendingClaim.copy(navigationClaim = null)), sha(text(storage, "metadata").toByteArray(Charsets.UTF_8)),
                NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.preferences(preferences)),
                sha(text(storage, NativeChatNotificationIdentity.RECORD).toByteArray(Charsets.UTF_8)),
                sha(text(storage, NativeChatNotificationRecoveryStore.RECORD).toByteArray(Charsets.UTF_8)),
                sha(original.encoded().toByteArray(Charsets.UTF_8)), aliasHash(aliasesAfter), members(context, lease))
            require(ports.forbiddenCommands.get() == 0 && ports.notificationCommands.get() == 0)
            verifyOriginal(context, graph, storage); verifyUnknown(storage, graph); verifyOriginal(context, graph, storage)
            currentClock(context, graph)
            append(context, "armed.json", graph.json())
            return graph
        } finally { storage.retireWriter() }
    }

    /** Read-only admission. No Activity, default namespace, new alias, query or recovery write. */
    suspend fun prepareReader(context: Context, lease: String, operation: String): Plan {
        guard(context); NativeChatNotificationColdPlan.uuid(lease); NativeChatNotificationColdPlan.uuid(operation)
        NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(context)
        require(permit == null && selected == null)
        val plan = armed(context, lease)
        require(operation !in setOf(plan.writerOperation, plan.recoveryOperation, plan.pendingOperation, plan.terminalOperation) &&
            plan.base.writerPid != Process.myPid() && plan.base.writerNonce != nonce)
        val processes = checkNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses)
        require(processes.filter { it.uid == Process.myUid() }.none { it.pid == plan.base.writerPid })
        require(processes.filter { it.uid == Process.myUid() }.let { it.size == 1 && it.single().pid == Process.myPid() })
        currentClock(context, plan)
        require(apkHash(context, false) == plan.base.appHash && apkHash(context, true) == plan.base.runnerHash)
        val storage = existingStorage(context, lease)
        verifyOriginal(context, plan, storage)
        verifyUnknown(storage, plan)
        verifyOriginal(context, plan, storage)
        permit = Permit(plan, storage, operation)
        return plan
    }

    /** Called only by the existing Main selector, before an ordinary Session can be made. */
    fun selectForActivity(context: Context, intent: Intent, retained: MobileSession?, restored: Boolean): NativeChatNotificationColdStart.Binding {
        guard(context)
        val admitted = checkNotNull(permit) { "A fresh instrumented graph reader must preflight before its first Main" }
        require(selected == null && retained == null && !restored &&
            NativeChatNotificationColdStart.activityEntries.get() == 1 && NativeChatNotificationColdStart.defaultSelections.get() == 0)
        val plan = armed(context, admitted.plan.base.lease)
        require(plan.fingerprint == admitted.plan.fingerprint && NativeChatNotificationColdStart.strictTicket(context, intent) == plan.base.ticket)
        // The suspend preflight validated identity and the original operation. Full byte/hash
        // continuity here proves those exact records still exist; no IO coroutine is started by Main.
        currentClock(context, plan); verifyOriginal(context, plan, admitted.storage)
        append(context, "selected.json", JSONObject().put("version", 1).put("kind", KIND).put("graphHash", plan.fingerprint)
            .put("readerOperation", admitted.readerOperation).put("pid", Process.myPid()).put("nonce", nonce)
            .put("startTicks", processTicks()).put("entryMode", "instrumented-first-main").put("at", System.currentTimeMillis()))
        selected = admitted; permit = null
        return NativeChatNotificationColdStart.Binding(plan.base, admitted.storage,
            NativeChatNotificationAcceptanceScope.create(context, plan.base.lease)).also {
            it.session = MobileSession(admitted.storage, it.ports.sessionReads, it.ports.commands)
        }
    }

    /** Fixed native UI evidence only. No arbitrary path, URI or caller-supplied record name. */
    fun screenshot(context: Context, screen: Screen, bitmap: Bitmap) {
        guard(context); require(selected != null)
        require(bitmap.width in 1..4096 && bitmap.height in 1..4096 && bitmap.width.toLong() * bitmap.height <= 6_000_000)
        val file = File(root(context), screen.filename)
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        FileOutputStream(fd).use { out ->
            val bounded = object : FilterOutputStream(out) {
                private var total = 0
                override fun write(value: Int) { require(total < 2 * 1024 * 1024); out.write(value); total++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(length >= 0 && total.toLong() + length <= 2 * 1024 * 1024)
                    out.write(bytes, offset, length); total += length
                }
            }
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded)); out.fd.sync()
        }
        syncDirectory(root(context))
    }

    /** Observe production ACK; this function never writes the notification/recovery records. */
    suspend fun completeReader(context: Context, binding: NativeChatNotificationColdStart.Binding): JSONObject {
        guard(context)
        val admitted = checkNotNull(selected); val plan = admitted.plan
        currentClock(context, plan)
        require(binding.storage === admitted.storage && binding.plan.fingerprint == plan.base.fingerprint &&
            NativeChatNotificationColdStart.active === binding)
        withContext(Dispatchers.Main.immediate) {
            require(NativeChatNotificationColdStart.activityEntries.get() == 1 && NativeChatNotificationColdStart.defaultSelections.get() == 0 &&
                binding.newIntentHandled.get() == 1 && binding.ports.forbiddenCommands.get() == 0 && binding.ports.notificationCommands.get() == 0)
            require(binding.session.selectedThread == plan.base.otherThread && binding.session.text == plan.laterDraft &&
                NativeChatNoticeTarget.from(checkNotNull(binding.session.selectedClient.scope)) == plan.base.target &&
                binding.session.operationRecords.isEmpty())
        }
        binding.storage.awaitQueuedWrites()
        require(names(context) == setOf("intent.json", "armed.json", "selected.json", Screen.FIRST_MAIN.filename, Screen.LATER_DRAFT.filename))
        val pending = receipt(binding.storage, plan.base.ticket); val terminal = receipt(binding.storage, plan.terminalTicket)
        require(pending.consumed && pending.navigationClaim == null && receiptHash(pending) == plan.pendingAckHash)
        require(aliasHash(aliases()) == plan.aliasSetHash)
        require(receiptHash(terminal) == plan.terminalHash)
        require(NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.preferences(NativeChatNotificationStore(binding.storage).preferences())) == plan.preferencesHash)
        verifyUnknown(binding.storage, plan)
        require(aliasHash(aliases()) == plan.aliasSetHash)
        val after = members(context, plan.base.lease)
        for (record in listOf(NativeChatNotificationIdentity.RECORD, NativeChatNotificationRecoveryStore.RECORD)) {
            val filename = NativeChatNotificationWire.hash(record) + ".bin"
            require(after[filename] == plan.members[filename]) { "Unknown operation and identity ciphertext must stay exact" }
        }
        val result = JSONObject().put("version", 1).put("kind", KIND).put("lease", plan.base.lease).put("graphHash", plan.fingerprint)
            .put("readerOperation", admitted.readerOperation).put("pid", Process.myPid()).put("nonce", nonce).put("startTicks", processTicks())
            .put("writerPid", plan.base.writerPid).put("writerStartTicks", plan.writerStartTicks)
            .put("entryMode", "instrumented-first-main").put("MainActivityUsed", true).put("instrumentedReaderProcess", true)
            .put("OsProcessCreationProven", false).put("SystemUiNotificationClickUsed", false).put("priorProcessExitExternallyProven", false)
            .put("pendingAckHash", receiptHash(pending)).put("terminalHash", receiptHash(terminal))
            .put("preferencesHash", plan.preferencesHash)
            .put("aliasSetHash", plan.aliasSetHash)
            .put("unknownRecoveryUnchanged", true).put("identityCiphertextUnchanged", true)
            .put("HostOrModelInput", false).put("notificationPosts", 0).put("recoveryCommandsOrQueries", 0)
            .put("seededIntermediateStateOnly", true).put("realIoCrashProven", false).put("physicalDevAccepted", false)
            .put("beforeMembers", JSONObject(plan.members)).put("afterMembers", JSONObject(after))
            .put("ordinaryOwnerChangedMembers", JSONArray((plan.members.keys + after.keys).filter { plan.members[it] != after[it] }.sorted()))
            .put("firstMainPngSha256", sha(read(File(root(context), Screen.FIRST_MAIN.filename), 2 * 1024 * 1024)))
            .put("laterDraftPngSha256", sha(read(File(root(context), Screen.LATER_DRAFT.filename), 2 * 1024 * 1024)))
            .put("screenshotsRequireRootVisualReview", true)
        append(context, "complete.json", result)
        return result
    }

    /**
     * Fresh observer only, after Root has copied and reviewed the exact complete evidence.
     * Move the closed raw permit/evidence directory to a lease-specific archive; delete no
     * journal, key, sound, screenshot or original bytes. A lost result is inspected, never replayed.
     */
    suspend fun releaseCompleted(context: Context, lease: String, operation: String, reviewedCompleteHash: String): JSONObject {
        guard(context); NativeChatNotificationColdPlan.uuid(lease); NativeChatNotificationColdPlan.uuid(operation)
        require(chatNoticeHash(reviewedCompleteHash))
        NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(context)
        require(permit == null && selected == null)
        require(names(context) == setOf("intent.json", "armed.json", "selected.json", "complete.json",
            Screen.FIRST_MAIN.filename, Screen.LATER_DRAFT.filename))
        val plan = storedPlan(context, lease)
        val completedBytes = read(File(root(context), "complete.json"), 16_384)
        require(sha(completedBytes) == reviewedCompleteHash)
        val completed = JSONObject(String(completedBytes, Charsets.UTF_8))
        val originalReader = JSONObject(String(read(File(root(context), "selected.json"), 16_384), Charsets.UTF_8))
        NativeChatNotificationWire.keys(originalReader, setOf("version", "kind", "graphHash", "readerOperation",
            "pid", "nonce", "startTicks", "entryMode", "at"))
        require(originalReader.get("version") == 1 && originalReader.getString("kind") == KIND &&
            originalReader.getString("graphHash") == plan.fingerprint && originalReader.getString("entryMode") == "instrumented-first-main")
        val readerPid = originalReader.getInt("pid"); require(readerPid > 0 && readerPid != Process.myPid())
        require(completed.get("version") == 1 && completed.getString("kind") == KIND &&
            completed.getString("lease") == lease && completed.getString("graphHash") == plan.fingerprint &&
            completed.getInt("pid") == readerPid && completed.getString("nonce") == originalReader.getString("nonce") &&
            completed.getLong("startTicks") == originalReader.getLong("startTicks") &&
            completed.getString("readerOperation") == originalReader.getString("readerOperation") &&
            completed.getString("entryMode") == "instrumented-first-main" &&
            !completed.getBoolean("OsProcessCreationProven") && !completed.getBoolean("SystemUiNotificationClickUsed"))
        require(operation !in setOf(plan.writerOperation, plan.recoveryOperation, plan.pendingOperation, plan.terminalOperation) &&
            operation != originalReader.getString("readerOperation"))
        val processes = checkNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses)
        require(processes.filter { it.uid == Process.myUid() }.none { it.pid == readerPid || it.pid == plan.base.writerPid })
        val reviewedAfterObject = completed.getJSONObject("afterMembers")
        require(reviewedAfterObject.length() in 1..MAX_MEMBERS)
        val reviewedAfter = reviewedAfterObject.keys().asSequence().associateWith { name ->
            require(name.length == 68 && name.endsWith(".bin") && chatNoticeHash(name.removeSuffix(".bin")))
            reviewedAfterObject.get(name).also { require(it is String && chatNoticeHash(it)) } as String
        }
        val before = members(context, lease)
        require(before == reviewedAfter) { "Namespace changed after the Root-reviewed completion" }
        require(aliasHash(aliases()) == plan.aliasSetHash)
        val storage = existingStorage(context, lease, readOnly = true)
        try {
            require(receiptHash(receipt(storage, plan.base.ticket)) == plan.pendingAckHash &&
                receiptHash(receipt(storage, plan.terminalTicket)) == plan.terminalHash)
            verifyUnknown(storage, plan)
            require(members(context, lease) == before)
        } finally { storage.retireWriter() }
        for (record in listOf(NativeChatNotificationIdentity.RECORD, NativeChatNotificationRecoveryStore.RECORD)) {
            val filename = NativeChatNotificationWire.hash(record) + ".bin"
            require(before[filename] == plan.members[filename])
        }
        require(sha(read(File(root(context), Screen.FIRST_MAIN.filename), 2 * 1024 * 1024)) == completed.getString("firstMainPngSha256") &&
            sha(read(File(root(context), Screen.LATER_DRAFT.filename), 2 * 1024 * 1024)) == completed.getString("laterDraftPngSha256"))
        require(members(context, lease) == reviewedAfter) { "Namespace changed before release" }
        val archive = File(parent(context), "P9-notification-main-graph-complete-" + lease)
        require(Files.notExists(archive.toPath(), NOFOLLOW_LINKS))
        val result = JSONObject().put("version", 1).put("kind", KIND).put("phase", "release-intent")
            .put("lease", lease).put("graphHash", plan.fingerprint).put("operation", operation)
            .put("reviewedCompleteHash", reviewedCompleteHash).put("pid", Process.myPid()).put("nonce", nonce)
            .put("readerPidAbsent", readerPid).put("archiveName", archive.name)
            .put("originalNamespaceAndKeyRetained", true).put("unknownOperationQueriedOrReplayed", false)
        append(context, "release.json", result)
        Os.rename(root(context).path, archive.path)
        syncDirectory(parent(context), systemParent = true)
        require(Files.notExists(root(context).toPath(), NOFOLLOW_LINKS) && archive.isDirectory)
        return JSONObject(result.toString()).put("phase", "released").put("archiveVerifiedPresent", true)
    }

    fun recordFailure(context: Context, lease: String, operation: String, type: String) {
        guard(context)
        if (!hasMaterial(context)) return
        // A bad argument or read-only preflight must never add a failure to another original.
        val original = runCatching {
            JSONObject(String(read(File(root(context), "intent.json"), 16_384), Charsets.UTF_8))
        }.getOrNull() ?: return
        if (original.optString("lease") != lease) return
        val writerOwned = original.optString("writerOperation") == operation &&
            original.optInt("writerPid") == Process.myPid() && original.optString("writerNonce") == nonce
        val admitted = selected ?: permit
        val readerOwned = admitted != null && admitted.plan.base.lease == lease && admitted.readerOperation == operation
        if (!writerOwned && !readerOwned || "complete.json" in names(context) || "failed.json" in names(context)) return
        append(context, "failed.json", JSONObject().put("version", 1).put("kind", KIND).put("phase", "unresolved")
            .put("lease", lease).put("operation", operation).put("pid", Process.myPid()).put("nonce", nonce).put("failureType", type.take(160))
            .put("originalAttemptPreserved", true).put("replayed", false))
    }
}
