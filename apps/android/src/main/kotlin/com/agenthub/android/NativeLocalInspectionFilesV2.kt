package com.agenthub.android

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/** Complete settled bytes for a fixed acceptance root. Decoding still uses Source51 ExistingReader. */
internal class NativeLocalInspectionFilesV2(private val root: File, private val expectedUid: Int) {
    private fun owned(path: java.nio.file.Path, permissions: String) {
        require((Files.getAttribute(path,"unix:uid",NOFOLLOW_LINKS) as Number).toInt()==expectedUid)
        require(Files.getPosixFilePermissions(path,NOFOLLOW_LINKS)==PosixFilePermissions.fromString(permissions))
    }
    private val original=capture()
    private fun capture(): Map<String,ByteArray> {
        val path=root.toPath();require(Files.isDirectory(path,NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path));owned(path,"rwx------")
        val rootBefore=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        val result=linkedMapOf<String,ByteArray>();var total=0L
        Files.newDirectoryStream(path).use { stream->for(p in stream){
            require(result.size<16&&p.fileName.toString().matches(Regex("[0-9a-f]{64}\\.bin"))) {"Unknown, pending or nested original preserved"}
            require(Files.isRegularFile(p,NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p)&&(Files.getAttribute(p,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1);owned(p,"rw-------")
            val before=Files.readAttributes(p,BasicFileAttributes::class.java,NOFOLLOW_LINKS);require(before.size() in 29..1048576);total+=before.size();require(total<=4194304)
            val bytes=FileChannel.open(p,READ,NOFOLLOW_LINKS).use {c->require(c.size()==before.size());val b=ByteBuffer.allocate(before.size().toInt());while(b.hasRemaining())check(c.read(b)>0);require(c.read(ByteBuffer.allocate(1))==-1);b.array()}
            val after=Files.readAttributes(p,BasicFileAttributes::class.java,NOFOLLOW_LINKS);require(before.fileKey()==after.fileKey()&&before.size()==after.size()&&before.lastModifiedTime()==after.lastModifiedTime());owned(p,"rw-------")
            require((Files.getAttribute(p,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1)
            result[p.fileName.toString()]=bytes
        }}
        val rootAfter=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS);require(rootBefore.fileKey()==rootAfter.fileKey()&&rootBefore.lastModifiedTime()==rootAfter.lastModifiedTime());owned(path,"rwx------")
        require(result.isNotEmpty());return result
    }
    fun requireOriginal(filename: String,bytes: ByteArray) {require(filename.matches(Regex("[a-f0-9]{64}\\.bin")));require(original[filename]?.contentEquals(bytes)==true)}
    fun requireAbsent(filename: String) {require(filename.matches(Regex("[a-f0-9]{64}\\.bin")));require(filename !in original)}
    fun assertUnchanged(){val now=capture();require(now.keys==original.keys&&now.all{(k,v)->v.contentEquals(original.getValue(k))})}
}
