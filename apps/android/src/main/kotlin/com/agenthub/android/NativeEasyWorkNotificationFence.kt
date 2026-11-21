package com.agenthub.android

internal object NativeEasyWorkNotificationFence {
    private val epochs = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    fun invalidate(storage: NativeSessionStorage) { epochs.computeIfAbsent(storage.coordinationKey) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet() }
    fun lease(storage: NativeSessionStorage): () -> Boolean {
        val value = epochs.computeIfAbsent(storage.coordinationKey) { java.util.concurrent.atomic.AtomicLong() }; val epoch = value.get()
        return { value.get() == epoch }
    }
}
