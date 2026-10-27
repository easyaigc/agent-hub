package com.agenthub.android

import androidx.compose.runtime.*
import com.agenthub.core.DesktopSuggestionRules
import com.agenthub.core.SuggestionScope
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.math.ceil

/** Match original iOS single-flight/fallback ownership; no transport writes. */
internal class NativeSuggestionReadAhead(private val tasks: CoroutineScope,
    private val now: ()->Double={ System.currentTimeMillis().toDouble() }) {
    private class Pending(val scope: SuggestionScope,val allowsFallback: Boolean) {
        val id=UUID.randomUUID().toString()
        lateinit var task: Deferred<MobileDesktopSuggestionResponse>
        var fallback: Deferred<MobileDesktopSuggestionResponse>?=null
        var received: MobileDesktopSuggestionResponse?=null
        val consumers=mutableMapOf<String,Job>()
        var wasRead=false
        val abandoned get()=wasRead&&consumers.values.none { it.isActive }
    }
    private var pending: Pending?=null
    private fun same(a: SuggestionScope?,b: SuggestionScope?)=DesktopSuggestionRules.sameScope(a,b)
    private fun current(owner: Pending)=pending===owner&&!owner.abandoned
    private fun expired(owner: Pending): Boolean {
        val response=owner.received ?: return false
        if(!response.matches(owner.scope))return false
        val value=response.suggestion ?: return false
        val expiry=value.expirationMillis ?: return false
        return DesktopSuggestionRules.refreshBound(value.validity)&&expiry<=now()&&value.isValid(expiry-1_000)
    }
    private fun cancelAbandoned(owner: Pending) {
        if(owner.abandoned) { owner.task.cancel();owner.fallback?.cancel() }
    }
    fun cancel(navigation: String?=null) {
        if(navigation!=null&&pending?.scope?.navigation!=navigation)return
        pending?.let { it.task.cancel();it.fallback?.cancel() };pending=null
    }
    private fun makeTask(owner: Pending,onReady: ((SuggestionScope,MobileDesktopSuggestionResponse)->Unit)?,
        fetch: suspend ()->MobileDesktopSuggestionResponse)=tasks.async(start=CoroutineStart.LAZY) {
        ensureActive();if(!current(owner))throw CancellationException("Suggestion request replaced")
        val response=fetch();ensureActive()
        if(!current(owner))throw CancellationException("Suggestion request replaced")
        owner.received=response;onReady?.invoke(owner.scope,response);response
    }
    private fun start(scope: SuggestionScope,allowsFallback: Boolean,
        onReady: ((SuggestionScope,MobileDesktopSuggestionResponse)->Unit)?=null,
        fetch: suspend ()->MobileDesktopSuggestionResponse): Pending {
        val owner=Pending(scope,allowsFallback);pending=owner
        owner.task=makeTask(owner,onReady,fetch);owner.task.start();return owner
    }
    fun prepare(scope: SuggestionScope?,onReady: (SuggestionScope,MobileDesktopSuggestionResponse)->Unit,
        fetch: suspend ()->MobileDesktopSuggestionResponse) {
        val current=pending
        if(scope!=null&&current!=null&&same(scope,current.scope)) {
            cancelAbandoned(current)
            if(!current.abandoned&&(current.consumers.isNotEmpty()||!expired(current)))return
        }
        cancel();if(scope!=null)start(scope,true,onReady,fetch)
    }
    fun completed(scope: SuggestionScope): MobileDesktopSuggestionResponse? = pending?.takeIf {
        same(it.scope,scope)&&!it.abandoned&&!expired(it)
    }?.received
    private suspend fun await(task: Deferred<MobileDesktopSuggestionResponse>,owner: Pending): MobileDesktopSuggestionResponse {
        val response=try { task.await() }catch(failure: Exception) {
            currentCoroutineContext().ensureActive()
            if(failure is CancellationException||!current(owner))throw CancellationException("Suggestion request replaced")
            throw failure
        }
        currentCoroutineContext().ensureActive()
        if(task.isCancelled||!current(owner))throw CancellationException("Suggestion request replaced")
        return response
    }
    suspend fun read(scope: SuggestionScope,fetch: suspend ()->MobileDesktopSuggestionResponse): MobileDesktopSuggestionResponse {
        currentCoroutineContext().ensureActive()
        val existing=pending
        val owner=if(existing!=null&&!existing.abandoned&&same(existing.scope,scope)&&
            (existing.consumers.isNotEmpty()||!expired(existing))) {
            existing
        }else { cancel();start(scope,false,fetch=fetch) }
        val consumer=UUID.randomUUID().toString();owner.wasRead=true
        owner.consumers[consumer]=currentCoroutineContext().job
        try {
            return try { await(owner.task,owner) }catch(failure: Exception) {
                currentCoroutineContext().ensureActive()
                if(failure is CancellationException||failure is DesktopSuggestionSourceMismatch||!current(owner))throw failure
                if(!owner.allowsFallback)throw failure
                val fallback=owner.fallback ?: makeTask(owner,null,fetch).also { owner.fallback=it;it.start() }
                await(fallback,owner)
            }
        }finally {
            owner.consumers.remove(consumer);cancelAbandoned(owner)
            if(pending===owner&&owner.consumers.isEmpty())pending=null
        }
    }
}

