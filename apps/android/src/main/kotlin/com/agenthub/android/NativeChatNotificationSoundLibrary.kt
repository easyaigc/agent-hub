package com.agenthub.android

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NativeChatNotificationSound(val id: String, val title: String, val imported: Boolean,
    val sha256: String, val bytes: Int, val frames: Int, val sourceExtension: String)

/** Same installation key/store as notifications. Only normalized local audio persists; source URIs do not. */
internal class NativeChatNotificationSoundLibrary(private val context: Context, private val storage: NativeSessionStorage,
    private val acceptance: NativeChatNotificationAcceptanceScope? = null) {
    init { acceptance?.requireStorage(context, storage) }
    companion object {
        const val RECORD = "native-chat-notification-sounds-v1"
        const val MAX_IMPORTED = 128
        const val CATALOG_LIMIT = 256 * 1024
        fun binaryName(id: String): String {
            require(id.endsWith(".caf") && UUID.fromString(id.removeSuffix(".caf")).toString() + ".caf" == id)
            return "native-chat-notification-sound-v1:$id"
        }
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private val executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "native-chat-sound-import").apply { isDaemon = true } }
        // Versioned bytes are shipped assets, so persistent channel URIs never depend on runtime floating-point math.
        private val builtins = listOf(
            NativeChatNotificationSound("soft.caf", "轻柔提示", false, "3579f427ab553b0fa3e71b1433a376b98acab91cae2d5a1247d9fa9ba4fab926", 10628, 5292, "generated"),
            NativeChatNotificationSound("chime.caf", "清亮铃声", false, "153716aeb2da8febad6e5a4ce79c7d5f268ccc0753c706864edcd9941132c3ec", 21212, 10584, "generated"),
            NativeChatNotificationSound("attention.caf", "醒目提醒", false, "6ea2939f779d5a29b1d65f811f29811704cfa6cc58ac576a9a55e05a491fdb7f", 31796, 15876, "generated"),
        )
        private data class Catalog(val items: List<NativeChatNotificationSound> = emptyList(),
            val staged: List<NativeChatNotificationSound> = emptyList(), val retired: List<NativeChatNotificationSound> = emptyList())
        private fun encoded(item: NativeChatNotificationSound) = JSONObject().put("id", item.id).put("title", item.title)
            .put("sha256", item.sha256).put("bytes", item.bytes).put("frames", item.frames).put("sourceExtension", item.sourceExtension)
        private fun decoded(v: JSONObject): NativeChatNotificationSound {
            require(v.keys().asSequence().toSet() == setOf("id", "title", "sha256", "bytes", "frames", "sourceExtension"))
            fun string(key: String) = v.get(key) as? String ?: error("Invalid sound string")
            fun integer(key: String) = (v.get(key) as? Int) ?: error("Invalid sound integer")
            return NativeChatNotificationSound(string("id"), string("title"), true, string("sha256"), integer("bytes"), integer("frames"), string("sourceExtension")).also {
                require(it.id.endsWith(".caf") && UUID.fromString(it.id.removeSuffix(".caf")).toString() + ".caf" == it.id)
                require(it.title.isNotBlank() && it.title.codePointCount(0, it.title.length) <= 80 && it.title == safeTitle(it.title))
                require(chatNoticeHash(it.sha256) && it.frames in 1..NativeChatNotificationPcm.MAX_FRAMES && it.bytes == 44 + it.frames * 2)
                require(it.sourceExtension in setOf("wav", "aiff", "aif", "caf", "m4a", "mp3"))
            }
        }
        private fun decode(raw: String): Catalog {
            require(raw.toByteArray(Charsets.UTF_8).size <= CATALOG_LIMIT)
            val v = JSONObject(raw)
            require(v.keys().asSequence().toSet() == setOf("version", "items", "staged", "retired") && v.get("version") == 1)
            fun entries(key: String) = v.getJSONArray(key).let { a -> require(a.length() <= MAX_IMPORTED); (0 until a.length()).map { decoded(a.getJSONObject(it)) } }
            return Catalog(entries("items"), entries("staged"), entries("retired")).also {
                val all = it.items + it.staged + it.retired
                require(all.size <= 256 && all.map { item -> item.id }.distinct().size == all.size)
            }
        }
        /** Closed migration schema/parser. Validation never publishes an import or changes preferences. */
        fun validateEncoded(raw: String) { decode(raw) }
        fun binaryInventory(raw: String): Map<String, NativeChatNotificationSound> = decode(raw).let {
            (it.items + it.staged + it.retired).associateBy { item -> binaryName(item.id) }
        }
        fun requiredBinaries(raw: String): Set<String> = decode(raw).items.map { binaryName(it.id) }.toSet()
        fun validateBinary(item: NativeChatNotificationSound, bytes: ByteArray) {
            require(bytes.size == item.bytes && sha(bytes) == item.sha256 && NativeChatNotificationPcm.validateNormalized(bytes) == item.frames)
        }
        private fun safeTitle(raw: String): String {
            val safe = raw.codePoints().filter { Character.getType(it) !in listOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt()) }.limit(80).toArray()
            return String(safe, 0, safe.size).trim().ifBlank { "Audio" }
        }
    }
    private val builtinBytes by lazy { builtins.associate { item ->
        val bytes = context.assets.open("chat-notification-sounds/v1/" + item.id.removeSuffix(".caf") + ".wav").use { it.readBytes() }
        validateBinary(item, bytes); item.id to bytes
    } }
    private fun <T> transaction(block: () -> T): T = storage.snapshotTransaction { storage.operationTransaction(block) }
    private fun load(): Catalog = storage.read(RECORD)?.let(::decode) ?: Catalog()
    private fun save(catalog: Catalog) {
        val raw = JSONObject().put("version", 1).put("items", JSONArray(catalog.items.map(::encoded)))
            .put("staged", JSONArray(catalog.staged.map(::encoded))).put("retired", JSONArray(catalog.retired.map(::encoded))).toString()
        validateEncoded(raw); storage.write(RECORD, raw)
    }
    fun catalog(): List<NativeChatNotificationSound> = transaction { builtins + load().items }
    fun find(id: String): NativeChatNotificationSound? = catalog().singleOrNull { it.id == id }
    fun uri(item: NativeChatNotificationSound): Uri = acceptance?.soundUri(context, item.sha256) ?: Uri.Builder().scheme("content").authority(context.packageName + ".chat-notification-sounds")
        .appendPath("v1").appendPath(item.sha256 + ".wav").build()
    fun read(id: String): ByteArray = transaction {
        val item = find(id) ?: throw FileNotFoundException("Unknown notification sound")
        if (!item.imported) builtinBytes.getValue(id).copyOf() else storage.readBinary(binaryName(id), item.bytes, NativeChatNotificationPcm.OUTPUT_LIMIT)
            .also { validateBinary(item, it) }
    }
    fun byHash(hash: String): NativeChatNotificationSound? { require(chatNoticeHash(hash)); return catalog().firstOrNull { it.sha256 == hash } }
    private fun removeKnown(item: NativeChatNotificationSound) {
        val bytes = try { storage.readBinary(binaryName(item.id), item.bytes, NativeChatNotificationPcm.OUTPUT_LIMIT) } catch (_: FileNotFoundException) { return }
        try { validateBinary(item, bytes) } finally { bytes.fill(0) }
        storage.remove(binaryName(item.id))
    }
    /** Only exact indexed abandoned/retired files may be removed; corrupt/unknown bytes are preserved. */
    fun settlePending() = transaction {
        val old = load()
        (old.staged + old.retired).forEach(::removeKnown)
        if (old.staged.isNotEmpty() || old.retired.isNotEmpty()) save(old.copy(staged = emptyList(), retired = emptyList()))
    }
    internal fun importNormalized(title: String, extension: String, bytes: ByteArray, cancelled: () -> Unit = {}): NativeChatNotificationSound = transaction {
        val frames = NativeChatNotificationPcm.validateNormalized(bytes)
        val old = load(); if (old.items.size >= MAX_IMPORTED) throw NativeChatNotificationPcm.Failure("铃声数量已达上限，请先删除不再使用的铃声。")
        val item = NativeChatNotificationSound(UUID.randomUUID().toString() + ".caf", safeTitle(title), true, sha(bytes), bytes.size, frames, extension)
        cancelled(); save(old.copy(staged = old.staged + item))
        try {
            cancelled(); storage.writeBinaryBytes(binaryName(item.id), bytes, NativeChatNotificationPcm.OUTPUT_LIMIT)
            cancelled(); save(load().let { it.copy(items = it.items + item, staged = it.staged.filterNot { staged -> staged.id == item.id }) })
            item
        } catch (failure: Throwable) {
            // Keep an explicit staged descriptor on failure; do not expose or auto-select it.
            throw failure
        }
    }
    /** Choosing bytes and updating references share the same lock order as retirement and notification preferences. */
    fun select(notices: NativeChatNotificationStore, kind: NativeChatNoticeKind?, id: String?, expectedRevision: String? = null,
        current: () -> Boolean = { true }): NativeChatNotificationPreferences? = transaction {
        if (!current()) return@transaction null
        if (expectedRevision != null && notices.preferences().revision != expectedRevision) return@transaction null
        require(kind != null || id != null)
        if (id != null && id !in setOf("default", "none")) { require(find(id) != null) { "Notification sound removed" }; read(id).fill(0) }
        notices.update { old -> check(current()); if (kind == null) old.copy(sound = requireNotNull(id)) else
            old.copy(categorySounds = if (id == null) old.categorySounds - kind else old.categorySounds + (kind to id)) }
    }
    fun delete(id: String, notices: NativeChatNotificationStore, current: () -> Boolean = { true }) = transaction {
        check(current())
        val old = load(); val item = old.items.singleOrNull { it.id == id } ?: throw NativeChatNotificationPcm.Failure("无法删除内置铃声。")
        // Order is deliberate: a crash may leave unused bytes, never a preference pointing at deleted content.
        notices.update { it.copy(sound = if (it.sound == id) "default" else it.sound, categorySounds = it.categorySounds.filterValues { value -> value != id }) }
        save(old.copy(items = old.items.filterNot { it.id == id }, retired = old.retired + item))
        removeKnown(item)
        save(load().let { it.copy(retired = it.retired.filterNot { retired -> retired.id == id }) })
    }
    suspend fun import(uri: Uri): NativeChatNotificationSound = suspendCancellableCoroutine { continuation ->
        require(uri.scheme == "content" && !uri.authority.isNullOrEmpty())
        val signal = CancellationSignal(); val descriptor = AtomicReference<AssetFileDescriptor?>(); val cancelled = AtomicBoolean(false)
        continuation.invokeOnCancellation { cancelled.set(true); signal.cancel(); runCatching { descriptor.get()?.close() } }
        executor.execute {
            var source: File? = null
            fun current() { if (cancelled.get()) throw java.util.concurrent.CancellationException() }
            try {
                current()
                val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: throw NativeChatNotificationPcm.Failure("请选择 WAV、AIFF、CAF、M4A 或 MP3 音频。")
                val extension = display.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension !in setOf("wav", "aiff", "aif", "caf", "m4a", "mp3")) throw NativeChatNotificationPcm.Failure("请选择 WAV、AIFF、CAF、M4A 或 MP3 音频。")
                val input = context.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: throw FileNotFoundException()
                descriptor.set(input); current()
                if (input.declaredLength > NativeChatNotificationPcm.INPUT_LIMIT) throw NativeChatNotificationPcm.Failure("铃声文件最多 10 MB。")
                source = File.createTempFile("chat-sound-source-", ".tmp", context.cacheDir)
                input.createInputStream().use { stream -> source.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024); var total = 0L
                    while (true) {
                        current(); val size = stream.read(buffer); if (size == -1) break
                        if (size == 0) throw NativeChatNotificationPcm.Failure("音频已损坏或无法解码，请选择其他音频。")
                        total += size; if (total > NativeChatNotificationPcm.INPUT_LIMIT) throw NativeChatNotificationPcm.Failure("铃声文件最多 10 MB。")
                        output.write(buffer, 0, size)
                    }
                    if (total == 0L) throw NativeChatNotificationPcm.Failure("音频没有可播放的内容。")
                } }
                current(); val normalized = NativeChatNotificationSoundDecoder.convert(source, extension, ::current)
                val item = try { settlePending(); importNormalized(display.substringBeforeLast('.'), extension, normalized, ::current) } finally { normalized.fill(0) }
                if (continuation.isActive) continuation.resume(item)
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(if (failure is NativeChatNotificationPcm.Failure) failure
                    else NativeChatNotificationPcm.Failure(when (failure) {
                        is SecurityException -> "无法读取所选音频，请重新选择并授予读取权限。"
                        is FileNotFoundException -> "所选音频已不可用，请重新选择。"
                        is android.media.MediaCodec.CodecException -> "此设备无法解码这段音频，请选择其他格式或编码。"
                        else -> "音频已损坏或无法解码，请选择其他音频。"
                    }))
            } finally { runCatching { descriptor.getAndSet(null)?.close() }; source?.delete() }
        }
    }
}
