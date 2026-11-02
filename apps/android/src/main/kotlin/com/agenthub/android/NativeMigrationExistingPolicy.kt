package com.agenthub.android

import java.util.UUID

/** Exact old writer -> released lease -> prepared new Session -> normal runtime. No transferable bool. */
internal class NativeMigrationExistingPolicy(val storage: Any,val originalGeneration: String?) {
    private enum class Phase { INITIAL,FROZEN,PREPARED,RELEASED,BOUND,ACTIVE,REVOKED }
    @Volatile private var phase=Phase.INITIAL
    private var operation: String?=null
    private var owner: Any?=null
    private var actualLease: Any?=null
    private var oldLive: (()->Unit)?=null
    private var frozen: (()->Unit)?=null
    private var oldPending: (()->Unit)?=null
    private var created: String?=null
    private var confirmed: String?=null
    private var ticket: Restore?=null
    class Restore internal constructor(internal val policy: NativeMigrationExistingPolicy,internal val lease: Any,
        internal val operation: String,val generation: String) {
        internal var root: Any?=null
        internal var session: Any?=null
        internal var live: (()->Unit)?=null
        internal var pending: (()->Unit)?=null
        internal val prepared=mutableSetOf<String>()
    }
    companion object {
        val paths=setOf("external-inbox","recent-photos","photo-permission","document-results","photo-results","camera-results",
            "document-results/payloads","photo-results/payloads","camera-results/payloads","camera-results/outputs")
    }
    fun bindSession(access: Any,root: Any,session: Any,requireLive: ()->Unit,requireUnchanged: ()->Unit=requireLive) {
        if(access===storage) {
            check(root===storage&&phase==Phase.INITIAL&&owner==null)
            owner=session;oldLive=requireLive
        } else {
            val r=exact(access);check(phase==Phase.RELEASED&&r.root===root&&r.session==null&&session!==owner)
            oldPending!!();r.session=session;r.live=requireLive;r.pending=requireUnchanged;phase=Phase.BOUND
        }
    }
    fun authorize(actualStorage: Any,actualOwner: Any,lease: Any,operationId: String,requireActualRawOwner: ()->Unit,requireActualSession: ()->Unit=requireActualRawOwner) {
        check(actualStorage===storage&&owner===actualOwner&&phase==Phase.INITIAL)
        require(UUID.fromString(operationId).toString()==operationId)
        oldLive!!();requireActualRawOwner()
        check(phase==Phase.INITIAL);operation=operationId;actualLease=lease;frozen=requireActualRawOwner;oldPending=requireActualSession;phase=Phase.FROZEN
    }
    private fun requireFrozen() {check(phase==Phase.FROZEN);frozen!!();check(phase==Phase.FROZEN)}
    fun beforeGeneration(actualStorage: Any,generation: String) {
        check(actualStorage===storage);require(UUID.fromString(generation).toString()==generation)
        check(generation!=originalGeneration&&created==null);requireFrozen();created=generation
    }
    fun publication(actualStorage: Any,generation: String) {
        check(actualStorage===storage&&created==generation);requireFrozen()
    }
    fun confirmPublication(actualStorage: Any,generation: String) {publication(actualStorage,generation);confirmed=generation}
    /** Storage calls only after authenticating its exact original pointer intent on IO.
     * This is a same-owner confirmation, never a cold-start or transferable grant. */
    fun confirmCommittedPublicationForRestore(actualStorage: Any,lease: Any,operationId: String,generation: String) {
        check(actualStorage===storage&&lease===actualLease&&operationId==operation&&created==generation)
        if(confirmed==generation)return
        check(confirmed==null&&ticket==null)
        requireFrozen();oldPending!!()
        confirmPublication(actualStorage,generation)
    }
    fun prepareRestore(actualStorage: Any,lease: Any,operationId: String,generation: String): Restore {
        check(actualStorage===storage&&lease===actualLease&&operationId==operation&&created==generation&&confirmed==generation)
        ticket?.let {check(phase in setOf(Phase.PREPARED,Phase.RELEASED,Phase.BOUND,Phase.ACTIVE));return it}
        requireFrozen();oldPending!!()
        return Restore(this,lease,operationId,generation).also {ticket=it;phase=Phase.PREPARED}
    }
    private fun exact(access: Any): Restore = requireNotNull(ticket).also {check(access===it&&it.policy===this)}
    fun released(r: Restore,lease: Any) {check(exact(r)===r&&r.lease===lease&&phase==Phase.PREPARED);oldPending!!();phase=Phase.RELEASED}
    fun attachRoot(r: Restore,root: Any) {check(exact(r)===r&&phase==Phase.RELEASED&&r.root==null);oldPending!!();r.root=root}
    private fun prepared(r: Restore) {
        check(phase==Phase.BOUND&&r.root!=null&&r.session!=null);oldPending!!();r.pending!!();check(phase==Phase.BOUND)
    }
    fun requireHandle(access: Any) {
        if(access===storage){check(phase in setOf(Phase.INITIAL,Phase.FROZEN));oldLive!!()}
        else {val r=exact(access);if(phase==Phase.ACTIVE){r.live!!();check(phase==Phase.ACTIVE)}else prepared(r)}
    }
    fun requireWrite(access: Any,path: String?,name: String) {
        if(access===storage) {
            if(phase==Phase.INITIAL){check(path==null&&name=="metadata");oldLive!!();check(phase==Phase.INITIAL)}
            else requireFrozen()
        } else {val r=exact(access);check(phase==Phase.ACTIVE);r.live!!();check(phase==Phase.ACTIVE)}
    }
    fun requireNewChild(actualStorage: Any,access: Any,generation: String,path: String) {
        check(actualStorage===storage&&created==generation)
        if(access===storage) {check(path in paths);requireFrozen()}
        else {val r=exact(access);check(r.generation==generation&&path in paths+"migration-candidates")
            if(phase==Phase.ACTIVE){r.live!!();check(phase==Phase.ACTIVE)}else {check(path in paths);prepared(r)}}
    }
    fun childPrepared(access: Any,path: String) {if(access!==storage){val r=exact(access);if(phase==Phase.BOUND){prepared(r);check(path in paths);r.prepared.add(path)}}}
    /** Exact pending Session may decode before publication; this grants no writes. */
    fun requireBoundSession(access: Any,root: Any,session: Any,lease: Any) {
        val r=exact(access);prepared(r)
        check(r.root===root&&r.session===session&&r.lease===lease&&r.prepared.containsAll(paths))
    }
    fun verifyPrepared(access: Any,root: Any,lease: Any,generation: String) {
        val r=exact(access);prepared(r);check(r.root===root&&r.lease===lease&&r.generation==generation&&r.prepared.containsAll(paths))
    }
    fun complete(access: Any,root: Any,session: Any,lease: Any) {
        val r=exact(access);prepared(r);check(r.root===root&&r.session===session&&r.lease===lease&&r.prepared.containsAll(paths));phase=Phase.ACTIVE
    }
    fun revoke(session: Any) {
        val r=ticket
        if(phase==Phase.ACTIVE){if(r?.session===session)phase=Phase.REVOKED}
        else if(owner===session||r?.session===session)phase=Phase.REVOKED
    }
    fun invalidate(session: Any) {if(phase!=Phase.ACTIVE)revoke(session)}
}
