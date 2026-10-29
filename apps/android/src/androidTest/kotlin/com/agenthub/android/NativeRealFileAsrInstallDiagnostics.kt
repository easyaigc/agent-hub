package com.agenthub.android

import android.net.TrafficStats
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Observes the unchanged real installer. Never opens a URL, ZIP content, model file or audio.
 * FD positions are point observations; UID traffic is separate, never an exact ZIP-byte claim. */
internal class NativeRealFileAsrInstallDiagnostics(private val root: File,private val spec: JSONObject,folder: File) {
    private val startedNs=SystemClock.elapsedRealtimeNanos()
    private val stopped=AtomicBoolean()
    private val worker=AtomicReference<Thread?>()
    private val checkpoints=AtomicLong()
    private val observerNs=AtomicLong()
    private val samplerFailure=AtomicReference<Throwable?>()
    private val truncated=AtomicBoolean()
    private val journal=File(folder,"installer-observations.jsonl")
    private val output: FileOutputStream
    private var events=0;private var written=0L;private var available=0;private var unknown=0;private var lastNs=0L
    private var lastSite=""
    private var zipWritesObserved=0
    private val phases=linkedSetOf<String>()
    private val filePaths=(0 until spec.getJSONArray("files").length()).map {spec.getJSONArray("files").getJSONObject(it).getString("path")}.toSet()
    private val sampler=Thread({
        try {while(!stopped.get()){Thread.sleep(1000);if(!stopped.get())sample("periodic")}}
        catch(t: InterruptedException){if(!stopped.get())samplerFailure.set(t)}
        catch(t: Throwable){samplerFailure.set(t)}
    },"P7-owned-installer-observer").apply {isDaemon=true}
    init {
        require(spec.getString("url")=="https://alphacephei.com/vosk/models/${spec.getString("id")}.zip")
        require(!journal.exists()&&journal.createNewFile())
        output=journal.outputStream();Os.fchmod(output.fd,0x180)
        sample("before-install");sampler.start()
    }
    fun checkpoint() {
        val start=SystemClock.elapsedRealtimeNanos()
        try {
            worker.set(Thread.currentThread());checkpoints.incrementAndGet()
            val site=sourceFrames(Thread.currentThread()).toString()
            synchronized(this) {
                if(site!=lastSite||start-lastNs>=1_000_000_000L) {lastSite=site;sample("checkpoint")}
            }
        }catch(t: Throwable){samplerFailure.compareAndSet(null,t)}
        finally{observerNs.addAndGet(SystemClock.elapsedRealtimeNanos()-start)}
    }
    private fun sourceFrames(thread: Thread?)=JSONArray(thread?.stackTrace.orEmpty().filter {
        it.className.startsWith("com.agenthub.android.VoskSavedVoiceSpeech")
    }.take(16).map {JSONObject().put("class",it.className).put("method",it.methodName).put("file",it.fileName).put("line",it.lineNumber)})
    private fun ownedDescriptors(): JSONArray {
        val descriptors=File("/proc/self/fd").listFiles()?:error("Self FD directory unavailable")
        require(descriptors.size<=4096)
        val result=JSONArray();val prefix=root.canonicalPath+File.separator
        for(fd in descriptors) {
            if(!fd.name.matches(Regex("[0-9]+")))continue
            try {
                val before=Os.readlink(fd.path);if(!before.startsWith(prefix))continue
                val info=File("/proc/self/fdinfo/${fd.name}").readText();require(info.length<=16384)
                if(Os.readlink(fd.path)!=before)continue // A close/reuse race is not an attributed position.
                val fields=info.lineSequence().filter {':' in it}.associate {it.substringBefore(':') to it.substringAfter(':').trim()}
                val position=fields.getValue("pos").toLong();val flags=fields.getValue("flags").toLong(8);require(position>=0)
                val access=when(flags and 3L){0L->"read";1L->"write";2L->"read-write";else->error("Invalid FD access")}
                result.put(JSONObject().put("fd",fd.name.toInt()).put("relativePath",before.removePrefix(prefix).take(200))
                    .put("positionBytes",position).put("access",access).put("flagsOctal",fields.getValue("flags")))
            }catch(t: ErrnoException){if(t.errno!=OsConstants.ENOENT)throw t}
            catch(t: java.io.FileNotFoundException){if(fd.exists())throw t}
        }
        return result
    }
    private fun files(): JSONObject {
        require(!java.nio.file.Files.isSymbolicLink(root.toPath()))
        val stages=root.listFiles()?.filter {it.name.matches(Regex("stage-[a-f0-9-]{36}"))}.orEmpty();require(stages.size<=1)
        var zipLength=0L;var expandedBytes=0L;var expandedFiles=0
        stages.singleOrNull()?.let {stage->
            require(stage.canonicalFile==File(root.canonicalFile,stage.name)&&!java.nio.file.Files.isSymbolicLink(stage.toPath()))
            val archive=File(stage,"model.zip");if(archive.exists()){require(archive.isFile&&!java.nio.file.Files.isSymbolicLink(archive.toPath()));zipLength=archive.length();require(zipLength in 0..spec.getLong("bytes"))}
            val expanded=File(stage,"expanded")
            if(expanded.exists()) {
                var entries=0
                expanded.walkTopDown().forEach {file->
                    require(++entries<=64&&!java.nio.file.Files.isSymbolicLink(file.toPath()))
                    if(file.isFile){val rel=file.relativeTo(expanded).path;require(rel in filePaths);expandedFiles++;expandedBytes+=file.length()}
                }
                require(expandedBytes in 0..spec.getLong("expandedBytes"))
            }
        }
        return JSONObject().put("stageCount",stages.size).put("zipObservedFileLengthBytes",zipLength)
            .put("expandedObservedFileCount",expandedFiles).put("expandedObservedFileBytes",expandedBytes)
            .put("destinationDirectoryPresent",File(root,spec.getString("id")).isDirectory)
    }
    @Synchronized private fun sample(kind: String) {
        if(events>=512){truncated.set(true);return}
        val began=SystemClock.elapsedRealtimeNanos();val thread=worker.get();val stack=sourceFrames(thread)
        val event=JSONObject().put("version",1).put("event",events).put("kind",kind).put("elapsedMs",(began-startedNs)/1_000_000)
            .put("checkpoints",checkpoints.get()).put("workerThreadId",thread?.id ?: -1).put("workerSourceFrames",stack)
            .put("uidRxBytes",TrafficStats.getUidRxBytes(android.os.Process.myUid())).put("uidTxBytes",TrafficStats.getUidTxBytes(android.os.Process.myUid()))
        try {
            val state=files();val fds=ownedDescriptors();val observed=mutableSetOf<String>()
            for(index in 0 until fds.length()) {
                val fd=fds.getJSONObject(index);val path=fd.getString("relativePath");val access=fd.getString("access")
                when {
                    path.endsWith("/model.zip")&&access=="write"->{observed.add("DOWNLOAD_ARCHIVE_WRITE_SCOPE");zipWritesObserved++}
                    path.contains("/expanded/")&&access=="write"->observed.add("EXTRACT_FILE_WRITE_SCOPE")
                    path.contains("/expanded/")&&access=="read"->observed.add("VERIFY_EXPANDED_FILE_READ_SCOPE")
                    path.endsWith("/model.zip")&&access=="read"->observed.add(if(stack.toString().contains("hashFile"))"HASH_ARCHIVE_READ_SCOPE"else"ARCHIVE_READ_HASH_OR_ZIP_INDEX_UNRESOLVED")
                }
            }
            if(observed.isEmpty())observed.add("NO_MODEL_FD_AT_THIS_OBSERVATION")
            phases.addAll(observed);available++
            event.put("observation","available").put("files",state).put("fds",fds).put("observedScopes",JSONArray(observed.toList()))
        }catch(t: Throwable) {unknown++;event.put("observation","unknown").put("failureClass",t.javaClass.name).put("failureMessage",(t.message?:"").take(300))}
        val ended=SystemClock.elapsedRealtimeNanos();event.put("observationDurationNs",ended-began)
        val bytes=(event.toString()+"\n").toByteArray(Charsets.UTF_8);if(written+bytes.size>2L*1024*1024){truncated.set(true);return}
        output.write(bytes);written+=bytes.size;events++;lastNs=ended
    }
    fun finish(actualWorkerExit: Boolean,installReturned: Boolean): JSONObject {
        stopped.set(true);sampler.interrupt();sampler.join(3000);check(!sampler.isAlive) {"Owned observer thread did not exit"}
        try {
            try {sample("after-worker-api-exit");output.fd.sync()}catch(t: Throwable){samplerFailure.compareAndSet(null,t)}
        }finally{output.close()}
        return JSONObject().put("journal",journal.name).put("journalSha256",NativeRealFileAsrContract.sha(journal)).put("events",events).put("bytes",written)
            .put("actualWorkerApiExit",actualWorkerExit).put("installReturned",installReturned).put("observationsAvailable",available).put("observationsUnknown",unknown)
            .put("archiveWriteScopesObserved",zipWritesObserved).put("scopesObserved",JSONArray(phases.toList())).put("observerCheckpointOverheadNs",observerNs.get())
            .put("traceBudgetTruncated",truncated.get()).put("samplerFailure",samplerFailure.get()?.javaClass?.name ?: JSONObject.NULL).put("ownedObserverExited",!sampler.isAlive)
            .put("totalElapsedMs",(SystemClock.elapsedRealtimeNanos()-startedNs)/1_000_000).put("phaseTimingKind","point observations and brackets; unobserved transitions remain unknown")
            .put("uidCounterScope","whole UID, unsupported=-1, not exact ZIP bytes or HTTP/upload attribution")
    }
}
