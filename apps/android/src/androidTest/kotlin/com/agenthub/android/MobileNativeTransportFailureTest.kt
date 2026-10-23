package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.FailureCopy
import com.agenthub.core.MobileConnectionFailureKind
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.net.ConnectException
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertPathBuilderException
import javax.net.ssl.SSLPeerUnverifiedException
import java.util.UUID

/** Real native DNS/TCP/TLS failures, not preconstructed exceptions; synthetic targets and no credentials. */
@RunWith(AndroidJUnit4::class)
class MobileNativeTransportFailureTest {
    @Before fun optIn() {
        require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance")
        require(InstrumentationRegistry.getArguments().getString("nativeTransportFailures")=="1")
    }
    private fun failure(endpoint: String,expected: MobileConnectionFailureKind): List<Throwable> {
        val client=MobileClient("synthetic-transport-host","synthetic transport",endpoint=endpoint,isDemo=false)
        val error=runBlocking {
            try {
                withTimeout(20_000) { NativeHostReads { "" }.history(client,"synthetic-transport-thread") }
                throw AssertionError("A refused native read must not publish history")
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) { error }
        }
        assertTrue("Original native transport error must remain available",error is HostReadError)
        val presentation=NativeOperationFailure.describe(error,"unexpected fallback")
        assertEquals(expected,presentation.kind);assertEquals(FailureCopy.connectionTitle,presentation.title)
        assertEquals(FailureCopy.message(expected),presentation.message)
        val causes=generateSequence<Throwable>(error) { it.cause }.take(16).toList()
        println("NATIVE_TRANSPORT_CAUSES="+causes.joinToString(",") { it.javaClass.name })
        return causes
    }
    @Test fun actualDnsFailureUsesUnreachableGuidance() {
        val causes=failure("https://native-read-"+UUID.randomUUID()+".invalid",MobileConnectionFailureKind.UNREACHABLE)
        assertTrue(causes.any { it is UnknownHostException })
    }
    @Test fun actualClosedLoopbackPortUsesUnreachableGuidance() {
        val reserved=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val port=reserved.localPort;reserved.close()
        val causes=failure("http://127.0.0.1:$port",MobileConnectionFailureKind.UNREACHABLE)
        assertTrue(causes.any { it is ConnectException })
    }
    @Test fun actualUntrustedCertificateRemainsRejectedWithCertificateGuidance() {
        val endpoint=InstrumentationRegistry.getArguments().getString("nativeTlsFixture") ?: error("Exact ephemeral TLS fixture required")
        require(Regex("https://127\\.0\\.0\\.1:[0-9]{1,5}").matches(endpoint))
        val causes=failure(endpoint,MobileConnectionFailureKind.CERTIFICATE)
        assertTrue(causes.any { it is CertificateException || it is CertPathValidatorException || it is CertPathBuilderException || it is SSLPeerUnverifiedException })
    }
}
