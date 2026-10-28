package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Full file pipeline using actual Keystore and low-memory-safe synthetic WAV generation. No Host. */
class NativeMigrationStreamTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule = NativeTestForeground()
    @get:Rule(order=1) val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password = "owned streamed migration password"
    @Before fun setup() { ui.waitUntil(30_000) { !ui.activity.session.restoring }; ui.runOnIdle { owned = OwnedControlAcceptance(ui.activity, "migration-stream") } }
    @After fun finish() { if (::owned.isInitialized) ui.runOnIdle { owned.session.leaveForeground(); owned.verifyDefault() } }
    private fun seed(source: NativeSessionStorage): MobileClient {
        val value = MigrationActivationFixtures.payload(); val rows = value.getJSONObject("records")
        rows.keys().forEach { source.write(it, rows.getString(it)) }; return MigrationActivationFixtures.client(value)
    }
    private suspend fun voice(source: NativeSessionStorage, client: MobileClient, pcm: Int, threadId: String = "synthetic-thread"): PendingVoiceRecording {
        val store = VoiceRecordingStore(ui.activity, source)
        val pending = store.begin(AISharingScope(client.scope!!, "codex-dictation"), client.codexDirect!!.fingerprint,
            client.cacheKey(threadId), "voice-v1:stream-${UUID.randomUUID()}", threadId, "original 中文 😀 100% %@")
        RandomAccessFile(store.file(pending.id), "rw").use { out ->
            out.write(WavePCM.header(pcm)); val chunk = ByteArray(65536) { (it % 251).toByte() }; var remaining = pcm
            while (remaining > 0) { val count = minOf(remaining, chunk.size); out.write(chunk, 0, count); remaining -= count }; out.fd.sync()
        }
        return store.seal(pending).also { store.mark(it, true, true, VoiceReceipt(it.manifest!!, "outcome-unknown", null, null, "2030-01-01T00:00:00Z")) }
    }
    internal fun tree(storage: NativeSessionStorage) = File(storage.coordinationKey).let { root ->
        root.walkTopDown().filter { it.isFile }.associate { file ->
            val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input ->
                val chunk = ByteArray(65536); while (true) { val count = input.read(chunk); if (count < 0) break; digest.update(chunk, 0, count) }
            }; file.relativeTo(root).path to digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    private fun export(source: NativeSessionStorage) = File(owned.folder, "stream-${UUID.randomUUID()}.migration").also { NativeMigrationExport(ui.activity, source).sealFile(it, password) }
    private suspend fun verify(current: NativeSessionStorage, original: PendingVoiceRecording) {
        val store = VoiceRecordingStore(ui.activity, current)
        val restored = store.restore(original.scope, original.certificate, original.draftKey, original.originalText)!!
        assertEquals(original.manifest, restored.manifest); assertTrue(restored.migrationReadOnly && restored.prepareAttempted && restored.audioAttempted)
        assertEquals("outcome-unknown", JSONObject(current.read("voice-recording:${restored.id}")!!).getString("phase"))
        val probe = NativeMigrationProbe(); current.copyBinaryTo("voice-audio:${restored.id}", restored.manifest!!.sizeBytes, probe)
        assertEquals(restored.manifest!!.sizeBytes.toLong(), probe.size); assertEquals(restored.manifest!!.sha256, probe.sha256())
        assertArrayEquals(WavePCM.header(restored.manifest!!.sizeBytes - 44), probe.prefix())
    }
    @Test fun fullFiveMinuteWavStreamsThroughExportCandidateAndAtomicActivation() = runBlocking {
        val source = owned.storage.child(ui.activity, "stream-source"); val client = seed(source); val original = voice(source, client, 9_600_000)
        val before = tree(source); val envelope = export(source); val candidate = NativeMigrationCandidateStore(ui.activity, owned.storage)
        val receipt = candidate.stageFile(UUID.randomUUID().toString(), envelope, password)
        val originalOperations = NativeMigrationStreamCandidate(ui.activity, owned.storage).read(receipt) { archive -> archive.text.filterKeys { it.startsWith("operations:") } }
        val local = owned.storage.child(ui.activity, "migration-candidates"); val candidateBefore = tree(local)
        assertEquals(receipt, candidate.stageFile(receipt.id, envelope, password)); assertEquals(candidateBefore, tree(local))
        val generation = NativeMigrationActivation(ui.activity, owned.storage).activate(receipt); val current = owned.storage.reopen(ui.activity)
        verify(current, original); current.write("later-edit", "independent later draft")
        originalOperations.forEach { (name, raw) ->
            val old = JSONObject(raw).getJSONArray("operations"); val restored = JSONObject(current.read(name)!!).getJSONArray("operations")
            assertEquals(old.length(), restored.length())
            for (n in 0 until old.length()) {
                for (field in listOf("id", "intent", "intentSha256", "result", "phase", "submissionAttempted", "detached", "sourceKey", "sourceVersion"))
                    assertEquals(old.getJSONObject(n).get(field), restored.getJSONObject(n).get(field))
                assertTrue(restored.getJSONObject(n).getBoolean("migrationReadOnly"))
            }
        }
        val imported = tree(current); assertEquals(generation, NativeMigrationActivation(ui.activity, owned.storage).activate(receipt)); assertEquals(imported, tree(current))
        assertEquals(candidateBefore, tree(local)); assertEquals(before, tree(source))
        File(owned.folder, "maximum-stream.json").writeText(JSONObject().put("wavBytes", 9_600_044).put("durationMs", 300_000)
            .put("heapLimitBytes", Runtime.getRuntime().maxMemory()).put("envelopeBytes", envelope.length()).put("originalCiphertextPreserved", true)
            .put("sameIdCandidateUnchanged", true).put("readOnlyOriginalReceipt", true).put("laterDraftPreserved", true).put("remoteWrites", 0).toString())
    }
    @Test fun threeMaximumWavsUseAggregateRawQuotaWithoutBase64OrWholeAudioAllocation() = runBlocking {
        val source = owned.storage.child(ui.activity, "stream-source"); val client = seed(source)
        val originals = (0 until 3).map { voice(source, client, 9_600_000, "synthetic-thread-$it") }; val before = tree(source); val file = export(source)
        assertTrue(file.length() > 28_800_000)
        val receipt = NativeMigrationCandidateStore(ui.activity, owned.storage).stageFile(UUID.randomUUID().toString(), file, password)
        NativeMigrationActivation(ui.activity, owned.storage).activate(receipt); val current = owned.storage.reopen(ui.activity)
        originals.forEach { verify(current, it) }; assertEquals(before, tree(source))
    }
    @Test fun corruptTruncatedTrailingWrongPasswordAndInterruptedCandidateKeepOriginalSlot() = runBlocking {
        val source = owned.storage.child(ui.activity, "stream-source"); voice(source, seed(source), 131072); val file = export(source)
        val store = NativeMigrationCandidateStore(ui.activity, owned.storage); val prior = store.stageFile(UUID.randomUUID().toString(), file, password)
        val before = tree(owned.storage.child(ui.activity, "migration-candidates")); val sourceBefore = tree(source)
        for (kind in listOf("corrupt", "truncated", "trailing", "password")) {
            val altered = File(owned.folder, "$kind.migration"); file.copyTo(altered)
            RandomAccessFile(altered, "rw").use { out -> when (kind) {
                "corrupt" -> { out.seek(out.length() - 1); val last = out.read(); out.seek(out.length() - 1); out.write(last xor 1) }
                "truncated" -> out.setLength(out.length() - 1)
                "trailing" -> { out.seek(out.length()); out.write(0) }
            } }
            assertTrue(kind, runCatching { store.stageFile(UUID.randomUUID().toString(), altered, if (kind == "password") "different owned password" else password, prior) }.isFailure)
            assertEquals(before, tree(owned.storage.child(ui.activity, "migration-candidates")))
        }
        for (stop in listOf(1, 0)) {
            assertTrue(runCatching { store.stageFile(UUID.randomUUID().toString(), file, password, prior) { if (it == stop) throw java.io.IOException("owned interruption") } }.isFailure)
            assertEquals(before, tree(owned.storage.child(ui.activity, "migration-candidates")))
        }
        assertEquals(sourceBefore, tree(source)); NativeMigrationStreamCandidate(ui.activity, owned.storage).read(prior) {}
    }
    @Test fun eachActivationFailureKeepsOriginalFilesAndCandidateThenRetryPublishesOnce() = runBlocking {
        val source = owned.storage.child(ui.activity, "stream-source"); val original = voice(source, seed(source), 131072); val file = export(source)
        val receipt = NativeMigrationCandidateStore(ui.activity, owned.storage).stageFile(UUID.randomUUID().toString(), file, password)
        val before = tree(owned.storage); val local = owned.storage.child(ui.activity, "migration-candidates"); val candidateBefore = tree(local)
        for (stop in listOf(1, receipt.recordCount, 0)) {
            assertTrue(runCatching { NativeMigrationActivation(ui.activity, owned.storage).activate(receipt) { if (it == stop) throw java.io.IOException("owned activation interruption") } }.isFailure)
            assertEquals(before, tree(owned.storage)); assertEquals(candidateBefore, tree(local))
        }
        NativeMigrationActivation(ui.activity, owned.storage).activate(receipt); verify(owned.storage.reopen(ui.activity), original)
    }
    @Test fun legacyPortableFileReadsOriginalThenExplicitReplacementWorksBothDirections() = runBlocking {
        val source=owned.storage.child(ui.activity,"stream-source");seed(source)
        val legacy=File(owned.folder,"legacy.migration");legacy.writeBytes(NativeMigrationExport(ui.activity,source).seal(password))
        val store=NativeMigrationCandidateStore(ui.activity,owned.storage);val original=store.stageFile(UUID.randomUUID().toString(),legacy,password)
        val local=owned.storage.child(ui.activity,"migration-candidates");val before=tree(local)
        assertEquals(original,store.stageFile(original.id,legacy,password));assertEquals(before,tree(local));store.readArchive(original)
        val streamed=store.stageFile(UUID.randomUUID().toString(),export(source),password,original);NativeMigrationStreamCandidate(ui.activity,owned.storage).read(streamed) {}
        val replacement=store.stageFile(UUID.randomUUID().toString(),legacy,password,streamed);store.readArchive(replacement);Unit
    }
    @Test fun knownSystemChildrenRejectFileExportWithoutDiscardingOrRecoveringTheirOriginals() {
        val source=owned.storage.child(ui.activity,"stream-source");seed(source)
        val output=File(owned.folder,"guarded.migration");output.writeText("owned previous export")
        val paths=listOf(listOf("external-inbox"),listOf("recent-photos"),listOf("photo-permission"),listOf("document-results"),
            listOf("photo-results","payloads"),listOf("camera-results","outputs"))
        for(path in paths) {
            val child=path.fold(source){parent,suffix->parent.child(ui.activity,suffix)}
            child.write("owned-audit","{\"version\":1,\"original\":true}");val before=tree(child);val rootBefore=tree(source)
            val failure=runCatching { NativeMigrationExport(ui.activity,source).sealFile(output,password) }.exceptionOrNull()
            assertTrue(failure is NativeMigrationExport.IncompleteScope);assertTrue((failure as NativeMigrationExport.IncompleteScope).scopes.contains(path.first()))
            assertEquals("owned previous export",output.readText());assertEquals(before,tree(child));assertEquals(rootBefore,tree(source));child.remove("owned-audit")
        }
        NativeMigrationExport(ui.activity,source).sealFile(output,password);assertTrue(NativeMigrationStreamCrypto.isEnvelope(output))
    }
    @Test fun provisionalWavExportRejectsWithoutSealPrepareOrRawMutation() = runBlocking {
        val source=owned.storage.child(ui.activity,"stream-source");val client=seed(source);val store=VoiceRecordingStore(ui.activity,source)
        val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey("synthetic-thread"),"voice-v1:unsealed","synthetic-thread","unsealed original")
        val raw=WavePCM.header(0)+byteArrayOf(1,2,3);store.file(pending.id).writeBytes(raw);val before=tree(source)
        val output=File(owned.folder,"unsealed.migration");output.writeText("previous export")
        val failure=runCatching { NativeMigrationExport(ui.activity,source).sealFile(output,password) }.exceptionOrNull()
        assertTrue(failure is NativeMigrationExport.IncompleteScope);assertEquals(setOf("provisional-voice"),(failure as NativeMigrationExport.IncompleteScope).scopes)
        assertEquals(before,tree(source));assertArrayEquals(raw,store.file(pending.id).readBytes());assertEquals("previous export",output.readText())
        assertNull(store.current(pending).manifest);assertFalse(store.current(pending).prepareAttempted||store.current(pending).audioAttempted)
    }
    @Test fun importedRecordingCannotSealOrDeleteSameIdRawFileOwnedBySourceNamespace() = runBlocking {
        val source=owned.storage.child(ui.activity,"stream-source");val original=voice(source,seed(source),131072);val envelope=export(source)
        val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),envelope,password)
        NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity);val imported=VoiceRecordingStore(ui.activity,current)
        val restored=imported.restore(original.scope,original.certificate,original.draftKey,original.originalText)!!;assertTrue(restored.migrationReadOnly)
        // Model an interrupted source cleanup with this test's own raw file; its UUID is the original ID.
        val raw=VoiceRecordingStore(ui.activity,source).file(original.id);val bytes="owned source provisional audit".toByteArray();raw.writeBytes(bytes)
        assertTrue(runCatching { imported.seal(restored) }.isFailure);assertArrayEquals(bytes,raw.readBytes())
        imported.remove(restored);assertArrayEquals(bytes,raw.readBytes());assertNull(current.read("voice-recording:${restored.id}"))
    }
    @Test fun stageMaximumWavForIndependentColdReader() = runBlocking {
        val source = owned.storage.child(ui.activity, "stream-source"); val original = voice(source, seed(source), 9_600_000)
        val sourceBefore=tree(source); val envelope=export(source)
        val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),envelope,password)
        owned.verifyDefault();val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"stream-cold-writer.json").writeText(JSONObject().put("version",1).put("kind","migration-stream-staged-cold")
            .put("namespace",namespace).put("writerPid",android.os.Process.myPid()).put("candidateId",receipt.id).put("sha256",receipt.sha256)
            .put("recordCount",receipt.recordCount).put("voice",original.json()).put("sourceCiphertext",JSONObject(sourceBefore))
            .put("candidateCiphertext",JSONObject(tree(owned.storage.child(ui.activity,"migration-candidates")))).toString())
    }
    @Test fun activateOriginalMaximumWavInIndependentColdReader() = runBlocking {
        val name=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("migrationActivationFixture")?:error("Original stream fixture required")
        require(name.matches(Regex("activation-cold-[a-f0-9]{16}\\.json")))
        val fixture=JSONObject(File(ui.activity.filesDir,"acceptance/$name").readText());require(fixture.get("version")==1&&fixture.get("kind")=="migration-stream-staged-cold")
        assertNotEquals(fixture.getInt("writerPid"),android.os.Process.myPid());val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        val parent=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val receipt=NativeMigrationCandidateStore.Receipt(fixture.getString("candidateId"),fixture.getString("sha256"),fixture.getInt("recordCount"))
        val candidate=parent.child(ui.activity,"migration-candidates");assertEquals(fixture.getJSONObject("candidateCiphertext").toString(),JSONObject(tree(candidate)).toString())
        val generation=NativeMigrationActivation(ui.activity,parent).activate(receipt);val current=parent.reopen(ui.activity);val voice=fixture.getJSONObject("voice")
        val original=PendingVoiceRecording(voice.getString("id"),AISharingScope.decode(voice.getJSONObject("scope")),voice.optional("certificate"),voice.getString("draftKey"),voice.getString("draftId"),
            voice.optional("threadId"),voice.getString("originalText"),voice.getLong("createdAt"),VoiceCodec.manifest(voice.getJSONObject("manifest")))
        verify(current,original);current.write("later-edit","cold reader later draft");val activeBefore=tree(current)
        assertEquals(generation,NativeMigrationActivation(ui.activity,parent).activate(receipt));assertEquals(activeBefore,tree(current))
        assertEquals(fixture.getJSONObject("candidateCiphertext").toString(),JSONObject(tree(candidate)).toString())
        assertEquals(fixture.getJSONObject("sourceCiphertext").toString(),JSONObject(tree(parent.child(ui.activity,"stream-source"))).toString())
        File(owned.folder,"stream-cold-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",android.os.Process.myPid())
            .put("originalCandidate",true).put("maximumAudio",true).put("originalUnknownReceipt",true).put("readOnly",true).put("laterDraft",true).put("sourcePreserved",true).put("remoteWrites",0).toString())
    }

}
