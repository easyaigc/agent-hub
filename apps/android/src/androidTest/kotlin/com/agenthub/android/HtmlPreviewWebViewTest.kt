package com.agenthub.android

import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebView engine checks. Synthetic inline input, no Host/session/model operations. */
class HtmlPreviewWebViewTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test(timeout=30_000) fun offlineScriptsButtonsAndScrollWorkWithoutFileOrStorageAccess() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val result=CountDownLatch(1)
        val page=HtmlPreviewPage { true }
        val evidence=HtmlPreviewEvidence("offline-diagnostic")
        val prefix="P1_OFFLINE_${java.util.UUID.randomUUID()}:"
        val stages=java.util.concurrent.CopyOnWriteArrayList<org.json.JSONObject>()
        val console=java.util.concurrent.CopyOnWriteArrayList<org.json.JSONObject>()
        val progress=java.util.concurrent.CopyOnWriteArrayList<Int>()
        val logged=java.util.concurrent.atomic.AtomicInteger()
        val outer=java.util.concurrent.atomic.AtomicReference<String?>()
        val outerDone=CountDownLatch(1)
        lateinit var web: WebView
        var created: WebView?=null
        val fragment="""
            <button id="go" onclick="document.getElementById('result').textContent='clicked'">Go</button>
            <div id="result"></div><div style="height:4000px">Scroll</div>
            <script>
            (function(){
              function report(stage,detail){console.log(${org.json.JSONObject.quote(prefix)}+JSON.stringify({stage:stage,detail:detail}))}
              function metrics(){var s=document.scrollingElement;return {
                ready:document.readyState,innerWidth:innerWidth,innerHeight:innerHeight,devicePixelRatio:devicePixelRatio,
                scrollY:scrollY,scrollX:scrollX,scrollElement:s?s.tagName:null,
                scrollHeight:s?s.scrollHeight:null,clientHeight:s?s.clientHeight:null,
                bodyHeight:document.body?document.body.getBoundingClientRect().height:null,
                button:document.getElementById('go')!==null,
                result:document.getElementById('result')?document.getElementById('result').textContent:null}}
              addEventListener('error',function(e){report('script-error',String(e.message).slice(0,240))});
              addEventListener('unhandledrejection',function(e){report('promise-error',String(e.reason).slice(0,240))});
              document.addEventListener('securitypolicyviolation',function(e){report('csp-violation',{directive:e.effectiveDirective,blocked:String(e.blockedURI).slice(0,120)})});
              document.addEventListener('DOMContentLoaded',function(){report('dom-ready',metrics())});
              addEventListener('load',function(){report('window-loaded',metrics())});
              report('script-entered',metrics());
              setTimeout(function(){
                try {
                  report('before-original-check',metrics());
                  document.getElementById('go').click(); window.scrollTo(0,300);
                  report('original-check',metrics());
                  // Preserve the original 500ms button+positive scrollY success condition exactly.
                  if(document.getElementById('result').textContent==='clicked' && window.scrollY>0)
                    console.log('html-inline-button-scroll-ok');
                  // Read-only later samples explain async layout; they cannot change the verdict.
                  requestAnimationFrame(function(){requestAnimationFrame(function(){report('after-two-frames',metrics())})});
                  setTimeout(function(){report('settled-read-only',metrics())},250);
                } catch(error) {report('probe-exception',String(error).slice(0,240))}
              },500);
            })();
            </script>
        """.trimIndent()
        try {
            instrumentation.runOnMainSync {
                web=page.create(ui.activity,fragment,false);created=web
                // Read-only console/progress observer; shipping WebViewClient/CSP stay unchanged.
                web.webChromeClient=object: WebChromeClient() {
                    override fun onProgressChanged(view: WebView,value: Int) {
                        if(progress.size<16)progress.add(value)
                    }
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        val line=message.message()
                        if(line.startsWith(prefix)&&line.length<=2048&&stages.size<16) {
                            try { stages.add(org.json.JSONObject(line.removePrefix(prefix))) }
                            catch(_: Exception) { if(console.size<16)console.add(org.json.JSONObject().put("parseError",line.take(512))) }
                        } else if(console.size<16) {
                            console.add(org.json.JSONObject().put("level",message.messageLevel().name)
                                .put("line",message.lineNumber()).put("sourceScheme",message.sourceId().orEmpty().substringBefore(':').take(32))
                                .put("message",line.take(768)))
                        }
                        if(logged.getAndIncrement()<32)android.util.Log.i("P1HtmlDiagnostic",message.messageLevel().name+": "+line.take(768))
                        if(line=="html-inline-button-scroll-ok")result.countDown()
                        return true
                    }
                }
                ui.activity.addContentView(web,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                assertTrue(web.settings.javaScriptEnabled);assertTrue(web.settings.blockNetworkLoads)
                assertFalse(web.settings.allowFileAccess);assertFalse(web.settings.allowContentAccess)
                assertFalse(web.settings.domStorageEnabled)
            }
            val passed=result.await(15,TimeUnit.SECONDS)
            val native=org.json.JSONObject()
            instrumentation.runOnMainSync {
                val location=IntArray(2);web.getLocationOnScreen(location)
                native.put("width",web.width).put("height",web.height).put("measuredWidth",web.measuredWidth).put("measuredHeight",web.measuredHeight)
                    .put("x",location[0]).put("y",location[1]).put("contentHeight",web.contentHeight).put("progress",web.progress)
                    .put("attached",web.isAttachedToWindow).put("shown",web.isShown).put("windowFocus",web.hasWindowFocus())
                    .put("windowVisibility",web.windowVisibility).put("package",ui.activity.packageName)
                    .put("webViewPackage",WebView.getCurrentWebViewPackage()?.packageName).put("webViewVersion",WebView.getCurrentWebViewPackage()?.versionName)
                    .put("rootDocumentServed",page.rootDocumentServed).put("blockNetworkLoads",web.settings.blockNetworkLoads).put("allowFileAccess",web.settings.allowFileAccess)
                    .put("allowContentAccess",web.settings.allowContentAccess).put("domStorage",web.settings.domStorageEnabled)
                // Fixed read-only script inspects only our synthetic outer document. It cannot read the opaque frame.
                web.evaluateJavascript("""
                    (function(){var f=document.querySelector('iframe');var r=f?f.getBoundingClientRect():null;return JSON.stringify({
                      ready:document.readyState,scheme:location.protocol,viewport:{w:innerWidth,h:innerHeight},
                      rootHeight:document.documentElement.clientHeight,frames:frames.length,
                      iframe:f?{width:r.width,height:r.height,clientHeight:f.clientHeight,sandbox:f.getAttribute('sandbox'),
                        srcdocLength:f.srcdoc.length,opaque:f.contentDocument===null}:null,
                      csp:document.querySelector('meta[http-equiv="Content-Security-Policy"]')?.content.slice(0,1200)})})()
                """.trimIndent()) { value -> outer.set(value.take(4096));outerDone.countDown() }
            }
            val observedOuter=outerDone.await(2,TimeUnit.SECONDS)
            val snapshot=org.json.JSONObject().put("selector","HtmlPreviewWebViewTest#offlineScriptsButtonsAndScrollWorkWithoutFileOrStorageAccess")
                .put("originalVerdictPassed",passed).put("native",native).put("outerObserved",observedOuter).put("outerJsonResult",outer.get())
                .put("stages",org.json.JSONArray(stages.toList())).put("console",org.json.JSONArray(console.toList())).put("progress",org.json.JSONArray(progress.toList()))
                .put("fragmentSha256",java.security.MessageDigest.getInstance("SHA-256").digest(fragment.toByteArray()).joinToString("") { "%02x".format(it) })
                .put("productionPolicyUnchanged",true)
            // Persist before teardown even on the original assertion failure. At most one owned-window image.
            evidence.json("diagnostic-before-teardown.json",snapshot)
            try { evidence.screenshot("assertion-window-before-teardown") }
            catch(error: Exception) { snapshot.put("screenshotFailure",error.javaClass.simpleName+": "+error.message?.take(160)) }
            evidence.json("diagnostic-before-teardown.json",snapshot)
            assertTrue("Inline sandbox script/button/scroll did not complete; pre-teardown diagnostics: ${evidence.directory}",passed)
        } finally {
            instrumentation.runOnMainSync {
                // Dispose also covers failures during create/attachment, without touching another window.
                created?.let { (it.parent as? ViewGroup)?.removeView(it) }
                page.dispose()
            }
        }
    }
    @Test(timeout=40_000) fun nativeUserAnchorTapScrollsWithinSrcdoc() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun <T> main(block: ()->T): T {
            val result=java.util.concurrent.atomic.AtomicReference<Result<T>>()
            instrumentation.runOnMainSync { result.set(runCatching(block)) }
            return result.get().getOrThrow()
        }
        val ready=CountDownLatch(1);val anchored=CountDownLatch(1);val outerDone=CountDownLatch(1)
        val geometry=java.util.concurrent.atomic.AtomicReference<org.json.JSONObject?>()
        val outer=java.util.concurrent.atomic.AtomicReference<org.json.JSONObject?>()
        val hashState=java.util.concurrent.atomic.AtomicReference<String?>()
        val console=java.util.concurrent.CopyOnWriteArrayList<String>()
        val evidence=HtmlPreviewEvidence("anchor-target")
        val report=org.json.JSONObject().put("status","unavailable").put("injectionEvents",org.json.JSONArray())
        val prefix="P1_ANCHOR_${java.util.UUID.randomUUID()}:"
        val page=HtmlPreviewPage { true };var created: WebView?=null
        try {
            val web=main {
                page.create(ui.activity,"""
                    <a id="jump" href="#end" style="position:fixed;top:0;left:0;width:100%;height:80px;z-index:5">Jump</a>
                    <div style="height:2000px"></div><div id="end">Destination</div><div style="height:1000px"></div>
                    <script>
                    addEventListener('hashchange',function(){
                      if(location.hash==='#end' && scrollY>0)console.log('anchor-ok');
                      console.log(${org.json.JSONObject.quote(prefix+"state:")}+JSON.stringify({hash:location.hash,scrollY:scrollY}));
                    });
                    setTimeout(function(){
                      var a=document.getElementById('jump'),r=a.getBoundingClientRect(),hits=[];
                      for(var row=0;row<9;row++)for(var col=0;col<9;col++){
                        var x=+(r.left+r.width*(col+.5)/9).toFixed(2),y=+(r.top+r.height*(row+.5)/9).toFixed(2);
                        var hit=document.elementFromPoint(x,y);if(hit&&hit.closest('#jump')===a)hits.push({x:x,y:y});
                      }
                      console.log(${org.json.JSONObject.quote(prefix+"geometry:")}+JSON.stringify({width:innerWidth,height:innerHeight,
                        position:getComputedStyle(a).position,rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom},hits:hits}));
                    },500);
                    </script>
                """.trimIndent(),false).also { view ->
                    created=view
                    view.webChromeClient=object: WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            val line=message.message()
                            if(line.startsWith(prefix+"geometry:")&&line.length<=8192) {
                                runCatching { geometry.compareAndSet(null,org.json.JSONObject(line.removePrefix(prefix+"geometry:"))) }
                                ready.countDown()
                            } else if(line.startsWith(prefix+"state:"))hashState.set(line.removePrefix(prefix+"state:").take(512))
                            else if(console.size<16)console.add(message.messageLevel().name+": "+line.take(768))
                            if(line=="anchor-ok")anchored.countDown()
                            return true
                        }
                    }
                    ui.activity.addContentView(view,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }
            check(ready.await(15,TimeUnit.SECONDS)) { "Synthetic anchor geometry not ready; targeted input unavailable" }
            main {
                web.evaluateJavascript("""
                    (function(){var f=document.querySelector('iframe'),r=f?f.getBoundingClientRect():null;return JSON.stringify({
                      scheme:location.protocol,width:innerWidth,height:innerHeight,scrollX:scrollX,scrollY:scrollY,
                      frame:f?{left:r.left+f.clientLeft,top:r.top+f.clientTop,width:f.clientWidth,height:f.clientHeight}:null})})()
                """.trimIndent()) { value ->
                    runCatching { outer.set(org.json.JSONObject(org.json.JSONTokener(value).nextValue() as String)) }
                    outerDone.countDown()
                }
            }
            check(outerDone.await(2,TimeUnit.SECONDS)) { "Outer viewport unavailable; no injection" }
            val target=main { NativeHtmlAnchorTarget.prepare(instrumentation,web,checkNotNull(geometry.get()),checkNotNull(outer.get()),report) }
            val down=android.os.SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                main { target.verify("before-$action") }
                val eventRecord=org.json.JSONObject().put("action",action).put("x",target.x).put("y",target.y).put("injectionReturned",false)
                report.getJSONArray("injectionEvents").put(eventRecord)
                val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,target.x,target.y,0)
                // Retain Instrumentation's native target-UID guard. Never fall back on rejection.
                try { event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;instrumentation.sendPointerSync(event);eventRecord.put("injectionReturned",true) }
                finally { event.recycle() }
                main { target.verify("after-$action") }
            }
            val passed=anchored.await(10,TimeUnit.SECONDS);report.put("originalHashScrollVerdict",passed)
            assertTrue("User anchor must scroll within the sandbox; ${evidence.directory}",passed)
            report.put("status","passed")
        } catch(error: Throwable) {
            report.put("status","unavailable-or-failed").put("failure",error.javaClass.name+": "+error.message?.take(400));throw error
        } finally {
            created?.let { view -> runCatching { main { NativeHtmlAnchorTarget.diagnose(instrumentation,view,report) } }
                .onFailure { report.put("finalWindowUnavailable",it.javaClass.simpleName+": "+it.message?.take(240)) } }
            report.put("console",org.json.JSONArray(console.toList())).put("lastHashState",hashState.get())
                .put("anchorDom",geometry.get()).put("outerDom",outer.get()).put("rootDocumentServed",page.rootDocumentServed)
            // Both receipt and one own-window image are collected before removing the fixture.
            try {
                evidence.json("anchor-before-teardown.json",report)
                report.put("windowCaptureAvailable",evidence.screenshot("anchor-window-before-teardown"))
                evidence.json("anchor-before-teardown.json",report)
            } finally { main { created?.let { (it.parent as? ViewGroup)?.removeView(it) };page.dispose() } }
        }
    }
    @Test fun revokeImmediatelyBlocksNetworkAndEveryNewPageStartsOffline() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val opened=HtmlPreviewPage { true }
            val first=opened.create(instrumentation.targetContext,"<button>One</button>",true)
            try {
                assertFalse(first.settings.blockNetworkLoads)
                opened.revoke();assertTrue(first.settings.blockNetworkLoads)
            } finally { opened.dispose() }
            for(name in listOf("same-file-reopened","different-file")) {
                val next=HtmlPreviewPage { true }
                try { assertTrue(next.create(instrumentation.targetContext,"<p>$name</p>",false).settings.blockNetworkLoads) }
                finally { next.dispose() }
            }
        }
    }
}
