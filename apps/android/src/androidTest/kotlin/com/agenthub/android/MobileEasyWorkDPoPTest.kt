package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Public test proofs are independently verified by Node, never sent to a real service. */
@RunWith(AndroidJUnit4::class)
class MobileEasyWorkDPoPTest {
    @Test fun systemSigningKeyPersistsAndProducesInteroperableProofs() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val identity=runBlocking { EasyWorkIdentity.load(NativeSessionStorage(context)) }
        val signer=EasyWorkDPoP.load(identity)
        assertEquals(signer.thumbprint, EasyWorkDPoP.load(identity).thumbprint)
        val url="https://relay.example.test/v1/remote-access/relay/pending?bindingId=test#ignored"
        val token="public-boundary-test-token"
        val proof=signer.proof("GET",url,token)
        val next=signer.proof("GET",url,token,"public-server-nonce")
        val payload=JSONObject(String(easyBytes(proof.split('.')[1]),Charsets.UTF_8))
        assertEquals("https://relay.example.test/v1/remote-access/relay/pending",payload.getString("htu"))
        assertFalse(signer.jwk.has("d"))
        assertNotEquals(proof,next)
        for(invalid in listOf("http://relay.example.test/path","https://name:password@relay.example.test/path")) {
            assertThrows(IllegalArgumentException::class.java) { signer.proof("GET",invalid,token) }
        }
        assertThrows(IllegalArgumentException::class.java) { signer.proof("GET",url,"invalid\r\nheader") }
        val folder=File(context.getExternalFilesDir(null),"acceptance/A5-dpop").apply { mkdirs() }
        File(folder,"public-proofs.json").writeText(JSONObject().put("api",android.os.Build.VERSION.SDK_INT)
            .put("jwk",signer.jwk).put("thumbprint",signer.thumbprint).put("accessToken",token)
            .put("proofs",JSONArray(listOf(proof,next))).put("persistedKeyVerified",true).toString())
    }
}
