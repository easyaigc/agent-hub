package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.MobileConnectionFailureKind
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** Platform cause precedence and bounded fallback, without issuing network requests. */
@RunWith(AndroidJUnit4::class)
class MobileFailureClassificationTest {
    @Test fun peerVerificationFailureRemainsCertificateGuidance() {
        val failure=SSLException("outer TLS").apply { initCause(SSLPeerUnverifiedException("peer mismatch")) }
        assertEquals(MobileConnectionFailureKind.CERTIFICATE,NativeOperationFailure.describe(failure,"fallback").kind)
    }
    @Test fun connectExceptionDoesNotBecomeLostSocket() {
        assertEquals(MobileConnectionFailureKind.UNREACHABLE,NativeOperationFailure.describe(ConnectException("connect refused"),"fallback").kind)
    }
    @Test fun unknownFailureWithNoMessageUsesCallerFallback() {
        val display=NativeOperationFailure.describe(Exception(),"caller context")
        assertNull(display.kind);assertEquals("caller context",display.message)
    }
    @Test(timeout=2_000) fun cyclicCausesTerminateWithoutReplacingDomainReason() {
        val first=Exception("original domain reason");val second=Exception("secondary domain reason")
        first.initCause(second);second.initCause(first)
        val display=NativeOperationFailure.describe(first,"fallback")
        assertNull(display.kind);assertEquals("original domain reason",display.message);assertSame(second,first.cause);assertSame(first,second.cause)
    }
    @Test fun outerTlsKeepsTlsGuidanceWhenUnderlyingSocketTimesOut() {
        val failure=SSLException("outer TLS").apply { initCause(SocketTimeoutException("nested timeout")) }
        assertEquals(MobileConnectionFailureKind.SECURE_CONNECTION,NativeOperationFailure.describe(failure,"fallback").kind)
    }
}
