package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual Session quiescence and next-migration gate; intercepted OkHttp never opens a socket. */
class NativeMigrationEasyWorkIoLifecycleTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var previous: MainActivity.AcceptanceSessionState
    @Before fun setup() {
        ui.waitUntil(30_000){!ui.activity.session.restoring}
        ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"migration-easywork-actual-io");owned.session.restore()}
        ui.waitUntil(30_000){!owned.session.restoring&&owned.session.phoneReading.ready}
        ui.runOnIdle {previous=ui.activity.adoptSessionForAcceptance(owned.session)}
        ui.waitUntil(30_000){ui.activity.session.migrationReady}
    }
    @After fun finish(){if(::previous.isInitialized)ui.runOnIdle{ui.activity.restoreSessionForAcceptance(previous);owned.verifyDefault()}}
    @Test fun cancelledActualSessionWaitKeepsDraftAndBlocksSecondMigrationUntilCallbackCloseExits()=runBlocking {
        val original=ui.activity.session;val body=NativeMigrationHeldResponse()
        val client=EasyWorkHttp.isolatedClient(OkHttpClient.Builder().addInterceptor {chain->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(body).build()
        })
        val field=MobileSession::class.java.getDeclaredField("tasks").apply {isAccessible=true}
        val actualScope=field.get(original) as CoroutineScope
        val reader=actualScope.launch {EasyWorkHttp.request(client,"https://synthetic.invalid/migration")}
        var migration: Deferred<NativeMigrationOwnerLease>?=null
        try {
            withContext(Dispatchers.IO){check(body.reading.await(5,TimeUnit.SECONDS))}
            ui.runOnIdle {original.text="unsaved draft with an actual callback still alive"}
            migration=async(Dispatchers.Main.immediate){original.quiesceForMigration(UUID.randomUUID().toString())}
            ui.waitUntil(5_000){original.migrationPaused&&!reader.isActive}
            assertFalse(migration.isCompleted);assertNull(owned.storage.migrationPublicationReceipt())
            migration.cancelAndJoin()
            ui.runOnIdle {
                assertSame(original,ui.activity.session);assertFalse(original.migrationPaused);assertFalse(original.migrationReady)
                assertEquals("unsaved draft with an actual callback still alive",original.text)
            }
            val second=withContext(Dispatchers.Main.immediate){runCatching {original.quiesceForMigration(UUID.randomUUID().toString())}}
            assertTrue(second.isFailure);assertNull(owned.storage.migrationPublicationReceipt())
            body.releaseRead.countDown()
            withContext(Dispatchers.IO){check(body.closing.await(5,TimeUnit.SECONDS))}
            ui.runOnIdle {assertFalse(original.migrationReady)}
            body.releaseClose.countDown()
            ui.waitUntil(10_000){original.migrationReady}
            ui.runOnIdle {assertSame(original,ui.activity.session);assertEquals("unsaved draft with an actual callback still alive",original.text)}
            assertNull(owned.storage.migrationPublicationReceipt());assertEquals(1,body.closeCount.get())
        }finally {
            body.releaseRead.countDown();body.releaseClose.countDown()
            migration?.cancel();reader.cancel();EasyWorkHttp.dispose(client)
        }
    }
}
