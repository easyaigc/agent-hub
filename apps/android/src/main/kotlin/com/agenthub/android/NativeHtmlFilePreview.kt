package com.agenthub.android

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream

/** Each open/mode owns its WebView. No native bridge, persistent storage or global WebView settings. */
internal class HtmlPreviewPage(private val current: ()->Boolean) {
    private interface PendingLoad { fun cancel() }
    private var view: WebView?=null
    private var pendingLoad: PendingLoad?=null
    @Volatile private var active=true
    @Volatile var rootDocumentServed=false
        private set
    fun revoke() {
        active=false
        pendingLoad?.cancel()
        view?.let { it.settings.blockNetworkLoads=true;it.stopLoading();it.loadUrl("about:blank") }
    }
    fun dispose() { revoke();view?.destroy();view=null }
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun create(context: Context,fragment: String,online: Boolean): WebView=WebView(context).also { web ->
        check(active);view=web
        // Chromium treats WRAP_CONTENT height as a zero CSS layout viewport,
        // independently of Compose's measured bounds.
        web.layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
        val document=HtmlPreviewRootDocument(fragment,online)
        web.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        web.settings.apply {
            javaScriptEnabled=true
            javaScriptCanOpenWindowsAutomatically=false
            setSupportMultipleWindows(false)
            allowFileAccess=false;allowContentAccess=false
            allowFileAccessFromFileURLs=false;allowUniversalAccessFromFileURLs=false
            domStorageEnabled=false;databaseEnabled=false;saveFormData=false
            cacheMode=WebSettings.LOAD_NO_CACHE
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads=!online
            mediaPlaybackRequiresUserGesture=true
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.setDownloadListener { _,_,_,_,_-> /* HTML cannot export or launch another app. */ }
        web.webChromeClient=object: WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String,callback: GeolocationPermissions.Callback) { callback.invoke(origin,false,false) }
        }
        web.webViewClient=object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                val url=request.url.toString()
                // User anchors stay inside the opaque srcdoc. No external or top-level navigation.
                return !active || !current() || !HtmlPreviewPolicy.allowsNavigation(
                    url,request.isForMainFrame,request.hasGesture(),request.method)
            }
            override fun shouldInterceptRequest(view: WebView,request: WebResourceRequest): WebResourceResponse? {
                // Called off-main: current() may inspect Compose/session state, so only consult the lease here.
                val url=request.url.toString()
                // Always fulfill this exact top-level GET from immutable memory, including offline.
                // No DNS/HTTP fallback: all other main-frame requests still hit the deny response below.
                if(active && document.matches(url,request.isForMainFrame,request.method)) {
                    rootDocumentServed=true
                    return WebResourceResponse("text/html","UTF-8",200,"OK",mapOf(
                        "Cache-Control" to "no-store","Referrer-Policy" to "no-referrer",
                        "X-Content-Type-Options" to "nosniff"),document.open())
                }
                if(active && url in setOf("about:blank","about:srcdoc"))return null
                return if(active && !request.isForMainFrame && HtmlPreviewPolicy.allowsRequest(url,online))null
                    else WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedHttpAuthRequest(view: WebView,handler: HttpAuthHandler,host: String,realm: String) { handler.cancel() }
            override fun onReceivedClientCertRequest(view: WebView,request: ClientCertRequest) { request.cancel() }
        }
        // AndroidView is initially detached/unmeasured. Start the immutable local
        // document only with a positive attached viewport, so its initial layout
        // is not calculated against zero native bounds. Both callback orders work.
        val pending=object: PendingLoad,View.OnLayoutChangeListener,View.OnAttachStateChangeListener {
            private var done=false
            override fun cancel() {
                done=true
                web.removeOnLayoutChangeListener(this)
                web.removeOnAttachStateChangeListener(this)
                if(pendingLoad===this)pendingLoad=null
            }
            private fun startIfReady() {
                if(done||!web.isAttachedToWindow||web.width<=0||web.height<=0)return
                cancel() // Retire both hooks before loadUrl can cause another layout.
                if(active&&view===web&&current())web.loadUrl(document.url)
            }
            override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,
                oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) = startIfReady()
            override fun onViewAttachedToWindow(v: View) = startIfReady()
            override fun onViewDetachedFromWindow(v: View) = Unit
            fun install() {
                web.addOnLayoutChangeListener(this)
                web.addOnAttachStateChangeListener(this)
                startIfReady()
            }
        }
        pendingLoad=pending
        pending.install()
    }
}

