package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Actual P9 producer/retainer is the oracle, never this migration wrapper's own fixture formula. */
class NativeMigrationAuthorityReaderTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-authority-reader")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun companion(type: Class<*>)=type.getField("Companion").get(null)
    private fun producerValidate(raw: String) {
        val type=Class.forName("com.agenthub.android.NativeChatNotificationAuthorizations")
        companion(type).javaClass.getDeclaredMethod("validateEncoded",String::class.java).invoke(companion(type),raw)
    }
    private fun produced(storage: NativeSessionStorage): String {
        val client=MigrationActivationFixtures.client(MigrationActivationFixtures.payload())
        val saved=Class.forName("com.agenthub.android.NativeChatNotificationSavedConnection");val factory=companion(saved)
        val original=factory.javaClass.getDeclaredMethod("capture",MobileClient::class.java,String::class.java)
            .invoke(factory,client,"synthetic-authority-reader-"+UUID.randomUUID())
        val type=Class.forName("com.agenthub.android.NativeChatNotificationAuthorizations")
        val retainer=type.getDeclaredConstructor(NativeSessionStorage::class.java).newInstance(storage)
        type.getDeclaredMethod("retain",saved).invoke(retainer,original)
        return requireNotNull(storage.read(NativeMigrationForeground.AUTHORIZATIONS))
    }
    @Test fun actualProducerSnapshotPassesPureWrapperWithoutInitializingIdentityOrRewritingCredential()=runBlocking {
        withContext(Dispatchers.IO) {
            val storage=owned.storage.child(ui.activity,"ar-"+UUID.randomUUID().toString().take(8));val raw=produced(storage);producerValidate(raw)
            val before=MigrationActivationFixtures.tree(storage);val row=JSONObject(raw).getJSONArray("authorizations").getJSONObject(0)
            val result=NativeMigrationNotificationAuthorizations.validate(raw)
            assertEquals(setOf(row.getString("authorization")),result.keys)
            assertEquals(NativeChatNoticeTarget.from(decodeClient(row.getJSONObject("client")).scope!!),result.values.single())
            assertEquals(raw,storage.read(NativeMigrationForeground.AUTHORIZATIONS));assertEquals(before,MigrationActivationFixtures.tree(storage))
            assertNull(storage.read(NativeMigrationForeground.IDENTITY));assertNull(storage.read(NativeMigrationForeground.JOURNAL));assertNull(storage.read(NativeMigrationForeground.RECOVERY))
        }
    }
    @Test fun independentProducerAndWrapperRejectChangedAuthorityAndAcceptDisplayOnlyChangeWithoutWeakeningSharedMetadata()=runBlocking {
        withContext(Dispatchers.IO) {
            val storage=owned.storage.child(ui.activity,"ar-"+UUID.randomUUID().toString().take(8));val original=produced(storage);val before=MigrationActivationFixtures.tree(storage)
            val changes=listOf<(JSONObject,JSONObject)->Unit>(
                {root,_->root.put("version",2)}, {root,_->root.put("future",true)},
                {_,row->row.put("credential","changed-original-credential-"+UUID.randomUUID())},
                {_,row->row.put("epoch","0".repeat(64))}, {_,row->row.put("authorization","0".repeat(64))},
                {_,row->row.getJSONObject("client").put("id","")},
                {_,row->row.getJSONObject("client").getJSONObject("scope").put("runtimeId","other-runtime")})
            for(change in changes) {
                val root=JSONObject(original);change(root,root.getJSONArray("authorizations").getJSONObject(0));val raw=root.toString()
                assertTrue(runCatching {producerValidate(raw)}.isFailure);assertTrue(runCatching {NativeMigrationNotificationAuthorizations.validate(raw)}.isFailure)
                assertEquals(before,MigrationActivationFixtures.tree(storage))
            }
            val display=JSONObject(original);display.getJSONArray("authorizations").getJSONObject(0).getJSONObject("client").put("name","renamed display 中文 😀")
            producerValidate(display.toString());assertEquals(NativeMigrationNotificationAuthorizations.validate(original),NativeMigrationNotificationAuthorizations.validate(display.toString()))
            val invalidContext=MigrationActivationFixtures.payload().getJSONObject("records").getString("metadata").let(::JSONObject).put("selected","")
            assertTrue(runCatching {NativeMigrationMetadata.validate(invalidContext)}.isFailure);assertEquals(original,storage.read(NativeMigrationForeground.AUTHORIZATIONS));assertEquals(before,MigrationActivationFixtures.tree(storage))
        }
    }
}
