package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Source-only assertion helper for the next closed paired purpose. No store, Session, callback,
 * grant, URI, operation or migration is created here. It cannot be selected as an instrumentation
 * test until Root binds a distinct host admission and complete owner-accounting implementation.
 */
internal object NativeMigrationExistingOriginalsWitness {
    const val SCHEMA = "p7-existing-child-originals-controller-v1"
    const val METHOD = "existingChildOriginalsControllerPreservesDistinctArchives"
    const val COLD_SCHEMA = "p7-existing-child-originals-readonly-cold-v1"
    const val COLD_METHOD = "existingChildOriginalsReadOnlyColdAfterExactOriginalExit"
    const val SOURCE_RECORDS = 33
    const val ACTIVE_TEXTS = 43
    const val ACTIVE_BINARIES = 13
    private data class Family(val catalog: String, val text: String, val binary: String,
        val kind: NativeMigrationSelectionJournal.Kind?)
    private val families = listOf(Family(NativeMigrationInbox.RECORD, NativeMigrationInbox.TEXT, NativeMigrationInbox.BINARY, null)) +
        listOf(NativeMigrationSelectionJournal.Kind.DOCUMENT, NativeMigrationSelectionJournal.Kind.PHOTO).map { kind ->
            Family(NativeMigrationSelections.record(kind), NativeMigrationSelections.textPrefix(kind), NativeMigrationSelections.binaryPrefix(kind), kind)
        }
    data class Original(val id: String, val descriptor: String, val text: Map<String, String>,
        val binary: Map<String, String>, val requestIds: List<String>, val phases: List<String>)
    data class Graph(val originals: Map<String, Map<String, Original>>, val textAliases: Set<String>, val binaryAliases: Set<String>) {
        fun json() = JSONObject().put("schema", "p7-existing-child-originals-witness-v1")
            .put("families", JSONObject(originals.mapValues { (_, archive) -> JSONObject(archive.mapValues { (_, original) ->
                JSONObject().put("id", original.id).put("descriptor", original.descriptor)
                    .put("textSha256", JSONObject(original.text)).put("binarySha256", JSONObject(original.binary))
                    .put("requestIds", JSONArray(original.requestIds)).put("phases", JSONArray(original.phases))
            }) })).put("textAliases", JSONArray(textAliases.sorted())).put("binaryAliases", JSONArray(binaryAliases.sorted()))
    }
    private fun sha(raw: String) = NativeMigrationInertArchive.sha(raw.toByteArray(Charsets.UTF_8))
    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long -> value.toString()
        JSONObject.NULL -> "null"
        else -> error("Invalid existing-originals witness scalar")
    }
    private fun actualBytes(blob: NativeMigrationBlob): ByteArray = ByteArrayOutputStream().also(blob::verifiedCopy).toByteArray()

    /** Exact descriptor and full-byte pins, with request IDs as evidence inside each content archive.
     * No call to Originals.merge is used to compute the expected result.
     */
    fun capture(records: NativeMigrationStreamRecords, originalsPerFamily: Int): Graph {
        require(originalsPerFamily in 1..2)
        NativeMigrationInbox.validate(records.text, records.binary.filterKeys { it.startsWith(NativeMigrationInbox.BINARY) })
        NativeMigrationSelections.validate(records.text, records.binary)
        val aliases = linkedSetOf<String>(); val byteAliases = linkedSetOf<String>()
        val result = linkedMapOf<String, Map<String, Original>>()
        for (family in families) {
            val rows = StrictMigrationJson.parse(records.text.getValue(family.catalog).toByteArray(Charsets.UTF_8)).getJSONArray("archives")
            require(rows.length() == originalsPerFamily)
            val originals = linkedMapOf<String, Original>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val id = row.getString("id"); val entries = row.getJSONArray("entries")
                val text = linkedMapOf<String, String>(); val binary = linkedMapOf<String, String>(); val rawText = linkedMapOf<String, String>()
                for (n in 0 until entries.length()) {
                    val entry = entries.getJSONObject(n); val name = entry.getString("name")
                    if (entry.getString("kind") == "text") {
                        val alias = family.text + id + ":$n"; require(aliases.add(alias))
                        val raw = records.text.getValue(alias); require(raw.toByteArray(Charsets.UTF_8).size == entry.getInt("size"))
                        require(text.put(name, sha(raw)) == null && text.getValue(name) == entry.getString("sha256")); rawText[name] = raw
                    } else {
                        require(entry.getString("kind") == "binary")
                        val alias = family.binary + id + ":$n"; require(byteAliases.add(alias))
                        val bytes = actualBytes(records.binary.getValue(alias))
                        try { require(bytes.size == entry.getInt("size") && binary.put(name, NativeMigrationInertArchive.sha(bytes)) == null && binary.getValue(name) == entry.getString("sha256")) }
                        finally { bytes.fill(0) }
                    }
                }
                val requests: List<String>; val phases: List<String>
                if (family.kind == null) {
                    val batches = StrictMigrationJson.parse(rawText.getValue(NativeMigrationInboxGraph.INDEX).toByteArray(Charsets.UTF_8)).getJSONArray("records")
                    require(batches.length() == 3)
                    requests = List(batches.length()) { batches.getJSONObject(it).getString("id") }
                    phases = List(batches.length()) { batches.getJSONObject(it).getString("phase") }
                    require(phases == listOf("pending", "failed", "consumed"))
                } else {
                    val journal = NativeMigrationSelectionJournal.decode(rawText.getValue("journal:" + NativeMigrationSelectionJournal.RECORD), family.kind)
                    requests = journal.requests.map { it.id }; phases = journal.requests.map { it.phase }
                    require(phases == listOf("ready", "failed", "consumed"))
                    require(journal.requests.all { it.releaseReadGrants.isEmpty() })
                }
                require(requests.distinct().size == 3)
                require(originals.put(id, Original(id, canonical(row), text, binary, requests, phases)) == null)
            }
            require(originals.keys.toList() == originals.keys.sorted()); aliases.add(family.catalog); result[family.catalog] = originals
        }
        return Graph(result, aliases, byteAliases)
    }
    fun capture(source: NativeMigrationChildOriginalsProfile.Source): Graph = capture(
        NativeMigrationStreamRecords(source.text, source.binary.mapValues { (_, bytes) ->
            NativeMigrationBlob(bytes.size, NativeMigrationInertArchive.sha(bytes), bytes.take(44).toByteArray()) { output -> output.write(bytes) }
        }, 4), 1)

    fun requireDistinct(source: Graph, destination: Graph) {
        require(source.originals.keys == destination.originals.keys && source.originals.size == 3)
        require(source.textAliases.size == 18 && destination.textAliases.size == 18 && source.binaryAliases.size == 6 && destination.binaryAliases.size == 6)
        for (family in families) {
            val first = source.originals.getValue(family.catalog).values.single()
            val second = destination.originals.getValue(family.catalog).values.single()
            require(first.requestIds == second.requestIds && first.phases == second.phases)
            require(first.id != second.id && first.descriptor != second.descriptor)
            require(first.binary.values.toSet().intersect(second.binary.values.toSet()).isEmpty())
        }
    }
    fun scenario(scope: NativeMigrationNonemptyDeviceCase.Scope): JSONObject = NativeMigrationChildOriginalsProfile.scenario(scope)
        .put("producerProfile", SCHEMA).put("destinationOriginals", "nonempty-no-real-clients-three-inert-families")
        .put("destinationOriginalTextRecords", 18).put("destinationOriginalBinaryRecords", 6)
        .put("activeTextRecords", ACTIVE_TEXTS).put("activeBinaryRecords", ACTIVE_BINARIES)

    fun requireActive(records: NativeMigrationStreamRecords) {
        val graph = capture(records, 2)
        for (family in families) {
            val pair = graph.originals.getValue(family.catalog).values.toList()
            require(pair[0].requestIds == pair[1].requestIds && pair[0].phases == pair[1].phases)
            require(pair[0].binary.values.toSet().intersect(pair[1].binary.values.toSet()).isEmpty())
        }
        require(records.text.size == ACTIVE_TEXTS && records.binary.size == ACTIVE_BINARIES)
        val local = setOf(NativeMigrationDeviceState.AUDIT, NativeMigrationNotificationTombstones.RECORD)
        require(local.all { it in records.text })
        NativeMigrationNonemptyPairContract.requireNineNames(records.text.keys - graph.textAliases - local,
            records.binary.keys - graph.binaryAliases)
    }

    /** Hash-level links supplement the native complete-byte witness above. Catalog contents are
     * validated from actual records, not reconstructed from a hash in a proof document.
     */
    fun requireMergePins(originalText: Map<String, String>, originalBinary: Map<String, String>,
        destinationText: Map<String, String>, destinationBinary: Map<String, String>,
        activeText: Map<String, String>, activeBinary: Map<String, String>) {
        require(destinationText.size == 18 && destinationBinary.size == 6)
        val sourceChildText = linkedSetOf<String>(); val activeChildText = linkedSetOf<String>()
        val sourceChildBinary = linkedSetOf<String>(); val activeChildBinary = linkedSetOf<String>()
        for ((index, family) in families.withIndex()) {
            val expectedText = listOf(3, 5, 7)[index]
            fun names(text: Map<String, String>, binary: Map<String, String>, originals: Int): Pair<Set<String>, Set<String>> {
                require(family.catalog in text)
                val texts = text.keys.filter { it.startsWith(family.text) }.toSet()
                val bytes = binary.keys.filter { it.startsWith(family.binary) }.toSet()
                require(texts.size == expectedText * originals && bytes.size == 2 * originals)
                val ids = (texts.map { it.removePrefix(family.text) } + bytes.map { it.removePrefix(family.binary) }).map {
                    require(it.matches(Regex("[a-f0-9]{64}:[0-9]{1,3}"))); it.substringBefore(':')
                }.toSet()
                require(ids.size == originals)
                return texts + family.catalog to bytes
            }
            val source = names(originalText, originalBinary, 1); val destination = names(destinationText, destinationBinary, 1)
            val active = names(activeText, activeBinary, 2)
            require((source.first - family.catalog).intersect(destination.first - family.catalog).isEmpty())
            require(source.second.intersect(destination.second).isEmpty())
            require(active.first == source.first + destination.first && active.second == source.second + destination.second)
            for (name in source.first - family.catalog) require(activeText.getValue(name) == originalText.getValue(name))
            for (name in destination.first - family.catalog) require(activeText.getValue(name) == destinationText.getValue(name))
            for (name in source.second) require(activeBinary.getValue(name) == originalBinary.getValue(name))
            for (name in destination.second) require(activeBinary.getValue(name) == destinationBinary.getValue(name))
            sourceChildText.addAll(source.first); sourceChildBinary.addAll(source.second)
            activeChildText.addAll(active.first); activeChildBinary.addAll(active.second)
        }
        require(destinationText.keys == activeChildText.intersect(destinationText.keys) && destinationBinary.keys == activeChildBinary.intersect(destinationBinary.keys))
        require(activeText.size == ACTIVE_TEXTS && activeBinary.size == ACTIVE_BINARIES)
        val local = setOf(NativeMigrationDeviceState.AUDIT, NativeMigrationNotificationTombstones.RECORD)
        NativeMigrationNonemptyPairContract.requireNineNames(originalText.keys - sourceChildText, originalBinary.keys - sourceChildBinary)
        NativeMigrationNonemptyPairContract.requireNineNames(activeText.keys - activeChildText - local, activeBinary.keys - activeChildBinary)
    }

    fun requireMerged(source: Graph, destination: Graph, active: NativeMigrationStreamRecords) {
        requireDistinct(source, destination)
        val seen = capture(active, 2)
        for (family in families) {
            val expected = source.originals.getValue(family.catalog) + destination.originals.getValue(family.catalog)
            require(expected.size == 2 && seen.originals.getValue(family.catalog) == expected)
        }
        require(seen.textAliases.size == 33 && seen.binaryAliases.size == 12)
        require(active.text.size == ACTIVE_TEXTS && active.binary.size == ACTIVE_BINARIES)
        val local = setOf(NativeMigrationDeviceState.AUDIT, NativeMigrationNotificationTombstones.RECORD)
        require(local.all { it in active.text })
        NativeMigrationNonemptyPairContract.requireNineNames(active.text.keys - seen.textAliases - local,
            active.binary.keys - seen.binaryAliases)
    }
}
