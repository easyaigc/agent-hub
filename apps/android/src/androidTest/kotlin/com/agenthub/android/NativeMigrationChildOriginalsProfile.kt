package com.agenthub.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Test-only bounded producer profile. Production inbox/journal/file/thumbnail writers supply every row.
 * Input bytes and opaque URI strings are synthetic; no Provider, grant, registry, import or upload runs.
 * The existing Controller/owner/cold pipeline owns acquisition, publication and process admission.
 */
internal object NativeMigrationChildOriginalsProfile {
    const val SCHEMA = "p7-child-originals-controller-v1"
    const val METHOD = "childOriginalsControllerProducesPortableArchives"
    const val COLD_SCHEMA = "p7-child-originals-readonly-cold-v1"
    const val COLD_METHOD = "childOriginalsReadOnlyColdAfterExactOriginalExit"
    const val RECORDS = 33
    const val TEXTS = 26
    const val BINARIES = 7
    val paths = listOf("external-inbox", "document-results", "photo-results", "document-results/payloads", "photo-results/payloads")
    private val kinds = listOf(NativeMigrationSelectionJournal.Kind.DOCUMENT, NativeMigrationSelectionJournal.Kind.PHOTO)
    private class ExpectedReadFailure : java.io.IOException("Owned synthetic second input failure")
    private fun id(run: String, purpose: String) = UUID.nameUUIDFromBytes("p7-child:$run:$purpose".toByteArray(Charsets.UTF_8)).toString()
    private fun sha(bytes: ByteArray) = NativeMigrationInertArchive.sha(bytes)
    private fun blob(bytes: ByteArray) = NativeMigrationBlob(bytes.size, sha(bytes), bytes.take(44).toByteArray()) { it.write(bytes) }

    private data class PresentationBaseline(val targetKey: String, val selectedThread: String,
        val destination: DraftDestination, val text: String, val attachments: List<LocalDraftFile>)
    private fun presentation(owner: MobileSession): PresentationBaseline {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        return PresentationBaseline(owner.targetKey, owner.selectedThread, owner.attachmentDestination(), owner.text,
            owner.attachments.map { it.copy(manifest = it.manifest.copy()) })
    }

    /** Only isolated synthetic child fixtures use this observer. Existing onHold handling suppresses
     * observer failures and enters the same permanent hold; no logging happens before hold owns it.
     */
    fun retainingLifetime(run: String) = NativeMigrationReadLifetime(onHold = { failure ->
        android.util.Log.e("NativeMigrationChildOriginals", "run=$run; retained=UNKNOWN; primary failure stack", failure.primary)
        Unit
    })

    fun scenario(scope: NativeMigrationNonemptyDeviceCase.Scope): JSONObject = NativeMigrationNonemptyDeviceCase.pristineScenario(scope)
        .put("producerProfile", SCHEMA).put("inputBoundary", "synthetic-bytes-and-opaque-content-uris")
        .put("providerReadCovered", false).put("osGrantCovered", false).put("cameraOutputsCovered", false)
        .put("producerNodes", org.json.JSONArray(paths)).put("portableTextRecords", TEXTS).put("portableBinaryRecords", BINARIES)

    suspend fun configure(context: Context, owner: MobileSession) = withContext(Dispatchers.Main.immediate) {
        owner.configureExternalInbox(context, migrationRestore = true)
        owner.configureDocumentPicker(context); owner.configurePhotoPicker(context)
        withTimeout(30_000) {
            while (owner.externalInbox?.migrationIdle != true || owner.documentPicker?.recovery?.migrationIdle != true ||
                owner.photoPicker?.recovery?.migrationIdle != true) delay(10)
        }
        require(owner.cameraCapture == null)
    }

