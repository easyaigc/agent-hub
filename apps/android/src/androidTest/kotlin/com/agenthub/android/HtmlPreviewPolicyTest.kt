package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class HtmlPreviewPolicyTest {
    @Test fun virtualRootIsPerOpenExactGetMainFrameAndIsNeverAnExternalResource() {
        val root=HtmlPreviewRootDocument("<button>本地 # % original</button>",false)
        val another=HtmlPreviewRootDocument("<button>different</button>",false)
        assertNotEquals(root.url,another.url)
        assertTrue(root.matches(root.url,true,"GET"))
        assertFalse(root.matches(root.url,false,"GET"))
        assertFalse(root.matches(root.url,true,"POST"))
        for(url in listOf(another.url,root.url+"?read=1",root.url+"#end",root.url.replace("https:","http:"),
            root.url.replace("/index.html","/other.html"),"https://cdn.jsdelivr.net/index.html","file:///index.html")) {
            assertFalse(url,root.matches(url,true,"GET"))
        }
        assertFalse(HtmlPreviewPolicy.allowsRequest(root.url,true))
        assertFalse(HtmlPreviewPolicy.allowsRequest(root.url,false))
        val first=root.open().use { it.readBytes() }
        val second=root.open().use { it.readBytes() }
        assertArrayEquals(first,second)
        assertTrue(first.toString(Charsets.UTF_8).contains("本地 # % original"))
    }
    @Test fun htmlDisclosureAndStatusResolveInBothAndroidCatalogs() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val catalogs=instrumentation.context.assets.open("localization-catalog.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
        val keys=listOf("已允许本次预览联网","默认离线预览，外部资源已阻止","切回离线","允许联网…","允许本次预览联网","保持离线","预览联网许可")+
            catalogs.getJSONObject("en").keys().asSequence().filter { it.startsWith("允许后，此文档") || it.startsWith("预览不提供电脑凭据") }.toList()
        assertEquals(9,keys.size)
        for(language in listOf("en","zh-Hans")) {
            val native=NativeL10n(instrumentation.targetContext,language)
            for(key in keys) {
                assertEquals(catalogs.getJSONObject(language).getString(key),native.tr(key))
                if(language=="en")assertNotEquals(key,native.tr(key))
            }
        }
    }
    @Test fun onlySameSrcdocGetFragmentsAllowUserNavigation() {
        assertTrue(HtmlPreviewPolicy.allowsNavigation("about:srcdoc#中文",false,true,"GET"))
        assertTrue(HtmlPreviewPolicy.allowsNavigation("about:srcdoc",false,false,"GET"))
        assertFalse(HtmlPreviewPolicy.allowsNavigation("about:srcdoc",false,true,"GET"))
        assertFalse(HtmlPreviewPolicy.allowsNavigation("about:srcdoc#end",true,true,"GET"))
        assertFalse(HtmlPreviewPolicy.allowsNavigation("about:srcdoc#end",false,true,"POST"))
        listOf("https://example.org/#end","intent://open#end","file:///secret#end","about:blank#end","about:srcdoc?other#end").forEach {
            assertFalse(it,HtmlPreviewPolicy.allowsNavigation(it,false,true,"GET"))
        }
    }
    @Test fun detectionAndStrictBoundedDecoding() {
        assertTrue(HtmlPreviewPolicy.accepts("CHART.HTM","application/octet-stream"))
        assertTrue(HtmlPreviewPolicy.accepts("download","Text/HTML; charset=UTF-8"))
        assertFalse(HtmlPreviewPolicy.accepts("chart.html.txt","text/plain"))
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"html-policy-${UUID.randomUUID()}")
        try {
            file.writeBytes("\uFEFF<button>中文</button>".toByteArray());assertEquals("<button>中文</button>",HtmlPreviewPolicy.read(file))
            for(bytes in listOf(byteArrayOf(0xc3.toByte(),0x28),"bad\u0000".toByteArray(),ByteArray(HtmlPreviewPolicy.maximumBytes+1))) {
                file.writeBytes(bytes)
                assertTrue(runCatching { HtmlPreviewPolicy.read(file) }.isFailure)
            }
        } finally { assertTrue(file.delete()) }
    }
    @Test fun offlineAndUntrustedDestinationsRemainBlockedEvenAfterGrant() {
        val allowed="https://cdn.jsdelivr.net/npm/d3@7"
        assertFalse(HtmlPreviewPolicy.allowsRequest(allowed,false));assertTrue(HtmlPreviewPolicy.allowsRequest(allowed,true))
        listOf("file:///data/local/private","content://app.resources/one","intent://open","javascript:alert(1)",
            "http://cdn.jsdelivr.net/a","https://cdn.jsdelivr.net.evil.test/a","https://user:pass@cdn.jsdelivr.net/a",
            "https://cdn.jsdelivr.net:8443/a","https://127.0.0.1/a","https://example.org/a",
            "https://persistent.oaistatic.com/other/a","https://persistent.oaistatic.com/plugins/visualize/../secret",
            "https://persistent.oaistatic.com/plugins/visualize/%2e%2e/secret").forEach { assertFalse(it,HtmlPreviewPolicy.allowsRequest(it,true)) }
        assertTrue(HtmlPreviewPolicy.allowsRequest("https://persistent.oaistatic.com/plugins/visualize/chart.js",true))
    }
    @Test fun hostileFragmentIsEscapedInsideOpaqueSandboxAndCannotWidenOfflineCsp() {
        val malicious="</iframe><script>window.top.location='https://example.org'</script><meta http-equiv=Content-Security-Policy content=\"default-src *\">"
        val html=HtmlPreviewPolicy.document(malicious,false)
        assertEquals(1,Regex("<iframe ").findAll(html).count())
        assertTrue(html.contains("sandbox=\"allow-scripts\""));assertFalse(html.contains("allow-same-origin"))
        assertTrue(html.contains("&lt;/iframe&gt;"));assertFalse(html.contains(malicious))
        assertTrue(html.contains("connect-src 'none'"));assertTrue(html.contains("worker-src 'none'"))
        assertTrue(html.contains("form-action 'none'"));assertTrue(html.contains("base-uri 'none'"))
        assertFalse(html.contains("https://cdn.jsdelivr.net"))
        assertTrue(HtmlPreviewPolicy.document("",true).contains("https://cdn.jsdelivr.net"))
    }
}
