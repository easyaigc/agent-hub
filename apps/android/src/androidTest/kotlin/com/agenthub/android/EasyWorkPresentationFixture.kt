package com.agenthub.android

import android.content.Context
import com.agenthub.core.TargetScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

/** Test-owned synthetic input only. No restore, selected live client, discovery or provider. */
internal class EasyWorkPresentationFixture(private val context: Context,val id: String) {
    val store=NativeSessionStorage(context,"p8-presentation-$id","p8-presentation-$id")
    lateinit var session: MobileSession
    lateinit var client: MobileClient
    val originalDraft="Independent presentation draft 中文 😀 %@"
    val scope=TargetScope("easywork","https://issuer.presentation.invalid/"+"a".repeat(36),"account-%@-中文-"+"s".repeat(40),"host-"+"h".repeat(40),"workspace-"+"w".repeat(40),"runtime-"+"r".repeat(40))
    val credentialName="easywork-credential:p8-presentation-$id"
    private val credential="Inert synthetic original credential; must never be decoded or used"
    private lateinit var journalName: String
    private lateinit var journalBytes: String
    private lateinit var operationId: String
    private lateinit var intentSha256: String
    private var identity: String?=null
    private var permissions=emptyMap<String,Int>()
    fun initializeModel() {
        val connection=AgentConnection(scope,listOf(AgentCandidate("direct","lan-wss","https://192.0.2.6:7443","a".repeat(64)),AgentCandidate("relay","relay-http","https://relay.presentation.invalid")),"p8-presentation-$id")
        client=MobileClient("presentation-$id","Saved computer 中文 %@ "+"name ".repeat(9).trim(),scope,connection.candidates.first().endpoint,false,"easywork-runtime",false,requiresReadTarget=true,agentConnection=connection)
        session=MobileSession(store);session.resetAcceptance();session.leaveForeground();clients().add(client);session.text=originalDraft
        assertNull(session.easyWorkDiscovery);assertTrue(session.selectedClient.isDemo)
    }
    fun initializeOriginals() {
        store.write(credentialName,credential);identity=store.read("easywork-device-identity")
        permissions=listOf(android.Manifest.permission.CAMERA,CodexHostDiscovery.LOCAL_PERMISSION).associateWith {context.checkSelfPermission(it)}
        val journal=NativeOperationJournal(store)
        val record=runBlocking {
            val prepared=journal.prepare(client,"presentation-owned-thread","reply",ReplyIntent(client.id,"presentation-owned-thread",java.util.UUID.randomUUID().toString(),"Synthetic immutable unknown input",emptyList(),"next-turn").body())
            journal.markSubmitting(prepared.target,prepared.id);journal.uncertain(prepared.target,prepared.id,"Synthetic unknown acknowledgement")
        }
        journalName="operations:"+record.target;journalBytes=checkNotNull(store.read(journalName));operationId=record.id;intentSha256=record.intentSha256
    }
    @Suppress("UNCHECKED_CAST") private fun clients()=MobileSession::class.java.getDeclaredField("realClients").apply {isAccessible=true}.get(session) as MutableList<MobileClient>
    fun live()=session.clients.single {it.id==client.id}
    fun imported() {
        val marker=MigratedEasyWorkConnection.decode(JSONObject().put("version",1).put("provider","easywork").put("archiveId","9".repeat(64))
            .put("target",CodexCommandCodec.target(scope)).put("requiresPairing",true).put("credentialState","none").put("preference","relay-only")
            .put("candidateOrigins",JSONArray(listOf("https://relay.presentation.invalid"))))
        client=client.copy(agentConnection=null,codexDirect=null,storageIdentity=null,requiresReadTarget=true,migrationEasyWork=marker)
        clients().clear();clients().add(client)
        val journal=JSONObject(journalBytes);journal.getJSONArray("operations").getJSONObject(0).put("migrationReadOnly",true)
        journalBytes=journal.toString();store.write(journalName,journalBytes);persist()
    }
    fun persist(){MobileSession::class.java.getDeclaredMethod("persistMetadata").apply {isAccessible=true}.invoke(session)}
    fun noActivation() {
        assertTrue(session.selectedClient.isDemo);assertEquals(originalDraft,session.text);assertNull(session.easyWorkDiscovery)
        val pool=MobileSession::class.java.getDeclaredField("easyWorkSessions").apply {isAccessible=true}.get(session)!!
        val entries=EasyWorkSessions::class.java.getDeclaredField("sessions").apply {isAccessible=true}.get(pool) as Map<*,*>
        assertTrue(entries.isEmpty())
        assertEquals(fields(scope),fields(live().scope!!))
        live().agentConnection?.let {assertEquals("p8-presentation-$id",it.credentialRef);assertEquals(listOf("direct","relay"),it.candidates.map {c->c.id})}
    }
    fun protectedBytes(): JSONObject {
        runBlocking {store.awaitQueuedWrites()}
        assertEquals(credential,store.read(credentialName));assertEquals(identity,store.read("easywork-device-identity"));assertEquals(journalBytes,store.read(journalName))
        assertEquals(permissions,permissions.keys.associateWith {context.checkSelfPermission(it)})
        return JSONObject().put("originalOperationId",operationId).put("originalIntentSha256",intentSha256).put("journalSha256",easyHash(journalBytes.toByteArray()))
            .put("credentialSha256",easyHash(credential.toByteArray())).put("identityUnchanged",true).put("permissionsUnchanged",true)
    }
    companion object {
        fun fields(scope: TargetScope)=listOf(scope.agentId,scope.accountAuthority.orEmpty(),scope.accountSubject.orEmpty(),scope.hostId,scope.workspaceId,scope.runtimeId)
        fun hash(file: File)=file.inputStream().use {input->val d=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString("") {"%02x".format(it)}}
        fun defaults(context: Context): Map<String,String> {
            val base=context.noBackupFilesDir.toPath();if(!Files.exists(base))return emptyMap();val result=linkedMapOf<String,String>();var roots=0
            Files.newDirectoryStream(base,"mobile-v1*").use {entries->for(root in entries) {
                val n=root.fileName.toString();if(n!="mobile-v1"&&!n.startsWith("mobile-v1-"))continue
                check(++roots<=64);check(!Files.isSymbolicLink(root))
                Files.walk(root).use {walk->val it=walk.iterator();while(it.hasNext()){val path=it.next();check(!Files.isSymbolicLink(path));if(!Files.isRegularFile(path))continue;check(result.size<1024);result[base.relativize(path).toString()]=hash(path.toFile())}}
            }}
            check(easyCanonical(JSONObject(result)).toByteArray().size<=262144);return result
        }
    }
}
