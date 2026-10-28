package com.agenthub.android

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.CancellationException

/** Complete thread metadata only. No history, task recovery, execution command, title or raw wire is retained. */
internal object NativeReadingDirectory {
    data class Result(val values: List<NativeReadingSnapshot>,val pages: Int)
    fun sameConnection(a: MobileClient,b: MobileClient)=a.id==b.id && sameScope(a.scope,b.scope) && a.endpoint==b.endpoint &&
        a.codexDirect?.fingerprint==b.codexDirect?.fingerprint && a.readerId==b.readerId && a.agentConnection==b.agentConnection
    suspend fun collect(client: MobileClient,first: MobileWorkspace?=null,current: ()->Boolean,
        fetch: suspend (String?)->MobileWorkspace): Result {
        val target=client.scope ?: throw HostReadError("电脑运行目标不完整")
        val readerId=client.readerId?.let(::nativeReaderId)
        val values=linkedMapOf<String,NativeReadingSnapshot>();var pages=0
        fun checkCurrent() { if(!current())throw CancellationException("Reading directory owner changed") }
        NativeWorkspaceThreadDirectory.scan<Unit>(client,first,current,fetch) { next,pageNumber ->
            pages=pageNumber
            for((index,thread) in next.threads.withIndex()) {
                if(index>0 && index%32==0) { currentCoroutineContext().ensureActive();checkCurrent();yield() }
                val key=ThreadReadingIdentity.key(target,thread.id)
                val value=NativeReadingSnapshot(target,thread.id,thread.readState?.latest,thread.unread,
                    readerId,thread.readState)
                val old=values[key];val prior=old?.latest;val incoming=value.latest
                require(prior==null || incoming==null || prior.revision!=incoming.revision || prior==incoming) { "未读目录阅读位置冲突" }
                val preferred=if(prior!=null && (incoming==null || prior.revision>incoming.revision))old else value
                values[key]=preferred!!.copy(sourceUnread=old?.sourceUnread==true || value.sourceUnread)
            }
            null
        }
        checkCurrent();return Result(values.values.toList(),pages)
    }
}
