package com.agenthub.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException

/** Register non-exported + grantUriPermissions. Only immutable normalized active sound bytes are exposed. */
class NativeChatNotificationSoundProvider : ContentProvider() {
    companion object {
        internal fun hash(uri: Uri, authority: String): String {
            val value = uri.pathSegments
            require(uri.scheme == "content" && uri.authority == authority && uri.query == null && uri.fragment == null && value.size == 2 && value[0] == "v1")
            val hash = value[1].removeSuffix(".wav"); require(chatNoticeHash(hash))
            require(uri.toString() == Uri.Builder().scheme("content").authority(authority).appendPath("v1").appendPath(hash + ".wav").build().toString())
            return hash
        }
        internal fun descriptor(context: android.content.Context, library: NativeChatNotificationSoundLibrary, hash: String): ParcelFileDescriptor {
            val item = library.byHash(hash) ?: throw FileNotFoundException("Notification sound removed")
            val bytes = library.read(item.id)
            val scratch = File.createTempFile("chat-sound-read-", ".wav", context.cacheDir)
            try {
                scratch.outputStream().use { it.write(bytes) }
                val fd = Os.open(scratch.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
                try { return ParcelFileDescriptor.dup(fd) } finally { Os.close(fd) }
            } finally { bytes.fill(0); scratch.delete() }
        }
    }
    override fun onCreate() = context != null
    private fun library() = NativeChatNotificationSoundLibrary(requireNotNull(context), NativeSessionStorage(requireNotNull(context)))
    private fun resolve(uri: Uri): Pair<NativeChatNotificationSoundLibrary, NativeChatNotificationSound> {
        val app = requireNotNull(context)
        val scope = NativeChatNotificationAcceptanceScope.fromSoundUri(app, uri)
        val hash = scope?.soundHash(app, uri) ?: hash(uri, app.packageName + ".chat-notification-sounds")
        val library = if (scope == null) library() else NativeChatNotificationSoundLibrary(app, scope.storage(app), scope)
        return library to (library.byHash(hash) ?: throw FileNotFoundException("Unknown notification sound"))
    }
    override fun getType(uri: Uri): String { resolve(uri); return "audio/wav" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(selection == null && selectionArgs == null && sortOrder == null)
        val (_, item) = resolve(uri)
        val columns = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        require(columns.all { it in setOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) })
        return MatrixCursor(columns.toTypedArray()).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) item.bytes else "notification.wav" }) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r"); val (library, item) = resolve(uri)
        return descriptor(requireNotNull(context), library, item.sha256)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
