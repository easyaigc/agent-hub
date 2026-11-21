package com.agenthub.android

/** Budgets use two same-clock intervals; phone wall time is never compared with Host wall time. */
internal object NativeProofClock {
    data class Receipt(val transferredAtHostMs: Long, val requestedAtDeviceElapsedMs: Long) {
        init { require(transferredAtHostMs > 0 && requestedAtDeviceElapsedMs > 0) }

        /** The request precedes Host publication, so this deliberately counts overlap twice. */
        fun hostNowUpperBound(nowDeviceElapsedMs: Long): Long? {
            if (nowDeviceElapsedMs < requestedAtDeviceElapsedMs) return null
            val deviceAge = nowDeviceElapsedMs - requestedAtDeviceElapsedMs
            if (deviceAge > Long.MAX_VALUE - transferredAtHostMs) return null
            return transferredAtHostMs + deviceAge
        }

        fun ageWithin(observedAtHostMs: Long, nowDeviceElapsedMs: Long, limitMs: Long): Long? {
            if (observedAtHostMs <= 0 || limitMs < 0 || transferredAtHostMs < observedAtHostMs ||
                nowDeviceElapsedMs < requestedAtDeviceElapsedMs) return null
            val hostAge = transferredAtHostMs - observedAtHostMs
            if (hostAge > limitMs) return null
            val deviceAge = nowDeviceElapsedMs - requestedAtDeviceElapsedMs
            if (deviceAge > limitMs - hostAge) return null
            return hostAge + deviceAge
        }
    }
}
