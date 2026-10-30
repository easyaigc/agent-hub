package com.agenthub.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Native Keystore/AtomicFile selectors, not executed by the P7 source-only worker. */
class NativeSelectionReturnStorageTest {
    private fun event(name: String)=NativeSelectionReturnAudit.capture("owned-original-selection",NativeMigrationSelectionJournal.Kind.DOCUMENT,InboxContractFixture.id(name),null,sources=listOf("content://owned.invalid/$name"))
    private fun index(root: NativeSessionStorage)=File(root.coordinationKey,NativeMigrationInertArchive.sha(NativeSelectionReturnAudit.RECORD.toByteArray())+".bin")
    @Test fun lateNativeAuditAfterPublicationUsesCurrentRootWhileOrdinaryRetiredWritesStayClosed() {
        val f=MigrationInboxFixture().also {it.fresh()};val context=f.context("destination");val old=f.storage("destination")
        old.write("metadata",JSONObject().put("clients",org.json.JSONArray()).toString())
        val first=event("before-publication");assertTrue(old.persistSelectionReturn(context,first));val oldFile=index(old);val oldBytes=oldFile.readBytes()
        val payload=MigrationActivationFixtures.payload();val source=NativeSelectionReturnAudit.admit(null,event("source-audit")).raw
        payload.getJSONObject("records").put(NativeSelectionReturnAudit.RECORD,source)
        val receipt=MigrationActivationFixtures.stage(context,old,payload);NativeMigrationActivation(context,old).activate(receipt)
        old.retireWriter();val late=event("after-publication");assertTrue(old.persistSelectionReturn(context,late))
        val fresh=old.reopen(context);val audit=NativeSelectionReturnAudit.read(fresh.read(NativeSelectionReturnAudit.RECORD))
        assertEquals(setOf(first.eventId,event("source-audit").eventId,late.eventId),audit.map {it.eventId}.toSet())
        assertArrayEquals(oldBytes,oldFile.readBytes());assertThrows(IllegalStateException::class.java){old.write("metadata","old owner cannot write")}
        val before=MigrationActivationFixtures.tree(fresh);assertEquals(NativeMigrationActivation(context,fresh).activate(receipt),fresh.migrationPublicationReceipt()!!.let {fresh.publishedMigration(it.id,it.sha256,it.recordCount)})
        assertEquals(before,MigrationActivationFixtures.tree(fresh));fresh.retireWriter()
    }
    @Test fun pendingSidecarAndMissingExistingKeyPreserveCiphertextWithoutFallbackOrAuditReplay() {
        val f=MigrationInboxFixture().also {it.fresh()};val context=f.context("source");val root=f.storage("source")
        root.write("metadata","{}");assertTrue(root.persistSelectionReturn(context,event("original")));val file=index(root)
        for(suffix in listOf(".bak",".new")) {
            val sidecar=File(file.path+suffix);sidecar.writeText("owned retained pending audit");android.system.Os.chmod(sidecar.path,0x180)
            val before=MigrationActivationFixtures.tree(root);assertTrue(runCatching {root.persistSelectionReturn(context,event("pending-$suffix"))}.isFailure)
            assertEquals(before,MigrationActivationFixtures.tree(root));check(sidecar.delete())
        }
        val before=MigrationActivationFixtures.tree(root);val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)};keys.deleteEntry(f.alias("source"))
        assertTrue(runCatching {root.persistSelectionReturn(context,event("missing-key"))}.isFailure)
        assertFalse(keys.containsAlias(f.alias("source")));assertEquals(before,MigrationActivationFixtures.tree(root));root.retireWriter()
    }
}
