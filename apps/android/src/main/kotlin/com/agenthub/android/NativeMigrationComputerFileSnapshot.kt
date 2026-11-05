package com.agenthub.android

import com.agenthub.core.ComputerFileFamilyChild
import com.agenthub.core.ComputerFileFamilyMember
import com.agenthub.core.ComputerFileFamilyRecord
import com.agenthub.core.ComputerFileFamilyRetiredChunk
import com.agenthub.core.ComputerFileFamilyTerminalObservation
import com.agenthub.core.ComputerFileMigrationEntry

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.OutputStream

/** Exact current-generation F59 originals, usable only inside one real frozen
 * owner's synchronous IO callback. No exporter/activation gate is changed.
 * Fixed directory handles and bounded regular-file reads never prepare a child,
 * create a key, borrow a writer lock, repair a sidecar, or expose a SecretKey.
 */
internal class NativeMigrationComputerFileSnapshot private constructor(context: Context,root: File,
    namespace: String,keyAlias: String,private val expectedGeneration: String?,
    private val limits: Limits,private val files: NativeMigrationComputerFileSourceFiles,
    private val familyGeneration: String?=null,private val familyMember: Boolean=false) {
    data class Limits(val graph: NativeMigrationComputerFileGraph.Limits,val maximumCiphertextBytes: Long) {
        init {require(maximumCiphertextBytes in 1..com.agenthub.core.ComputerFileMigrationRules.MAX_SAFE_BYTES)}
    }
    private val parent=files.directory(requireNotNull(root.parentFile))
    private val rootDirectory=files.directory(root)
    private var generationDirectory: NativeMigrationComputerFileSourceFiles.Directory?=null
    private val pointerFile=File(root,hash("migration-active-generation-v1")+".bin")
    private val pointer=if(absent(pointerFile))null else files.reference(rootDirectory,pointerFile.name,70_000)
    private val rootDecoder=if(pointer==null)null else decoder(context,parent.file,namespace,keyAlias)
    val generation: String?
    private val childName: String
    private val childFile: File
    private val child: NativeMigrationComputerFileSourceFiles.Directory?
    private val childDecoder: NativeSessionStorage?
    private val inventory: List<String>
    private val control: NativeMigrationComputerFileSourceFiles.Reference?
    private val references=linkedMapOf<String,NativeMigrationComputerFileSourceFiles.Reference>()
    private val terminalJournals=linkedMapOf<String,NativeMigrationComputerFileSourceFiles.Reference>()
    private val textFrames=linkedMapOf<String,NativeMigrationBlob>()
    private val binaryFrames=linkedMapOf<String,NativeMigrationBlob>()
    private var terminalBarriersConfirmed=false
    private lateinit var originalGraph: NativeMigrationComputerFileGraph.Graph
    val present get()=child!=null
    val journals: Map<String,String> get(){files.current();return NativeMigrationTextMap(textFrames.mapValues {it.value.copy(prefix=it.value.prefix.copyOf())})}
    val chunks: Map<String,NativeMigrationBlob> get(){files.current();return binaryFrames.mapValues {it.value.copy(prefix=it.value.prefix.copyOf())}}
    val graph: NativeMigrationComputerFileGraph.Graph get(){
        files.current()
        return originalGraph.copy(journals=originalGraph.journals.toList(),chunks=originalGraph.chunks.toList(),
            retiredChunks=originalGraph.retiredChunks.toList())
    }

    init {
        require(root.name==namespace&&namespace.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        pointerSettled()
        val currentGeneration=pointer?.let {ref->
            val bytes=ref.bytes(synchronize=true)
            try {requireNotNull(rootDecoder).existingMigrationGeneration(rootDecoder.existingComputerFileSourcePointer(bytes))}
            finally {bytes.fill(0)}
        }
        require(currentGeneration==expectedGeneration) {"F59 source generation changed"}
        generation=if(familyMember)familyGeneration else currentGeneration
        generationDirectory=generation?.let {files.directory(File(root,"migration-generation-$it"))}
        childName=namespace+(generation?.let {"-$it"}?:"")+"-"+NativeComputerFileStore.SCOPE
        require(childName.matches(Regex("[a-zA-Z0-9_-]{1,255}")))
        childFile=File(parent.file,childName)
        child=if(absent(childFile))null else files.directory(childFile,privateMode=true)
        childDecoder=if(child==null)null else decoder(context,parent.file,childName,
            NativeMigrationExistingView.childAlias(keyAlias,NativeComputerFileStore.SCOPE,generation))
        rejectChildSidecars()
        inventory=child?.names(limits.graph.maximumJournals+limits.graph.maximumChunkDescriptors+1).orEmpty()
        require(inventory.all {it==CONTROL||it.matches(Regex("[a-f0-9]{64}\\.bin"))}) {
            "Unknown F59 source entry or unfinished write; originals retained"
        }
        control=if(CONTROL in inventory)files.reference(requireNotNull(child),CONTROL,0,control=true)else null
        control?.requireControl()
        var encryptedBytes=0L
        for(name in inventory.filter {it!=CONTROL}) {
            val ref=files.reference(requireNotNull(child),name,MAX_CIPHERTEXT_RECORD)
            require(encryptedBytes<=limits.maximumCiphertextBytes-ref.size) {"F59 source ciphertext budget exceeded"}
            encryptedBytes+=ref.size;references[name]=ref
            val bytes=ref.bytes()
            try {when {
                bytes[0]==3.toByte()->discoverJournal(ref,bytes)
                bytes.size>=4&&java.nio.ByteBuffer.wrap(bytes).int==4->discoverChunk(ref,bytes)
                else->error("Only authenticated F59 local formats3/4 are portable; original retained")
            }}finally {bytes.fill(0)}
        }
        // A completed row may have retired chunks. Source file+both directory
        // barriers and the actual file-FD closes must all return before the graph
        // may describe such absence. An O_RDONLY fsync rejection is a failure.
        for(ref in terminalJournals.values){val bytes=ref.bytes(synchronize=true);bytes.fill(0)}
        child?.synchronize();parent.synchronize()
        if(pointer!=null)rootDirectory.synchronize()
        terminalBarriersConfirmed=true
        originalGraph=NativeMigrationComputerFileGraph.validate(journals,binaryFrames,limits.graph)
        unchanged()
    }
    private fun decoder(context: Context,base: File,namespace: String,alias: String): NativeSessionStorage {
        val bounded=object: ContextWrapper(context) {
            override fun getNoBackupFilesDir()=base
            override fun getApplicationContext(): Context=this
        }
        val storage=files.own({NativeSessionStorage.existingComputerFileSourceDecoder(bounded,namespace,alias)},{it.retireWriter()})
        storage.requireExistingComputerFileSourceKey()
        return storage
    }
    private fun absent(file: File): Boolean {
        files.current()
        return try {android.system.Os.lstat(file.path);false}catch(error: android.system.ErrnoException) {
            if(error.errno!=android.system.OsConstants.ENOENT)throw error
            files.requireAbsent(file);true
        }
    }
    private fun pointerSettled() {
        rootDirectory.unchanged();parent.unchanged();generationDirectory?.unchanged()
        for(suffix in listOf(".new",".bak",".f59-new"))files.requireAbsent(File(pointerFile.path+suffix))
        if(pointer==null)files.requireAbsent(pointerFile)else pointer.unchanged()
    }
    private fun rejectChildSidecars() {
        parent.unchanged()
        for(name in parent.names(MAX_PARENT_ENTRIES))if(name==childName||name.startsWith("$childName-")||name.startsWith("$childName."))
            require(name==childName) {"Unrecognized F59 source sibling retained"}
        if(child==null)files.requireAbsent(childFile)else child.unchanged()
    }
    private fun journal(ref: NativeMigrationComputerFileSourceFiles.Reference,expected: String?=null): Pair<String,String> {
        files.current();val bytes=ref.bytes()
        try {
            val result=requireNotNull(childDecoder).existingComputerFileSourceJournal(bytes)
            require(expected==null||expected==result.first);files.current();return result
        }finally {bytes.fill(0)}
    }
    private fun discoverJournal(ref: NativeMigrationComputerFileSourceFiles.Reference,bytes: ByteArray) {
        val (name,raw)=requireNotNull(childDecoder).existingComputerFileSourceJournal(bytes)
        require(ref.file.name==hash(name)+".bin"&&name !in textFrames&&textFrames.size<limits.graph.maximumJournals)
        val decoded=NativeComputerFileJournal.decode(name,raw,null)
        if(decoded.files.any {it.phase==ComputerFilePhase.COMPLETED})terminalJournals[name]=ref
        val clear=NativeMigrationTextFrames.bytes(raw)
        try {
            require(clear.size<=NativeComputerFileJournal.MAX_JOURNAL_BYTES)
            val probe=NativeMigrationProbe();probe.write(clear)
            textFrames[name]=NativeMigrationBlob(clear.size,probe.sha256(),probe.prefix()) {output->
                val current=NativeMigrationTextFrames.bytes(journal(ref,name).second)
                try {files.current();output.write(current);files.current()}finally {current.fill(0)}
            }
        }finally {clear.fill(0)}
    }
    private fun discoverChunk(ref: NativeMigrationComputerFileSourceFiles.Reference,bytes: ByteArray) {
        val (name,clear)=requireNotNull(childDecoder).existingComputerFileSourceChunk(bytes)
        try {
            require(ref.file.name==hash(name)+".bin"&&name !in binaryFrames&&binaryFrames.size<limits.graph.maximumChunkDescriptors)
            val probe=NativeMigrationProbe();probe.write(clear)
            val size=clear.size;val digest=probe.sha256()
            binaryFrames[name]=NativeMigrationBlob(size,digest,probe.prefix()) {output->copy(ref,name,size,digest,output)}
        }finally {clear.fill(0)}
    }
    private fun copy(ref: NativeMigrationComputerFileSourceFiles.Reference,name: String,size: Int,digest: String,output: OutputStream) {
        files.current();val bytes=ref.bytes()
        try {
            val current=requireNotNull(childDecoder).existingComputerFileSourceChunk(bytes)
            try {
                require(current.first==name&&current.second.size==size&&ComputerFileCodec.hash(current.second)==digest)
                files.current();output.write(current.second);files.current()
            }finally {current.second.fill(0)}
        }finally {bytes.fill(0)}
    }
    /** Before publication, and automatically once more when the consumer returns.
     * This is a finite observation under cooperating-writer freeze, not an atomic
     * filesystem snapshot or a defence against malicious same-UID code.
     */
    fun unchanged() {
        files.current();check(terminalBarriersConfirmed)
        pointerSettled();pointer?.verify();rejectChildSidecars()
        require(child?.names(limits.graph.maximumJournals+limits.graph.maximumChunkDescriptors+1).orEmpty()==inventory)
        control?.requireControl();references.values.forEach {it.verify()}
        pointerSettled();rejectChildSidecars();files.current()
    }
    /** Only the family owner projects this after its real unchanged fence.
     * The claim records an observed barrier, not family/source/Frozen close.
     */
    internal fun inertInventoryMember(): ComputerFileFamilyMember {
        files.current();check(terminalBarriersConfirmed)
        val observed=originalGraph
        val names=linkedSetOf<String>()
        fun record(entry: NativeMigrationComputerFileGraph.Entry): ComputerFileFamilyRecord {
            val diskName=hash(entry.name)+".bin"
            check(names.add(diskName))
            val pin=references.getValue(diskName).ciphertextSha256()
            return ComputerFileFamilyRecord(ComputerFileMigrationEntry(entry.name,entry.size,entry.sha256),pin)
        }
        val journals=observed.journals.map(::record)
        val chunks=observed.chunks.map(::record)
        check(references.keys==names)
        val journalHashes=observed.journals.associate {it.name to it.sha256}
        val retired=observed.retiredChunks.map {entry->
            val reference=references.getValue(hash(entry.journalName)+".bin")
            check(terminalJournals[entry.journalName]===reference&&hash(entry.part.name)+".bin" !in references)
            ComputerFileFamilyRetiredChunk(entry.journalName,journalHashes.getValue(entry.journalName),
                entry.originalId,entry.index,entry.part,ComputerFileFamilyTerminalObservation.SOURCE_FILE_AND_DIRECTORIES_SYNCED)
        }
        val presence=when {
            child==null->ComputerFileFamilyChild.ABSENT
            journals.isEmpty()->ComputerFileFamilyChild.PRESENT_EMPTY
            else->ComputerFileFamilyChild.PRESENT_WITH_RECORDS
        }
        files.current()
        return ComputerFileFamilyMember(generation,presence,journals,chunks,retired)
    }

    internal fun inertPointerCiphertextPin(): String? {
        files.current()
        return pointer?.ciphertextSha256()
    }

    internal val ciphertextBytes: Long get(){files.current();return references.values.sumOf {it.size}+(pointer?.size?:0L)}
    fun ciphertextPins(): Map<String,String> {
        unchanged()
        return references.mapValues {it.value.ciphertextSha256()}+
            if(pointer==null)emptyMap()else mapOf("root-pointer" to pointer.ciphertextSha256())
    }
    companion object {
        internal fun <T> usingFamily(context: Context,root: File,namespace: String,keyAlias: String,generation: String?,
            owner: NativeMigrationOwnerLease,limits: NativeMigrationComputerFileFamilySnapshot.Limits,authority: ()->Unit,
            consume: (NativeMigrationComputerFileFamilySnapshot)->T): T {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
            check(root.path==owner.storage.coordinationKey)
            val frozen=owner.requireRawWriters()
            val files=NativeMigrationComputerFileSourceFiles {
                check(root.path==owner.storage.coordinationKey&&owner.requireRawWriters()===frozen)
                ComputerFileFdOwners.requireReleased();authority()
            }
            var failure: Throwable?=null
            try {
                val source=NativeMigrationComputerFileFamilySnapshot.capture(root,namespace,generation,limits,files) {selected->
                    NativeMigrationComputerFileSnapshot(context,root,namespace,keyAlias,generation,limits.member,files,selected,true)
                }
                return consume(source).also {source.unchanged()}
            }catch(error: Throwable){failure=error;throw error}
            finally {files.closeOwned(failure)}
        }
        private const val CONTROL="computer-files-writer-v1.lock"
        private const val MAX_CIPHERTEXT_RECORD=1_100_000
        private const val MAX_PARENT_ENTRIES=4096
        private fun hash(value: String)=ComputerFileCodec.hash(value.toByteArray(Charsets.UTF_8))
        internal fun <T> using(context: Context,root: File,namespace: String,keyAlias: String,generation: String?,
            owner: NativeMigrationOwnerLease,limits: Limits,authority: ()->Unit,consume: (NativeMigrationComputerFileSnapshot)->T): T {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
            check(root.path==owner.storage.coordinationKey)
            val frozen=owner.requireRawWriters()
            val files=NativeMigrationComputerFileSourceFiles {
                check(root.path==owner.storage.coordinationKey&&owner.requireRawWriters()===frozen)
                ComputerFileFdOwners.requireReleased();authority()
            }
            var failure: Throwable?=null
            try {
                val source=NativeMigrationComputerFileSnapshot(context,root,namespace,keyAlias,generation,limits,files)
                return consume(source).also {source.unchanged()}
            }catch(error: Throwable){failure=error;throw error}
            finally {files.closeOwned(failure)}
        }
    }
}
