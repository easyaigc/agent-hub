package com.agenthub.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** Revision observation prevents a frame between inspecting state and waiting from being lost. */
internal class EasyWorkEventSignal {
    private val changes = MutableStateFlow(0L)
    val revision: Long get() = changes.value
    fun signal() { changes.update { it + 1 } }
    suspend fun awaitChange(observed: Long, timeoutMillis: Long) {
        if (timeoutMillis > 0) withTimeoutOrNull(timeoutMillis) { changes.first { it != observed } }
    }
}
