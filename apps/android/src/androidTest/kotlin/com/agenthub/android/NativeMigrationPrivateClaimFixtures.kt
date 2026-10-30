package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Parent supplies a fresh UUID per warm selector, or the same UUID for the ordered cold chain.
 * Actual Keystore/Session/stream storage, isolated roots. All remote ports fail before transport.
 */
internal class NativeMigrationPrivateClaimFixtures {
    val app=InstrumentationRegistry.getInstrumentation().targetContext
    val run=requireNotNull(InstrumentationRegistry.getArguments().getString("privateClaimMigrationRun")) {
        "Fresh parent-owned private claim migration run required"
    }.also {require(UUID.fromString(it).toString()==it)}
    val folder=File(app.filesDir,"acceptance/P7-private-claims-$run")
    val password="owned private claim inert migration password"
    private val ownerToken=PrivateClaimFixtureOwners.current()
    val remoteCalls=AtomicInteger()
    var exportCiphertext: Map<String,String>?=null;private set
    init {NativeChatNotificationNaturalStore.guard(app)}
    fun fresh() {
        require(!folder.exists()) {"Existing original/candidate must not be replayed"}
        check(folder.mkdirs());android.system.Os.chmod(folder.path,0x1c0)
        for(role in listOf("source","destination")){val f=File(folder,role);check(f.mkdir());android.system.Os.chmod(f.path,0x1c0)}
    }
    fun context(role: String): Context {
        require(role in setOf("source","destination"));val f=File(folder,role)
        require(f.isDirectory&&!java.nio.file.Files.isSymbolicLink(f.toPath()))
        return object: ContextWrapper(app){override fun getNoBackupFilesDir()=f;override fun getApplicationContext(): Context=this}
    }
    fun alias(role: String)="p7-private-claims-$role-$run"
    fun storage(role: String)=NativeSessionStorage(context(role),"installation",alias(role))
    fun seedSource(): NativeSessionStorage=storage("source").also {root->
        val rows=MigrationActivationFixtures.payload().getJSONObject("records")
        rows.keys().forEach {root.write(it,rows.getString(it))}
    }
    fun intent(host: String="unpaired-claim-host",subject: String?="historical-account"): String {
        val target=JSONObject().put("agentId","codex").put("hostId",host).put("workspaceId","original-workspace").put("runtimeId","original-runtime")
        if(subject!=null)target.put("account",JSONObject().put("authority","historical-authority").put("subject",subject))
        return "\n "+JSONObject().put("claim",JSONObject().put("version",1).put("target",target).put("publicKey","A".repeat(43)))
            .put("endpoint","https://127.0.0.1:1").put("fingerprint","a".repeat(64)).toString()+" \n"
    }
    fun name(raw: String)=NativeMigrationPrivateClaims.ACTIVE_PREFIX+NativeMigrationInertArchive.sha(raw.toByteArray(Charsets.UTF_8))
    fun seedClaim(root: NativeSessionStorage,raw: String=intent())=name(raw).also {root.write(it,raw)}
    fun tree(root: NativeSessionStorage)=MigrationActivationFixtures.tree(root)
    fun operations(root: NativeSessionStorage): Map<String,JSONObject> {
        val rows=MigrationActivationFixtures.payload().getJSONObject("records")
        return rows.keys().asSequence().filter {it.startsWith("operations:")}.associateWith {name->
            val values=JSONObject(requireNotNull(root.read(name))).getJSONArray("operations")
            JSONObject().also {result->for(i in 0 until values.length()) {
                val row=values.getJSONObject(i);val original=JSONObject()
                for(key in listOf("id","intent","intentSha256","phase","result","submissionAttempted","sourceKey","sourceVersion"))
                    if(row.has(key))original.put(key,row.get(key))
                result.put(row.getString("id"),original)
            }}
        }
    }
    private fun commands(): CodexCommands=java.lang.reflect.Proxy.newProxyInstance(CodexCommands::class.java.classLoader,
        arrayOf(CodexCommands::class.java)) {proxy,method,args->
            if(method.declaringClass==Any::class.java)when(method.name) {
                "hashCode"->System.identityHashCode(proxy)
                "equals"->proxy===args?.get(0)
                "toString"->"IsolatedNoRemoteCommands"
                else->error("Unexpected object method")
            }else {remoteCalls.incrementAndGet();error("Unexpected remote command ${method.name}")}
        } as CodexCommands
    private fun reads()=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
            remoteCalls.incrementAndGet();error("Unexpected remote workspace read")
        }
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
            remoteCalls.incrementAndGet();error("Unexpected remote history read")
        }
    }
    /** Uses actual Main-owned quiesce, not a truthy test lease. No discovery/voice actor is started. */
    suspend fun <T> owned(root: NativeSessionStorage,consume: suspend (NativeMigrationOwnerLease)->T): T {
        val record=PrivateClaimFixtureRecord<MobileSession,NativeMigrationOwnerLease,NativeSessionStorage>(ownerToken,root,run,root.coordinationKey) {state->
            val file=File(folder,"owner-${state.sequence}.txt")
            java.io.FileOutputStream(file).use {it.write(state.diagnostic().toByteArray(Charsets.UTF_8));it.fd.sync()}
            android.system.Os.chmod(file.path,0x180)
        }
        return PrivateClaimFixtureLifecycle(record,Dispatchers.Main.immediate,
            create={MobileSession(root,reads(),commands())},restore={},
            awaitRestore={session->
                val names=listOf("metadata","codex-private-connections-v1")
                val originals=withContext(Dispatchers.IO){names.associateWith {root.read(it)}}
                session.restoreForMigration()
                withContext(Dispatchers.IO){assertTrue("Migration restore must preserve original metadata and vault",
                    originals==names.associateWith {root.read(it)})}
            },acquire={it.quiesceForMigration(UUID.randomUUID().toString())},
            retire={session,lease->session.retireForMigration(lease.operationId)},
            abandon={it.abandonMigrationRestore()},release={it.releaseRawWriters()},
            verify={assertEquals("No remote lookup/authorization/POST/replay is admissible",0,remoteCalls.get())}
        ).run(consume)
    }
    suspend fun export(root: NativeSessionStorage): File=File(folder,"claims.encrypted").also {file->
        owned(root){lease->exportCiphertext=tree(root);NativeMigrationExport(context("source"),root).sealFile(file,password,lease)}
    }
    fun inspect(current: NativeSessionStorage,expected: Map<String,String>) {
        val originals=NativeMigrationPrivateClaims.read(current);val restored=linkedMapOf<String,String>()
        for(original in originals)for(i in original.entries.indices) {
            val entry=original.entries[i];assertEquals("text",entry.kind)
            restored[entry.name]=NativeMigrationPrivateClaims.text(current,original,i)
        }
        assertEquals(expected,restored)
        val active=current.migrationReadTextRecords(expected.keys)
        assertTrue("Incoming intents cannot regain an active claim name",active.isEmpty())
        assertNull(current.read("easywork-device-identity"))
        assertTrue(CodexConnectionVault(current).clients().none {it.scope?.hostId in setOf("unpaired-claim-host","local-original-claim-host")})
        assertEquals(0,remoteCalls.get())
    }
    /** Exact historical tag1 ciphertext under the isolated source key; unknown name stays unidentifiable. */
    fun legacyClaim(root: NativeSessionStorage,raw: String) {
        val name=name(raw);val key=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey(alias("source"),null) as javax.crypto.SecretKey
        val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {init(javax.crypto.Cipher.ENCRYPT_MODE,key);updateAAD(name.toByteArray(Charsets.UTF_8))}
        val file=File(root.coordinationKey,NativeMigrationInertArchive.sha(name.toByteArray(Charsets.UTF_8))+".bin")
        java.io.FileOutputStream(file).use {it.write(byteArrayOf(1)+cipher.iv+cipher.doFinal(raw.toByteArray(Charsets.UTF_8)));it.fd.sync()}
        android.system.Os.chmod(file.path,0x180)
    }
    fun proofFile()=File(folder,"cold-proof.json")
    fun receipt(value: JSONObject)=NativeMigrationCandidateStore.Receipt(value.getString("candidateId"),value.getString("candidateSha256"),value.getInt("recordCount"))
    fun hashes(value: JSONObject)=value.keys().asSequence().associateWith {value.getString(it)}
    fun publish(value: JSONObject) {
        check(proofFile().createNewFile());java.io.FileOutputStream(proofFile()).use {it.write(value.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
    }
    fun proof(): JSONObject {
        val f=proofFile();require(f.isFile&&!java.nio.file.Files.isSymbolicLink(f.toPath())&&f.length() in 1..128*1024)
        return StrictMigrationJson.parse(f.readBytes()).also {require(it.get("version")==1&&it.get("kind")=="private-claims-cold-v1"&&it.get("run")==run)}
    }
    fun coldHeader(receipt: NativeMigrationCandidateStore.Receipt)=JSONObject().put("version",1).put("kind","private-claims-cold-v1").put("run",run)
        .put("writerPid",Process.myPid()).put("candidateId",receipt.id).put("candidateSha256",receipt.sha256).put("recordCount",receipt.recordCount)
}
