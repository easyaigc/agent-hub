package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Full ordinary-chat import boundary; actual storage/journal/history, no network or live input. */
class NativeMigrationEasyWorkBusinessTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned EasyWork descriptive business password"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-easywork-business")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun source()=owned.storage.child(ui.activity,"ew-business-source-"+UUID.randomUUID().toString().take(8))
    private fun envelope(storage: NativeSessionStorage)=File(owned.folder,"business-"+UUID.randomUUID()+".encrypted").also {NativeMigrationExport(ui.activity,storage).sealFile(it,password)}
    private fun stage(storage: NativeSessionStorage,destination: NativeSessionStorage=owned.storage)=NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),envelope(storage),password)
    private fun activate(receipt: NativeMigrationCandidateStore.Receipt,destination: NativeSessionStorage=owned.storage)=NativeMigrationActivation(ui.activity,destination,NativeMigrationNotificationActivationLease(destination,receipt){true}).activate(receipt)
    @Test fun activeSourceBecomesExplicitUnpairedCacheAndOriginalImmutableGraph()=runBlocking {
        val source=source();val seed=NativeMigrationEasyWorkBusinessFixtures.seed(ui.activity,source);val before=MigrationActivationFixtures.tree(source);val originals=NativeMigrationEasyWorkBusinessFixtures.operationRows(seed)
        val receipt=stage(source);val candidate=owned.storage.child(ui.activity,"migration-candidates");val candidateBefore=MigrationActivationFixtures.tree(candidate)
        assertTrue(runCatching {NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)}.isFailure)
        activate(receipt);val current=owned.storage.reopen(ui.activity);val metadata=current.metadata()!!;val client=decodeClient(metadata.getJSONArray("clients").getJSONObject(0))
        assertEquals(seed.client.id,client.id);assertTrue(sameScope(seed.client.scope,client.scope));assertNull(client.agentConnection);assertNull(client.codexDirect);assertNotNull(client.migrationEasyWork)
        assertTrue(client.migrationEasyWork!!.requiresPairing);assertEquals("none",client.migrationEasyWork!!.credentialState);assertEquals("lan-only",client.migrationEasyWork!!.preference)
        assertFalse(client.online);assertFalse(client.isConnecting);assertEquals(NativeMigrationEasyWorkGate.STATUS,client.connectionLabel);assertNotEquals(seed.client.readerId,client.readerId)
        assertEquals("later editable draft, never the original submission",metadata.getJSONObject("drafts").getString(client.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD)))
        assertEquals(source.read(seed.fileName),current.read(seed.fileName));assertEquals("Original cached body",NativeHistoryCache(current).read(client.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD))!!.messages.single().text)
        val journal=NativeOperationJournal(current)
        for(row in journal.records(client.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD))+journal.records(client.cacheKey(null))) {
            val original=originals.getValue(row.id);assertTrue(row.migrationReadOnly)
            for(field in listOf("id","kind","target","intent","intentSha256","phase","result","submissionAttempted","sourceKey","sourceVersion"))assertEquals(original.body().get(field),row.body().get(field))
            assertTrue(runCatching {journal.markSubmitting(row.target,row.id)}.isFailure)
        }
        assertEquals(source.read("submission:"+seed.reply.id),current.read("submission:"+seed.reply.id))
        assertEquals(source.read("submission:"+seed.completed.id),current.read("submission:"+seed.completed.id))
        val completed=journal.records(client.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD)).single {it.id==seed.completed.id}
        assertEquals("completed",completed.phase);assertNotNull(completed.result);assertEquals(seed.completed.result,completed.result)
        val readingAudit=JSONObject(current.read(NativeMigrationDeviceState.AUDIT)!!).getJSONArray("entries").getJSONObject(0)
        assertEquals(source.read(NativeMigrationDeviceState.READING),readingAudit.getString("reading"))
        assertEquals(0,JSONObject(current.read(NativeMigrationDeviceState.READING)!!).getJSONArray("scopes").length())
        seed.authority.originalNames.forEach {assertNull(current.read(it))};assertEquals(before,MigrationActivationFixtures.tree(source));assertEquals(candidateBefore,MigrationActivationFixtures.tree(candidate))
        val audit=JSONObject(current.read(NativeMigrationEasyWorkBusiness.RECORD)!!).getJSONArray("clients").getJSONObject(0)
        assertEquals(easyCanonical(encodeClient(seed.client)),easyCanonical(JSONObject(audit.getString("originalClient"))))
        val after=MigrationActivationFixtures.tree(current);NativeMigrationActivation(ui.activity,current).activate(receipt);assertEquals(after,MigrationActivationFixtures.tree(current))
    }
    @Test fun registryAdapterAndTransportStopBeforeAnyImportedReadQueryOrWrite()=runBlocking {
        val source=source();NativeMigrationEasyWorkBusinessFixtures.seed(ui.activity,source);activate(stage(source));val current=owned.storage.reopen(ui.activity);val client=decodeClient(current.metadata()!!.getJSONArray("clients").getJSONObject(0));var opened=0
        val adapter=EasyWorkAgentClient(EasyWorkChannels {opened++;error("Imported target must not resolve a channel")});val registry=AgentRegistry(NativeHostReads {""},NativeCodexCommands {""}).apply {register("easywork",adapter,adapter)}
        assertEquals("EasyWork",registry.agentTitle(client));assertTrue(registry.permissionOptions(client).isNotEmpty())
        val work=listOf<suspend ()->Unit>({registry.workspace(client,null,null)},{registry.history(client,NativeMigrationEasyWorkBusinessFixtures.THREAD,null)},
            {registry.models(client)},{registry.receipt(client,NativeMigrationEasyWorkBusinessFixtures.THREAD,UUID.randomUUID().toString())},
            {registry.send(client,ReplyIntent(client.id,NativeMigrationEasyWorkBusinessFixtures.THREAD,UUID.randomUUID().toString(),"synthetic unsent",emptyList(),"next-turn"))},
            {adapter.workspace(client,null,null)},{adapter.models(client)},{EasyWorkSessions(current,EasyWorkLogin(current)).session(client)},
            {NativeOperationJournal(current).prepare(client,NativeMigrationEasyWorkBusinessFixtures.THREAD,"reply",ReplyIntent(client.id,NativeMigrationEasyWorkBusinessFixtures.THREAD,UUID.randomUUID().toString(),"new blocked intent",emptyList(),"next-turn").body())})
        val before=MigrationActivationFixtures.tree(current)
        for(action in work)assertEquals(NativeMigrationEasyWorkGate.MESSAGE,runCatching {action()}.exceptionOrNull()?.message)
        assertEquals(0,opened);assertEquals(before,MigrationActivationFixtures.tree(current));assertNull(current.read(NativeMigrationEasyWorkOriginals.IDENTITY))
    }
    @Test fun actualSessionRestoresCacheDraftAndDisabledActionsWithoutAutomaticQueries() {
        val source=source();val seed=NativeMigrationEasyWorkBusinessFixtures.seed(ui.activity,source);activate(stage(source));val current=owned.storage.reopen(ui.activity);lateinit var session: MobileSession
        ui.runOnIdle {session=MobileSession(current);session.restore();session.enterForeground()};ui.waitUntil(30_000){!session.restoring&&session.hasClient&&session.messages.any {it.id=="original-message"}}
        ui.runOnIdle {ui.activity.setContent {AgentHubApp(session) { }}}
        ui.onNodeWithTag("readonly-notice").assertIsDisplayed().assertTextContains("Pair again",substring=true)
        ui.onNodeWithTag("message-input").assertIsEnabled()
        try {ui.runOnIdle {
            assertEquals(seed.client.id,session.selectedClient.id);assertNotNull(session.selectedClient.migrationEasyWork);assertEquals(NativeMigrationEasyWorkBusinessFixtures.THREAD,session.selectedThread)
            assertFalse(session.canCreateThread);assertFalse(session.canCreateProjectless);assertFalse(session.selectedClient.online)
            session.refresh(userInitiated=true);assertEquals(NativeMigrationEasyWorkGate.MESSAGE,session.error)
            assertNull(session.loadHistory(userInitiated=true));session.renameEasyWork(session.selectedClient,"Renamed imported cache")
            session.setEasyWorkPreference(session.selectedClient,"relay-only");assertEquals("relay-only",session.selectedClient.migrationEasyWork!!.preference)
            session.text="editable local draft after import";assertEquals("editable local draft after import",session.text)
        }}finally {ui.runOnIdle {session.leaveForeground()}}
        runBlocking {current.awaitQueuedWrites()}
        assertEquals("editable local draft after import",current.metadata()!!.getJSONObject("drafts").getString(seed.client.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD)))
        assertEquals(source.read("submission:"+seed.reply.id),current.read("submission:"+seed.reply.id))
        assertNull(current.read(NativeMigrationEasyWorkOriginals.IDENTITY));assertTrue(NativeMigrationExport(ui.activity,current).capture().isNotEmpty())
    }
    @Test fun corruptedOrActiveDescriptorsAndForeignAccountCacheRefuseAtomically() {
        val source=source();val seed=NativeMigrationEasyWorkBusinessFixtures.seed(ui.activity,source);val store=NativeMigrationCandidateStore(ui.activity,owned.storage);val prior=stage(source);val candidate=owned.storage.child(ui.activity,"migration-candidates");val before=MigrationActivationFixtures.tree(candidate)
        val payload=JSONObject(String(NativeMigrationExport(ui.activity,source).capture(),Charsets.UTF_8))
        val mutations=listOf<(JSONObject)->Unit>(
            {records->val m=JSONObject(records.getString("metadata"));m.getJSONArray("clients").getJSONObject(0).getJSONObject("migrationEasyWork").put("credentialState","active");records.put("metadata",m.toString())},
            {records->val m=JSONObject(records.getString("metadata"));m.getJSONArray("clients").getJSONObject(0).put("agentConnection",seed.client.agentConnection!!.json());records.put("metadata",m.toString())},
            {records->val m=JSONObject(records.getString("metadata"));m.getJSONArray("clients").getJSONObject(0).getJSONObject("migrationEasyWork").put("privateKey","not an allowed descriptor");records.put("metadata",m.toString())},
            {records->val m=JSONObject(records.getString("metadata"));m.getJSONObject("workspaces").getJSONObject(seed.client.cacheKey(null)).getJSONObject("target").getJSONObject("account").put("subject","foreign-account");records.put("metadata",m.toString())},
            {records->val entry=JSONObject(records.getString(NativeMigrationEasyWorkBusiness.RECORD)).getJSONArray("clients").getJSONObject(0);records.remove(NativeMigrationEasyWorkArchives.PREFIX+entry.getString("installationArchiveId")+":"+NativeChatNotificationWire.hash(entry.getString("credentialName")))},
            {records->val old="operations:"+seed.client.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD);val body=JSONObject(records.getString(old));val foreign=seed.client.copy(scope=com.agenthub.core.TargetScope("easywork",seed.client.scope!!.accountAuthority,"foreign-account",seed.client.scope!!.hostId,seed.client.scope!!.workspaceId,seed.client.scope!!.runtimeId),agentConnection=null).cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD);body.put("target",foreign);body.getJSONArray("operations").objects().forEach {it.put("target",foreign)};records.remove(old);records.put("operations:"+foreign,body.toString())},
            {records->records.put(NativeMigrationEasyWorkBusiness.RECORD,JSONObject(records.getString(NativeMigrationEasyWorkBusiness.RECORD)).put("version",2).toString())},
            {records->records.put(NativeMigrationEasyWorkBusiness.RECORD," ".repeat(2*1024*1024+1))})
        for(mutation in mutations) {
            val changed=JSONObject(payload.toString());mutation(changed.getJSONObject("records"));val encrypted=NativeMigrationCrypto.seal(changed.toString().toByteArray(),password)
            assertTrue(runCatching {store.stage(UUID.randomUUID().toString(),encrypted,password,prior)}.isFailure)
            assertEquals(prior,store.currentReceipt());assertEquals(before,MigrationActivationFixtures.tree(candidate))
        }
    }
    @Test fun reexportKeepsOriginalAccountAndDescriptorAfterLocalNameAndPathEdits() {
        val source=source();val seed=NativeMigrationEasyWorkBusinessFixtures.seed(ui.activity,source);activate(stage(source));val current=owned.storage.reopen(ui.activity);val original=requireNotNull(current.read(NativeMigrationEasyWorkBusiness.RECORD))
        val metadata=current.metadata()!!;val row=metadata.getJSONArray("clients").getJSONObject(0);row.put("name","Local imported label");row.put("migrationEasyWork",MigratedEasyWorkConnection.decode(row.getJSONObject("migrationEasyWork")).withPreference("relay-only").json());current.write("metadata",metadata.toString())
        val destination=current.child(ui.activity,"ew-business-reexport-"+UUID.randomUUID().toString().take(8));val receipt=stage(current,destination);activate(receipt,destination);val reopened=destination.reopen(ui.activity)
        assertEquals(original,reopened.read(NativeMigrationEasyWorkBusiness.RECORD));val client=decodeClient(reopened.metadata()!!.getJSONArray("clients").getJSONObject(0));assertEquals("Local imported label",client.name);assertEquals("relay-only",client.migrationEasyWork!!.preference);assertTrue(sameScope(seed.client.scope,client.scope));assertNull(client.agentConnection)
    }
    @Test fun freshPairingModelCannotThawOriginalUncertainOrUnsubmittedOperations()=runBlocking {
        val source=source();val seed=NativeMigrationEasyWorkBusinessFixtures.seed(ui.activity,source);activate(stage(source));val current=owned.storage.reopen(ui.activity);val imported=decodeClient(current.metadata()!!.getJSONArray("clients").getJSONObject(0))
        val fresh=imported.copy(migrationEasyWork=null,agentConnection=seed.client.agentConnection!!.copy(credentialRef="synthetic-new-action-"+UUID.randomUUID()))
        assertNull(NativeMigrationEasyWorkGate.previousCredentialRef(imported));assertNull(NativeMigrationEasyWorkGate.previousCredentialRef(null));assertEquals(fresh.agentConnection!!.credentialRef,NativeMigrationEasyWorkGate.previousCredentialRef(fresh))
        NativeMigrationEasyWorkGate.requireReady(fresh);val journal=NativeOperationJournal(current);val reply=journal.records(imported.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD)).single {it.id==seed.reply.id}
        assertTrue(reply.migrationReadOnly);assertTrue(runCatching {journal.markSubmitting(reply.target,reply.id)}.isFailure)
        val creation=journal.records(imported.cacheKey(null)).single();assertTrue(creation.migrationReadOnly);assertTrue(runCatching {journal.markPreparing(creation.target,creation.id)}.isFailure)
        assertEquals(seed.reply.intent,reply.intent);assertEquals(seed.creation.intent,creation.intent);assertNotEquals(seed.client.agentConnection!!.credentialRef,fresh.agentConnection!!.credentialRef)
    }
}
