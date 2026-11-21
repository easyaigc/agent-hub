package com.agenthub.android

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A per-open local response at an unresolvable HTTPS origin; never a network allowlist entry. */
internal class HtmlPreviewRootDocument(fragment: String,online: Boolean) {
    val url="https://html-preview.invalid/${java.util.UUID.randomUUID()}/index.html"
    private val bytes=HtmlPreviewPolicy.document(fragment,online).toByteArray(Charsets.UTF_8)
    fun matches(requestUrl: String,mainFrame: Boolean,method: String)=mainFrame&&method=="GET"&&requestUrl==url
    fun open()=java.io.ByteArrayInputStream(bytes)
}

/** One immutable, bounded UTF-8 snapshot. Never uses a file:// base URL. */
internal object HtmlPreviewPolicy {
    const val maximumBytes=2*1024*1024
    val externalSources=listOf(
        "https://cdnjs.cloudflare.com", "https://esm.sh", "https://cdn.jsdelivr.net", "https://unpkg.com",
        "https://fonts.googleapis.com", "https://fonts.gstatic.com", "https://fonts.bunny.net",
        "https://persistent.oaistatic.com/plugins/visualize/"
    )
    fun accepts(name: String,mediaType: String)=name.substringAfterLast('.',"").lowercase() in setOf("html","htm") ||
        mediaType.substringBefore(';').trim().equals("text/html",ignoreCase=true)
    fun read(file: File): String {
        val bytes=file.inputStream().use { input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(16_384)
            while(true) { val count=input.read(buffer);if(count<0)break
                require(out.size()+count<=maximumBytes);out.write(buffer,0,count) }
            out.toByteArray()
        }
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in text)
        return text
    }
    fun allowsRequest(url: String,online: Boolean): Boolean {
        if(!online)return false
        val uri=runCatching { URI(url) }.getOrNull() ?: return false
        if(uri.scheme!="https" || uri.rawUserInfo!=null || uri.port !in listOf(-1,443))return false
        return externalSources.any { source ->
            val allowed=URI(source)
            uri.host==allowed.host && (allowed.rawPath.isNullOrEmpty() ||
                (uri.normalize().rawPath.orEmpty().startsWith(allowed.rawPath) &&
                    !uri.rawPath.orEmpty().contains('%') && !uri.rawPath.orEmpty().contains('\\')))
        }
    }
    fun allowsNavigation(url: String,mainFrame: Boolean,gesture: Boolean,method: String): Boolean {
        if(mainFrame || method!="GET" || url.substringBefore('#')!="about:srcdoc")return false
        // Only initial srcdoc creation and a same-document fragment. CSP separately denies all forms.
        return '#' in url || !gesture
    }
    private fun escape(text: String)=text.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;")
    fun document(fragment: String,online: Boolean): String {
        val sources=if(online)externalSources.joinToString(" ")else ""
        val policy="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' $sources; style-src 'unsafe-inline' $sources; img-src data: blob: $sources; font-src data: $sources; connect-src ${if(online)sources else "'none'"}; worker-src 'none'; media-src data: blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
        val inner="""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="${escape(policy)}"><style>$styles</style><script>$anchorNavigation</script></head><body>$fragment</body></html>"""
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="referrer" content="no-referrer"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="${escape(policy.replace("frame-src 'none'","frame-src about:"))}"><style>html,body{margin:0;height:100vh;overflow:hidden}iframe{display:block;position:fixed;top:0;left:0;width:100%;height:100vh;border:0}</style></head><body><iframe title="可视化" sandbox="allow-scripts" referrerpolicy="no-referrer" srcdoc="${escape(inner)}"></iframe></body></html>"""
    }
    // srcdoc inherits its parent's base URL. Handle literal #links locally so an about:blank
    // parent cannot turn a same-document user jump into a different document navigation.
    // This script stays in the opaque frame and exposes no message/native interface.
    private const val anchorNavigation="""document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[href]');if(!a)return;var h=a.getAttribute('href');if(h&&h[0]==='#'&&(!a.target||a.target==='_self')){e.preventDefault();if(location.hash===h){var n=document.getElementById(decodeURIComponent(h.slice(1)));if(n)n.scrollIntoView()}else{location.hash=h.slice(1)}if(h==='#')scrollTo(0,0)}},true);"""
    // Shared visualization fragment tokens; kept independent of conversation styling.
    private const val styles=""":root{color-scheme:light dark;--background:light-dark(#fff,#181818);--foreground:light-dark(#1a1c1f,#fff);--card:light-dark(#f5f5f5,#242424);--card-foreground:var(--foreground);--popover:var(--background);--popover-foreground:var(--foreground);--primary:light-dark(#007aff,#83c3ff);--primary-foreground:#fff;--secondary:var(--card);--secondary-foreground:var(--foreground);--muted:light-dark(#f0f0f0,#303030);--muted-foreground:light-dark(#737373,#aaa);--accent:light-dark(#e5f2ff,#0d273f);--accent-foreground:var(--primary);--border:light-dark(#ddd,#444);--input:var(--border);--ring:var(--primary);--destructive:#e25507;--blue:#339cff;--orange:#f3883b;--green:#5dc977;--red:#eb77b1;--purple:#9b79ec;--yellow:#ffc300;--viz-series-1:var(--primary);--viz-series-2:var(--orange);--viz-series-3:var(--green);--viz-series-4:var(--red);--viz-series-5:var(--purple);--viz-series-6:#3ab9b1;--color-background-primary:var(--background);--color-background-secondary:var(--card);--color-text-primary:var(--foreground);--color-text-secondary:var(--muted-foreground);--color-border-primary:var(--border);--color-border-secondary:var(--border);--color-text-info:var(--primary);--font-sans:-apple-system,BlinkMacSystemFont,system-ui,sans-serif;--font-mono:ui-monospace,monospace;--radius:12px}
  *{box-sizing:border-box}body{margin:0;padding:16px;font:16px/1.5 var(--font-sans);background:var(--background);color:var(--foreground);overflow-wrap:break-word;-webkit-text-size-adjust:100%}button,input,select,textarea{font:inherit}button,select,input[type=number]{min-height:44px}button{color:var(--foreground);background:var(--card);border:1px solid var(--border);border-radius:8px;padding:8px 14px;touch-action:manipulation}input[type=range]{max-width:100%}a{color:var(--primary)}svg,canvas,img{max-width:100%}pre{overflow:auto}table{border-collapse:collapse}td,th{padding:8px;border:1px solid var(--border)}.card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:16px}.muted,.subtitle{color:var(--muted-foreground)}.chart-container{position:relative;width:100%}svg text{fill:var(--foreground)}.axis text{fill:var(--muted-foreground)}.axis path,.axis line,.grid line{stroke:var(--border)}"""
}
