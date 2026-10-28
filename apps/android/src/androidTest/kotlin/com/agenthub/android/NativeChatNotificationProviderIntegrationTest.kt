package com.agenthub.android

import android.content.ComponentName
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Installed provider roundtrip and ungranted shell boundary. No notification, playback or grant. */
class NativeChatNotificationProviderIntegrationTest {
    @Test(timeout = 30_000) fun installedProviderServesOnlyReadOnlyCanonicalSoundAndDeniesUngrant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        val storage = NativeSessionStorage(context)
        val beforeSounds = storage.read(NativeChatNotificationSoundLibrary.RECORD)
        val beforeNotices = storage.read(NativeChatNotificationStore.RECORD)
        val priorScratch = File(context.cacheDir.absolutePath).listFiles().orEmpty()
            .filter { it.name.startsWith("chat-sound-read-") }.map { it.name }.toSet()
        val provider = context.packageManager.getProviderInfo(ComponentName(context, NativeChatNotificationSoundProvider::class.java), 0)
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
        assertEquals(context.packageName + ".chat-notification-sounds", provider.authority)
        assertEquals(context.applicationInfo.uid, provider.applicationInfo.uid)
        val library = NativeChatNotificationSoundLibrary(context, storage)
        val item = library.find("soft.caf")!!
        val uri = library.uri(item)
        assertEquals("audio/wav", context.contentResolver.getType(uri))
        context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.count)
            assertEquals("notification.wav", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(item.bytes.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            val bytes = ByteArray(item.bytes); var offset = 0
            while (offset < bytes.size) {
                val count = Os.read(descriptor.fileDescriptor, bytes, offset, bytes.size - offset)
                assertTrue("Original sound must be complete", count > 0); offset += count
            }
            assertEquals(item.sha256, NativeChatNotificationSoundLibrary.sha(bytes))
            assertEquals(0L, Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET))
            assertTrue("Granted descriptor must remain read only", runCatching {
                Os.write(descriptor.fileDescriptor, byteArrayOf(0), 0, 1)
            }.isFailure)
        }
        // A test-only provider runs in the runner APK's separate UID and uses ContentResolver.
        // No root/shell command, granted URI, arbitrary path or notification is involved.
        val runner = instrumentation.context.packageName
        require(runner == context.packageName + ".test")
        val runnerUid = context.packageManager.getApplicationInfo(runner, 0).uid
        require(runnerUid != android.os.Process.myUid())
        val witness = Uri.parse("content://$runner.sound-witness/probe")
        context.contentResolver.query(witness, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.count)
            assertEquals(runnerUid, cursor.getInt(cursor.getColumnIndexOrThrow("witnessUid")))
            assertEquals(android.os.Process.myUid(), cursor.getInt(cursor.getColumnIndexOrThrow("callerUid")))
            assertEquals("Separate ungranted app must receive SecurityException", 1,
                cursor.getInt(cursor.getColumnIndexOrThrow("denied")))
        }
        val remaining = File(context.cacheDir.absolutePath).listFiles().orEmpty()
            .filter { it.name.startsWith("chat-sound-read-") }.map { it.name }.toSet()
        assertEquals(priorScratch, remaining)
        assertTrue("Provider reads must preserve the original catalog", beforeSounds == storage.read(NativeChatNotificationSoundLibrary.RECORD))
        assertTrue("Provider reads must preserve original notification records", beforeNotices == storage.read(NativeChatNotificationStore.RECORD))
    }
}
