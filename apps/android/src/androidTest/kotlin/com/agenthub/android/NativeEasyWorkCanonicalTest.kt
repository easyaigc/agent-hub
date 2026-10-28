package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Prepared actual Android JSON/crypto/encrypted-journal checks. No network or submitted input. */
class NativeEasyWorkCanonicalTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(60)
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val id=UUID.randomUUID().toString()
    private val vector get()=JSONObject(i.context.assets.open("easywork-canonical-keys-v1.json").bufferedReader().use {it.readText()})
    private var before=emptyMap<String,String>()
    private lateinit var folder: File
    private fun hash(file: File)=file.inputStream().use {input->val d=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val count=input.read(buffer);if(count<0)break;d.update(buffer,0,count)};d.digest().joinToString("") {"%02x".format(it)}}
    private fun defaults()=i.targetContext.noBackupFilesDir.listFiles().orEmpty().filter {it.name=="mobile-v1"||it.name.startsWith("mobile-v1-")}.flatMap {root->root.walkTopDown().filter {it.isFile}.toList()}.associate {it.relativeTo(i.targetContext.noBackupFilesDir).path to hash(it)}
    @Before fun setup() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        val build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PairingUiBuild")))
        for(k in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(k).matches(Regex("[a-f0-9]{64}")))
        assertEquals(build.getString("appSha256"),hash(File(i.targetContext.applicationInfo.sourceDir)));assertEquals(build.getString("runnerSha256"),hash(File(i.context.applicationInfo.sourceDir)))
        before=defaults();folder=File(i.targetContext.filesDir,"acceptance/p8-canonical/$id").apply {check(!exists());check(mkdirs())}
    }
    @After fun finish() {if(::folder.isInitialized){assertEquals(before,defaults());File(folder,"default-protection.json").writeText(JSONObject().put("unchanged",true).put("modelInputs",0).put("networkRequests",0).toString())}}
    @Test fun actualAndroidKeyAndValueEncodingMatchesAllCrossLanguageVectors() {
        for(v in vector.getJSONArray("vectors").objects()) {val input=v.get("input");val original=input.toString();assertEquals(v.getString("id"),v.getString("canonical"),easyCanonical(input));assertEquals(v.getString("sha256"),easyHash(easyCanonical(input).toByteArray()));assertEquals(original,input.toString())}
        File(folder,"platform-json.json").writeText(JSONObject().put("api",android.os.Build.VERSION.SDK_INT).put("platformQuotedSlash",JSONObject.quote("/")).put("canonicalScalarSlash",easyCanonical("/")).put("canonicalKeySlash",easyCanonical(JSONObject().put("a/b","value/path"))).put("vectors",12).toString())
    }
    @Test fun sourceGraphHashRemainsStrictForChangedKeysValuesAndHashes() {
        val v=vector.getJSONArray("vectors").objects().single {it.getString("id")=="exact-four-source-graph"};val graph=v.getJSONObject("input");val expected=v.getString("sha256")
        assertEquals(expected,easyHash(easyCanonical(graph).toByteArray()))
        for(changed in listOf(JSONObject(graph.toString()).put("pairing-ui/fixture.mjs","0".repeat(64)),JSONObject(graph.toString()).apply {put("pairing-ui/fixture.mjS",remove("pairing-ui/fixture.mjs"))}))assertNotEquals(expected,easyHash(easyCanonical(changed).toByteArray()))
        assertNotEquals("0".repeat(64),easyHash(easyCanonical(graph).toByteArray()))
    }
    @Test fun originalJweUsesItsOriginalProtectedHeaderAndRejectsReencoding() {
        val v=vector.getJSONObject("protectedDelivery");val who=v.getJSONObject("identity");val identity=EasyWorkIdentity(who.getString("deviceId"),who.getString("privateKey"),who.getString("publicKey"));val original=v.getString("delivery");val parts=original.split('.').toMutableList()
        assertEquals(v.getString("deliverySha256"),easyHash(original.toByteArray()));assertEquals(v.getString("protectedHeaderSha256"),easyHash(easyBytes(parts[0])))
        assertEquals(v.getString("payloadCanonical"),easyCanonical(EasyWorkDeviceDelivery.open(identity,original,v.getString("deliveryId"))))
        val replacement=easyBase64(easyCanonical(JSONObject(String(easyBytes(parts[0]),Charsets.UTF_8))).toByteArray());assertNotEquals(parts[0],replacement);parts[0]=replacement
        assertTrue(runCatching {EasyWorkDeviceDelivery.open(identity,parts.joinToString("."),v.getString("deliveryId"))}.isFailure);assertEquals(v.getString("deliverySha256"),easyHash(original.toByteArray()))
    }
    @Test fun unknownJournalRetainsOriginalRawIntentAndFingerprintAcrossReopen()=runBlocking {
        val namespace="p8-canonical-$id";check(!File(i.targetContext.noBackupFilesDir,namespace).exists());val storage=NativeSessionStorage(i.targetContext,namespace,namespace)
        val c=EasyWorkCredential.decode(JSONObject(i.context.assets.open("easywork-crypto-vector.json").bufferedReader().use {it.readText()}).getJSONObject("credential"));val target=c.binding.target.scope()
        val connection=AgentConnection(target,listOf(AgentCandidate("unused","relay-http","https://not-used.invalid")),"unused")
        val client=MobileClient("synthetic-no-network","Synthetic",target,"https://not-used.invalid",false,"easywork-runtime",false,agentConnection=connection)
        val journal=NativeOperationJournal(storage);val row=journal.prepare(client,"synthetic-thread","reply",ReplyIntent(client.id,"synthetic-thread",UUID.randomUUID().toString(),"Original / quote\" 中文 😀",emptyList(),"next-turn").body());journal.markSubmitting(row.target,row.id);journal.uncertain(row.target,row.id,"synthetic original unknown; never sent")
        val key="operations:${row.target}";val original=checkNotNull(storage.read(key));val intent=row.intent;val sha=row.intentSha256
        val reopened=NativeSessionStorage(i.targetContext,namespace,namespace);val restored=NativeOperationJournal(reopened).records(row.target).single();assertEquals(intent,restored.intent);assertEquals(sha,restored.intentSha256);assertEquals("uncertain",restored.phase)
        val recoded=easyCanonical(JSONObject(intent));assertNotEquals(intent,recoded)
        val altered=JSONObject(original);altered.getJSONArray("operations").getJSONObject(0).put("intent",recoded)
        assertTrue(runCatching {NativeOperationJournal(reopened).validateMigrationRecords(row.target,altered)}.isFailure)
        assertEquals(original,reopened.read(key));assertEquals(sha,easyHash(intent.toByteArray()));File(folder,"original-journal.json").writeText(JSONObject().put("originalIntentSha256",sha).put("originalJournalSha256",easyHash(original.toByteArray())).put("rawIntentUnchanged",true).put("recodedIntentRejectedWithoutReplacingFingerprint",true).put("unknownNotReplayed",true).toString())
    }
}
