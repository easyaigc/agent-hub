package com.agenthub.android

import androidx.compose.runtime.*
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*

internal data class NativeLegacyReading(val target: TargetScope,val threadId: String,val messageId: String,val contentVersion: String,
    val sourceUnread: Boolean)
internal data class NativeVisiblePhoneReading(val client: MobileClient,val key: String,val threadId: String,
    val selection: Long,val connectionRevision: Long,val retry: Long,val position: ThreadReadPosition,val body: NativeReadingBody)

/** Session-owned background writer/projection. Notification integration consumes this same ledger instance. */
internal class NativePhoneReadingStore(storage: NativeSessionStorage?,private val tasks: CoroutineScope,private val changed: ()->Unit) {
    val ledger=storage?.let(::NativeReadingLedger)
    val lifecycle=NativeReadingLifecycle()
    var snapshot by mutableStateOf(NativeUnreadSnapshot(0,emptySet(),emptyMap()));private set
    var ready by mutableStateOf(false);private set
    val heads=mutableStateMapOf<String,ThreadReadPosition>()
    var failure by mutableStateOf<String?>(null);private set
    var busy by mutableStateOf(false);private set
    private var generation=0L
    private data class Input(val scopes: List<TargetScope>,val values: List<NativeReadingSnapshot>,val legacy: List<NativeLegacyReading>)
    private var pending: Input?=null
    private var worker: Job?=null
    private data class LegacyPosition(val scope: String,val position: ThreadReadPosition)
    private val legacyPositions=linkedMapOf<String,LegacyPosition>()
    private var focusedKey: String?=null
    internal val legacyPositionCount get()=legacyPositions.size
    fun focus(key: String?) {
        focusedKey=key
        heads.keys.toList().filter { it!=key }.forEach(heads::remove)
    }
    fun publish(value: NativeUnreadSnapshot) { if(value.revision>=snapshot.revision) { snapshot=value;ready=true } }
    suspend fun awaitReconciliation() {
        while(worker?.isActive==true)worker?.join()
        check(ready && failure==null) { "Phone reading reconciliation is unavailable" }
    }
    fun reconcile(scopes: List<TargetScope>,values: List<NativeReadingSnapshot>,legacy: List<NativeLegacyReading>) {
        val ledger=ledger ?: return
        // Coalesce small metadata, not old pages/bodies. Switching chats before the writer runs must not lose a head.
        val rows=linkedMapOf<String,NativeReadingSnapshot>()
        for(value in pending?.values.orEmpty()+values) {
            val key=ThreadReadingIdentity.key(value.target,value.threadId);val old=rows[key]
            val prior=old?.latest ?: old?.state?.latest;val next=value.latest ?: value.state?.latest
            val preferred=if(prior!=null && (next==null || prior.revision>next.revision || prior.revision==next.revision && prior!=next))old!! else value
            rows[key]=preferred.copy(sourceUnread=(old?.sourceUnread==true || value.sourceUnread))
        }
        val oldHosts=linkedMapOf<String,NativeLegacyReading>()
        for(value in pending?.legacy.orEmpty()+legacy) {
            val key=ThreadReadingIdentity.key(value.target,value.threadId)
            oldHosts[key]=value.copy(sourceUnread=oldHosts[key]?.sourceUnread==true || value.sourceUnread)
        }
        pending=Input(scopes.toList(),rows.values.toList(),oldHosts.values.toList())
        if(worker?.isActive==true)return
        val epoch=generation;busy=true
        worker=tasks.launch {
            try {
                while(pending!=null && epoch==generation) {
                    val input=pending!!;pending=null
                    try {
                        val previousLegacy=legacyPositions.toMap()
                        val (value,positions,resolvedLegacy)=withContext(Dispatchers.IO) {
                            val resolved=mutableMapOf<String,LegacyPosition>()
                            val oldHosts=input.legacy.map { source ->
                                val key=ThreadReadingIdentity.key(source.target,source.threadId)
                                val prior=previousLegacy[key]
                                val position=if(prior?.position?.messageId==source.messageId && prior.position.contentVersion==source.contentVersion)prior.position else
                                    ledger.localPositionVersion(source.target,source.threadId,source.messageId,source.contentVersion)
                                resolved[key]=LegacyPosition(ThreadReadingIdentity.scopeKey(source.target),position)
                                NativeReadingSnapshot(source.target,source.threadId,position,source.sourceUnread)
                            }
                            val snapshots=input.values+oldHosts
                            val value=ledger.reconcile(input.scopes,snapshots)
                            val heads=snapshots.groupBy {
                                ThreadReadingIdentity.key(it.target,it.threadId)
                            }.mapNotNull { (key,rows) -> NativePhoneReadingRules.latest(rows.map { it.latest ?: it.state?.latest })?.let { key to it } }.toMap()
                            Triple(value,heads,resolved)
                        }
                        if(epoch==generation) {
                            publish(value)
                            focusedKey?.let { key -> positions[key]?.let { heads[key]=it } }
                            val allowed=input.scopes.map(ThreadReadingIdentity::scopeKey).toSet()
                            legacyPositions.entries.removeAll { it.value.scope !in allowed }
                            resolvedLegacy.filterValues { it.scope in allowed }.forEach { (key,position) -> legacyPositions.remove(key);legacyPositions[key]=position }
                            while(legacyPositions.size>64)legacyPositions.remove(legacyPositions.keys.first())
                            failure=null;changed()
                        }
                    }catch(cancel: CancellationException) { throw cancel }
                    catch(error: Exception) { if(epoch==generation)failure=error.message ?: error.javaClass.simpleName }
                    catch(_: OutOfMemoryError) { if(epoch==generation)failure="OutOfMemoryError" }
                }
            }finally { if(epoch==generation) { busy=false;worker=null } }
        }
    }
    fun visible(content: NativeVisiblePhoneReading,readerId: String): Job? {
        val ledger=ledger ?: return null;val target=content.client.scope ?: return null
        lifecycle.present(target,content.threadId,content.position)
        val lease=lifecycle.visible(target,content.threadId,content.body.messageId,content.body.text,content.body.status,true) ?: return null
        val threadId=content.threadId;val epoch=generation
        return tasks.launch {
            try {
                val value=withContext(Dispatchers.IO) { ledger.readVisible(target,threadId,readerId,lifecycle,lease) }
                if(value!=null && epoch==generation) { publish(value);failure=null;changed() }
            }catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { if(epoch==generation)failure=error.message ?: error.javaClass.simpleName }
            catch(_: OutOfMemoryError) { if(epoch==generation)failure="OutOfMemoryError" }
        }
    }
    fun reset() {
        generation++;lifecycle.leave();pending=null;worker=null;busy=false;focusedKey=null;heads.clear();legacyPositions.clear()
        snapshot=NativeUnreadSnapshot(0,emptySet(),emptyMap());ready=false;failure=null
    }
}
