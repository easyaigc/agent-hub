package com.agenthub.android

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import java.io.EOFException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.CharacterCodingException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

/** Preserve read failure provenance; never infer write delivery or retry an operation. */
internal fun eventReadFailure(failure: Exception): HostReadError {
    val causes=generateSequence(failure as Throwable) { it.cause }.take(12).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    if(failure is HostReadError&&!failure.transient)return failure
    if(causes.any { it is SSLException||it is GeneralSecurityException })
        return HostReadError("无法验证电脑的安全连接，请核对连接信息后重试。",failure)
    if(causes.any { it is IllegalArgumentException||it is JSONException||it is CharacterCodingException })
        return HostReadError("电脑返回的对话数据或运行目标无效，请重新读取。",failure)
    val status=causes.filterIsInstance<CodexCommandError>().firstNotNullOfOrNull { it.status }
    if(status!=null)return HostReadError(when(status) {
        401,403->"客户端密钥无效或未获得读取权限"
        in 300..399->"连接地址发生重定向，请核对电脑地址"
        502,503,504->"对话同步暂时中断，正在重试。"
        else->"无法同步对话，请稍后重试（$status）。"
    },failure,status in listOf(502,503,504))
    if(failure is HostReadError)return failure
    val transient=causes.any { it is SocketException||it is SocketTimeoutException||it is UnknownHostException||it is NoRouteToHostException||it is EOFException }
    return HostReadError(if(transient)"对话同步暂时中断，正在重试。" else "无法同步对话，请重新读取。",failure,transient)
}
