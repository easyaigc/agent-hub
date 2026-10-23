package com.agenthub.android

import android.system.ErrnoException
import android.system.OsConstants
import com.agenthub.core.FailureCopy
import com.agenthub.core.MobileConnectionFailureKind
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

internal data class NativeFailurePresentation(val title: String,val message: String,val kind: MobileConnectionFailureKind?)

/** Presentation only. Never changes the original exception, transport policy or operation. */
internal object NativeOperationFailure {
    private fun connectionKind(failure: Throwable): MobileConnectionFailureKind? {
        val seen=Collections.newSetFromMap(IdentityHashMap<Throwable,Boolean>())
        val causes=mutableListOf<Throwable>();var current: Throwable?=failure
        while(current!=null && causes.size<16 && seen.add(current)) { causes+=current;current=current.cause }
        return when {
            causes.any { it is CertificateException || it is CertPathBuilderException || it is CertPathValidatorException || it is SSLPeerUnverifiedException }->MobileConnectionFailureKind.CERTIFICATE
            causes.any { it is SSLException }->MobileConnectionFailureKind.SECURE_CONNECTION
            causes.any { it is ErrnoException && it.errno in listOf(OsConstants.ENETDOWN,OsConstants.ENETUNREACH) }->MobileConnectionFailureKind.NO_NETWORK
            causes.any { it is SocketTimeoutException }->MobileConnectionFailureKind.TIMEOUT
            causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException }->MobileConnectionFailureKind.UNREACHABLE
            causes.any { it is SocketException }->MobileConnectionFailureKind.LOST
            else->null
        }
    }
    fun describe(failure: Throwable,fallback: String): NativeFailurePresentation {
        val kind=connectionKind(failure)
        return NativeFailurePresentation(if(kind==null) FailureCopy.defaultTitle else FailureCopy.connectionTitle,
            kind?.let(FailureCopy::message) ?: failure.message ?: fallback,kind)
    }
}
