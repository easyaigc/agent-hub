package com.agenthub.android

import android.content.Intent

/** Narrow native notification boundary. Test implementations never invoke NotificationManager or permission UI. */
internal interface NativeChatNotificationSystem {
    val sounds: NativeChatNotificationSoundLibrary
    fun languageChanged(selection: String)
    fun permission(): NativeChatNotificationPermission
    fun settings(channel: String? = null): Intent
    fun channels(): List<NativeChatNotificationChannelState>
    fun soundProviderRegistered(): Boolean
    fun channelSoundTitle(channel: NativeChatNotificationChannelState): String
    suspend fun prepareAsync(kind: NativeChatNoticeKind, sound: String, badge: Boolean, current: () -> Boolean): String?
    fun cancelOwned(target: NativeChatNoticeTarget? = null)
    fun cancelEvents(target: NativeChatNoticeTarget, ids: Set<String>)
    fun post(receipt: NativeChatNoticeReceipt, preferences: NativeChatNotificationPreferences, computer: String, unread: Int, channel: String)
    suspend fun testAsync(preferences: NativeChatNotificationPreferences, current: () -> Boolean): Boolean
}
