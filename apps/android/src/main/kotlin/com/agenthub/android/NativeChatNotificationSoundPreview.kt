package com.agenthub.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Explicit local audition, not a notification. Stops on navigation/background/delete and never records audio. */
internal class NativeChatNotificationSoundPreview(private val context: Context, private val library: NativeChatNotificationSoundLibrary) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var generation = 0L
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    var playingId: String? by mutableStateOf(null)
        private set
    private fun main() = check(Looper.myLooper() == Looper.getMainLooper())
    fun stop() {
        main(); generation++; player?.release(); player = null
        focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null; playingId = null
    }
    suspend fun play(id: String, failed: () -> Unit) {
        main(); stop(); if (id == "none") return
        val captured = generation
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        try {
            if (id != "default") withContext(Dispatchers.IO) {
                val item = library.find(id) ?: error("Sound removed")
                descriptor.set(NativeChatNotificationSoundProvider.descriptor(context, library, item.sha256))
            }
            if (generation != captured) return
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({ change -> if (change < 0 && generation == captured) stop() }, android.os.Handler(Looper.getMainLooper())).build()
            if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { failed(); return }
            focus = request
            val next = MediaPlayer(); player = next; playingId = id
            next.setAudioAttributes(attributes); next.isLooping = false
            next.setOnPreparedListener { if (generation == captured && player === next) {
                try { next.start() } catch (_: Exception) { stop(); failed() }
            } }
            next.setOnCompletionListener { if (generation == captured && player === next) stop() }
            next.setOnErrorListener { _, _, _ -> if (generation == captured && player === next) { stop(); failed() }; true }
            if (id == "default") next.setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            else next.setDataSource(requireNotNull(descriptor.get()).fileDescriptor)
            next.prepareAsync()
        } catch (failure: Exception) {
            if (generation == captured) { stop(); failed() }
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        } finally { descriptor.getAndSet(null)?.close() }
    }
}
