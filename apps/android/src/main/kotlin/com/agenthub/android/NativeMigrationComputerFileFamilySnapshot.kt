package com.agenthub.android

import com.agenthub.core.ComputerFileFamilyInventory
import com.agenthub.core.ComputerFileFamilyInventoryBudget
import com.agenthub.core.ComputerFileFamilySourcePointer

import java.io.File
import java.util.UUID

/** F59 source-only family inventory. Every retained local generation, including
 * unpublished generations, is an independent inert source. Directory presence
 * is not publication lineage, retry authority, or permission to activate it.
 * No portable container/export/activation or ordinary restore gate is changed.
 */
internal class NativeMigrationComputerFileFamilySnapshot private constructor(
    root: File,private val namespace: String,private val activeGeneration: String?,
    private val limits: Limits,private val files: NativeMigrationComputerFileSourceFiles,
    capture: (String?)->NativeMigrationComputerFileSnapshot
) {
    data class Limits(val member: NativeMigrationComputerFileSnapshot.Limits,val maximumGenerations: Int=16) {
        init {require(maximumGenerations in 0..32)}
    }
    private val parent=files.directory(requireNotNull(root.parentFile))
    private val directory=files.directory(root)
    private val rootNames=directory.names(MAX_DIRECTORY_ENTRIES)
    private val generationNames=rootNames.filter {it.startsWith(GENERATION_PREFIX)}.also {names->
        require(names.size<=limits.maximumGenerations) {"F59 family generation budget exceeded"}
        names.forEach {name->
            val id=name.removePrefix(GENERATION_PREFIX)
            require(UUID.fromString(id).toString()==id) {"Unrecognized F59 source generation retained"}
        }
    }
    private val generationDirectories=generationNames.map {files.directory(File(root,it))}
    private val ids=listOf<String?>(null)+generationNames.map {it.removePrefix(GENERATION_PREFIX)}
    private val expectedChildren=ids.associateWith {id->namespace+(id?.let {"-$it"}?:"")+"-"+NativeComputerFileStore.SCOPE}
    private val familyNames=f59PeerNames()
    private val sources=linkedMapOf<String?,NativeMigrationComputerFileSnapshot>()
    val generations: List<String?> get(){files.current();return ids.toList()}
    val currentGeneration: String? get(){files.current();return activeGeneration}

    init {
        require(root.name==namespace&&namespace.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        // Preserve foreign root domains for their own typed inventory. Reject
        // unfinished/unknown root names; never treat a sidecar as a generation.
        require(rootNames.all {it in generationNames||it.matches(Regex("[a-f0-9]{64}\\.bin"))}) {
            "Unrecognized root entry in F59 family; originals retained"
        }
        require(activeGeneration==null||activeGeneration in ids) {"Active F59 source generation missing"}
        var journals=0;var descriptors=0;var binaryBytes=0L;var ciphertextBytes=0L
        for(id in ids) {
            val source=capture(id)
            require(source.generation==id)
            val graph=source.graph
            require(graph.journals.size<=limits.member.graph.maximumJournals-journals) {"F59 family journal budget exceeded"}
            journals+=graph.journals.size
            val count=graph.chunks.size+graph.retiredChunks.size
            require(count<=limits.member.graph.maximumChunkDescriptors-descriptors) {"F59 family chunk budget exceeded"}
            descriptors+=count
            for(chunk in graph.chunks) {
                require(binaryBytes<=limits.member.graph.maximumBinaryBytes-chunk.size) {"F59 family binary budget exceeded"}
                binaryBytes+=chunk.size
            }
            // The same current-pointer ciphertext is conservatively charged
            // per member; this never widens the caller's existing byte budget.
            val size=source.ciphertextBytes
            require(ciphertextBytes<=limits.member.maximumCiphertextBytes-size) {"F59 family ciphertext budget exceeded"}
            ciphertextBytes+=size;sources[id]=source
        }
        unchanged()
    }
    private fun f59PeerNames(): List<String> {
        parent.unchanged()
        val names=parent.names(MAX_DIRECTORY_ENTRIES).filter {name->
            name.startsWith("$namespace-")&&name.contains("-"+NativeComputerFileStore.SCOPE)
        }
        // A child from a missing/unknown generation, suffix lookalike, pending
        // write or sibling sidecar refuses the whole family. No best effort.
        require(names.all {it in expectedChildren.values}) {"Unindexed F59 source family peer retained"}
        return names
    }
    fun member(generation: String?): NativeMigrationComputerFileSnapshot {
        files.current();require(generation in sources);return sources.getValue(generation)
    }
    fun unchanged() {
        files.current();parent.unchanged();directory.unchanged()
        require(directory.names(MAX_DIRECTORY_ENTRIES)==rootNames) {"F59 source family root inventory changed"}
        generationDirectories.forEach {it.unchanged()}
        require(f59PeerNames()==familyNames) {"F59 source family peer inventory changed"}
        sources.values.forEach {it.unchanged()}
        directory.unchanged();parent.unchanged();files.current()
    }
    /** Detached value-only inventory. The actual source and raw owner remain
     * live until their owning scopes close; no seal or activation follows here.
     */
    fun inertInventory(): ComputerFileFamilyInventory {
        unchanged()
        val pointers=sources.values.map {it.inertPointerCiphertextPin()}.distinct()
        check(pointers.size==1)
        val pin=pointers.single()
        val generation=activeGeneration
        val pointer=if(generation==null) {
            check(pin==null)
            ComputerFileFamilySourcePointer.Absent
        } else ComputerFileFamilySourcePointer.Observed(generation,checkNotNull(pin))
        val graph=limits.member.graph
        val inventory=ComputerFileFamilyInventory.validated(sources.values.map {it.inertInventoryMember()},pointer,
            ComputerFileFamilyInventoryBudget(limits.maximumGenerations,graph.maximumJournals,
                graph.maximumChunkDescriptors,graph.maximumBinaryBytes))
        unchanged()
        return inventory
    }

    /** Local evidence only, not a new wire schema. Same logical row names from
     * distinct generations remain distinct; no winning generation is inferred.
     */
    fun ciphertextPins(): Map<String,String> {
        unchanged()
        return sources.flatMap {(id,source)->source.ciphertextPins().map {(name,digest)->
            (id?.let {"generation-$it"}?:"original-root")+"/"+name to digest
        }}.toMap()
    }
    companion object {
        private const val GENERATION_PREFIX="migration-generation-"
        private const val MAX_DIRECTORY_ENTRIES=4096
        internal fun capture(root: File,namespace: String,activeGeneration: String?,limits: Limits,
            files: NativeMigrationComputerFileSourceFiles,member: (String?)->NativeMigrationComputerFileSnapshot)=
            NativeMigrationComputerFileFamilySnapshot(root,namespace,activeGeneration,limits,files,member)
    }
}
