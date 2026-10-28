package com.agenthub.android

/** Caller serializes read/write/rollback with the native storage transaction, never this
 * short ownership monitor. A receipt records the actual prior bytes at write time.
 * Activation is a separate Main-thread capability commit, not a successful disk write.
 */
internal class EasyWorkConnectionWriteGate {
    internal class Receipt(val key: String,val owner: String,val value: String,val previous: String?)
    private val lock=Any()
    private val owners=mutableMapOf<String,Receipt>()
    fun write(key: String,value: String,lease: EasyWorkConnectionActionLease,read: ()->String?,save: (String?)->Unit): Receipt {
        lease.checkpoint()
        val receipt=Receipt(key,lease.id,value,read())
        synchronized(lock) {owners[key]=receipt}
        var attempted=false
        try {lease.write {attempted=true;save(value)};return receipt}
        catch(failure: Exception) {
            try {if(attempted)rollback(receipt,read,save)else release(receipt)}catch(rollback: Exception){failure.addSuppressed(rollback)}
            throw failure
        }
    }
    fun rollback(receipt: Receipt,read: ()->String?,save: (String?)->Unit) {
        if(!synchronized(lock) {owners[receipt.key]===receipt})return
        if(read()==receipt.value)save(receipt.previous)
        synchronized(lock) {if(owners[receipt.key]===receipt)owners.remove(receipt.key)}
    }
    /** No filesystem, crypto, storage transaction or user callback under this lock. */
    fun release(receipt: Receipt): Boolean=synchronized(lock) {
        if(owners[receipt.key]!==receipt)false else {owners.remove(receipt.key);true}
    }
}
