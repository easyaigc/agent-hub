package com.agenthub.android

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Source-only, exact UUID fixture cleanup. No production namespace/key lookup, repair or retry.
 * Passive identity/mount revalidation; it does not claim atomic defense against hostile same-UID swaps. */
internal class P8MainScopeBoundary(private val actual: Context,private val context: P8MainFilesContext) {
    companion object {const val ENTRIES=128;const val DEPTH=8;const val BYTES=1024L*1024;const val READ_HANDLES=8192}
    private data class Identity(val dev: Long,val inode: Long,val uid: Int,val mode: Int,
        val links: Long=-1,val size: Long=-1,val modified: Long=-1,val changed: Long=-1)
    private data class Node(val file: File,val relative: String,val depth: Int,val identity: Identity,val directory: Boolean,val digest: String?)
    private class ReadOwner(val label: String) {
        var resource: Any?=null;var opened=false;var closeAttempted=false;var closed=false
        var failure: Throwable?=null
    }
    // Exact returned raw FDs/DirectoryStreams stay here after an unconfirmed close. Never close twice.
    private val reads=mutableListOf<ReadOwner>()
    private val ancestors=linkedMapOf<File,Identity>()
    private lateinit var parent: File;private lateinit var root: File;private lateinit var appData: File
    private lateinit var rootIdentity: Identity;private lateinit var markerIdentity: Identity;private lateinit var mountsAtPin: String
    private var pinAttempted=false;private var pinned=false;private var removalAttempted=false;private var markerRemoved=false
    /** Pure retained-record metadata only: no close, stat, retry or ownership promotion. */
    fun retentionSummary(): Map<String,Any> {
        val unknown=reads.withIndex().filterNot {it.value.opened&&it.value.closeAttempted&&it.value.closed}
        return mapOf("pinAttempted" to pinAttempted,"pinned" to pinned,"removalAttempted" to removalAttempted,
            "markerRemovalConfirmed" to markerRemoved,"readResources" to reads.size,"unconfirmedReadResources" to unknown.size,
            "retainedReadRecords" to unknown.take(16).map {row->mapOf("index" to row.index,"label" to row.value.label,
                "opened" to row.value.opened,"closeAttempted" to row.value.closeAttempted,"closeConfirmed" to row.value.closed,
                "resourceIdentity" to (row.value.resource?.let {System.identityHashCode(it)}?:-1))})
    }
    private fun identity(stat: android.system.StructStat,regular: Boolean=false)=Identity(stat.st_dev,stat.st_ino,stat.st_uid,stat.st_mode,
        if(regular)stat.st_nlink else -1,if(regular)stat.st_size else -1,if(regular)stat.st_mtime else -1,if(regular)stat.st_ctime else -1)
    private fun same(stat: android.system.StructStat,expected: Identity)=identity(stat,expected.size>=0)==expected
    private fun privateDirectory(file: File,fixture: Boolean): Identity {
        val stat=Os.lstat(file.path)
        check(OsConstants.S_ISDIR(stat.st_mode)&&!Files.isSymbolicLink(file.toPath())&&file.canonicalFile==file)
        val owned=file==appData||file==parent||fixture
        if(owned) {
            check(stat.st_uid==Process.myUid())
            val mode=stat.st_mode and 0x1ff
            // Only the actual framework app-data/no_backup parents permit 0700/0771; every fixture directory is 0700.
            check(if(fixture)mode==0x1c0 else mode in setOf(0x1c0,0x1f9))
        }
        val expected=identity(stat)
        // App UID may traverse system ancestors such as /data without permission to open/read them.
        // Follow the repository pattern: no-follow handles for owned ancestors; lstat identity for system ancestors.
        // Public SDK flags plus exact fstat directory/identity verification before any use.
        if(owned)fd(file,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW){opened->val stat=Os.fstat(opened);check(OsConstants.S_ISDIR(stat.st_mode)&&same(stat,expected))}
        check(identity(Os.lstat(file.path))==expected)
        return expected
    }
    private fun <R: Any,T> using(label: String,open: ()->R,body: (R)->T,close: (R)->Unit): T {
        check(reads.size<READ_HANDLES)
        val owner=ReadOwner(label);reads.add(owner) // Register acquisition before the call, even if it never returns.
        val resource=try {open().also {owner.resource=it;owner.opened=true}}catch(error: Throwable){owner.failure=error;throw error}
        var first: Throwable?=null
        try {return body(resource)}catch(error: Throwable){first=error;owner.failure=error;throw error}
        finally {
            owner.closeAttempted=true
            try {close(resource);owner.closed=true}catch(error: Throwable){
                owner.failure=first?:error
                if(first==null)throw error
                if(first!==error)first.addSuppressed(error)
            }
        }
    }
    private fun <T> fd(file: File,flags: Int,body: (FileDescriptor)->T)=using(file.path,{Os.open(file.path,flags,0)},body,{Os.close(it)})
    private fun regular(file: File): Identity {
        val stat=Os.lstat(file.path)
        check(OsConstants.S_ISREG(stat.st_mode)&&stat.st_uid==Process.myUid()&&stat.st_nlink==1L&&stat.st_mode and 0x3f==0)
        check(stat.st_size in 0..BYTES&&stat.st_dev==rootIdentity.dev&&!Files.isSymbolicLink(file.toPath()))
        return identity(stat,true)
    }
    private fun readRegular(file: File,expected: Identity,consume: (ByteArray,Int)->Unit) {
        check(regular(file)==expected)
        fd(file,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW) {opened->
            check(same(Os.fstat(opened),expected));val bytes=ByteArray(4096);var count=0L
            while(count<expected.size){
                val requested=minOf(bytes.size.toLong(),expected.size-count).toInt()
                val size=Os.read(opened,bytes,0,requested);check(size>0&&size<=expected.size-count);count+=size;consume(bytes,size)
            }
            check(count==expected.size&&same(Os.fstat(opened),expected)&&regular(file)==expected)
        }
    }
    private fun marker() {
        val file=File(root,"owner");check(regular(file)==markerIdentity&&markerIdentity.size==36L&&markerIdentity.mode and 0x1ff==0x180)
        val output=java.io.ByteArrayOutputStream(36)
        readRegular(file,markerIdentity){bytes,size->output.write(bytes,0,size)}
        check(output.toByteArray().toString(Charsets.UTF_8)==context.run)
    }
    private fun decodeMountPoint(value: String): String=buildString {
        var position=0
        while(position<value.length) {
            if(value[position]!='\\'){append(value[position++]);continue}
            check(position+3<value.length)
            append(when(value.substring(position+1,position+4)){"040"->' ';"011"->'\t';"012"->'\n';"134"->'\\';else->error("Unsupported mount escape")});position+=4
        }
    }
    private fun under(path: String,base: String)=path==base||path.startsWith(base.trimEnd('/')+"/")
    private fun mounts(): String {
        val output=java.io.ByteArrayOutputStream()
        fd(File("/proc/self/mountinfo"),OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW){opened->
            val bytes=ByteArray(4096);var count=0
            while(true){
                check(count<BYTES) // At the cap with no observed EOF, refuse rather than read beyond the budget.
                val requested=minOf(bytes.size.toLong(),BYTES-count).toInt()
                val size=Os.read(opened,bytes,0,requested);if(size==0)break;check(size>0&&size<=BYTES-count);count+=size;output.write(bytes,0,size)
            }
        }
        val result=mutableListOf<String>();var lines=0
        for(line in output.toByteArray().toString(Charsets.UTF_8).lineSequence()) {
            if(line.isEmpty())continue
            check(++lines<=4096&&line.length<=8192)
            val fields=line.split(' ');check(fields.size>=10)
            val point=decodeMountPoint(fields[4]);check(point.startsWith('/'))
            // Reject even a same-device bind mount at/below either spelling of the fresh scope.
            check(!under(point,root.path)&&!under(point,context.root.absolutePath)) {"Mount intersects owned scope"}
            if(point=="/"||under(root.path,point)||under(context.root.absolutePath,point))result.add(line)
        }
        check(result.isNotEmpty())
        return result.sorted().joinToString("\n")
    }
    fun pin() {
        check(!pinAttempted);pinAttempted=true
        check(actual.packageName=="com.easyai.agenthub.android.acceptance"&&actual.applicationInfo.uid==Process.myUid())
        parent=actual.noBackupFilesDir.canonicalFile;appData=File(actual.applicationInfo.dataDir).canonicalFile
        check(parent==File(appData,"no_backup")&&context.root.name=="p8-main-preference-${context.run}"&&context.root.parentFile.canonicalFile==parent)
        root=File(parent,context.root.name);check(context.root.canonicalFile==root)
        var current=File("/");ancestors[current]=privateDirectory(current,false)
        for(part in parent.path.split('/').filter {it.isNotEmpty()}) {
            check(ancestors.size<16&&part!="."&&part!="..");current=File(current,part);ancestors[current]=privateDirectory(current,false)
        }
        check(current==parent)
        rootIdentity=privateDirectory(root,true);check(rootIdentity.dev==ancestors.getValue(parent).dev)
        markerIdentity=regular(File(root,"owner"));marker();mountsAtPin=mounts()
        check(reads.all {it.opened&&it.closeAttempted&&it.closed})
        pinned=true
    }
    private fun guard(markerMayBeMissing: Boolean=false) {
        check(pinned&&actual.noBackupFilesDir.canonicalFile==parent&&context.root.canonicalFile==root)
        for((file,expected) in ancestors)check(privateDirectory(file,false)==expected)
        check(privateDirectory(root,true)==rootIdentity&&mounts()==mountsAtPin)
        if(markerMayBeMissing){check(markerRemoved&&Files.notExists(File(root,"owner").toPath(),NOFOLLOW_LINKS))}else marker()
    }
    private fun allowed(relative: String,directory: Boolean): Boolean=if(directory)
        relative in setOf("","no-backup","files","cache","no-backup/session","cache/migration-ui-v1")
        else relative=="owner"||relative.matches(Regex("no-backup/session/[a-f0-9]{64}\\.bin"))
    private fun inventory(): List<Node> {
        val nodes=mutableListOf<Node>();var total=0L
        fun visit(file: File,relative: String,depth: Int) {
            // Limits are checked before accumulation and before any descent, not after a walk/toList.
            check(depth<=DEPTH&&nodes.size<ENTRIES);guard()
            val stat=Os.lstat(file.path);val directory=OsConstants.S_ISDIR(stat.st_mode)
            check(allowed(relative,directory)&&stat.st_uid==Process.myUid()&&stat.st_dev==rootIdentity.dev&&!Files.isSymbolicLink(file.toPath()))
            val expected: Identity;var digest: String?=null
            if(directory){check(stat.st_mode and 0x1ff==0x1c0);expected=identity(stat)}else {
                expected=regular(file);check(expected.size<=BYTES-total);total+=expected.size
                val hash=MessageDigest.getInstance("SHA-256");readRegular(file,expected){bytes,size->hash.update(bytes,0,size)}
                digest=hash.digest().joinToString(""){"%02x".format(it)}
            }
            nodes.add(Node(file,relative,depth,expected,directory,digest))
            if(directory)fd(file,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW){opened->
                check(same(Os.fstat(opened),expected))
                using<DirectoryStream<Path>,Unit>(file.path+":entries",{Files.newDirectoryStream(file.toPath())},{stream->
                    val iterator=stream.iterator()
                    while(iterator.hasNext()) {
                        check(nodes.size<ENTRIES&&depth<DEPTH)
                        val child=iterator.next().toFile();val name=child.name
                        check(name.isNotEmpty()&&name!="."&&name!=".."&&!name.contains('/')&&child.parentFile==file)
                        visit(child,if(relative.isEmpty())name else "$relative/$name",depth+1)
                    }
                },{it.close()})
                check(same(Os.fstat(opened),expected)&&identity(Os.lstat(file.path))==expected)
            }
        }
        visit(root,"",0);guard();check(reads.all {it.opened&&it.closeAttempted&&it.closed})
        return nodes.sortedBy {it.relative}
    }
    fun removeConfirmedOwnedScope(beforeDelete: (Map<String,Any>)->Unit) {
        check(pinned&&!removalAttempted);removalAttempted=true
        val original=inventory();val confirmation=inventory();check(original==confirmation)
        beforeDelete(mapOf("entries" to original.size,"bytes" to original.filterNot {it.directory}.sumOf {it.identity.size},
            "depth" to (original.maxOfOrNull {it.depth}?:0),"rootIdentity" to rootIdentity.toString(),
            "readHandles" to reads.size,"allReadClosesConfirmed" to reads.all {it.opened&&it.closeAttempted&&it.closed}))
        guard();check(reads.all {it.opened&&it.closeAttempted&&it.closed})
        fun delete(node: Node) {
            guard();check(if(node.directory)identity(Os.lstat(node.file.path))==node.identity else regular(node.file)==node.identity)
            if(!node.directory){val hash=MessageDigest.getInstance("SHA-256");readRegular(node.file,node.identity){bytes,size->hash.update(bytes,0,size)};check(hash.digest().joinToString(""){"%02x".format(it)}==node.digest)}
            check(reads.all {it.opened&&it.closeAttempted&&it.closed});Files.delete(node.file.toPath())
        }
        // Never recurse during deletion. New children cause rmdir failure rather than being followed/deleted.
        original.filter {it.relative!=""&&it.relative!="owner"}.sortedByDescending {it.depth}.forEach(::delete)
        delete(original.single {it.relative=="owner"});markerRemoved=true
        guard(markerMayBeMissing=true);check(reads.all {it.opened&&it.closeAttempted&&it.closed})
        Files.delete(root.toPath());check(Files.notExists(root.toPath(),NOFOLLOW_LINKS))
    }
}
