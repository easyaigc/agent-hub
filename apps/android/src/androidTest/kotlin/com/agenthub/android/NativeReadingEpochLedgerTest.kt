package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Synthetic owned namespaces/aliases only. Does not launch an Activity or create commands/approvals.
 * Real encrypted-storage assertions require Root scheduling; host evidence is separately classified. */
class NativeReadingEpochLedgerTest {
    // Exact live handles remain reachable if physical exit is unknown; no fixture replay/retirement.
    private data class Retained(val root: File,val alias: String,val store: NativeSessionStorage,val workers: NativeReadingEpochWorkers)
    companion object {private val retained=ConcurrentHashMap<String,Retained>()}
    private fun immutable(root: File,name: String,value: JSONObject) {
        val fd=Os.open(File(root,name).path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,384)
        FileOutputStream(fd).use {out->out.write(value.toString().toByteArray());out.fd.sync()}
        NativeReadingFixtureFiles.syncDirectory(root)
    }
    private fun withStore(check: (NativeSessionStorage,NativeReadingEpochWorkers)->Unit) {
        kotlin.check(Looper.myLooper()!=Looper.getMainLooper()) {"Fixture storage/exit waits must not run on Main"}
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        require(base.packageName=="com.easyai.agenthub.android.acceptance")
        val id=UUID.randomUUID().toString();val alias="agent-hub-reading-epoch-"+id
        val root=File(base.noBackupFilesDir,"reading-epoch-"+id);require(root.mkdir());Os.chmod(root.path,448)
        val local=object: ContextWrapper(base) {
            override fun getApplicationContext(): Context=this
            override fun getNoBackupFilesDir()=root
        }
        val store=try {NativeSessionStorage(local,"ledger",alias)}catch(error: Throwable) {
            System.err.println("P7_READING_EPOCH_FIXTURE fixture=$id alias=$alias phase=initialization cleanup=not-attempted")
            throw error
        }
        val directory=File(root,"ledger");Os.chmod(directory.path,448)
        val workers=NativeReadingEpochWorkers(id);var passed=false;var failure: Throwable?=null;var retired=false
        val cleanup=NativeReadingFixtureCleanup {sequence,phase,states->
            immutable(root,"cleanup-%02d.json".format(sequence),JSONObject().put("fixture",id).put("alias",alias)
                .put("phase",phase).put("states",JSONObject(states)).put("ledgerAssertionsPassed",passed)
                .put("ownedWorkersTerminated",workers.allTerminated).put("storageRetired",retired)
                .put("completionMissingMeansUnknown",true).put("interactiveUiProof",false))
        }
        try {check(store,workers);passed=true}catch(error: Throwable) {failure=error}
        // queued() releases the transaction lock before its single bounded exit wait. Never retry
        // an unknown wait, interrupt a synchronous action, or retire its storage to make it finish.
        for(worker in workers.owned.filter {!it.exitAttempted})try {worker.awaitExit()}catch(error: Throwable) {
            if(failure==null)failure=error else failure.addSuppressed(error)
        }
        if(!workers.allTerminated) {
            retained[id]=Retained(root,alias,store,workers)
            if(failure==null)failure=IllegalStateException("Physical assertion worker exit unknown: $id")
        }
        try {
            immutable(root,"workers.json",JSONObject().put("fixture",id).put("alias",alias)
                .put("workers",JSONArray(workers.owned.map {JSONObject(it.record())}))
                .put("allTerminated",workers.allTerminated).put("ledgerAssertionsPassed",passed)
                .put("storageRetired",false).put("retainedHandle",if(workers.allTerminated)JSONObject.NULL else "NativeReadingEpochLedgerTest.retained[$id]"))
            if(workers.allTerminated) {store.retireWriter();retired=true}
            if(passed&&workers.allTerminated) {
                val name=MessageDigest.getInstance("SHA-256").digest(NativeReadingLedger.STORAGE_NAME.toByteArray()).joinToString(""){"%02x".format(it)}+".bin"
                require(root.canonicalFile==File(base.noBackupFilesDir.canonicalFile,root.name) && directory.canonicalFile==File(root.canonicalFile,"ledger"))
                for(dir in listOf(root,directory)) {val stat=Os.lstat(dir.path);require(stat.st_uid==Process.myUid()&&stat.st_mode==16832&&!Files.isSymbolicLink(dir.toPath()))}
                require(root.list()?.toSet()==setOf("ledger","workers.json") && directory.list()?.toSet()==setOf(name))
                val file=File(directory,name);val s=Os.lstat(file.path)
                require(s.st_uid==Process.myUid()&&s.st_mode==33152&&s.st_nlink==1L&&!Files.isSymbolicLink(file.toPath()))
                cleanup.step("cipher","removed") {Files.delete(file.toPath())}
                cleanup.step("directory","removed") {Files.delete(directory.toPath())}
                cleanup.step("alias","removed") {
                    val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)};keys.deleteEntry(alias);require(!keys.containsAlias(alias))
                }
                cleanup.step("result","written") {
                    immutable(root,"result.json",JSONObject().put("fixture",id).put("ledgerAssertionsPassed",true)
                        .put("ownedWorkersTerminated",true).put("storageRetired",retired).put("executionNamespaceRemoved",true)
                        .put("syntheticAliasRemoved",true).put("parentCaptured",false).put("interactiveUiProof",false)
                        .put("purpose","Root capture of synthetic worker and cleanup evidence").put("reviewAtMs",System.currentTimeMillis()+21_600_000L)
                        .put("releaseCondition","Root independently captures workers/cleanup/result, then removes this exact evidence fixture"))
                }
            }
        }catch(error: Throwable) {if(failure==null)failure=error else if(failure!==error)failure.addSuppressed(error)}
        System.err.println("P7_READING_EPOCH_FIXTURE fixture=$id alias=$alias workersTerminated=${workers.allTerminated} storageRetired=$retired ledgerAssertionsPassed=$passed cleanupPhase=${cleanup.phase} cleanupStates=${JSONObject(cleanup.snapshot())} workerRecord=workers.json")
        failure?.let {throw it}
    }
    @Test fun queuedRemovedRuntimeCannotOverwriteRestoredScopes()=withStore(NativeReadingEpochAssertions::removedRuntimeScopeCannotBeRestoredByQueuedOwner)
    @Test fun queuedOldHeadCannotReviveRestoredReadOrQuestion()=withStore(NativeReadingEpochAssertions::restoredReadAndSeenNoticeCannotBeRevivedByQueuedOwner)
}
