package com.agenthub.android

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.*

/** Local, bounded container decoding. No Android/media service is required for PCM WAV/AIFF/CAF. */
internal object NativeChatNotificationPcm {
    const val INPUT_LIMIT = 10 * 1024 * 1024
    const val RATE = 22050
    const val SECONDS = 29
    const val MAX_FRAMES = RATE * SECONDS
    const val OUTPUT_LIMIT = 44 + MAX_FRAMES * 2
    class Failure(val copy: String) : IllegalArgumentException(copy)
    private fun valid(value: Boolean, copy: String = "音频已损坏或无法解码，请选择其他音频。") { if (!value) throw Failure(copy) }
    private fun label(bytes: ByteBuffer, at: Int, size: Int = 4): String {
        valid(at >= 0 && at.toLong() + size <= bytes.limit())
        return String(ByteArray(size) { bytes[at + it] }, Charsets.US_ASCII)
    }
    private fun u16(b: ByteBuffer, at: Int, little: Boolean): Int = b.duplicate().order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).getShort(at).toInt() and 65535
    private fun u32(b: ByteBuffer, at: Int, little: Boolean): Long = b.duplicate().order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).getInt(at).toLong() and 0xffffffffL
    private fun rate(value: Double): Int {
        valid(value.isFinite() && value in 8000.0..192000.0 && value == floor(value), "此音频的采样率或声道暂不支持。")
        return value.toInt()
    }
    private data class View(val rate: Int, val channels: Int, val width: Int, val floating: Boolean,
        val little: Boolean, val unsigned8: Boolean, val offset: Int, val length: Int) {
        val frames get() = length / (width * channels)
        init {
            valid(rate in 8000..192000 && channels in 1..8 && width in 1..8, "此音频的采样率或声道暂不支持。")
            valid(!floating || width in setOf(4, 8)); valid(floating || width <= 4)
            valid(length > 0 && length % (width * channels) == 0)
            valid(frames.toLong() <= rate.toLong() * SECONDS, "请选择不超过 29 秒的短铃声。")
        }
    }
    private fun wave(b: ByteBuffer): View {
        valid(b.limit() >= 12 && label(b, 8) == "WAVE")
        val little = label(b, 0) == "RIFF"; valid(little || label(b, 0) == "RIFX")
        valid(u32(b, 4, little) + 8 == b.limit().toLong())
        var at = 12; var format: Int? = null; var channels = 0; var sampleRate = 0; var bits = 0; var block = 0
        var offset = -1; var length = 0
        var chunks = 0
        while (at < b.limit()) {
            valid(++chunks <= 4096 && at + 8 <= b.limit()); val type = label(b, at); val size = u32(b, at + 4, little)
            val start = at + 8; valid(size <= b.limit().toLong() - start); val end = start + size.toInt()
            if (type == "fmt ") {
                valid(format == null && size >= 16)
                var code = u16(b, start, little)
                channels = u16(b, start + 2, little); sampleRate = rate(u32(b, start + 4, little).toDouble())
                bits = u16(b, start + 14, little); block = u16(b, start + 12, little)
                if (code == 0xfffe) {
                    valid(little && size >= 40 && u16(b, start + 16, true) >= 22)
                    valid(u16(b, start + 18, true) in 1..bits)
                    valid(ByteArray(12) { b[start + 28 + it] }.contentEquals(byteArrayOf(0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113)))
                    code = u32(b, start + 24, true).toInt()
                }
                valid(code in setOf(1, 3), "此音频编码暂不支持，请选择 PCM 音频或系统可解码的 M4A、MP3。")
                valid(bits in if (code == 3) setOf(32, 64) else setOf(8, 16, 24, 32))
                valid(block == channels * (bits / 8) && u32(b, start + 8, little) == sampleRate.toLong() * block)
                format = code
            } else if (type == "data") { valid(offset == -1); offset = start; length = size.toInt() }
            at = end + (size.toInt() and 1); valid(at <= b.limit())
        }
        valid(format != null && offset >= 0)
        return View(sampleRate, channels, bits / 8, format == 3, little, format == 1 && bits == 8, offset, length)
    }
    private fun aiff(b: ByteBuffer): View {
        valid(b.limit() >= 12 && label(b, 0) == "FORM" && u32(b, 4, false) + 8 == b.limit().toLong())
        val compressed = label(b, 8) == "AIFC"; valid(compressed || label(b, 8) == "AIFF")
        var at = 12; var channels = 0; var sampleRate = 0; var bits = 0; var frames = -1L
        var little = false; var floating = false; var offset = -1; var length = 0
        var chunks = 0
        while (at < b.limit()) {
            valid(++chunks <= 4096 && at + 8 <= b.limit()); val type = label(b, at); val size = u32(b, at + 4, false)
            val start = at + 8; valid(size <= b.limit().toLong() - start); val end = start + size.toInt()
            if (type == "COMM") {
                valid(frames == -1L && size >= if (compressed) 22 else 18)
                channels = u16(b, start, false); frames = u32(b, start + 2, false); bits = u16(b, start + 6, false)
                val exponent = u16(b, start + 8, false)
                valid(exponent and 0x8000 == 0 && exponent != 0x7fff)
                val mantissa = u32(b, start + 10, false) * 4294967296.0 + u32(b, start + 14, false)
                sampleRate = rate(Math.scalb(mantissa, (exponent and 0x7fff) - 16383 - 63))
                val codec = if (compressed) label(b, start + 18) else "NONE"
                valid(codec in setOf("NONE", "sowt", "fl32", "FL32", "fl64", "FL64"), "此音频编码暂不支持，请选择 PCM 音频或系统可解码的 M4A、MP3。")
                little = codec == "sowt"; floating = codec.lowercase(Locale.ROOT) in setOf("fl32", "fl64")
                valid(if (floating) bits == if (codec.lowercase(Locale.ROOT) == "fl32") 32 else 64 else bits in 1..32)
            } else if (type == "SSND") {
                valid(offset == -1 && size >= 8); val skip = u32(b, start, false)
                valid(skip <= size - 8); offset = start + 8 + skip.toInt(); length = end - offset
            }
            at = end + (size.toInt() and 1); valid(at <= b.limit())
        }
        valid(frames > 0 && offset >= 0 && length.toLong() == frames * channels * ((bits + 7) / 8))
        return View(sampleRate, channels, (bits + 7) / 8, floating, little, false, offset, length)
    }
    private fun caf(b: ByteBuffer): View {
        valid(b.limit() >= 52 && label(b, 0) == "caff" && u16(b, 4, false) == 1 && u16(b, 6, false) == 0)
        val header = b.duplicate().order(ByteOrder.BIG_ENDIAN)
        valid(label(b, 8) == "desc" && header.getLong(12) == 32L)
        val sampleRate = rate(header.getDouble(20))
        valid(label(b, 28) == "lpcm", "此音频编码暂不支持，请选择 PCM 音频或系统可解码的 M4A、MP3。")
        val flags = u32(b, 32, false); val block = u32(b, 36, false); val channels = u32(b, 44, false)
        val bits = u32(b, 48, false); val floating = flags and 1L != 0L
        valid(flags and 3L == flags && u32(b, 40, false) == 1L && channels in 1..8 && block % channels == 0L)
        val width = (block / channels).toInt(); valid(width in 1..8)
        valid(if (floating) bits == width * 8L && bits in setOf(32L, 64L) else bits in 1..32 && bits <= width * 8L && width <= 4)
        var at = 52; var offset = -1; var length = 0
        var chunks = 0
        while (at < b.limit()) {
            valid(++chunks <= 4096 && at + 12 <= b.limit()); val type = label(b, at); val size = header.getLong(at + 4)
            val start = at + 12; valid(size >= 0 || type == "data" && size == -1L)
            val actual = if (size == -1L) b.limit().toLong() - start else size
            valid(actual <= b.limit().toLong() - start); val end = start + actual.toInt()
            if (type == "data") { valid(offset == -1 && actual >= 4); offset = start + 4; length = actual.toInt() - 4 }
            at = end
        }
        valid(offset >= 0)
        return View(sampleRate, channels.toInt(), width, floating, flags and 2L != 0L, false, offset, length)
    }
    fun convert(bytes: ByteArray, extension: String, cancelled: () -> Unit = {}): ByteArray = convert(ByteBuffer.wrap(bytes), extension, cancelled)
    fun convert(bytes: ByteBuffer, extension: String, cancelled: () -> Unit = {}): ByteArray {
        valid(bytes.position() == 0 && bytes.limit() in 1..INPUT_LIMIT, "铃声文件最多 10 MB。")
        val view = when (extension.lowercase(Locale.ROOT)) { "wav" -> wave(bytes); "aiff", "aif" -> aiff(bytes); "caf" -> caf(bytes); else -> throw Failure("请选择 WAV、AIFF、CAF、M4A 或 MP3 音频。") }
        val sink = Sink(view.rate)
        val order = if (view.little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val input = bytes.duplicate().order(order)
        var at = view.offset
        repeat(view.frames) { frame ->
            if (frame % 4096 == 0) cancelled()
            var sum = 0.0
            repeat(view.channels) {
                val sample = if (view.floating) {
                    val value = if (view.width == 4) input.getFloat(at).toDouble() else input.getDouble(at)
                    valid(value.isFinite()); value.coerceIn(-1.0, 1.0)
                } else if (view.unsigned8) ((bytes[at].toInt() and 255) - 128) / 128.0
                else {
                    var raw = 0L
                    repeat(view.width) { index -> raw = (raw shl 8) or (bytes[at + if (view.little) view.width - 1 - index else index].toLong() and 255) }
                    val shift = 64 - view.width * 8
                    ((raw shl shift) shr shift).toDouble() / (1L shl (view.width * 8 - 1)).toDouble()
                }
                sum += sample; at += view.width
            }
            sink.add(sum / view.channels)
        }
        return sink.finish()
    }
    /** Streaming mono mix/resampling sink shared by PCM containers and actual MediaCodec output. */
    class Sink(private val sourceRate: Int) {
        private val pcm = ByteArrayOutputStream()
        private var frames = 0L; private var output = 0; private var previous = 0.0
        private var sourceFrames = 0L; private var fed = 0L; private var last = 0.0
        // Downsampling needs an anti-alias low-pass. A bounded FIR uses lookahead compensated at finish.
        private val coefficients = if (sourceRate > RATE) DoubleArray(63) { index ->
            val distance = index - 31; val cutoff = 0.45 * RATE / sourceRate
            (if (distance == 0) 2 * cutoff else sin(2 * PI * cutoff * distance) / (PI * distance)) * (0.54 - 0.46 * cos(2 * PI * index / 62))
        }.let { values -> val sum = values.sum(); values.map { it / sum }.toDoubleArray() } else null
        private val ring = DoubleArray(63); private var cursor = 0
        init { valid(sourceRate in 8000..192000, "此音频的采样率或声道暂不支持。") }
        private fun write(sample: Double) {
            valid(output < MAX_FRAMES, "请选择不超过 29 秒的短铃声。")
            val value = (sample.coerceIn(-1.0, 1.0) * 32768.0).roundToInt().coerceIn(-32768, 32767)
            pcm.write(value and 255); pcm.write(value shr 8 and 255); output++
        }
        private fun emit(sample: Double) {
            while (output.toLong() * sourceRate <= frames * RATE) {
                val fraction = if (frames == 0L) 1.0 else (output.toLong() * sourceRate - (frames - 1) * RATE).toDouble() / RATE
                write(previous + (sample - previous) * fraction)
            }
            previous = sample; frames++
        }
        private fun filter(sample: Double) {
            val weights = coefficients
            if (weights == null) { emit(sample); return }
            ring[cursor] = sample; cursor = (cursor + 1) % ring.size; fed++
            if (fed > 31) emit(weights.indices.sumOf { index -> weights[index] * ring[(cursor + ring.size - 1 - index) % ring.size] })
        }
        fun add(sample: Double) {
            valid(sample.isFinite()); valid(sourceFrames < sourceRate.toLong() * SECONDS, "请选择不超过 29 秒的短铃声。")
            if (sourceFrames == 0L) ring.fill(sample)
            sourceFrames++; last = sample; filter(sample)
        }
        fun finish(): ByteArray {
            valid(sourceFrames > 0, "音频没有可播放的内容。")
            while (frames < sourceFrames) filter(last)
            val expected = ((frames * RATE + sourceRate - 1) / sourceRate).toInt()
            while (output < expected) write(previous)
            return wav(pcm.toByteArray())
        }
    }
    fun wav(pcm: ByteArray): ByteArray {
        valid(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= MAX_FRAMES * 2)
        return header(pcm.size) + pcm
    }
    private fun header(bytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes).array()
    fun validateNormalized(bytes: ByteArray): Int {
        valid(bytes.size <= OUTPUT_LIMIT && bytes.size >= 46)
        val view = wave(ByteBuffer.wrap(bytes))
        valid(view.rate == RATE && view.channels == 1 && view.width == 2 && !view.floating && view.little &&
            view.offset == 44 && view.length == bytes.size - 44 && bytes.copyOfRange(0, 44).contentEquals(header(bytes.size - 44)))
        return view.frames
    }
    fun builtin(id: String): ByteArray {
        val frequencies = when (id) { "soft.caf" -> listOf(660.0); "chime.caf" -> listOf(660.0, 880.0); "attention.caf" -> listOf(880.0, 660.0, 880.0); else -> throw Failure("无法试听这段铃声。") }
        val buffer = ByteBuffer.allocate((RATE * frequencies.size * 0.24).toInt() * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(buffer.capacity() / 2) { index ->
            val time = index.toDouble() / RATE; val phase = time % 0.24
            val envelope = min(1.0, phase / 0.01) * exp(-phase * 12)
            buffer.putShort((sin(time * frequencies[min(frequencies.lastIndex, (time / 0.24).toInt())] * 2 * PI) * envelope * 10000).toInt().toShort())
        }
        return wav(buffer.array())
    }
}
