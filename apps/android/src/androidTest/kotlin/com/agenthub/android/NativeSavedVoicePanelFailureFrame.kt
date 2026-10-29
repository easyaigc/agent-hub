package com.agenthub.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Separate failure-only budget. Every failure is diagnostic data, never a replacement assertion.
 * PixelCopy always receives this exact Activity's Window; no display/global screenshot API. */
internal class NativeSavedVoicePanelFailureFrame(private val activity: NativeSavedVoicePanelActivity,private val folder: File) {
    enum class Reason(val fileValue: String) { BUTTON_OVERFLOW("button-overflow"),AX_OWNER_LABEL("ax-owner-label"),AX_DIAGNOSTIC_INCOMPLETE("ax-diagnostic-incomplete") }
    private var attempts=0;private var captured=0;private var failures=0;private var omitted=0
    private var encodedBytes=0L
    private val pendingBitmaps=AtomicInteger()
    private val lateCallbacks=AtomicInteger()
    private val writeFailures=AtomicInteger()
    private val recycleFailure=AtomicReference<String?>()
    private data class OwnedWindow(val window: Window,val width: Int,val height: Int,val metadata: JSONObject)
    fun summary()=JSONObject().put("attempts",attempts).put("captured",captured).put("failed",failures).put("omitted",omitted)
        .put("encodedPngBytes",encodedBytes).put("pendingBitmapRelease",pendingBitmaps.get()).put("lateCallbacksIgnored",lateCallbacks.get())
        .put("diagnosticWriteFailures",writeFailures.get()).put("recycleFailure",recycleFailure.get() ?: JSONObject.NULL)
        .put("maxAttempts",2).put("maxPngBytesPerAttempt",2*1024*1024).put("maxPixels",6*1024*1024)
        .put("maxBitmapBytes",24*1024*1024).put("maxEdgePixels",4096).put("attemptDeadlineMs",5000).put("normalPointBudgetChanged",false)
    fun capture(reason: Reason,tag: String,metadata: ()->JSONObject) {
        if(attempts>=2){omitted++;return}
        val index=++attempts;val start=SystemClock.elapsedRealtime();val deadline=start+5000
        val budget=NativeSavedVoiceReadBudget(5000)
        val stem="failure-frame-%02d-%s".format(index,reason.fileValue)
        val info=JSONObject().put("version",1).put("reason",reason.fileValue).put("tag",tag).put("startedElapsedMs",start)
            .put("method","PixelCopy.exact-NativeSavedVoicePanelActivity-window").put("beforeHardAssertion",true)
            .put("fullSystemComposite",false).put("countsAsPass",false).put("captured",false)
        val state=AtomicInteger(0) // 0=not submitted, 1=submitted, 2=abandoned before submit, 3=callback/request error completed
        val release=AtomicBoolean();val recycled=AtomicBoolean();val complete=CountDownLatch(1)
        val result=AtomicInteger(-1);val copyError=AtomicReference<Throwable?>()
        var image: Bitmap?=null
        fun recycleOnce() {
            val bitmap=image ?: return
            if(recycled.compareAndSet(false,true)) {
                try {bitmap.recycle()}catch(t: Throwable){recycleFailure.compareAndSet(null,t.javaClass.name)}
                finally{pendingBitmaps.decrementAndGet()}
            }
        }
        fun remaining(maximum: Long)=minOf(maximum,(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))
        try {
            require(tag.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            val detail=metadata()
            val boundedDetail=NativeSavedVoiceDiagnosticJson.encode(detail,budget,24*1024)
            check(budget.active()) {"Late failure-frame metadata discarded"}
            info.put("detail",JSONObject(String(boundedDetail,Charsets.UTF_8)))
            val window=AtomicReference<OwnedWindow?>();val prepareError=AtomicReference<Throwable?>();val prepared=CountDownLatch(1)
            val main=Handler(Looper.getMainLooper())
            main.post {
                if(SystemClock.elapsedRealtime()>=deadline){prepared.countDown();return@post}
                try {
                    check(activity.javaClass==NativeSavedVoicePanelActivity::class.java&&activity.packageName==NativeRealFileAsrContract.PACKAGE)
                    check(!activity.isFinishing&&!activity.isDestroyed)
                    val owned=activity.window;val decor=owned.decorView
                    check(decor.hasWindowFocus()&&decor.windowToken!=null)
                    require(decor.width in 1..4096&&decor.height in 1..4096&&decor.width.toLong()*decor.height<=6L*1024*1024)
                    val location=IntArray(2);decor.getLocationOnScreen(location)
                    val data=JSONObject().put("package",activity.packageName).put("activity",activity.javaClass.name)
                        .put("windowIdentity",System.identityHashCode(owned)).put("focused",true).put("width",decor.width).put("height",decor.height)
                        .put("decorOriginOnScreen",org.json.JSONArray(location.toList())).put("actualResourceFontScale",activity.resources.configuration.fontScale)
                        .put("actualOrientation",activity.resources.configuration.orientation).put("density",activity.resources.displayMetrics.density)
                    decor.postOnAnimation {decor.postOnAnimation {
                        if(SystemClock.elapsedRealtime()<deadline&&decor.hasWindowFocus())window.set(OwnedWindow(owned,decor.width,decor.height,data))
                        prepared.countDown()
                    }}
                }catch(t: Throwable){prepareError.set(t);prepared.countDown()}
            }
            check(prepared.await(remaining(1000),TimeUnit.MILLISECONDS)) {"Owned-window frame preparation timed out"}
            prepareError.get()?.let {throw it}
            val owned=checkNotNull(window.get()) {"Owned window unavailable/focus lost; no frame captured"}
            info.put("window",owned.metadata)
            check(SystemClock.elapsedRealtime()<deadline)
            image=Bitmap.createBitmap(owned.width,owned.height,Bitmap.Config.ARGB_8888);pendingBitmaps.incrementAndGet()
            val bitmap=checkNotNull(image)
            info.put("bitmapAllocationBytes",bitmap.allocationByteCount).put("bitmapRowBytes",bitmap.rowBytes)
            require(bitmap.allocationByteCount<=24*1024*1024)
            main.post {
                if(!state.compareAndSet(0,1))return@post
                try {
                    check(SystemClock.elapsedRealtime()<deadline&&owned.window.decorView.hasWindowFocus())
                    PixelCopy.request(owned.window,bitmap,{status->
                        try {result.set(status);state.set(3);complete.countDown();if(release.get())lateCallbacks.incrementAndGet()}
                        finally {if(release.get())recycleOnce()}
                    },main)
                }catch(t: Throwable){copyError.set(t);state.set(3);complete.countDown();if(release.get())recycleOnce()}
            }
            check(complete.await(remaining(2000),TimeUnit.MILLISECONDS)) {"Owned-window PixelCopy callback timed out"}
            copyError.get()?.let {throw it}
            info.put("pixelCopyStatus",result.get());check(result.get()==PixelCopy.SUCCESS)
            check(SystemClock.elapsedRealtime()<deadline)
            val part=File(folder,"$stem.png.part");check(!part.exists()&&part.createNewFile())
            var bytes=0L;val digest=java.security.MessageDigest.getInstance("SHA-256")
            part.outputStream().use {raw->
                android.system.Os.fchmod(raw.fd,0x180)
                val bounded=object: FilterOutputStream(raw) {
                    override fun write(value: Int) {write(byteArrayOf(value.toByte()),0,1)}
                    override fun write(values: ByteArray,offset: Int,length: Int) {
                        if(SystemClock.elapsedRealtime()>=deadline||bytes+length>2L*1024*1024)throw IOException("Failure-frame encoding deadline/byte limit")
                        out.write(values,offset,length);digest.update(values,offset,length);bytes+=length
                    }
                }
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,bounded));raw.fd.sync()
            }
            info.put("pngBytes",bytes);check(SystemClock.elapsedRealtime()<deadline)
            val png=File(folder,"$stem.png");check(!png.exists())
            java.nio.file.Files.move(part.toPath(),png.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            if(SystemClock.elapsedRealtime()>=deadline){check(png.delete());error("Late PNG publication discarded")}
            encodedBytes+=bytes;captured++
            info.put("captured",true).put("png",png.name).put("pngSha256",digest.digest().joinToString(""){"%02x".format(it)})
        }catch(t: Throwable) {
            failures++;info.put("captureFailureClass",t.javaClass.name).put("captureFailureMessage",(t.message?:"").take(700))
            val part=File(folder,"$stem.png.part");if(part.isFile)info.put("partialPng",part.name).put("partialBytes",part.length())
        }finally {
            release.set(true)
            if(state.compareAndSet(0,2)||state.get()==3)recycleOnce()
            // If a request is still in flight, only its callback's finally may safely recycle.
            // A late callback never publishes a PNG or claims a post-cleanup image as pre-assert evidence.
            info.put("elapsedMs",SystemClock.elapsedRealtime()-start).put("bitmapReleasePending",image!=null&&!recycled.get())
                .put("bitmapRecycled",image!=null&&recycled.get()).put("originalAssertionPreserved",true)
            try {
                info.put("complete",info.optBoolean("captured",false)&&budget.active()&&!budget.truncated)
                val file=File(folder,"$stem.json")
                val bytes=if(budget.active())NativeSavedVoiceDiagnosticJson.encode(info,budget,32*1024)else NativeSavedVoiceDiagnosticJson.encode(
                    JSONObject().put("complete",false).put("reason",reason.fileValue).put("tag",tag).put("captureDiagnostic","deadline-exceeded; late metadata discarded")
                        .put("timing",budget.timing()).put("countsAsPass",false),null,32*1024)
                if(!NativeSavedVoiceDiagnosticJson.immutable(file,bytes,if(budget.active())budget else null)) {
                    NativeSavedVoiceDiagnosticJson.immutable(file,NativeSavedVoiceDiagnosticJson.encode(JSONObject().put("complete",false)
                        .put("captureDiagnostic","metadata-write-deadline").put("timing",budget.timing()).put("countsAsPass",false),null))
                }
            }catch(_: Throwable){writeFailures.incrementAndGet()}
        }
    }
}