    private fun png(): ByteArray {
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            image.setPixels(IntArray(64) { n -> (0xff000000L or ((n * 7919L) and 0xffffffL)).toInt() }, 0, 8, 0, 0, 8, 8)
            ByteArrayOutputStream().also { require(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
                .also { require(it.size in 1..65536) }
        } finally { image.recycle() }
    }

    suspend fun produce(context: Context, owner: MobileSession, stores: Map<String, NativeSessionStorage>, run: String) {
        require(stores.keys == paths.toSet())
        val baseline = withContext(Dispatchers.Main.immediate) { presentation(owner) }
        val persistedMetadata = withContext(Dispatchers.IO) {
            owner.migrationStorage.awaitQueuedWrites()
            requireNotNull(owner.migrationStorage.read("metadata"))
        }
        // The offline case cannot pass UI compose admission. Use its captured actual presentation
        // with a synthetic retired local generation; never select another draft or persist authority.
        val destination = AttachmentImportDestination(baseline.targetKey, id(run, "retired-local-generation"), baseline.destination,
            requestId = id(run, "retired-local-request"), maximum = 5)
        val photo = png(); val plain = "Owned nonempty child original 😀\n".toByteArray(Charsets.UTF_8)
        try {
            for (namespace in listOf("external-inbox") + kinds.map { it.namespace }) {
                val selected = kinds.firstOrNull { it.namespace == namespace }
                val child = stores.getValue(namespace)
                val payload = if (selected == null) child else stores.getValue("$namespace/payloads")
                val inbox = NativeAttachmentInboxStore(payload)
                val journal = selected?.let { NativeDocumentJournal(child) }
                for (phase in listOf("pending", "failed", "consumed")) {
                    val batchId = id(run, "$namespace:$phase")
                    val uris = (0 until if (phase == "failed") 2 else 1).map { index ->
                        Uri.parse("content://p7-child-originals.invalid/$run/$namespace/$phase/$index")
                    }
                    journal?.let {
                        it.prepare(NativePhotoRequest(destination, 7, false, batchId))
                        it.update(batchId) { old -> old.copy(phase = "selected", sources = uris.map(Uri::toString)) }
                    }
                    var reads = 0
                    val staged = try {
                        inbox.stage(batchId, uris, context.contentResolver, maximum = destination.maximum) { uri ->
                            require(uri == uris[reads]); val index = reads++
                            if (phase == "failed" && index == 1) throw ExpectedReadFailure()
                            val image = selected == NativeMigrationSelectionJournal.Kind.PHOTO
                            LocalAttachmentInput(if (image) "original-$phase.png" else "original-$phase.txt",
                                if (image) "image/png" else "text/plain", (if (image) photo else plain).copyOf())
                        }
                    } catch (failure: ExpectedReadFailure) {
                        require(phase == "failed" && reads == 2)
                        inbox.snapshot().records.single { it.id == batchId }.also { require(it.phase == "failed") }
                    }
                    require(reads == uris.size)
                    val binding = InboxBinding(destination.key, destination.generation)
                    when (phase) {
                        "pending" -> { inbox.bind(staged, binding); journal?.update(batchId) { it.copy(phase = "ready") } }
                        "consumed" -> {
                            inbox.consume(inbox.bind(staged, binding), binding)
                            journal?.update(batchId) { it.copy(phase = "consumed") }
                        }
                        else -> journal?.update(batchId) { it.copy(phase = "failed") }
                    }
                }
                val original = inbox.snapshot()
                require(original.records.map { it.phase } == listOf("pending", "failed", "consumed"))
                require(original.records[0].binding == InboxBinding(destination.key, destination.generation))
                require(original.records[1].slots.size == 2 && original.records[1].readyFiles.size == 1)
                require(original.records[2].slots.isEmpty() && original.records[2].binding == null)
                if (selected != null) require(journal!!.records().map { it.phase } == listOf("ready", "failed", "consumed"))
            }
        } finally { photo.fill(0); plain.fill(0) }
        withContext(Dispatchers.Main.immediate) {
            require(presentation(owner) == baseline) // Exact current presentation, including an originally empty attachment list.
            require(owner.externalInbox!!.migrationIdle && owner.documentPicker!!.recovery!!.migrationIdle && owner.photoPicker!!.recovery!!.migrationIdle)
        }
        withContext(Dispatchers.IO) {
            owner.migrationStorage.awaitQueuedWrites()
            // The independent persisted synthetic-thread draft/file remains byte-identical even
            // when it is not the currently selected presentation. Full portable graph checks stay separate.
            require(owner.migrationStorage.read("metadata") == persistedMetadata)
        }
    }

    /** Complete eager pins are bounded synthetic evidence, not a second exporter or production graph definition. */
    class Source(val text: Map<String, String>, val binary: Map<String, ByteArray>) {
        fun close() = binary.values.forEach { it.fill(0) }
    }
    fun source(context: Context, storage: NativeSessionStorage): Source = storage.migrationInboxSnapshot(context) { inbox ->
        require(inbox.present && inbox.records != null)
        storage.migrationSelectionSnapshot(context, kinds[0]) { document ->
            storage.migrationSelectionSnapshot(context, kinds[1]) { photo ->
                var records = NativeMigrationInbox.append(NativeMigrationStreamRecords(mapOf("metadata" to "{}"), emptyMap(), 4), inbox.records)
                for (selection in listOf(document, photo)) {
                    require(selection.journal.present && selection.payloads.present && selection.outputs == null && selection.raw == null)
                    records = NativeMigrationSelections.append(records, selection)
                }
                val text = records.text.filterKeys { it != "metadata" }.mapValues { it.value }
                val binary = records.binary.mapValues { (_, value) -> ByteArrayOutputStream().also(value::verifiedCopy).toByteArray() }
                require(text.size == 18 && binary.size == 6)
                inbox.unchanged(); document.unchanged(); photo.unchanged()
                Source(text, binary)
            }
        }
    }
    fun requireSource(source: Source, records: NativeMigrationStreamRecords) {
        requirePortable(records)
        for ((name, value) in source.text) require(records.text.getValue(name) == value)
        for ((name, bytes) in source.binary) {
            val seen = ByteArrayOutputStream().also(records.binary.getValue(name)::verifiedCopy).toByteArray()
            try { require(seen.contentEquals(bytes)) } finally { seen.fill(0) }
        }
    }

    fun requireNames(text: Set<String>, binary: Set<String>) {
        val childText = linkedSetOf<String>(); val childBinary = linkedSetOf<String>()
        val sections = listOf(Triple(NativeMigrationInbox.RECORD, NativeMigrationInbox.TEXT, NativeMigrationInbox.BINARY) to (3 to 2)) +
            kinds.mapIndexed { index, kind -> Triple(NativeMigrationSelections.record(kind), NativeMigrationSelections.textPrefix(kind), NativeMigrationSelections.binaryPrefix(kind)) to ((if (index == 0) 5 else 7) to 2) }
        for ((section, counts) in sections) {
            val (catalog, textPrefix, binaryPrefix) = section
            val texts = text.filter { it.startsWith(textPrefix) }.toSet(); val bytes = binary.filter { it.startsWith(binaryPrefix) }.toSet()
            require(catalog in text && texts.size == counts.first && bytes.size == counts.second)
            val identities = (texts.map { it.removePrefix(textPrefix) } + bytes.map { it.removePrefix(binaryPrefix) }).map { suffix ->
                require(suffix.matches(Regex("[a-f0-9]{64}:[0-9]{1,3}"))); suffix.substringBefore(':')
            }
            require(identities.distinct().size == 1)
            childText.add(catalog); childText.addAll(texts); childBinary.addAll(bytes)
        }
        require(text.size == TEXTS && binary.size == BINARIES)
        NativeMigrationNonemptyPairContract.requireNineNames(text - childText, binary - childBinary)
    }

    private fun original(records: NativeMigrationStreamRecords, catalog: String, textPrefix: String, binaryPrefix: String): NativeMigrationStreamRecords {
        val row = StrictMigrationJson.parse(records.text.getValue(catalog).toByteArray(Charsets.UTF_8)).getJSONArray("archives")
            .also { require(it.length() == 1) }.getJSONObject(0)
        val text = linkedMapOf<String, String>(); val binary = linkedMapOf<String, NativeMigrationBlob>(); val entries = row.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index); val name = entry.getString("name")
            if (entry.getString("kind") == "text") text[name] = records.text.getValue(textPrefix + row.getString("id") + ":$index")
            else binary[name] = records.binary.getValue(binaryPrefix + row.getString("id") + ":$index")
        }
        return NativeMigrationStreamRecords(text, binary, 4)
    }
    private fun requireInbox(records: NativeMigrationStreamRecords, photo: Boolean = false): JSONObject {
        NativeMigrationInboxGraph.validate(records.text, records.binary)
        val index = StrictMigrationJson.parse(records.text.getValue(NativeMigrationInboxGraph.INDEX).toByteArray(Charsets.UTF_8))
        val rows = index.getJSONArray("records"); require(rows.length() == 3 && index.getInt("version") == 2)
        require(rows.objects().map { it.getString("phase") } == listOf("pending", "failed", "consumed"))
        require(!rows.getJSONObject(0).isNull("binding") && rows.getJSONObject(1).isNull("binding") && rows.getJSONObject(2).isNull("binding"))
        require(rows.getJSONObject(0).getJSONArray("slots").length() == 1 && rows.getJSONObject(1).getJSONArray("slots").length() == 2 && rows.getJSONObject(2).getJSONArray("slots").length() == 0)
        require(records.text.size == (if (photo) 5 else 3) && records.binary.size == 2)
        if (photo) {
            val thumbs = records.text.filterKeys { it.startsWith("draft-thumbnail-v1:") }; require(thumbs.size == 2)
            require(thumbs.values.all { !JSONObject(it).isNull("png") })
        }
        return index
    }
    fun requirePortable(text: Map<String, String>, binary: Map<String, ByteArray>) =
        requirePortable(NativeMigrationStreamRecords(text, binary.mapValues { blob(it.value) }, 4))
    fun requirePortable(records: NativeMigrationStreamRecords) {
        require(records.version == 4 && records.size == RECORDS); requireNames(records.text.keys, records.binary.keys)
        NativeMigrationInbox.validate(records.text, records.binary.filterKeys { it.startsWith(NativeMigrationInbox.BINARY) })
        NativeMigrationSelections.validate(records.text, records.binary)
        requireInbox(original(records, NativeMigrationInbox.RECORD, NativeMigrationInbox.TEXT, NativeMigrationInbox.BINARY))
        for (kind in kinds) {
            val selected = original(records, NativeMigrationSelections.record(kind), NativeMigrationSelections.textPrefix(kind), NativeMigrationSelections.binaryPrefix(kind))
            val journal = NativeMigrationSelectionJournal.decode(selected.text.getValue("journal:" + NativeMigrationSelectionJournal.RECORD), kind)
            require(journal.requests.map { it.phase } == listOf("ready", "failed", "consumed"))
            val payloads = NativeMigrationStreamRecords(selected.text.filterKeys { it.startsWith("payloads:") }.mapKeys { it.key.removePrefix("payloads:") },
                selected.binary.mapKeys { it.key.removePrefix("payloads:") }, 4)
            val rows = requireInbox(payloads, kind == NativeMigrationSelectionJournal.Kind.PHOTO).getJSONArray("records")
            require(journal.requests.map { it.id } == rows.objects().map { it.getString("id") })
            require(journal.requests.all { it.releaseReadGrants.isEmpty() && it.sources.all { uri -> uri.startsWith("content://p7-child-originals.invalid/") } })
            val binding = rows.getJSONObject(0).getJSONObject("binding")
            require(binding.getString("key") == journal.requests[0].key && binding.getString("generation") == journal.requests[0].generation)
        }
    }
    fun requireActive(records: NativeMigrationStreamRecords) {
        val local = setOf(NativeMigrationDeviceState.AUDIT, NativeMigrationNotificationTombstones.RECORD)
        require(local.all { it in records.text })
        requirePortable(records.copy(text = records.text.filterKeys { it !in local }))
    }
    fun requireDestination(storage: NativeSessionStorage, original: Map<String, String>) {
        require(storage.migrationPendingChildScopes().isEmpty()) // No original journal/index was restored as a live child.
        require(NativeMigrationInbox.read(storage).size == 1 && kinds.all { NativeMigrationSelections.read(storage, it).size == 1 })
        for (name in original.keys.filter { it.startsWith("migration-external-inbox-") || it.startsWith("migration-document-results-") || it.startsWith("migration-photo-results-") })
            require(storage.read(name) == original.getValue(name))
    }
    /** Source selection owners remain sealed after restore; never resume/requery/import them.
     * Formal retirement requests actor scope cancellation and retires its exact writers. Neither
     * tasks.cancel nor the separate shared Session join is evidence of an actor Job.join.
     */
    suspend fun retireTransferredSourceActors(owner: MobileSession, lease: NativeMigrationOwnerLease,
        stores: Map<String, NativeSessionStorage>,
        retire: (String, Any, List<NativeSessionStorage>, String, () -> Unit) -> Unit) = withContext(Dispatchers.Main.immediate) {
        require(stores.keys == paths.toSet())
        val document = requireNotNull(owner.documentPicker?.recovery)
        val photo = requireNotNull(owner.photoPicker?.recovery)
        val inbox = requireNotNull(owner.externalInbox)
        val documentStores = listOf(stores.getValue("document-results"), stores.getValue("document-results/payloads"))
        val photoStores = listOf(stores.getValue("photo-results"), stores.getValue("photo-results/payloads"))
        val inboxStores = listOf(stores.getValue("external-inbox"))
        val root = owner.migrationStorage
        // The closed former lease identifies the transferred actors; it grants no current writer authority.
        val selected = listOf(NativeMigrationSelectionJournal.Kind.DOCUMENT to (document to documentStores),
            NativeMigrationSelectionJournal.Kind.PHOTO to (photo to photoStores))
        require(lease.selectionWriters.map { it.kind }.toSet() == selected.map { it.first }.toSet())
        for ((kind, pair) in selected) {
            val (actor, expected) = pair
            val actual = actor.migrationStores(root)
            require(actual.size == expected.size && actual.indices.all { actual[it] === expected[it] })
            val former = lease.selectionWriters.single { it.kind == kind }
            require(former.owner === actor && former.keys == expected.map { it.coordinationKey })
        }
        val formerInbox = requireNotNull(lease.inboxWriters)
        require(formerInbox.owner === inbox && formerInbox.operation == lease.operationId && formerInbox.childKey == inboxStores.single().coordinationKey)
        retire("document-results", document, documentStores, lease.operationId) { document.retireForMigration(lease.operationId) }
        retire("photo-results", photo, photoStores, lease.operationId) { photo.retireForMigration(lease.operationId) }
        retire("external-inbox", inbox, inboxStores, lease.operationId) { inbox.retireForMigration() }
    }
}
