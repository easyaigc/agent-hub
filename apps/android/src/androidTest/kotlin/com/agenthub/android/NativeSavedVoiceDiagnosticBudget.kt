package com.agenthub.android

import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter

/** A wall-clock observation budget, not a claim that a blocking framework call can be interrupted. */
internal class NativeSavedVoiceReadBudget(val limitMs: Long=2000,private val clock: ()->Long=System::nanoTime) {
    private val start=clock();private val deadline=start+limitMs*1_000_000
    var exceeded=false;private set
    var lateReadsDiscarded=0;private set
    var truncated=false;private set
    private var chars=8192
    private var lines=128
    data class Read<T>(val value: T)
    fun active(): Boolean {if(clock()>=deadline)exceeded=true;return !exceeded}
    fun <T> read(block: ()->T): Read<T>? {
        if(!active())return null
        val value=block()
        if(!active()){lateReadsDiscarded++;return null}
        return Read(value)
    }
    fun clip(value: CharSequence?,maximum: Int=1024): String? {
        if(value==null||!active())return null
        val length=value.length;val count=minOf(length,maximum,chars);if(count<length)truncated=true
        val out=StringBuilder(count)
        for(i in 0 until count){if(!active())return null;out.append(value[i])}
        chars-=count;return if(active())out.toString()else null
    }
    fun takeLines(total: Int): Int {val n=minOf(total,64,lines);lines-=n;if(n<total)truncated=true;return n}
    fun markIncomplete(){truncated=true}
    fun timing()=JSONObject().put("limitMs",limitMs).put("elapsedMs",(clock()-start)/1_000_000)
        .put("deadlineExceeded",!active()).put("lateReadsDiscarded",lateReadsDiscarded).put("generationTruncated",truncated)
        .put("blockingFrameworkCallInterruptible",false)
}

internal class NativeSavedVoiceBoundedBytes(private val maximum: Int): ByteArrayOutputStream(maximum) {
    override fun write(value: Int) {if(count>=maximum)throw IOException("Diagnostic byte limit");super.write(value)}
    override fun write(value: ByteArray,offset: Int,length: Int) {if(length>maximum-count)throw IOException("Diagnostic byte limit");super.write(value,offset,length)}
}

/** Streams bounded JSON; never first creates an unbounded JSONObject.toString(). */
internal object NativeSavedVoiceDiagnosticJson {
    fun encode(value: JSONObject,budget: NativeSavedVoiceReadBudget?,maximum: Int=96*1024): ByteArray {
        val bytes=NativeSavedVoiceBoundedBytes(maximum);var tokens=0
        JsonWriter(OutputStreamWriter(bytes,Charsets.UTF_8)).use {writer->
            fun emit(v: Any?,depth: Int) {
                require(depth<=48&&++tokens<=8192)
                check(budget?.active()!=false) {"Diagnostic deadline exceeded before serialization"}
                when(v) {
                    null,JSONObject.NULL->writer.nullValue()
                    is JSONObject->{
                        writer.beginObject();val keys=v.keys();var count=0
                        while(keys.hasNext()) {
                            require(++count<=128);val k=keys.next();require(k.length<=160)
                            if(depth==0&&(k=="complete"||k=="timing"&&budget!=null))continue
                            writer.name(k);emit(v.get(k),depth+1)
                        }
                        if(depth==0) {
                            writer.name("complete").value(v.optBoolean("complete",true)&&budget?.truncated!=true&&budget?.active()!=false)
                            if(budget!=null){writer.name("timing");emit(budget.timing(),depth+1)}
                        }
                        writer.endObject()
                    }
                    is JSONArray->{require(v.length()<=512);writer.beginArray();for(i in 0 until v.length())emit(v.get(i),depth+1);writer.endArray()}
                    is Boolean->writer.value(v)
                    is Number->writer.value(v)
                    is CharSequence->{
                        val text=if(budget!=null)budget.clip(v,2048)else {
                            val n=minOf(v.length,1024);buildString(n){for(i in 0 until n)append(v[i])}
                        }
                        writer.value(text)
                    }
                    else->error("Unsupported diagnostic JSON value")
                }
            }
            emit(value,0)
        }
        check(budget?.active()!=false) {"Diagnostic deadline exceeded after serialization"}
        return bytes.toByteArray()
    }
    fun immutable(file: File,bytes: ByteArray,budget: NativeSavedVoiceReadBudget?=null): Boolean {
        require(bytes.size<=96*1024&&!file.exists())
        val pending=File(file.parentFile,file.name+".pending")
        require(!pending.exists()&&pending.createNewFile())
        try {
            pending.outputStream().use {out->android.system.Os.fchmod(out.fd,0x180);out.write(bytes);out.fd.sync()}
            if(budget?.active()==false)return false
            java.nio.file.Files.move(pending.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            if(budget?.active()==false){check(file.delete());return false}
            return true
        }finally{if(pending.exists())check(pending.delete())}
    }
}
