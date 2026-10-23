package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real pinned HTTPS and native framing against an explicitly synthetic transport server. */
@RunWith(AndroidJUnit4::class)
class TaskHubTransportTest {
    @get:org.junit.Rule val timeout=org.junit.rules.Timeout.seconds(90)
    @Test fun nativePinnedHTTPDoesNotRedirectRetryOrLeakCredentialIntoPublicProbe()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("taskHubEndpoint") ?: error("Missing isolated HTTPS fixture")
        val fingerprint=args.getString("taskHubFingerprint") ?: error("Missing fixture pin")
        val service=TaskHubService("fixture-deployment","fixture-instance","fixture-primary","Task Hub fixture",emptyList())
        val binding=TaskHubBinding(service,endpoint,fingerprint)
        val device=UUID.randomUUID().toString();val client=TaskHubClient(binding,device,"fixture-human-token")
        client.verify()
        client.request("auth/me")
        val op=UUID.randomUUID().toString()
        val original="{\"body\":\"原文 中文 😀\"}"
        assertEquals(original,client.request("boundary/patch","PATCH",original,op))
        try { client.request("boundary/redirect");fail("Redirect followed") } catch(e: TaskHubHTTPError) { assertEquals(302,e.status) }
        try { client.request("boundary/unknown","POST",original,UUID.randomUUID().toString());fail("Expected unknown outcome") } catch(_: java.io.IOException) { }
        var count=0
        client.events("00000000-0000-4000-8000-000000000011","41") { cursor,task,revision ->
            count++;assertEquals("42",cursor);assertEquals("00000000-0000-4000-8000-000000000011",task);assertEquals(8L,revision)
        }
        assertEquals(1,count)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"um06-upload-"+UUID.randomUUID()).apply { writeText("original multipart 中文 😀") }
        try { assertEquals("uploaded",client.uploadFile("00000000-0000-4000-8000-000000000011",null,UUID.randomUUID().toString(),file,"multipart/form-data; boundary=fixture",taskHubSha(file.readBytes()))) }
        finally { file.delete() }
        val counts=JSONObject(client.request("boundary/counts"))
        assertEquals(0,counts.getInt("publicCredential"));assertEquals(1,counts.getInt("unknownPosts"));assertEquals(1,counts.getInt("patches"));assertEquals(0,counts.getInt("redirected"));assertEquals(1,counts.getInt("uploads"))
        val wrong=TaskHubClient(binding.copy(fingerprint="0".repeat(64)),device,"fixture-human-token")
        try { wrong.verify();fail("Changed TLS pin accepted") } catch(_: javax.net.ssl.SSLException) { }
    }
}
