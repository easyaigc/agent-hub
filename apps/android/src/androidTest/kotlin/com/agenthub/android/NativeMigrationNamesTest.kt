package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Actual Keystore format compatibility and authenticated automatic record-name recovery. */
class NativeMigrationNamesTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-names")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun file(name: String)=File(owned.storage.coordinationKey,MigrationActivationFixtures.sha(name.toByteArray())+".bin")
    private fun old(name: String,text: String) {
        // Write an actual format1 ciphertext using this app's same existing Keystore key.
        owned.storage.write("metadata","key initialized")
        val secret=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey("agent-hub-mobile-v1",null) as SecretKey
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret);cipher.updateAAD(name.toByteArray())
        file(name).writeBytes(byteArrayOf(1)+cipher.iv+cipher.doFinal(text.toByteArray()))
    }
    @Test fun newEncryptedRecordsCanRecoverUnindexedNamesAndOriginalTextWithoutANameList(){
        val records=mapOf("metadata" to "original local draft 100% %@ 😀\u0000","submission:${UUID.randomUUID()}" to "unindexed original input","history:合成目标" to "line1\nline2\tend","empty" to "")
        records.forEach{(name,text)->owned.storage.write(name,text)};val before=MigrationActivationFixtures.tree(owned.storage)
        assertEquals(records.keys,owned.storage.migrationNames());assertEquals(records,owned.storage.migrationSnapshot(owned.storage.migrationNames()))
        for(name in records.keys){assertEquals(3,file(name).readBytes()[0].toInt());assertFalse(String(file(name).readBytes(),Charsets.ISO_8859_1).contains("unindexed original input"))}
        assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun legacyNamesMustBeExplicitAndReadsNeverRewriteOldCiphertext(){
        old("legacy:original", "original legacy unknown input");val original=file("legacy:original").readBytes();val before=MigrationActivationFixtures.tree(owned.storage)
        assertEquals("original legacy unknown input",owned.storage.read("legacy:original"));assertArrayEquals(original,file("legacy:original").readBytes())
        assertTrue(runCatching{owned.storage.migrationNames()}.isFailure);assertEquals(setOf("metadata","legacy:original"),owned.storage.migrationNames(setOf("legacy:original")));assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
        old("legacy-empty", "");val empty=file("legacy-empty").readBytes();assertEquals("",owned.storage.read("legacy-empty"));assertArrayEquals(empty,file("legacy-empty").readBytes())
    }
    @Test fun renamedRecordOrTamperedNameCannotBeAdoptedByAnotherLogicalRecord(){
        owned.storage.write("metadata","original");owned.storage.write("original:credential","synthetic secret");val original=file("original:credential").readBytes()
        val wrong=file("different:credential");wrong.writeBytes(original)
        val before=MigrationActivationFixtures.tree(owned.storage);assertTrue(runCatching{owned.storage.read("different:credential")}.isFailure);assertTrue(runCatching{owned.storage.migrationNames()}.isFailure);assertEquals(before,MigrationActivationFixtures.tree(owned.storage));assertTrue(wrong.delete())
        file("original:credential").writeBytes(original.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()});val changed=MigrationActivationFixtures.tree(owned.storage)
        assertTrue(runCatching{owned.storage.migrationNames()}.isFailure);assertEquals(changed,MigrationActivationFixtures.tree(owned.storage));file("original:credential").writeBytes(original)
    }
    @Test fun versionFlipCannotConfuseLegacyNameAadAndMalformedUnicodeCannotOverwrite(){
        val name="agent-hub/android-local-record/v3";owned.storage.write("metadata","original");owned.storage.write(name,"unchanged");val bytes=file(name).readBytes()
        file(name).writeBytes(bytes.copyOf().also{it[0]=1});assertTrue(runCatching{owned.storage.read(name)}.isFailure);file(name).writeBytes(bytes)
        val before=MigrationActivationFixtures.tree(owned.storage);assertTrue(runCatching{owned.storage.write(name,"invalid\uD800")}.isFailure);assertEquals(before,MigrationActivationFixtures.tree(owned.storage));assertEquals("unchanged",owned.storage.read(name))
    }
    @Test fun discoveryUsesPublishedGenerationAndRetiresAnOldHandle(){
        val old=owned.storage;old.write("metadata","old");old.write("old-only","old inactive user record");val records=mapOf("metadata" to "new","submission:original" to "immutable original")
        old.publishMigration(UUID.randomUUID().toString(),"a".repeat(64),records);val current=old.reopen(ui.activity);val before=MigrationActivationFixtures.tree(current)
        assertEquals(records.keys,current.migrationNames());assertEquals(records,current.migrationSnapshot(current.migrationNames()));assertTrue(runCatching{old.migrationNames()}.isFailure);assertEquals(before,MigrationActivationFixtures.tree(current))
    }
    @Test fun unsupportedBinaryAndUnknownFilesStillBlockDiscoveryWithoutDeletingThem(){
        owned.storage.write("metadata","original");val audio=File(ui.activity.cacheDir,"owned-name-audio-${UUID.randomUUID()}");audio.writeBytes(ByteArray(64){it.toByte()})
        try{owned.storage.writeBinary("voice-audio:original",audio);val before=MigrationActivationFixtures.tree(owned.storage);assertTrue(runCatching{owned.storage.migrationNames(setOf("voice-audio:original"))}.isFailure);assertEquals(before,MigrationActivationFixtures.tree(owned.storage))}finally{assertTrue(audio.delete())}
    }
}
