package com.agenthub.android

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** The production capture WAV sink. Caller owns the existing raw-writer lease and its lifecycle.
 * close is deliberately not finish: an interrupted capture retains its original partial header/PCM.
 * This class does not register a recording, acquire a raw writer, seal metadata, or use a microphone.
 */
internal class NativeVoicePcmWriter(file: File, private val writer: NativeVoiceRawGate.Writer) : Closeable {
    private val output = writer.run { requireCurrent(); RandomAccessFile(file, "rw") }
    var pcmBytes = 0; private set
    private var finished = false
    init {
        try {
            android.system.Os.chmod(file.path, 0x180)
            output.setLength(0); output.write(WavePCM.header(0))
        } catch (failure: Throwable) {
            try { output.close() } catch (close: Throwable) { if (failure !== close) failure.addSuppressed(close) }
            throw failure
        }
    }
    fun append(bytes: ByteArray, count: Int) {
        require(!finished && count in 2..bytes.size && count % 2 == 0 && pcmBytes <= 9_600_000 - count)
        writer.requireCurrent(); output.write(bytes, 0, count); pcmBytes += count
        if (pcmBytes % 32000 < count) output.fd.sync()
    }
    fun finish() {
        check(!finished); writer.requireCurrent()
        output.seek(0); output.write(WavePCM.header(pcmBytes)); output.fd.sync(); finished = true
    }
    override fun close() = output.close()
}
