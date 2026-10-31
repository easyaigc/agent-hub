package com.agenthub.android

/** The native key lookup decides existing-vs-create once; captures the exact returned object. */
internal object NativeMigrationKeyChoice {
    fun <K: Any> resolve(found: K?,existingOnly: Boolean,validate: ()->Unit={},capture: (K)->Unit={},create: ()->K): K {
        check(!existingOnly||found!=null){"Existing installation key required; no fallback generation"}
        validate()
        val key=found?:create()
        capture(key)
        validate()
        return key
    }
}
