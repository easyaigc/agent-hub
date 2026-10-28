package com.agenthub.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Dedicated host vectors against shipping PCM: no device, audio output or substitute decoder. */
fun main(args: Array<String>) {
    val builtinRoot = java.io.File("apps/android/src/main/assets/chat-notification-sounds/v1")
    if (args.contentEquals(arrayOf("--write-builtins"))) {
        builtinRoot.mkdirs()
        listOf("soft.caf", "chime.caf", "attention.caf").forEach { id ->
            val bytes = NativeChatNotificationPcm.builtin(id); val file = java.io.File(builtinRoot, id.removeSuffix(".caf") + ".wav")
            if (file.exists()) check(file.readBytes().contentEquals(bytes)) else file.writeBytes(bytes)
        }
    } else check(args.isEmpty())
    var passed = 0
    fun case(name: String, test: () -> Unit) { test(); passed++; println("PASS $name") }
    fun rejects(test: () -> Unit) { check(runCatching(test).isFailure) }
    fun samples(wav: ByteArray) = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { b -> ShortArray(b.remaining()).also { b.get(it) } }
    fun wave(rate: Int, channels: Int, bits: Int, payload: ByteArray, code: Int = 1): ByteArray = ByteBuffer.allocate(44 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + payload.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(code.toShort()).putShort(channels.toShort())
        .putInt(rate).putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort()).put("data".toByteArray()).putInt(payload.size).put(payload).array()
    fun aiff(payload: ByteArray, frames: Int, codec: String? = null): ByteArray {
        val comm = ByteBuffer.allocate(if (codec == null) 18 else 22).order(ByteOrder.BIG_ENDIAN).putShort(1).putInt(frames).putShort(16)
            .putShort(0x400d.toShort()).putLong(0xac44000000000000UL.toLong()).apply { if (codec != null) put(codec.toByteArray()) }.array()
        return ByteBuffer.allocate(12 + 8 + comm.size + 16 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put("FORM".toByteArray()).putInt(12 + 8 + comm.size + 16 + payload.size - 8).put((if (codec == null) "AIFF" else "AIFC").toByteArray())
            .put("COMM".toByteArray()).putInt(comm.size).put(comm).put("SSND".toByteArray()).putInt(payload.size + 8).putInt(0).putInt(0).put(payload).array()
    }
    fun caf(payload: ByteArray, flags: Int = 2, bits: Int = 16, width: Int = bits / 8, length: Long = payload.size.toLong() + 4): ByteArray =
        ByteBuffer.allocate(68 + payload.size).order(ByteOrder.BIG_ENDIAN).put("caff".toByteArray()).putShort(1).putShort(0)
            .put("desc".toByteArray()).putLong(32).putDouble(22050.0).put("lpcm".toByteArray()).putInt(flags).putInt(width).putInt(1).putInt(1).putInt(bits)
            .put("data".toByteArray()).putLong(length).putInt(0).put(payload).array()
    val pcm = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { listOf(-32768, -8192, 0, 8192, 16384, 24576, 32767, 0).forEach { putShort(it.toShort()) } }.array()
    val canonical = NativeChatNotificationPcm.wav(pcm)
    case("canonical WAV bit-exact roundtrip") { check(NativeChatNotificationPcm.convert(canonical, "wav").contentEquals(canonical)); check(NativeChatNotificationPcm.validateNormalized(canonical) == 8) }
    case("read-only mapped snapshot uses the same parser without a full input copy") {
        val file = java.nio.file.Files.createTempFile("chat-notification-pcm-vector-", ".wav")
        try {
            java.nio.file.Files.write(file, canonical)
            java.io.FileInputStream(file.toFile()).channel.use { channel ->
                val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                check(mapped.isReadOnly); check(NativeChatNotificationPcm.convert(mapped, "wav").contentEquals(canonical))
            }
        } finally { java.nio.file.Files.deleteIfExists(file) }
    }
    case("WAV unsigned 8 bit") {
        val result = samples(NativeChatNotificationPcm.convert(wave(22050, 1, 8, byteArrayOf(0, 64, -128, -64)), "wav"))
        check(result.contentEquals(shortArrayOf(-32768, -16384, 0, 16384)))
    }
    case("stereo mono downmix") {
        val stereo = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(4) { putShort(16384); putShort(-16384) } }.array()
        check(samples(NativeChatNotificationPcm.convert(wave(22050, 2, 16, stereo), "wav")).all { it == 0.toShort() })
    }
    case("44.1kHz downsample duration") { check(NativeChatNotificationPcm.validateNormalized(NativeChatNotificationPcm.convert(wave(44100, 1, 16, ByteArray(44100 * 2)), "wav")) == 22050) }
    case("downsample suppresses aliases while retaining audible tone") {
        fun rms(frequency: Double): Double {
            val input = ByteBuffer.allocate(44100 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(44100) { putShort((sin(it * 2 * PI * frequency / 44100) * 16000).toInt().toShort()) }
            }.array()
            val converted = samples(NativeChatNotificationPcm.convert(wave(44100, 1, 16, input), "wav")).drop(200).dropLast(200)
            return sqrt(converted.map { it.toDouble() * it }.average())
        }
        check(rms(2000.0) in 10000.0..12000.0); check(rms(15000.0) < 1000.0)
    }
    case("8kHz upsample DC amplitude") {
        val input = ByteBuffer.allocate(8000 * 2).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(8000) { putShort(8192) } }.array()
        val result = samples(NativeChatNotificationPcm.convert(wave(8000, 1, 16, input), "wav")); check(result.size == 22050 && result.all { it == 8192.toShort() })
    }
    case("24 bit integer amplitude") {
        val input = byteArrayOf(0, 0, -128, 0, 0, 0, 0, 0, 64, 0, 0, 0)
        check(samples(NativeChatNotificationPcm.convert(wave(22050, 1, 24, input), "wav")).contentEquals(shortArrayOf(-32768, 0, 16384, 0)))
    }
    case("32 bit integer amplitude") {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE).putInt(1073741824).array()
        check(samples(NativeChatNotificationPcm.convert(wave(22050, 1, 32, input), "wav")).contentEquals(shortArrayOf(-32768, 16384)))
    }
    case("float clipping") {
        val input = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(-2f).putFloat(-0.5f).putFloat(0.5f).putFloat(2f).array()
        check(samples(NativeChatNotificationPcm.convert(wave(22050, 1, 32, input, 3), "wav")).contentEquals(shortArrayOf(-32768, -16384, 16384, 32767)))
    }
    case("float NaN rejected") { rejects { NativeChatNotificationPcm.convert(wave(22050, 1, 32, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array(), 3), "wav") } }
    val big = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply { samples(canonical).forEach { putShort(it) } }.array()
    case("AIFF big endian PCM") { check(NativeChatNotificationPcm.convert(aiff(big, 8), "aiff").contentEquals(canonical)) }
    case("AIFC little endian PCM") { check(NativeChatNotificationPcm.convert(aiff(pcm, 8, "sowt"), "aif").contentEquals(canonical)) }
    case("AIFC unsupported codec rejected") { rejects { NativeChatNotificationPcm.convert(aiff(pcm, 8, "ulaw"), "aiff") } }
    case("CAF little endian PCM") { check(NativeChatNotificationPcm.convert(caf(pcm), "caf").contentEquals(canonical)) }
    case("CAF big endian PCM") { check(NativeChatNotificationPcm.convert(caf(big, 0), "caf").contentEquals(canonical)) }
    case("CAF unknown final data size") { check(NativeChatNotificationPcm.convert(caf(pcm, length = -1), "caf").contentEquals(canonical)) }
    case("CAF high aligned 24 in 32") {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE).putInt(1073741824).array()
        check(samples(NativeChatNotificationPcm.convert(caf(input, bits = 24, width = 4), "caf")).contentEquals(shortArrayOf(-32768, 16384)))
    }
    case("CAF unsupported compression rejected") { val bytes = caf(pcm); "alac".toByteArray().copyInto(bytes, 28); rejects { NativeChatNotificationPcm.convert(bytes, "caf") } }
    case("CAF float little endian") {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(-0.5f).putFloat(0.5f).array()
        check(samples(NativeChatNotificationPcm.convert(caf(input, flags = 3, bits = 32), "caf")).contentEquals(shortArrayOf(-16384, 16384)))
    }
    case("container type mismatch rejected") { rejects { NativeChatNotificationPcm.convert(caf(pcm), "wav") }; rejects { NativeChatNotificationPcm.convert(canonical, "caf") } }
    case("truncation and oversized chunk rejected") {
        for (size in listOf(0, 1, 11, 20, 43, canonical.size - 1)) rejects { NativeChatNotificationPcm.convert(canonical.copyOf(size), "wav") }
        val bytes = canonical.copyOf(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Int.MAX_VALUE); rejects { NativeChatNotificationPcm.convert(bytes, "wav") }
    }
    case("compressed WAV rejected") { rejects { NativeChatNotificationPcm.convert(wave(22050, 1, 16, pcm, 7), "wav") } }
    case("invalid sample rate and channel counts rejected") { rejects { NativeChatNotificationPcm.convert(wave(1, 1, 16, pcm), "wav") }; rejects { NativeChatNotificationPcm.convert(wave(22050, 0, 16, pcm), "wav") } }
    case("29 second boundary and excess") {
        val full = NativeChatNotificationPcm.convert(wave(22050, 1, 16, ByteArray(22050 * 29 * 2)), "wav")
        check(full.size == NativeChatNotificationPcm.OUTPUT_LIMIT)
        rejects { NativeChatNotificationPcm.convert(wave(22050, 1, 16, ByteArray(22050 * 29 * 2 + 2)), "wav") }
    }
    case("10 MiB source cap") { rejects { NativeChatNotificationPcm.convert(ByteArray(NativeChatNotificationPcm.INPUT_LIMIT + 1), "wav") } }
    case("cancellation propagates without output") { var calls = 0; rejects { NativeChatNotificationPcm.convert(canonical, "wav") { calls++; error("cancelled") } }; check(calls == 1) }
    case("builtin synthesis duration and distinct voices") {
        val builtins = listOf("soft.caf", "chime.caf", "attention.caf").map(NativeChatNotificationPcm::builtin)
        check(builtins.map { NativeChatNotificationPcm.validateNormalized(it) } == listOf(5292, 10584, 15876))
        check(builtins.map { it.contentHashCode() }.distinct().size == 3 && builtins.all { samples(it).any { n -> n != 0.toShort() } })
    }
    case("shipping builtin assets are canonical and match frozen synthesis") {
        listOf("soft.caf", "chime.caf", "attention.caf").forEach { id ->
            val bytes = java.io.File(builtinRoot, id.removeSuffix(".caf") + ".wav").readBytes()
            NativeChatNotificationPcm.validateNormalized(bytes)
            check(bytes.contentEquals(NativeChatNotificationPcm.builtin(id)))
        }
    }
    case("empty audio and unknown format rejected") { rejects { NativeChatNotificationPcm.convert(wave(22050, 1, 16, byteArrayOf()), "wav") }; rejects { NativeChatNotificationPcm.convert(canonical, "exe") } }
    case("normalized archive validator rejects extra metadata") { rejects { NativeChatNotificationPcm.validateNormalized(caf(pcm)) }; rejects { NativeChatNotificationPcm.validateNormalized(canonical + byteArrayOf(1)) } }
    println("Native chat notification PCM vectors: $passed passed; Android codecs/player/URI/system delivery remain separate.")
}
