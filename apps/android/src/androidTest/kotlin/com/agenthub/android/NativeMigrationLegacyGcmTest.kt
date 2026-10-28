package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Real historical single-tag ciphertext, generated incrementally without a giant fixture String. */
class NativeMigrationLegacyGcmTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned legacy inline migration password"
    private data class Fixture(val storage: NativeSessionStorage,val alias: String,val name: String,val file: CreationFile,val encrypted: File,val records: Map<String,String>,val version: Int)
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-legacy-gcm")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun pattern(size: Int,output: OutputStream) {
        val buffer=ByteArray(65536){(it*37%251).toByte()};var remaining=size
        try{while(remaining>0){val count=minOf(buffer.size,remaining);output.write(buffer,0,count);remaining-=count}}finally{buffer.fill(0)}
    }
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->root.walkTopDown().filter{it.isFile}.associate {file->
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        file.relativeTo(root).path to digest.digest().joinToString(""){"%02x".format(it)}
    }}
    private fun fixture(version: Int,size: Int): Fixture {
        require(version==1||version==3)
        val id=UUID.randomUUID().toString();val alias="p7-legacy-$id";val source=NativeSessionStorage(ui.activity,"P7-legacy-$id",alias)
        val root=MigrationActivationFixtures.payload().getJSONObject("records");val oldName=root.keys().asSequence().single{it.startsWith("draft-file:")}
        val old=JSONObject(root.getString(oldName)).getJSONObject("file");val probe=NativeMigrationProbe();pattern(size,probe)
        val file=CreationFile(old.getString("id"),old.getString("name"),old.getString("mediaType"),size.toLong(),probe.sha256());root.remove(oldName)
        val metadata=JSONObject(root.getString("metadata"));val drafts=metadata.getJSONObject("draftFiles");drafts.keys().forEach{drafts.put(it,JSONArray().put(file.body()))};root.put("metadata",metadata.toString())
        val submission=root.keys().asSequence().single{it.startsWith("submission:")};root.put(submission,JSONObject(root.getString(submission)).put("files",JSONArray().put(file.body())).toString())
        root.keys().forEach{source.write(it,root.getString(it))}
        val key=(KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(alias,null) as SecretKey)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key);updateAAD(if(version==1)oldName.toByteArray(Charsets.UTF_8)else byteArrayOf(0xff.toByte(),3)+"agent-hub/android-local-record/v3".toByteArray(Charsets.UTF_8))}
        val encrypted=File(source.coordinationKey,MigrationActivationFixtures.sha(oldName.toByteArray())+".bin")
        encrypted.outputStream().use {out->
            out.write(version);out.write(cipher.iv)
            val sink=object: OutputStream() {
                override fun write(value: Int){write(byteArrayOf(value.toByte()))}
                override fun write(bytes: ByteArray,offset: Int,count: Int){cipher.update(bytes,offset,count)?.let{out.write(it)}}
            }
            if(version==3){val name=oldName.toByteArray(Charsets.UTF_8);sink.write(java.nio.ByteBuffer.allocate(4).putInt(name.size).array());sink.write(name)}
            sink.write(("{\"file\":"+file.body()+",\"bytes\":\"").toByteArray(Charsets.UTF_8))
            // The platform Base64 stream may emit individual encoded bytes. Batch them before
            // Android Keystore IPC; fixture generation must not make one binder call per byte.
            java.util.Base64.getEncoder().wrap(java.io.BufferedOutputStream(sink,65536)).use {pattern(size,it)}
            sink.write("\"}".toByteArray(Charsets.UTF_8));out.write(cipher.doFinal());out.fd.sync()
        }
        val records=root.keys().asSequence().associateWith{root.getString(it)}
        return Fixture(source,alias,oldName,file,encrypted,records,version)
    }
    private fun unsupported(fixture: Fixture,kind: String) {
        val original=tree(fixture.storage);val output=File(owned.folder,"$kind.migration");output.writeText("previous authenticated export remains")
        val error=runCatching{NativeMigrationExport(ui.activity,fixture.storage).sealFile(output,password)}.exceptionOrNull()
        assertTrue(error is NativeSessionStorage.LegacyGcmAuthenticationLimit);val limit=(error as NativeSessionStorage.LegacyGcmAuthenticationLimit).limit
        assertEquals(fixture.version,limit.observedFormat);assertEquals(fixture.encrypted.length(),limit.ciphertextBytes)
        assertEquals(fixture.encrypted.length()-29,limit.unauthenticatedPlaintextBytes);assertFalse(limit.authenticationAttempted)
        assertTrue(limit.unauthenticatedPlaintextBytes>limit.maximumRecordPlaintextBytes)
        if(Runtime.getRuntime().maxMemory()<=48L*1024*1024)assertTrue(limit.auditedAospMinimumBufferAndResultBytes>Runtime.getRuntime().maxMemory())
        assertEquals(original,tree(fixture.storage));assertEquals("previous authenticated export remains",output.readText())
        fixture.records.forEach{(name,body)->assertEquals(body,fixture.storage.read(name))}
        val candidate=owned.storage.child(ui.activity,"migration-candidates");assertNull(candidate.read("candidate-v1"))
        File(owned.folder,"$kind-failure.json").writeText(JSONObject().put("observedFormat",limit.observedFormat).put("rawAttachmentBytes",fixture.file.sizeBytes)
            .put("ciphertextBytes",limit.ciphertextBytes).put("unauthenticatedPlaintextBytes",limit.unauthenticatedPlaintextBytes)
            .put("auditedAospMinimumBufferAndResultBytes",limit.auditedAospMinimumBufferAndResultBytes).put("heapLimitBytes",limit.heapLimitBytes)
            .put("authenticationAttempted",false).put("sourceAuthenticityEstablished",false).put("untrustedPlaintextPublished",false)
            .put("sourceCiphertextPreserved",true).put("immutableIntentAndUnknownReceiptPreserved",true).put("candidatePublished",false)
            .put("migrationStatus","unsupported-whole-gcm-authentication").put("fullLegacyMigrationPassed",false).put("remoteWrites",0).toString())
    }
    @Test fun maximumFormatOneInlineRemainsUnsupportedWithExactFailureAndOriginalCiphertext(){unsupported(fixture(1,20*1024*1024),"legacy-v1-max")}
    @Test fun maximumFormatThreeInlineRemainsUnsupportedWithExactFailureAndOriginalCiphertext(){unsupported(fixture(3,20*1024*1024),"legacy-v3-max")}
    @Test fun corruptedMaximumSourceIsNotAuthenticatedRepairedOrPublished(){
        val source=fixture(3,20*1024*1024);RandomAccessFile(source.encrypted,"rw").use{file->file.seek(file.length()-1);val b=file.read();file.seek(file.length()-1);file.write(b xor 1)}
        unsupported(source,"legacy-v3-corrupt-max")
    }
    @Test fun boundedHistoricalRecordsStillAuthenticateAndImportWithoutSourceRewrite()=runBlocking {
        for(version in listOf(1,3)) {
            val source=fixture(version,65536);val before=tree(source.storage);val output=File(owned.folder,"legacy-v$version-bounded.migration")
            NativeMigrationExport(ui.activity,source.storage).sealFile(output,password)
            val destination=owned.storage.child(ui.activity,"legacy-import-$version")
            val receipt=NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),output,password)
            NativeMigrationActivation(ui.activity,destination).activate(receipt);val current=destination.reopen(ui.activity)
            val probe=NativeMigrationProbe();NativeDraftFiles(current).copyTo(source.file,probe);assertEquals(source.file.sizeBytes,probe.size);assertEquals(source.file.sha256,probe.sha256())
            assertEquals(before,tree(source.storage));assertFalse(JSONObject(current.read(source.name)!!).has("version"))
        }
    }
    @Test fun actualKeystoreUpdateWithholdsPlaintextUntilWholeMessageAuthentication(){
        val source=fixture(3,65536);val key=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(source.alias,null) as SecretKey;assertNull(key.encoded)
        fun observe(corrupt: Boolean): JSONObject {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");var early=0L;var finalBytes=0;var authenticated=false;var tagRejected=false
            source.encrypted.inputStream().use{input->
                assertEquals(3,input.read());val nonce=ByteArray(12);java.io.DataInputStream(input).readFully(nonce)
                cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,nonce));cipher.updateAAD(byteArrayOf(0xff.toByte(),3)+"agent-hub/android-local-record/v3".toByteArray(Charsets.UTF_8))
                val buffer=ByteArray(16384);var position=13L
                while(true){val count=input.read(buffer);if(count<0)break;position+=count;if(corrupt&&position==source.encrypted.length())buffer[count-1]=(buffer[count-1].toInt() xor 1).toByte()
                    cipher.update(buffer,0,count)?.let{bytes->early+=bytes.size;bytes.fill(0)}}
                try{val clear=cipher.doFinal();try{finalBytes=clear.size;authenticated=true}finally{clear.fill(0)}}catch(_: javax.crypto.AEADBadTagException){tagRejected=true}
            }
            assertEquals(0L,early)
            if(corrupt){assertTrue(tagRejected);assertFalse(authenticated);assertEquals(0,finalBytes)}
            else {assertTrue(authenticated);assertEquals(source.encrypted.length()-29,finalBytes.toLong())}
            return JSONObject().put("provider",cipher.provider.name).put("earlyOutputBytes",early).put("doFinalBytes",finalBytes).put("authenticated",authenticated).put("tagRejected",tagRejected)
        }
        val before=tree(source.storage);val valid=observe(false);val corrupt=observe(true);assertEquals(before,tree(source.storage))
        File(owned.folder,"keystore-authentication-boundary.json").writeText(JSONObject().put("valid",valid).put("corrupt",corrupt).put("keyExportable",false)
            .put("heapLimitBytes",Runtime.getRuntime().maxMemory()).put("maximumLegacyMigrationPassed",false).put("remoteWrites",0).toString())
    }
}
