package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Current-generation registry boundary. No claim about all historical/global installation stores. */
class NativeMigrationScopeRegistryTest {
    private class Fixture {
        val f=MigrationInboxFixture().also {it.fresh();it.seedRoot()}
        private val files=File(f.folder,"owned-scope-files").also {check(it.mkdir());android.system.Os.chmod(it.path,0x1c0)}
        val context=object:ContextWrapper(f.context("source")){override fun getFilesDir()=files;override fun getApplicationContext(): Context=this}
        val root=f.storage("source")
        val output=f.envelope("registered-output")
        fun export(beforePublication: ()->Unit={})=NativeMigrationExport(context,root).sealFile(output,f.password,beforePublication=beforePublication)
        fun unknown(suffix: String)=File(root.coordinationKey+"-"+suffix)
    }
    @Test fun entirelyUnregisteredEmptyAndNonemptyNodesRefuseWithoutOmittingOrChangingOriginalBackup() {
        val f=Fixture();f.output.writeText("original destination bytes")
        for(nonempty in listOf(false,true)) {
            val folder=f.unknown("future-origin");check(folder.mkdir());android.system.Os.chmod(folder.path,0x1c0)
            val original=File(folder,"original-owned.txt");if(nonempty){original.writeText("original durable material");android.system.Os.chmod(original.path,0x180)}
            val before=MigrationActivationFixtures.tree(f.root)
            val error=runCatching {f.export()}.exceptionOrNull();assertTrue(error is NativeMigrationExport.IncompleteScope)
            assertEquals(setOf("unregistered-child"),(error as NativeMigrationExport.IncompleteScope).scopes)
            assertEquals("original destination bytes",f.output.readText());assertEquals(before,MigrationActivationFixtures.tree(f.root))
            if(nonempty){assertEquals("original durable material",original.readText());check(original.delete())};check(folder.delete())
        }
    }
    @Test fun lateUnknownNodeAndStagingPrefixImpostorFailTheFinalPublicationFence() {
        val f=Fixture();f.output.writeText("original destination bytes")
        // Only the exact current staging directory is excluded, regardless of an empty node.
        val staging=f.unknown("migration-candidates");check(staging.mkdir());android.system.Os.chmod(staging.path,0x1c0)
        assertTrue(f.root.migrationPendingChildScopes().isEmpty())
        val impostor=f.unknown("migration-candidates-future");check(impostor.mkdir());android.system.Os.chmod(impostor.path,0x1c0)
        assertEquals(setOf("unregistered-child"),f.root.migrationPendingChildScopes());check(impostor.delete())
        val late=f.unknown("future-origin.new")
        val error=runCatching {f.export {late.writeText("late owned source");android.system.Os.chmod(late.path,0x180)}}.exceptionOrNull()
        assertTrue(error is NativeMigrationExport.IncompleteScope);assertEquals("late owned source",late.readText());assertEquals("original destination bytes",f.output.readText())
    }
    @Test fun pendingRootPointerIsRefusedBeforeAnyAtomicRecoveryDuringRegistryAudit() {
        val f=Fixture();val pointer=File(f.root.coordinationKey,NativeMigrationInertArchive.sha("migration-active-generation-v1".toByteArray())+".bin")
        val backup=File(pointer.path+".bak");backup.writeText("owned pending pointer fixture");android.system.Os.chmod(backup.path,0x180)
        val before=MigrationActivationFixtures.tree(f.root)
        assertTrue(runCatching {f.root.migrationPendingChildScopes()}.isFailure)
        assertEquals(before,MigrationActivationFixtures.tree(f.root));assertFalse(pointer.exists());assertEquals("owned pending pointer fixture",backup.readText())
    }
}
