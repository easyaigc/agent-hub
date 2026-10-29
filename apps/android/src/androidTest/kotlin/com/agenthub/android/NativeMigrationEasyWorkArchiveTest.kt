package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Actual native P8 durable writers and encrypted migration graph; no HTTP, signer or live account. */
class NativeMigrationEasyWorkArchiveTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned EasyWork original archive password"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-easywork-archives")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun source(): NativeSessionStorage=owned.storage.child(ui.activity,"ew-source-"+UUID.randomUUID().toString().take(8)).also {store->
        val rows=MigrationActivationFixtures.payload().getJSONObject("records");rows.keys().forEach {store.write(it,rows.getString(it))}
    }
    private fun envelope(source: NativeSessionStorage)=File(owned.folder,"ew-"+UUID.randomUUID()+".encrypted").also {NativeMigrationExport(ui.activity,source).sealFile(it,password)}
    private fun stage(source: NativeSessionStorage,destination: NativeSessionStorage=owned.storage)=NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),envelope(source),password)
    private fun activate(destination: NativeSessionStorage,receipt: NativeMigrationCandidateStore.Receipt)=NativeMigrationActivation(ui.activity,destination,NativeMigrationNotificationActivationLease(destination,receipt){true}).activate(receipt)
    private fun originals(seed: NativeMigrationEasyWorkFixtures.Seed,names: Set<String> =seed.originalNames)=names.associateWith {requireNotNull(seed.storage.read(it))}
    @Test fun actualUnactivatedCredentialAndPendingRevokeOriginalsStayInertAndExactThroughPublication() {
        val source=source();val seed=NativeMigrationEasyWorkFixtures.seed(ui.activity,source,"pending");val oidc=NativeMigrationEasyWorkFixtures.oidc(source,seed.identity)
        val names=seed.originalNames+oidc;val raw=originals(seed,names);val before=MigrationActivationFixtures.tree(source)
        assertTrue(source.metadata()!!.getJSONArray("clients").objects().map(::decodeClient).all {it.agentConnection==null})
        val receipt=stage(source);val candidate=owned.storage.child(ui.activity,"migration-candidates");val candidateBefore=MigrationActivationFixtures.tree(candidate)
        assertEquals(receipt,NativeMigrationCandidateStore(ui.activity,owned.storage).currentReceipt())
        assertTrue(runCatching {NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)}.isFailure)
        activate(owned.storage,receipt);val current=owned.storage.reopen(ui.activity)
        val archived=NativeMigrationEasyWorkArchives.readOnlyArchives(current).single();assertTrue(archived.requiresOriginalInstallation)
        assertEquals("ArchivedEasyWorkOriginals(redacted)",archived.toString());assertEquals(names,archived.names())
        raw.forEach {(name,value)->assertEquals(value,archived.readOnly(name));assertNull(current.read(name))}
        assertTrue(current.metadata()!!.getJSONArray("clients").objects().map(::decodeClient).all {it.scope?.agentId=="codex"&&it.agentConnection==null})
        val fence=JSONObject(archived.readOnly(seed.originalNames.single {it.startsWith(NativeMigrationEasyWorkOriginals.FENCE)})!!).getJSONObject("revoke")
        assertEquals("pending",fence.getString("state"));assertEquals("original unknown extension",JSONObject(fence.getString("saved")).getString("futureOwner"))
        assertEquals("original pending extension",JSONObject(fence.getString("saved")).getJSONObject("refreshOperation").getString("futurePending"))
        assertEquals(before,MigrationActivationFixtures.tree(source));assertEquals(candidateBefore,MigrationActivationFixtures.tree(candidate))
        val after=MigrationActivationFixtures.tree(current);NativeMigrationActivation(ui.activity,current).activate(receipt);assertEquals(after,MigrationActivationFixtures.tree(current))
    }
    @Test fun actualRefreshResultsKeepOriginalBodyTokenHashAndUnknownExtensions() {
        val source=source();val seed=NativeMigrationEasyWorkFixtures.seed(ui.activity,source,"pending",refreshAudit=true)
        val name=seed.originalNames.single {it.startsWith(NativeMigrationEasyWorkOriginals.FENCE)};val before=requireNotNull(source.read(name))
        val results=JSONObject(before).getJSONObject("refreshResults");assertEquals(1,results.length())
        val operation=results.keys().next();val row=results.getJSONObject(operation)
        assertEquals("original pending extension",row.getString("futurePending"));assertEquals("original pending extension",row.getJSONObject("originalPending").getString("futurePending"))
        assertFalse(row.has("token"));assertEquals(easyHash("b".repeat(43).toByteArray()),row.getString("tokenHash"))
        assertEquals(easyCanonical(JSONObject().put("operationId",operation)),row.getString("body"))
        val receipt=stage(source);activate(owned.storage,receipt);val archive=NativeMigrationEasyWorkArchives.readOnlyArchives(owned.storage.reopen(ui.activity)).single()
        assertEquals(before,archive.readOnly(name));assertEquals(before,source.read(name))
    }
    @Test fun confirmedFenceWithoutGrantAndGuardOnlyCrashAreNeverOmittedOrReactivated() {
        for(mode in listOf("confirmed-missing","guard-only")) {
            val source=source();val seed=NativeMigrationEasyWorkFixtures.seed(ui.activity,source,mode);assertNull(source.read(seed.grantRef))
            val raw=originals(seed);val before=MigrationActivationFixtures.tree(source)
            if(mode=="confirmed-missing") {
                val revoke=JSONObject(raw.getValue(seed.originalNames.single {it.startsWith(NativeMigrationEasyWorkOriginals.FENCE)})).getJSONObject("revoke")
                assertTrue(revoke.get("saved") is String);assertTrue(revoke.get("localReceipt") is JSONObject)
            }
            val destination=owned.storage.child(ui.activity,"ew-dest-"+UUID.randomUUID().toString().take(8));val receipt=stage(source,destination)
            activate(destination,receipt);val current=destination.reopen(ui.activity);val archived=NativeMigrationEasyWorkArchives.readOnlyArchives(current).single()
            raw.forEach {(name,value)->assertEquals(value,archived.readOnly(name));assertNull(current.read(name))};assertNull(current.read(seed.grantRef))
            assertEquals(before,MigrationActivationFixtures.tree(source))
        }
    }
    @Test fun actualConfirmedCleanupResumeKeepsReceiptObjectAndOriginalSavedStringExact() {
        val source=source();val seed=NativeMigrationEasyWorkFixtures.seed(ui.activity,source,"confirmed-missing")
        val name=seed.originalNames.single {it.startsWith(NativeMigrationEasyWorkOriginals.FENCE)}
        val before=requireNotNull(source.read(name));val revoke=JSONObject(before).getJSONObject("revoke")
        assertTrue(revoke.get("saved") is String);assertTrue(revoke.get("localReceipt") is JSONObject)
        val local=revoke.getJSONObject("localReceipt");assertEquals("original unknown extension",local.getString("futureOwner"))
        assertEquals("original pending extension",local.getJSONObject("refreshOperation").getString("futurePending"))
        assertEquals("original extension",local.getJSONObject("grant").getString("futureGrant"))
        NativeMigrationEasyWorkFixtures.resumeConfirmedCleanup(seed)
        val resumed=requireNotNull(source.read(name));val original=JSONObject(resumed).getJSONObject("revoke")
        assertEquals(revoke.getString("saved"),original.getString("saved"));assertTrue(original.get("localReceipt") is JSONObject)
        assertEquals(easyCanonical(local),easyCanonical(original.getJSONObject("localReceipt")))
        assertEquals(revoke.getString("operationId"),original.getString("operationId"));assertEquals(revoke.getString("body"),original.getString("body"));assertEquals(revoke.getString("intentHash"),original.getString("intentHash"))
        val receipt=stage(source);activate(owned.storage,receipt)
        assertEquals(resumed,NativeMigrationEasyWorkArchives.readOnlyArchives(owned.storage.reopen(ui.activity)).single().readOnly(name))
        assertEquals(resumed,source.read(name));assertNull(source.read(seed.grantRef))
    }
    @Test fun incorrectLocalReceiptTypesAndChangedOriginalIdentityRefuseWithoutReplacingCandidate() {
        val source=source();val seed=NativeMigrationEasyWorkFixtures.seed(ui.activity,source,"confirmed-missing")
        val name=seed.originalNames.single {it.startsWith(NativeMigrationEasyWorkOriginals.FENCE)};val raw=requireNotNull(source.read(name))
        val prior=stage(source);val candidate=owned.storage.child(ui.activity,"migration-candidates");val before=MigrationActivationFixtures.tree(candidate)
        val original=JSONObject(raw).getJSONObject("revoke").getJSONObject("localReceipt")
        val wrong= listOf<Any>(original.toString(),JSONObject.NULL,true,org.json.JSONArray(),JSONObject(original.toString()).put("origin","https://other.example.invalid"),
            JSONObject(original.toString()).apply {getJSONObject("grant").getJSONObject("binding").put("bindingId","another-binding")})
        for(value in wrong) {
            source.write(name,JSONObject(raw).apply {getJSONObject("revoke").put("localReceipt",value)}.toString());val sourceBefore=MigrationActivationFixtures.tree(source)
            assertTrue(runCatching {envelope(source)}.isFailure);assertEquals(sourceBefore,MigrationActivationFixtures.tree(source))
            assertEquals(before,MigrationActivationFixtures.tree(candidate));assertEquals(prior,NativeMigrationCandidateStore(ui.activity,owned.storage).currentReceipt())
        }
        source.write(name,raw)
    }
    @Test fun destinationOriginalAuthorityAndUnknownRemainExactWhileSourceArchivesMergeAcrossReexport() {
        val source=source();val imported=NativeMigrationEasyWorkFixtures.seed(ui.activity,source,"pending");val local=NativeMigrationEasyWorkFixtures.seed(ui.activity,owned.storage,"confirmed-missing")
        val localRaw=originals(local);val receipt=stage(source);val before=MigrationActivationFixtures.tree(owned.storage)
        assertTrue(runCatching {NativeMigrationActivation(ui.activity,owned.storage,NativeMigrationNotificationActivationLease(owned.storage,receipt){true}).activate(receipt){if(it==0)throw java.io.IOException("owned pre-publication interruption")}}.isFailure)
        assertEquals(before,MigrationActivationFixtures.tree(owned.storage));activate(owned.storage,receipt);val current=owned.storage.reopen(ui.activity)
        localRaw.forEach {(name,value)->assertEquals(value,current.read(name))}
        val original=NativeMigrationEasyWorkArchives.readOnlyArchives(current).single();assertEquals(imported.identity.deviceId,original.deviceId)
        assertNull(current.read(imported.credentialName));assertEquals(local.identity.json().toString(),current.read(NativeMigrationEasyWorkOriginals.IDENTITY))
        val reexport=envelope(current);val destination=current.child(ui.activity,"ew-again-"+UUID.randomUUID().toString().take(8))
        val again=NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),reexport,password);activate(destination,again)
        val archives=NativeMigrationEasyWorkArchives.readOnlyArchives(destination.reopen(ui.activity));assertEquals(2,archives.size)
        assertEquals(setOf(imported.identity.deviceId,local.identity.deviceId),archives.map {it.deviceId}.toSet())
    }
    @Test fun modernOrphanReferencesResolveExactLegacyGrantAndFenceWithoutAtomicRecovery() {
        val source=source();val seed=NativeMigrationEasyWorkFixtures.seed(ui.activity,source,"pending")
        val names=setOf(seed.grantRef,seed.originalNames.single {it.startsWith(NativeMigrationEasyWorkOriginals.FENCE)})
        val key=NativeSessionStorage::class.java.getDeclaredMethod("getKey").apply {isAccessible=true}.invoke(source) as javax.crypto.SecretKey
        for(name in names) {
            val raw=requireNotNull(source.read(name));val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {init(javax.crypto.Cipher.ENCRYPT_MODE,key);updateAAD(name.toByteArray(Charsets.UTF_8))}
            java.io.File(source.coordinationKey,MigrationActivationFixtures.sha(name.toByteArray())+".bin").writeBytes(byteArrayOf(1)+cipher.iv+cipher.doFinal(raw.toByteArray(Charsets.UTF_8)))
        }
        val before=MigrationActivationFixtures.tree(source);val raw=originals(seed);val receipt=stage(source)
        activate(owned.storage,receipt);val archive=NativeMigrationEasyWorkArchives.readOnlyArchives(owned.storage.reopen(ui.activity)).single()
        raw.forEach {(name,value)->assertEquals(value,archive.readOnly(name))};assertEquals(before,MigrationActivationFixtures.tree(source))
        val pending=java.io.File(source.coordinationKey,MigrationActivationFixtures.sha(names.first().toByteArray())+".bin.new").apply {writeText("owned unfinished original")}
        val pendingBefore=MigrationActivationFixtures.tree(source);assertTrue(runCatching {envelope(source)}.isFailure)
        assertEquals(pendingBefore,MigrationActivationFixtures.tree(source));assertTrue(pending.delete())
        val pointer=java.io.File(source.coordinationKey,MigrationActivationFixtures.sha("migration-active-generation-v1".toByteArray())+".bin.bak").apply {writeText("owned ambiguous generation pointer")}
        val pointerBefore=MigrationActivationFixtures.tree(source);assertTrue(runCatching {envelope(source)}.isFailure)
        assertEquals(pointerBefore,MigrationActivationFixtures.tree(source));assertTrue(pointer.delete())
    }
    @Test fun malformedFenceUnclaimedOriginalAndActiveEasyWorkMetadataRefuseWithoutChangingOriginalCandidate() {
        val source=source();val seed=NativeMigrationEasyWorkFixtures.seed(ui.activity,source,"pending");val store=NativeMigrationCandidateStore(ui.activity,owned.storage)
        val prior=stage(source);val candidateBefore=MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates"))
        val name=seed.originalNames.single {it.startsWith(NativeMigrationEasyWorkOriginals.FENCE)};val original=source.read(name)!!
        for(change in listOf<(JSONObject)->Unit>({it.put("version",2)},{it.put("ref",NativeMigrationEasyWorkOriginals.GRANT+"0".repeat(64))},{it.getJSONObject("revoke").put("operationId",UUID.randomUUID().toString())},{it.getJSONObject("revoke").put("intentHash","0".repeat(64))})) {
            source.write(name,JSONObject(original).also(change).toString());val before=MigrationActivationFixtures.tree(source)
            assertTrue(runCatching {envelope(source)}.isFailure);assertEquals(before,MigrationActivationFixtures.tree(source));assertEquals(candidateBefore,MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates")))
        }
        source.write(name,original);source.write("easywork-future-authority-v9","{\"version\":9}");val before=MigrationActivationFixtures.tree(source)
        assertTrue(runCatching {envelope(source)}.isFailure);assertEquals(before,MigrationActivationFixtures.tree(source));source.remove("easywork-future-authority-v9")
        // Current scope does not silently turn an active EasyWork business graph into an orphan.
        val metadata=source.metadata()!!;metadata.getJSONArray("clients").getJSONObject(0).getJSONObject("scope").put("agentId","easywork")
        source.write("metadata",metadata.toString());assertTrue(runCatching {envelope(source)}.isFailure)
        assertEquals(prior,store.currentReceipt());assertEquals(candidateBefore,MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates")))
    }
}
