package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

/** Opt-in metadata for the independent debug acceptance app, never product content. */
internal object NativeReadAcceptanceTiming {
    const val ENABLED="AGENT_HUB_READ_ACCEPTANCE_ENABLED"
    const val HOST="AGENT_HUB_READ_ACCEPTANCE_HOST"
    const val RUN="AGENT_HUB_READ_ACCEPTANCE_RUN"
    enum class Point(val wire: String) {
        SESSION_START("session-start"),CONNECTION_SUCCESS("connection-success"),
        HISTORY_RESPONSE("history-response"),HISTORY_APPLYING("history-applying"),CACHE_APPLYING("cache-applying")
    }
    private val uuid=Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
    private fun canonical(value: String?)=value?.takeIf { uuid.matches(it) }?.lowercase(Locale.ROOT)
    private val writer by lazy { Executors.newSingleThreadExecutor { task -> Thread(task,"native-read-acceptance").apply { isDaemon=true } } }
    @Volatile private var current: Recorder?=null

    @Synchronized fun configure(context: Context,intent: Intent?) {
        try {
            if(!BuildConfig.DEBUG || BuildConfig.APPLICATION_ID!="com.easyai.agenthub.android.acceptance" ||
                context.packageName!=BuildConfig.APPLICATION_ID || intent?.getBooleanExtra(ENABLED,false)!=true) { current=null;return }
            val host=canonical(intent.getStringExtra(HOST));val run=canonical(intent.getStringExtra(RUN))
            if(host==null || run==null) { current=null;return }
            val file=File(context.cacheDir,"native-read-$run.jsonl")
            if(current?.matches(host,run,file)==true)return
            // An earlier run's sole evidence must never be overwritten or appended by a new recorder.
            if(file.exists()) { current=null;return }
            val recorder=Recorder(host,run,file);current=recorder
            recorder.record(Point.SESSION_START,host)
        } catch(_: Exception) { current=null }
    }
    fun capture(): Recorder?=current

    class Recorder internal constructor(private val host: String,private val run: String,private val file: File) {
        private var reserved=0
        private var created=false
        @Volatile private var failed=false
        internal fun matches(host: String,run: String,file: File)=this.host==host && this.run==run && this.file==file
        @Synchronized fun record(point: Point,hostId: String,threadId: String?=null,startedAtNanos: Long?=null) {
            if(failed || canonical(hostId)!=host || reserved>=256)return
            val now=SystemClock.elapsedRealtimeNanos()
            val row=JSONObject().put("event",point.wire).put("uptime",now/1_000_000_000.0).put("utc",System.currentTimeMillis()/1000.0)
            canonical(threadId)?.let { row.put("threadId",it) }
            if(startedAtNanos!=null && startedAtNanos in 0..now)row.put("startedAt",startedAtNanos/1_000_000_000.0).put("elapsedMS",(now-startedAtNanos)/1_000_000.0)
            val bytes=(row.toString()+"\n").toByteArray(Charsets.UTF_8);reserved++
            try { writer.execute {
                if(failed)return@execute
                try {
                    if(!created) { check(file.createNewFile());created=true }
                    FileOutputStream(file,true).use { it.write(bytes) }
                } catch(_: Exception) { failed=true /* Instrumentation cannot change a product result. */ }
            } } catch(_: Exception) { failed=true }
        }
    }
}
