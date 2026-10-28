package com.agenthub.android

import androidx.compose.runtime.*
import javax.net.ssl.SSLException
import java.security.cert.CertificateException

/** Ephemeral connection form inputs. Submitted authorization intents remain in the native journal. */
internal class EasyWorkConnectionDraft(name: String,relay: String) {
    internal var formOwner: EasyWorkConnectionAuthority.Form?=null
    private var nameState by mutableStateOf(name)
    var name: String
        get()=nameState
        set(value) {if(nameState!=value){formOwner?.inputsChanged();nameState=value}}
    private var methodState by mutableStateOf("自动发现")
    var method: String
        get()=methodState
        set(value) {if(methodState!=value){formOwner?.inputsChanged();methodState=value}}
    private var qrState by mutableStateOf("")
    var qr: String
        get()=qrState
        set(value) {if(qrState!=value){formOwner?.inputsChanged();qrState=value}}
    private var addressState by mutableStateOf("")
    var address: String
        get()=addressState
        set(value) {if(addressState!=value){formOwner?.inputsChanged();addressState=value}}
    private var relayState by mutableStateOf(relay)
    var relay: String
        get()=relayState
        set(value) {if(relayState!=value){formOwner?.inputsChanged();relayState=value}}
    private var codeState by mutableStateOf("")
    var code: String
        get()=codeState
        set(value) {if(codeState!=value){formOwner?.inputsChanged();codeState=value}}
}

/** Reviewed product copy only; peer-provided errors and names are never translated or echoed. */
internal enum class EasyWorkConnectionMessage(val key: String) {
    FAILED("连接未完成，请核对电脑端提示后重试。"),
    CERTIFICATE("电脑证书校验失败，原连接已保留。"),
    TARGET("配对设备或运行目标不匹配，原连接已保留。"),
    EXPIRED("连接信息已过期或撤销，请在电脑上重新生成。"),
    REJECTED("电脑拒绝了此设备，请在电脑上重新确认。"),
    CODE("请输入电脑显示的 16 位连接码。"),
    QR("连接二维码内容无效，请从电脑重新复制。"),
    ISSUER("二维码的中继服务未受信任，请核对电脑上的服务地址。"),
    PENDING("请在已登录的电脑上确认此设备。"),
    VERIFY("请在电脑上确认此设备，核对码：%@");
    companion object {
        fun failure(error: Exception): EasyWorkConnectionMessage {
            if(generateSequence<Throwable>(error) {it.cause}.take(12).any {it is SSLException||it is CertificateException})return CERTIFICATE
            if(error is EasyWorkDeviceAuthorizationError)return when(error.code) {
                "offer_expired","refresh_expired","grant_revoked","binding_inactive"->EXPIRED
                "offer_not_found","offer_unavailable","claim_unavailable"->CODE
                else->FAILED
            }
            return when(error.message) {
                "请输入电脑显示的 16 位连接码"->CODE
                "二维码的中继服务未受信任，请核对电脑上的服务地址"->ISSUER
                "请扫描电脑提供的设备授权二维码","请扫描或粘贴桌面的局域网配对二维码","二维码缺少连接信息"->QR
                "连接信息无效或已过期，请在桌面重新生成","连接码已过期，请在电脑上重新生成","设备授权已过期或撤销，请重新配对"->EXPIRED
                "电脑拒绝了此设备，请重新配对","桌面拒绝了配对"->REJECTED
                "配对设备或运行时身份不匹配","配对账户不匹配","电脑身份校验失败","设备授权目标或密钥已改变，请重新配对"->TARGET
                else->FAILED
            }
        }
        fun verificationCode(value: String)=value.substringAfter("核对码：","").takeIf {it.matches(Regex("[0-9A-Z -]{1,64}"))}
    }
}
