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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class NativeChatNotificationPermission(val declared: Boolean, val granted: Boolean, val appEnabled: Boolean) {
    val canPost get() = declared && granted && appEnabled
}
internal data class NativeChatNotificationChannelSubmission(val id: String, val lockscreenVisibility: Int)
internal data class NativeChatNotificationChannelState(val id: String, val name: String, val enabled: Boolean, val importance: Int,
    val sound: String?, val showBadge: Boolean)

/** API26+ platform implementation. Never registers a vendor or claims remote push readiness. */
internal class NativeChatNotificationPlatform private constructor(private val context: Context, private var text: NativeL10n,
    override val sounds: NativeChatNotificationSoundLibrary, private val acceptance: NativeChatNotificationAcceptanceScope?) : NativeChatNotificationSystem {
    constructor(context: Context, text: NativeL10n,
        sounds: NativeChatNotificationSoundLibrary = NativeChatNotificationSoundLibrary(context, NativeSessionStorage(context))) : this(context, text, sounds, null)
    companion object {
        const val ACTION = "com.agenthub.android.action.OPEN_CHAT_NOTIFICATION_V1"
        const val TICKET = "chat-notification-ticket-v1"
        private const val PREFIX = "native-chat-v1."
        fun forAcceptance(context: Context, text: NativeL10n, scope: NativeChatNotificationAcceptanceScope,
            storage: NativeSessionStorage): NativeChatNotificationPlatform {
            scope.requireStorage(context, storage)
            return NativeChatNotificationPlatform(context, text, NativeChatNotificationSoundLibrary(context, storage, scope), scope)
        }
        fun openIntent(context: Context, ticket: String): Intent {
            NativeChatNotificationWire.uuid(ticket)
            return Intent(context, MainActivity::class.java).setAction(ACTION)
                .setData(Uri.Builder().scheme("agenthub-chat-notice").authority(context.packageName).appendPath(ticket).build())
                .putExtra(TICKET, ticket).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
    internal var lastChannelSubmission: NativeChatNotificationChannelSubmission? = null; private set
    private val prefix = acceptance?.prefix ?: PREFIX
    private val manager = context.getSystemService(NotificationManager::class.java)
    override fun languageChanged(selection: String) { text = NativeL10n(context, selection) }
    override fun permission(): NativeChatNotificationPermission {
        @Suppress("DEPRECATION")
        val declared = Build.VERSION.SDK_INT < 33 || context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.POST_NOTIFICATIONS)
        return NativeChatNotificationPermission(declared, Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, manager.areNotificationsEnabled())
    }
    override fun settings(channel: String?): Intent = Intent(if (channel == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).apply { if (channel != null) putExtra(Settings.EXTRA_CHANNEL_ID, channel) }
    private fun channelId(kind: NativeChatNoticeKind, sound: String, badge: Boolean) = prefix + kind.wire + "." + sound + if (badge) ".badge" else ".no-badge"
    private fun ensure(kind: NativeChatNoticeKind, sound: String, badge: Boolean, item: NativeChatNotificationSound?): String {
        val id = channelId(kind, item?.let { "clip." + it.sha256 } ?: sound, badge)
        if (manager.getNotificationChannel(id) == null) {
            val title = if (item != null) (if (item.imported) item.title else text.tr(item.title)) else text.tr(if (sound == "none") "静音" else "系统默认")
            val previous = channels().filter { it.id.startsWith(prefix + kind.wire + ".") }
            val importance = minOf(NotificationManager.IMPORTANCE_HIGH, previous.minOfOrNull { it.importance } ?: NotificationManager.IMPORTANCE_HIGH)
            val channel = NotificationChannel(id, text.tr(kind.title) + " · " + title + " · " + text.tr(if (badge) "显示 App 图标角标" else "已关闭"), importance)
            channel.setSound(if (item != null) sounds.uri(item) else if (sound == "none") null else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            channel.setShowBadge(badge && previous.none { !it.showBadge && !it.id.endsWith(".no-badge") }); channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            if (acceptance != null) lastChannelSubmission = NativeChatNotificationChannelSubmission(id, channel.lockscreenVisibility)
            manager.createNotificationChannel(channel)
        }
        return id
    }
    override fun channels(): List<NativeChatNotificationChannelState> = manager.notificationChannels.filter { it.id.startsWith(prefix) }.map {
        NativeChatNotificationChannelState(it.id, it.name.toString(), it.importance != NotificationManager.IMPORTANCE_NONE, it.importance, it.sound?.toString(), it.canShowBadge())
    }
    override fun soundProviderRegistered(): Boolean {
        val provider = context.packageManager.resolveContentProvider(context.packageName + ".chat-notification-sounds", 0)
        return provider?.enabled == true && !provider.exported && provider.grantUriPermissions && provider.name == NativeChatNotificationSoundProvider::class.java.name
    }
    override fun channelSoundTitle(channel: NativeChatNotificationChannelState): String {
        val value = channel.sound ?: return text.tr("静音")
        if (value == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()) return text.tr("系统默认")
        val item = runCatching { sounds.byHash(acceptance?.soundHash(context, Uri.parse(value)) ?: NativeChatNotificationSoundProvider.hash(Uri.parse(value), context.packageName + ".chat-notification-sounds")) }.getOrNull()
        return if (item == null) text.tr("系统自定义铃声") else if (item.imported) item.title else text.tr(item.title)
    }
    fun prepare(kind: NativeChatNoticeKind, sound: String, badge: Boolean): String? {
        if (!permission().canPost || !chatNoticeSound(sound)) return null
        var item: NativeChatNotificationSound? = null
        if (sound !in setOf("default", "none")) {
            item = sounds.find(sound) ?: return null
            if (!soundProviderRegistered()) return null
            // Verify the exact immutable bytes before publication; an absent/corrupt sound never silently becomes default.
            sounds.read(item.id).fill(0)
        }
        // A new sound/badge variant must not bypass a system block for this category.
        if (channels().any { it.id.startsWith(prefix + kind.wire + ".") && !it.enabled }) return null
        return ensure(kind, sound, badge, item).takeIf { manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE }
    }
    /** Storage/authentication on IO, system channel access on Main, checked again after suspension. */
    override suspend fun prepareAsync(kind: NativeChatNoticeKind, sound: String, badge: Boolean, current: () -> Boolean): String? {
        if (!current() || !chatNoticeSound(sound)) return null
        val custom = sound !in setOf("default", "none")
        val item = if (!custom) null else withContext(Dispatchers.IO) {
            if (!current()) return@withContext null
            sounds.find(sound)?.also { sounds.read(it.id).fill(0) }
        }
        if (custom && item == null) return null
        return withContext(Dispatchers.Main.immediate) {
            if (!current() || !permission().canPost || item != null && !soundProviderRegistered()) return@withContext null
            if (channels().any { it.id.startsWith(prefix + kind.wire + ".") && !it.enabled }) return@withContext null
            ensure(kind, sound, badge, item).takeIf { manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE }
        }
    }
    override fun cancelOwned(target: NativeChatNoticeTarget?) {
        val targetPrefix = target?.let { tagPrefix(it) }
        manager.activeNotifications.filter { it.tag?.startsWith(prefix) == true && (targetPrefix == null || it.tag.startsWith(targetPrefix)) }
            .forEach { manager.cancel(it.tag, it.id) }
    }
    override fun cancelEvents(target: NativeChatNoticeTarget, ids: Set<String>) {
        require(ids.all(::chatNoticeHash))
        ids.forEach { manager.cancel(tagPrefix(target) + it, 1) }
    }
    private fun tagPrefix(target: NativeChatNoticeTarget): String = prefix + digest(NativeChatNotificationCodec.target(target).toString()) + "."
    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    override fun post(receipt: NativeChatNoticeReceipt, preferences: NativeChatNotificationPreferences, computer: String, unread: Int, channel: String) {
        if (acceptance != null) require(channel.startsWith(prefix))
        val event = receipt.event
        val intent = openIntent(context, receipt.ticket)
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
        manager.notify(prefix + "test", 1, notice); return true
    }
    override suspend fun testAsync(preferences: NativeChatNotificationPreferences, current: () -> Boolean): Boolean {
        if (!preferences.enabled) return false
        val channel = prepareAsync(NativeChatNoticeKind.CONNECTION, if (preferences.soundEnabled) preferences.sound else "none", false, current) ?: return false
        return withContext(Dispatchers.Main.immediate) {
            if (!current()) return@withContext false
            val notice = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(text.tr("通知")).setContentText(text.tr("发送测试通知"))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build()
            manager.notify(prefix + "test", 1, notice); true
        }
    }
}
