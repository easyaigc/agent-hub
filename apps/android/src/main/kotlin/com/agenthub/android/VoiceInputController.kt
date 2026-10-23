package com.agenthub.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Native PCM capture; no phone speech service or automatic upload. */
internal class NativeAudioCapture(private val file: File,private val scope: CoroutineScope,private val meter: (Float)->Unit) {
    private val running=AtomicBoolean(false)
    private var worker: Deferred<Unit>?=null
    val ended get()=worker?.isCompleted==true
    suspend fun start() {
        running.set(true)
        val ready=CompletableDeferred<Unit>()
        worker=scope.async(Dispatchers.IO) {
            var recorder: AudioRecord?=null
            try {
                val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                require(minimum>0) { "此设备不支持录音格式" }
                var opened: AudioRecord?=null
                // Some older/vendor HALs omit the voice-recognition source. Both sources are local PCM microphones.
                for(source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.MIC,MediaRecorder.AudioSource.DEFAULT)) {
                    @Suppress("MissingPermission")
                    val candidate=AudioRecord(source,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(6400,minimum*2))
                    if(candidate.state==AudioRecord.STATE_INITIALIZED) { opened=candidate;break }
                    candidate.release()
                }
                val input=opened ?: error("无法初始化麦克风录音，请检查系统录音占用后重试。原草稿已保留。")
                recorder=input
                if(!running.get()) { ready.completeExceptionally(CancellationException("录音已取消"));return@async }
                input.startRecording();check(input.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "麦克风未能开始录音，原草稿已保留。" };ready.complete(Unit)
                RandomAccessFile(file,"rw").use { out->
                    android.system.Os.chmod(file.path,0x180);out.setLength(0);out.write(WavePCM.header(0))
                    val buffer=ShortArray(1600);val bytes=ByteArray(buffer.size*2);var count=0
                    while(running.get() && count<9_600_000) {
                        ensureActive()
                        val n=input.read(buffer,0,minOf(buffer.size,(9_600_000-count)/2),AudioRecord.READ_BLOCKING)
                        check(n>0) { "录音已被系统中断，原文件已保留。" }
                        var power=0.0
                        for(i in 0 until n) { val value=buffer[i].toInt();bytes[i*2]=value.toByte();bytes[i*2+1]=(value shr 8).toByte();power+=value.toDouble()*value }
                        out.write(bytes,0,n*2);count+=n*2
                        // A process death leaves recoverable PCM; the final header is sealed before networking.
                        if(count%32000<n*2) out.fd.sync()
                        val level=(sqrt(power/n)/5000).toFloat().coerceIn(0f,1f)
                        withContext(Dispatchers.Main.immediate) { meter(level) }
                    }
                    out.seek(0);out.write(WavePCM.header(count));out.fd.sync()
                }
            } catch(e: Throwable) { ready.completeExceptionally(e);throw e }
            finally { running.set(false);recorder?.let { try { if(it.recordingState==AudioRecord.RECORDSTATE_RECORDING) it.stop() } finally { it.release() } } }
        }
        try { ready.await() } catch(e: Throwable) { running.set(false);throw e }
    }
    suspend fun stop() { running.set(false);worker?.await() }
}

data class VoiceDraft(val client: MobileClient,val scope: AISharingScope,val key: String,val version: String,val draftId: String,val threadId: String?,val text: String) {
    val certificate get()=client.codexDirect?.fingerprint
}

