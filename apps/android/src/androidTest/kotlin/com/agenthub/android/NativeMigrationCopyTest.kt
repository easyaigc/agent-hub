package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real native catalogs and accessibility semantics. Password/target values never become catalog keys. */
class NativeMigrationCopyTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-copy")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    @Test fun catalogUsesExistingKeysAndFormatsCountsWithLiteralArguments() {
        val english=NativeL10n(ui.activity,"en");val chinese=NativeL10n(ui.activity,"zh-Hans")
        assertEquals("Secure migration",english.tr("安全迁移"))
        assertEquals("迁移尚未就绪。",chinese.tr("迁移尚未就绪。"));assertEquals("Migration is not ready.",english.tr("迁移尚未就绪。"))
        val format="%@ 台电脑 · %@ 个草稿 · %@ 条操作与回执 · %@ 个附件"
        assertEquals("2 computers · 3 drafts · 4 operations and receipts · 5 attachments",english.tr(format,2,3,4,5))
        val source="source-通知-%@-100%"
        assertEquals("$source computers · 3 drafts · 4 operations and receipts · 5 attachments",english.tr(format,source,3,4,5))
        assertEquals("2 台电脑 · 3 个草稿 · 4 条操作与回执 · 5 个附件",chinese.tr(format,2,3,4,5))
        assertEquals("Enable notifications explicitly after migration. Original records are retained.",english.tr("迁移后需明确开启通知，原记录已保留。"))
    }
    @Test fun englishNativePageHasMaskedPasswordAndAccessibleCatalogActions() {
        val controller=NativeMigrationController(ui.activity)
        val host=object:NativeMigrationLifecycle {
            override val ready=true;override val canImport=true
            override fun currentStorage()=owned.storage
            override suspend fun quiesce(operationId: String): NativeMigrationOwnerLease=error("No migration is submitted by copy test")
            override suspend fun restore(lease: NativeMigrationOwnerLease,requiresExplicitEnrollment: Boolean)=error("No restore is submitted by copy test")
        }
        ui.runOnIdle {controller.attach(host);ui.activity.setContent {ProductTextProvider("en"){MaterialTheme{NativeMigrationPage(controller)}}}}
        ui.onNodeWithTag("migration-password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assertTextContains("Migration password (at least 12 characters)")
        ui.onNodeWithTag("migration-export").assertHasClickAction().assertIsNotEnabled().assertTextContains("Export encrypted migration file")
        ui.onNodeWithTag("migration-import").assertHasClickAction().assertIsNotEnabled().assertTextContains("Import encrypted migration file")
        val snapshot=MigrationActivationFixtures.tree(owned.storage)
        ui.runOnIdle {controller.password="owned transient password"}
        ui.onNodeWithTag("migration-export").assertIsEnabled();ui.onNodeWithTag("migration-import").assertIsEnabled()
        ui.onNodeWithTag("migration-cancel").performScrollTo().assertHasClickAction().performClick()
        ui.runOnIdle {assertEquals("",controller.password);assertFalse(controller.presented)}
        assertEquals(snapshot,MigrationActivationFixtures.tree(owned.storage));assertNull(owned.storage.read("migration-password"))
    }
}
