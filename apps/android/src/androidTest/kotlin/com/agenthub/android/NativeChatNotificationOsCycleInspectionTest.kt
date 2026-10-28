package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Optional parent-owned inspection. No Activity, controller, identity loader, command, permission or OS notification call. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationOsCycleInspectionTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(60)
    @Test fun inspectLegacyOriginalBytesWithoutQueryReplayOrJournalWrite() = runBlocking<Unit> { withContext(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Explicit parent inspection lease required; skip is not acceptance", args.getString("p9SystemLease") != null)
        val lease = checkNotNull(args.getString("p9SystemLease")); require(UUID.fromString(lease).toString() == lease)
        val runId = checkNotNull(args.getString("p9SystemCycle"))
        val scope = NativeChatNotificationAcceptanceScope.create(context, runId)
        val root = File(context.noBackupFilesDir, "P9-native-system-$runId")
        require(root.isDirectory && !java.nio.file.Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile) { "Existing exact original directory required" }
        fun inventory(): Map<String, String> {
            val rows = checkNotNull(root.listFiles()); require(rows.isNotEmpty() && rows.size <= 32 && rows.sumOf { it.length() } <= 1_048_576)
            require(rows.all { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root.canonicalFile && it.name.matches(Regex("[0-9a-f]{64}\\.bin")) }) { "Partial/unknown original files preserved; no AtomicFile recovery attempted" }
            return rows.associate { it.name to NativeChatNotificationSoundLibrary.sha(it.readBytes()) }
        }
        val before = inventory()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        require(keys.getKey("agent-hub-mobile-v1", null) is javax.crypto.SecretKey) { "Existing key required; inspection never generates/imports a key" }
        val storage = scope.storage(context)
        val result = NativeChatNotificationCycleEvidence.inspectLegacy(checkNotNull(storage.read(NotificationOsFixture.CYCLE_RECORD)),
            checkNotNull(storage.read(NativeChatNotificationIdentity.RECORD)), checkNotNull(storage.read(NativeChatNotificationRecoveryStore.RECORD)), runId)
        val after = inventory(); check(before == after) { "Original byte inventory changed; do not proceed or replay" }
        result.put("version", 1).put("role", "read-only-legacy-inspection").put("lease", lease).put("runId", runId)
            .put("originalPhysicalBytesUnchanged", true).put("fileSha256", JSONObject(before)).put("commands", 0).put("controllerCalls", 0)
            .put("defaultStoreOpened", false).put("nativePermissionOrNotificationCalls", 0)
        val output = File(context.filesDir, "acceptance/P9-native-system-inspection/$lease").apply { mkdirs() }
        File(output, "$runId-${UUID.randomUUID()}.json").writeText(result.toString())
        check(listOf("identityHashMatches", "originalHashMatches", "epochMatchesDeterministicFixture", "targetMatchesFixture").all(result::getBoolean)) { "Inspection mismatch retained; no repair or replay" }
    } }
}
