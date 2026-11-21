package com.agenthub.android

import android.content.Context
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKey

/** One closed task-owned Q04 installation only. No default storage, pairing, key factory or IPC. */
internal class Q04ExistingOwnedProfile private constructor(
    val ownerRun: String, val root: File, private val manifest: JSONObject,
    private val lockFile: ExistingLockFile, private val lock: FileLock,
    private val protectedFiles: Map<String,String>,private val originalFiles: Map<String,String>,
    private val readingPermit: Q04ExistingOwnedReadingPermit?
) {
    val alias="q02-real-$ownerRun"
    private var released=false
    internal val protectedRecords=linkedMapOf<String,String>()
    internal val mutableRecords=manifest.getJSONArray("mutableRecords").strings().toSet()+
        if(readingPermit!=null)setOf(NativeReadingLedger.STORAGE_NAME)else emptySet()
    internal lateinit var original: SavedOperation; private set
    internal lateinit var client: MobileClient; private set
    private val newInputs=linkedMapOf<String,String>()
    private var journalRows=emptyMap<String,SavedOperation>()
    private var readingScope: NativeExistingOwnedReadingScope?=null
    private var actualSession: MobileSession?=null
    @Volatile private var replyBoundary: ((String)->Unit)?=null
    @Volatile private var control: Pair<String,()->Unit>?=null

    /** Owns one existing descriptor; opening/closing another fd can drop POSIX locks. */
    private class ExistingLockFile private constructor(
        val fd: FileDescriptor, private val stream: FileOutputStream, private val path: String
    ): Closeable {
        val channel get()=stream.channel
        private var closed=false
        @Synchronized override fun close() {
            if(closed)return
            closed=true
            try {stream.close()} finally {
                try {Os.close(fd)} finally {synchronized(openPaths){openPaths.remove(path)}}
            }
        }
        companion object {
            private val openPaths=hashSetOf<String>()
            fun open(file: File): ExistingLockFile {
                val path=file.path
                synchronized(openPaths) {
                    if(!openPaths.add(path))throw OverlappingFileLockException()
                }
                var descriptor: FileDescriptor?=null
                try {
                    // Never CREATE/TRUNCATE and never follow the lock leaf.
                    val fd=Os.open(path,OsConstants.O_RDWR or OsConstants.O_NOFOLLOW,0)
                    descriptor=fd
                    // Android's FileDescriptor constructor does not own or open the fd.
                    return ExistingLockFile(fd,FileOutputStream(fd),path)
                }catch(failure: Throwable) {
                    if(descriptor!=null)try {Os.close(descriptor)}catch(closeFailure: Throwable){failure.addSuppressed(closeFailure)}
                    synchronized(openPaths){openPaths.remove(path)}
                    throw failure
                }
            }
        }
    }

    companion object {
        internal val protectedNames=setOf("easywork-device-identity","codex-private-connections-v1","q02-new-binding-attempt-v1")
        private val dirs=setOf("", "no-backup","no-backup/q02-session","files","cache","cache/migration-ui-v1","code-cache")
        internal fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        internal fun sha(text: String)=sha(text.toByteArray(Charsets.UTF_8))
        private fun uuid(value: String)=value.also {require(UUID.fromString(it).toString()==it)}
        private fun hex(value: String)=value.also {require(it.matches(Regex("[a-f0-9]{64}")))}
        internal fun validateRemainingReply(row: SavedOperation,slots: JSONObject,host: String,thread: String,controlTurn: String?): String {
            require(row.kind=="reply"&&sha(row.intent)==row.intentSha256)
            val body=JSONObject(row.intent)
            require(body.getString("hostId")==host&&body.getString("threadId")==thread&&body.getString("clientMessageId")==row.id&&body.getJSONArray("attachmentIds").length()==0)
            val which=listOf("stopOrigin","queueCancel","queueExecute","steer").single {slots.getJSONObject(it).getString("textSha256")==sha(body.getString("text"))}
            require(body.getString("mode")==mapOf("stopOrigin" to "next-turn","queueCancel" to "queue","queueExecute" to "queue","steer" to "steer").getValue(which))
            if(which=="stopOrigin")require(!body.has("expectedTurnId"))
            else require(controlTurn!=null&&body.getString("expectedTurnId")==controlTurn)
            if(which=="steer")require(listOf("model","effort","permissionMode","speed","serviceTier").none(body::has))
            return which
        }
        internal fun beforeNewReplyWrite(row: SavedOperation,prior: SavedOperation?,slot: String,verify: (String)->Unit) {
            if(prior==null||row.phase=="submitting"&&prior.phase!="submitting")verify(slot)
        }
        private fun directory(file: File,device: Long?=null) {
            require(file.absoluteFile==file.canonicalFile&&!Files.isSymbolicLink(file.toPath()))
            val s=Os.lstat(file.path)
            require(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==android.os.Process.myUid()&&s.st_mode and 0x1ff==0x1c0)
            if(device!=null)require(s.st_dev==device)
        }
        private fun bytes(file: File,device: Long): ByteArray {
            val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0)
            return try {
                val a=Os.fstat(fd)
                require(OsConstants.S_ISREG(a.st_mode)&&a.st_dev==device&&a.st_uid==android.os.Process.myUid()&&
                    a.st_nlink==1L&&a.st_mode and 0x1ff==0x180&&a.st_size in 1..1_048_576)
                val value=ByteArray(a.st_size.toInt());var offset=0
                while(offset<value.size){val n=Os.read(fd,value,offset,value.size-offset);require(n>0);offset+=n}
                require(Os.read(fd,ByteArray(1),0,1)==0)
                val b=Os.fstat(fd);val p=Os.lstat(file.path)
                require(a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&a.st_mtime==b.st_mtime&&
                    p.st_dev==a.st_dev&&p.st_ino==a.st_ino&&p.st_nlink==1L&&p.st_size==a.st_size)
                value
            } finally {Os.close(fd)}
        }
        private fun inventory(root: File): Map<String,String> {
            directory(root);val device=Os.lstat(root.path).st_dev;val result=linkedMapOf<String,String>();var total=0L
            fun scan(relative: String) {
                val folder=if(relative.isEmpty())root else File(root,relative);directory(folder,device)
                Files.newDirectoryStream(folder.toPath()).use {stream->for(p in stream) {
                    val name=listOf(relative,p.fileName.toString()).filter {it.isNotEmpty()}.joinToString("/")
                    if(name in dirs)scan(name) else {
                        require(name.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin"))) {"Unknown profile file, child, generation or backup retained"}
                        require(result.size<32);val value=bytes(p.toFile(),device)
                        total+=value.size;require(total<=8_388_608);result[name]=sha(value);value.fill(0)
                    }
                }}
            }
            scan("");require(result.isNotEmpty());return result
        }
        /** Root prepares this fixed once-scope lock file, outside the original profile. Never creates it here. */
        internal fun admit(context: Context,permit: Q02RealReadInstallation.Permit,m: JSONObject,
            readingPermit: Q04ExistingOwnedReadingPermit?=null): Q04ExistingOwnedProfile {
            readingPermit?.validateForAdmission(permit,m) // Before opening any profile lock or writer.
            require(m.getString("kind")=="existing-owned-q04-remaining-v1"&&m.opt("rootContinuationAuthorized")==true)
            require(m.getInt("uid")==android.os.Process.myUid())
            require(m.getString("continuationRun")==permit.run&&m.getString("leaseId")==permit.leaseId)
            val owner=uuid(m.getString("storageOwnerRun"));require(owner!=permit.run)
            for(k in listOf("originalClaimSha256","rootDecisionSha256","originalProfileProofSha256","originalOriginBodySha256"))hex(m.getString(k))
            require(m.opt("originalProfileClosed")==true&&m.opt("originalProfileDestroyed")==true&&m.opt("oldWriterStopped")==true)
            require(m.opt("newBindingAuthorized")==false&&m.opt("originReplayAllowed")==false&&m.getInt("hostBindings")==12)
            require(sameScope(HostReadCodec.target(m.getJSONObject("target")),permit.target)&&permit.target.agentId=="codex")
            require(m.getString("endpoint")==permit.endpoint&&m.getString("certificateSha256")==permit.certificateSha256)
            require(m.getString("grantPolicy")=="use-original-no-refresh-no-repair")
            // Normalize only Android's trusted Context root; child symlinks still fail directory().
            val filesRoot=context.filesDir.canonicalFile
            val parent=File(filesRoot,"q02-real-dev-profiles-v1");directory(parent)
            val root=File(parent,owner);directory(root,Os.lstat(parent.path).st_dev)
            val lockDir=File(filesRoot,"acceptance/q04-remaining-v1/$owner")
            directory(File(filesRoot,"acceptance"));directory(File(filesRoot,"acceptance/q04-remaining-v1"));directory(lockDir)
            val lf=File(lockDir,"writer.lock");val ls=Os.lstat(lf.path)
            require(OsConstants.S_ISREG(ls.st_mode)&&ls.st_uid==android.os.Process.myUid()&&ls.st_nlink==1L&&ls.st_mode and 0x1ff==0x180&&ls.st_size==0L)
            val handle=ExistingLockFile.open(lf)
            try {
                val opened=Os.fstat(handle.fd)
                val current=Os.lstat(lf.path)
                require(opened.st_dev==ls.st_dev&&opened.st_ino==ls.st_ino&&current.st_ino==ls.st_ino&&current.st_dev==ls.st_dev&&
                    opened.st_dev==Os.lstat(root.path).st_dev&&opened.st_nlink==1L&&current.st_nlink==1L&&
                    OsConstants.S_ISREG(opened.st_mode)&&OsConstants.S_ISREG(current.st_mode)&&
                    opened.st_uid==android.os.Process.myUid()&&current.st_uid==opened.st_uid&&
                    opened.st_mode and 0x1ff==0x180&&current.st_mode and 0x1ff==0x180&&
                    opened.st_size==0L&&current.st_size==0L&&!Files.isSymbolicLink(lf.toPath()))
                val lock=checkNotNull(handle.channel.tryLock()) {"Owned profile already has a writer"}
                try {
                    val expected=m.getJSONArray("inventory").objects().associate {row->
                        val name=row.getString("path");name to hex(row.getString("sha256"))
                    }
                    require(expected.size==m.getJSONArray("inventory").length()&&expected.size==11)
                    require(inventory(root)==expected)
                    val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
                    val key=keys.getKey("q02-real-$owner",null)
                    require(key is SecretKey&&key.encoded==null) {"Existing non-exportable key required; no fallback"}
                    val protected=protectedNames.associate {name->
                        val filename="no-backup/q02-session/"+sha(name)+".bin"
                        filename to checkNotNull(expected[filename])
                    }
                    return Q04ExistingOwnedProfile(owner,root,JSONObject(m.toString()),handle,lock,protected,expected,readingPermit)
                }catch(error: Throwable){lock.release();throw error}
            }catch(error: Throwable){handle.close();throw error}
        }
    }
    /** Decode only the admitted original namespace; never EasyWorkIdentity.load or Session.restore. */
    internal fun inspect(context: Context) {
        require(!released&&lock.isValid);require(inventory(root)==originalFiles)
        val read=NativeSessionStorage(context,"q02-session",alias,existingKeyOnly=true,existingNamespaceOnly=true,existingReadOnly=true)
        try {
            for(name in protectedNames)protectedRecords[name]=checkNotNull(read.read(name))
            val identity=JSONObject(protectedRecords.getValue("easywork-device-identity"))
            val public=easyBytes(identity.getString("publicKey"));val private=easyBytes(identity.getString("privateKey"))
            try {
                require(identity.getString("deviceId")==manifest.getString("mobileDeviceId"))
                require(sha(public)==manifest.getString("mobilePublicKeySha256"))
                require(org.bouncycastle.crypto.params.X25519PrivateKeyParameters(private,0).generatePublicKey().encoded.contentEquals(public))
            }finally {private.fill(0);public.fill(0)}
            val clients=CodexConnectionVault(read).clients();client=clients.single()
            require(client.id==manifest.getJSONObject("target").getString("hostId")&&sameScope(client.scope,HostReadCodec.target(manifest.getJSONObject("target"))))
            require(client.endpoint==manifest.getString("endpoint")&&client.codexDirect?.fingerprint==manifest.getString("certificateSha256"))
            require(CodexConnectionVault(read).token(client.id)!=null)
            val binding=JSONObject(protectedRecords.getValue("q02-new-binding-attempt-v1"))
            require(binding.getString("mobileDeviceId")==identity.getString("deviceId")&&binding.getString("mobilePublicKeySha256")==manifest.getString("mobilePublicKeySha256"))
            require(sameScope(HostReadCodec.target(binding.getJSONObject("target")),client.scope))
            val key=client.cacheKey(manifest.getString("threadId"))
            val journal=JSONObject(checkNotNull(read.read("operations:$key")))
            original=NativeOperationJournal.validateExistingMigrationRecords(key,journal).single()
            require(original.kind=="reply"&&original.id==manifest.getString("originalOperationId")&&original.submissionAttempted&&original.phase=="completed")
            val intent=JSONObject(original.intent)
            require(intent.getString("threadId")==manifest.getString("threadId")&&sha(intent.getString("text"))==manifest.getString("originalOriginBodySha256"))
            journalRows=mapOf(original.id to original)
            val allowed=setOf("metadata","operations:$key","history:$key","history:$key:parts","ai-sharing-v1","submission:q04-remaining",NativeReadingLedger.STORAGE_NAME)
            require(mutableRecords.isNotEmpty()&&mutableRecords.all {it in allowed}&&"metadata" in mutableRecords&&"operations:$key" in mutableRecords)
            require("submission:q04-remaining" in mutableRecords)
            if(readingPermit!=null) {
                val payload=checkNotNull(read.read(NativeReadingLedger.STORAGE_NAME)) {"Certified reading ledger is absent"}
                val cipher=bytes(File(root,readingPermit.cipherPath),Os.lstat(root.path).st_dev)
                try {readingScope=readingPermit.inspectBaseline(cipher,payload)} finally {cipher.fill(0)}
            } else if(NativeReadingLedger.STORAGE_NAME in mutableRecords)readingScope=NativeExistingOwnedReadingScope(
                checkNotNull(client.scope),manifest.getString("threadId"),read.read(NativeReadingLedger.STORAGE_NAME))
            assertProtected()
            require(inventory(root)==originalFiles)
        }finally {read.retireWriter()}
    }
    private fun validateWrite(name: String,text: String) {
        val key=client.cacheKey(manifest.getString("threadId"))
        val slots=manifest.getJSONObject("slots")
        fun slot(body: String): String {
            val names=listOf("stopOrigin","queueCancel","queueExecute","steer").filter {slots.getJSONObject(it).getString("textSha256")==sha(body)}
            return names.single()
        }
        if(name.startsWith("submission:")) {
            val body=JSONObject(text);val which=slot(body.getString("text"));val id=name.removePrefix("submission:")
            require(body.getString("hostId")==client.id&&sameScope(HostReadCodec.target(body.getJSONObject("target")),client.scope)&&body.getJSONArray("files").length()==0)
            require(newInputs[which]?.let {it==id}!=false&&newInputs.values.none {it==id&&newInputs[which]!=id})
            if(which !in newInputs)require(newInputs.keys.toList()+which==listOf("stopOrigin","queueCancel","queueExecute","steer").take(newInputs.size+1))
            require(id!=original.id);newInputs[which]=id
        } else if(name=="operations:$key") {
            val rows=NativeOperationJournal.validateExistingMigrationRecords(key,JSONObject(text))
            require(rows.size in 1..5&&rows.all {it.kind=="reply"})
            val old=rows.single {it.id==original.id};require(old.intent==original.intent&&old.intentSha256==original.intentSha256)
            for(row in rows.filter {it.id!=original.id}) {
                val which=validateRemainingReply(row,slots,client.id,manifest.getString("threadId"),control?.first)
                require(newInputs[which]==row.id)
                val prior=journalRows[row.id]
                require(prior==null||prior.intent==row.intent&&prior.intentSha256==row.intentSha256)
                beforeNewReplyWrite(row,prior,which,checkNotNull(replyBoundary) {"No original reply clock boundary"})
                if(which=="stopOrigin"&&(prior==null||row.phase=="submitting"&&prior.phase!="submitting")) {
                    val session=checkNotNull(actualSession)
                    require(!session.running&&session.runtime?.turnId==null&&session.selectedThread==manifest.getString("threadId"))
                }
                if(which!="stopOrigin") {
                    val binding=checkNotNull(control) {"No independent control-Turn proof"}
                    if(prior==null||row.phase=="submitting"&&prior.phase!="submitting")binding.second()
                }
            }
            require(journalRows.keys.all {id->rows.any {it.id==id}})
            journalRows=rows.associateBy {it.id}
        } else if(name=="metadata") {
            val body=JSONObject(text);val clients=body.getJSONArray("clients").objects().map(::decodeClient)
            require(clients.size==1&&clients.single().id==client.id&&sameScope(clients.single().scope,client.scope))
            val draftFiles=body.getJSONObject("draftFiles")
            require(draftFiles.keys().asSequence().all {draftFiles.getJSONArray(it).length()==0})
        }
    }
    internal fun bindSession(session: MobileSession) {require(actualSession==null);actualSession=session}
    internal fun bindReplyBoundary(verify: (String)->Unit) {
        require(actualSession!=null&&replyBoundary==null&&newInputs.isEmpty())
        replyBoundary=verify
    }
    internal fun bindControlTurn(turn: String,live: ()->Unit) {
        require(control==null&&UUID.fromString(turn).toString()==turn&&turn!=manifest.getString("originalTurnId"))
        live();control=turn to live
    }
    internal fun openWriter(context: Context): NativeSessionStorage {
        require(::original.isInitialized&&::client.isInitialized);assertProtected()
        val policy=NativeSessionStorage.ExistingOwnedPolicy(protectedRecords,mutableRecords,client.cacheKey(manifest.getString("threadId")),::assertProtected,::validateWrite,readingScope)
        return NativeSessionStorage.existingQ04Owned(context,ownerRun,policy)
    }
    /** Run only inside the future exact lease. These are production calls, not a mirror policy. */
    internal fun verifyWriteFences(storage: NativeSessionStorage,context: Context) {
        for((name,value)in protectedRecords) {
            storage.write(name,value);assertProtected() // Same contents must not re-encrypt/rewrite the ciphertext.
            require(runCatching {storage.write(name,value+" ")}.isFailure)
            require(runCatching {storage.remove(name)}.isFailure)
        }
        require(runCatching {storage.write("q04-unapproved-record","{}")}.isFailure)
        require(runCatching {storage.child(context,"q04-unapproved-child")}.isFailure)
        require(runCatching {storage.writeBinaryBytes("q04-unapproved-binary",byteArrayOf(1),1)}.isFailure)
        require(runCatching {storage.writeBinaryStream("q04-unapproved-binary",1){it.write(1)}}.isFailure)
        require(runCatching {storage.reopen(context)}.isFailure)
        assertProtected()
        require(inventory(root)==originalFiles)
    }
    internal fun assertProtected() {
        require(!released&&lock.isValid)
        directory(root);val device=Os.lstat(root.path).st_dev
        directory(File(root,"no-backup"),device);directory(File(root,"no-backup/q02-session"),device)
        for((name,hash)in protectedFiles) {
            val value=bytes(File(root,name),device)
            try {require(sha(value)==hash) {"Original identity/grant/binding record changed"}}finally {value.fill(0)}
        }
    }
    internal fun release() {
        require(!released)
        try {assertProtected()}finally {released=true;protectedRecords.clear();try{lock.release()}finally {lockFile.close()}}
    }
}
