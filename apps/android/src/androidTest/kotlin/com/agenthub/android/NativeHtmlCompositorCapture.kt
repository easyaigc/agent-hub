package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Synthetic diagnostic: bound Window evidence plus explicitly opted-in emulator display comparison. */
internal class NativeHtmlCompositorCapture(private val evidence: HtmlPreviewEvidence,private val activity: ComponentActivity,
    private val excludedActivity: View,private val syntheticResource: (() -> MobileSession.PreparedMessageResource?)?=null,
    private val syntheticProbe: NativeHtmlSyntheticProbe?=null) {
    data class Observation(val owner: View,val language: String,val semantics: JSONArray) {
        fun json()=JSONObject().put("language",language).put("semantics",semantics)
            .put("ownerIdentity",System.identityHashCode(owner)).put("observedElapsedNanos",SystemClock.elapsedRealtimeNanos())
        fun requireSame(other: Observation) {
            check(owner===other.owner&&language==other.language&&semantics.toString()==other.semantics.toString()) {
                "Actual semantic owner, locale, node identity/text/bounds changed during capture"
            }
        }
    }
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private var attempts=0
    private val displayStages=HashSet<String>()
    private data class Bound(val owner: View,val provider: View,val window: Window,val root: View,
        val token: android.os.IBinder,val bounds: Rect,val visible: Rect,val flags: Int,val activityState: String) {
        fun json()=JSONObject().put("uid",root.context.applicationInfo.uid).put("package",root.context.packageName)
            .put("ownerClass",owner.javaClass.name).put("ownerIdentity",System.identityHashCode(owner))
            .put("providerClass",provider.javaClass.name).put("providerIdentity",System.identityHashCode(provider))
            .put("windowClass",window.javaClass.name).put("windowIdentity",System.identityHashCode(window))
            .put("decorIdentity",System.identityHashCode(root)).put("decorIsSemanticRoot",true)
            .put("rootClass",root.javaClass.name).put("rootIdentity",System.identityHashCode(root))
            .put("token",token.toString()).put("tokenIdentity",System.identityHashCode(token))
            .put("bounds",bounds.toShortString()).put("visible",visible.toShortString()).put("flags",flags)
            .put("ruleActivity",JSONObject(activityState))
            .put("attached",root.isAttachedToWindow).put("shown",root.isShown).put("focused",root.hasWindowFocus())
    }
    private fun bind(owner: View): Bound {
        check(Looper.myLooper()==Looper.getMainLooper())
        val target=instrumentation.targetContext;val uid=Process.myUid();val root=owner.rootView
        val ruleActivity=activityState()
        check(target.applicationInfo.uid==uid)
        check(listOf(owner,root).all { it.context.packageName==target.packageName&&it.context.applicationInfo.uid==uid })
        check(owner.isAttachedToWindow&&owner.isShown&&owner.hasWindowFocus())
        val focused=WindowInspector.getGlobalWindowViews().filter {
            it.rootView===it&&it.isAttachedToWindow&&it.isShown&&it.hasWindowFocus()&&it.windowVisibility==View.VISIBLE&&
                it.context.packageName==target.packageName&&it.context.applicationInfo.uid==uid
        }
        check(focused.size==1&&focused.single()===root&&root!==excludedActivity) { "No unique focused own semantic Dialog root" }
        check(root.width>0&&root.height>0)
        val token=checkNotNull(root.windowToken);check(owner.windowToken===token)
        val providers=ArrayList<View>();var ancestor: View?=owner;var depth=0
        while(ancestor!=null) {
            val current=ancestor;check(depth++<32&&current.rootView===root) { "Invalid semantic ancestor chain" }
            if(current is DialogWindowProvider)providers.add(current)
            if(current===root)break
            ancestor=current.parent as? View
        }
        check(providers.size==1) { "Expected exactly one DialogWindowProvider in actual semantic ancestor chain" }
        val provider=providers.single();val window=(provider as DialogWindowProvider).window
        check(provider.context.packageName==target.packageName&&provider.context.applicationInfo.uid==uid)
        check(window.peekDecorView()===root&&provider.windowToken===token) { "Provider Window decor/token is not semantic root" }
        check(window.decorView.windowToken===token)
        val flags=window.attributes.flags
        check((flags and WindowManager.LayoutParams.FLAG_SECURE)==0) { "Secure window evidence unavailable" }
        val location=IntArray(2);root.getLocationOnScreen(location)
        val bounds=Rect(location[0],location[1],location[0]+root.width,location[1]+root.height)
        val visible=Rect();check(root.getGlobalVisibleRect(visible)&&!visible.isEmpty)
        return Bound(owner,provider,window,root,token,bounds,visible,flags,ruleActivity.toString())
    }
    private fun <T> onMain(block: ()->T): T {
        val value=AtomicReference<T>();val error=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { value.set(block()) } catch(failure: Throwable) { error.set(failure) } }
        error.get()?.let { throw it };return value.get()
    }
    private fun mainBind(owner: View)=onMain { bind(owner) }
    @Suppress("DEPRECATION")
    private fun displayState(bound: Bound,expected: MobileSession.PreparedMessageResource?=null): Pair<MobileSession.PreparedMessageResource,JSONObject> {
        check(bind(bound.owner)==bound) { "Own dialog changed before display observation" }
        check(instrumentation.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "Display comparison is emulator-only" }
        // Activity identity comes only from this test's rule; DecorContext is never unwrapped.
        val context=activity
        activityState()
        check(bound.window.attributes.type in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..WindowManager.LayoutParams.LAST_APPLICATION_WINDOW)
        val display=checkNotNull(bound.root.display)
        check(display.displayId==Display.DEFAULT_DISPLAY&&display.state==Display.STATE_ON&&excludedActivity.display?.displayId==display.displayId)
        val size=Point();display.getRealSize(size)
        check(size.x>0&&size.y>0&&Rect(0,0,size.x,size.y).contains(bound.bounds)) { "Dialog outside ordinary display bounds" }
        val resource=fixedSyntheticResource(expected)
        val fixture=checkNotNull(syntheticProbe) { "Fixed diagnosis probe required" }.fixtureBytes
        val location=IntArray(2);excludedActivity.getLocationOnScreen(location)
        return resource to JSONObject().put("dialog",bound.json()).put("displayId",display.displayId)
            .put("width",size.x).put("height",size.y).put("rotation",display.rotation).put("hardware",Build.HARDWARE)
            .put("activityRootIdentity",System.identityHashCode(excludedActivity)).put("activityToken",excludedActivity.windowToken.toString())
            .put("activityBounds",Rect(location[0],location[1],location[0]+excludedActivity.width,location[1]+excludedActivity.height).toShortString())
            .put("activityFlags",context.window.attributes.flags).put("activityClass",context.javaClass.name)
            .put("syntheticName",resource.name).put("syntheticResourceIdentity",System.identityHashCode(resource))
            .put("syntheticBytes",fixture.size).put("syntheticFixtureMatches",true)
    }
    /** The exact rule instance and decor, never a global/context-derived Activity. Main only. */
    private fun activityState(): JSONObject {
        check(Looper.myLooper()==Looper.getMainLooper())
        val target=instrumentation.targetContext;val uid=Process.myUid()
        check(activity.javaClass==NativeSavedVoicePanelActivity::class.java&&!activity.isFinishing&&!activity.isDestroyed)
        check(activity.packageName=="com.easyai.agenthub.android.acceptance"&&activity.packageName==target.packageName)
        check(activity.applicationInfo.uid==uid&&target.applicationInfo.uid==uid)
        check(activity.window.peekDecorView()===excludedActivity&&excludedActivity.rootView===excludedActivity)
        check(excludedActivity.context.packageName==target.packageName&&excludedActivity.context.applicationInfo.uid==uid)
        check(excludedActivity.isAttachedToWindow&&excludedActivity.isShown&&excludedActivity.windowToken!=null)
        check((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)==0)
        val display=checkNotNull(excludedActivity.display)
        check(display.displayId==Display.DEFAULT_DISPLAY&&display.state==Display.STATE_ON)
        val location=IntArray(2);excludedActivity.getLocationOnScreen(location)
        return JSONObject().put("class",activity.javaClass.name).put("identity",System.identityHashCode(activity))
            .put("package",activity.packageName).put("uid",uid).put("decorIdentity",System.identityHashCode(excludedActivity))
            .put("token",excludedActivity.windowToken.toString()).put("tokenIdentity",System.identityHashCode(excludedActivity.windowToken))
            .put("bounds",Rect(location[0],location[1],location[0]+excludedActivity.width,location[1]+excludedActivity.height).toShortString())
            .put("flags",activity.window.attributes.flags).put("displayId",display.displayId).put("rotation",display.rotation)
    }
    private fun fixedSyntheticResource(expected: MobileSession.PreparedMessageResource?=null): MobileSession.PreparedMessageResource {
        check(Looper.myLooper()==Looper.getMainLooper())
        val resource=checkNotNull(syntheticResource?.invoke()) { "No current synthetic diagnosis resource" }
        check(expected==null||resource===expected) { "Synthetic resource changed during display capture" }
        check(resource.name=="synthetic-layer-diagnosis.html"&&resource.mediaType=="text/html")
        val local=resource.local.canonicalFile
        check(local.parentFile==instrumentation.targetContext.cacheDir.canonicalFile)
        val fixture=checkNotNull(syntheticProbe) { "Fixed diagnosis probe required" }.fixtureBytes
        checkNotNull(syntheticProbe).requireResource(resource)
        check(local.length()==fixture.size.toLong()&&local.readBytes().contentEquals(fixture)) { "Not the nonce-bound diagnostic fixture" }
        return resource
    }
    /** Search only this already-bound own Dialog tree. Never select a backing/global WebView. */
    private fun boundWebView(bound: Bound): WebView? {
        check(bind(bound.owner)==bound) { "Own dialog changed before WebView observation" }
        val views=java.util.ArrayDeque<View>();views.add(bound.root)
        val matches=ArrayList<WebView>();var visited=0
        while(views.isNotEmpty()) {
            check(++visited<=512) { "Bound dialog view budget exhausted" }
            val view=views.removeFirst();check(view.rootView===bound.root)
            if(view is WebView) {
                check(view.isAttachedToWindow&&view.isShown&&view.windowToken===bound.token)
                check(view.context.packageName==activity.packageName&&view.context.applicationInfo.uid==Process.myUid())
                matches.add(view)
            }
            if(view is ViewGroup)for(index in 0 until view.childCount)views.add(view.getChildAt(index))
        }
        check(matches.size<=1) { "More than one WebView in the bound Dialog" }
        return matches.singleOrNull()
    }
    /** Readiness evidence only, sharing the existing 3s root-frame budget. No visual-pass inference. */
    private fun awaitWebViewVisualState(name: String,bound: Bound,deadline: Long,receipt: JSONObject) {
        val done=CountDownLatch(1);val closed=AtomicBoolean(false)
        val outcome=AtomicReference<JSONObject?>();val failure=AtomicReference<Throwable?>()
        val request=SystemClock.elapsedRealtimeNanos()
        val resource=onMain { fixedSyntheticResource() }
        val web=onMain { boundWebView(bound) }
        if(name=="chinese-disclosure") {
            check(web==null) { "Disclosure must not borrow the backing preview WebView" }
            receipt.put("webViewVisualState",JSONObject().put("status","not-applicable-no-webview-in-bound-disclosure")
                .put("onlyBoundDialogSearched",true).put("provesVisualPass",false))
            return
        }
        check(name in setOf("english-offline","chinese-offline"))
        val view=checkNotNull(web) { "No unique WebView in bound preview Dialog" }
        fun state(): JSONObject {
            check(boundWebView(bound)===view);fixedSyntheticResource(resource)
            val location=IntArray(2);view.getLocationOnScreen(location)
            return JSONObject().put("identity",System.identityHashCode(view)).put("rootIdentity",System.identityHashCode(view.rootView))
                .put("tokenIdentity",System.identityHashCode(view.windowToken)).put("uid",view.context.applicationInfo.uid)
                .put("bounds",Rect(location[0],location[1],location[0]+view.width,location[1]+view.height).toShortString())
        }
        val before=onMain { state() };val armed=SystemClock.elapsedRealtimeNanos()
        try {
            val probe=checkNotNull(syntheticProbe)
            val dom=probe.awaitReady(name,view,deadline) {check(state().toString()==before.toString())}
            receipt.put("syntheticDom",JSONObject().put("outer",dom.outer).put("child",dom.child).put("visualPass",false))
            // One real UID-targeted tap only in the second preview phase; no extra screenshot stage.
            // If no safe point exists it remains unavailable; pixel evidence is still collected honestly.
            if(name=="chinese-offline")receipt.put("fixedGoTrustedTapVerified",probe.tapOnce(name,bound.window,view,dom,deadline))
            onMain {
                check(state().toString()==before.toString())
                view.postVisualStateCallback(request,object: WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if(closed.get())return
                        try {
                            check(requestId==request&&state().toString()==before.toString())
                            outcome.set(JSONObject().put("status","callback-received-visual-unverified").put("requestId",request)
                                .put("armedElapsedNanos",armed).put("callbackElapsedNanos",SystemClock.elapsedRealtimeNanos())
                                .put("webView",before).put("onlyBoundDialogSearched",true).put("provesVisualPass",false))
                        } catch(error: Throwable) { failure.set(error) }
                        finally { done.countDown() }
                    }
                })
            }
            check(done.await((deadline-SystemClock.uptimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)) {
                "Bound WebView visual-state callback unavailable within shared 3s frame budget"
            }
            failure.get()?.let { throw it }
            onMain { check(state().toString()==before.toString()) }
            receipt.put("webViewVisualState",checkNotNull(outcome.get()))
        } finally {
            // Public WebView has no unregister API. Ignore late callbacks; @After disposes their owner.
            closed.set(true)
            if(!receipt.has("webViewVisualState"))receipt.put("webViewVisualState",JSONObject().put("status","unavailable")
                .put("requestId",request).put("lateCallbacksIgnored",true).put("provesVisualPass",false))
        }
    }
    /** Optional comparison only. Failure never rewrites or substitutes the original own-Window evidence. */
    private fun captureDisplay(name: String,bound: Bound,original: Observation,observe: (String)->Observation) {
        val receipt=JSONObject().put("status","disabled").put("method","UiAutomation.takeScreenshot()")
            .put("source","default-display-compositor").put("pairedOwnWindowPng","$name.png")
            .put("sdk",Build.VERSION.SDK_INT).put("pid",Process.myPid()).put("uid",Process.myUid())
            .put("optInArgument","nativeHtmlDisplayCapture=1").put("defaultEnabled",false)
            .put("languageVisualVerified",false).put("redactionAbsenceProven",false)
            .put("raceBoundary","Own-process dialog focus/window/semantics and display geometry are observed before/after; no atomic global WindowManager or foreign-overlay proof.")
            .put("startedElapsedNanos",SystemClock.elapsedRealtimeNanos())
        var bitmap: Bitmap?=null;var pngStarted=false
        val png=File(evidence.directory,"$name-display.png")
        try {
            if(InstrumentationRegistry.getArguments().getString("nativeHtmlDisplayCapture")!="1")return
            receipt.put("status","unavailable")
            check(Looper.myLooper()!=Looper.getMainLooper()) { "Display capture must run on instrumentation worker" }
            check(name in setOf("english-offline","chinese-offline","chinese-disclosure"))
            check(displayStages.size<3&&displayStages.add(name)) { "Display budget exhausted or stage already attempted" }
            receipt.put("attempt",displayStages.size).put("maximumAttempts",3).put("maximumPerStage",1)
            val beforeObservation=observe("display-before");original.requireSame(beforeObservation)
            val before=onMain { displayState(bound) }
            receipt.put("before",before.second).put("beforeLanguageSemantics",beforeObservation.json())
                .put("ownWindowFrameAlreadyCommitted",true).put("captureCallStartedElapsedNanos",SystemClock.elapsedRealtimeNanos())
            // Standard public default-display API; no flags/permissions/settings changed. Synchronous, no invented API timeout.
            bitmap=instrumentation.uiAutomation.takeScreenshot()
            receipt.put("captureCallFinishedElapsedNanos",SystemClock.elapsedRealtimeNanos())
            val afterObservation=observe("display-after");beforeObservation.requireSame(afterObservation)
            val after=onMain { displayState(bound,before.first) }
            receipt.put("after",after.second).put("afterLanguageSemantics",afterObservation.json())
            check(before.second.toString()==after.second.toString()) { "Display/window/synthetic identity changed" }
            receipt.put("sameOwnWindowAndDisplayBeforeAfter",true).put("sameLanguageSemanticsBeforeAfter",true)
            val pixels=checkNotNull(bitmap) { "Display screenshot returned null; unavailable" }
            check(pixels.width==before.second.getInt("width")&&pixels.height==before.second.getInt("height")) { "Unexpected display screenshot geometry" }
            check(png.createNewFile()) { "Refusing to overwrite display evidence" };pngStarted=true;Os.chmod(png.path,384)
            png.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.PNG,100,it)) }
            var black=0
            for(y in 0 until 32)for(x in 0 until 32)if((pixels.getPixel(x*pixels.width/32,y*pixels.height/32) and 0x00ffffff)==0)black++
            receipt.put("width",pixels.width).put("height",pixels.height).put("bytes",png.length())
                .put("sha256",MessageDigest.getInstance("SHA-256").digest(png.readBytes()).joinToString("") { "%02x".format(it) })
                .put("blackSamplesOf1024",black).put("contentEvidence","requires-independent-display-image-review; secure/partial redaction is not inferable from focus or white pixels")
            if(black==1024)receipt.put("reason","Uniform black sample: possible protected/redacted/real content; unavailable")
            else receipt.put("status","captured-visual-unverified")
        } catch(error: Throwable) {
            if(error !is Exception&&error !is AssertionError)throw error
            receipt.put("status","unavailable").put("reason",error.javaClass.simpleName+": "+error.message?.take(240))
            if(pngStarted&&png.exists())receipt.put("partialPngRemoved",png.delete())
        } finally {
            bitmap?.recycle();receipt.put("finishedElapsedNanos",SystemClock.elapsedRealtimeNanos())
            evidence.json("$name-display.json",receipt)
        }
    }
    /** One capture per stage. Framework Window capture is synchronous; no claimed 3s API timeout. */
    fun capture(name: String,observe: (String)->Observation): Boolean {
        val receipt=JSONObject().put("status","unavailable").put("method","UiAutomation.takeScreenshot(Window)")
            .put("sdk",Build.VERSION.SDK_INT).put("syntheticContent",true).put("languageVisualVerified",false)
            .put("startedElapsedNanos",SystemClock.elapsedRealtimeNanos())
        val ready=CountDownLatch(1);val closed=AtomicBoolean(false);val failure=AtomicReference<String?>()
        val gate=AtomicReference<NativeHtmlFrameBarrier?>();var bitmap: Bitmap?=null
        val png=File(evidence.directory,"$name.png");var pngStarted=false;var saved=false
        try {
            check(Looper.myLooper()!=Looper.getMainLooper()) { "Blocking Window capture must run on instrumentation worker" }
            check(attempts++<3) { "Own-window compositor capture budget exhausted" }
            check(Build.VERSION.SDK_INT>=34) { "Public Window screenshot API unavailable" }
            val beforeObservation=observe("before");receipt.put("beforeLanguageSemantics",beforeObservation.json())
            val before=mainBind(beforeObservation.owner);receipt.put("before",before.json()).put("sameSemanticOwnerWindow",true)
            val synchronizationDeadline=SystemClock.uptimeMillis()+3000
            receipt.put("sharedWebViewAndFrameBudgetMs",3000)
            awaitWebViewVisualState(name,before,synchronizationDeadline,receipt)
            onMain {
                check(bind(before.owner)==before) { "Window changed before frame barrier" }
                val barrier=NativeHtmlFrameBarrier(before.root);gate.set(barrier);receipt.put("frameSubmission",barrier.evidence)
                barrier.start(ready={
                    if(!closed.get())try { check(bind(before.owner)==before) { "Window changed at frame commit" } }
                    catch(error: Exception) { failure.set(error.javaClass.simpleName+": "+error.message?.take(200)) }
                    finally { ready.countDown() }
                },unavailable={ reason -> if(!closed.get()) { failure.set(reason);ready.countDown() } })
            }
            check(ready.await((synchronizationDeadline-SystemClock.uptimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)) { "Own-window visual/draw/frame commit unavailable within shared 3s (not screenshot API timeout)" }
            check(failure.get()==null) { failure.get().orEmpty() }
            val committedObservation=observe("frame-ready");beforeObservation.requireSame(committedObservation)
            receipt.put("frameReadyLanguageSemantics",committedObservation.json())
            check(mainBind(committedObservation.owner)==before) { "Window changed before compositor call" }
            receipt.put("captureCallStartedElapsedNanos",SystemClock.elapsedRealtimeNanos())
            // Original bound-Window source stays separate from the optional emulator display comparison.
            bitmap=instrumentation.uiAutomation.takeScreenshot(before.window)
            receipt.put("captureCallFinishedElapsedNanos",SystemClock.elapsedRealtimeNanos())
            val after=mainBind(before.owner);receipt.put("after",after.json())
            check(after==before) { "Window/provider/decor/token/geometry/focus changed during compositor capture" }
            val afterObservation=observe("after");beforeObservation.requireSame(afterObservation)
            check(mainBind(afterObservation.owner)==before) { "Window changed during final semantic check" }
            receipt.put("afterLanguageSemantics",afterObservation.json()).put("sameRootTokenGeometryAndFocus",true)
                .put("sameLanguageSemanticsBeforeAndAfter",true)
            val pixels=checkNotNull(bitmap) { "Window compositor returned null; pixels unavailable" }
            check(pixels.width>0&&pixels.height>0)
            check(png.createNewFile()) { "Refusing to overwrite existing evidence" };pngStarted=true;Os.chmod(png.path,384)
            png.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val colors=HashSet<Int>();var white=0;var black=0
            for(y in 0 until 32)for(x in 0 until 32) {
                val color=pixels.getPixel(x*pixels.width/32,y*pixels.height/32);colors.add(color)
                if((color and 0x00ffffff)==0x00ffffff)white++
                if((color and 0x00ffffff)==0)black++
            }
            receipt.put("width",pixels.width).put("height",pixels.height).put("bytes",png.length())
                .put("sha256",MessageDigest.getInstance("SHA-256").digest(png.readBytes()).joinToString("") { "%02x".format(it) })
                .put("pixelSamples",JSONObject().put("count",1024).put("distinctColors",colors.size).put("exactWhite",white).put("exactBlack",black))
                .put("contentEvidence","requires-independent-own-window-language-image-review")
            if(black==1024)receipt.put("reason","Uniform black sample: possible redaction or real content; visual evidence unavailable")
            else { receipt.put("status","captured-visual-unverified");saved=true }
            if(saved)captureDisplay(name,before,beforeObservation,observe)
        } catch(error: Throwable) {
            if(error !is Exception&&error !is AssertionError)throw error
            receipt.put("reason",error.javaClass.simpleName+": "+error.message?.take(240))
            if(pngStarted&&png.exists())receipt.put("partialPngRemoved",png.delete())
        } finally {
            closed.set(true);bitmap?.recycle()
            try { onMain { gate.getAndSet(null)?.cancel() } }
            catch(error: Exception) {
                saved=false;receipt.put("status","unavailable").put("cleanupError",error.javaClass.simpleName+": "+error.message?.take(200))
            }
            receipt.put("finishedElapsedNanos",SystemClock.elapsedRealtimeNanos())
            evidence.json("$name-window.json",receipt)
        }
        return saved
    }
}
