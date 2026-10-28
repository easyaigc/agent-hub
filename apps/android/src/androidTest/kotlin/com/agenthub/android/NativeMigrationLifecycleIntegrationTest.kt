package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Actual Activity/controller/Session/ledger boundary. Synthetic file data; no real Host/model. */
class NativeMigrationLifecycleIntegrationTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var previous: MainActivity.AcceptanceSessionState
    @Before fun setup() {
        ui.waitUntil(30_000){!ui.activity.session.restoring}
        ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"migration-system-lifecycle");owned.session.restore()}
        ui.waitUntil(30_000){!owned.session.restoring&&owned.session.phoneReading.ready}
        ui.runOnIdle {previous=ui.activity.adoptSessionForAcceptance(owned.session)}
        ui.waitUntil(30_000){ui.activity.session.migrationReady}
    }
    @After fun finish(){if(::previous.isInitialized)ui.runOnIdle{ui.activity.restoreSessionForAcceptance(previous);owned.verifyDefault()}}
    @Test fun actualConfirmationPublishesOnceRestoresFreshStorageAndBindsTheSameReadingLedger() {
        val source=owned.storage.child(ui.activity,"ui-source")
        val payload=MigrationActivationFixtures.payload();val rows=payload.getJSONObject("records")
        rows.keys().forEach {source.write(it,rows.getString(it))}
        val client=MigrationActivationFixtures.client(payload);val pair=NotificationArchiveFixtures.identity();val authority=NotificationArchiveFixtures.authority(client)
        source.write(NativeMigrationForeground.IDENTITY,pair.raw)
        source.write(NativeMigrationForeground.JOURNAL,NotificationArchiveFixtures.v1(pair,NativeChatNoticeTarget.from(client.scope!!),authority.fingerprint))
        source.write(NativeMigrationForeground.AUTHORIZATIONS,authority.raw)
        val sourceTree=MigrationActivationFixtures.tree(source)
        val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stage(UUID.randomUUID().toString(),NativeMigrationExport(ui.activity,source).seal("owned actual entry password"),"owned actual entry password")
        ui.runOnIdle {ui.activity.migration.show()}
        ui.waitUntil(30_000){ui.activity.migration.phase==NativeMigrationPhase.PREVIEW}
        ui.onNodeWithTag("migration-preview").assertExists()
        assertNull(owned.storage.migrationPublicationReceipt())
        val original=ui.activity.session;val ledger=original.phoneReading.ledger
        val retained=ui.activity.migration
        ui.activityRule.scenario.recreate()
        ui.waitUntil(30_000){ui.activity.migration.phase==NativeMigrationPhase.PREVIEW&&ui.activity.session.migrationReady}
        assertSame(retained,ui.activity.migration);assertSame(original,ui.activity.session)
        ui.onNodeWithTag("migration-confirm").performScrollTo().performClick()
        ui.waitUntil(30_000){ui.activity.migration.phase==NativeMigrationPhase.DONE}
        ui.runOnIdle {
            val fresh=ui.activity.session;assertNotSame(original,fresh);assertNotSame(ledger,fresh.phoneReading.ledger)
            assertNotSame(owned.storage,fresh.migrationStorage);assertTrue(fresh.phoneReading.ready)
            assertEquals(receipt,fresh.migrationStorage.migrationPublicationReceipt())
            assertTrue(runCatching {owned.storage.write("metadata","late retired UI write")}.isFailure)
            val coordinator=requireNotNull(fresh.chatNotifications)
            val portField=coordinator.javaClass.getDeclaredField("session").apply {isAccessible=true}
            val reading=(portField.get(coordinator) as NativeChatNotificationSessionPort).reading
            assertTrue(reading.javaClass.declaredFields.any {field->field.isAccessible=true;field.get(reading)===fresh.phoneReading.ledger})
            val gateField=coordinator.javaClass.getDeclaredField("migrationEnrollment").apply {isAccessible=true}
            assertFalse((gateField.get(coordinator) as NativeMigrationNotificationEnrollment).allowed)
            assertNull(fresh.migrationStorage.read(NativeMigrationForeground.IDENTITY));assertNull(fresh.migrationStorage.read(NativeMigrationForeground.AUTHORIZATIONS))
        }
        assertEquals(sourceTree,MigrationActivationFixtures.tree(source))
        val current=ui.activity.session.migrationStorage;val after=MigrationActivationFixtures.tree(current)
        assertEquals(receipt,current.migrationPublicationReceipt());assertEquals(after,MigrationActivationFixtures.tree(current))
    }
    @Test fun actualQuiescenceFailureKeepsOriginalSessionAndUnsavedDraftInMemory()=runBlocking {
        val original=ui.activity.session;val generation=owned.storage.beginRestore();val failed=CompletableDeferred<Unit>()
        ui.runOnIdle {original.text="unsaved draft survives a failed migration boundary"}
        owned.storage.enqueue("metadata","blocked owned write"){failed.complete(Unit)}
        withTimeout(10_000){failed.await()}
        val outcome=withContext(Dispatchers.Main.immediate){runCatching {original.quiesceForMigration(UUID.randomUUID().toString())}}
        assertTrue(outcome.isFailure)
        ui.runOnIdle {assertSame(original,ui.activity.session);assertFalse(original.migrationPaused);assertEquals("unsaved draft survives a failed migration boundary",original.text)}
        owned.storage.finishRestore(generation){}
    }
}
