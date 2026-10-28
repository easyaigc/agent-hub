package com.agenthub.android

/** JVM-only storage port: exercises shipping journal logic, not Android Keystore/AtomicFile. */
class NativeSessionStorage() {
    constructor(context: android.content.Context, namespace: String) : this()
    internal val coordinationKey = java.util.UUID.randomUUID().toString()
    private val records = mutableMapOf<String, String>()
    fun read(name: String): String? = synchronized(records) { records[name] }
    fun write(name: String, value: String) { synchronized(records) { records[name] = value } }
    internal fun <T> snapshotTransaction(action: () -> T): T = synchronized(records) { action() }
    internal fun <T> operationTransaction(action: () -> T): T = synchronized(records) { action() }
}
