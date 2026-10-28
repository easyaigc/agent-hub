package com.agenthub.android

import org.json.JSONObject
import java.io.OutputStream

/** P7 owns the migration graph; P9 owns the closed catalog and normalized audio validators. */
internal object NativeMigrationSounds {
    const val RECORD = NativeChatNotificationSoundLibrary.RECORD
    private const val PREFIX = "native-chat-notification-sound-v1:"
    private val builtins = setOf("default", "none", "soft.caf", "chime.caf", "attention.caf")
    internal class MissingReferences(val references: Set<String>) : IllegalArgumentException("迁移的铃声引用缺失，请明确修复或重设铃声后重新导出。")

    fun validate(records: Map<String, String>, binaries: Map<String, String>, streams: Map<String, NativeMigrationBlob>?, verifyBytes: Boolean = true): Set<String> {
        val raw = records[RECORD]
        val inventory = raw?.let(NativeChatNotificationSoundLibrary::binaryInventory).orEmpty()
        raw?.let(NativeChatNotificationSoundLibrary::validateEncoded)
        val present = (streams?.keys ?: binaries.keys).filter { it.startsWith(PREFIX) }.toSet()
        require(present.all { it in inventory }) { "Unindexed notification sound" }
        require(raw?.let(NativeChatNotificationSoundLibrary::requiredBinaries).orEmpty().all { it in present }) { "Missing active notification sound" }
        // Only current preferences select a sound. Historical receipts/audits remain immutable and inert.
        records[NativeMigrationDeviceState.NOTIFICATIONS]?.let { notices ->
            val prefs = StrictMigrationJson.parse(notices.toByteArray(Charsets.UTF_8)).getJSONObject("preferences")
            val references = setOf(prefs.getString("sound")) + prefs.getJSONObject("categorySounds").let { v -> v.keys().asSequence().map(v::getString).toSet() }
            val active = raw?.let { JSONObject(it).getJSONArray("items").objects().map { item -> item.getString("id") }.toSet() }.orEmpty()
            val missing = references - builtins - active
            if (missing.isNotEmpty()) throw MissingReferences(missing)
        }
        for (name in present) {
            val item = inventory.getValue(name)
            if (streams == null) {
                val bytes = NativeMigrationRecords.bytes(binaries.getValue(name))
                try { NativeChatNotificationSoundLibrary.validateBinary(item, bytes) } finally { bytes.fill(0) }
            } else {
                val blob = streams.getValue(name)
                require(blob.size == item.bytes && blob.sha256 == item.sha256)
                if (verifyBytes) {
                    // At most one 1,278,944-byte normalized clip is held; never decode all sounds together.
                    val bytes = ByteArray(item.bytes); var offset = 0
                    try {
                        blob.verifiedCopy(object : OutputStream() {
                            override fun write(value: Int) { require(offset < bytes.size); bytes[offset++] = value.toByte() }
                            override fun write(value: ByteArray, start: Int, count: Int) {
                                require(count >= 0 && count <= bytes.size - offset)
                                System.arraycopy(value, start, bytes, offset, count); offset += count
                            }
                        })
                        require(offset == bytes.size); NativeChatNotificationSoundLibrary.validateBinary(item, bytes)
                    } finally { bytes.fill(0) }
                }
            }
        }
        return present
    }
}
