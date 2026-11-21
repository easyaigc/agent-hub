package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.webkit.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Fixed synthetic diagnostic only. No native bridge, arbitrary JS input, request or navigation. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class NativeHtmlSyntheticProbe(private val evidence: HtmlPreviewEvidence) {
    val nonce=UUID.randomUUID().toString()
    private val prefix="P1_HTML_DOM_V1:$nonce:"
    val fragment="""<button onclick="this.textContent='OK'">Go</button><script>
(function(){
'use strict';
const nonce='$nonce',prefix='P1_HTML_DOM_V1:'+nonce+':';let request=0;
const b=document.querySelector('button');
function rect(v){return {left:v.left,top:v.top,right:v.right,bottom:v.bottom,width:v.width,height:v.height};}
function box(n){if(!n)return null;const r=n.getBoundingClientRect(),s=getComputedStyle(n);return {width:r.width,height:r.height,clientWidth:n.clientWidth,clientHeight:n.clientHeight,offsetWidth:n.offsetWidth,offsetHeight:n.offsetHeight,scrollWidth:n.scrollWidth,scrollHeight:n.scrollHeight,computedWidth:s.width,computedHeight:s.height,minHeight:s.minHeight,display:s.display,position:s.position,overflowX:s.overflowX,overflowY:s.overflowY};}
function emit(trusted){
 const r=b.getBoundingClientRect(),s=getComputedStyle(b),v=window.visualViewport,hits=[];
 for(const fx of [0.25,0.5,0.75])for(const fy of [0.25,0.5,0.75]){
  const x=r.left+r.width*fx,y=r.top+r.height*fy;
  if(document.elementFromPoint(x,y)===b)hits.push({x:x,y:y});
 }
 console.log(prefix+JSON.stringify({nonce:nonce,request:request,kind:trusted===null?'snapshot':'click',
  trusted:trusted,text:b.textContent==='Go'?'Go':b.textContent==='OK'?'OK':'INVALID',
  documentBoxes:{html:box(document.documentElement),body:box(document.body)},
  ready:document.readyState,scheme:location.protocol,buttonCount:document.querySelectorAll('button').length,
  rect:rect(r),hits:hits,width:innerWidth,height:innerHeight,dpr:devicePixelRatio,scrollX:scrollX,scrollY:scrollY,
  viewport:v?{width:v.width,height:v.height,left:v.offsetLeft,top:v.offsetTop,scale:v.scale}:null,
  style:{display:s.display,visibility:s.visibility,opacity:s.opacity,color:s.color,background:s.backgroundColor}}));
}
addEventListener('message',function(e){
 const d=e.data;
 if(e.source!==parent||!d||d.kind!=='P1_FIXED_DOM_READ'||d.nonce!==nonce||
    !Number.isInteger(d.request)||d.request<1||d.request>4)return;
 request=d.request;
 if(document.readyState==='complete')emit(null);
 else addEventListener('load',function(){emit(null);},{once:true});
});
b.addEventListener('click',function(e){emit(e.isTrusted===true);});
if(document.readyState==='complete')emit(null);else addEventListener('load',function(){emit(null);},{once:true});
})();
</script>"""
    val fixtureBytes get()=fragment.toByteArray(Charsets.UTF_8)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val closed=AtomicBoolean(false)
    private val queue=LinkedBlockingQueue<JSONObject>(16)
    private val errors=ArrayList<String>()
    private val callbackFailure=AtomicReference<Throwable?>()
    private val firstZeroSizeChild=AtomicReference<JSONObject?>()
    private var resource: MobileSession.PreparedMessageResource?=null
    private var view: WebView?=null
    private var original: WebChromeClient?=null
    private var observer: WebChromeClient?=null
    private var own: (() -> Unit)?=null
    private var sequence=0
    private var tapAttempted=false

    fun claim(value: MobileSession.PreparedMessageResource) {
        check(Looper.myLooper()==Looper.getMainLooper()&&resource==null)
        resource=value;requireResource(value)
    }
    fun requireResource(value: MobileSession.PreparedMessageResource) {
        check(Looper.myLooper()==Looper.getMainLooper())
        check(value===resource&&value.name=="synthetic-layer-diagnosis.html"&&value.mediaType=="text/html")
        val file=value.local
        check(!Files.isSymbolicLink(file.toPath())&&file.parentFile.canonicalFile==instrumentation.targetContext.cacheDir.canonicalFile)
        check(file.name=="html-dom-$nonce.html"&&file.length()==fixtureBytes.size.toLong()&&file.readBytes().contentEquals(fixtureBytes))
    }
    private fun checkOwn() {
        check(Looper.myLooper()==Looper.getMainLooper()&&!closed.get())
        checkNotNull(own).invoke();requireResource(checkNotNull(resource))
        val w=checkNotNull(view)
        check(instrumentation.targetContext.applicationInfo.uid==Process.myUid()&&w.context.applicationInfo.uid==Process.myUid()&&w.context.packageName=="com.easyai.agenthub.android.acceptance")
        check(w.webChromeClient===observer)
    }
    private fun attach(web: WebView,validate: ()->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper()&&!closed.get())
        own=validate;validate();requireResource(checkNotNull(resource))
        if(view!=null) {check(view===web&&web.webChromeClient===observer);return}
        val client=checkNotNull(web.webChromeClient) { "Shipping ChromeClient unavailable; do not replace blindly" }
        view=web;original=client
        observer=object: WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if(!closed.get())try {
                    checkOwn()
                    val text=message.message()
                    if(text.startsWith(prefix)) {
                        check(text.length<=4096)
                        val value=JSONObject(text.substring(prefix.length))
                        check(value.getString("nonce")==nonce&&value.getInt("request") in 0..4)
                        val rect=value.getJSONObject("rect")
                        if(listOf(value.getDouble("width"),value.getDouble("height"),rect.getDouble("width"),rect.getDouble("height")).any {it==0.0}) {
                            if(firstZeroSizeChild.compareAndSet(null,value))evidence.json("synthetic-dom-zero-size-child.json",
                                JSONObject().put("rawChild",value).put("visualPass",false).put("acceptedReady",false))
                        }
                        validateChild(value) // Shape validation permits initial zero; awaitReady still requires strictly positive geometry.
                        check(queue.offer(value)) { "Fixed synthetic console budget exhausted" }
                    } else if(errors.size<12&&(message.sourceId().isNullOrEmpty()||message.sourceId()==web.url||message.sourceId()=="about:srcdoc")) {
                        errors.add("${message.messageLevel()}: "+text.take(320))
                    }
                } catch(error: Throwable) {callbackFailure.compareAndSet(null,error)}
                return client.onConsoleMessage(message)
            }
            // All public Chrome callbacks retain the exact original client behavior, including denials.
            override fun onPermissionRequest(request: PermissionRequest)=client.onPermissionRequest(request)
            override fun onPermissionRequestCanceled(request: PermissionRequest)=client.onPermissionRequestCanceled(request)
            override fun onGeolocationPermissionsShowPrompt(origin: String,callback: GeolocationPermissions.Callback)=client.onGeolocationPermissionsShowPrompt(origin,callback)
            override fun onGeolocationPermissionsHidePrompt()=client.onGeolocationPermissionsHidePrompt()
            override fun onProgressChanged(v: WebView,p: Int)=client.onProgressChanged(v,p)
            override fun onReceivedTitle(v: WebView,title: String)=client.onReceivedTitle(v,title)
            override fun onReceivedIcon(v: WebView,icon: Bitmap)=client.onReceivedIcon(v,icon)
            override fun onReceivedTouchIconUrl(v: WebView,url: String,precomposed: Boolean)=client.onReceivedTouchIconUrl(v,url,precomposed)
            override fun onShowCustomView(v: View,callback: CustomViewCallback)=client.onShowCustomView(v,callback)
            override fun onShowCustomView(v: View,orientation: Int,callback: CustomViewCallback)=client.onShowCustomView(v,orientation,callback)
            override fun onHideCustomView()=client.onHideCustomView()
            override fun onCreateWindow(v: WebView,dialog: Boolean,gesture: Boolean,message: android.os.Message)=client.onCreateWindow(v,dialog,gesture,message)
            override fun onRequestFocus(v: WebView)=client.onRequestFocus(v)
            override fun onCloseWindow(v: WebView)=client.onCloseWindow(v)
            override fun onJsAlert(v: WebView,url: String,message: String,result: JsResult)=client.onJsAlert(v,url,message,result)
            override fun onJsConfirm(v: WebView,url: String,message: String,result: JsResult)=client.onJsConfirm(v,url,message,result)
            override fun onJsPrompt(v: WebView,url: String,message: String,default: String,result: JsPromptResult)=client.onJsPrompt(v,url,message,default,result)
            override fun onJsBeforeUnload(v: WebView,url: String,message: String,result: JsResult)=client.onJsBeforeUnload(v,url,message,result)
            override fun onExceededDatabaseQuota(url: String,id: String,quota: Long,estimated: Long,total: Long,updater: WebStorage.QuotaUpdater)=client.onExceededDatabaseQuota(url,id,quota,estimated,total,updater)
            override fun onJsTimeout()=client.onJsTimeout()
            override fun onConsoleMessage(message: String,line: Int,source: String)=client.onConsoleMessage(message,line,source)
            override fun getDefaultVideoPoster(): Bitmap?=client.defaultVideoPoster
            override fun getVideoLoadingProgressView(): View?=client.videoLoadingProgressView
            override fun getVisitedHistory(callback: ValueCallback<Array<String>>)=client.getVisitedHistory(callback)
            override fun onShowFileChooser(v: WebView,callback: ValueCallback<Array<Uri>>,params: FileChooserParams)=client.onShowFileChooser(v,callback,params)
        }
        web.webChromeClient=observer
        checkOwn()
    }
    private fun validateChild(value: JSONObject) {
        check(value.getString("nonce")==nonce&&value.getInt("request") in 0..4)
        check(value.getString("text") in setOf("Go","OK","INVALID")&&value.getString("kind") in setOf("snapshot","click"))
        check(value.getString("ready") in setOf("loading","interactive","complete")&&value.getString("scheme")=="about:")
        check(value.getInt("buttonCount")==1&&value.getJSONArray("hits").length()<=9)
        for(key in listOf("width","height"))check(value.getDouble(key).isFinite()&&value.getDouble(key)>=0&&value.getDouble(key)<100000)
        val r=value.getJSONObject("rect")
        for(key in listOf("left","top","right","bottom","width","height"))check(r.getDouble(key).isFinite())
        check(r.getDouble("width")>=0&&r.getDouble("height")>=0) {"Fixed button has invalid layout"}
        val style=value.getJSONObject("style")
        for(key in listOf("display","visibility","opacity","color","background"))check(style.getString(key).length<=80)
    }
    private fun <T> main(block: ()->T): T {
        val out=AtomicReference<T>();val error=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {try {out.set(block())}catch(t: Throwable){error.set(t)}}
        error.get()?.let {throw it};return out.get()
    }
    private fun remaining(deadline: Long)= (deadline-SystemClock.uptimeMillis()).coerceAtLeast(0)
    private fun rootScript(request: Int)="""(function(){
const fs=document.querySelectorAll('iframe'),f=fs.length===1?fs[0]:null,v=window.visualViewport;
function box(n){if(!n)return null;const r=n.getBoundingClientRect(),s=getComputedStyle(n);return {width:r.width,height:r.height,clientWidth:n.clientWidth,clientHeight:n.clientHeight,offsetWidth:n.offsetWidth,offsetHeight:n.offsetHeight,scrollWidth:n.scrollWidth,scrollHeight:n.scrollHeight,computedWidth:s.width,computedHeight:s.height,minHeight:s.minHeight,display:s.display,position:s.position,overflowX:s.overflowX,overflowY:s.overflowY};}
const out={documentBoxes:{html:box(document.documentElement),body:box(document.body)},nonce:'$nonce',request:$request,ready:document.readyState,scheme:location.protocol,
 frames:fs.length,bodyChildren:document.body?document.body.children.length:0,width:innerWidth,height:innerHeight,dpr:devicePixelRatio,
 scrollX:scrollX,scrollY:scrollY,viewport:v?{width:v.width,height:v.height,left:v.offsetLeft,top:v.offsetTop,scale:v.scale}:null};
if(f){const r=f.getBoundingClientRect(),s=getComputedStyle(f);
 out.frame={left:r.left,top:r.top,width:r.width,height:r.height,clientLeft:f.clientLeft,clientTop:f.clientTop,
 clientWidth:f.clientWidth,clientHeight:f.clientHeight,offsetWidth:f.offsetWidth,offsetHeight:f.offsetHeight,
 sandbox:f.getAttribute('sandbox'),srcdocMatches:(f.getAttribute('srcdoc')||'').includes('$nonce'),
 display:s.display,visibility:s.visibility,opacity:s.opacity,transform:s.transform};
 f.contentWindow.postMessage({kind:'P1_FIXED_DOM_READ',nonce:'$nonce',request:$request},'*');}
return JSON.stringify(out);})();"""
    private fun readRoot(request: Int,deadline: Long): JSONObject {
        val done=CountDownLatch(1);val active=AtomicBoolean(true);val value=AtomicReference<JSONObject?>();val error=AtomicReference<Throwable?>()
        try {
            main {checkOwn();check(remaining(deadline)>0);checkNotNull(view).evaluateJavascript(rootScript(request)) { encoded ->
                if(active.get())try {checkOwn();value.set(JSONObject(JSONTokener(encoded).nextValue() as String))}
                catch(t: Throwable){error.set(t)}finally{done.countDown()}
            }}
            check(done.await(remaining(deadline),TimeUnit.MILLISECONDS)) {"Fixed root read unavailable in shared3s"}
            error.get()?.let {throw it};return checkNotNull(value.get())
        } finally {active.set(false)}
    }
    data class Snapshot(val outer: JSONObject,val child: JSONObject)
    fun awaitReady(stage: String,web: WebView,deadline: Long,validate: ()->Unit): Snapshot {
        check(Looper.myLooper()!=Looper.getMainLooper())
        val record=JSONObject().put("nonce",nonce).put("stage",stage).put("visualPass",false)
        try {
            main {attach(web,validate)}
            val request=++sequence;check(request in 1..4)
            var outer=readRoot(request,deadline);record.put("outer",outer)
            var initialRetried=false
            while(remaining(deadline)>0) {
                callbackFailure.get()?.let {throw it}
                val child=queue.poll(remaining(deadline),TimeUnit.MILLISECONDS) ?: break
                record.put("lastChild",child)
                if(child.getInt("request")==0&&!initialRetried) {initialRetried=true;outer=readRoot(request,deadline);record.put("outer",outer);continue}
                if(child.getInt("request")!=request||child.getString("kind")!="snapshot")continue
                check(child.getString("ready")=="complete"&&child.getString("text")=="Go") {"Fixed child not ready Go"}
                check(outer.getString("scheme")=="https:"&&outer.getInt("frames")==1&&outer.getInt("bodyChildren")==1)
                val frame=outer.getJSONObject("frame")
                val rect=child.getJSONObject("rect")
                val positiveGeometry=listOf(child.getDouble("width"),child.getDouble("height"),
                    rect.getDouble("width"),rect.getDouble("height"),frame.getDouble("height"),
                    frame.getDouble("clientHeight"),frame.getDouble("offsetHeight")).all {it.isFinite()&&it>0}
                if(!positiveGeometry) {
                    record.put("zeroGeometryNotAccepted",true)
                    // A valid initial zero-size snapshot is not ready. At most the
                    // existing one extra read is allowed, inside the same3s deadline.
                    if(!initialRetried) {initialRetried=true;outer=readRoot(request,deadline);record.put("outer",outer)}
                    continue
                }
                check(outer.getJSONObject("frame").getBoolean("srcdocMatches")&&outer.getJSONObject("frame").getString("sandbox")=="allow-scripts")
                main {checkOwn();check(web.url?.matches(Regex("https://html-preview\\.invalid/[0-9a-f-]{36}/index\\.html"))==true)}
                record.put("childReady",true).put("buttonHasPositiveLayout",child.getJSONObject("rect").getDouble("width")>0&&child.getJSONObject("rect").getDouble("height")>0);return Snapshot(outer,child)
            }
            error("No fixed child console response within shared3s")
        } finally {
            record.put("rawZeroSizeChild",firstZeroSizeChild.get()).put("callbackFailureType",callbackFailure.get()?.javaClass?.name).put("console",main {JSONArray(errors.toList())}).put("web",main {
                JSONObject().put("identity",System.identityHashCode(web)).put("progress",web.progress).put("contentHeight",web.contentHeight)
                    .put("width",web.width).put("height",web.height).put("scheme",web.url?.substringBefore(':'))
                    .put("layoutParams",web.layoutParams?.let { JSONObject().put("width",it.width).put("height",it.height) } ?: JSONObject.NULL)
            })
            evidence.json("$stage-dom-probe.json",record)
        }
    }
    private data class NativePoint(val root: View,val token: android.os.IBinder,val proof: String,val x: Float,val y: Float)
    private fun target(window: Window,web: WebView,snapshot: Snapshot,record: JSONObject): NativePoint {
        checkOwn();val root=web.rootView;val token=checkNotNull(root.windowToken)
        val roots=WindowInspector.getGlobalWindowViews().filter {it.isAttachedToWindow&&it.isShown&&it.hasWindowFocus()&&it.windowVisibility==View.VISIBLE&&it.context.packageName==instrumentation.targetContext.packageName&&it.context.applicationInfo.uid==Process.myUid()}
        check(roots.size==1&&roots.single()===root&&window.peekDecorView()===root&&web.windowToken===token)
        check(root.display?.displayId==android.view.Display.DEFAULT_DISPLAY&&web.isEnabled&&web.hasWindowFocus())
        check((window.attributes.flags and (WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))==0)
        var a: View?=web
        while(a!=null) {check(a.matrix.isIdentity&&a.alpha==1f);a=a.parent as? View}
        check(root.scrollX==0&&root.scrollY==0&&web.scrollX==0&&web.scrollY==0)
        fun bounds(v: View): RectF {val p=IntArray(2);v.getLocationOnScreen(p);return RectF(p[0].toFloat(),p[1].toFloat(),(p[0]+v.width).toFloat(),(p[1]+v.height).toFloat())}
        val rb=bounds(root);val wb=bounds(web);val size=android.graphics.Point();checkNotNull(root.display).getRealSize(size)
        check(rb.width()>0&&rb.height()>0&&RectF(0f,0f,size.x.toFloat(),size.y.toFloat()).contains(rb))
        val clip=Rect();check(web.getGlobalVisibleRect(clip))
        val origin=IntArray(2);root.getLocationOnScreen(origin);clip.offset(origin[0],origin[1]);val visible=RectF(clip);check(visible.intersect(rb))
        val wi=checkNotNull(root.rootWindowInsets)
        val ins=listOf(wi.getInsets(WindowInsets.Type.systemBars()),wi.getInsets(WindowInsets.Type.displayCutout()),wi.getInsets(WindowInsets.Type.ime()),
            wi.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()),wi.getInsets(WindowInsets.Type.mandatorySystemGestures()))
        val safe=RectF(rb);safe.left+=ins.maxOf{it.left};safe.top+=ins.maxOf{it.top};safe.right-=ins.maxOf{it.right};safe.bottom-=ins.maxOf{it.bottom}
        val o=snapshot.outer;val c=snapshot.child;val f=o.getJSONObject("frame");val br=c.getJSONObject("rect")
        for(v in listOf(o,c)) {check(v.getDouble("scrollX")==0.0&&v.getDouble("scrollY")==0.0)
            val vp=v.getJSONObject("viewport");check(vp.getDouble("scale")==1.0&&vp.getDouble("left")==0.0&&vp.getDouble("top")==0.0)}
        check(f.getString("transform")=="none"&&f.getString("visibility")=="visible"&&f.getDouble("opacity")==1.0)
        val style=c.getJSONObject("style");check(style.getString("display")!="none"&&style.getString("visibility")=="visible"&&style.getDouble("opacity")==1.0)
        val sx=web.width/o.getDouble("width");val sy=web.height/o.getDouble("height");check(sx>0&&sy>0&&kotlin.math.abs(sx/sy-1.0)<0.03)
        val fx=f.getDouble("width")/f.getDouble("offsetWidth");val fy=f.getDouble("height")/f.getDouble("offsetHeight")
        val ix=f.getDouble("clientWidth")/c.getDouble("width");val iy=f.getDouble("clientHeight")/c.getDouble("height")
        check(fx>0&&fy>0&&ix>0&&iy>0)
        fun screen(x: Double,y: Double)=Pair((wb.left+(f.getDouble("left")+fx*(f.getDouble("clientLeft")+x*ix))*sx).toFloat(),
            (wb.top+(f.getDouble("top")+fy*(f.getDouble("clientTop")+y*iy))*sy).toFloat())
        val tl=screen(br.getDouble("left"),br.getDouble("top"));val rr=screen(br.getDouble("right"),br.getDouble("bottom"))
        val area=RectF(tl.first,tl.second,rr.first,rr.second);check(area.intersect(wb)&&area.intersect(visible)&&area.intersect(safe))
        area.inset(maxOf(4f,2f*web.resources.displayMetrics.density),maxOf(4f,2f*web.resources.displayMetrics.density));check(!area.isEmpty)
        val hits=c.getJSONArray("hits");val points=(0 until hits.length()).map {hits.getJSONObject(it)}.map {screen(it.getDouble("x"),it.getDouble("y"))}
        val point=points.firstOrNull {area.contains(it.first,it.second)} ?: error("No safe DOM-hit point")
        fun front(v: View): View? {
            if(!v.isShown||v.alpha<=0f||!bounds(v).contains(point.first,point.second))return null
            if(v is WebView)return v
            if(v is ViewGroup)for(index in (0 until v.childCount).sortedWith(compareByDescending<Int>{v.getChildAt(it).z}.thenByDescending{it}))front(v.getChildAt(index))?.let{return it}
            return v
        }
        check(front(root)===web)
        val proof=JSONObject().put("uid",Process.myUid()).put("root",System.identityHashCode(root)).put("web",System.identityHashCode(web))
            .put("token",token.toString()).put("rootBounds",rb.toString()).put("webBounds",wb.toString()).put("visible",visible.toString())
            .put("safe",safe.toString()).put("insets",JSONArray(ins.map{listOf(it.left,it.top,it.right,it.bottom)})).put("flags",window.attributes.flags)
        record.put("native",proof).put("screenPoint",JSONArray(listOf(point.first,point.second))).put("buttonScreenIntersection",area.toString())
        return NativePoint(root,token,proof.toString(),point.first,point.second)
    }
    fun tapOnce(stage: String,window: Window,web: WebView,snapshot: Snapshot,deadline: Long): Boolean {
        check(!tapAttempted);tapAttempted=true
        val record=JSONObject().put("nonce",nonce).put("stage",stage).put("status","unavailable").put("visualPass",false)
        var downSent=false;var upSent=false;var inputAttempted=false
        try {
            val start=main {target(window,web,snapshot,record)}
            fun verify() {val now=main {target(window,web,snapshot,JSONObject())};check(now.root===start.root&&now.token===start.token&&now==start)}
            val downTime=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                check(remaining(deadline)>0);verify()
                val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,start.x,start.y,0)
                try {event.source=InputDevice.SOURCE_TOUCHSCREEN;inputAttempted=true;instrumentation.sendPointerSync(event)} finally {event.recycle()}
                if(action==MotionEvent.ACTION_DOWN)downSent=true else upSent=true
                verify()
            }
            while(remaining(deadline)>0) {
                callbackFailure.get()?.let{throw it}
                val message=queue.poll(remaining(deadline),TimeUnit.MILLISECONDS) ?: break
                if(message.getString("kind")!="click"||message.getInt("request")!=snapshot.child.getInt("request"))continue
                check(message.getBoolean("trusted")&&message.getString("text")=="OK"&&message.getString("ready")=="complete")
                verify();record.put("trustedClick",message).put("status","trusted-Go-to-OK-pixels-unverified");return true
            }
            error("No fixed trusted Go-to-OK response in shared3s")
        } catch(error: Throwable) {
            if(error !is Exception&&error !is AssertionError)throw error
            record.put("reason",error.javaClass.simpleName+": "+error.message?.take(240))
            // No second DOWN/UP, fallback injection API or unsafe cleanup event after ownership loss.
            if(inputAttempted&&!upSent)throw AssertionError("Native input outcome/owner unknown after attempt; no unsafe fallback UP",error)
            return false
        } finally {
            record.put("inputAttempted",inputAttempted).put("downSent",downSent).put("upSent",upSent).put("maximumNativeTaps",1)
            evidence.json("$stage-native-tap.json",record)
        }
    }
    /** Main only; called by method finally AND DisposableEffect, before original page destruction. */
    fun close(): Unit {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(!closed.compareAndSet(false,true))return
        val record=JSONObject().put("nonce",nonce).put("lateProbeCallbacksIgnored",true).put("workersCreated",0)
        try {
            val w=view
            if(w!=null) {
                check(w.webChromeClient===observer) {"Observer client changed; never overwrite a foreign replacement"}
                val client=checkNotNull(original);w.webChromeClient=client
                record.put("originalClientIdentity",System.identityHashCode(client)).put("restoredExactly",w.webChromeClient===client)
                check(w.webChromeClient===client)
            } else record.put("restoredExactly",true).put("observerWasAttached",false)
        } finally {
            queue.clear();own=null;view=null;original=null;observer=null
            evidence.json("synthetic-dom-probe-close.json",record)
        }
    }
}
