package com.agenthub.android

import android.system.ErrnoException
import android.system.OsConstants
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** Native presentation with platform exception types; synthetic GETs, no model inputs. */
@RunWith(AndroidJUnit4::class)
class MobileConnectionFailureTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private var nextFailure: Exception?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun failRead(failure: Exception) {
        val reads=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId","failure-fixture").put("name","synthetic failure computer").put("connectionMode","history-only").put("projects",JSONArray())
                .put("threads",JSONArray().put(JSONObject().put("id","failure-chat").put("hostId","failure-fixture").put("title","failure fixture").put("state","idle"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                nextFailure?.let { throw it }
                return HostReadCodec.history(JSONObject().put("hostId",client.id).put("threadId",threadId)
                    .put("messages",JSONArray().put(JSONObject().put("id","preserved-message").put("role","assistant").put("text","original synthetic history"))).toString())
            }
        }
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reads) }
        main { session.connect("synthetic failure computer","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.single());session.text="independent unsubmitted draft" }
        ui.waitUntil(5_000) { !session.historyLoading && session.messages.size==1 }
        ui.runOnIdle { session.leaveForeground();nextFailure=failure }
        main { session.loadHistory(userInitiated=true)?.join() }
        ui.waitUntil(5_000) { session.error!=null }
        assertEquals("original synthetic history",session.messages.single().text)
        assertEquals("independent unsubmitted draft",session.text);assertTrue(session.operationRecords.isEmpty())
    }
    private fun transport(cause: Exception)=HostReadError("synthetic transport detail",cause,
        cause is SocketException || cause is SocketTimeoutException || cause is UnknownHostException)
    private fun assertConnectionCopy(cause: Exception,message: String) {
        failRead(transport(cause));ui.onNodeWithText("暂时无法连接电脑").assertExists();ui.onNodeWithTag("session-error").assertTextContains(message,substring=true)
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun secureConnectionUsesGuidanceWithoutLosingHistoryOrDraft() = assertConnectionCopy(SSLException("synthetic TLS"),
        "与电脑的安全连接未能建立。请确认电脑已联网、连接服务正在运行，稍后再试；若仍无法连接，请在电脑端检查连接设置。")
    @Test fun nestedCertificateTakesPrecedenceOverGenericHandshake() = assertConnectionCopy(SSLHandshakeException("synthetic TLS").apply { initCause(CertificateException("synthetic certificate")) },
        "暂时无法验证这台电脑的安全连接。请在电脑端检查连接设置后重试；如果最近重新配置过连接，请重新添加这台电脑。")
    @Test fun nativeNetworkUnreachableUsesPhoneAndVpnGuidance() = assertConnectionCopy(SocketException("synthetic socket").apply { initCause(ErrnoException("connect",OsConstants.ENETUNREACH)) },
        "手机当前没有可用的网络。请检查 Wi-Fi 或移动网络；如果通过 VPN 连接，也请确认 VPN 已连接。")
    @Test fun wrappedSocketTimeoutUsesTimeoutGuidance() = assertConnectionCopy(SocketTimeoutException("synthetic timeout"),
        "连接电脑超时。请确认电脑已开机并联网，且连接服务正在运行，然后重试。")
    @Test fun dnsFailureUsesComputerAndVpnGuidance() = assertConnectionCopy(UnknownHostException("synthetic.invalid"),
        "暂时无法连接这台电脑。请确认电脑已开机并联网，且连接服务正在运行；如果通过 VPN 连接，也请确认 VPN 已连接。")
    @Test fun lostSocketUsesInterruptedConnectionGuidance() = assertConnectionCopy(SocketException("synthetic reset"),
        "与电脑的连接已中断。请检查手机和电脑的网络，连接恢复后再试。")
    @Test fun permissionFailureRetainsOriginalTextAndGenericTitle() {
        failRead(HostReadError("客户端密钥无效或未获得读取权限"));ui.onNodeWithText("无法完成操作").assertExists()
        ui.onNodeWithTag("session-error").assertTextContains("客户端密钥无效或未获得读取权限",substring=true)
    }
    @Test fun laterLocalFailureDoesNotBorrowConnectionTitle() {
        assertConnectionCopy(SocketTimeoutException("synthetic timeout"),"连接电脑超时。请确认电脑已开机并联网，且连接服务正在运行，然后重试。")
        ui.runOnIdle { session.error="synthetic local operation" };ui.onNodeWithText("无法完成操作").assertExists();ui.onNodeWithText("synthetic local operation").assertExists()
    }
}