@Composable internal fun NativeHtmlFilePreview(resource: MobileSession.PreparedMessageResource,current: ()->Boolean,close: ()->Unit) {
    val context=LocalContext.current
    val latestCurrent by rememberUpdatedState(current)
    var fragment by remember(resource) { mutableStateOf<String?>(null) }
    var failure by remember(resource) { mutableStateOf(false) }
    var online by remember(resource) { mutableStateOf(false) }
    var disclosure by remember(resource) { mutableStateOf(false) }
    var shareFailure by remember(resource) { mutableStateOf(false) }
    val page=remember(resource,online) { HtmlPreviewPage { latestCurrent() } }
    DisposableEffect(page) { onDispose { page.dispose() } }
    LaunchedEffect(resource) {
        try {
            val text=withContext(Dispatchers.IO) { HtmlPreviewPolicy.read(resource.local) }
            if(latestCurrent())fragment=text
        } catch(cancel: CancellationException) { throw cancel }
          catch(_: Exception) { failure=true }
          catch(_: OutOfMemoryError) { failure=true }
    }
    fun dismiss() { page.revoke();online=false;disclosure=false;close() }
    // Target invalidation also retires scripts/network, rather than leaving an old document interactive.
    val valid=current()
    LaunchedEffect(valid) { if(!valid) { page.revoke();close() } }
    Dialog(onDismissRequest=::dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("visualization-preview-screen")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconAction("close",productText("完成"),"visualization-preview-close",onClick=::dismiss)
                    Text(resource.name,Modifier.weight(1f),maxLines=2)
                    IconAction("share",productText("保存或分享文件"),"visualization-preview-share",valid) {
                        if(latestCurrent())try { NativeResourceIntents.launch(context,resource,true,resource.name) }
                        catch(_: Exception) { shareFailure=true }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(productText(if(online)"已允许本次预览联网" else "默认离线预览，外部资源已阻止"),Modifier.weight(1f).testTag(if(online)"visualization-network-online" else "visualization-network-offline"),style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=valid&&fragment!=null&&!failure,onClick={
                        if(latestCurrent())if(online) { page.revoke();online=false } else disclosure=true
                    },modifier=Modifier.testTag("visualization-network-permission")) { Text(productText(if(online)"切回离线" else "允许联网…")) }
                }
                when {
                    failure -> Text(productText("此可视化文件过大、编码不支持或无法读取，可保存原文件后查看。"),Modifier.padding(24.dp).testTag("visualization-preview-error"))
                    fragment==null -> Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
                    valid -> key(page) { AndroidView(factory={ page.create(it,fragment!!,online) },modifier=Modifier.weight(1f).fillMaxWidth().testTag("visualization-web")) }
                }
            }
        }
    }
    if(disclosure)AlertDialog(onDismissRequest={ disclosure=false },title={ Text(productText("预览联网许可")) },text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(productText("允许后，此文档可从下列第三方站点加载脚本、字体和图片。这些站点会收到你的 IP 地址和请求信息；加载的脚本也能读取此文档及你在预览中输入的内容，并可向下列站点发送数据。请只为你信任的文档允许联网。"))
            HtmlPreviewPolicy.externalSources.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
            Text(productText("预览不提供电脑凭据或原生操作接口。联网许可仅适用于当前打开的这个文件，关闭后不保存。选择离线仍可查看不依赖外部资源的内容。"))
        }
    },confirmButton={ TextButton(onClick={
        if(latestCurrent()) { page.revoke();online=true };disclosure=false
    },modifier=Modifier.testTag("visualization-network-allow")) { Text(productText("允许本次预览联网")) } },
        dismissButton={ TextButton(onClick={ disclosure=false },modifier=Modifier.testTag("visualization-network-decline")) { Text(productText("保持离线")) } })
    if(shareFailure)AlertDialog(onDismissRequest={shareFailure=false},title={Text(productText("无法打开链接"))},text={Text(productText("手机上没有可打开此链接的应用。"))},
        confirmButton={TextButton(onClick={shareFailure=false}){Text(productText("好"))}})
}
