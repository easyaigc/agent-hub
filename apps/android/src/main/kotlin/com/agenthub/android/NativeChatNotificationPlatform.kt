package com.agenthub.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

internal data class NativeChatNotificationPermission(val declared: Boolean, val granted: Boolean, val appEnabled: Boolean) {
    val canPost get() = declared && granted && appEnabled
}
internal data class NativeChatNotificationChannelState(val id: String, val name: String, val enabled: Boolean, val importance: Int,
    val sound: String?, val showBadge: Boolean)

/** API26+ platform implementation. Never registers a vendor or claims remote push readiness. */
internal class NativeChatNotificationPlatform(private val context: Context, private val text: NativeL10n) {
    companion object {
        const val ACTION = "com.agenthub.android.action.OPEN_CHAT_NOTIFICATION_V1"
        const val TICKET = "chat-notification-ticket-v1"
        private const val PREFIX = "native-chat-v1."
    }
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun permission(): NativeChatNotificationPermission {
        @Suppress("DEPRECATION")
        val declared = Build.VERSION.SDK_INT < 33 || context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.POST_NOTIFICATIONS)
        return NativeChatNotificationPermission(declared, Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, manager.areNotificationsEnabled())
    }
    fun settings(channel: String? = null): Intent = Intent(if (channel == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).apply { if (channel != null) putExtra(Settings.EXTRA_CHANNEL_ID, channel) }
    private fun channelId(kind: NativeChatNoticeKind, sound: String, badge: Boolean) = PREFIX + kind.wire + "." + sound + if (badge) ".badge" else ".no-badge"
    private fun ensure(kind: NativeChatNoticeKind, sound: String, badge: Boolean): String {
        require(sound in setOf("default", "none")) { "Custom chat notification sounds are not implemented" }
        val id = channelId(kind, sound, badge)
        if (manager.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(id, text.tr(kind.title) + " · " + text.tr(if (sound == "none") "静音" else "系统默认") +
                " · " + text.tr(if (badge) "显示 App 图标角标" else "已关闭"), NotificationManager.IMPORTANCE_HIGH)
            channel.setSound(if (sound == "none") null else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            channel.setShowBadge(badge); channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            manager.createNotificationChannel(channel)
        }
        return id
    }
    fun channels(): List<NativeChatNotificationChannelState> = manager.notificationChannels.filter { it.id.startsWith(PREFIX) }.map {
        NativeChatNotificationChannelState(it.id, it.name.toString(), it.importance != NotificationManager.IMPORTANCE_NONE, it.importance, it.sound?.toString(), it.canShowBadge())
    }
    fun prepare(kind: NativeChatNoticeKind, sound: String, badge: Boolean): String? {
        if (!permission().canPost || sound !in setOf("default", "none")) return null
        // A new sound/badge variant must not bypass a system block for this category.
        if (channels().any { it.id.startsWith(PREFIX + kind.wire + ".") && !it.enabled }) return null
        return ensure(kind, sound, badge).takeIf { manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE }
    }
    fun cancelOwned(target: NativeChatNoticeTarget? = null) {
        val targetPrefix = target?.let { tagPrefix(it) }
        manager.activeNotifications.filter { it.tag?.startsWith(PREFIX) == true && (targetPrefix == null || it.tag.startsWith(targetPrefix)) }
            .forEach { manager.cancel(it.tag, it.id) }
    }
    private fun tagPrefix(target: NativeChatNoticeTarget): String = PREFIX + digest(NativeChatNotificationCodec.target(target).toString()) + "."
    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun post(receipt: NativeChatNoticeReceipt, preferences: NativeChatNotificationPreferences, computer: String, unread: Int, channel: String) {
        val event = receipt.event
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
            .setData(Uri.Builder().scheme("agenthub-chat-notice").authority(context.packageName).appendPath(receipt.ticket).build())
            .putExtra(TICKET, receipt.ticket).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val presentation = NativeChatNoticePresentation.create(event, computer, preferences.showThreadTitle) { text.tr(it) }
        val public = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(presentation.publicTitle).setContentText(presentation.metadata).build()
        val notice = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(presentation.title).setSubText(presentation.metadata).setContentText(presentation.summary)
            .setStyle(Notification.BigTextStyle().bigText(presentation.summary)).setPublicVersion(public)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS).setNumber(if (preferences.badgeEnabled) unread else 0).build()
        manager.notify(tagPrefix(event.target) + event.id, 1, notice)
    }
    /** Explicit settings action only; it has no target, route, unread mutation or remote submission. */
    fun test(preferences: NativeChatNotificationPreferences): Boolean {
        if (!preferences.enabled) return false
        val channel = prepare(NativeChatNoticeKind.CONNECTION, if (preferences.soundEnabled) preferences.sound else "none", false) ?: return false
        val notice = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(text.tr("通知")).setContentText(text.tr("发送测试通知"))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build()
        manager.notify(PREFIX + "test", 1, notice); return true
    }
}
