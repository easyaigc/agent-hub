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
    enum class Provider { DESKTOP, SYSTEM }
    var provider by mutableStateOf(Provider.DESKTOP);private set
    var transcript by mutableStateOf("");private set
    internal val capturedText get()=draft?.text
    val canRetry get()=provider==Provider.DESKTOP && phase==Phase.FAILED && pending!=null && !discardFailed
    var discarding by mutableStateOf(false);private set
    var discardFailed by mutableStateOf(false);private set
    internal var discardPort: NativeVoiceDiscardPort?=null
    private var discardRestartAllowed=false
    private var discardPresentationDetached=false
    val canFillRecognizedText get()=provider==Provider.SYSTEM && phase==Phase.FAILED && transcript.isNotBlank() && allowed?.invoke()==true
    val canRerecordWithSystem get()=provider==Provider.DESKTOP && phase==Phase.FAILED && allowed?.invoke()==true
    internal var systemFactory: ()->NativeSystemVoice = { AndroidSystemVoice(context.applicationContext) }
    private var systemVoice: NativeSystemVoice?=null
    private var systemTimeout: Job?=null
    var phase by mutableStateOf(Phase.IDLE);private set
    var error by mutableStateOf<String?>(null);private set
    var levels by mutableStateOf(List(9) { 0f });private set
    var permissionRequest by mutableStateOf<String?>(null);private set
    val active get()=phase!=Phase.IDLE
    val status get()=when {
        discarding->"正在结束语音输入…"
        provider==Provider.SYSTEM && phase==Phase.REQUESTING->"准备本地听写…"
        provider==Provider.SYSTEM && phase==Phase.LISTENING->"正在本地听写"
        provider==Provider.SYSTEM && phase==Phase.FINISHING->"正在完成本地听写…"
        else->when(phase) {
        Phase.IDLE->"";Phase.REQUESTING->"正在连接电脑转写…";Phase.LISTENING->"正在录音，结束后由电脑转写";Phase.FINISHING->"电脑正在转写…";Phase.FAILED->error ?: "录音已保留，点击重试查询转写"
    }
    }
    // Only known local states supply a product copy key. Arbitrary Host/OS errors stay literal.
    private var errorCopyKey by mutableStateOf<String?>(null)
    internal val statusCopyKey get()=when {
        discarding->"正在结束语音输入…"
        provider==Provider.SYSTEM && phase in listOf(Phase.REQUESTING,Phase.LISTENING,Phase.FINISHING)->status
        else->when(phase) {
        Phase.IDLE->"";Phase.REQUESTING->"正在连接电脑转写…";Phase.LISTENING->"正在录音，结束后由电脑转写";Phase.FINISHING->"电脑正在转写…"
        Phase.FAILED->if(error==null)"录音已保留，点击重试查询转写"else errorCopyKey?.takeIf {it==error}
    }
    }
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var generation=""
    private var draft: VoiceDraft?=null
    private var pending: PendingVoiceRecording?=null
    private var registration: CompletableDeferred<PendingVoiceRecording?>?=null
    private var capture: NativeAudioCapture?=null
    private var work: Job?=null
    private var monitor: Job?=null
    private var allowed: (() -> Boolean)?=null
    private var apply: ((VoiceDraft,String)->Boolean)?=null
    private var waiting: CompletableDeferred<Boolean>?=null
    private var cancelling=false
    private val audio=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest?=null
    fun start(original: VoiceDraft,allowed: ()->Boolean,apply: (VoiceDraft,String)->Boolean,restore: Boolean=true,provider: Provider=Provider.DESKTOP) {
        if(active) return
        this.provider=provider;transcript="";discardFailed=false;discarding=false;registration=null
        generation=UUID.randomUUID().toString();val id=generation
        draft=original;this.allowed=allowed;this.apply=apply;pending=null;error=null;errorCopyKey=null;levels=List(9) { 0f };phase=Phase.REQUESTING
        work=tasks.launch {
            try {
                localCheck(allowed(),"录音目标或草稿已变化")
                if(provider==Provider.SYSTEM) { startSystem(id);return@launch }
                if(restore) {
                    val saved=store.restore(original.scope,original.certificate,original.key,original.text)
                    if(saved!=null) { pending=saved;checkCurrent(id);phase=Phase.FAILED;error="已恢复此草稿的原录音。点击重试查询同一转写；不会自动重新提交。";errorCopyKey=error;return@launch }
                }
                val ready=commands().voiceReadiness(original.client);checkCurrent(id)
                if(!ready.available)throw ready.reason?.let { IllegalStateException(it) } ?: NativeVoiceFailure("电脑转写暂不可用，请检查 Codex 登录。原草稿已保留。")
                localCheck(ready.maxBytes>=44 && ready.maxDurationMs>0,"电脑未提供有效的录音限制")
                if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
                    val result=CompletableDeferred<Boolean>();waiting=result;permissionRequest=id
                    val granted=result.await();waiting=null;permissionRequest=null;checkCurrent(id)
                    localCheck(granted,"麦克风权限未开启，请在系统设置中允许访问麦克风。")
                }
                checkCurrent(id)
                val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { change->if(change<0 && generation==id && phase==Phase.LISTENING) suspendRecording(NativeVoiceFailure("录音被系统中断，原录音已保留。")) }.build()
                localCheck(audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED,"麦克风正被其他应用占用，请稍后重试。");focus=request
                // Local registration can commit while cancellation is arriving. Keep its exact result for discard.
                val registered=CompletableDeferred<PendingVoiceRecording?>();registration=registered
                withContext(NonCancellable) {
                    try {
                        val created=store.begin(original.scope,original.certificate,original.key,original.draftId,original.threadId,original.text)
                        registered.complete(created);if(generation==id)pending=created
                    }catch(failure: Exception) { registered.completeExceptionally(failure);throw failure }
                }
                checkCurrent(id)
                val recorder=NativeAudioCapture(store.file(pending!!.id),tasks) { level->if(generation==id && phase==Phase.LISTENING) levels=levels.drop(1)+level }
                capture=recorder;recorder.start();checkCurrent(id);phase=Phase.LISTENING
                monitor=tasks.launch {
                    while(generation==id && phase==Phase.LISTENING) {
                        delay(100)
                        if(allowed()!=true) { suspendRecording(NativeVoiceFailure("录音目标、共享许可或草稿已变化，原录音已保留。"));return@launch }
                        if(recorder.ended) { suspendRecording(NativeVoiceFailure("录音已停止，原录音已保留。点击重试转写。"));return@launch }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: OutOfMemoryError) { if(generation==id) { if(provider==Provider.SYSTEM)systemFailed(id,NativeVoiceFailure("手机内存不足，原草稿已保留，请稍后重试。"))else { releaseFocus();phase=Phase.FAILED;error="手机内存不足，原录音和草稿已保留，请稍后重试。";errorCopyKey=error } } }
            catch(e: Exception) { if(generation==id) { if(provider==Provider.SYSTEM)systemFailed(id,e)else { releaseFocus();phase=Phase.FAILED;error=e.message ?: "无法开始录音，原草稿已保留";errorCopyKey=(e as? NativeVoiceFailure)?.copyKey } } }
        }
    }
    private fun localCheck(value: Boolean,key: String) { if(!value)throw NativeVoiceFailure(key) }
    private suspend fun startSystem(id: String) {
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            val result=CompletableDeferred<Boolean>();waiting=result;permissionRequest=id
            val granted=result.await();waiting=null;permissionRequest=null;checkCurrent(id)
            localCheck(granted,"麦克风权限未开启，请在系统设置中允许访问麦克风。")
        }
        checkCurrent(id);localCheck(!audio.isMicrophoneMute,"麦克风已被系统静音，请解除静音后重试。原草稿已保留。")
        val engine=systemFactory();systemVoice=engine;systemDeadline(id,15_000)
        fun current()=generation==id && allowed?.invoke()==true
        engine.start(object: NativeSystemVoice.Listener {
            override fun ready() { if(current() && phase==Phase.REQUESTING) { phase=Phase.LISTENING;systemDeadline(id,120_000) } }
            override fun level(value: Float) { if(current() && phase==Phase.LISTENING) levels=levels.drop(1)+value }
            override fun partial(text: String) { if(current())transcript=text }
            override fun ended() { if(current() && phase in listOf(Phase.REQUESTING,Phase.LISTENING)) { phase=Phase.FINISHING;systemDeadline(id,15_000) } }
            override fun result(text: String) {
                if(!current())return
                try {
                    localCheck(text.isNotBlank(),"没有识别到语音，请重新录音。")
                    val original=draft ?: return
                    localCheck(apply?.invoke(original,text)==true,"原草稿或目标已变化，请先取消，再在当前草稿开始录音。")
                    clear()
                }catch(e: Exception) { systemFailed(id,e) }
            }
            override fun failed(code: Int) {
                if(!current())return
                val key=when(code) {
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"麦克风权限未开启，请在系统设置中允许访问麦克风。"
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH,android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"没有识别到语音，请重新录音。"
                    else->null
                }
                systemFailed(id,if(key!=null)NativeVoiceFailure(key)else IllegalStateException("SpeechRecognizer error $code"))
            }
        })
        monitor=tasks.launch {
            while(generation==id && phase in listOf(Phase.REQUESTING,Phase.LISTENING,Phase.FINISHING)) {
                delay(100)
                if(!current()) { suspendRecording();return@launch }
                if(audio.isMicrophoneMute) { systemFailed(id,NativeVoiceFailure("麦克风已被系统静音，请解除静音后重试。原草稿已保留。"));return@launch }
            }
        }
    }
    private fun systemDeadline(id: String,millis: Long) {
        systemTimeout?.cancel();systemTimeout=tasks.launch {
            delay(millis)
            if(generation==id)systemFailed(id,NativeVoiceFailure("本地听写超时，原草稿已保留，请重试。"))
        }
    }
    private fun stopSystem() {
        systemTimeout?.cancel();systemTimeout=null
        val engine=systemVoice;systemVoice=null
        engine?.cancel()
    }
    private fun systemFailed(id: String,failure: Exception) {
        if(generation!=id)return
        generation=UUID.randomUUID().toString();monitor?.cancel();stopSystem();releaseFocus()
        phase=Phase.FAILED;error=failure.message ?: "本地听写失败，原草稿已保留。";errorCopyKey=(failure as? NativeVoiceFailure)?.copyKey
    }
    private fun checkCurrent(id: String) { localCheck(generation==id && allowed?.invoke()==true,"共享许可、录音目标或草稿已变化，原录音已保留。") }
    fun permissionResult(id: String,granted: Boolean) { if(permissionRequest==id && generation==id) waiting?.complete(granted) }
    private fun releaseFocus() { focus?.let { audio.abandonAudioFocusRequest(it) };focus=null }
    fun finish() {
        if(discarding || discardFailed)return
        if(provider==Provider.SYSTEM) {
            if(phase!=Phase.LISTENING)return
            phase=Phase.FINISHING
            try { systemVoice?.finish();systemDeadline(generation,15_000) } catch(e: Exception) { systemFailed(generation,e) }
            return
        }
        if(phase !in listOf(Phase.LISTENING,Phase.FAILED)) return
        val original=draft ?: return;val id=generation;phase=Phase.FINISHING;error=null;errorCopyKey=null;monitor?.cancel()
        work=tasks.launch {
            try {
                val recorder=capture;capture=null
                val stopped=runCatching { recorder?.stop() };releaseFocus();checkCurrent(id)
                if(stopped.isFailure && pending?.let { store.file(it.id).length()<=44 }!=false) throw stopped.exceptionOrNull()!!
                val record=pending ?: throw NativeVoiceFailure("尚无可重试的录音，请重新录音。")
                val sealed=if(record.manifest==null) store.seal(record) else record
                pending=sealed;checkCurrent(id)
                val text=VoiceTranscriptionFlow(commands(),store).transcribe(original.client,sealed) { generation==id && allowed?.invoke()==true }
                checkCurrent(id)
                localCheck(apply?.invoke(original,text)==true,"原草稿已变化，转写结果和录音已保留。")
                store.remove(sealed)
                if(generation==id) clear()
            } catch(e: CancellationException) { throw e }
            catch(_: OutOfMemoryError) { if(generation==id) { releaseFocus();phase=Phase.FAILED;error="手机内存不足，原录音和草稿已保留，请稍后重试。" } }
            catch(e: Exception) { if(generation==id) { releaseFocus();phase=Phase.FAILED;error=e.message ?: "录音已保留，点击重试查询转写";errorCopyKey=if(e is ImportedVoiceReceiptOnly)ImportedVoiceReceiptOnly.MESSAGE else (e as? NativeVoiceFailure)?.copyKey } }
        }
    }
    /** Explicitly use already recognized text after interruption; no microphone, file transcription or upload. */
    fun fillRecognizedText(): Boolean {
        if(!canFillRecognizedText)return false
        val original=draft ?: return false
        if(apply?.invoke(original,transcript)!=true) {
            error="原草稿或目标已变化，请先取消，再在当前草稿开始录音。";errorCopyKey=error;return false
        }
        clear();return true
    }
    /** Background/target changes stop local capture and preserve audio, without submitting. */
    fun suspendRecording() = suspendRecording(NativeVoiceFailure("已离开录音输入，原录音已保留。"))
    private fun suspendRecording(failure: NativeVoiceFailure) {
        if(discarding) { discardRestartAllowed=false;return }
        if(!active || cancelling) return
        if(provider==Provider.SYSTEM) {
            generation=UUID.randomUUID().toString();work?.cancel();monitor?.cancel();waiting?.cancel();waiting=null;permissionRequest=null
            stopSystem();releaseFocus();phase=Phase.FAILED;error="已离开语音输入，原草稿已保留。";errorCopyKey=error;return
        }
        val recorder=capture;capture=null;generation=UUID.randomUUID().toString();val id=generation
        monitor?.cancel();work?.cancel();waiting?.cancel();waiting=null;permissionRequest=null;releaseFocus();phase=Phase.FINISHING
        work=tasks.launch {
            try { runCatching { recorder?.stop() };pending?.let { if(it.manifest==null && store.file(it.id).length()>44) pending=store.seal(it) } }
            catch(_: OutOfMemoryError) { if(generation==id) error="手机内存不足，原录音和草稿已保留，请稍后重试。" }
            catch(e: Exception) { if(generation==id) error=e.message }
            finally { if(generation==id) { phase=Phase.FAILED;if(error==null) { error=failure.copyKey;errorCopyKey=failure.copyKey } } }
        }
    }
    /** Navigation/presentation cancellation preserves the original recovery semantics; UI discard is separate. */
    fun cancel() {
        if(discarding) { discardRestartAllowed=false;discardPresentationDetached=true;return }
        if(!active || cancelling) return
        cancelling=true
        if(provider==Provider.SYSTEM) { generation=UUID.randomUUID().toString();work?.cancel();monitor?.cancel();stopSystem();clear();return }
        val recorder=capture;capture=null;val record=pending
        generation=UUID.randomUUID().toString();monitor?.cancel();work?.cancel();waiting?.cancel();waiting=null;permissionRequest=null;releaseFocus();phase=Phase.FINISHING
        work=tasks.launch {
            try { runCatching { recorder?.stop() };if(record!=null && record.manifest==null) store.remove(record,unsealedOnly=true) }
            catch(_: OutOfMemoryError) { cancelling=false;error="手机内存不足，原录音已保留，请稍后重试。";phase=Phase.FAILED;return@launch }
            catch(e: Exception) { cancelling=false;error=e.message;phase=Phase.FAILED;return@launch }
            clear()
        }
    }
    fun rerecord()=rerecordWith(provider)
    fun rerecordWith(nextProvider: Provider) {
        if(phase!=Phase.FAILED) return
        val guard=allowed ?: return
        if(!guard()) { error="原草稿或目标已变化，请先取消，再在当前草稿开始录音。";errorCopyKey=error;return }
        discardThen(nextProvider)
    }
    fun discard() { if(active && !discarding)discardThen(null) }
    private fun discardThen(nextProvider: Provider?) {
        if(cancelling || discarding)return
        if(provider==Provider.SYSTEM) {
            val original=draft;val guard=allowed;val change=apply
            try {
                cancel()
                if(nextProvider!=null && original!=null && guard?.invoke()==true && change!=null)start(original,guard,change,restore=false,provider=nextProvider)
            }catch(_: Exception) { cancelling=false;discardFailed=true;phase=Phase.FAILED;error="无法丢弃录音，请重试。";errorCopyKey=error }
            return
        }
        val original=draft ?: return;val guard=allowed ?: return;val change=apply ?: return
        val oldWork=work;val recorder=capture;val registered=registration;val previous=pending
        generation=UUID.randomUUID().toString();val id=generation;discardRestartAllowed=nextProvider!=null;discardPresentationDetached=false
        discarding=true;discardFailed=false;cancelling=true;error=null;errorCopyKey=null;phase=Phase.FINISHING
        monitor?.cancel();oldWork?.cancel();waiting?.cancel();waiting=null;permissionRequest=null;releaseFocus()
        work=tasks.launch {
            try {
                withTimeout(10_000) {
                    oldWork?.join()
                    val stopped=runCatching { recorder?.stop() }
                    if(stopped.isFailure && recorder?.ended!=true)throw stopped.exceptionOrNull()!!
                    if(generation!=id)return@withTimeout
                    capture=null
                    val record=previous ?: registered?.await()
                    if(record!=null) {
                        pending=record
                        val request=NativeVoiceDiscardRequest(record)
                        val owner=discardPort ?: throw NativeVoiceFailure("无法丢弃录音，请重试。")
                        localCheck(owner.discard(request).acknowledges(request),"无法丢弃录音，请重试。")
                    }
                }
                if(generation!=id)return@launch
                val restart=discardRestartAllowed && nextProvider!=null && guard()
                clear()
                if(restart)start(original,guard,change,restore=false,provider=nextProvider!!)
            }catch(_: Exception) {
                if(generation==id) {
                    if(discardPresentationDetached && (recorder==null || recorder.ended)) { clear();return@launch }
                    if(recorder?.ended!=true && recorder!=null)capture=recorder
                    discarding=false;discardFailed=true;cancelling=false;phase=Phase.FAILED
                    error="无法丢弃录音，请重试。";errorCopyKey=error
                }
            }catch(_: OutOfMemoryError) {
                if(generation==id) { discarding=false;discardFailed=true;cancelling=false;phase=Phase.FAILED;error="无法丢弃录音，请重试。";errorCopyKey=error }
            }
        }
    }
    private fun clear() { generation=UUID.randomUUID().toString();stopSystem();transcript="";waiting?.cancel();cancelling=false;discarding=false;discardFailed=false;discardRestartAllowed=false;discardPresentationDetached=false;registration=null;releaseFocus();draft=null;pending=null;capture=null;permissionRequest=null;waiting=null;phase=Phase.IDLE;error=null;errorCopyKey=null;levels=List(9) { 0f } }
}
