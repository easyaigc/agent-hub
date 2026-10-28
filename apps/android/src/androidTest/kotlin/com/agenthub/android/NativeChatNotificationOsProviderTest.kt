package com.agenthub.android

import android.content.ComponentName
import android.content.ContextWrapper
import java.util.UUID
import java.io.File
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Installed ContentResolver/provider boundary, entirely within fresh acceptance-owned namespaces. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationOsProviderTest : NativeChatNotificationOsBase() {
    @Test fun installedScopedProviderReturnsExactReadOnlyNormalizedBytes() {
        val f = fixture()
        val info = context.packageManager.getProviderInfo(ComponentName(context, NativeChatNotificationSoundProvider::class.java), 0)
        assertFalse(info.exported); assertTrue(info.grantUriPermissions); assertEquals(context.packageName + ".chat-notification-sounds", info.authority)
        assertTrue(f.platform.soundProviderRegistered())
        val bytes = NotificationOsFixture.tone(); val item = f.imported(bytes); val uri = f.platform.sounds.uri(item)
        assertEquals(f.scope.runId, uri.pathSegments[1]); assertEquals("audio/wav", context.contentResolver.getType(uri))
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.count); assertEquals("notification.wav", it.getString(0)); assertEquals(bytes.size.toLong(), it.getLong(1))
        }
        context.contentResolver.openFileDescriptor(uri, "r")!!.use {
            assertEquals(0L, Os.lseek(it.fileDescriptor, 0, OsConstants.SEEK_SET))
            assertTrue(runCatching { Os.write(it.fileDescriptor, byteArrayOf(1), 0, 1) }.isFailure)
        }
        assertArrayEquals(bytes, f.readUri(item)); f.notes.put("uriReadHash", item.sha256).put("crossUidGrantAndAudibility", "not-tested")
    }
    @Test fun silentAndOrdinaryOwnedClipsHaveDifferentRealUriBytes() {
        val f = fixture(); val tone = NotificationOsFixture.tone(); val silence = tone.copyOf().also { it.fill(0, 44) }
        val a = f.imported(silence, "Owned silent PCM"); val b = f.imported(tone, "Owned ordinary PCM")
        assertNotEquals(f.platform.sounds.uri(a), f.platform.sounds.uri(b))
        val silentRead = f.readUri(a); val ordinaryRead = f.readUri(b)
        assertTrue(silentRead.drop(44).all { it == 0.toByte() }); assertTrue(ordinaryRead.drop(44).any { it != 0.toByte() })
        assertEquals(a.frames, NativeChatNotificationPcm.validateNormalized(silentRead)); assertEquals(b.frames, NativeChatNotificationPcm.validateNormalized(ordinaryRead))
        f.notes.put("silentHash", a.sha256).put("ordinaryHash", b.sha256).put("playback", "not-invoked")
    }
    @Test fun malformedScopedUriAndWriteModeCannotReadOrMutateSound() {
        val f = fixture(); val item = f.imported(NotificationOsFixture.tone()); val uri = f.platform.sounds.uri(item)
        for (bad in listOf(uri.buildUpon().appendQueryParameter("path", "ignored").build(), uri.buildUpon().fragment("extra").build(), Uri.parse(uri.toString().removeSuffix(".wav"))))
            assertTrue(runCatching { context.contentResolver.openFileDescriptor(bad, "r")?.close() }.isFailure)
        for (mode in listOf("w", "rw", "rwt")) assertTrue(runCatching { context.contentResolver.openFileDescriptor(uri, mode)?.close() }.isFailure)
        assertEquals(item.sha256, NativeChatNotificationSoundLibrary.sha(f.readUri(item)))
    }
    @Test fun oneOwnedScopeDeletionCannotExposeOrDeleteAnotherScope() {
        val a = fixture(); val b = fixture(); val bytes = NotificationOsFixture.tone()
        val first = a.imported(bytes); val second = b.imported(bytes)
        assertNotEquals(a.platform.sounds.uri(first), b.platform.sounds.uri(second))
        context.contentResolver.openFileDescriptor(a.platform.sounds.uri(first), "r")!!.use { held ->
            a.io { a.platform.sounds.delete(first.id, a.notices) }
            assertTrue(runCatching { context.contentResolver.openFileDescriptor(a.platform.sounds.uri(first), "r")?.close() }.isFailure)
            val header = ByteArray(4); assertEquals(4, Os.read(held.fileDescriptor, header, 0, 4)); assertEquals("RIFF", String(header))
        }
        assertArrayEquals(bytes, b.readUri(second)); a.notes.put("otherOwnedScopePreserved", b.scope.runId)
    }
    @Test fun acceptanceScopeRejectsProtectedSiblingBeforeCreatingStorage() {
        val run = UUID.randomUUID().toString()
        val sibling = object : ContextWrapper(context) { override fun getPackageName() = "com.easyai.agenthub.android.acceptance.easywork" }
        assertTrue(runCatching { NativeChatNotificationAcceptanceScope.create(sibling, run) }.isFailure)
        assertFalse(File(context.noBackupFilesDir, "P9-native-system-$run").exists())
    }

}
