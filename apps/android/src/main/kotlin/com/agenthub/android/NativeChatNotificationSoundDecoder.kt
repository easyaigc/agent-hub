package com.agenthub.android

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder

/** M4A/MP3 use the installed Android decoder, never a claimed extension or fixed transcript/PCM. */
internal object NativeChatNotificationSoundDecoder {
    fun convert(source: File, extension: String, cancelled: () -> Unit): ByteArray {
        if (source.length() !in 1..NativeChatNotificationPcm.INPUT_LIMIT.toLong()) throw NativeChatNotificationPcm.Failure("铃声文件最多 10 MB。")
        if (extension !in setOf("m4a", "mp3")) {
            java.io.FileInputStream(source).channel.use { channel ->
                val snapshot = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, source.length())
                return NativeChatNotificationPcm.convert(snapshot, extension, cancelled)
            }
        }
        val prefix = source.inputStream().use { stream -> ByteArray(12).also { require(stream.read(it) >= 8) } }
        require(if (extension == "m4a") String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp"
            else String(prefix, 0, 3, Charsets.US_ASCII) == "ID3" || prefix[0] == 0xff.toByte() && prefix[1].toInt() and 0xe0 == 0xe0)
        val extractor = MediaExtractor(); var codec: MediaCodec? = null; var started = false
        val deadline = SystemClock.elapsedRealtime() + 45_000
        fun current() { cancelled(); if (SystemClock.elapsedRealtime() >= deadline) throw NativeChatNotificationPcm.Failure("音频已损坏或无法转换。") }
        try {
            extractor.setDataSource(source.absolutePath)
            require(extractor.drmInitData == null)
            val tracks = (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            require(tracks.size == 1)
            val (track, format) = tracks.single()
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            require(mime.startsWith("audio/") && (extension != "mp3" || mime == "audio/mpeg"))
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > 29_000_000L)
                throw NativeChatNotificationPcm.Failure("请选择不超过 29 秒的短铃声。")
            extractor.selectTrack(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0); codec.start(); started = true
            var sink: NativeChatNotificationPcm.Sink? = null
            var sampleRate = 0; var channels = 0; var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false; var outputEnded = false
            val info = MediaCodec.BufferInfo()
            fun outputFormat(value: MediaFormat) {
                val rate = value.getInteger(MediaFormat.KEY_SAMPLE_RATE); val count = value.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val pcm = if (value.containsKey(MediaFormat.KEY_PCM_ENCODING)) value.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                require(rate in 8000..192000 && count in 1..8 && pcm in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
                require(sink == null || sampleRate == rate && channels == count && encoding == pcm)
                if (sink == null) sink = NativeChatNotificationPcm.Sink(rate)
                sampleRate = rate; channels = count; encoding = pcm
            }
            while (!outputEnded) {
                current()
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(index)); input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else {
                            require(size <= input.capacity() && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0)
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0); extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat(codec.outputFormat)
                else if (index >= 0) {
                    try {
                        if (sink == null) outputFormat(codec.getOutputFormat(index))
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                            require(info.offset >= 0 && info.size >= 0 && info.offset.toLong() + info.size <= output.capacity())
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            require(info.size % (channels * width) == 0)
                            while (output.hasRemaining()) {
                                var sample = 0.0
                                repeat(channels) { sample += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) output.float.toDouble() else output.short / 32768.0 }
                                requireNotNull(sink).add(sample / channels)
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            current(); return requireNotNull(sink).finish()
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }; extractor.release()
        }
    }
}
