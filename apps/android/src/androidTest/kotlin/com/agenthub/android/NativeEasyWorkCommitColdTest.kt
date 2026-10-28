package com.agenthub.android

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Two separately selected invocations; parent must force-stop the held writer and
 * independently prove the new target PID. Reader cannot start a case, identity or claim.
 */
@RunWith(AndroidJUnit4::class)
class NativeEasyWorkCommitColdTest {
    private fun fixture(phase: String): P8CommitAcceptance {
        val a=InstrumentationRegistry.getArguments();require(a.getString("p8CommitColdPhase")==phase)
        return P8CommitAcceptance(requireNotNull(a.getString("p8CommitColdCaseId")),phase=="reader")
    }
    @Test(timeout=65_000) fun writeUnactivatedCandidateAtFinalReadForColdRestart(): Unit=runBlocking {
        val f=fixture("writer");val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try {
            f.start();val input=EasyWorkConnectionInput("code","Cold synthetic candidate",f.origin,"0123456789ABCDEF")
            val lease=f.main {easyWorkConnectionForm(f.session).begin(input)}
            val job=tasks.async {withContext(NonCancellable+lease){withTimeout(35_000){runCatching {f.session.connectEasyWorkCode(input.name,input.endpoint,input.value)}}}}
            f.pending();f.inactive();val ref=f.candidate(lease.id);f.candidatePresent(ref);f.unchanged();f.flush();assertFalse(job.isCompleted)
            val metadata=f.storage.read("metadata")!!;assertEquals(0,JSONObject(metadata).getJSONArray("clients").length())
            val proof=f.common().put("phase","writer-held-final-read").put("readyElapsed",android.os.SystemClock.elapsedRealtime()).put("candidateRef",ref).put("candidateSha256",easyHash(f.storage.read("easywork-credential:$ref")!!.toByteArray()))
                .put("metadataSha256",easyHash(metadata.toByteArray())).put("identitySha256",easyHash(f.storage.read("easywork-device-identity")!!.toByteArray()))
                .put("grantRef",f.grantRef).put("grantSha256",easyHash(f.grantBytes.toByteArray())).put("claimKey",f.claimKey).put("claimSha256",easyHash(f.claimBytes.toByteArray()))
                .put("journalKey",f.journalKey).put("journalSha256",easyHash(f.journalBytes.toByteArray())).put("originalTargetKey",f.originalTarget).put("originalDraftSha256",easyHash(f.originalText.toByteArray()))
                .put("defaults",JSONObject(f.defaults)).put("claimRequestsSha256",easyHash(f.stats().getJSONArray("requests").toString().toByteArray())).put("namedReadsSha256",easyHash(f.stats().getJSONArray("reads").toString().toByteArray()))
                .put("originalOperationUnchanged",true).put("candidateUnreferenced",true).put("savedCallbacks",0).put("mustForceStopWithinSeconds",18)
            f.proof("writer-ready",proof)
            // Timeout/completed job is a failed writer attempt, never a passing result.
            withTimeout(20_000){while(true){check(!job.isCompleted) {"Held write completed before the externally verified stop"};delay(25)}}
        } finally {tasks.cancel()}
    }
    @Test(timeout=25_000) fun newProcessRestoreKeepsUnreferencedCandidateInactiveWithoutClaimOrSubmit() {
        val f=fixture("reader");f.health();val expected=JSONObject(requireNotNull(f.args.getString("p8CommitWriterProof")))
        require(expected.getString("caseId")==f.id&&expected.getString("namespace")==f.namespace&&expected.getString("keyAlias")==f.alias)
        require(expected.getInt("pid")!=Process.myPid()&&Process.getStartElapsedRealtime()>expected.getLong("readyElapsed")) {"A real new target process is required"}
        require(expected.getString("configSha256")==easyHash(easyCanonical(f.config).toByteArray()));assertEquals(easyCanonical(f.build),easyCanonical(expected.getJSONObject("build")))
        val onDisk=JSONObject(File(f.folder,"writer-ready.json").readText());assertEquals(easyCanonical(expected),easyCanonical(onDisk));require(!File(f.folder,"reader-proof.json").exists())
        require(File(f.i.targetContext.noBackupFilesDir,f.namespace).isDirectory)
        val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)};require(keys.getKey(f.alias,null) is javax.crypto.SecretKey);require(keys.getEntry("agent-hub-easywork-dpop-"+easyHash("p8-fence-${f.id}-a".toByteArray()),null) is KeyStore.PrivateKeyEntry)
        f.storage=NativeSessionStorage(f.i.targetContext,f.namespace,f.alias)
        val before=f.stats();assertEquals(1,before.getInt("held"));assertFalse(before.getBoolean("released"))
        assertEquals(expected.getString("claimRequestsSha256"),easyHash(before.getJSONArray("requests").toString().toByteArray()));assertEquals(expected.getString("namedReadsSha256"),easyHash(before.getJSONArray("reads").toString().toByteArray()))
        val refs=listOf("identitySha256" to "easywork-device-identity","grantSha256" to expected.getString("grantRef"),"claimSha256" to expected.getString("claimKey"),"journalSha256" to expected.getString("journalKey"),"candidateSha256" to "easywork-credential:"+expected.getString("candidateRef"),"metadataSha256" to "metadata")
        for((digest,key)in refs)assertEquals(expected.getString(digest),easyHash(f.storage.read(key)!!.toByteArray()))
        val defaults=expected.getJSONObject("defaults");assertEquals(defaults.keys().asSequence().associateWith {defaults.getString(it)},f.defaults())
        f.session=f.main {MobileSession(f.storage).also {it.leaveForeground();it.restore()}};f.waitFor {!f.main {f.session.restoring}}
        f.main {assertFalse(f.session.localRecoveryRequired);assertTrue("Restore must not discover or promote candidate refs",f.session.clients.isEmpty());f.session.leaveForeground()}
        f.flush();val metadata=f.storage.metadata()!!;assertEquals(0,metadata.getJSONArray("clients").length());assertEquals(expected.getString("originalDraftSha256"),easyHash(metadata.getJSONObject("drafts").getString(expected.getString("originalTargetKey")).toByteArray()))
        for((digest,key)in refs)assertEquals(expected.getString(digest),easyHash(f.storage.read(key)!!.toByteArray()))
        val after=f.stats();assertEquals(before.getJSONArray("requests").toString(),after.getJSONArray("requests").toString());assertEquals(before.getJSONArray("reads").toString(),after.getJSONArray("reads").toString());assertEquals(defaults.keys().asSequence().associateWith {defaults.getString(it)},f.defaults())
        f.proof("reader-proof",f.common().put("phase","reader-complete").put("writerPid",expected.getInt("pid")).put("writerProofSha256",easyHash(easyCanonical(expected).toByteArray())).put("candidateStillUnreferenced",true).put("candidateGrantClaimJournalMetadataUnchanged",true).put("defaultEncryptedFilesUnchanged",true).put("newClaimOrProtocolReadOrSubmit",false).put("realProcessReopen",true))
        f.api("/end-case",JSONObject().put("caseId",f.id))
    }
}
