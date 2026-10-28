package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real Keystore/filesystem inventory never silently drops an unsupported or unknown record. */
class NativeMigrationInventoryTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-inventory")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun fails(names: Set<String>,maximum: Int=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES){assertTrue(runCatching{owned.storage.migrationSnapshot(names,maximum)}.exceptionOrNull() is NativeSessionStorage.IncompleteMigrationInventory)}
    private fun physical(name: String)=File(owned.storage.coordinationKey,MigrationActivationFixtures.sha(name.toByteArray())+".bin")
    @Test fun everyActiveRecordMustBeNamedAndOriginalStringsAndCiphertextRemainExact(){
        val records=mapOf("metadata" to " \n{\"draft\":\"100% %@ 😀\"}\n ","operations:original" to "original immutable unknown operation","draft-file:original" to "original bytes")
        records.forEach{(name,text)->owned.storage.write(name,text)};val before=MigrationActivationFixtures.tree(owned.storage)
        assertEquals(records,owned.storage.migrationSnapshot(records.keys));fails(records.keys-"operations:original");fails(records.keys+"missing record")
        assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun snapshotCannotMixTwoSerializedMultiRecordCommits()=runBlocking {
        val store=owned.storage;store.write("metadata","0");store.write("operations:revision","0")
        val writer=async(Dispatchers.IO){repeat(40){revision->store.operationTransaction{store.write("metadata",revision.toString());store.write("operations:revision",revision.toString())}}}
        val reader=async(Dispatchers.IO){repeat(40){val current=store.migrationSnapshot(setOf("metadata","operations:revision"));assertEquals(current.getValue("metadata"),current.getValue("operations:revision"))}}
        writer.await();reader.await();assertEquals("39",store.migrationSnapshot(setOf("metadata","operations:revision")).getValue("metadata"))
    }
    @Test fun corruptAndUnfinishedCiphertextRejectWithoutRecoveryOrDeletion(){
        val store=owned.storage;store.write("metadata","original local draft");val file=physical("metadata");val bytes=file.readBytes();file.writeBytes(bytes.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})
        var before=MigrationActivationFixtures.tree(store);fails(setOf("metadata"));assertEquals(before,MigrationActivationFixtures.tree(store));file.writeBytes(bytes)
        for(suffix in listOf(".bak",".new")){val incomplete=File(file.path+suffix);incomplete.writeText("owned unfinished atomic write");before=MigrationActivationFixtures.tree(store);fails(setOf("metadata"));assertEquals(before,MigrationActivationFixtures.tree(store));assertTrue(incomplete.delete())}
        assertEquals("original local draft",store.read("metadata"))
    }
    @Test fun unknownFileAndEscapingSymlinkAreNotExportedOrRemoved(){
        val store=owned.storage;store.write("metadata","original");val unknown=File(store.coordinationKey,"unknown-owned.bin");unknown.writeText("unknown original record");val before=MigrationActivationFixtures.tree(store)
        fails(setOf("metadata"));assertEquals(before,MigrationActivationFixtures.tree(store));assertTrue(unknown.delete())
        val outside=File(ui.activity.cacheDir,"owned-migration-outside-${UUID.randomUUID()}");outside.writeText("independent synthetic file");val link=File(store.coordinationKey,"owned-link")
        try{android.system.Os.symlink(outside.path,link.path);fails(setOf("metadata"));assertEquals("independent synthetic file",outside.readText());assertTrue(link.exists())}
        finally{if(java.nio.file.Files.isSymbolicLink(link.toPath()))assertTrue(link.delete());assertTrue(outside.delete())}
    }
    @Test fun onlyThePublishedGenerationIsActiveAndOldRootIsPreserved(){
        val old=owned.storage;old.write("metadata","previous installation");old.write("old-only","original inactive record");val original=MigrationActivationFixtures.tree(old)
        val next=mapOf("metadata" to "new installation","operations:original" to "same original immutable input")
        old.publishMigration(UUID.randomUUID().toString(),"a".repeat(64),next);val current=old.reopen(ui.activity)
        assertEquals(next,current.migrationSnapshot(next.keys));assertNull(current.read("old-only"));assertTrue(runCatching{current.migrationSnapshot(next.keys+"old-only")}.isFailure)
        val tree=MigrationActivationFixtures.tree(current);for((name,digest)in original)assertEquals(digest,tree[name])
        assertTrue(runCatching{old.migrationSnapshot(next.keys)}.isFailure)
    }
    @Test fun binaryAndQuotaBoundariesCannotBecomeAnIncompleteSuccessfulExport(){
        val store=owned.storage;store.write("metadata","x".repeat(128));val before=MigrationActivationFixtures.tree(store);fails(setOf("metadata"),127);assertEquals(before,MigrationActivationFixtures.tree(store))
        val audio=File(ui.activity.cacheDir,"owned-migration-audio-${UUID.randomUUID()}");audio.writeBytes(ByteArray(128){it.toByte()})
        try{store.writeBinary("voice-audio:owned",audio);val encrypted=MigrationActivationFixtures.tree(store);fails(setOf("metadata"));fails(setOf("metadata","voice-audio:owned"));assertEquals(encrypted,MigrationActivationFixtures.tree(store))}finally{assertTrue(audio.delete())}
    }
}
