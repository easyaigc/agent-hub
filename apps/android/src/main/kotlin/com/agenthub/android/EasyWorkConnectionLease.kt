package com.agenthub.android

import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Pure, revocable capability. It never cancels/retries an authorization or stores a credential. */
internal class EasyWorkConnectionAbandoned: IllegalStateException("连接表单已离开或原目标已改变，原授权记录已保留")
internal data class EasyWorkConnectionInput(val kind: String,val name: String,val endpoint: String,val value: String) {
    val sha256: String get()=MessageDigest.getInstance("SHA-256").digest(listOf(kind,name,endpoint,value).joinToString("") {"${it.length}:$it"}.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
}
internal data class EasyWorkConnectionOrigin(val targetKey: String,val navigation: String,val draftRevision: String,val draftSha256: String)
internal class EasyWorkConnectionAuthority {
    private val lock=Any()
    private var epoch=0L
    private var busy: EasyWorkConnectionActionLease?=null
    fun invalidate()=synchronized(lock) {epoch++}
    fun form(origin: ()->EasyWorkConnectionOrigin)=Form(origin)
    inner class Form(private val origin: ()->EasyWorkConnectionOrigin) {
        val id=UUID.randomUUID().toString()
        private var epoch=0L
        private var attached=true
        private var resumed=true
        fun inputsChanged()=synchronized(lock) {epoch++}
        fun lifecycle(active: Boolean): Boolean=synchronized(lock) {
            if(resumed!=active){resumed=active;epoch++}
            if(!active)release()else false
        }
        fun close(): Boolean=synchronized(lock) {attached=false;epoch++;release()}
        private fun release(): Boolean {if(busy?.form!==this)return false;busy=null;return true}
        fun begin(input: EasyWorkConnectionInput,currentInput: ()->EasyWorkConnectionInput = {input}): EasyWorkConnectionActionLease=synchronized(lock) {
            if(!attached||!resumed||busy!=null)throw EasyWorkConnectionAbandoned()
            epoch++
            EasyWorkConnectionActionLease(this@EasyWorkConnectionAuthority,this,epoch,this@EasyWorkConnectionAuthority.epoch,origin(),input,currentInput).also {busy=it}
        }
        internal fun current(revision: Long)=attached&&resumed&&epoch==revision
        internal fun snapshot()=origin()
    }
    internal fun current(action: EasyWorkConnectionActionLease,completed: Boolean=false): Boolean=synchronized(lock) {
        action.authority===this&&busy===action&&action.form.current(action.formEpoch)&&epoch==(if(completed)action.completionEpoch else action.authorityEpoch)&&
            action.form.snapshot()==(if(completed)action.completionOrigin else action.origin)&&action.currentInput()==action.input&&action.draftRevisionCurrent(completed)
    }
    internal fun <T> checkpoint(action: EasyWorkConnectionActionLease,block: ()->T): T=synchronized(lock) {
        if(action.committed||!current(action))throw EasyWorkConnectionAbandoned()
        block()
    }
    internal fun <T> commit(action: EasyWorkConnectionActionLease,block: ()->T): T=synchronized(lock) {
        checkpoint(action) {
            block().also {action.committed=true;action.completionEpoch=epoch;action.completionOrigin=action.form.snapshot();action.completionDraftRevision=action.readDraftRevision()}
        }
    }
    internal fun finish(action: EasyWorkConnectionActionLease,block: ()->Unit)=synchronized(lock) {
        if(action.committed&&current(action,true)) {block();action.completionEpoch=epoch;action.completionOrigin=action.form.snapshot();action.completionDraftRevision=action.readDraftRevision()}
    }
    internal fun bindDraftRevision(action: EasyWorkConnectionActionLease,read: ()->String)=synchronized(lock) {
        checkpoint(action) {action.bindDraftRevisionOnce(read)}
    }
    internal fun release(action: EasyWorkConnectionActionLease): Boolean=synchronized(lock) {if(busy!==action)false else {busy=null;true}}
}
internal class EasyWorkConnectionActionLease internal constructor(
    internal val authority: EasyWorkConnectionAuthority,internal val form: EasyWorkConnectionAuthority.Form,
    internal val formEpoch: Long,internal val authorityEpoch: Long,internal val origin: EasyWorkConnectionOrigin,
    val input: EasyWorkConnectionInput,internal val currentInput: ()->EasyWorkConnectionInput
): AbstractCoroutineContextElement(Key) {
    val id=UUID.randomUUID().toString()
    companion object Key: CoroutineContext.Key<EasyWorkConnectionActionLease>
    internal var committed=false
    internal var completionEpoch: Long?=null
    internal var completionOrigin: EasyWorkConnectionOrigin?=null
    private var draftRevisionProbe: (()->String)?=null
    private var originalDraftRevision: String?=null
    internal var completionDraftRevision: String?=null
    internal fun readDraftRevision()=draftRevisionProbe?.invoke()
    internal fun draftRevisionCurrent(completed: Boolean)=draftRevisionProbe==null||readDraftRevision()==if(completed)completionDraftRevision else originalDraftRevision
    internal fun bindDraftRevisionOnce(read: ()->String) {if(draftRevisionProbe==null){originalDraftRevision=read();draftRevisionProbe=read}}
    fun bindDraftRevision(read: ()->String)=authority.bindDraftRevision(this,read)
    fun checkpoint()=authority.checkpoint(this) {}
    /** IO never holds the revocation monitor. The credential writer owns exact rollback. */
    fun <T> write(block: ()->T): T {checkpoint();return block().also {checkpoint()}}
    /** The Session calls this around its actual replace/cache/select commit, once only. */
    fun <T> commit(block: ()->T)=authority.commit(this,block)
    fun finish(block: ()->Unit)=authority.finish(this,block)
    fun currentForUi()=authority.current(this,committed)
    fun releaseBusy()=authority.release(this)
}
