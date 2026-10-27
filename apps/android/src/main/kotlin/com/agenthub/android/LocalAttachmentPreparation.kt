package com.agenthub.android

import androidx.compose.runtime.mutableStateMapOf
import com.agenthub.core.AttachmentPreparationRules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/** Local preparation belongs to a draft generation, not the screen showing it. */
data class AttachmentImportDestination(val key: String,val generation: String,
    val presentation: DraftDestination,val requestId: String=UUID.randomUUID().toString())
data class LocalAttachmentInput(val name: String,val mediaType: String,val bytes: ByteArray)

/** This component can prepare local bytes only. It has no Host command or upload interface. */
internal class LocalAttachmentPreparation(
    private val tasks: CoroutineScope,
    private val files: ()->NativeDraftFiles?,
    private val current: (AttachmentImportDestination)->Boolean,
    private val count: (String)->Int,
    private val commit: (AttachmentImportDestination,LocalDraftFile)->Unit,
    private val referenced: (LocalDraftFile)->Boolean,
) {
    private val active=mutableStateMapOf<String,AttachmentImportDestination>()
    private val jobs=mutableMapOf<String,Job>()
    private val reservations=mutableMapOf<String,Int>()
    private val completed=linkedSetOf<String>()
    private val workers=Semaphore(2)
    fun preparing(key: String,generation: String?)=active.values.any { it.key==key&&it.generation==generation }
    fun start(destination: AttachmentImportDestination,sources: List<suspend ()->LocalAttachmentInput>,failed: (Exception)->Unit): Deferred<Unit>? {
        val loaders=sources.toList()
        if(!current(destination)||destination.requestId in active||destination.requestId in completed)return null
        val store=files() ?: return null
        require(AttachmentPreparationRules.canReserve(count(destination.key),reservations[destination.generation] ?: 0,loaders.size)) { "最多添加 10 个附件" }
        active[destination.requestId]=destination
        reservations[destination.generation]=(reservations[destination.generation] ?: 0)+loaders.size
        val job=tasks.async<Unit>(start=CoroutineStart.LAZY) {
            try {
                var firstFailure: Exception?=null
                for(loader in loaders) {
                    ensureActive();if(!current(destination))break
                    try { workers.withPermit {
                        ensureActive();if(!current(destination))return@withPermit
                        var prepared: LocalDraftFile?=null
                        try {
                            val input=withContext(Dispatchers.IO) { loader() }
                            ensureActive();if(!current(destination))return@withPermit
                            val file=store.import(input.name,input.mediaType,input.bytes);prepared=file
                            ensureActive()
                            if(current(destination))commit(destination,file)
                        } finally {
                            val file=prepared
                            if(file!=null&&!referenced(file))withContext(NonCancellable+Dispatchers.IO) { store.discardPrepared(file) }
                        }
                    } } catch(cancel: CancellationException) { throw cancel }
                      catch(failure: Exception) { failed(failure);if(firstFailure==null)firstFailure=failure }
                      catch(failure: OutOfMemoryError) {
                          val error=IllegalStateException("手机内存不足，未能准备附件；原草稿已保留",failure)
                          failed(error);if(firstFailure==null)firstFailure=error
                      }
                }
                firstFailure?.let { throw it }
            }
            finally {
                if(active.remove(destination.requestId)!=null) {
                    val remaining=(reservations[destination.generation] ?: loaders.size)-loaders.size
                    if(remaining==0)reservations.remove(destination.generation)else reservations[destination.generation]=remaining
                    completed.add(destination.requestId);while(completed.size>256)completed.remove(completed.first())
                }
                jobs.remove(destination.requestId)
            }
        }
        jobs[destination.requestId]=job;job.start();return job
    }
    fun reset() {
        jobs.values.toList().forEach { it.cancel() };jobs.clear();active.clear();reservations.clear();completed.clear()
    }
}
