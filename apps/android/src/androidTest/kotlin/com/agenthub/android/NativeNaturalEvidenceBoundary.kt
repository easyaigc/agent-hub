package com.agenthub.android

/** The outer rule observes @Before and body failures and preserves the original if reporting fails. */
internal object NativeNaturalEvidenceBoundary {
    fun evaluate(body: () -> Unit, emit: (Throwable?) -> Unit) {
        var failure: Throwable? = null
        try { body() } catch(error: Throwable) { failure = error }
        try { emit(failure) } catch(error: Throwable) { if(failure == null)failure = error else failure!!.addSuppressed(error) }
        failure?.let { throw it }
    }
}
