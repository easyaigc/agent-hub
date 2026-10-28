package com.agenthub.android

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.util.UUID

/** Owned native acceptance utility. Only fixed loopback control operations and namespaced files. */
internal class P8CommitAcceptance(val id: String=UUID.randomUUID().toString(),val reader: Boolean=false) {
    val i=InstrumentationRegistry.getInstrumentation()
    val args=InstrumentationRegistry.getArguments()
    val config=JSONObject(requireNotNull(args.getString("p8CommitConfig")))
    val build=JSONObject(requireNotNull(args.getString("p8CommitBuild")))
    val namespace="p8-positive-commit-$id"
    val alias="p8-positive-commit-$id"
    val folder=File(i.targetContext.filesDir,"acceptance/p8-positive-commit/$id")
    val origin=config.getString("authOrigin")
    val asset=i.context.assets.open("easywork-crypto-vector.json").use {it.readBytes()}
    lateinit var storage: NativeSessionStorage
    lateinit var session: MobileSession
    lateinit var original: MobileClient
    lateinit var originalTarget: String
    lateinit var journalKey: String
    lateinit var journalBytes: String
    val originalText="P8 synthetic original unsent draft 中文 %@ $id"
    var defaults=emptyMap<String,String>()
    var started=false
    lateinit var claimKey: String
    lateinit var claimBytes: String
    lateinit var grantRef: String
    lateinit var grantBytes: String
    lateinit var binding: EasyWorkBinding
    init {
        guard();require(UUID.fromString(id).toString()==id)
        require(config.getInt("version")==1&&config.getString("fixture")=="p8-synthetic-positive-commit-v1")
        for(k in listOf("authOrigin","controlOrigin")){val u=URI(config.getString(k));require(u.scheme=="http"&&u.host=="127.0.0.1"&&u.port in 1..65535&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null&&u.rawPath.isNullOrEmpty())}
        require(URI(origin).port!=URI(config.getString("controlOrigin")).port)
        require(config.getString("sourceFixtureSha256")==easyHash(asset))
        require(config.getString("sourceGraphSha256")==easyHash(easyCanonical(config.getJSONObject("sourceGraph")).toByteArray()))
        for(k in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(k).matches(Regex("[a-f0-9]{64}")))
        require(build.getString("appSha256")==hash(File(i.targetContext.applicationInfo.sourceDir)));require(build.getString("runnerSha256")==hash(File(i.context.applicationInfo.sourceDir)))
    }
    companion object {
        fun guard(){val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")}
        fun hash(file: File)=file.inputStream().use {input->val d=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString("") {"%02x".format(it)}}
    }
    fun <T> main(block: suspend ()->T): T=runBlocking {withContext(Dispatchers.Main) {block()}}
    fun defaults()=i.targetContext.noBackupFilesDir.listFiles().orEmpty().filter {it.name=="mobile-v1"||it.name.startsWith("mobile-v1-")}.flatMap {root->root.walkTopDown().filter {it.isFile}.toList()}.associate {it.relativeTo(i.targetContext.noBackupFilesDir).path to hash(it)}
    fun api(path: String,value: JSONObject?=null): JSONObject {
        require(path in setOf("/health","/begin-case","/stats","/resolve","/release-read","/end-case"));val c=URL(config.getString("controlOrigin")+path).openConnection() as HttpURLConnection
        try {c.connectTimeout=1500;c.readTimeout=1500;c.instanceFollowRedirects=false;c.setRequestProperty("X-P8-Fixture",config.getString("instanceId"));if(value!=null){val b=easyCanonical(value).toByteArray();c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(b.size);c.outputStream.use {it.write(b)}};check(c.responseCode==200);val b=c.inputStream.use {it.readBytes()};require(b.size<=256_000);return JSONObject(String(b,Charsets.UTF_8))}finally{c.disconnect()}
    }
    fun health(){val h=api("/health");for(k in listOf("fixture","instanceId","startedAt","authOrigin","controlOrigin","sourceGraphSha256","sourceFixtureSha256"))require(config.getString(k)==h.getString(k));require(h.getInt("sidecarPid")==config.getInt("sidecarPid")&&h.getBoolean("onlyLoopback")&&!h.getBoolean("realDesktop")&&!h.getBoolean("realModel"))}
    fun stats()=api("/stats").also {assertEquals(id,it.getString("caseId"));assertEquals(0,it.getJSONArray("errors").length());assertEquals(0,it.getInt("realDesktopRequests"));assertEquals(0,it.getInt("realModelInputs"))}
    fun waitFor(test: ()->Boolean)=runBlocking {withTimeout(12_000){while(!test())delay(25)}}
    fun start() {
        check(!reader);health();require(!File(i.targetContext.noBackupFilesDir,namespace).exists());require(!folder.exists());require(!KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias(alias))
        defaults=defaults();check(folder.mkdirs());api("/begin-case",JSONObject().put("caseId",id));started=true
        storage=NativeSessionStorage(i.targetContext,namespace,alias)
        val credential=EasyWorkCredential.decode(JSONObject(String(asset)).getJSONObject("credential"));val identity=EasyWorkIdentity("p8-fence-$id-a",credential.privateKey,credential.binding.mobilePublicKey);storage.write("easywork-device-identity",identity.json().toString())
        val target=credential.binding.target.scope();original=MobileClient("p8-original-$id","Original synthetic target",target,origin,false,"easywork-runtime",false,agentConnection=AgentConnection(target,listOf(AgentCandidate("not-used","relay-http",origin)),"missing-original-fixture-ref"))
        main {session=MobileSession(storage);session.resetAcceptance();session.select(original);session.text=originalText;originalTarget=session.targetKey}
        val row=runBlocking {val j=NativeOperationJournal(storage);val r=j.prepare(original,"original-thread","reply",ReplyIntent(original.id,"original-thread",UUID.randomUUID().toString(),"Synthetic immutable original unknown input",emptyList(),"next-turn").body());j.markSubmitting(r.target,r.id);j.uncertain(r.target,r.id,"Synthetic original acknowledgement unknown")}
        journalKey="operations:${row.target}";journalBytes=storage.read(journalKey)!!
    }
    fun pending(waitForProgress: ((()->Boolean)->Unit)=::waitFor){
        waitForProgress {stats().getJSONArray("requests").length()>=2}
        api("/resolve",JSONObject().put("caseId",id).put("status","approved"))
        waitForProgress {stats().getInt("held")==1};captureAuthorization()
    }
    fun captureAuthorization() {
        val identity=runBlocking {EasyWorkIdentity.load(storage)};claimKey="easywork-device-claim:"+easyHash((origin+"\n"+EasyWorkDPoP.load(identity).thumbprint+"\n0123456789ABCDEF").toByteArray())
        claimBytes=storage.read(claimKey)!!;grantRef=JSONObject(claimBytes).getString("grantRef");grantBytes=storage.read(grantRef)!!;binding=EasyWorkBinding(JSONObject(grantBytes).getJSONObject("grant").getJSONObject("binding").toString())
        assertEquals(1,stats().getJSONArray("requests").objects().count {it.getString("method")=="POST"})
    }
    fun release(outcome: String="ok")=api("/release-read",JSONObject().put("caseId",id).put("outcome",outcome))
    fun flush()=runBlocking {withTimeout(8000){storage.awaitQueuedWrites()}}
    fun metadata(): JSONObject {flush();return storage.metadata()!!}
    fun unchanged() {assertEquals(journalBytes,storage.read(journalKey));assertEquals(claimBytes,storage.read(claimKey));assertEquals(grantBytes,storage.read(grantRef));assertEquals(defaults,defaults());val s=stats();assertEquals(1,s.getJSONArray("requests").objects().count {it.getString("method")=="POST"});assertTrue(s.getJSONArray("reads").objects().all {it.getString("method") in setOf("runtime/context","project/list","thread/list")})}
    fun candidate(actionId: String): String {val scope=binding.target.scope();return "agent-easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(scope)).toByteArray())+"-$actionId"}
    fun candidatePresent(ref: String){val c=EasyWorkCredential.decode(JSONObject(storage.read("easywork-credential:$ref")!!));assertEquals(grantRef,c.grantRef);assertEquals(binding.target,c.binding.target)}
    fun inactive(){assertEquals(0,metadata().getJSONArray("clients").length());assertTrue(main {session.clients.isEmpty()});assertEquals(original.scope,main {session.selectedClient.scope});assertEquals(originalText,main {session.text})}
    fun reopenWithoutPromotion(candidateRef: String?,candidateHash: String?=null,expectedText: String=originalText) {
        main {session.leaveForeground()};flush();val before=stats().getJSONArray("requests").toString();val reads=stats().getJSONArray("reads").toString()
        val reopened=storage.reopen(i.targetContext);val fresh=main {MobileSession(reopened).also {it.leaveForeground();it.restore()}}
        waitFor {!main {fresh.restoring}};main {assertFalse(fresh.localRecoveryRequired);assertTrue(fresh.clients.isEmpty());fresh.leaveForeground()}
        assertEquals(0,reopened.metadata()!!.getJSONArray("clients").length());assertEquals(expectedText,reopened.metadata()!!.getJSONObject("drafts").getString(originalTarget))
        if(candidateRef!=null)assertEquals(candidateHash,reopened.read("easywork-credential:$candidateRef")?.let {easyHash(it.toByteArray())})
        assertEquals(before,stats().getJSONArray("requests").toString());assertEquals(reads,stats().getJSONArray("reads").toString());unchanged()
    }
    fun proof(name: String,v: JSONObject) {require(name.matches(Regex("[a-z-]+")));val f=File(folder,"$name.json");check(!f.exists());val atomic=android.util.AtomicFile(f);val stream=atomic.startWrite();try {stream.write(easyCanonical(v).toByteArray());atomic.finishWrite(stream)}catch(error: Throwable){atomic.failWrite(stream);throw error};f.setReadable(false,false);f.setWritable(false,false);check(f.setReadable(true,true)&&f.setWritable(true,true))}
    fun common()=JSONObject().put("caseId",id).put("namespace",namespace).put("keyAlias",alias).put("pid",Process.myPid()).put("processStartElapsed",Process.getStartElapsedRealtime()).put("configSha256",easyHash(easyCanonical(config).toByteArray())).put("sourceGraphSha256",config.getString("sourceGraphSha256")).put("build",build).put("syntheticOnly",true)
}
