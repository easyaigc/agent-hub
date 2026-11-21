package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Auxiliary Native tests of the shipping Session owner and actual encrypted queued metadata.
 * An already-authenticated adapter observation is supplied at AgentReads; this is NOT an
 * encrypted peer/network acceptance test. No MainActivity, application restore or default store.
 * Requires a fresh f09CandidatesRun UUID; Root owns compilation and Native scheduling.
 */
@RunWith(AndroidJUnit4::class)
class MobileEasyWorkAuthenticatedRefreshTest {
    @get:Rule val method = TestName()
    @get:Rule val timeout: org.junit.rules.TestRule = Timeout.seconds(90)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return checkNotNull(value).getOrThrow()
    }
    private fun join(job: Job) = runBlocking { withTimeout(10_000) { job.join() } }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun field(name: String) = MobileSession::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** Uncancellable fixture response: cancellation alone cannot satisfy a stale-owner assertion. */
    private class ReadGate(val clientId: String) {
        val entered = CompletableDeferred<Unit>()
        var returnedValue = false
        private var continuation: Continuation<Unit>? = null
        suspend fun hold() = suspendCoroutine<Unit> { continuation = it; entered.complete(Unit) }
        fun release() { val saved = continuation; continuation = null; saved?.resume(Unit) }
    }
    private enum class Observation { NONE, VALID, INVALID }
    private class Reads : HostReads {
        val calls = CopyOnWriteArrayList<String>()
        var mode = Observation.NONE
        var gate: ReadGate? = null
        var nextOrdinaryRead: CompletableDeferred<Unit>? = null
        override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?): MobileWorkspace {
            check(projectCursor == null && threadCursor == null)
            calls.add(client.id)
            val capturedMode = mode
            val capturedGate = gate?.takeIf { it.clientId == client.id }?.also { gate = null }
            capturedGate?.hold()
            val original = checkNotNull(client.agentConnection)
            val target = EasyWorkTarget(checkNotNull(original.target.accountAuthority),
                checkNotNull(original.target.accountSubject), original.target.hostId,
                original.target.workspaceId, original.target.runtimeId)
            val context = JSONObject().put("target", target.json())
            if (capturedMode != Observation.NONE) context.put("connectionCandidates", JSONArray().put(
                JSONObject().put("transportId", "lan-wss").put("endpoint", "https://192.168.77.33:44319")
                    .put("peerFingerprint", if (capturedMode == Observation.VALID) "a".repeat(64) else "invalid-pin")))
            val observed = EasyWorkAuthenticatedCandidates.read(context, target, original)
            val wire = MobileSnapshotCodec.header(client).put("name", "catalog-${capturedMode.name}")
                .put("connectionMode", "easywork-runtime").put("runtimeConnected", true)
                .put("projects", JSONArray()).put("threads", JSONArray()).put("capabilities", JSONArray())
            val result = MobileSnapshotCodec.workspace(wire.toString()).copy(authenticatedConnection = observed)
            if (capturedMode == Observation.NONE) nextOrdinaryRead?.also { nextOrdinaryRead = null; it.complete(Unit) }
            capturedGate?.returnedValue = true
            return result
        }
        override suspend fun history(client: MobileClient, threadId: String, cursor: String?): MobileHistory =
            error("No thread is selected by this fixture; history must not be requested")
    }
    /** No delegating network-capable provider. All business ports fail; only model inventory is inert. */
    private class Commands : CodexCommands {
        val invoked = CopyOnWriteArrayList<String>()
        private fun deny(name: String): Nothing { invoked.add(name); error("Fixture forbids $name") }
        override suspend fun models(client: MobileClient) = emptyList<CodexModel>()
        override suspend fun events(client: MobileClient, threadId: String, epoch: String, cursor: Long): EventBatch = deny("events")
        override suspend fun send(client: MobileClient, intent: ReplyIntent): DeliveryReceipt = deny("send")
        override suspend fun receipt(client: MobileClient, threadId: String, operationId: String): DeliveryReceipt = deny("receipt")
        override suspend fun createThread(client: MobileClient, projectId: String?, operationId: String): MobileThread = deny("createThread")
        override suspend fun prepareCreation(client: MobileClient, intent: CreationIntent): DesktopCreation = deny("prepareCreation")
        override suspend fun creation(client: MobileClient, operationId: String, inspectOnly: Boolean): DesktopCreation = deny("creation")
        override suspend fun submitCreation(client: MobileClient, operationId: String): DesktopCreation = deny("submitCreation")
        override suspend fun detachCreation(client: MobileClient, operationId: String): DesktopCreation = deny("detachCreation")
        override suspend fun open(client: MobileClient, threadId: String, operationId: String, queryOnly: Boolean): DesktopOpen = deny("open")
        override suspend fun stop(client: MobileClient, threadId: String, turnId: String): Unit = deny("stop")
        override suspend fun cancelQueue(client: MobileClient, threadId: String, operationId: String): Unit = deny("cancelQueue")
        override suspend fun answer(client: MobileClient, threadId: String, turnId: String, approvalId: String, decision: String, answers: Map<String, List<String>>): Unit = deny("answer")
        override suspend fun upload(client: MobileClient, threadId: String, file: CreationFile, bytes: ByteArray): MobileFile = deny("upload")
        override suspend fun uploadCreation(client: MobileClient, operationId: String, file: CreationFile, bytes: ByteArray): Unit = deny("uploadCreation")
    }

    private inner class Fixture {
        val id = UUID.randomUUID().toString()
        val alias = "f09-auth-refresh-$id"
        val reads = Reads()
        val commands = Commands()
        val target = EasyWorkTarget("https://issuer.example", "fixture-subject", "fixture-desktop", "workspace-a", "runtime-a")
        val direct = EasyWorkPairing.directCandidates(listOf("https://192.168.77.11:44319"), fingerprint = "a".repeat(64))
            .single().copy(id = "original-direct", networkKind = "lan")
        val relay = AgentCandidate("original-relay", "relay-http", "https://relay.example")
        val connection = AgentConnection(target.scope(), listOf(direct, relay), "opaque-credential-a-$id", "automatic")
        val first = MobileClient("f09-a-$id", "Original A", target.scope(), direct.endpoint, false, "easywork-runtime", false,
            readerId = "original-reader-a", requiresReadTarget = true, agentConnection = connection)
        val otherConnection = connection.copy(target = target.copy(workspaceId = "workspace-b", runtimeId = "runtime-b").scope(), credentialRef = "opaque-credential-b-$id")
        val second = first.copy(id = "f09-b-$id", name = "Original B", scope = otherConnection.target,
            readerId = "original-reader-b", agentConnection = otherConnection)
        lateinit var session: MobileSession
        lateinit var store: NativeSessionStorage
        lateinit var evidence: File
        lateinit var payload: File
        lateinit var secure: File
        private var aliasAdmitted = false
        private var evidenceOwned = false
        private var payloadOwned = false
        private var closed = false
        private val gates = mutableListOf<ReadGate>()
        private var protectedBodies = emptyMap<String, String>()
        private var protectedCipher = emptyMap<String, String>()
        private val keys get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun open() {
            check(BuildConfig.DEBUG && instrumentation.targetContext.packageName == "com.easyai.agenthub.android.acceptance")
            check(instrumentation.context.packageName == "com.easyai.agenthub.android.acceptance.test")
            val run = checkNotNull(InstrumentationRegistry.getArguments().getString("f09CandidatesRun"))
            check(UUID.fromString(run).toString() == run && UUID.fromString(run).version() == 4)
            val parent = File(instrumentation.targetContext.filesDir, "acceptance/F09-auth-refresh/$run").apply { mkdirs(); Os.chmod(path, 0x1c0) }
            evidence = File(parent, method.methodName)
            check(evidence.mkdir()) { "Original case exists: reconcile it, never replay/clear" }
            evidenceOwned = true
            Os.chmod(evidence.path, 0x1c0)
            payload = File(evidence, "owned-$id"); check(payload.mkdir()); payloadOwned = true; Os.chmod(payload.path, 0x1c0)
            secure = File(payload, "secure"); check(secure.mkdir()); Os.chmod(secure.path, 0x1c0)
            val files = File(payload, "files"); check(files.mkdir()); Os.chmod(files.path, 0x1c0)
            val cache = File(payload, "cache"); check(cache.mkdir()); Os.chmod(cache.path, 0x1c0)
            check(alias != "agent-hub-mobile-v1" && !keys.containsAlias(alias)); aliasAdmitted = true
            val context = object : ContextWrapper(instrumentation.targetContext) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir() = secure
                override fun getFilesDir() = files
                override fun getCacheDir() = cache
            }
            store = NativeSessionStorage(context, "installation", alias)
            protectedBodies = mapOf("easywork-credential:${connection.credentialRef}" to "Original inert fixture credential A; never issued or decoded",
                "easywork-credential:${otherConnection.credentialRef}" to "Original inert fixture credential B; never issued or decoded",
                "fixture-old-fields" to JSONObject().put("version", 17).put("unrelatedField", "original fixture data").toString())
            runBlocking { withContext(Dispatchers.IO) { protectedBodies.forEach { (key, value) -> store.write(key, value) } } }
            protectedCipher = protectedBodies.keys.associateWith { sha(cipher(it).readBytes()) }
            main {
                session = MobileSession(store)
                session.resetAcceptance(reads, commands, acceptanceEasyWorkPorts = reads to commands)
                // Keep unrelated directory/events producers inactive, as this fixture has no UI/selected thread.
                // refresh itself uses its shipping owner and persistence checks unchanged.
                session.leaveForeground()
                @Suppress("UNCHECKED_CAST")
                val clients = field("realClients").get(session) as MutableList<MobileClient>
                clients.addAll(listOf(first, second)) // Established fixture-only initial client injection.
                session.select(first)
                session.changeLanguage("en"); session.changeAppearance("dark")
                session.text = "Original owned draft 中文 100% %@"
            }
            join(currentJob())
            snapshot("initial")
        }
        private fun cipher(key: String) = File(File(secure, "installation"), sha(key.toByteArray(Charsets.UTF_8)) + ".bin")
        fun currentJob(): Job = main { checkNotNull(field("workspaceJob").get(session) as? Job) }
        fun refresh(mode: Observation): Job = main { reads.mode = mode; session.refresh(); checkNotNull(field("workspaceJob").get(session) as? Job) }
        fun delayNext(): Pair<ReadGate, Job> {
            val gate = ReadGate(first.id); gates.add(gate)
            val job = main { reads.gate = gate; reads.mode = Observation.VALID; session.refresh(); checkNotNull(field("workspaceJob").get(session) as? Job) }
            runBlocking { withTimeout(10_000) { gate.entered.await() } }
            return gate to job
        }
        fun release(gate: ReadGate, old: Job) { main { gate.release() }; join(old); assertTrue("Actual late value returned by fake reads", gate.returnedValue) }
        fun snapshot(label: String): JSONObject {
            main { check(!session.loading) }
            val result = runBlocking { withContext(Dispatchers.IO) {
                withTimeout(10_000) { store.awaitQueuedWrites() }
                checkNotNull(store.metadata()) // Read actual encrypted serialized production metadata, not a rebuilt expected object.
            } }
            File(evidence, "$label-metadata.json").apply { check(createNewFile()); Os.chmod(path, 0x180); writeText(result.toString()) }
            assertProtected()
            return result
        }
        fun assertProtected() {
            runBlocking { withContext(Dispatchers.IO) {
                for ((key, expected) in protectedBodies) {
                    assertEquals(expected, store.read(key)); assertEquals(protectedCipher.getValue(key), sha(cipher(key).readBytes()))
                }
            } }
            assertTrue(commands.invoked.toString(), commands.invoked.isEmpty())
        }
        fun close() {
            if (!evidenceOwned || closed) return
            closed = true
            var failure: Throwable? = null
            fun attempt(block: () -> Unit) { try { block() } catch (e: Throwable) { if (failure == null) failure = e else failure!!.addSuppressed(e) } }
            var joined = !::session.isInitialized
            var writerRetired = !::store.isInitialized
            attempt { main { gates.forEach { it.release() }; if (::session.isInitialized) session.leaveForeground() } }
            attempt { if (::session.isInitialized) runBlocking { withTimeout(10_000) {
                withContext(Dispatchers.Main.immediate) { session.abandonMigrationRestore() }; joined = true
            } } }
            if (joined && ::store.isInitialized) {
                attempt { runBlocking { withTimeout(10_000) { store.awaitQueuedWrites() } }; assertProtected() }
                attempt { store.retireWriter(); writerRetired = true }
            }
            var payloadRemoved = false; var aliasRemoved = false
            if (joined && writerRetired && failure == null && payloadOwned) {
                attempt {
                    val root = payload.toPath(); check(root.parent == evidence.toPath() && payload.name == "owned-$id")
                    Files.walkFileTree(root, object : SimpleFileVisitor<java.nio.file.Path>() {
                        override fun preVisitDirectory(dir: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                            check(attrs.isDirectory && !attrs.isSymbolicLink); return FileVisitResult.CONTINUE
                        }
                        override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                            check(attrs.isRegularFile && !attrs.isSymbolicLink); Files.delete(file); return FileVisitResult.CONTINUE
                        }
                        override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): FileVisitResult {
                            if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE
                        }
                    })
                    check(Files.notExists(root, LinkOption.NOFOLLOW_LINKS)); payloadRemoved = true
                }
                if (payloadRemoved && aliasAdmitted) attempt {
                    check(alias == "f09-auth-refresh-$id" && alias != "agent-hub-mobile-v1")
                    keys.deleteEntry(alias); check(!keys.containsAlias(alias)); aliasRemoved = true
                }
            }
            attempt { File(evidence, "cleanup.json").apply {
                check(createNewFile()); Os.chmod(path, 0x180)
                writeText(JSONObject().put("fixture", id).put("namespace", "installation").put("keyAlias", alias)
                    .put("sessionJoined", joined).put("writerRetired", writerRetired).put("payloadRemoved", payloadRemoved)
                    .put("dedicatedAliasRemoved", aliasRemoved).put("MainActivityUsed", false).put("defaultStoreOpened", false)
                    .put("sharedAliasDeleted", false).put("realNetworkOrModelAcceptance", false)
                    .put("failureType", failure?.javaClass?.name ?: JSONObject.NULL)
                    .put("retentionIfIncomplete", "Keep only this owned payload/alias for Root reconciliation; never touch defaults/shared aliases").toString())
                Os.chmod(path, 0x180)
            } }
            failure?.let { throw it }
        }
    }

    private fun savedClient(metadata: JSONObject, id: String): MobileClient = metadata.getJSONArray("clients").objects().map(::decodeClient).single { it.id == id }
    private fun assertStableFields(before: JSONObject, after: JSONObject) {
        for (name in listOf("appearance", "language", "drafts", "draftFiles", "draftVersions", "attachmentImportGenerations", "photoSources", "newProjects", "generation", "generationPreferences", "replyModes", "sidebar"))
            assertEquals("Original fixture field $name", easyCanonical(before.opt(name)), easyCanonical(after.opt(name)))
    }
    private fun withFixture(block: (Fixture) -> Unit) {
        val f = Fixture(); var original: Throwable? = null
        try { f.open(); block(f) } catch (e: Throwable) { original = e; throw e }
        finally { try { f.close() } catch (cleanup: Throwable) { if (original != null) original.addSuppressed(cleanup) else throw cleanup } }
    }

    @Test fun authenticatedObservationPersistsOnlyCandidatesInOwnedStorage() = withFixture { f ->
        val before = f.snapshot("before-valid")
        join(f.refresh(Observation.VALID))
        val after = f.snapshot("after-valid")
        val saved = savedClient(after, f.first.id); val learned = checkNotNull(saved.agentConnection)
        assertEquals(f.connection, learned.copy(candidates = f.connection.candidates))
        assertEquals(listOf("https://192.168.77.33:44319", f.direct.endpoint, f.relay.endpoint), learned.candidates.map { it.endpoint })
        assertEquals(f.direct, learned.candidates[1]); assertEquals(f.relay, learned.candidates[2])
        assertEquals(f.first.readerId, saved.readerId); assertEquals(f.first.name, saved.name)
        assertEquals(f.first.endpoint, saved.endpoint); assertTrue(sameScope(f.first.scope, saved.scope))
        assertEquals(f.first.requiresReadTarget, saved.requiresReadTarget)
        assertEquals(f.otherConnection, savedClient(after, f.second.id).agentConnection)
        main { assertEquals(learned, f.session.selectedClient.agentConnection); assertTrue(f.session.selectedClient.online) }
        assertStableFields(before, after)
    }
    @Test fun lateWorkspaceAfterTargetSwitchCannotSaveEitherConnection() = withFixture { f ->
        val (gate, old) = f.delayNext()
        main { f.reads.mode = Observation.NONE; f.session.select(f.second); f.session.text = "Second owned draft" }
        join(f.currentJob())
        val beforeLate = f.snapshot("new-target-before-late")
        f.release(gate, old)
        val afterLate = f.snapshot("new-target-after-late")
        for (metadata in listOf(beforeLate, afterLate)) {
            assertEquals(f.connection, savedClient(metadata, f.first.id).agentConnection)
            assertEquals(f.otherConnection, savedClient(metadata, f.second.id).agentConnection)
            assertEquals(f.second.id, metadata.getString("selected"))
        }
        main {
            assertEquals(f.second.id, f.session.selectedClient.id); assertEquals("Second owned draft", f.session.text)
            assertEquals(f.connection, f.session.clients.single { it.id == f.first.id }.agentConnection)
            assertEquals(f.otherConnection, f.session.selectedClient.agentConnection)
        }
        assertStableFields(beforeLate, afterLate)
    }
    @Test fun lateWorkspaceAfterPreferenceEditCannotOverwriteCurrentConnection() = withFixture { f ->
        val before = f.snapshot("before-preference")
        val (gate, old) = f.delayNext(); val nextRead = CompletableDeferred<Unit>()
        main {
            f.reads.mode = Observation.NONE; f.reads.nextOrdinaryRead = nextRead
            f.session.setEasyWorkPreference(f.session.selectedClient, "relay-only") // Shipping revision increment and cancel/join path.
            assertEquals("relay-only", f.session.selectedClient.agentConnection?.preference)
        }
        f.release(gate, old)
        runBlocking { withTimeout(10_000) { nextRead.await() } }
        join(f.currentJob())
        val after = f.snapshot("after-preference-and-late")
        assertEquals(f.connection.copy(preference = "relay-only"), savedClient(after, f.first.id).agentConnection)
        assertEquals(f.otherConnection, savedClient(after, f.second.id).agentConnection)
        main { assertEquals(f.connection.copy(preference = "relay-only"), f.session.selectedClient.agentConnection) }
        assertStableFields(before, after)
    }
    @Test fun invalidOptionalMetadataKeepsUsableWorkspaceAndConnection() = withFixture { f ->
        val before = f.snapshot("before-invalid")
        join(f.refresh(Observation.INVALID))
        val after = f.snapshot("after-invalid")
        assertEquals(f.connection, savedClient(after, f.first.id).agentConnection)
        main {
            assertTrue(f.session.selectedClient.online); assertNull(f.session.error)
            assertEquals("catalog-INVALID", f.session.workspace?.name)
            assertEquals(f.first.id, f.session.workspace?.hostId)
        }
        assertStableFields(before, after)
    }
}
