package com.agenthub.android

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import org.json.JSONObject

/** Existing material only. Native codec/FD fences are reused; no mutable storage or AtomicFile read. */
internal class NativeMigrationExistingView private constructor(
    private val context: Context,private val base: File,val namespace: String,private val alias: String,
    private val lifetime: NativeMigrationReadLifetime,private val budget: Budget,requireKey: Boolean
): java.io.Closeable {
    private class Budget {
        private var directories=0;private var files=0;private var bytes=0L
        fun directory(){check(++directories<=128){"Existing namespace quota; originals retained"}}
        fun record(size: Long){files++;bytes+=size;require(files<=4096&&bytes<=NativeMigrationStreamRecords.MAX_TOTAL_BYTES){"Existing record quota; originals retained"}}
    }
    private class Directory(val folder: File) {
        private fun identity(): Pair<Long,Long> {
            require(!Files.isSymbolicLink(folder.toPath()))
            val s=Os.lstat(folder.path)
            require(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==Process.myUid()&&s.st_mode and 0x3f==0)
            return s.st_dev to s.st_ino
        }
        private val original=identity()
        private fun names()=requireNotNull(folder.listFiles()).map {it.name}.sorted().also {require(it.size<=1024)}
        val names=names()
        fun unchanged(){require(identity()==original&&names()==names)}
    }
    private val baseDirectory=Directory(base)
    private val root=File(base,namespace)
    private val directory=Directory(root).also {budget.directory()}
    private val generationNames=directory.names.filter {it.startsWith("migration-generation-")}.also {names->
        names.forEach {name->require(java.util.UUID.fromString(name.removePrefix("migration-generation-")).toString()==name.removePrefix("migration-generation-"))}
    }
    private val decoder=if(requireKey||directory.names.isNotEmpty()||java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias(alias))NativeSessionStorage.migrationDecoder(context,namespace,alias,lifetime)else null
    private val directories=mutableListOf(directory)
    private val references=linkedMapOf<String,NativeMigrationReadOnlyFile>()
    private val childViews=linkedMapOf<String,NativeMigrationExistingView>()
    private val controls=linkedMapOf<String,()->Unit>()
    private val rawReferences=linkedMapOf<String,NativeMigrationReadOnlyFile>()
    private var closed=false
    private val pointerName="migration-active-generation-v1"
    private var pointer: JSONObject?=null
    val generation: String?
    val original: NativeMigrationStreamRecords?
    private val content: NativeMigrationStreamRecords?
    val records: NativeMigrationStreamRecords get(){check(!closed);return requireNotNull(content){"Existing namespace has no records"}}
    private val generations=linkedMapOf<String,NativeMigrationStreamRecords?>()
    init {
        require(namespace.matches(Regex("[a-zA-Z0-9_-]{1,128}"))&&alias.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        val first=readDirectory(directory,true)
        original=first
        generation=pointer?.let {requireNotNull(decoder).existingMigrationGeneration(it.toString())}
        for(name in generationNames) {
            val dir=Directory(File(root,name));budget.directory();directories.add(dir)
            generations[name.removePrefix("migration-generation-")]=readDirectory(dir,false)
        }
        content=if(generation!=null)requireNotNull(generations[generation]){"Active generation is empty"}else original
        // Pointer counts describe the immutable publication, not later independent local edits.
        require((content?.size?:0)<=1024)
        unchanged()
    }
    private fun readDirectory(dir: Directory,allowPointer: Boolean): NativeMigrationStreamRecords? {
        val files=dir.names.filterNot {allowPointer&&it in generationNames}.associateWith {name->
            require(name.matches(Regex("[a-f0-9]{64}\\.bin")))
            NativeMigrationReadOnlyFile(dir.folder,File(dir.folder,name),23*1024*1024,lifetime).also {budget.record(it.identity.size);references[it.file.relativeTo(root).path]=it}
        }
        val texts=linkedMapOf<String,NativeMigrationBlob>();val binaries=linkedMapOf<String,NativeMigrationBlob>();val legacy=linkedMapOf<String,Int>()
        for((filename,ref)in files) {
            val version=ref.read(false){NativeMigrationLocalRecordFormat.read(it)}.value
            when(version) {
                3->{val (name,blob)=requireNotNull(decoder).existingMigrationTextFrame(ref);require(filename==hash(name)+".bin")
                    if(name==pointerName){require(allowPointer&&pointer==null);pointer=StrictMigrationJson.parse(requireNotNull(decoder).existingMigrationText(ref).second.toByteArray())}
                    else require(texts.put(name,blob)==null)}
                4->{val(name,blob)=requireNotNull(decoder).existingMigrationBinary(ref);require(filename==hash(name)+".bin"&&name!=pointerName);require(binaries.put(name,blob)==null)}
                1,2->legacy[filename]=version
                else->error("Unknown existing record format; originals retained")
            }
        }
        if(legacy.isNotEmpty()) {
            val read: (String)->String?={name->NativeMigrationTextMap(texts)[name]?:files[hash(name)+".bin"]?.takeIf {legacy[it.file.name]==1}?.let {requireNotNull(decoder).existingMigrationText(it,name).second}}
            val aliases=NativeMigrationExistingAliases.root(read).associateBy {hash(it)+".bin"}
            for((filename,version)in legacy) {
                val name=requireNotNull(aliases[filename]){"Unidentified legacy original retained"};val ref=files.getValue(filename)
                if(version==1)require(texts.put(name,requireNotNull(decoder).existingMigrationTextFrame(ref,name).second)==null)
                else {val(entry,blob)=requireNotNull(decoder).existingMigrationBinary(ref,name);require(entry==name&&binaries.put(name,blob)==null)}
            }
        }
        require((texts.keys intersect binaries.keys).isEmpty())
        return if(texts.isEmpty()&&binaries.isEmpty())null else NativeMigrationStreamRecords(NativeMigrationTextMap(texts),binaries,4)
    }
    /** All known original peer namespaces are opened before a mutable root can be admitted. */
    private fun family() {
        val specs=linkedMapOf<String,String>()
        val roots=listOf<String?>(null)+generations.keys
        for(gen in roots)for(suffix in listOf("migration-candidates","external-inbox","recent-photos","photo-permission","document-results","photo-results","camera-results")) {
            val name=namespace+(gen?.let {"-$it"}?:"")+"-$suffix"
            val child=childAlias(alias,suffix,gen);specs[name]=child
            if(suffix in listOf("document-results","photo-results","camera-results"))specs["$name-payloads"]=childAlias(child,"payloads",null)
            if(suffix=="camera-results")specs["$name-outputs"]=childAlias(child,"outputs",null)
        }
        val peers=requireNotNull(base.listFiles()).filter {it.name.startsWith("$namespace-")||it.name=="voice-recordings-writer-v1.lock"||it.name=="voice-recordings-v1"}
        for(file in peers) {
            val name=file.name
            if(name=="voice-recordings-v1") {
                val raw=Directory(file);budget.directory();controls[name]=raw::unchanged
                val datasets=listOfNotNull(original)+generations.values.filterNotNull()
                val known=datasets.flatMap {NativeMigrationProvisionalVoice.knownRawIds(it.text)}.toSet()
                require(raw.names.size<=128)
                for(filename in raw.names) {
                    require(filename.endsWith(".wav"));val id=filename.removeSuffix(".wav")
                    require(java.util.UUID.fromString(id).toString()==id&&id in known){"Unindexed raw original retained"}
                    val ref=NativeMigrationReadOnlyFile(file,File(file,filename),NativeMigrationProvisionalVoice.MAX_RAW_BYTES,lifetime,0)
                    budget.record(ref.identity.size)
                    ref.read {input->input.copyTo(nullOutputStreamCompat())}
                    rawReferences["$name/$filename"]=ref
                }
                continue
            }
            if(name=="voice-recordings-writer-v1.lock"||name.endsWith(".inbox-writer-v1.lock")) {
                if(name!="voice-recordings-writer-v1.lock")require(name.removeSuffix(".inbox-writer-v1.lock") in specs)
                controls[name]=control(file);continue
            }
            val key=requireNotNull(specs[name]){"Unknown existing namespace retained"}
            childViews[name]=NativeMigrationExistingView(context,base,name,key,lifetime,budget,false)
        }
    }
    private fun control(file: File): ()->Unit {
        fun stat(): List<Long> {require(!Files.isSymbolicLink(file.toPath()));val s=Os.lstat(file.path)
            require(OsConstants.S_ISREG(s.st_mode)&&s.st_uid==Process.myUid()&&s.st_nlink==1L&&s.st_size==0L&&s.st_mode and 0x3f==0)
            return listOf(s.st_dev,s.st_ino,s.st_size,s.st_mtime)}
        val original=stat()
        // Never open a cooperating lock FD: closing another FD can drop process record locks.
        require(stat()==original)
        return {require(stat()==original)}
    }
    fun unchanged() {check(!closed);baseDirectory.unchanged();directories.forEach {it.unchanged()};references.values.forEach {it.unchanged()};childViews.values.forEach {it.unchanged()};controls.values.forEach {it()};rawReferences.values.forEach {it.unchanged()}}
    fun ciphertext(): Map<String,String> {unchanged();return references.mapValues {(_,ref)->ref.read {input->input.copyTo(nullOutputStreamCompat())}.ciphertextSha256}}
    internal fun boundKeys(): Map<String,javax.crypto.SecretKey> {
        unchanged()
        val keys=linkedMapOf<String,javax.crypto.SecretKey>()
        decoder?.let {keys[alias]=it.existingMigrationKey()}
        childViews.values.forEach {view->keys.putAll(view.boundKeys())}
        return keys
    }
    fun namespaces(): List<String> {
        unchanged();return (listOf(namespace)+generationNames.map {"$namespace/$it"}+childViews.keys+controls.keys.filter {it=="voice-recordings-v1"}).sorted()
    }
    fun familyCiphertext(): Map<String,String> = ciphertext().mapKeys {"$namespace/${it.key}"}+
        childViews.flatMap {(name,view)->view.ciphertext().map {(path,sha)->"$name/$path" to sha}}.toMap()+
        rawReferences.mapValues {(_,ref)->ref.read {input->input.copyTo(nullOutputStreamCompat())}.ciphertextSha256}
    fun originalCandidateCiphertext(originGeneration: String?): Map<String,String> {
        unchanged();require(originGeneration==null||originGeneration in generations)
        val name=namespace+(originGeneration?.let {"-$it"}?:"")+"-migration-candidates"
        return requireNotNull(childViews[name]){"Original candidate namespace missing"}.ciphertext()
    }
    fun <T> using(block: (NativeMigrationExistingView)->T): T {
        var first: Throwable?=null
        try{return block(this)}catch(error: Throwable){first=error;throw error}
        finally{lifetime.closeOwned(first);markClosed()}
    }
    private fun markClosed(){closed=true;childViews.values.forEach {it.markClosed()}}
    fun originalCandidate(originGeneration: String?,receipt: NativeMigrationCandidateStore.Receipt,consume: (NativeMigrationStreamRecords)->Unit={}) {
        unchanged();require(originGeneration==null||originGeneration in generations)
        val name=namespace+(originGeneration?.let {"-$it"}?:"")+"-migration-candidates"
        val view=requireNotNull(childViews[name]){"Original candidate namespace missing"}
        NativeMigrationExistingCandidate.read(context.packageName,view.records,receipt,consume)
        unchanged()
    }
    fun published(receipt: NativeMigrationCandidateStore.Receipt): String {
        unchanged();val value=requireNotNull(pointer)
        require(value.getString("candidateId")==receipt.id&&value.getString("sha256")==receipt.sha256&&
            (if(value.getInt("version")==2)value.getInt("sourceRecords")else value.getInt("records"))==receipt.recordCount)
        return requireNotNull(generation)
    }
    fun writer(): NativeSessionStorage {unchanged();return requireNotNull(decoder).migrationExistingWriter(context,this)}
    override fun close() {
        if(closed)return
        lifetime.closeOwned()
        markClosed()
    }
    companion object {
        private fun hash(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
        internal fun childAlias(alias: String,suffix: String,generation: String?): String {
            val child="agent-hub-child-"+MessageDigest.getInstance("SHA-256").digest((alias+"\u0000"+suffix).toByteArray()).take(24).joinToString(""){"%02x".format(it)}
            return if(generation==null)child else "agent-hub-child-"+hash(child+"\u0000"+generation).take(48)
        }
        fun open(context: Context,base: File,namespace: String,keyAlias: String): NativeMigrationExistingView {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
            require(namespace.matches(Regex("[a-zA-Z0-9_-]{1,128}"))&&keyAlias.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
            val lifetime=NativeMigrationReadLifetime()
            try {
                // Validate the caller-provided existing path before any Context directory getter.
                Directory(base).unchanged()
                val bounded=object: android.content.ContextWrapper(context){
                    override fun getNoBackupFilesDir()=base
                    override fun getApplicationContext(): Context=this
                }
                val view=NativeMigrationExistingView(bounded,base,namespace,keyAlias,lifetime,Budget(),true)
                view.family();view.unchanged();return view
            }catch(error: Throwable){lifetime.closeOwned(error);throw error}
        }
    }
}
