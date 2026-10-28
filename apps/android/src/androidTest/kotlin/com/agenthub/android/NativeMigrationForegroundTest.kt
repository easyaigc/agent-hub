package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Frozen v1 source audit/safe-subset migration only. Never calls an identity loader/monitor/Host. */
class NativeMigrationForegroundTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned foreground migration password"
    private val at=java.time.Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private data class PairRecord(val raw: String,val device: String,val fingerprint: String)
    private data class Seed(val storage: NativeSessionStorage,val target: NativeChatNoticeTarget)
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-fg-v1")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->root.walkTopDown().filter{it.isFile}.associate {file->
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        file.relativeTo(root).path to digest.digest().joinToString(""){"%02x".format(it)}
    }}
    private fun sha(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun canonical(value: Any): String=when(value){
        is JSONObject->value.keys().asSequence().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.get(it))}
        is String->JSONObject.quote(value)
        is Boolean,is Number->value.toString()
        JSONObject.NULL->"null"
        else->error("Invalid test JSON")
    }
    private fun pair(bound: Boolean=false): PairRecord {
        val key=X25519PrivateKeyParameters(SecureRandom());val secret=key.encoded
        val public=Base64.getEncoder().encodeToString(key.generatePublicKey().encoded);val device="android-"+UUID.randomUUID()
        val raw=try{JSONObject().put("version",1).put("deviceId",device).put("journalBound",bound)
            .put("privateKey",Base64.getEncoder().encodeToString(secret)).put("publicKey",public).toString()}finally{secret.fill(0)}
        return PairRecord(raw,device,sha(public))
    }
    private fun seed(): Seed {
        val source=owned.storage.child(ui.activity,"fg-src-"+UUID.randomUUID().toString().take(8));val value=MigrationActivationFixtures.payload();val rows=value.getJSONObject("records")
        rows.keys().forEach{source.write(it,rows.getString(it))};return Seed(source,NativeChatNoticeTarget.from(MigrationActivationFixtures.client(value).scope!!))
    }
    private fun journal(pair: PairRecord,subscriptions: JSONArray=JSONArray())=JSONObject().put("version",1).put("deviceId",pair.device).put("identity",pair.fingerprint).put("subscriptions",subscriptions)
    private fun nonempty(pair: PairRecord,target: NativeChatNoticeTarget,batch: Boolean=false): JSONObject {
        val prefs=NativeChatNotificationPreferences(enabled=true,revision="original-fg-preference",generation=7)
        val pref=NativeChatNotificationCodec.preferences(prefs);val auth=sha("original-retained-authorization-reference")
        val operation=JSONObject().put("id",UUID.randomUUID().toString()).put("kind","register").put("authorization",auth).put("preferences",pref)
            .put("state",if(batch)"confirmed"else"unknown").put("at",at).put("fingerprint",sha(listOf("register",auth,sha(canonical(pref))).joinToString("\u0000")))
        if(batch)operation.put("responseState","ready").put("responseDelivery","foreground-only")
        val outcomes=JSONArray();val ids=JSONArray()
        if(batch)for(state in listOf("pending","processing")) {
            val event=NativeChatNotificationEvent(sha("original-fg-event-$state"),target,NativeChatNoticeKind.UNCERTAIN,"2026-10-28T12:00:00Z","synthetic-thread",operationId="original-business-unknown")
            val encoded=NativeChatNotificationCodec.event(event);ids.put(event.id)
            outcomes.put(JSONObject().put("event",encoded).put("fingerprint",sha(canonical(encoded))).put("state",state).put("at",at))
        }
        val sub=JSONObject().put("target",NativeChatNotificationCodec.target(target)).put("authorization",auth).put("preferences",pref).put("revoked",false)
            .put("cursor","").put("mutations",JSONArray().put(operation)).put("batch",if(!batch)JSONObject.NULL else JSONObject()
                .put("id",UUID.randomUUID().toString()).put("before","").put("after",ids.getString(ids.length()-1)).put("revision",prefs.revision).put("authorization",auth).put("ids",ids).put("activeIds",ids))
            .put("outcomes",outcomes)
        return journal(pair,JSONArray().put(sub))
    }
    private fun export(source: NativeSessionStorage)=File(owned.folder,"fg-${UUID.randomUUID().toString().take(8)}.migration").also{NativeMigrationExport(ui.activity,source).sealFile(it,password)}
    private fun stage(source: NativeSessionStorage): NativeMigrationCandidateStore.Receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),export(source),password)
    @Test fun unboundSourceIdentityNeverEntersEitherPortableFormatOrNewInstallation() {
        val source=seed();val identity=pair();source.storage.write(NativeMigrationForeground.IDENTITY,identity.raw);val before=tree(source.storage)
        val clear=NativeMigrationExport(ui.activity,source.storage).capture()
        try{val text=String(clear,Charsets.UTF_8);for(value in listOf(identity.device,JSONObject(identity.raw).getString("privateKey"),JSONObject(identity.raw).getString("publicKey"),NativeMigrationForeground.IDENTITY))assertFalse(text.contains(value))}finally{clear.fill(0)}
        val receipt=stage(source.storage);val records=NativeMigrationStreamCandidate(ui.activity,owned.storage).read(receipt){it.text}
        assertFalse(records.containsKey(NativeMigrationForeground.IDENTITY));assertFalse(records.containsKey(NativeMigrationForeground.JOURNAL))
        NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        assertNull(current.read(NativeMigrationForeground.IDENTITY));assertNull(current.read(NativeMigrationForeground.JOURNAL));assertEquals(before,tree(source.storage))
    }
    @Test fun validatedEmptySourceJournalIsExcludedAndDestinationOwnPairSurvivesRollbackAndRetry() {
        val source=seed();val sourcePair=pair(true);source.storage.write(NativeMigrationForeground.IDENTITY,sourcePair.raw);source.storage.write(NativeMigrationForeground.JOURNAL,journal(sourcePair).toString())
        NativeChatNotificationStore(source.storage).update{it.copy(enabled=true,sound="none")}
        val local=pair(true);val localJournal=journal(local).toString();owned.storage.write(NativeMigrationForeground.IDENTITY,local.raw);owned.storage.write(NativeMigrationForeground.JOURNAL,localJournal)
        val receipt=stage(source.storage);val before=tree(owned.storage);val original=tree(source.storage)
        for(point in listOf(1,receipt.recordCount+1,receipt.recordCount+2,receipt.recordCount+3,receipt.recordCount+4,0)) {
            assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt){if(it==point)throw java.io.IOException("owned foreground publication interruption")}}.isFailure)
            assertEquals(before,tree(owned.storage));assertEquals(original,tree(source.storage))
        }
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        assertEquals(local.raw,current.read(NativeMigrationForeground.IDENTITY));assertEquals(localJournal,current.read(NativeMigrationForeground.JOURNAL));assertNotEquals(sourcePair.raw,current.read(NativeMigrationForeground.IDENTITY))
        assertFalse(NativeChatNotificationStore(current).preferences().enabled);assertEquals("none",NativeChatNotificationStore(current).preferences().sound)
        assertTrue(NativeMigrationNotificationTombstones.decode(current.read(NativeMigrationNotificationTombstones.RECORD)!!).isEmpty())
        val later=pair(true);current.write(NativeMigrationForeground.IDENTITY,later.raw);current.write(NativeMigrationForeground.JOURNAL,journal(later).toString());current.write("later-fg-draft","keep later draft");val after=tree(current)
        assertEquals(generation,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt));assertEquals(after,tree(current));assertEquals(later.raw,current.read(NativeMigrationForeground.IDENTITY))
    }
    @Test fun unknownMutationHasExactReadOnlyAuditAndBlocksExportWithoutDeletingOrReplayingIt() {
        val source=seed();val identity=pair(true);val raw=nonempty(identity,source.target).toString();source.storage.write(NativeMigrationForeground.IDENTITY,identity.raw);source.storage.write(NativeMigrationForeground.JOURNAL,raw)
        val audit=NativeMigrationForeground.audit(source.storage);val operation=JSONObject(raw).getJSONArray("subscriptions").getJSONObject(0).getJSONArray("mutations").getJSONObject(0)
        assertEquals(raw,audit.readOnlyJournal());assertEquals(sha(raw),audit.journalSha256);assertEquals(listOf(operation.getString("id")),audit.operationIds);assertTrue(audit.requiresOriginalInstallation)
        assertEquals("ForegroundSourceAudit(redacted)",audit.toString());val before=tree(source.storage)
        val file=File(owned.folder,"existing-fg-export.migration");file.writeBytes(byteArrayOf(8,3,1,7));val previous=file.readBytes()
        val failure=runCatching{NativeMigrationExport(ui.activity,source.storage).sealFile(file,password)}.exceptionOrNull()
        assertTrue(failure is NativeMigrationForeground.OriginalInstallationRequired);assertEquals(raw,(failure as NativeMigrationForeground.OriginalInstallationRequired).audit.readOnlyJournal())
        assertArrayEquals(previous,file.readBytes());assertTrue(runCatching{NativeMigrationExport(ui.activity,source.storage).capture()}.isFailure);assertEquals(before,tree(source.storage))
        assertEquals(raw,source.storage.read(NativeMigrationForeground.JOURNAL));assertEquals("unknown",operation.getString("state"))
    }
    @Test fun processingBatchAndOriginalOutcomesRemainExactAndCannotBePromotedByMigration() {
        val source=seed();val identity=pair(true);val value=nonempty(identity,source.target,true);val raw=value.toString()
        source.storage.write(NativeMigrationForeground.IDENTITY,identity.raw);source.storage.write(NativeMigrationForeground.JOURNAL,raw);val before=tree(source.storage)
        val audit=NativeMigrationForeground.audit(source.storage);assertEquals(raw,audit.readOnlyJournal());assertEquals(2,audit.outcomeIds.size);assertEquals(1,audit.operationIds.size)
        assertTrue(runCatching{export(source.storage)}.exceptionOrNull() is NativeMigrationForeground.OriginalInstallationRequired);assertEquals(before,tree(source.storage))
        val sub=JSONObject(audit.readOnlyJournal()!!).getJSONArray("subscriptions").getJSONObject(0)
        assertEquals(value.getJSONArray("subscriptions").getJSONObject(0).getJSONObject("batch").toString(),sub.getJSONObject("batch").toString())
        assertEquals(listOf("pending","processing"),sub.getJSONArray("outcomes").objects().map{it.getString("state")})
    }
    @Test fun futureCorruptMissingOrMismatchedIdentityJournalFailClosedWithoutGeneratingAReplacement() {
        for(kind in listOf("identity-v2","identity-field","private-key","missing-identity","missing-bound-journal","journal-v2","journal-identity","mutation-field","mutation-fingerprint")) {
            val source=seed();val material=pair(true);val identity=JSONObject(material.raw);val journal=nonempty(material,source.target)
            when(kind){
                "identity-v2"->identity.put("version",2)
                "identity-field"->identity.put("futureRecovery",true)
                "private-key"->identity.put("publicKey",JSONObject(pair().raw).getString("publicKey"))
                "journal-v2"->journal.put("version",2)
                "journal-identity"->journal.put("identity","0".repeat(64))
                "mutation-field"->journal.getJSONArray("subscriptions").getJSONObject(0).getJSONArray("mutations").getJSONObject(0).put("responseV2",true)
                "mutation-fingerprint"->journal.getJSONArray("subscriptions").getJSONObject(0).getJSONArray("mutations").getJSONObject(0).put("fingerprint","0".repeat(64))
            }
            if(kind!="missing-identity")source.storage.write(NativeMigrationForeground.IDENTITY,identity.toString())
            if(kind!="missing-bound-journal")source.storage.write(NativeMigrationForeground.JOURNAL,journal.toString())
            val before=tree(source.storage);assertTrue(kind,runCatching{NativeMigrationForeground.audit(source.storage)}.isFailure);assertTrue(kind,runCatching{export(source.storage)}.isFailure);assertEquals(before,tree(source.storage))
        }
    }
    @Test fun evenPasswordAuthenticatedPortableIdentityOrJournalCannotReplaceAnOriginalCandidate() {
        val source=seed();val first=stage(source.storage);val local=owned.storage.child(ui.activity,"migration-candidates");val before=tree(local);val identity=pair()
        val clear=NativeMigrationExport(ui.activity,source.storage).capture();val original=try{JSONObject(String(clear,Charsets.UTF_8))}finally{clear.fill(0)}
        for(kind in listOf("identity","journal","both")) {
            val value=JSONObject(original.toString());val records=value.getJSONObject("records")
            if(kind!="journal")records.put(NativeMigrationForeground.IDENTITY,identity.raw)
            if(kind!="identity")records.put(NativeMigrationForeground.JOURNAL,journal(identity).toString())
            val envelope=NativeMigrationCrypto.seal(value.toString().toByteArray(),password)
            assertTrue(runCatching{NativeMigrationCandidateStore(ui.activity,owned.storage).stage(UUID.randomUUID().toString(),envelope,password,first)}.isFailure);assertEquals(before,tree(local))
        }
    }
    @Test fun nonemptyOrBrokenDestinationJournalBlocksActivationWithCandidateAndLocalAuthorityUnchanged() {
        val source=seed();val receipt=stage(source.storage);val identity=pair(true);val raw=nonempty(identity,source.target).toString();val candidate=owned.storage.child(ui.activity,"migration-candidates");val original=tree(candidate)
        for(kind in listOf("nonempty","missing-identity","missing-bound-journal")) {
            owned.storage.remove(NativeMigrationForeground.IDENTITY);owned.storage.remove(NativeMigrationForeground.JOURNAL)
            if(kind!="missing-identity")owned.storage.write(NativeMigrationForeground.IDENTITY,identity.raw)
            if(kind!="missing-bound-journal")owned.storage.write(NativeMigrationForeground.JOURNAL,raw)
            val before=tree(owned.storage);assertTrue(kind,runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)}.isFailure)
            assertEquals(before,tree(owned.storage));assertEquals(original,tree(candidate))
        }
        File(owned.folder,"foreground-v1-boundary.json").writeText(JSONObject().put("privateIdentityPortable",false).put("nonemptyJournalMigrationSupported",false)
            .put("originalUnknownAuditPreserved",true).put("identityGenerationCalls",0).put("httpCalls",0).put("nativeProductionP9Invoked",false).toString())
    }
    @Test fun frozenP9DecodersIndependentlyValidateTheSameReadOnlyOriginals() {
        // The coordinator's adopted160b51ec/66b12bd8 dependency must be present. Missing producer
        // classes are a failed prerequisite, never a skipped or substitute native proof.
        fun decoder(name: String,method: String): (String)->Unit {
            val type=Class.forName("com.agenthub.android.$name");val companion=type.getField("Companion").get(null)
            val decode=companion.javaClass.getDeclaredMethod(method,String::class.java).apply{isAccessible=true}
            return {raw->decode.invoke(companion,raw);Unit}
        }
        val validateIdentity=decoder("NativeChatNotificationIdentity","decode")
        val validateJournal=decoder("NativeChatNotificationForegroundStore","validateEncoded")
        for(batch in listOf(false,true)) {
            val source=seed();val identity=pair(true);val raw=nonempty(identity,source.target,batch).toString()
            validateIdentity(identity.raw);validateJournal(raw)
            source.storage.write(NativeMigrationForeground.IDENTITY,identity.raw);source.storage.write(NativeMigrationForeground.JOURNAL,raw)
            assertEquals(raw,NativeMigrationForeground.audit(source.storage).readOnlyJournal())
            val invalid=JSONObject(raw).put("version",2).toString();assertTrue(runCatching{validateJournal(invalid)}.isFailure)
            val wrongPair=JSONObject(identity.raw).put("publicKey",JSONObject(pair().raw).getString("publicKey")).toString()
            assertTrue(runCatching{validateIdentity(wrongPair)}.isFailure)
        }
    }
    @Test fun pendingIdentityFilesCannotBeRecoveredOrDeletedByReadOnlyAuditOrActivation() {
        for(suffix in listOf(".bak",".new")) {
            val source=seed();source.storage.write(NativeMigrationForeground.IDENTITY,pair().raw)
            val pending=File(source.storage.coordinationKey,sha(NativeMigrationForeground.IDENTITY)+".bin"+suffix)
            pending.writeBytes("owned unfinished identity file".toByteArray());val before=tree(source.storage)
            assertTrue(runCatching{NativeMigrationForeground.audit(source.storage)}.isFailure)
            assertTrue(runCatching{export(source.storage)}.isFailure);assertEquals(before,tree(source.storage))
        }
        val source=seed();val receipt=stage(source.storage);owned.storage.write(NativeMigrationForeground.IDENTITY,pair().raw)
        File(owned.storage.coordinationKey,sha(NativeMigrationForeground.IDENTITY)+".bin.new").writeBytes("owned pending destination identity".toByteArray())
        val before=tree(owned.storage);assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)}.isFailure);assertEquals(before,tree(owned.storage))
    }
}
