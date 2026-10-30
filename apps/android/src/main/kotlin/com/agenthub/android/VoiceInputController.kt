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
    val ended get()=worker?.isCompleted!=false
    suspend fun start() {
        running.set(true)
        val ready=CompletableDeferred<Unit>()
        worker=scope.async(Dispatchers.IO) {
            var recorder: AudioRecord?=null
            var owned: NativeVoiceRawGate.Writer?=null
            try {
                val rawWriter=NativeVoiceRawOwnership.writer(file).also {owned=it} // IO, before microphone or rw open.
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
                rawWriter.requireCurrent()
                RandomAccessFile(file,"rw").use { out->
                    android.system.Os.chmod(file.path,0x180);out.setLength(0);out.write(WavePCM.header(0))
                    val buffer=ShortArray(1600);val bytes=ByteArray(buffer.size*2);var count=0
                    while(running.get() && count<9_600_000) {
                        ensureActive()
                        val n=input.read(buffer,0,minOf(buffer.size,(9_600_000-count)/2),AudioRecord.READ_BLOCKING)
                        check(n>0) { "录音已被系统中断，原文件已保留。" }
                        var power=0.0
                        for(i in 0 until n) { val value=buffer[i].toInt();bytes[i*2]=value.toByte();bytes[i*2+1]=(value shr 8).toByte();power+=value.toDouble()*value }
                        rawWriter.requireCurrent();out.write(bytes,0,n*2);count+=n*2
                        // A process death leaves recoverable PCM; the final header is sealed before networking.
                        if(count%32000<n*2) out.fd.sync()
                        val level=(sqrt(power/n)/5000).toFloat().coerceIn(0f,1f)
                        withContext(Dispatchers.Main.immediate) { meter(level) }
                    }
                    rawWriter.requireCurrent();out.seek(0);out.write(WavePCM.header(count));out.fd.sync()
                }
            } catch(e: Throwable) { ready.completeExceptionally(e);throw e }
            finally { try {running.set(false);recorder?.let { try { if(it.recordingState==AudioRecord.RECORDSTATE_RECORDING) it.stop() } finally { it.release() } }} finally {owned?.close()} }
        }
        worker!!.invokeOnCompletion {error->if(error!=null)ready.completeExceptionally(error)}
        try { ready.await() } catch(e: Throwable) { running.set(false);withContext(NonCancellable){worker?.join()};throw e }
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
    val canRetry get()=provider==Provider.DESKTOP && phase==Phase.FAILED && hasRetryableRecording && !discardFailed && !savedFileBusy && !savedFileClosing
    // A provisional/empty file is not a retryable transcription. A sealed restored original is.
    private var capturedAudioAvailable by mutableStateOf(false)
    internal val hasRetryableRecording get()=provider==Provider.DESKTOP && (pending?.manifest!=null || capturedAudioAvailable)
    internal val failureTitleKey get()=if(hasRetryableRecording || canFillRecognizedText)"转写失败"else"录音未完成"
    var discarding by mutableStateOf(false);private set
    var discardFailed by mutableStateOf(false);private set
    internal var discardPort: NativeVoiceDiscardPort?=null
    private var discardRestartAllowed=false
    private var discardPresentationDetached=false
    val canFillRecognizedText get()=provider==Provider.SYSTEM && phase==Phase.FAILED && transcript.isNotBlank() && allowed?.invoke()==true
    val canRecordAgain get()=phase==Phase.FAILED && !discarding && !discardFailed && !cancelling && !savedFileBusy && !savedFileClosing && allowed?.invoke()==true
    val canEndWaiting get()=provider==Provider.DESKTOP && phase in listOf(Phase.FINISHING,Phase.FAILED) && !discarding && !cancelling
    // A fresh phone capture cannot retire or abandon a provisional/in-flight original recording.
    val canRerecordWithSystem get()=provider==Provider.DESKTOP && phase==Phase.FAILED && !discarding && !discardFailed && !cancelling && !savedFileBusy && !savedFileClosing && localAllowed?.invoke()==true && capture==null &&
        (pending?.manifest!=null || pending==null && registration==null)
    internal var localResultPort: NativeVoiceLocalResultPort?=null
    internal var savedFileFactory: ()->NativeSavedVoiceSpeech = { VoskSavedVoiceSpeech(context) }
    internal var savedFileLocale by mutableStateOf(when(java.util.Locale.getDefault().language){"zh"->"zh-CN";"en"->"en-US";else->""});private set
    internal var savedFileProbe by mutableStateOf<SavedVoiceProbe?>(null);private set
    internal var savedFileBusy by mutableStateOf(false);private set
    private var savedFileAction by mutableStateOf(SavedVoiceAction.PROBE)
    private var savedFileFailureKey by mutableStateOf<String?>(null)
    private var savedFileClosing by mutableStateOf(false)
    private var savedFileDetachRequested=false
    private var localDestination: NativeVoiceDraftDestinationPort?=null
    internal val canInspectSavedFile get()=provider==Provider.DESKTOP && pending?.manifest!=null && capture==null && phase==Phase.FAILED && !discarding && !discardFailed && !cancelling && !savedFileBusy && !savedFileClosing && localAllowed?.invoke()==true
    internal val canTranscribeSavedFile get()=canInspectSavedFile && localResultPort!=null && localDestination!=null && savedFileProbe?.availability==SavedVoiceAvailability.INSTALLED
    internal val savedFileStatusKey get()=when {
        savedFileClosing->"正在结束原录音识别…"
        savedFileBusy->savedFileAction.busyKey
        savedFileFailureKey!=null->savedFileFailureKey!!
        savedFileLocale.isEmpty()->"请选择中文或英语模型。"
        localResultPort==null || localDestination==null->"本机原录音识别暂不可用。"
        else->when(savedFileProbe?.availability) {
            null->"检查本机原录音识别资源"
            SavedVoiceAvailability.UNSUPPORTED->"此设备暂不支持原录音识别。"
            SavedVoiceAvailability.MISSING->"需要下载所选语言的离线模型。"
            SavedVoiceAvailability.CORRUPT->"离线模型校验失败，请重新下载。"
            SavedVoiceAvailability.INSTALLED->"离线模型已校验，可识别原录音。"
        }
    }
    private var savedFileWorker: Job?=null
    private var localAllowed: (() -> Boolean)?=null
    private var localApply: ((VoiceDraft,String)->Boolean)?=null
    internal var systemFactory: ()->NativeSystemVoice = { AndroidSystemVoice(context.applicationContext) }
    private var systemVoice: NativeSystemVoice?=null
    private var systemTimeout: Job?=null
    var phase by mutableStateOf(Phase.IDLE);private set
    var error by mutableStateOf<String?>(null);private set
    var levels by mutableStateOf(List(9) { 0f });private set
    var permissionRequest by mutableStateOf<String?>(null);private set
    val active get()=phase!=Phase.IDLE
    val status get()=when {
        savedFileBusy || savedFileClosing->savedFileStatusKey
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
        savedFileBusy || savedFileClosing->savedFileStatusKey
        discarding->"正在结束语音输入…"
        provider==Provider.SYSTEM && phase in listOf(Phase.REQUESTING,Phase.LISTENING,Phase.FINISHING)->status
        else->when(phase) {
        Phase.IDLE->"";Phase.REQUESTING->"正在连接电脑转写…";Phase.LISTENING->"正在录音，结束后由电脑转写";Phase.FINISHING->"电脑正在转写…"
        Phase.FAILED->if(error==null)"录音已保留，点击重试查询转写"else errorCopyKey?.takeIf {it==error}
    }
    }
    private val migrationIo=NativeMigrationIoWork()
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate+migrationIo)
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
    internal fun start(original: VoiceDraft,allowed: ()->Boolean,apply: (VoiceDraft,String)->Boolean,restore: Boolean=true,provider: Provider=Provider.DESKTOP,localAllowed: ()->Boolean=allowed,localApply: (VoiceDraft,String)->Boolean=apply,restoreOnly: Boolean=false,localDestination: NativeVoiceDraftDestinationPort?=null) {
        if(active || savedFileBusy || savedFileClosing) return
        this.provider=provider;transcript="";capturedAudioAvailable=false;discardFailed=false;discarding=false;registration=null
        generation=UUID.randomUUID().toString();val id=generation
        draft=original;this.allowed=allowed;this.apply=apply;this.localAllowed=localAllowed;this.localApply=localApply;this.localDestination=localDestination;savedFileDetachRequested=false;savedFileProbe=null;savedFileFailureKey=null;pending=null;error=null;errorCopyKey=null;levels=List(9) { 0f };phase=Phase.REQUESTING
        work=tasks.launch {
            try {
                localCheck(localAllowed(),"录音目标或草稿已变化")
                if(provider==Provider.SYSTEM) { startSystem(id);return@launch }
                if(restore) {
                    val saved=store.restore(original.scope,original.certificate,original.key,original.text)
                    if(saved!=null) { pending=saved;checkLocalFileCurrent(id);phase=Phase.FAILED;error=if(saved.manifest==null)"已恢复此草稿未完成的录音记录，不能重试转写；不会自动提交。"else"已恢复此草稿的原录音。点击重试查询同一转写；不会自动重新提交。";errorCopyKey=error;return@launch }
                }
                if(restoreOnly)throw NativeVoiceFailure("没有与当前草稿匹配的已保存原录音。")
                checkLocalFileCurrent(id)
                if(!allowed())throw NativeVoiceFailure("电脑转写需要共享许可，原草稿和录音已保留。")
                checkCurrent(id)
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
        if(discarding || discardFailed || savedFileBusy || savedFileClosing)return
        if(provider==Provider.SYSTEM) {
            if(phase!=Phase.LISTENING)return
            val id=generation;phase=Phase.FINISHING
            try {
                systemVoice?.finish()
                if(generation==id && phase==Phase.FINISHING)systemDeadline(id,15_000)
            } catch(e: Exception) { systemFailed(id,e) }
            return
        }
        if(phase==Phase.FAILED && !hasRetryableRecording)return // A restored unsealed original is never repaired/submitted by Retry.
        if(phase !in listOf(Phase.LISTENING,Phase.FAILED)) return
        val original=draft ?: return;val id=generation;phase=Phase.FINISHING;error=null;errorCopyKey=null;monitor?.cancel()
        work=tasks.launch {
            try {
                val recorder=capture;capture=null
                val stopped=runCatching { recorder?.stop() };releaseFocus();checkCurrent(id)
                if(stopped.isFailure && pending?.let { store.file(it.id).length()<=44 }!=false) throw stopped.exceptionOrNull()!!
                val record=pending ?: throw NativeVoiceFailure("尚无可重试的录音，请重新录音。")
                val audioAvailable=record.manifest!=null || withContext(Dispatchers.IO){store.file(record.id).length()>44}
                checkCurrent(id);capturedAudioAvailable=audioAvailable
                localCheck(capturedAudioAvailable,"尚无可重试的录音，请重新录音。")
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
    private fun checkLocalFileCurrent(id: String) {
        localCheck(generation==id && localAllowed?.invoke()==true,"原草稿或目标已变化，本机识别结果和原录音已保留。")
    }
    internal fun chooseSavedFileLocale(locale: String) {
        if(!canInspectSavedFile || locale !in setOf("en-US","zh-CN"))return
        savedFileLocale=locale;savedFileProbe=null;savedFileFailureKey=null
    }
    internal fun inspectSavedFile()=savedFileOperation(download=false,recognize=false)
    internal fun downloadSavedFileModel() {
        if(savedFileProbe?.availability !in setOf(SavedVoiceAvailability.MISSING,SavedVoiceAvailability.CORRUPT))return
        savedFileOperation(download=true,recognize=false)
    }
    internal fun transcribeSavedFile() {
        if(canTranscribeSavedFile)savedFileOperation(download=false,recognize=true)
    }
    private fun savedFileOperation(download: Boolean,recognize: Boolean) {
        if(!canInspectSavedFile)return
        val original=pending ?: return;val input=draft ?: return;val owner=localResultPort
        if(recognize && owner==null)return
        val id=generation;val locale=savedFileLocale;val action=UUID.randomUUID().toString()
        savedFileAction=when {download->SavedVoiceAction.INSTALL;recognize->SavedVoiceAction.TRANSCRIBE;else->SavedVoiceAction.PROBE}
        savedFileFailureKey=null
        if(download)savedFileProbe=null // Publication may finish just as a budget expires; explicitly inspect before retry.
        savedFileBusy=true;phase=Phase.FINISHING;error=null;errorCopyKey=null
        val worker=tasks.launch {
            suspend fun checkpoint()=withContext(Dispatchers.Main.immediate) {currentCoroutineContext().ensureActive();checkLocalFileCurrent(id)}
            try {
                val attempt=attemptSavedVoiceAction {
                    checkpoint();val engine=savedFileFactory()
                    if(download)engine.install(locale,::checkpoint)
                    if(!recognize) {
                        val result=engine.probe(locale);checkpoint();savedFileProbe=result
                    }else {
                        // Reuse a staged result only after this new explicit action and exact original check.
                        var result=owner!!.pending(original,locale);checkpoint()
                        if(result==null) {
                            val wav=store.bytes(original)
                            val recognized=try {checkpoint();engine.transcribe(locale,wav,::checkpoint)}finally{wav.fill(0)}
                            checkpoint()
                            result=NativeVoiceLocalResult(action,original.id,NativeVoiceDiscardRequest(original).fingerprint,original.manifest!!.sha256,locale,recognized.modelId,recognized.modelSha256,recognized.text,System.currentTimeMillis())
                            owner.stage(original,result);checkpoint()
                        }
                        result.validate(original);checkpoint()
                        val request=NativeVoiceDraftRequest(input,original,result)
                        val destination=localDestination ?: error("Voice destination persistence unavailable")
                        val receipt=destination.accept(request,::checkpoint);receipt.validate(request)
                        // Destination publication changed its revision. Only generation remains ours to check;
                        // the destination owner already verified the exact original authority before publication.
                        currentCoroutineContext().ensureActive();localCheck(generation==id,"原草稿或目标已变化，本机识别结果和原录音已保留。")
                        withContext(NonCancellable) {owner.applied(original,result,receipt)}
                        if(generation==id)clear()
                    }
                }
                if(attempt is SavedVoiceAttempt.Failed && generation==id) {
                    if(!recognize)savedFileProbe=null
                    val key=(attempt.error as? NativeVoiceFailure)?.copyKey ?: savedFileAction.failureKey(attempt.timedOut)
                    savedFileFailureKey=key;error=key;errorCopyKey=key
                }
            } catch(e: CancellationException){throw e}
            catch(_: OutOfMemoryError){if(generation==id){if(!recognize)savedFileProbe=null;error="手机内存不足，原录音和草稿已保留，请稍后重试。";errorCopyKey=error}}
            catch(e: Exception){if(generation==id){if(!recognize)savedFileProbe=null;val key=(e as? NativeVoiceFailure)?.copyKey ?: savedFileAction.failureKey(false);savedFileFailureKey=key;error=key;errorCopyKey=key}}
            catch(_: LinkageError){if(generation==id){savedFileProbe=SavedVoiceProbe(SavedVoiceAvailability.UNSUPPORTED);error="此设备暂不支持原录音识别。";errorCopyKey=error}}
            finally {
                withContext(NonCancellable+Dispatchers.Main.immediate) {
                    savedFileBusy=false
                    if(generation==id && phase!=Phase.IDLE)phase=Phase.FAILED
                }
            }
        }
        savedFileWorker=worker;work=worker
    }
    private fun closeSavedFile(detach: Boolean) {
        savedFileDetachRequested=savedFileDetachRequested || detach
        if(savedFileClosing)return
        val worker=savedFileWorker ?: return
        generation=UUID.randomUUID().toString();savedFileClosing=true;cancelling=true;worker.cancel();phase=Phase.FINISHING
        work=tasks.launch {
            worker.join() // Native close / download read timeout / model verification must really exit.
            savedFileWorker=null;savedFileClosing=false;cancelling=false
            if(savedFileDetachRequested)clear()else {phase=Phase.FAILED;error="本机原录音识别已停止，原录音和已有结果已保留。";errorCopyKey=error}
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
        if(savedFileBusy || savedFileClosing) { closeSavedFile(detach=false);return }
        if(discarding) { discardRestartAllowed=false;return }
        // A terminal failure owns its original target/callback/error through background return.
        // A failed capture that still owns native resources must finish stopping them.
        if(!active || cancelling || phase==Phase.FAILED && capture==null && systemVoice==null) return
        if(provider==Provider.SYSTEM) {
            generation=UUID.randomUUID().toString();work?.cancel();monitor?.cancel();waiting?.cancel();waiting=null;permissionRequest=null
            stopSystem();releaseFocus();phase=Phase.FAILED;error="已离开语音输入，原草稿已保留。";errorCopyKey=error;return
        }
        val recorder=capture;capture=null;generation=UUID.randomUUID().toString();val id=generation
        monitor?.cancel();work?.cancel();waiting?.cancel();waiting=null;permissionRequest=null;releaseFocus();phase=Phase.FINISHING
        work=tasks.launch {
            try { runCatching { recorder?.stop() };pending?.let { if(it.manifest!=null || store.file(it.id).length()>44) {if(generation==id)capturedAudioAvailable=true;if(it.manifest==null)pending=store.seal(it)} } }
            catch(_: OutOfMemoryError) { if(generation==id) error="手机内存不足，原录音和草稿已保留，请稍后重试。" }
            catch(e: Exception) { if(generation==id) error=e.message }
            finally { if(generation==id) { phase=Phase.FAILED;if(error==null) { error=failure.copyKey;errorCopyKey=failure.copyKey } } }
        }
    }
    /** Migration never stops/finalizes a live recorder or seals a stationary failed original. */
    internal fun requireMigrationRawIdle() {
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
        if(phase !in setOf(Phase.IDLE,Phase.FAILED)||capture?.let {!it.ended}==true||work?.isActive==true||
            discarding||cancelling||savedFileBusy||savedFileClosing||registration?.let {!it.isCompleted}==true)
            throw NativeVoiceRawGate.Busy()
    }
    internal suspend fun quiesceForMigration() {
        requireMigrationRawIdle()
        // Already stationary: cancel/join housekeeping only. Never call suspendRecording/stop/seal.
        val children=tasks.coroutineContext[Job]!!.children.toList()
        children.forEach {it.cancel()};children.forEach {it.join()}
        check(capture==null||capture?.ended==true)
        migrationIo.awaitIdle()
    }
    internal fun retireForMigration(){migrationIo.close();tasks.cancel()}
    /** Navigation/presentation cancellation preserves the original recovery semantics; UI discard is separate. */
    fun cancel()=cancel(preserveOriginal=false)
    private fun cancel(preserveOriginal: Boolean) {
        if(savedFileBusy || savedFileClosing) { closeSavedFile(detach=true);return }
        if(discarding) { discardRestartAllowed=false;discardPresentationDetached=true;return }
        if(!active || cancelling) return
        cancelling=true
        if(provider==Provider.SYSTEM) { generation=UUID.randomUUID().toString();work?.cancel();monitor?.cancel();stopSystem();clear();return }
        val recorder=capture;capture=null;val record=pending
        generation=UUID.randomUUID().toString();monitor?.cancel();work?.cancel();waiting?.cancel();waiting=null;permissionRequest=null;releaseFocus();phase=Phase.FINISHING
        work=tasks.launch {
            try { runCatching { recorder?.stop() };if(!preserveOriginal && record!=null && record.manifest==null) store.remove(record,unsealedOnly=true) }
            catch(_: OutOfMemoryError) { cancelling=false;error="手机内存不足，原录音已保留，请稍后重试。";phase=Phase.FAILED;return@launch }
            catch(e: Exception) { cancelling=false;error=e.message;phase=Phase.FAILED;return@launch }
            clear()
        }
    }
    fun rerecord()=rerecordWith(provider)
    /** End waiting only detaches this presentation. It never marks a submitted original discarded. */
    fun endWaiting() { if(canEndWaiting)cancel(preserveOriginal=true) }
    /** Explicit new microphone capture, not saved-file ASR. Leave the old sealed original offerable. */
    fun recordAgainWithSystem() {
        if(!canRerecordWithSystem)return
        val original=draft ?: return;val guard=localAllowed ?: return;val change=localApply ?: return
        generation=UUID.randomUUID().toString();work?.cancel();monitor?.cancel()
        clear()
        start(original,guard,change,restore=false,provider=Provider.SYSTEM)
    }
    fun rerecordWith(nextProvider: Provider) {
        if(phase!=Phase.FAILED) return
        val guard=allowed ?: return
        if(!guard()) { error="原草稿或目标已变化，请先取消，再在当前草稿开始录音。";errorCopyKey=error;return }
        discardThen(nextProvider)
    }
    fun discard() { if(active && !discarding)discardThen(null) }
    private fun discardThen(nextProvider: Provider?) {
        if(cancelling || discarding || savedFileBusy || savedFileClosing)return
        if(provider==Provider.SYSTEM) {
            val original=draft;val guard=allowed;val change=apply;val localGuard=localAllowed;val localChange=localApply;val destination=localDestination
            try {
                cancel()
                if(nextProvider!=null && original!=null && guard?.invoke()==true && change!=null)start(original,guard,change,restore=false,provider=nextProvider,localAllowed=localGuard ?: guard,localApply=localChange ?: change,localDestination=destination)
            }catch(_: Exception) { cancelling=false;discardFailed=true;phase=Phase.FAILED;error="无法丢弃录音，请重试。";errorCopyKey=error }
            return
        }
        val original=draft ?: return;val guard=allowed ?: return;val change=apply ?: return;val localGuard=localAllowed ?: guard;val localChange=localApply ?: change;val destination=localDestination
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
                if(restart)start(original,guard,change,restore=false,provider=nextProvider!!,localAllowed=localGuard,localApply=localChange,localDestination=destination)
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
    private fun clear() { savedFileDetachRequested=false;generation=UUID.randomUUID().toString();stopSystem();transcript="";capturedAudioAvailable=false;waiting?.cancel();cancelling=false;discarding=false;discardFailed=false;discardRestartAllowed=false;discardPresentationDetached=false;registration=null;releaseFocus();draft=null;pending=null;capture=null;permissionRequest=null;waiting=null;phase=Phase.IDLE;error=null;errorCopyKey=null;levels=List(9) { 0f } }
}
