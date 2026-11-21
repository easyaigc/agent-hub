package com.agenthub.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.system.Os
import android.util.Base64
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Bounded public CDN read through shipping HtmlPreviewPage. No client/interceptor or JS bridge replacement. */
class HtmlPreviewNetworkTest {
    @get:Rule val ui=createAndroidComposeRule<NativeSavedVoicePanelActivity>()
    private val evidence=HtmlPreviewEvidence("network")
    private val results=JSONArray()
    private val url="https://cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.min.css"
    private val expectedSha="97ce4e98f3a3be297f48ebd5b771e74928f31754d43324fd795d1cd81cc41b35"
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private var opened: HtmlPreviewPage?=null
    private var web: WebView?=null

    private fun close(): Unit {
        instrumentation.runOnMainSync {
            val view=web;val page=opened;opened=null;web=null
            try { view?.webChromeClient=null }
            finally {
                try { view?.let { (it.parent as? ViewGroup)?.removeView(it) } }
                finally { page?.dispose() }
            }
        }
    }
    @After fun clean(): Unit { close() }
    private fun probe(phase: String,online: Boolean): JSONObject {
        close()
        // App-process diagnostic only after default-offline has passed. Never grants WebView network access.
        val nativeProbe=if(online && phase=="allowed-online") NativeHtmlNetworkProbe.capture(ui.activity.applicationContext) else null
        nativeProbe?.let { evidence.json("native-network-probe.json",it) }
        val done=CountDownLatch(1);val result=AtomicReference<JSONObject?>()
        val diagnostics=CopyOnWriteArrayList<String>()
        val prefix="P1_PUBLIC_CSS_${UUID.randomUUID()}:"
        // Optional diagnostic lead-in lets an external Network-only observer attach.
        // Original/default acceptance remains100ms; no observer handshake or production policy change.
        // The previous observer acknowledged Network.enable after6.34s overall. Four seconds
        // after this later online page is created leaves an observable request start while keeping
        // the original12s fetch budget and18s console window intact. Diagnostic timing is separate.
        val observation=InstrumentationRegistry.getArguments().getString("htmlNetworkObserverLeadIn")
        require(observation in listOf(null,"0","1"))
        if(observation=="1")require(instrumentation.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&
            android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val fetchStartDelayMs=if(online&&observation=="1")4000 else 100
        val began=SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            val page=HtmlPreviewPage { true };opened=page
            val view=page.create(ui.activity,"""
                <p>Synthetic public CSS connectivity check</p>
                <script>
                const policyViolations=[];
                document.addEventListener('securitypolicyviolation',function(event){
                  if(policyViolations.length>=3)return;
                  const blocked=String(event.blockedURI);
                  policyViolations.push({effectiveDirective:String(event.effectiveDirective).slice(0,80),
                    violatedDirective:String(event.violatedDirective).slice(0,80),disposition:String(event.disposition).slice(0,24),
                    blockedURI:blocked===${JSONObject.quote(url)}?blocked:(blocked==='inline'||blocked==='eval'?blocked:'[other]')});
                });
                setTimeout(async function(){
                  const controller=new AbortController();let timedOut=false;
                  const timer=setTimeout(function(){timedOut=true;controller.abort()},12000);
                  const fetchBegan=performance.now();
                  function report(value){
                    value.fetchElapsedMs=Math.round(performance.now()-fetchBegan);
                    value.securityPolicyViolations=policyViolations.slice(0,3);
                    value.resourceTiming=performance.getEntriesByName(${JSONObject.quote(url)}).slice(0,2).map(function(entry){
                      return {initiatorType:String(entry.initiatorType).slice(0,24),startTime:entry.startTime,duration:entry.duration,
                        fetchStart:entry.fetchStart,domainLookupStart:entry.domainLookupStart,domainLookupEnd:entry.domainLookupEnd,
                        connectStart:entry.connectStart,secureConnectionStart:entry.secureConnectionStart,connectEnd:entry.connectEnd,
                        responseStart:entry.responseStart,responseEnd:entry.responseEnd,transferSize:entry.transferSize,
                        encodedBodySize:entry.encodedBodySize,decodedBodySize:entry.decodedBodySize};
                    });
                    console.log(${JSONObject.quote(prefix)}+JSON.stringify(value));
                  }
                  try {
                    const response=await fetch(${JSONObject.quote(url)},{credentials:'omit',cache:'no-store',redirect:'error',signal:controller.signal});
                    const reader=response.body.getReader();const chunks=[];let length=0;
                    while(true){const part=await reader.read();if(part.done)break;length+=part.value.length;
                      if(length>4096){controller.abort();throw new Error('public-fixture-too-large')}chunks.push(part.value)}
                    const bytes=new Uint8Array(length);let offset=0;
                    for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length}
                    let binary='';for(const b of bytes)binary+=String.fromCharCode(b);
                    report({kind:'response',status:response.status,url:response.url,length:bytes.length,base64:btoa(binary)});
                  } catch(error) { report({kind:'rejected',timeout:timedOut,error:String(error.name)+': '+String(error.message).slice(0,240)}) }
                  finally {clearTimeout(timer)}
                },${fetchStartDelayMs});
                </script>
            """.trimIndent(),online)
            web=view
            // Only reads this synthetic page's console. Never replaces the shipping WebViewClient.
            view.webChromeClient=object: WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    val line=message.message()
                    if(line.startsWith(prefix)&&line.length<=8192) {
                        try { result.compareAndSet(null,JSONObject(line.removePrefix(prefix))) }
                        catch(_: Exception) { result.compareAndSet(null,JSONObject().put("kind","invalid-console-result")) }
                        done.countDown()
                    } else if(!line.startsWith(prefix)&&diagnostics.size<12) {
                        diagnostics.add(message.messageLevel().name+": "+line.take(512))
                    }
                    return true
                }
            }
            ui.activity.addContentView(view,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val received=done.await(18,TimeUnit.SECONDS)
        val value=result.get() ?: JSONObject().put("kind","no-terminal-console-result")
        val record=JSONObject().put("phase",phase).put("online",online).put("elapsedMs",SystemClock.elapsedRealtime()-began)
            .put("kind",value.optString("kind")).put("received",received).put("consoleDiagnostics",JSONArray(diagnostics.toList()))
            .put("syntheticFetchStartDelayMs",fetchStartDelayMs).put("observerLeadInEnabled",observation=="1")
        // Keep public body/Base64 in memory only; retain length/hash and bounded diagnostics.
        if(value.has("base64")) {
            val bytes=Base64.decode(value.getString("base64"),Base64.DEFAULT)
            record.put("bytes",bytes.size).put("sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
        if(nativeProbe!=null)record.put("nativeNetworkProbe",nativeProbe)
        // Missing/zero cross-origin timing and absent CSP events are observations, not policy verdicts.
        for(key in listOf("status","url","length","error","timeout","fetchElapsedMs","securityPolicyViolations","resourceTiming"))if(value.has(key))record.put(key,value.get(key))
        results.put(record)
        evidence.json("network-results.json",JSONObject().put("fixedUrl",url).put("expectedBytes",1861).put("expectedSha256",expectedSha).put("phases",results))
        assertTrue("$phase: no terminal result within 18s; WebView/renderer environment failed; ${evidence.directory}",received)
        assertFalse("$phase: fetch timed out; connectivity/environment failure, not policy proof: $record",value.optBoolean("timeout"))
        if(online) {
            assertEquals("Online fetch failed; cannot claim CDN connectivity: $record","response",value.optString("kind"))
            assertEquals(200,value.getInt("status"));assertEquals(url,value.getString("url"))
            assertEquals(1861,value.getInt("length"));assertEquals(1861,record.getInt("bytes"));assertEquals(expectedSha,record.getString("sha256"))
        } else {
            assertEquals("Offline page unexpectedly read network content: $record","rejected",value.optString("kind"))
        }
        return record
    }
    @Test(timeout=90_000) fun publicCssIsFetchedOnlyOnlineAndRejectedAfterRevokeAndReopen(): Unit {
        NativeHtmlNetworkProbe.captureProxy(ui.activity.applicationContext,
            InstrumentationRegistry.getArguments().getString("htmlSystemProxyExpectation"),"none",evidence)
        try {
            probe("default-offline",false)
            probe("allowed-online",true)
            // Same retirement path used by the shipping 'Go offline' UI; existing UI test covers confirmation.
            instrumentation.runOnMainSync { opened!!.revoke() }
            probe("back-offline",false)
            close()
            probe("closed-and-reopened",false)
        } finally { close() }
    }
}

/** Small app-private evidence only. Never scans/deletes shared acceptance data. */
internal class HtmlPreviewEvidence(label: String) {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    val directory=File(instrumentation.targetContext.filesDir,"acceptance/P1-html-$label-${UUID.randomUUID()}").apply {
        check(mkdirs());Os.chmod(path,448) // 0700
    }
    fun json(name: String,value: JSONObject) {
        val file=File(directory,name);file.writeText(value.toString(2));Os.chmod(file.path,384) // 0600
    }
    private var screenshots=0
    private data class WindowStamp(val root: android.view.View,val token: android.os.IBinder,
        val owner: String,val x: Int,val y: Int,val width: Int,val height: Int) {
        fun json()=JSONObject().put("package",owner).put("rootClass",root.javaClass.name)
            .put("rootIdentity",System.identityHashCode(root)).put("contextClass",root.context.javaClass.name)
            .put("tokenClass",token.javaClass.name).put("tokenIdentity",System.identityHashCode(token)).put("token",token.toString())
            .put("screenX",x).put("screenY",y).put("width",width).put("height",height)
            .put("attached",root.isAttachedToWindow).put("shown",root.isShown).put("focused",root.hasWindowFocus())
            .put("windowVisibility",root.windowVisibility)
            .put("windowType",(root.layoutParams as? android.view.WindowManager.LayoutParams)?.type)
    }
    // Called on main only. WindowInspector lists this process's windows, never other apps.
    private fun focusedWindow(excludedRoot: android.view.View?): WindowStamp {
        val owner=instrumentation.targetContext.packageName
        val roots=android.view.inspector.WindowInspector.getGlobalWindowViews().filter {
            it.isAttachedToWindow&&it.isShown&&it.hasWindowFocus()&&it.windowVisibility==android.view.View.VISIBLE&&
                it.context.packageName==owner&&it.rootView===it
        }
        check(roots.size==1) { "Expected one focused attached shown target-process root, found ${roots.size}" }
        val root=roots.single()
        check(root!==excludedRoot) { "Expected foreground Dialog; refusing backing Activity window" }
        check(root.width>0&&root.height>0) { "Focused window has no copyable geometry" }
        val token=checkNotNull(root.windowToken) { "Focused window has no token" }
        val location=IntArray(2);root.getLocationOnScreen(location)
        return WindowStamp(root,token,owner,location[0],location[1],root.width,root.height)
    }
    /** Returns false with an unavailable receipt; never changes a functional test's original verdict. */
    fun screenshot(name: String,excludedRoot: android.view.View?=null,phaseEvidence: JSONObject?=null,expectedRoot: android.view.View?=null): Boolean {
        val receipt=JSONObject().put("status","unavailable").put("syntheticContent",true)
            .put("method","PixelCopy.Request.Builder.ofWindow(View)").put("sdk",android.os.Build.VERSION.SDK_INT)
            .put("startedElapsedMs",SystemClock.elapsedRealtime())
        val done=CountDownLatch(1);val lock=Any()
        val frame=AtomicReference<NativeHtmlFrameBarrier?>()
        if(phaseEvidence!=null)receipt.put("languageSemanticsBefore",phaseEvidence).put("languageVisualVerified",false)
        val captured=AtomicReference<Bitmap?>();val failure=AtomicReference<String?>()
        var closed=false;var pngStarted=false;var saved=false
        val file=File(directory,"$name.png")
        try {
            check(screenshots<3) { "Bounded P1 screenshot attempt budget exhausted" };screenshots++
            check(android.os.Build.VERSION.SDK_INT>=34) { "Window PixelCopy Request API unavailable below API34" }
            instrumentation.runOnMainSync {
                try {
                    val before=focusedWindow(excludedRoot);receipt.put("before",before.json())
                    if(expectedRoot!=null) {
                        receipt.put("semanticRootIdentity",System.identityHashCode(expectedRoot))
                        check(expectedRoot.context.packageName==instrumentation.targetContext.packageName&&expectedRoot.context.applicationInfo.uid==android.os.Process.myUid())
                        check(before.root===expectedRoot&&before.token===expectedRoot.windowToken) { "Focused copy window is not the actual semantics owner window" }
                        receipt.put("sameSemanticOwnerWindow",true)
                    }
                    fun copyCommittedWindow() {
                        if(closed)return
                        val current=focusedWindow(excludedRoot)
                        check(current.root===before.root&&current.token===before.token&&current==before) { "Window changed before submitted-frame copy" }
                        val builder=android.view.PixelCopy.Request.Builder.ofWindow(before.root)
                        if(phaseEvidence!=null) {
                            val location=IntArray(2);before.root.getLocationInWindow(location)
                            val crop=android.graphics.Rect(location[0],location[1],location[0]+before.width,location[1]+before.height)
                            builder.setSourceRect(crop);receipt.put("sourceContentRect",crop.toShortString())
                        }
                        val request=builder.build()
                        android.view.PixelCopy.request(request,instrumentation.targetContext.mainExecutor,callback@{ result ->
                            val bitmap=if(result.status==android.view.PixelCopy.SUCCESS)result.bitmap else null
                            synchronized(lock) {
                                if(closed) { bitmap?.recycle();return@callback }
                                try {
                                    receipt.put("pixelCopyStatus",result.status)
                                    check(result.status==android.view.PixelCopy.SUCCESS) { "Window PixelCopy failed with status ${result.status}" }
                                    val after=focusedWindow(excludedRoot)
                                    check(after.root===before.root&&after.token===before.token&&after==before) {
                                        "Focused root/token/geometry changed during window copy; discard pixels"
                                    }
                                    receipt.put("after",after.json()).put("sameRootTokenGeometryAndFocus",true)
                                    captured.set(checkNotNull(bitmap))
                                } catch(error: Exception) {
                                    bitmap?.recycle();failure.set(error.javaClass.simpleName+": "+error.message?.take(200))
                                } finally { done.countDown() }
                            }
                        })
                    }
                    if(phaseEvidence==null)copyCommittedWindow()
                    else {
                        val gate=NativeHtmlFrameBarrier(before.root);frame.set(gate)
                        receipt.put("frameSubmission",gate.evidence)
                        gate.start(ready={
                            synchronized(lock) { if(!closed)try { copyCommittedWindow() }
                            catch(error: Exception) { failure.set(error.javaClass.simpleName+": "+error.message?.take(200));done.countDown() } }
                        },unavailable={ reason -> synchronized(lock) { if(!closed) { failure.set(reason);done.countDown() } } })
                    }
                } catch(error: Exception) {
                    failure.set(error.javaClass.simpleName+": "+error.message?.take(200));done.countDown()
                }
            }
            check(done.await(3,TimeUnit.SECONDS)) { "Own-window PixelCopy callback did not complete within 3s" }
            check(failure.get()==null) { failure.get().orEmpty() }
            val bitmap=checkNotNull(captured.get()) { "No verified own-window pixels" }
            check(!file.exists()) { "Evidence filename already exists; refusing overwrite" }
            pngStarted=true
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            Os.chmod(file.path,384)
            receipt.put("status","captured").put("width",bitmap.width).put("height",bitmap.height).put("bytes",file.length())
            if(phaseEvidence!=null) {
                val colors=HashSet<Int>();var white=0
                for(row in 0 until 32)for(col in 0 until 32) {
                    val color=bitmap.getPixel((col*bitmap.width/32).coerceAtMost(bitmap.width-1),(row*bitmap.height/32).coerceAtMost(bitmap.height-1))
                    colors.add(color);if((color and 0x00ffffff)==0x00ffffff)white++
                }
                receipt.put("pixelSamples",JSONObject().put("count",1024).put("distinctColors",colors.size).put("exactWhite",white))
                    .put("contentEvidence",if(colors.size<=1)"uniform-frame-suspected-visual-unverified" else "requires-independent-language-image-review")
            }
            saved=true
        } catch(error: Exception) {
            synchronized(lock) {
                closed=true
                receipt.put("status","unavailable").put("reason",error.javaClass.simpleName+": "+error.message?.take(240))
            }
            if(pngStarted&&file.exists())receipt.put("partialPngRemoved",file.delete())
        } finally {
            synchronized(lock) { closed=true;captured.getAndSet(null)?.recycle() }
            instrumentation.runOnMainSync { frame.getAndSet(null)?.cancel() }
            receipt.put("finishedElapsedMs",SystemClock.elapsedRealtime())
            try { json("$name-window.json",receipt) }
            catch(_: Exception) {
                saved=false
                if(pngStarted&&file.exists())file.delete()
                android.util.Log.e("P1HtmlEvidence","Own-window receipt unavailable; screenshot is UNKNOWN")
            }
        }
        return saved
    }
}
