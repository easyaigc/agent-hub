package com.agenthub.android

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal data class SavedVoiceModelFile(val path: String,val bytes: Long,val sha256: String)
internal data class SavedVoiceModel(val locale: String,val id: String,val url: String,val bytes: Long,val sha256: String,val expandedBytes: Long,val files: List<SavedVoiceModelFile>)
internal enum class SavedVoiceAvailability { UNSUPPORTED, MISSING, CORRUPT, INSTALLED }
internal data class SavedVoiceProbe(val availability: SavedVoiceAvailability)
internal data class SavedVoiceText(val text: String,val modelId: String,val modelSha256: String)

/** File-only contract. No microphone, Android speech intent, upload, or desktop command exists here. */
internal interface NativeSavedVoiceSpeech {
    suspend fun probe(locale: String): SavedVoiceProbe
    suspend fun install(locale: String,checkpoint: suspend ()->Unit)
    suspend fun transcribe(locale: String,wav: ByteArray,checkpoint: suspend ()->Unit): SavedVoiceText
}

internal object SavedVoicePcm {
    fun validate(wav: ByteArray) {
        val size=wav.size-44
        require(size in 2..9_600_000 && size%2==0 && wav.copyOfRange(0,44).contentEquals(WavePCM.header(size)))
    }
}

/** The coroutine owns every blocking resource until its actual finally/close has returned.
 * A cancelled join cannot publish idle while a native call or network read still owns resources.
 */
