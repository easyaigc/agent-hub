package com.agenthub.android

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Native Keystore/URI/MediaCodec boundaries. No player, notification, model, microphone or grant action. */
class NativeChatNotificationSoundTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "P9-sound-${UUID.randomUUID()}"
    private lateinit var storage: NativeSessionStorage
    private lateinit var library: NativeChatNotificationSoundLibrary
    private val tone get() = NativeChatNotificationPcm.builtin("soft.caf").also { it[60] = (it[60].toInt() xor 1).toByte() }
    @Before fun prepare() {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        storage = NativeSessionStorage(context, namespace); library = NativeChatNotificationSoundLibrary(context, storage)
    }
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }
    private fun imported() = library.importNormalized("synthetic sound", "wav", tone)
    @Test fun builtinsAreLocalDistinctAndCannotBeDeleted() {
        val before = storage.read(NativeChatNotificationSoundLibrary.RECORD)
        val sounds = library.catalog(); assertEquals(3, sounds.size); assertEquals(3, sounds.map { it.sha256 }.distinct().size)
        sounds.forEach { item -> assertFalse(item.imported); assertEquals(item.frames, NativeChatNotificationPcm.validateNormalized(library.read(item.id))) }
        assertEquals(before, storage.read(NativeChatNotificationSoundLibrary.RECORD))
        val notices = NativeChatNotificationStore(storage)
        val prefs = storage.read(NativeChatNotificationStore.RECORD)
        rejected { library.delete("soft.caf", notices) }; assertEquals(prefs, storage.read(NativeChatNotificationStore.RECORD))
    }
    @Test fun encryptedImportAndColdRoundtripKeepIdAndBytes() {
        val item = imported(); assertTrue(item.imported); assertEquals("wav", item.sourceExtension)
        val cold = NativeChatNotificationSoundLibrary(context, NativeSessionStorage(context, namespace))
        assertEquals(item, cold.find(item.id)); assertArrayEquals(tone, cold.read(item.id))
        val secret = "synthetic sound".toByteArray()
        val encrypted = File(context.noBackupFilesDir, namespace).listFiles()!!.filter { it.isFile }.map { it.readBytes() }
        assertTrue(encrypted.isNotEmpty())
        assertTrue(encrypted.none { bytes -> bytes.toList().windowed(secret.size).any { it == secret.toList() } })
    }
    @Test fun deleteResetsDefaultAndMatchingCategoriesPreservingOriginalReceipt() {
        val item = imported(); val notices = NativeChatNotificationStore(storage)
        notices.update { it.copy(enabled = true, sound = item.id, categorySounds = mapOf(NativeChatNoticeKind.APPROVAL to item.id, NativeChatNoticeKind.QUESTION to "none"), showThreadTitle = false) }
        val target = NativeChatNoticeTarget("synthetic-host", "synthetic-workspace", "desktop-runtime")
        val lease = notices.bind(target, "credential-epoch")
        val event = NativeChatNotificationEvent("c".repeat(64), target, NativeChatNoticeKind.APPROVAL, "2026-10-28T11:00:00Z", "thread")
        assertNotNull(notices.claim(event, lease, java.time.Instant.parse(event.occurredAt).toEpochMilli()))
        val receipts = JSONObject(storage.read(NativeChatNotificationStore.RECORD)!!).getJSONArray("receipts").toString()
        library.delete(item.id, notices)
        val prefs = notices.preferences(); assertEquals("default", prefs.sound); assertFalse(prefs.showThreadTitle)
        assertFalse(prefs.categorySounds.containsKey(NativeChatNoticeKind.APPROVAL)); assertEquals("none", prefs.categorySounds[NativeChatNoticeKind.QUESTION])
        assertEquals(receipts, JSONObject(storage.read(NativeChatNotificationStore.RECORD)!!).getJSONArray("receipts").toString())
        assertNull(library.find(item.id)); rejected { library.read(item.id) }
    }
    @Test fun sameContentHasStableUriAcrossDifferentIdsAndOneDeletion() {
        val first = imported(); val second = imported(); assertNotEquals(first.id, second.id)
        assertEquals(library.uri(first), library.uri(second))
        library.delete(first.id, NativeChatNotificationStore(storage))
        assertEquals(second, library.byHash(first.sha256)); assertArrayEquals(tone, library.read(second.id))
    }
    @Test fun corruptMetadataIsPreservedWithoutReset() {
        storage.write(NativeChatNotificationSoundLibrary.RECORD, "{\"version\":2}")
        val before = storage.read(NativeChatNotificationSoundLibrary.RECORD)
        rejected { library.catalog() }; rejected { imported() }
        assertEquals(before, storage.read(NativeChatNotificationSoundLibrary.RECORD))
    }
    @Test fun corruptBinaryNeverBecomesDefaultOrGetsDeleted() {
        val item = imported(); val wrong = NativeChatNotificationPcm.builtin("chime.caf")
        storage.writeBinaryBytes(NativeChatNotificationSoundLibrary.binaryName(item.id), wrong, NativeChatNotificationPcm.OUTPUT_LIMIT)
        val before = storage.read(NativeChatNotificationSoundLibrary.RECORD)
        rejected { library.read(item.id) }
        assertEquals(before, storage.read(NativeChatNotificationSoundLibrary.RECORD))
        assertNotNull(library.find(item.id))
    }
    @Test fun cancelledStageBeforeBinaryIsIndexedButNeverSelectedAndCanSettle() {
        var step = 0
        rejected { library.importNormalized("synthetic", "wav", tone) { if (++step == 2) error("cancelled") } }
        assertEquals(3, library.catalog().size)
        val pending = storage.read(NativeChatNotificationSoundLibrary.RECORD)!!
        assertEquals(1, NativeChatNotificationSoundLibrary.binaryInventory(pending).size)
        library.settlePending()
        assertTrue(NativeChatNotificationSoundLibrary.binaryInventory(storage.read(NativeChatNotificationSoundLibrary.RECORD)!!).isEmpty())
    }
    @Test fun cancelledStageAfterBinaryIsNotPublishedOrReplayedOnColdRead() {
        var step = 0
        rejected { library.importNormalized("synthetic", "wav", tone) { if (++step == 3) error("cancelled") } }
        val cold = NativeChatNotificationSoundLibrary(context, NativeSessionStorage(context, namespace))
        assertEquals(3, cold.catalog().size)
        cold.settlePending(); assertEquals(3, cold.catalog().size)
    }
    @Test fun failedRetirementPreservesUnknownBytesAndPendingDescriptor() {
        val item = imported(); storage.writeBinaryBytes(NativeChatNotificationSoundLibrary.binaryName(item.id), NativeChatNotificationPcm.builtin("chime.caf"), NativeChatNotificationPcm.OUTPUT_LIMIT)
        val notices = NativeChatNotificationStore(storage); notices.update { it.copy(sound = item.id) }
        rejected { library.delete(item.id, notices) }; assertEquals("default", notices.preferences().sound); assertNull(library.find(item.id))
        val pending = storage.read(NativeChatNotificationSoundLibrary.RECORD)!!
        assertEquals(1, NativeChatNotificationSoundLibrary.binaryInventory(pending).size)
        rejected { library.settlePending() }; assertEquals(pending, storage.read(NativeChatNotificationSoundLibrary.RECORD))
    }
    @Test fun productionMigrationValidatorEnforcesClosedCatalogAndMatchingAudio() {
        val item = imported(); val raw = storage.read(NativeChatNotificationSoundLibrary.RECORD)!!
        NativeChatNotificationSoundLibrary.validateEncoded(raw)
        NativeChatNotificationSoundLibrary.validateBinary(item, tone)
        assertEquals(setOf(NativeChatNotificationSoundLibrary.binaryName(item.id)), NativeChatNotificationSoundLibrary.binaryInventory(raw).keys)
        rejected { NativeChatNotificationSoundLibrary.validateEncoded(JSONObject(raw).put("extra", true).toString()) }
        rejected { NativeChatNotificationSoundLibrary.validateBinary(item, NativeChatNotificationPcm.builtin("attention.caf")) }
    }
    @Test fun lateImportCannotOverwriteNewerChoiceAndSelectionChecksLiveBytes() {
        val notices = NativeChatNotificationStore(storage); val captured = notices.preferences().revision
        val item = imported(); notices.update { it.copy(sound = "chime.caf") }
        val newer = storage.read(NativeChatNotificationStore.RECORD)
        assertNull(library.select(notices, null, item.id, captured)); assertEquals(newer, storage.read(NativeChatNotificationStore.RECORD))
        assertEquals(item.id, library.select(notices, null, item.id, notices.preferences().revision)!!.sound)
        library.delete(item.id, notices)
        val after = storage.read(NativeChatNotificationStore.RECORD)
        rejected { library.select(notices, null, item.id) }; assertEquals(after, storage.read(NativeChatNotificationStore.RECORD))
    }
    @Test fun providerUriIsCanonicalAndCannotSelectOtherPrivateFiles() {
        val item = imported(); val uri = library.uri(item); val authority = context.packageName + ".chat-notification-sounds"
        assertEquals(item.sha256, NativeChatNotificationSoundProvider.hash(uri, authority))
        for (invalid in listOf(uri.toString() + "?path=metadata", uri.toString() + "/extra", uri.toString() + "#fragment",
            "content://$authority/v1/../metadata", "content://$authority/v1/${item.sha256}.wav%2Fextra", "content://other/v1/${item.sha256}.wav")) {
            rejected { NativeChatNotificationSoundProvider.hash(Uri.parse(invalid), authority) }
        }
    }
    @Test fun providerDescriptorIsSeekableReadOnlyAndDoesNotKeepPlaintextFile() {
        fun reads() = context.cacheDir.listFiles()!!.filter { it.name.startsWith("chat-sound-read-") }.map { it.name }.toSet()
        val item = imported(); val before = reads()
        val descriptor = NativeChatNotificationSoundProvider.descriptor(context, library, item.sha256)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            assertArrayEquals(tone, input.readBytes()); assertEquals(0, Os.lseek(input.fd, 0, OsConstants.SEEK_SET).toInt())
            assertArrayEquals(tone, input.readBytes()); rejected { Os.write(input.fd, byteArrayOf(0), 0, 1) }
        }
        assertEquals(before, reads())
    }
    private fun nativeDecode(asset: String) {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("chat-notification-sounds/$asset").use { it.readBytes() }
        val source = File.createTempFile("native-chat-decoder-", ".tmp", context.cacheDir)
        try {
            source.writeBytes(bytes)
            val wav = NativeChatNotificationSoundDecoder.convert(source, asset.substringAfterLast('.')) { }
            val frames = NativeChatNotificationPcm.validateNormalized(wav)
            assertTrue(frames in (NativeChatNotificationPcm.RATE / 5)..(NativeChatNotificationPcm.RATE / 2))
            assertTrue(wav.drop(44).any { it != 0.toByte() })
        } finally { source.delete() }
    }
    @Test fun actualM4aUsesInstalledCodecWithoutPlaying() { nativeDecode("synthetic-tone.m4a") }
    @Test fun actualMp3UsesInstalledCodecWithoutPlaying() { nativeDecode("synthetic-tone.mp3") }
    @Test fun decodedContentCannotBeSelectedByMisleadingExtension() {
        val source = File.createTempFile("native-chat-mismatch-", ".tmp", context.cacheDir)
        try { source.writeBytes(tone); rejected { NativeChatNotificationSoundDecoder.convert(source, "mp3") { } } } finally { source.delete() }
    }
}
