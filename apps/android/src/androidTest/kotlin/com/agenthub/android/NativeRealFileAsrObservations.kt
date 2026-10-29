package com.agenthub.android

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/** Observations are deliberately narrower than a no-network/no-microphone attestation. */
internal class NativeRealFileAsrObservations(private val context: Context) {
    private val audio=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val snapshots=Collections.synchronizedList(mutableListOf<JSONObject>())
    private var callbackCount=0
    private var maximumConfigurations=-1
    private var observer="unavailable"
    private val permission=context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
    private val callback=object: AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            synchronized(this@NativeRealFileAsrObservations) {callbackCount++;maximumConfigurations=maxOf(maximumConfigurations,configs.size)}
        }
    }
    init {
        try {audio.registerAudioRecordingCallback(callback,Handler(Looper.getMainLooper()));observer="registered-device-configurations"}
        catch(_: Exception){observer="registration-unavailable"}
        sample("before")
    }
    @Synchronized fun sample(phase: String) {
        require(snapshots.size<24)
        val uid=context.applicationInfo.uid
        val count=try{audio.activeRecordingConfigurations.size}catch(_: Exception){-1}
        maximumConfigurations=maxOf(maximumConfigurations,count)
        snapshots.add(JSONObject().put("phase",phase).put("elapsedMs",android.os.SystemClock.elapsedRealtime())
            .put("uidRxBytes",TrafficStats.getUidRxBytes(uid)).put("uidTxBytes",TrafficStats.getUidTxBytes(uid)).put("recordingConfigurations",count))
    }
    fun finish(): JSONObject {
        sample("after")
        if(observer=="registered-device-configurations")runCatching {audio.unregisterAudioRecordingCallback(callback)}
        val after=context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        return JSONObject().put("recordAudioPermissionBefore",permission).put("recordAudioPermissionAfter",after).put("permissionUnchanged",permission==after)
            .put("recordingObserver",observer).put("recordingCallbacks",if(observer=="registered-device-configurations")callbackCount else JSONObject.NULL).put("maximumRecordingConfigurations",maximumConfigurations)
            .put("traffic",JSONArray(snapshots.toList())).put("trafficScope","whole-target-UID cumulative counters; -1 means unavailable; no HTTP/body/attempt attribution")
            .put("microphoneUseAttestation","unknown; configuration observation and unchanged permission are not a complete app-UID microphone audit")
            .put("uploadAttestation","unknown; parent needs independent phase-specific network evidence")
    }
}

internal object NativeRealFileAsrFiles {
    fun modelRoot(context: Context)=File(context.noBackupFilesDir,"saved-voice-models-v1")
    fun inventory(root: File): Map<String,String> {
        if(!root.exists())return emptyMap()
        require(root.isDirectory&&!java.nio.file.Files.isSymbolicLink(root.toPath()))
        val result=linkedMapOf<String,String>()
        fun visit(directory: File) {
            val children=directory.listFiles()?:error("Owned directory unreadable")
            for(file in children) {
                require(!java.nio.file.Files.isSymbolicLink(file.toPath())&&file.canonicalFile==File(root.canonicalFile,file.relativeTo(root).path))
                if(file.isDirectory)visit(file)else {require(file.isFile&&result.size<64&&file.length()<=80L*1024*1024);result[file.relativeTo(root).invariantSeparatorsPath]=NativeRealFileAsrContract.sha(file)}
            }
        }
        visit(root);return result
    }
    fun references(root: File): JSONObject {
        val canonical=root.canonicalPath+File.separator
        val fd=runCatching {
            File("/proc/self/fd").listFiles()!!.mapNotNull {link->
                try {android.system.Os.readlink(link.path)}catch(failure: android.system.ErrnoException){if(failure.errno==android.system.OsConstants.ENOENT)null else throw failure}
            }.filter {it.startsWith(canonical)}.map {it.removePrefix(canonical).take(200)}.distinct()
        }
        val mapped=runCatching {File("/proc/self/maps").useLines {lines->lines.mapNotNull {line->line.trim().split(Regex("\\s+"),limit=6).getOrNull(5)?.takeIf {it.startsWith(canonical)}?.removePrefix(canonical)?.take(200)}.distinct().take(32).toList()}}
        return JSONObject().put("fdObservation",if(fd.isSuccess)"available"else"unavailable").put("fds",JSONArray(fd.getOrDefault(emptyList())))
            .put("mappingObservation",if(mapped.isSuccess)"available"else"unavailable").put("mappings",JSONArray(mapped.getOrDefault(emptyList())))
    }
    fun libraries(context: Context): JSONArray {
        return ZipFile(context.applicationInfo.sourceDir).use {zip->JSONArray().also {array->
            for(name in listOf("libvosk.so","libjnidispatch.so")) {
                val entry=zip.getEntry("lib/arm64-v8a/$name")?:error("Pinned arm64 native library absent")
                val digest=java.security.MessageDigest.getInstance("SHA-256");var bytes=0L
                zip.getInputStream(entry).use {input->val chunk=ByteArray(65536);while(true){val n=input.read(chunk);if(n<0)break;bytes+=n;digest.update(chunk,0,n)}}
                array.put(JSONObject().put("name",name).put("bytes",bytes).put("sha256",digest.digest().joinToString(""){"%02x".format(it)}))
            }
        }}
    }
    /** Only verified public model cache, never inputs, original records or keys. */
    fun release(root: File,modelId: String,expectedFiles: Set<String>) {
        if(!root.exists())return
        val inventory=inventory(root)
        require(inventory.keys.all {it.startsWith("$modelId/")&&it.removePrefix("$modelId/") in expectedFiles})
        fun remove(file: File) {
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            if(file.isDirectory)file.listFiles()!!.forEach(::remove)
            check(file.delete())
        }
        remove(root);check(!root.exists())
    }
}
