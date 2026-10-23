package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MobileHistoryBoundaryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun storage()=NativeSessionStorage(context,"history-boundary-"+UUID.randomUUID())
    private fun wire(count: Int=70)=JSONObject().put("hostId","history-host").put("threadId","history-thread")
        .put("messages",JSONArray((0 until count).map { JSONObject().put("id","message-$it").put("role","assistant").put("text","中文 😀 \" \\ \n".repeat(400)+it)
            .put("kind",if(it==33) "contextCompaction" else "agentMessage").put("status","completed") }))
    private fun decode(value: String)=NativeHistoryWire.history(value.byteInputStream()) { true }
    private fun rejected(block: ()->Unit) { try { block();fail("Invalid or incomplete data must be rejected") } catch(_: Exception) { } }
    @Test fun streamedHttpAndEncryptedFragmentsRestoreEveryOriginalMessage() = runBlocking {
        val input=wire().toString();val expected=HostReadCodec.history(input)
        assertSame(expected.messages.first().text,decode(input).messages.first().text)
        assertNotEquals(expected.messages.first().text,NativeHistoryStrings.share(expected.messages.first().text+"different"))
        CommandFixture { WireResponse(body=input) }.use { server ->
            val client=MobileClient("history-host","history",endpoint=server.endpoint)
            val actual=NativeHostReads { "" }.history(client,"history-thread")
            assertEquals(expected.messages,actual.messages);assertEquals(0,JSONObject(actual.wire).getJSONArray("messages").length())
            val s=storage();val cache=NativeHistoryCache(s);cache.save("target",client,"history-thread",actual,actual.messages)
            val restored=cache.read("target")!!;assertEquals(actual.messages,restored.messages)
            assertEquals(3,JSONObject(s.read("history:target")!!).getJSONArray("parts").length())
            assertEquals(1,server.requests.size)
        }
    }
    @Test fun incompleteReplacementCannotPublishOrRemovePreviousSnapshot() {
        val s=storage();val c=NativeHistoryCache(s);val p=decode(wire().toString());val client=MobileClient("history-host","history")
        c.save("target",client,"history-thread",p,p.messages);val index=s.read("history:target")
        rejected { c.save("target",client,"history-thread",p.copy(wire="invalid"),p.messages.take(40)) }
        assertEquals(index,s.read("history:target"));assertEquals(p.messages,c.read("target")!!.messages)
        c.save("target",client,"history-thread",p,p.messages.take(34));assertEquals(p.messages.take(34),c.read("target")!!.messages)
        JSONObject(index!!).getJSONArray("parts").strings().forEach { assertNull(s.read(it)) }
    }
    @Test fun legacyCacheAndFragmentTargetChecksPreserveEvidence() {
        val s=storage();val c=NativeHistoryCache(s);val original=wire(2)
        val longName="long:"+"x".repeat(1024);s.write(longName,original.toString());assertEquals(original.toString(),s.read(longName))
        s.write("history:legacy",JSONObject().put("version",1).put("targetKey","legacy").put("history",original).toString())
        assertEquals(decode(original.toString()).messages,c.read("legacy")!!.messages)
        val p=decode(wire(34).toString());c.save("target",MobileClient("history-host","history"),"history-thread",p,p.messages)
        val index=JSONObject(s.read("history:target")!!);val parts=index.getJSONArray("parts").strings()
        s.remove(parts.last());rejected { c.read("target") };assertNotNull(s.read(parts.first()));assertNotNull(s.read("history:target"))
        index.put("parts",JSONArray(listOf("history:other:part:${index.getString("generation")}:0")))
        s.write("history:target",index.toString());rejected { c.read("target") };assertNotNull(s.read(parts.first()))
    }
    @Test fun streamedEnvelopeMessagesAndEventsKeepSchemaAndTargetGuards() {
        rejected { decode("{\"hostId\":\"h\",\"threadId\":\"t\",\"messages\":[{\"id\":\"i\",\"role\":\"assistant\",\"text\":null}]}") }
        rejected { decode("{\"hostId\":\"h\",\"hostId\":\"other\",\"threadId\":\"t\",\"messages\":[]}") }
        rejected { decode(wire(1).toString()+"{}") };rejected { decode(wire(1).put("cursor",1.5).toString()) }
        val events=JSONObject().put("hostId","history-host").put("epoch","e").put("cursor",1).put("reset",true)
            .put("events",JSONArray().put(JSONObject().put("threadId","history-thread").put("snapshot",wire(2))))
        val good=NativeHistoryWire.events(events.toString().byteInputStream()) { true };assertEquals(2,good.snapshots.single().messages.size)
        events.getJSONArray("events").getJSONObject(0).put("threadId","wrong")
        rejected { NativeHistoryWire.events(events.toString().byteInputStream()) { true } }
    }
    @Test fun strictUtf8ByteLimitAndCancellationAreNeverSilentlyAccepted() {
        rejected { NativeHistoryWire.history(byteArrayOf(0xC3.toByte(),0x28).inputStream()) { true } }
        rejected { NativeWireReader.read("😀".byteInputStream(),3) { true } }
        assertEquals("😀",NativeWireReader.read("😀".byteInputStream(),4) { true })
        rejected { NativeHistoryWire.history(wire(1).toString().byteInputStream()) { false } }
    }
    @Test fun historyCoalescingKeepsNewestSnapshotPerTargetWithoutReorderingIntentWrites() {
        val s=storage();val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        val observed=java.util.concurrent.CopyOnWriteArrayList<String>();val failure: (Exception)->Unit={ throw AssertionError(it) }
        s.enqueueSnapshot("one",{ entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));observed.add("one-first") },failure)
        assertTrue(entered.await(10,TimeUnit.SECONDS))
        s.enqueueSnapshot("one",{ observed.add("one-obsolete") },failure)
        s.enqueueSnapshot("one",{ observed.add("one-newest") },failure)
        s.enqueueSnapshot("two",{ observed.add("two") },failure)
        s.enqueue("intent","immutable",failure);s.enqueueSnapshot("end",{ done.countDown() },failure)
        release.countDown();assertTrue(done.await(10,TimeUnit.SECONDS))
        assertEquals(listOf("one-first","one-newest","two"),observed);assertEquals("immutable",s.read("intent"))
    }
}
