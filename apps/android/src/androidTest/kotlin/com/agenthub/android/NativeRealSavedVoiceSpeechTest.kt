package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Parent-lease-only real default Vosk/JNA tests. No injected engine/text, Activity, microphone or Host. */
class NativeRealSavedVoiceSpeechTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(300)
    private class Unavailable(val code: String): IllegalStateException(code)
    private class Fixture(val lease: NativeRealFileAsrLease,val case: String,val folder: File,val report: JSONObject) {
        private val observation=lazy {NativeRealFileAsrObservations(lease.target)}
        val observations get()=observation.value
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val workers=mutableListOf<Deferred<*>>()
        val calls=AtomicInteger()
        val storage=NativeSessionStorage(lease.context,"original","agent-hub-real-asr-"+lease.input.lease)
        val store=VoiceRecordingStore(lease.context,storage)
        val controller=VoiceInputController(lease.context,store){calls.incrementAndGet();error("No Host command path authorized")}
        val engine=controller.savedFileFactory().also {check(it is VoskSavedVoiceSpeech)}
        val models=NativeRealFileAsrFiles.modelRoot(lease.context)
        val spec: JSONObject
        lateinit var original: PendingVoiceRecording
        private lateinit var before: JSONObject
        init {
            val raw=lease.target.assets.open("saved-voice-models-v1.json").use {it.readBytes()}
            require(voiceSHA(raw)==NativeRealFileAsrContract.MODEL_MANIFEST_SHA256)
            val manifest=JSONObject(String(raw,Charsets.UTF_8));require(manifest.getInt("version")==1)
            spec=manifest.getJSONArray("models").objects().single {it.getString("locale")==lease.input.locale}
            require(spec.getString("url")=="https://alphacephei.com/vosk/models/${spec.getString("id")}.zip")
            report.put("engine",JSONObject().put("factory","VoiceInputController.savedFileFactory (unmodified default)").put("class",engine.javaClass.name)
                .put("injectedRecognition",false).put("manifestAssetSha256",voiceSHA(raw)).put("modelId",spec.getString("id"))
                .put("declaredModelArchiveSha256",spec.getString("sha256")).put("archiveBytes",spec.getLong("bytes"))
                .put("declaredDependencies",JSONArray(listOf("com.alphacephei:vosk-android:0.3.75@aar","net.java.dev.jna:jna:5.18.1@aar")))
                .put("packagedArm64Libraries",NativeRealFileAsrFiles.libraries(lease.target)))
        }
        fun <T> worker(block: suspend CoroutineScope.()->T): Deferred<T> = scope.async(block=block).also {workers.add(it)}
        suspend fun checkpoint() {currentCoroutineContext().ensureActive();lease.checkpoint()}
        suspend fun prepareOriginal() {
            val target=TargetScope("codex",null,null,"file-asr-"+lease.input.lease,"acceptance","runtime-"+lease.input.lease)
            val client=MobileClient(target.hostId,"Owned TTS original",target,"https://file-asr.invalid")
            val scope=AISharingScope(target,"codex-dictation");val key=client.cacheKey("file-asr");val phrase=NativeRealFileAsrContract.phrase(lease.input.locale)
            val intent=File(lease.root,"original-intent.json")
            val restored=store.restore(scope,null,key,phrase)
            if(restored==null&&(intent.exists()||File(lease.root,"original.json").exists()||case=="release-cache"))throw Unavailable("ORIGINAL_RECOVERY_UNKNOWN_NO_REPLACEMENT")
            if(case=="release-cache"&&!intent.isFile)throw Unavailable("ORIGINAL_INTENT_REQUIRED_FOR_CACHE_RELEASE")
            original=restored ?: run {
                val pending=store.begin(scope,null,key,"voice-v1:"+lease.input.lease,"file-asr",phrase)
                lease.audio.inputStream().use {input->store.file(pending.id).outputStream().use {out->android.system.Os.chmod(store.file(pending.id).path,0x180);input.copyTo(out);out.fd.sync()}}
                val sealed=store.seal(pending)
                store.mark(sealed,true,true,VoiceReceipt(sealed.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"));store.current(sealed)
            }
            before=originalProof()
            require(before.getString("audioSha256")==lease.input.audioSha256)
            if(intent.exists())require(StrictMigrationJson.parse(NativeRealFileAsrContract.ownedFile(lease.root,intent.name,16384).readBytes()).toString()==before.toString())
            else NativeRealFileAsrContract.immutable(intent,before)
            report.put("originalBefore",before).put("originalKind","new harness-owned local record with synthetic unknown-attempt flags; no desktop submission")
        }
        private fun canonicalJson(value: Any?): String=when(value) {
            null,JSONObject.NULL->"null"
            is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonicalJson(value.get(it))}
            is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonicalJson(value.get(it))}
            is String->JSONObject.quote(value)
            is Boolean,is Int,is Long->value.toString()
            else->error("Unexpected original JSON value")
        }
        private suspend fun originalProof(): JSONObject {
            val current=store.current(original);val bytes=store.bytes(current);val supplied=lease.audio.readBytes()
            return try {
                assertArrayEquals("The entire decrypted original WAV must equal the declared input",supplied,bytes)
                val canonical=canonicalJson(current.json());val row=storage.read("voice-recording:${current.id}")!!
                val exact=JSONObject().put("canonicalOriginalJson",canonical).put("persistedRow",row)
                val baseline=File(lease.root,"original.json")
                if(baseline.exists())require(NativeRealFileAsrContract.ownedFile(lease.root,baseline.name,65536).readText(Charsets.UTF_8)==exact.toString())
                else {require(!File(lease.root,"original-intent.json").exists());NativeRealFileAsrContract.immutable(baseline,exact)}
                JSONObject().put("id",current.id).put("fingerprint",NativeVoiceDiscardRequest(current).fingerprint)
                .put("manifestSha256",voiceSHA(current.manifest!!.json().toString().toByteArray(Charsets.UTF_8)))
                .put("canonicalOriginalSha256",voiceSHA(canonical.toByteArray(Charsets.UTF_8))).put("audioSha256",voiceSHA(bytes)).put("rowSha256",voiceSHA(row.toByteArray(Charsets.UTF_8)))
                .put("exactCanonicalOriginalAndRowVerified",true).put("fullWavByteEqualityVerified",true)
                .put("prepareAttempted",current.prepareAttempted).put("audioAttempted",current.audioAttempted)
            }finally{bytes.fill(0);supplied.fill(0)}
        }
        suspend fun probe(phase: String): SavedVoiceAvailability {
            observations.sample(phase+"-before");val result=engine.probe(lease.input.locale).availability;observations.sample(phase+"-after")
            report.put(phase,result.name)
            if(result==SavedVoiceAvailability.UNSUPPORTED)throw Unavailable("PROVIDER_UNSUPPORTED_OR_NATIVE_LOAD_UNAVAILABLE")
            return result
        }
        suspend fun installed() {when(probe("resourceBefore")){SavedVoiceAvailability.INSTALLED->Unit;SavedVoiceAvailability.MISSING->throw Unavailable("MODEL_MISSING");SavedVoiceAvailability.CORRUPT->throw Unavailable("MODEL_CORRUPT");SavedVoiceAvailability.UNSUPPORTED->throw Unavailable("PROVIDER_UNSUPPORTED_OR_NATIVE_LOAD_UNAVAILABLE")}}
        suspend fun wav()=store.bytes(original).also {require(voiceSHA(it)==lease.input.audioSha256);SavedVoicePcm.validate(it)}
        suspend fun close(): Throwable? {
            var failure: Throwable?=null
            withContext(NonCancellable) {
                workers.forEach {it.cancel()};workers.forEach {it.join()};scope.cancel()
                report.put("actualWorkerApiExit",workers.all {it.isCompleted}).put("specificHostCommandFactoryCallsObserved",calls.get())
                try {
                    if(::original.isInitialized) {val after=originalProof();report.put("originalAfter",after);check(after.toString()==before.toString());check(NativeRealFileAsrContract.sha(lease.audio)==lease.input.audioSha256);report.put("originalPreserved",true)}
                    val refs=NativeRealFileAsrFiles.references(models);report.put("modelReferencesAfterJoin",refs)
                    check(refs.getString("fdObservation")=="available"&&refs.getString("mappingObservation")=="available") {"MODEL_RESOURCE_OBSERVATION_UNAVAILABLE"}
                    check(refs.getJSONArray("fds").length()==0&&refs.getJSONArray("mappings").length()==0)
                    report.put("resourceReferencesVerified",true)
                    check(calls.get()==0)
                }catch(t: Throwable){failure=t;report.put("invariantObservation","failed-or-unknown")}
                val observed=if(observation.isInitialized())observations.finish()else JSONObject().put("permissionUnchanged",true).put("runtimeObservations","not-started")
                report.put("observations",observed)
                if(!observed.getBoolean("permissionUnchanged")||lease.target.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=report.getInt("recordAudioPermissionBefore"))failure=IllegalStateException("RECORD_AUDIO_PERMISSION_CHANGED")
                withContext(Dispatchers.Main){controller.retireForMigration()}
            }
            return failure
        }
    }
    private fun scenario(case: String,requiredLocale: String?=null,body: suspend Fixture.()->Unit): Unit=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val arguments=InstrumentationRegistry.getArguments()
        require(i.targetContext.packageName==NativeRealFileAsrContract.PACKAGE&&i.context.packageName==NativeRealFileAsrContract.PACKAGE+".test") {"Wrong install identity: no data path may be touched"}
        val input=NativeRealFileAsrContract.parse(arguments.keySet().filter {it.startsWith("fileAsr")}.associateWith {arguments.getString(it)})
        val lease=try {NativeRealFileAsrLease(i.targetContext,i.context,input)}catch(t: Throwable){
            val a=NativeRealFileAsrContract.privateDirectory(i.targetContext.filesDir,"acceptance");val g=NativeRealFileAsrContract.privateDirectory(a,"P7-real-file-asr-guards")
            NativeRealFileAsrContract.immutable(File(g,input.lease+"-"+case+"-"+UUID.randomUUID()+".json"),JSONObject().put("version",1).put("lease",input.lease).put("case",case).put("outcome","PREFLIGHT_REJECTED_NO_ENGINE_PASS")
                .put("sdk",android.os.Build.VERSION.SDK_INT).put("abis",JSONArray(android.os.Build.SUPPORTED_ABIS.toList())).put("failureClass",t.javaClass.name).put("process64Bit",android.os.Process.is64Bit()))
            throw t
        }
        require(case in NativeRealFileAsrContract.cases&&lease.permits(case));require(requiredLocale==null||input.locale==requiredLocale)
        val lockPath=File(lease.root,"runtime.lock")
        require(!java.nio.file.Files.isSymbolicLink(lockPath.toPath())&&lockPath.canonicalFile==File(lease.root.canonicalFile,"runtime.lock"))
        if(lockPath.createNewFile())android.system.Os.chmod(lockPath.path,0x180)
        val lockStat=android.system.Os.lstat(lockPath.path)
        require(lockPath.isFile&&lockPath.length()==0L&&lockStat.st_uid==android.os.Process.myUid()&&lockStat.st_nlink==1L&&lockStat.st_mode and 0x3f==0)
        RandomAccessFile(lockPath,"rw").use {lockFile->
            val lock=lockFile.channel.tryLock()?:error("Canonical lease runtime is occupied; do not interrupt or replay")
            lock.use {
                val submission=File(lease.root,"submission.json");val signature=JSONObject().put("version",1).put("lease",input.lease).put("declarationSha256",lease.declarationSha256)
                if(submission.exists())require(StrictMigrationJson.parse(NativeRealFileAsrContract.ownedFile(lease.root,submission.name,16384).readBytes()).toString()==signature.toString())else NativeRealFileAsrContract.immutable(submission,signature)
                val cases=NativeRealFileAsrContract.privateDirectory(lease.root,"cases")
                if(case!="release-cache")cases.listFiles()!!.filter {it.isDirectory}.forEach {previous->
                    require(previous.name in NativeRealFileAsrContract.cases&&!java.nio.file.Files.isSymbolicLink(previous.toPath())&&previous.canonicalFile==File(cases.canonicalFile,previous.name))
                    if(File(previous,"intent.json").exists())require(StrictMigrationJson.parse(NativeRealFileAsrContract.ownedFile(previous,"result.json",128*1024).readBytes()).let {it.optBoolean("actualWorkerApiExit",false)&&it.optBoolean("resourceReferencesVerified",false)}){"An earlier result is unknown; inspect its original receipt instead of replaying"}
                }
                val folder=NativeRealFileAsrContract.privateDirectory(cases,case);require(folder.listFiles()!!.isEmpty()&&!File(folder,"intent.json").exists()){"Immutable case already claimed; inspect original result, never repeat it"}
                val action=UUID.randomUUID().toString();val report=JSONObject().put("version",1).put("lease",input.lease).put("case",case).put("actionId",action).put("locale",input.locale)
                    .put("audioSha256",input.audioSha256).put("sdk",android.os.Build.VERSION.SDK_INT).put("abi","arm64-v8a").put("buildFingerprint",android.os.Build.FINGERPRINT).put("device",android.os.Build.DEVICE).put("product",android.os.Build.PRODUCT).put("processId",android.os.Process.myPid()).put("processUid",android.os.Process.myUid()).put("pageSizeBytes",runCatching {android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)}.getOrDefault(-1L)).put("appSha256",lease.declaration.getString("appSha256"))
                    .put("runnerSha256",lease.declaration.getString("runnerSha256")).put("startedAtMs",System.currentTimeMillis()).put("elapsedRealtimeAtStartMs",android.os.SystemClock.elapsedRealtime()).put("recordAudioPermissionBefore",lease.target.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)).put("recognizerOutputReturned",false).put("outcome","RUNNING")
                NativeRealFileAsrContract.immutable(File(folder,"intent.json"),JSONObject(report.toString()).put("declarationSha256",lease.declarationSha256))
                NativeRealFileAsrContract.atomic(File(folder,"result.json"),report)
                var fixture: Fixture?=null;var failure: Throwable?=null
                try {
                    fixture=Fixture(lease,case,folder,report);fixture.prepareOriginal();require(action!=fixture.original.id);fixture.checkpoint();fixture.body()
                    report.put("outcome","ENGINE_ASSERTIONS_PASSED_RUNTIME_PRIVACY_PENDING").put("engineAssertionsPassed",true)
                }catch(t: Throwable){failure=t;report.put("outcome",when(t){is Unavailable->t.code;is org.junit.ComparisonFailure->"ACOUSTIC_OR_EXACT_VALUE_MISMATCH";is java.net.SocketTimeoutException,is java.net.UnknownHostException,is java.net.ConnectException,is javax.net.ssl.SSLException->"NETWORK_UNAVAILABLE_OR_FAILED";is TimeoutCancellationException->"TIME_WINDOW_EXPIRED_UNKNOWN";else->"FAILED_OR_UNOBSERVABLE"}).put("failureClass",t.javaClass.name).put("engineAssertionsPassed",false)}
                finally {
                    val closeFailure=fixture?.close()
                    if(closeFailure!=null){failure=closeFailure;report.put("outcome","RESOURCE_OR_ORIGINAL_INVARIANT_FAILED").put("invariantFailureClass",closeFailure.javaClass.name).put("engineAssertionsPassed",false)}
                    report.put("finishedAtMs",System.currentTimeMillis()).put("overallAcceptance","pending independent parent microphone/network/full-DEV evidence")
                    NativeRealFileAsrContract.atomic(File(folder,"result.json"),report)
                }
                failure?.let {throw AssertionError("Real file ASR $case did not pass; inspect its bounded private result",it)}
                Unit
            }
        }
    }
    @Test fun probeMissingModelDoesNotDownloadOrRecognize()=scenario("probe-empty") {
        val before=NativeRealFileAsrFiles.inventory(models);require(before.isEmpty())
        assertEquals(SavedVoiceAvailability.MISSING,probe("probeOnly"));assertTrue(NativeRealFileAsrFiles.inventory(models).isEmpty())
        report.put("probeCreatedModelFiles",false).put("recognizerOutputReturned",false)
    }
    @Test fun explicitOfficialDownloadInstallsPinnedModel()=scenario("install") {
        assertEquals(SavedVoiceAvailability.MISSING,probe("resourceBefore"))
        observations.sample("explicit-download-before");engine.install(lease.input.locale){checkpoint()};observations.sample("explicit-download-after")
        assertEquals(SavedVoiceAvailability.INSTALLED,probe("resourceAfter"))
        val actual=NativeRealFileAsrFiles.inventory(models);val expected=spec.getJSONArray("files").objects().associate {spec.getString("id")+"/"+it.getString("path") to it.getString("sha256")}
        assertEquals(expected,actual);report.put("installedFiles",actual.size).put("installedTreeFingerprint",voiceSHA(JSONArray(actual.toSortedMap().map {(path,hash)->JSONArray(listOf(path,hash))}).toString().toByteArray(Charsets.UTF_8)))
    }
    @Test fun explicitOfficialInstallRecordsActualPhasesAndByteOffsets()=scenario("install-trace") {
        require(NativeRealFileAsrFiles.inventory(models).isEmpty()) {"Diagnostic must not reuse a preloaded model"}
        assertEquals(SavedVoiceAvailability.MISSING,probe("diagnosticResourceBefore"))
        val diagnostic=NativeRealFileAsrInstallDiagnostics(models,spec,folder)
        val exited=AtomicBoolean();val returned=AtomicBoolean()
        observations.sample("explicit-install-before")
        val job=worker {
            try {
                engine.install(lease.input.locale) {diagnostic.checkpoint();checkpoint()}
                returned.set(true)
            }finally{exited.set(true)}
        }
        var summary: JSONObject?=null
        try {
            job.await()
        }finally {
            withContext(NonCancellable) {
                job.cancel();job.join()
                summary=diagnostic.finish(exited.get()&&job.isCompleted,returned.get())
                report.put("installerDiagnostic",summary).put("installerProductionTimeoutUnchangedMs",180000)
                observations.sample("explicit-install-after-actual-api-exit")
            }
        }
        // Stop observing install before the independent post-install hash/probe begins.
        assertEquals(SavedVoiceAvailability.INSTALLED,probe("diagnosticResourceAfter"))
        val expected=spec.getJSONArray("files").objects().associate {spec.getString("id")+"/"+it.getString("path") to it.getString("sha256")}
        assertEquals(expected,NativeRealFileAsrFiles.inventory(models))
        val final=checkNotNull(summary)
        assertTrue(final.getBoolean("actualWorkerApiExit"));assertTrue(final.getBoolean("installReturned"))
        assertTrue(final.getBoolean("ownedObserverExited"));assertTrue(final.isNull("samplerFailure"));assertFalse(final.getBoolean("traceBudgetTruncated"))
        assertTrue("Unobservable FD/file state is not an installer diagnostic pass",final.getInt("observationsAvailable")>0)
        assertTrue("A real archive write scope must be observed; preloading is not installer acceptance",final.getInt("archiveWriteScopesObserved")>0)
    }
    private suspend fun Fixture.recognize() {
        installed();val bytes=wav()
        try {
            observations.sample("decode-before");val result=engine.transcribe(lease.input.locale,bytes){checkpoint()};observations.sample("decode-after")
            require(result.text.length<=2048);report.put("actualTranscript",result.text).put("actualTranscriptSha256",voiceSHA(result.text.toByteArray(Charsets.UTF_8)))
                .put("declaredTtsPhrase",NativeRealFileAsrContract.phrase(lease.input.locale)).put("recognizerOutputReturned",true)
                .put("actualModelId",result.modelId).put("actualModelArchiveSha256",result.modelSha256)
            assertEquals(spec.getString("id"),result.modelId);assertEquals(spec.getString("sha256"),result.modelSha256)
            val expectedText=NativeRealFileAsrContract.normalize(NativeRealFileAsrContract.phrase(lease.input.locale));val actualText=NativeRealFileAsrContract.normalize(result.text)
            report.put("normalizedPhraseMatches",expectedText==actualText);assertEquals(expectedText,actualText)
        }finally{bytes.fill(0)}
    }
    @Test fun recognizeDeclaredEnglishWithDefaultVosk()=scenario("decode","en-US"){recognize()}
    @Test fun recognizeDeclaredMandarinWithDefaultVosk()=scenario("decode","zh-CN"){recognize()}
    @Test fun cancelPartialExplicitDownloadWaitsForActualExitAndNeverPublishes()=scenario("cancel-download") {
        assertEquals(SavedVoiceAvailability.MISSING,probe("resourceBefore"));val entered=CompletableDeferred<Long>();val release=CompletableDeferred<Unit>()
        val job=worker {
            engine.install(lease.input.locale) {
                checkpoint()
                if(!entered.isCompleted) {
                    val partial=models.listFiles().orEmpty().filter {it.name.matches(Regex("stage-[a-f0-9-]{36}"))}.map {File(it,"model.zip").length()}.firstOrNull {it in 1 until spec.getLong("bytes")}
                    if(partial!=null){entered.complete(partial);withContext(NonCancellable){release.await()};currentCoroutineContext().ensureActive()}
                }
            }
        }
        try {
            withTimeout(90_000){while(!entered.isCompleted&&!job.isCompleted)delay(10);if(job.isCompleted)job.await();entered.await()}
            report.put("partialDownloadedArchiveBytes",entered.await());job.cancel();delay(50);assertFalse(job.isCompleted)
            release.complete(Unit);job.join();assertTrue(job.isCancelled);assertTrue(NativeRealFileAsrFiles.inventory(models).isEmpty())
            assertEquals(SavedVoiceAvailability.MISSING,probe("resourceAfterCancel"));report.put("cancelHeldActualDownloadScope",true)
        }finally{release.complete(Unit);job.cancel();job.join()}
    }
    private suspend fun Fixture.cancelNative(frameClass: String,frameMethod: String) {
        installed();val bytes=wav();val thread=AtomicReference<Thread>();val enteredAt=System.nanoTime()
        val job=worker {engine.transcribe(lease.input.locale,bytes){thread.set(Thread.currentThread());checkpoint()}}
        val nativeMethod=if(frameClass=="org.vosk.Model")"vosk_model_new"else"vosk_recognizer_accept_waveform"
        try {
            var observed: Array<StackTraceElement>?=null
            withTimeout(60_000) {
                while(observed==null&&!job.isCompleted) {
                    val stack=thread.get()?.stackTrace
                    if(stack?.any {it.className==frameClass&&it.methodName==frameMethod}==true&&stack.any {it.className=="org.vosk.LibVosk"&&it.methodName==nativeMethod&&it.isNativeMethod})observed=stack else delay(2)
                }
            }
            if(observed==null) {if(job.isCompleted)job.await();throw Unavailable("NATIVE_CANCELLATION_WINDOW_NOT_OBSERVED")}
            report.put("observedNativeFrames",JSONArray(observed!!.filter {it.className.startsWith("org.vosk.")||it.className.startsWith("com.sun.jna.")}.take(12).map {it.className+"."+it.methodName+(if(it.isNativeMethod)" [native]"else"")}))
            if(job.isCompleted)throw Unavailable("NATIVE_CALL_COMPLETED_BEFORE_CANCEL")
            val requested=System.nanoTime();job.cancel();job.join()
            report.put("nativeWindowObservedAfterMs",(requested-enteredAt)/1_000_000).put("cancelJoinMs",(System.nanoTime()-requested)/1_000_000)
                .put("cancelledDefaultBackend",job.isCancelled).put("nativeCloseProof","production use/finally API exit, joined worker, subsequent real probe and FD/mapping observations; not direct JNI free tracing")
            assertTrue(job.isCancelled);assertEquals(SavedVoiceAvailability.INSTALLED,probe("resourceAfterNativeCancel"))
        }finally{job.cancel();job.join();bytes.fill(0)}
    }
    @Test fun cancelDuringActualModelConstructorJoinsDefaultBackend()=scenario("cancel-model"){cancelNative("org.vosk.Model","<init>")}
    @Test fun cancelDuringActualRecognizerFeedJoinsDefaultBackend()=scenario("cancel-feed"){cancelNative("org.vosk.Recognizer","acceptWaveForm")}
    @Test fun corruptedPinnedModelIsRejectedAndRestoredWithoutChangingOriginal()=scenario("corrupt") {
        installed();val file=File(models,spec.getString("id")+"/am/final.mdl");val expected=spec.getJSONArray("files").objects().single {it.getString("path")=="am/final.mdl"}.getString("sha256")
        assertEquals(expected,NativeRealFileAsrContract.sha(file));var originalByte=-1
        try {
            RandomAccessFile(file,"rw").use {it.seek(0);originalByte=it.read();require(originalByte>=0);it.seek(0);it.write(originalByte xor 1);it.fd.sync()}
            assertEquals(SavedVoiceAvailability.CORRUPT,probe("corruptProbe"));val bytes=wav()
            try {
                val failure=runCatching {engine.transcribe(lease.input.locale,bytes){checkpoint()}}.exceptionOrNull()
                require(failure!=null&&failure !is CancellationException);report.put("corruptDecodeRejected",true).put("corruptFailureClass",failure.javaClass.name)
            }finally{bytes.fill(0)}
        }finally {
            if(originalByte>=0)RandomAccessFile(file,"rw").use {it.seek(0);it.write(originalByte);it.fd.sync()}
        }
        assertEquals(expected,NativeRealFileAsrContract.sha(file));assertEquals(SavedVoiceAvailability.INSTALLED,probe("resourceRestored"))
    }
    @Test fun releaseOnlyOwnedVerifiedModelCachePreservingInputsAndOriginals()=scenario("release-cache") {
        val expected=spec.getJSONArray("files").objects().map {it.getString("path")}.toSet()
        val refs=NativeRealFileAsrFiles.references(models);require(refs.getString("fdObservation")=="available"&&refs.getString("mappingObservation")=="available"&&refs.getJSONArray("fds").length()==0&&refs.getJSONArray("mappings").length()==0)
        val staged=NativeRealFileAsrFiles.inventory(models);probe("resourceBeforeRelease") // Default probe reclaims interrupted public staging under its own mutex.
        val before=NativeRealFileAsrFiles.inventory(models);report.put("stagedFilesReclaimedByProbe",staged.keys.count {it !in before})
        NativeRealFileAsrFiles.release(models,spec.getString("id"),expected)
        assertEquals(SavedVoiceAvailability.MISSING,probe("resourceAfterRelease"));assertTrue(NativeRealFileAsrFiles.inventory(models).isEmpty())
        report.put("releasedCacheFiles",before.size).put("inputAndOriginalRetained",true)
    }
}
