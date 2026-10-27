package com.agenthub.android

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MobileHistoryPresentationTest {
    private fun original(messages: List<MobileMessage>, limit: Int=80, run: String?=null, running: Boolean=false, focus: String?=null): TimelineProjection {
        val values=messages.map { it.timelineMessage() }
        return MessageProjection.focus(MessageProjection.project(values,limit,run,running),values,focus)
    }
    private fun assertProjection(expected: TimelineProjection, actual: TimelineProjection) {
        assertEquals(expected.hasEarlierMessages,actual.hasEarlierMessages)
        fun rows(value: TimelineProjection)=value.rows.map { listOf(it.kind,it.id,it.indices,it.text,it.runId) }
        assertEquals(rows(expected),rows(actual))
    }
    @Test fun repeatedPresentationRetainsOneProjectionAndEveryInputInvalidatesIt() {
        val cache=NativeTimelineCache()
        val messages=MutableList(400) { MobileMessage("message-$it","assistant","正文 $it",runId="run-$it",replyPhase="final") }
        fun project(target: String="target",limit: Int=80,run: String?=null,running: Boolean=false,focus: String?=null)=cache.project(target,messages,limit,run,running,focus)
        var previous=project()
        repeat(200) { assertSame(previous,project()); assertSame(previous,project()) }
        val changes: List<(MobileMessage)->MobileMessage> = listOf(
            { it.copy(text="同 ID 更新的正文") }, { it.copy(status="running") }, { it.copy(kind="tool") },
            { it.copy(detail="新的工具输出") }, { it.copy(files=listOf("new-file.txt")) },
            { it.copy(attachments=listOf(MobileFile("file","image.png","image/png",120,"host","thread","digest"))) },
            { it.copy(blocks="[{\"type\":\"text\",\"text\":\"changed\"}]") },
            { it.copy(replyPhase="final",replyMessageId="reply-new") }, { it.copy(runId="run-new") })
        for(change in changes) {
            messages[399]=change(messages[399]);val next=project()
            assertNotSame(previous,next);assertProjection(original(messages),next);assertSame(next,project());previous=next
        }
        for(next in listOf(project(target="other-target"),project(limit=120),project(run="run-new",running=true),project(focus="message-399"))) {
            assertNotSame(previous,next);previous=next
        }
        assertTrue(previous.rows.any { it.kind=="message" && 399 in it.indices })
        messages.removeAt(0)
        assertProjection(original(messages),project())
        messages.clear();assertProjection(original(messages),project())
    }
    @Test fun runtimeKeyEncodingPreservesFullIdentityAndBoundedConcurrentEviction() {
        val cache=NativeRuntimeKeyCache(8)
        val codex=TargetScope("codex","old-account","one","host","workspace","runtime")
        val first=cache.value("client",codex,"thread",null)
        repeat(2000) { assertSame(first,cache.value("client",codex,"thread",null)) }
        val changedAccount=TargetScope("codex","new-account","two","host","workspace","runtime")
        assertSame(first,cache.value("client",changedAccount,"thread",null))
        val scopes=listOf(null,codex,changedAccount,
            TargetScope("codex",null,null,"other-host","workspace","runtime"),
            TargetScope("codex",null,null,"host","other-workspace","runtime"),
            TargetScope("codex",null,null,"host","workspace","other-runtime"),
            TargetScope("easywork","authority","one","host","workspace","runtime"),
            TargetScope("easywork","authority","two","host","workspace","runtime"))
        for(scope in scopes) for(thread in listOf(null,"","thread","汉字/e\u0301")) for(project in listOf(null,"","project")) {
            assertEquals(RuntimeIdentity.cacheKey("client",scope,thread,project),cache.value("client",scope,thread,project))
        }
        val tiny=NativeRuntimeKeyCache(2)
        val old=tiny.value("client",codex,"a",null)
        for(thread in listOf("b","c"))tiny.value("client",codex,thread,null)
        assertEquals(old,tiny.value("client",codex,"a",null))
        assertNotSame(old,tiny.value("client",codex,"a",null))
        val workers=java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val reads=List(200) { i -> workers.submit<Boolean> {
                val scope=scopes[i%scopes.size];val thread="thread-$i"
                RuntimeIdentity.cacheKey("client",scope,thread,null)==tiny.value("client",scope,thread,null)
            } }
            assertTrue(reads.all { it.get(5,java.util.concurrent.TimeUnit.SECONDS) })
        } finally { workers.shutdownNow() }
    }
    @Test fun repeatedLongHistoryPresentationCPUComparison() {
        val messages=List(1000) { MobileMessage("bench-$it",if(it%2==0) "user" else "assistant","长历史正文 ".repeat(80)+it,runId="run-${it/2}",replyPhase="final") }
        val cache=NativeTimelineCache();val first=cache.project("target",messages,80,null,false,null)
        repeat(20) { original(messages);cache.project("target",messages,80,null,false,null) }
        val baseline=mutableListOf<Long>();val cached=mutableListOf<Long>();var baselineBuilds=0;var cachedBuilds=1;var observedRows=0
        assertProjection(first,original(messages))
        repeat(5) {
            var start=Debug.threadCpuTimeNanos()
            repeat(200) { observedRows+=original(messages).rows.size;baselineBuilds++ }
            baseline+=Debug.threadCpuTimeNanos()-start
            start=Debug.threadCpuTimeNanos()
            repeat(200) { val next=cache.project("target",messages,80,null,false,null);if(next!==first)cachedBuilds++;observedRows+=next.rows.size }
            cached+=Debug.threadCpuTimeNanos()-start
        }
        assertEquals(1,cachedBuilds);assertTrue(observedRows>0)
        val keyCache=NativeRuntimeKeyCache()
        val scope=TargetScope("codex",null,null,"bench-host","bench-workspace","bench-runtime")
        val key=keyCache.value("client",scope,"thread",null)
        val keyBaseline=mutableListOf<Long>();val keyCached=mutableListOf<Long>();var observedKeyBytes=0
        repeat(5) {
            var start=Debug.threadCpuTimeNanos()
            repeat(200) { observedKeyBytes+=RuntimeIdentity.cacheKey("client",scope,"thread",null).length }
            keyBaseline+=Debug.threadCpuTimeNanos()-start
            start=Debug.threadCpuTimeNanos()
            repeat(200) { observedKeyBytes+=keyCache.value("client",scope,"thread",null).length }
            keyCached+=Debug.threadCpuTimeNanos()-start
        }
        assertEquals(key.length*2000,observedKeyBytes)
        val report=JSONObject().put("kind","synthetic-native-presentation").put("messages",messages.size).put("reads",1000)
            .put("baselineBuilds",baselineBuilds).put("cachedBuilds",cachedBuilds)
            .put("keyBaselineMedianThreadCPUNanosPer200",keyBaseline.sorted()[2]).put("keyCachedMedianThreadCPUNanosPer200",keyCached.sorted()[2])
            .put("baselineMedianThreadCPUNanosPer200",baseline.sorted()[2]).put("cachedMedianThreadCPUNanosPer200",cached.sorted()[2])
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"history-presentation-metrics.json").writeText(report.toString(2))
        println("HISTORY_PRESENTATION_METRICS $report")
    }
}
