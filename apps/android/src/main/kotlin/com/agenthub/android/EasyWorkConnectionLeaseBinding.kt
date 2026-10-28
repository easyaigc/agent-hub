package com.agenthub.android

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

private val easyWorkConnectionAuthorities=WeakHashMap<MobileSession,EasyWorkConnectionAuthority>()
internal fun easyWorkConnectionAuthority(session: MobileSession): EasyWorkConnectionAuthority=synchronized(easyWorkConnectionAuthorities) {
    easyWorkConnectionAuthorities.getOrPut(session) {EasyWorkConnectionAuthority()}
}
/** A draft's full text is hashed once per revision, not on every network checkpoint. */
internal fun easyWorkConnectionForm(session: MobileSession): EasyWorkConnectionAuthority.Form {
    var previous: Pair<String,String>?=null
    var digest=""
    return easyWorkConnectionAuthority(session).form {
        val draft=session.suggestionDraft;val key=draft.targetKey to draft.revision
        if(previous!=key){digest=easyHash(draft.text.toByteArray(Charsets.UTF_8));previous=key}
        EasyWorkConnectionOrigin(session.targetKey,session.suggestionNavigation,draft.revision,digest)
    }
}
/** A nested QR-to-code route keeps the original submitted QR input and capability. */
internal suspend fun easyWorkConnectionAction(session: MobileSession,input: EasyWorkConnectionInput): EasyWorkConnectionActionLease {
    val current=currentCoroutineContext()[EasyWorkConnectionActionLease]
    if(current!=null) {
        if(current.authority!==easyWorkConnectionAuthority(session))throw EasyWorkConnectionAbandoned()
        val derived=current.input.kind=="qr"&&input.kind=="code"&&current.input.name==input.name&&runCatching {
            val offer=EasyWorkDeviceOffer.parse(current.input.value)
            offer.origin==EasyWorkDeviceOffer.origin(input.endpoint)&&offer.code==EasyWorkDeviceOffer.code(input.value)
        }.getOrDefault(false)
        if(current.input!=input&&!derived)throw EasyWorkConnectionAbandoned()
        current.checkpoint();return current
    }
    return easyWorkConnectionForm(session).begin(input)
}

internal suspend fun <T> withEasyWorkConnectionAction(session: MobileSession,input: EasyWorkConnectionInput,block: suspend (EasyWorkConnectionActionLease)->T): T {
    val standalone=currentCoroutineContext()[EasyWorkConnectionActionLease]==null
    val lease=easyWorkConnectionAction(session,input)
    try {return withContext(lease) {block(lease)}}
    catch(cancel: CancellationException) {throw cancel}
    catch(failure: Exception) {if(!lease.committed)try {lease.checkpoint()}catch(abandoned: EasyWorkConnectionAbandoned){abandoned.initCause(failure);throw abandoned};throw failure}
    finally {if(standalone)lease.releaseBusy()}
}
/** In-process ownership prevents an obsolete rollback from overwriting a newer identical write.
 * Only a credential mapping is guarded here; original grant/claim/unknown receipts are untouched.
 */
internal object EasyWorkConnectionCredentialWrites {
    private val gate=EasyWorkConnectionWriteGate()
    private data class Owner(val storage: NativeSessionStorage,val ref: String,val leaseId: String)
    private val receipts=ConcurrentHashMap<Owner,EasyWorkConnectionWriteGate.Receipt>()
    private fun owner(storage: NativeSessionStorage,ref: String,lease: EasyWorkConnectionActionLease)=Owner(storage,ref,lease.id)
    fun write(storage: NativeSessionStorage,ref: String,value: String,lease: EasyWorkConnectionActionLease) {
        lease.checkpoint()
        storage.operationTransaction {
            val receipt=gate.write(storage.coordinationKey+"\n"+ref,value,lease,{storage.read(ref)}) {prior->if(prior==null)storage.remove(ref)else storage.write(ref,prior)}
            receipts[owner(storage,ref,lease)]=receipt
        }
    }
    // previous is retained for the existing native probe call contract. The receipt,
    // captured inside the actual transaction, owns the authoritative rollback bytes.
    @Suppress("UNUSED_PARAMETER")
    fun rollback(storage: NativeSessionStorage,ref: String,value: String,previous: String?,lease: EasyWorkConnectionActionLease)=storage.operationTransaction {
        receipts.remove(owner(storage,ref,lease))?.let {receipt->
            check(receipt.value==value)
            gate.rollback(receipt,{storage.read(ref)}) {prior->if(prior==null)storage.remove(ref)else storage.write(ref,prior)}
        }
    }
    @Suppress("UNUSED_PARAMETER")
    fun committed(storage: NativeSessionStorage,ref: String,lease: EasyWorkConnectionActionLease) {
        // Lookup uses only the action/ref string; even canonicalPath is kept off Main.
        val receipt=receipts.remove(owner(storage,ref,lease))?:throw EasyWorkConnectionAbandoned()
        if(!gate.release(receipt))throw EasyWorkConnectionAbandoned()
    }
}