/** All callbacks are fenced by the original target, draft version, durable permission and generation. */
@Stable
class VoiceInputController(private val context: Context,private val store: VoiceRecordingStore,private val commands: ()->AgentCommands) {
    enum class Phase { IDLE, REQUESTING, LISTENING, FINISHING, FAILED }
    var phase by mutableStateOf(Phase.IDLE);private set
    var error by mutableStateOf<String?>(null);private set
    var levels by mutableStateOf(List(9) { 0f });private set
    var permissionRequest by mutableStateOf<String?>(null);private set
    val active get()=phase!=Phase.IDLE
    val status get()=when(phase) {
        Phase.IDLE->"";Phase.REQUESTING->"正在连接电脑转写…";Phase.LISTENING->"正在录音，结束后由电脑转写";Phase.FINISHING->"电脑正在转写…";Phase.FAILED->error ?: "录音已保留，点击重试查询转写"
    }
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var generation=""
    private var draft: VoiceDraft?=null
    private var pending: PendingVoiceRecording?=null
    private var capture: NativeAudioCapture?=null
    private var work: Job?=null
    private var monitor: Job?=null
    private var allowed: (() -> Boolean)?=null
    private var apply: ((VoiceDraft,String)->Boolean)?=null
    private var waiting: CompletableDeferred<Boolean>?=null
    private var cancelling=false
    private val audio=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest?=null
    fun start(original: VoiceDraft,allowed: ()->Boolean,apply: (VoiceDraft,String)->Boolean,restore: Boolean=true) {
        if(active) return
        generation=UUID.randomUUID().toString();val id=generation
        draft=original;this.allowed=allowed;this.apply=apply;pending=null;error=null;levels=List(9) { 0f };phase=Phase.REQUESTING
        work=tasks.launch {
            try {
                check(allowed()) { "录音目标或草稿已变化" }
                if(restore) {
                    val saved=store.restore(original.scope,original.certificate,original.key,original.text)
                    if(saved!=null) { pending=saved;checkCurrent(id);phase=Phase.FAILED;error="已恢复此草稿的原录音。点击重试查询同一转写；不会自动重新提交。";return@launch }
                }
                val ready=commands().voiceReadiness(original.client);checkCurrent(id)
                check(ready.available) { ready.reason ?: "电脑转写暂不可用，请检查 Codex 登录。原草稿已保留。" }
                check(ready.maxBytes>=44 && ready.maxDurationMs>0) { "电脑未提供有效的录音限制" }
                if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
                    val result=CompletableDeferred<Boolean>();waiting=result;permissionRequest=id
                    val granted=result.await();waiting=null;permissionRequest=null;checkCurrent(id)
                    check(granted) { "麦克风权限未开启，请在系统设置中允许访问麦克风。" }
                }
                checkCurrent(id)
                val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { change->if(change<0 && generation==id && phase==Phase.LISTENING) suspendRecording("录音被系统中断，原录音已保留。") }.build()
                check(audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "麦克风正被其他应用占用，请稍后重试。" };focus=request
                pending=store.begin(original.scope,original.certificate,original.key,original.draftId,original.threadId,original.text);checkCurrent(id)
                val recorder=NativeAudioCapture(store.file(pending!!.id),tasks) { level->if(generation==id && phase==Phase.LISTENING) levels=levels.drop(1)+level }
                capture=recorder;recorder.start();checkCurrent(id);phase=Phase.LISTENING
                monitor=tasks.launch {
                    while(generation==id && phase==Phase.LISTENING) {
                        delay(100)
                        if(allowed()!=true) { suspendRecording("录音目标、共享许可或草稿已变化，原录音已保留。");return@launch }
                        if(recorder.ended) { suspendRecording("录音已停止，原录音已保留。点击重试转写。");return@launch }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: OutOfMemoryError) { if(generation==id) { releaseFocus();phase=Phase.FAILED;error="手机内存不足，原录音和草稿已保留，请稍后重试。" } }
            catch(e: Exception) { if(generation==id) { releaseFocus();phase=Phase.FAILED;error=e.message ?: "无法开始录音，原草稿已保留" } }
        }
    }
    private fun checkCurrent(id: String) { check(generation==id && allowed?.invoke()==true) { "共享许可、录音目标或草稿已变化，原录音已保留。" } }
    fun permissionResult(id: String,granted: Boolean) { if(permissionRequest==id && generation==id) waiting?.complete(granted) }
    private fun releaseFocus() { focus?.let { audio.abandonAudioFocusRequest(it) };focus=null }
    fun finish() {
        if(phase !in listOf(Phase.LISTENING,Phase.FAILED)) return
        val original=draft ?: return;val id=generation;phase=Phase.FINISHING;error=null;monitor?.cancel()
        work=tasks.launch {
            try {
                val recorder=capture;capture=null
                val stopped=runCatching { recorder?.stop() };releaseFocus();checkCurrent(id)
                if(stopped.isFailure && pending?.let { store.file(it.id).length()<=44 }!=false) throw stopped.exceptionOrNull()!!
                val record=pending ?: error("尚无可重试的录音，请重新录音。")
                val sealed=if(record.manifest==null) store.seal(record) else record
                pending=sealed;checkCurrent(id)
                val text=VoiceTranscriptionFlow(commands(),store).transcribe(original.client,sealed) { generation==id && allowed?.invoke()==true }
                checkCurrent(id)
                check(apply?.invoke(original,text)==true) { "原草稿已变化，转写结果和录音已保留。" }
                store.remove(sealed)
                if(generation==id) clear()
            } catch(e: CancellationException) { throw e }
            catch(_: OutOfMemoryError) { if(generation==id) { releaseFocus();phase=Phase.FAILED;error="手机内存不足，原录音和草稿已保留，请稍后重试。" } }
            catch(e: Exception) { if(generation==id) { releaseFocus();phase=Phase.FAILED;error=e.message ?: "录音已保留，点击重试查询转写" } }
        }
    }
    /** Background/target changes stop local capture and preserve audio, without submitting. */
    fun suspendRecording(message: String="已离开录音输入，原录音已保留。") {
        if(!active || cancelling) return
        val recorder=capture;capture=null;generation=UUID.randomUUID().toString();val id=generation
        monitor?.cancel();work?.cancel();waiting?.cancel();waiting=null;permissionRequest=null;releaseFocus();phase=Phase.FINISHING
        work=tasks.launch {
            try { runCatching { recorder?.stop() };pending?.let { if(it.manifest==null && store.file(it.id).length()>44) pending=store.seal(it) } }
            catch(_: OutOfMemoryError) { if(generation==id) error="手机内存不足，原录音和草稿已保留，请稍后重试。" }
            catch(e: Exception) { if(generation==id) error=e.message }
            finally { if(generation==id) { phase=Phase.FAILED;error=error ?: message } }
        }
    }
    fun cancel() {
        if(!active || cancelling) return
        cancelling=true
        val recorder=capture;capture=null;val record=pending
        generation=UUID.randomUUID().toString();monitor?.cancel();work?.cancel();waiting?.cancel();waiting=null;permissionRequest=null;releaseFocus();phase=Phase.FINISHING
        work=tasks.launch {
            try { runCatching { recorder?.stop() };if(record!=null && record.manifest==null) store.remove(record,unsealedOnly=true) }
            catch(_: OutOfMemoryError) { cancelling=false;error="手机内存不足，原录音已保留，请稍后重试。";phase=Phase.FAILED;return@launch }
            catch(e: Exception) { cancelling=false;error=e.message;phase=Phase.FAILED;return@launch }
            clear()
        }
    }
    fun rerecord() {
        if(phase!=Phase.FAILED) return
        val original=draft ?: return;val guard=allowed ?: return;val change=apply ?: return
        if(!guard()) { error="原草稿或目标已变化，请先取消，再在当前草稿开始录音。";return }
        val record=pending;clear()
        work=tasks.launch {
            if(record!=null && record.manifest==null) { try { store.remove(record,true) } catch(_: OutOfMemoryError) { error="手机内存不足，原录音已保留，请稍后重试。";phase=Phase.FAILED;return@launch } catch(e: Exception) { error=e.message;phase=Phase.FAILED;return@launch } }
            // Sealed/unknown old operations stay in private storage; a new capture gets a new ID.
            start(original,guard,change,restore=false)
        }
    }
    private fun clear() { generation=UUID.randomUUID().toString();cancelling=false;releaseFocus();draft=null;pending=null;capture=null;permissionRequest=null;waiting=null;phase=Phase.IDLE;error=null;levels=List(9) { 0f } }
}
