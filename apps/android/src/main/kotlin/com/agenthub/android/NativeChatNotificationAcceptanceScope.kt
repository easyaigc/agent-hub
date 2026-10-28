package com.agenthub.android

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/** Fixed, debug-only acceptance ownership. Never accepts an arbitrary namespace, filename or method. */
internal class NativeChatNotificationAcceptanceScope private constructor(val runId: String) {
    companion object {
        private const val PATH = "acceptance-v1"
        fun create(context: Context, runId: String): NativeChatNotificationAcceptanceScope {
            require(BuildConfig.DEBUG && context.packageName == "com.easyai.agenthub.android.acceptance") { "Independent notification acceptance package required" }
            require(UUID.fromString(runId).toString() == runId)
            return NativeChatNotificationAcceptanceScope(runId)
        }
        fun fromSoundUri(context: Context, uri: Uri): NativeChatNotificationAcceptanceScope? {
            if (uri.pathSegments.firstOrNull() != PATH) return null
            require(uri.pathSegments.size == 3)
            return create(context, uri.pathSegments[1]).also { it.soundHash(context, uri) }
        }
    }
    val prefix = "native-chat-acceptance-v1.$runId."
    private val namespace = "P9-native-system-$runId"
    fun storage(context: Context): NativeSessionStorage { create(context, runId); return NativeSessionStorage(context, namespace) }
    fun requireStorage(context: Context, storage: NativeSessionStorage) {
        create(context, runId)
        require(storage.coordinationKey == File(context.noBackupFilesDir, namespace).canonicalPath)
    }
    fun soundUri(context: Context, hash: String): Uri {
        create(context, runId); require(chatNoticeHash(hash))
        return Uri.Builder().scheme("content").authority(context.packageName + ".chat-notification-sounds")
            .appendPath(PATH).appendPath(runId).appendPath(hash + ".wav").build()
    }
    fun soundHash(context: Context, uri: Uri): String {
        create(context, runId)
        require(uri.pathSegments.size == 3 && uri.pathSegments[0] == PATH && uri.pathSegments[1] == runId)
        val hash = uri.pathSegments[2].removeSuffix(".wav")
        require(uri == soundUri(context, hash))
        return hash
    }
}
