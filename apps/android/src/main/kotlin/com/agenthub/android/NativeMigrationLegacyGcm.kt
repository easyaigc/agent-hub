package com.agenthub.android

/** Inventory facts only. Length/version bytes are not authentication or authority to publish data. */
internal object NativeMigrationLegacyGcm {
    internal data class Limit(
        val observedFormat: Int,
        val ciphertextBytes: Long,
        val unauthenticatedPlaintextBytes: Long,
        val maximumRecordPlaintextBytes: Long,
        val auditedAospMinimumBufferAndResultBytes: Long,
        val heapLimitBytes: Long,
        val authenticationAttempted: Boolean = false
    )
    fun limit(observedFormat: Int, ciphertextBytes: Long, maximumTextBytes: Int, heapLimitBytes: Long): Limit? {
        require(maximumTextBytes in 1..NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES && heapLimitBytes > 0)
        if (observedFormat !in listOf(1, 3) || ciphertextBytes < 29) return null
        // Both historical formats have one version byte, 12-byte nonce and 16-byte tag.
        // v3's logical-name frame is inside that same indivisible GCM message.
        val clear = ciphertextBytes - 29
        val maximum = maximumTextBytes + 65540L // Preserve the existing text + maximum name-frame bound.
        if (clear <= maximum) return null
        // AOSP Keystore's authenticated decrypt buffers output then makes a result copy.
        // This is only a lower bound: capacity growth, input, Strings and the app add more.
        return Limit(observedFormat, ciphertextBytes, clear, maximum, Math.multiplyExact(clear, 2L), heapLimitBytes)
    }
}