/** Native state/clock/jobs; pure source and validity policy stays in MobileCore. */
internal class NativeDesktopSuggestionReader(private val tasks: CoroutineScope,
    private val now: ()->Double={ System.currentTimeMillis().toDouble() }) {
    var suggestion by mutableStateOf<MobileDesktopSuggestion?>(null,neverEqualPolicy());private set
    var scope by mutableStateOf<SuggestionScope?>(null);private set
    private var generation=UUID.randomUUID().toString()
    private var refreshing: String?=null
    private var loop: Job?=null
    private var expiration: Job?=null
    private var enabledContext=false
    private fun publish(value: MobileDesktopSuggestion?) { if(value!=null||suggestion!=null)suggestion=value }
    private fun same(a: SuggestionScope?,b: SuggestionScope?)=DesktopSuggestionRules.sameScope(a,b)
    private fun current(expected: SuggestionScope,owner: String)=generation==owner&&same(scope,expected)
    fun clear() {
        generation=UUID.randomUUID().toString();loop?.cancel();loop=null;expiration?.cancel();expiration=null
        refreshing=null;enabledContext=false;scope=null;publish(null)
    }
    private fun expire(value: MobileDesktopSuggestion,expected: SuggestionScope,owner: String) {
        expiration?.cancel();expiration=null
        val expiry=value.expirationMillis ?: return
        expiration=tasks.launch {
            delay(ceil(expiry-now()).coerceAtLeast(0.0).toLong());ensureActive()
            if(current(expected,owner)&&suggestion?.expirationMillis==expiry)publish(null)
        }
    }
    fun suspendKeeping(expected: SuggestionScope?) {
        if(expected==null||!same(expected,scope)) { clear();return }
        generation=UUID.randomUUID().toString();loop?.cancel();loop=null;refreshing=null;enabledContext=false
        expiration?.cancel();expiration=null
        val value=suggestion?.takeIf { it.isValid(now()) }
        publish(value)
        if(value!=null&&! (value.validity=="source-turn"))expire(value,expected,generation)
    }
    fun visible(expected: SuggestionScope?): MobileDesktopSuggestion? =
        suggestion?.takeIf { expected!=null&&same(expected,scope)&&it.isValid(now()) }
    fun seed(response: MobileDesktopSuggestionResponse,expected: SuggestionScope) {
        val value=response.suggestion ?: return
        if(!response.matches(expected)||!value.isValid(now()))return
        if(same(scope,expected)&&refreshing==generation)return
        scope=expected;publish(value);suspendKeeping(expected)
    }
    fun reconcile(expected: SuggestionScope?,enabled: Boolean,fetch: suspend ()->MobileDesktopSuggestionResponse) {
        if(expected==null) { clear();return }
        if(!same(scope,expected)) { clear();scope=expected }
        if(!enabled) { if(enabledContext)suspendKeeping(expected);return }
        // Invalid source/hash ends this context, as on iOS; a recompose cannot restart it.
        if(enabledContext)return
        enabledContext=true
        generation=UUID.randomUUID().toString();val owner=generation;refreshing=owner
        loop=tasks.launch(start=CoroutineStart.LAZY) {
            try {
                while(currentCoroutineContext().isActive&&current(expected,owner)) {
                    try {
                        val response=fetch();ensureActive();if(!current(expected,owner))return@launch
                        if(!response.matches(expected)) { publish(null);return@launch }
                        val value=response.suggestion
                        if(value!=null&&(!DesktopSuggestionRules.refreshBound(value.validity)||!value.isValid(now())||value.expirationMillis==null)) {
                            publish(null);return@launch
                        }
                        expiration?.cancel();expiration=null;publish(value)
                        if(value!=null)expire(value,expected,owner)
                    }catch(cancel: CancellationException) {
                        // A cancelled shared provider is not cancellation of this
                        // still-current reader. Owning cancellation still exits.
                        currentCoroutineContext().ensureActive()
                        if(!current(expected,owner))return@launch
                        publish(null)
                    }
                     catch(failure: Exception) {
                        if(!current(expected,owner))return@launch
                        publish(null)
                        if(failure is DesktopSuggestionSourceMismatch)return@launch
                    }
                    delay(2_000)
                }
            }finally { if(refreshing==owner)refreshing=null }
        }.also { it.start() }
    }
}