internal class VoskSavedVoiceSpeech(context: Context): NativeSavedVoiceSpeech {
    private val app=context.applicationContext
    private val root=File(app.noBackupFilesDir,"saved-voice-models-v1")
    private val models by lazy {
        val value=app.assets.open("saved-voice-models-v1.json").bufferedReader().use {JSONObject(it.readText())}
        require(value.getInt("version")==1)
        val rows=value.getJSONArray("models")
        (0 until rows.length()).map { rows.getJSONObject(it) }.associate { row ->
            val parts=row.getJSONArray("files")
            val model=SavedVoiceModel(row.getString("locale"),row.getString("id"),row.getString("url"),row.getLong("bytes"),row.getString("sha256"),row.getLong("expandedBytes"),
                (0 until parts.length()).map { parts.getJSONObject(it).let { f->SavedVoiceModelFile(f.getString("path"),f.getLong("bytes"),f.getString("sha256")) } })
            require(model.locale in setOf("en-US","zh-CN") && model.url=="https://alphacephei.com/vosk/models/${model.id}.zip")
            require(model.files.map {it.path}.distinct().size==model.files.size && model.files.sumOf {it.bytes}==model.expandedBytes)
            model.locale to model
        }
    }
    private fun nativeAvailable(): Boolean {
        if(Build.VERSION.SDK_INT<26 || !android.os.Process.is64Bit() || Build.SUPPORTED_ABIS.none {it in setOf("arm64-v8a","x86_64")})return false
        return try { LibVosk.vosk_set_log_level(-1);true } catch(_: LinkageError) {false} catch(_: Exception) {false}
    }
    private fun prepareRoot() {
        require(!Files.isSymbolicLink(root.toPath()));check(root.mkdirs()||root.isDirectory)
        android.system.Os.chmod(root.path,0x1c0)
        // The process-wide model lock excludes every live installer/decoder. Recover only our staged cache.
        root.listFiles()?.filter {it.name.matches(Regex("stage-[a-f0-9-]{36}"))}?.forEach(::deleteOwned)
    }
    private fun folder(model: SavedVoiceModel)=File(root,model.id)
    private suspend fun verify(model: SavedVoiceModel,dir: File,checkpoint: suspend ()->Unit) {
        require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath()))
        val expected=model.files.associateBy {it.path};val seen=mutableSetOf<String>()
        fun collect(current: File,prefix: String) {
            require(!Files.isSymbolicLink(current.toPath()))
            current.listFiles()?.forEach { child ->
                val name=prefix+child.name
                require(child.canonicalFile==File(dir.canonicalFile,name))
                if(child.isDirectory) {require(expected.keys.any {it.startsWith("$name/")});collect(child,"$name/")}
                else {require(child.isFile && name in expected);require(seen.add(name))}
            } ?: error("Model directory unreadable")
        }
        collect(dir,"");require(seen==expected.keys)
        for(file in model.files) {
            checkpoint();val source=File(dir,file.path);require(source.length()==file.bytes)
            require(hashFile(source,checkpoint)==file.sha256)
        }
    }
    override suspend fun probe(locale: String): SavedVoiceProbe=withContext(Dispatchers.IO) {
        lock.withLock {
            if(!nativeAvailable() || locale !in models)return@withLock SavedVoiceProbe(SavedVoiceAvailability.UNSUPPORTED)
            prepareRoot();val model=models.getValue(locale);val dir=folder(model)
            if(!Files.exists(dir.toPath(),LinkOption.NOFOLLOW_LINKS))return@withLock SavedVoiceProbe(SavedVoiceAvailability.MISSING)
            try {verify(model,dir) {currentCoroutineContext().ensureActive()};SavedVoiceProbe(SavedVoiceAvailability.INSTALLED)}
            catch(e: CancellationException){throw e} catch(_: Exception){SavedVoiceProbe(SavedVoiceAvailability.CORRUPT)}
        }
    }
    override suspend fun install(locale: String,checkpoint: suspend ()->Unit)=withContext(Dispatchers.IO) {
        lock.withLock {
            check(nativeAvailable());val model=models.getValue(locale);prepareRoot();checkpoint()
            // Model files are installation-local cache; no recording or credential is stored here.
            require(root.usableSpace>model.bytes+model.expandedBytes+64L*1024*1024)
            val stage=File(root,"stage-${UUID.randomUUID()}");check(stage.mkdir());android.system.Os.chmod(stage.path,0x1c0)
            val archive=File(stage,"model.zip");val expanded=File(stage,"expanded");check(expanded.mkdir());android.system.Os.chmod(expanded.path,0x1c0)
            try {
                withTimeout(180_000) {
                    val call=downloads.newCall(Request.Builder().url(model.url).build())
                    ownSavedVoiceDownload(call::cancel) {
                        call.execute().use { response ->
                            check(response.code==200)
                            val body=checkNotNull(response.body)
                            require(body.contentLength() in setOf(-1L,model.bytes))
                            body.byteStream().use { input -> archive.outputStream().use { output ->
                                android.system.Os.chmod(archive.path,0x180)
                                copySavedVoiceArchive(input,output,model.bytes,model.sha256,checkpoint)
                                output.fd.sync()
                            }}
                        }
                    }
                    require(hashFile(archive,checkpoint)==model.sha256)
                    ZipFile(archive).use { zip ->
                        val expected=model.files.associateBy {"${model.id}/${it.path}"};val seen=mutableSetOf<String>();val entries=zip.entries()
                        while(entries.hasMoreElements()) {
                            checkpoint();currentCoroutineContext().ensureActive();val entry=entries.nextElement();val name=entry.name
                            require(!name.startsWith('/') && !name.contains('\\') && name.split('/').none {it==".."||it=="."})
                            if(entry.isDirectory) {require(name=="${model.id}/"||expected.keys.any {it.startsWith(name)});continue}
                            val item=expected[name]?:error("Unexpected model entry");require(seen.add(name)&&entry.size==item.bytes)
                            val target=File(expanded,item.path);require(target.canonicalFile==File(expanded.canonicalFile,item.path))
                            check(target.parentFile!!.mkdirs()||target.parentFile!!.isDirectory)
                            var parent=target.parentFile!!;while(parent!=expanded){android.system.Os.chmod(parent.path,0x1c0);parent=parent.parentFile!!}
                            zip.getInputStream(entry).use { input -> target.outputStream().use {output ->
                                android.system.Os.chmod(target.path,0x180);val chunk=ByteArray(65536);var count=0L
                                while(true) {checkpoint();currentCoroutineContext().ensureActive();val n=input.read(chunk);if(n<0)break;count+=n;require(count<=item.bytes);output.write(chunk,0,n)}
                                require(count==item.bytes);output.fd.sync()
                            }}
                        }
                        require(seen==expected.keys)
                    }
                    verify(model,expanded,checkpoint);checkpoint();currentCoroutineContext().ensureActive()
                    val destination=folder(model)
                    // All users share this lock. Never replace an open Model.
                    if(Files.exists(destination.toPath(),LinkOption.NOFOLLOW_LINKS))deleteOwned(destination)
                    Files.move(expanded.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)
                }
            } finally {deleteOwned(stage)}
            Unit
        }
    }
    override suspend fun transcribe(locale: String,wav: ByteArray,checkpoint: suspend ()->Unit): SavedVoiceText=withContext(Dispatchers.IO) {
        lock.withLock {
            check(nativeAvailable());prepareRoot();val spec=models.getValue(locale);SavedVoicePcm.validate(wav);verify(spec,folder(spec),checkpoint);checkpoint()
            val text=StringBuilder()
            fun append(raw: String) {val part=JSONObject(raw).getString("text").trim();if(part.isNotEmpty()){if(text.isNotEmpty())text.append(' ');text.append(part);require(text.length<=32_000)}}
            Model(folder(spec).path).use { model ->
                checkpoint();currentCoroutineContext().ensureActive()
                Recognizer(model,16000f).use { decoder ->
                    val chunk=ByteArray(6400)
                    try {
                        var offset=44
                        while(offset<wav.size) {
                            checkpoint();currentCoroutineContext().ensureActive();val count=minOf(chunk.size,wav.size-offset)
                            wav.copyInto(chunk,0,offset,offset+count);if(decoder.acceptWaveForm(chunk,count))append(decoder.result);offset+=count
                        }
                        checkpoint();currentCoroutineContext().ensureActive();append(decoder.finalResult)
                    } finally {chunk.fill(0)}
                }
            }
            checkpoint();require(text.isNotBlank());SavedVoiceText(text.toString(),spec.id,spec.sha256)
        }
    }
    private suspend fun hashFile(file: File,checkpoint: suspend ()->Unit): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(65536);while(true){checkpoint();currentCoroutineContext().ensureActive();val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun deleteOwned(file: File) {
        require(file.parentFile==root || file.path.startsWith(root.path+File.separator))
        // Do not follow a symlink during cleanup, including an interrupted install.
        if(Files.isSymbolicLink(file.toPath())) {Files.delete(file.toPath());return}
        if(file.isDirectory)file.listFiles()?.forEach(::deleteOwned)
        check(file.delete()||!file.exists())
    }
    companion object {
        private val lock=Mutex()
        // Dedicated model client: no Host credentials, redirects, connection retry or partial resume.
        private val downloads=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
            .build()
    }
}
