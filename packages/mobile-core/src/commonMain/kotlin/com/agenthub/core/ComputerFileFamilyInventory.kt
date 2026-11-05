package com.agenthub.core

/** Metadata observed in a local F59 source. Validation does not authenticate
 * these values or authorize restoration, replay, publication or activation.
 */
enum class ComputerFileFamilyChild { ABSENT, PRESENT_EMPTY, PRESENT_WITH_RECORDS }

data class ComputerFileFamilyRecord(val entry: ComputerFileMigrationEntry, val sourceCiphertextSha256: String)

/** A native source can record this only after its real read-only journal and
 * directory barriers. Portable validation only checks the claimed shape.
 */
enum class ComputerFileFamilyTerminalObservation { SOURCE_FILE_AND_DIRECTORIES_SYNCED }

data class ComputerFileFamilyRetiredChunk(
    val journalName: String,
    val terminalJournalSha256: String,
    val originalId: String,
    val index: Int,
    val entry: ComputerFileMigrationEntry,
    val observation: ComputerFileFamilyTerminalObservation
)

data class ComputerFileFamilyMember(
    val generation: String?,
    val child: ComputerFileFamilyChild,
    val journals: List<ComputerFileFamilyRecord>,
    val chunks: List<ComputerFileFamilyRecord>,
    val retired: List<ComputerFileFamilyRetiredChunk>
)

/** Source pointer audit only. The observed generation never identifies a
 * destination candidate or its publication pointer.
 */
sealed interface ComputerFileFamilySourcePointer {
    object Absent : ComputerFileFamilySourcePointer
    data class Observed(val generation: String, val sourceCiphertextSha256: String) : ComputerFileFamilySourcePointer
}

data class ComputerFileFamilyInventoryBudget(
    val maximumGenerations: Int,
    val maximumJournals: Int,
    val maximumChunkDescriptors: Int,
    val maximumBinaryBytes: Long
)

class ComputerFileFamilyInventory private constructor(
    private val values: List<ComputerFileFamilyMember>,
    val sourcePointer: ComputerFileFamilySourcePointer
) {
    val members: List<ComputerFileFamilyMember> get() = values.map { it.detached() }

    companion object {
        private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val digest = Regex("[a-f0-9]{64}")

        fun validated(
            members: List<ComputerFileFamilyMember>,
            sourcePointer: ComputerFileFamilySourcePointer,
            budget: ComputerFileFamilyInventoryBudget
        ): ComputerFileFamilyInventory {
            require(budget.maximumGenerations in 0..32 &&
                budget.maximumJournals in 1..ComputerFileMigrationRules.MAX_JOURNALS &&
                budget.maximumChunkDescriptors in 1..ComputerFileMigrationRules.MAX_CHUNK_DESCRIPTORS &&
                budget.maximumBinaryBytes in 1..ComputerFileMigrationRules.MAX_SAFE_BYTES) { "family-inventory-budget" }
            val suppliedMembers = members.boundedCopy(budget.maximumGenerations + 1, "family-member-count")
            require(suppliedMembers.isNotEmpty()) { "family-member-count" }
            require(suppliedMembers.first().generation == null) { "family-root-first" }

            val copies = ArrayList<ComputerFileFamilyMember>(suppliedMembers.size)
            var previousGeneration = ""
            var journalCount = 0
            var descriptorCount = 0
            var binaryBytes = 0L
            for ((index, supplied) in suppliedMembers.withIndex()) {
                if (index != 0) {
                    val generation = requireNotNull(supplied.generation) { "family-single-root" }
                    require(uuid.matches(generation) && generation > previousGeneration) { "family-generation-order" }
                    previousGeneration = generation
                }
                val journals = supplied.journals.boundedCopy(budget.maximumJournals - journalCount, "family-record-count")
                val chunks = supplied.chunks.boundedCopy(budget.maximumChunkDescriptors - descriptorCount, "family-record-count")
                val retired = supplied.retired.boundedCopy(
                    budget.maximumChunkDescriptors - descriptorCount - chunks.size, "family-record-count"
                )
                val member = supplied.copy(journals = journals, chunks = chunks, retired = retired)
                journalCount += member.journals.size
                descriptorCount += member.chunks.size + member.retired.size
                when (member.child) {
                    ComputerFileFamilyChild.ABSENT, ComputerFileFamilyChild.PRESENT_EMPTY ->
                        require(member.journals.isEmpty() && member.chunks.isEmpty() && member.retired.isEmpty()) { "family-empty-child" }
                    ComputerFileFamilyChild.PRESENT_WITH_RECORDS ->
                        require(member.journals.isNotEmpty()) { "family-missing-journal" }
                }

                val journalHashes = HashMap<String, String>()
                var previousName = ""
                for (record in member.journals) {
                    require(record.entry.name > previousName) { "family-journal-order" }
                    require(digest.matches(record.sourceCiphertextSha256)) { "family-ciphertext-pin" }
                    ComputerFileInnerPayloadRules.validateDescriptor(ComputerFileInnerDescriptor(
                        member.generation, ComputerFileInnerKind.JOURNAL, record.entry
                    ))
                    journalHashes[record.entry.name] = record.entry.sha256
                    previousName = record.entry.name
                }
                val liveChunks = HashSet<String>()
                previousName = ""
                for (record in member.chunks) {
                    require(record.entry.name > previousName) { "family-chunk-order" }
                    require(digest.matches(record.sourceCiphertextSha256)) { "family-ciphertext-pin" }
                    ComputerFileInnerPayloadRules.validateDescriptor(ComputerFileInnerDescriptor(
                        member.generation, ComputerFileInnerKind.CHUNK, record.entry
                    ))
                    binaryBytes = ComputerFileInnerPayloadRules.add(binaryBytes, record.entry.size.toLong(), budget.maximumBinaryBytes)
                    liveChunks.add(record.entry.name)
                    previousName = record.entry.name
                }
                val retiredOwners = HashMap<String, String>()
                previousName = ""
                for (retired in member.retired) {
                    require(retired.entry.name > previousName && retired.entry.name !in liveChunks) { "family-retired-order-or-live" }
                    require(retired.entry.name == "computer-file-original:${retired.originalId}:${retired.index}" &&
                        journalHashes[retired.journalName] == retired.terminalJournalSha256) { "family-retired-journal-or-part" }
                    ComputerFileInnerPayloadRules.validateDescriptor(ComputerFileInnerDescriptor(
                        member.generation, ComputerFileInnerKind.CHUNK, retired.entry
                    ))
                    val priorJournal = retiredOwners.put(retired.originalId, retired.journalName)
                    require(priorJournal == null || priorJournal == retired.journalName) { "family-retired-original-owner" }
                    previousName = retired.entry.name
                }
                copies.add(member)
            }
            if (sourcePointer is ComputerFileFamilySourcePointer.Observed) {
                require(digest.matches(sourcePointer.sourceCiphertextSha256) &&
                    copies.any { it.generation == sourcePointer.generation }) { "family-source-pointer" }
            }
            return ComputerFileFamilyInventory(copies, sourcePointer)
        }
    }
}

private fun <T> List<T>.boundedCopy(maximum: Int, failure: String): List<T> {
    val count = size
    require(count in 0..maximum) { failure }
    val snapshot = ArrayList<T>(count)
    val source = iterator()
    while (source.hasNext()) {
        require(snapshot.size < count) { failure }
        snapshot.add(source.next())
    }
    require(snapshot.size == count) { failure }
    return snapshot
}

private fun ComputerFileFamilyMember.detached() =
    copy(journals = journals.toList(), chunks = chunks.toList(), retired = retired.toList())
