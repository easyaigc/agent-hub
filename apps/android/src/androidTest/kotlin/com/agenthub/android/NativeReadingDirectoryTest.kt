package com.agenthub.android

import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Executable on host JVM: the real metadata collector, with no Android, Host or model service. */
class NativeReadingDirectoryTest {
    private val target=TargetScope("codex",null,null,"host","workspace","runtime")
    private val client=MobileClient("host","Host",endpoint="https://fixture.invalid",scope=target,isDemo=false)
    private fun row(id: String,n: Long=1)=MobileThread(id,"host",id,"idle",unread=true,
        readState=ThreadReadState(ThreadReadPosition(n,id,"a".repeat(64))))
    private fun page(rows: List<MobileThread>,cursor: String?=null)=MobileWorkspace("host","Host","history-only",emptyList(),rows,null,cursor,target,emptyList(),"unused-wire")
    @Test fun traversesUnloadedPagesDeduplicatesAndKeepsOnlySmallMetadata()=runBlocking {
        val cursors=mutableListOf<String?>()
        val result=NativeReadingDirectory.collect(client,page(listOf(row("one")),"p2"),{true}) { cursor ->
            cursors+=cursor
            when(cursor) { "p2"->page(listOf(row("one",2),row("two")),"p3");"p3"->page(listOf(row("three")));else->error("Unexpected first-page request") }
        }
        assertEquals(listOf("p2","p3"),cursors);assertEquals(3,result.pages);assertEquals(3,result.values.size)
        assertEquals(2L,result.values.first { it.threadId=="one" }.latest!!.revision)
        val graph=NativeReadingRetainedGraph.size(listOf(result));assertEquals(0,graph.histories);assertEquals(0,graph.messages);assertEquals(0,graph.largeStrings)
    }
    @Test fun repeatedCursorAndConflictingVersionNeverReturnAPartialSuccess()=runBlocking {
        suspend fun rejects(fetch: suspend (String?)->MobileWorkspace) {
            try { NativeReadingDirectory.collect(client,null,{true},fetch);fail("Invalid catalog accepted") }catch(_: IllegalArgumentException) { }
        }
        rejects { page(listOf(row("one")),"same") }
        rejects { cursor -> if(cursor==null)page(listOf(row("one")),"p2") else page(listOf(row("one").copy(readState=ThreadReadState(ThreadReadPosition(1,"one","b".repeat(64)))))) }
    }
    @Test fun missingOrForeignTargetsAndForeignChildrenAreRejected()=runBlocking {
        for(value in listOf(page(emptyList()).copy(scope=null),page(emptyList()).copy(scope=TargetScope("codex",null,null,"host","workspace","other")),page(listOf(row("one").copy(hostId="foreign"))))) {
            try { NativeReadingDirectory.collect(client,value,{true}) { error("No subsequent request") };fail("Foreign catalog accepted") }catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun responseAfterOwnerChangeCannotCompleteEvenIfTransportIgnoresCancellation()=runBlocking {
        var current=true
        try {
            NativeReadingDirectory.collect(client,page(listOf(row("one")),"p2"),{current}) { current=false;page(listOf(row("late"))) }
            fail("Late response accepted")
        }catch(_: CancellationException) { }
        assertFalse(NativeReadingDirectory.sameConnection(client,client.copy(scope=TargetScope("codex",null,null,"host","workspace","new"))))
        assertFalse(NativeReadingDirectory.sameConnection(client,client.copy(readerId="new-reader")))
        assertFalse(NativeReadingDirectory.sameConnection(client,client.copy(endpoint="https://other.invalid")))
    }
}
