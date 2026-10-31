package com.agenthub.android

import android.os.Process
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** New cold v2 only. All synchronous preflight happens on the supervised non-Main JUnit body. */
internal class NativeMigrationPrivateClaimColdFixtures {
    val f=NativeMigrationPrivateClaimFixtures()
    private val token=PrivateClaimFixtureOwners.current()
    private val lifetime=NativeMigrationReadLifetime()
    private var freshWriter=false
    fun view(role: String)=NativeSessionStorage.existingMigration(f.context(role),File(f.folder,role),"installation",f.alias(role))
    fun fresh() {
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        for(role in listOf("source","destination"))for(alias in listOf(f.alias(role),NativeMigrationExistingView.childAlias(f.alias(role),"migration-candidates",null)))
            require(!keys.containsAlias(alias)){"Fresh cold writer cannot reuse an orphan key"}
        f.fresh();freshWriter=true
    }
    /** Initial permissions on this writer's newly created fixture only; never used by readers. */
    fun privateFreshNamespaces() {
        check(freshWriter)
        for(role in listOf("source","destination")) {
            val base=f.context(role).noBackupFilesDir
            for(file in base.walkTopDown().onEnter {require(!java.nio.file.Files.isSymbolicLink(it.toPath()));true}) {
                require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
                if(file.name.endsWith(".lock"))continue
                val before=android.system.Os.lstat(file.path)
                val directory=android.system.OsConstants.S_ISDIR(before.st_mode)
                require(directory||android.system.OsConstants.S_ISREG(before.st_mode))
                val flags=android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK
                lifetime.use({android.system.Os.open(file.path,flags,0)},{fd->
                    val stat=android.system.Os.fstat(fd)
                    require(stat.st_uid==Process.myUid()&&stat.st_dev==before.st_dev&&stat.st_ino==before.st_ino&&
                        android.system.OsConstants.S_ISDIR(stat.st_mode)==directory&&
                        (directory||android.system.OsConstants.S_ISREG(stat.st_mode)&&stat.st_nlink==1L))
                    android.system.Os.fchmod(fd,if(directory)0x1c0 else 0x180)
                },{android.system.Os.close(it)})
            }
        }
    }
    fun read(file: File): JSONObject=NativeMigrationReadOnlyFile(f.folder,file,128*1024,lifetime).read {StrictMigrationJson.parse(it.readBytes())}.value
    fun proof(): JSONObject=read(f.proofFile()).also {value->
        require(value.keys().asSequence().toSet()==setOf("version","kind","run","writerPid","candidateId","candidateSha256","recordCount","candidateOriginGeneration","originalClaims","sourceCiphertext","destinationCiphertext","candidateCiphertext","sourceNamespaces","destinationNamespaces","operations","remoteCalls"))
        require(value.get("version")==2&&value.get("kind")=="private-claims-cold-v2"&&value.get("run")==f.run&&value.get("remoteCalls")==0)
    }
    fun origin(proof: JSONObject)=proof.get("candidateOriginGeneration").let {if(it==JSONObject.NULL)null else (it as String).also {value->require(UUID.fromString(value).toString()==value)}}
    fun operations(records: NativeMigrationStreamRecords): Map<String,String> {
        val immutable=listOf("id","kind","target","intent","intentSha256","phase","result","error","detached","submissionAttempted","sourceKey","sourceVersion","parentOperationId","forkContext","sharingScope")
        return records.text.filterKeys {it.startsWith("operations:")}.flatMap {(name,raw)->
            val rows=StrictMigrationJson.parse(raw.toByteArray()).getJSONArray("operations")
            (0 until rows.length()).map {i->val row=rows.getJSONObject(i);val saved=JSONObject()
                immutable.forEach {key->if(row.has(key))saved.put(key,row.get(key))}
                "$name:${row.getString("id")}" to NativeMigrationInertArchive.sha(saved.toString().toByteArray())
            }
        }.toMap()
    }
    fun inspect(view: NativeMigrationExistingView,proof: JSONObject) {
        assertEquals(f.hashes(proof.getJSONObject("originalClaims")),NativeMigrationPrivateClaims.readOnly(view.records))
        assertTrue(view.records.text.keys.none {it.startsWith(NativeMigrationPrivateClaims.ACTIVE_PREFIX)})
        assertEquals(f.hashes(proof.getJSONObject("operations")),operations(view.records))
        assertFalse(view.records.text.containsKey("easywork-device-identity"))
        assertEquals(0,f.remoteCalls.get())
    }
    fun publish(file: File,value: JSONObject) {
        check(file.createNewFile());java.io.FileOutputStream(file).use {out->out.write(value.toString().toByteArray());out.fd.sync()}
        android.system.Os.chmod(file.path,0x180)
    }
    fun rejectMissingMaterial() {
        val missing=f.alias("destination")+"-missing";val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        require(!keys.containsAlias(missing));val before=view("source").using {it.familyCiphertext()}
        assertTrue(runCatching{NativeSessionStorage.existingMigration(f.context("source"),File(f.folder,"source"),"installation",missing).using {}}.isFailure)
        assertFalse(keys.containsAlias(missing));assertEquals(before,view("source").using {it.familyCiphertext()})
        val absent=File(f.context("source").noBackupFilesDir,"missing-namespace")
        assertFalse(absent.exists())
        assertTrue(runCatching{NativeSessionStorage.existingMigration(f.context("source"),File(f.folder,"source"),absent.name,f.alias("source")).using {}}.isFailure)
        assertFalse(absent.exists())
    }
    fun rejectSidecarWithoutRepair() {
        val base=File(f.folder,"readonly-boundary");require(!base.exists());check(base.mkdir());android.system.Os.chmod(base.path,0x1c0)
        val context=object: android.content.ContextWrapper(f.app){override fun getNoBackupFilesDir()=base;override fun getApplicationContext(): android.content.Context=this}
        val alias=f.alias("source")+"-boundary";val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        require(!keys.containsAlias(alias))
        val store=NativeSessionStorage(context,"installation",alias)
        lifetime.use({store},{it.write("metadata","{}")},{it.retireWriter()})
        val root=File(base,"installation");android.system.Os.chmod(root.path,0x1c0)
        val original=requireNotNull(root.listFiles()).single();android.system.Os.chmod(original.path,0x180)
        val sidecar=File(root,original.name+".bak");check(sidecar.createNewFile());android.system.Os.chmod(sidecar.path,0x180)
        fun bytes()=requireNotNull(root.listFiles()).associate {it.name to NativeMigrationInertArchive.sha(it.readBytes())}
        val before=bytes()
        assertTrue(runCatching{NativeSessionStorage.existingMigration(context,base,"installation",alias).using {}}.isFailure)
        assertEquals(before,bytes());assertTrue(keys.containsAlias(alias))
        // The exact negative vector is retained as fixture evidence; never delete a possibly original file.
    }
    /** Read source+destination+fixed original candidate completely before the only writer admission. */
    fun existingWriter(proof: JSONObject): NativeSessionStorage = view("source").using {source->view("destination").using {destination->
        assertEquals(f.hashes(proof.getJSONObject("sourceCiphertext")),source.familyCiphertext())
        assertEquals(proof.getJSONArray("sourceNamespaces").strings(),source.namespaces())
        assertTrue(destination.namespaces().containsAll(proof.getJSONArray("destinationNamespaces").strings()))
        val originals=f.hashes(proof.getJSONObject("destinationCiphertext"));val current=destination.familyCiphertext()
        assertTrue("Every original destination ciphertext remains",originals.all {(name,sha)->current[name]==sha})
        assertEquals(origin(proof),destination.generation)
        assertEquals(f.hashes(proof.getJSONObject("candidateCiphertext")),destination.originalCandidateCiphertext(origin(proof)))
        destination.originalCandidate(origin(proof),f.receipt(proof))
        assertTrue(runCatching{destination.originalCandidate(origin(proof),f.receipt(proof).copy(sha256="0".repeat(64)))}.isFailure)
        destination.writer()
    }}
    suspend fun rejectInvalidOwnerAndAtomicFault(proof: JSONObject) {
        val invalid=existingWriter(proof)
        f.owned(invalid){lease->
            val before=f.tree(invalid)
            val session=token.records.last {it.storage===invalid}.session as MobileSession
            val notification=lease.notification(f.receipt(proof))
            val forged=NativeMigrationOwnerLease(invalid,lease.operationId,lease.rawWriters,lease.inboxWriters,lease.selectionWriters,lease.localPhotoWriters){true}
            assertTrue(runCatching {invalid.authorizeMigrationGeneration(session,forged)}.isFailure)
            withContext(Dispatchers.Main.immediate){session.invalidateMigrationLease()}
            assertTrue(runCatching {NativeMigrationActivation(f.context("destination"),invalid,notification,lease).activate(f.receipt(proof))}.isFailure)
            assertEquals(before,f.tree(invalid))
        }
        val unpublished=existingWriter(proof)
        f.owned(unpublished){lease->
            val before=f.tree(unpublished)
            assertTrue(runCatching {lease.prepareAndReleaseForRestore()}.isFailure)
            assertTrue(runCatching {lease.requireRawWriters()}.isFailure)
            assertEquals(before,f.tree(unpublished))
        }
        val fault=existingWriter(proof)
        f.owned(fault){lease->
            val before=f.tree(fault)
            assertTrue(runCatching {NativeMigrationActivation(f.context("destination"),fault,lease.notification(f.receipt(proof)),lease).activate(f.receipt(proof)){point->if(point==0)error("Cold publication checkpoint")}}.isFailure)
            assertEquals(before,f.tree(fault))
        }
    }
    private fun session(store: NativeSessionStorage)=MobileSession(store,object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {f.remoteCalls.incrementAndGet();error("Unexpected cold restore workspace")}
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {f.remoteCalls.incrementAndGet();error("Unexpected cold restore history")}
    },java.lang.reflect.Proxy.newProxyInstance(CodexCommands::class.java.classLoader,arrayOf(CodexCommands::class.java)){proxy,method,args->
        if(method.declaringClass==Any::class.java)when(method.name){"hashCode"->System.identityHashCode(proxy);"equals"->proxy===args?.get(0);"toString"->"ColdNoRemoteCommands";else->error("Object method")}
        else {f.remoteCalls.incrementAndGet();error("Unexpected cold restore command")}
    } as CodexCommands)
    /** Actual Controller release order and the same Activity restore function; every exact fresh Session is retained. */
    suspend fun activateAndRestore(proof: JSONObject): String {
        val root=existingWriter(proof)
        val old=PrivateClaimFixtureRecord<MobileSession,NativeMigrationOwnerLease,NativeSessionStorage>(token,root,f.run,root.coordinationKey)
        return PrivateClaimFixtureLifecycle(old,Dispatchers.Main.immediate,create={session(root)},restore={},
            awaitRestore={it.restoreForMigration()},acquire={it.quiesceForMigration(UUID.randomUUID().toString())},
            retire={original,lease->if(lease.restoredStorage!=null)original.requireCompletedMigrationTransfer(lease)else original.retireForMigration(lease.operationId)},
            abandon={it.abandonMigrationRestore()},release={it.releaseRawWriters()},verify={assertEquals(0,f.remoteCalls.get())}).run {lease->
            val oldChild=root.child(f.context("destination"),"migration-candidates")
            val generation=NativeMigrationActivation(f.context("destination"),root,lease.notification(f.receipt(proof)),lease).activate(f.receipt(proof))
            lease.prepareAndReleaseForRestore()
            val beforeModelRestore=view("destination").using {it.familyCiphertext()}
            assertTrue(runCatching {oldChild.write("stale-child","must refuse")}.isFailure)
            val job=requireNotNull(currentCoroutineContext()[Job])
            var record: PrivateClaimFixtureRecord<MobileSession,Any,NativeSessionStorage>?=null
            var fresh: MobileSession?=null
            try {
                withContext(Dispatchers.Main.immediate){fresh=restoreNativeMigrationSession(f.context("destination"),old.session!!,lease,create={store->
                    val owned=PrivateClaimFixtureRecord<MobileSession,Any,NativeSessionStorage>(token,store,"cold actual restored Session",store.coordinationKey+"#generation="+generation)
                    record=owned;token.enroll(owned);owned.attach(job);job.invokeOnCompletion {owned.coordinatorFinished()}
                    owned.entered();owned.creating()
                    session(store).also {owned.created(it);owned.restoring()}
                },beforeCommit={
                    val restoring=requireNotNull(record?.session)
                    assertTrue(runCatching {restoring.awaitMigrationRestore()}.isFailure)
                    assertNull(restoring.capturePanelNoticePresentation())
                    withContext(Dispatchers.IO) {
                        assertEquals(beforeModelRestore,view("destination").using {it.familyCiphertext()})
                        val pending=restoring.migrationStorage.child(f.context("destination"),"external-inbox")
                        assertTrue(runCatching {pending.write("pending-child","must refuse")}.isFailure)
                    }
                })}
                record!!.restored()
                withContext(Dispatchers.Main.immediate) {
                    requireNotNull(fresh).awaitMigrationRestore()
                    assertTrue(requireNotNull(fresh).phoneUnreadKnown)
                    assertNull(requireNotNull(fresh).phoneReadingError)
                }
                assertTrue(runCatching {root.write("stale-root","must refuse")}.isFailure)
                assertTrue(runCatching {oldChild.write("stale-child","must refuse")}.isFailure)
                val current=requireNotNull(fresh).migrationStorage
                // All fixed children were prepared after release, and ordinary editing is now allowed.
                for(path in NativeMigrationExistingPolicy.paths) {
                    var node=current
                    for(part in path.split('/'))node=node.child(f.context("destination"),part)
                    assertTrue(node.coordinationKey.contains(generation))
                }
                current.write("later-private-claim-draft","cold independent draft")
                assertEquals("cold independent draft",current.read("later-private-claim-draft"))
                assertDestinationOriginals(proof)
                generation
            }catch(error: Throwable){
                record?.let {owned->owned.failure("actual fresh restore/body",error)
                    // The shared helper throws only after confirmed abandonment; unknown exit holds inside it.
                    if(fresh==null&&owned.session!=null)owned.abandoned()
                }
                throw error
            }finally {
                record?.let {owned->withContext(NonCancellable){
                    val active=fresh
                    if(active!=null)try {
                        withContext(Dispatchers.Main.immediate){active.abandonMigrationRestore()}
                        withContext(Dispatchers.IO){active.migrationStorage.awaitQueuedWrites();active.migrationStorage.retireWriter()}
                        owned.abandoned()
                    }catch(error: Throwable){owned.failure("fresh cleanup, not retried",error)}
                    if(!owned.cleanupConfirmed)withContext(Dispatchers.IO){owned.awaitConfirmed()}
                    owned.delivered();owned.signal()
                    owned.primary?.let {throw it}
                }}
            }
        }
    }
    fun assertDestinationOriginals(proof: JSONObject) = view("destination").using {destination->
        val current=destination.familyCiphertext()
        for((path,sha)in f.hashes(proof.getJSONObject("destinationCiphertext")))assertEquals("Original destination $path",sha,current[path])
    }
    fun rejectMissingBaseWithoutGetter() {
        val base=File(f.folder,"missing-readonly-base");assertFalse(base.exists());var calls=0
        val context=object: android.content.ContextWrapper(f.app){override fun getNoBackupFilesDir(): File {calls++;error("Read-only must not call creating getter")}}
        assertTrue(runCatching{NativeSessionStorage.existingMigration(context,base,"installation",f.alias("source")).using {}}.isFailure)
        assertEquals(0,calls);assertFalse(base.exists())
    }
    fun thirdProof()=File(f.folder,"readonly-proof.json")
}
