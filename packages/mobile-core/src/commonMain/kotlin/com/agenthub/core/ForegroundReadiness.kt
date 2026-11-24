package com.agenthub.core

/** In-memory evidence only. Lifecycle and cached online flags cannot mint control proofs.
 * Native owners supply a complete target/credential scope and capture revision before I/O.
 * Workspace and thread proofs deliberately do not imply one another.
 */
class ForegroundReadiness {
    var revision: Long = 0
        private set
    private val workspaces = mutableSetOf<String>()
    private val threads = mutableMapOf<String, MutableSet<String>>()
    private var allInvalidatedAt: Long = 0
    private val scopeInvalidatedAt = mutableMapOf<String, Long>()

    fun invalidate() {
        revision++
        allInvalidatedAt = revision
        scopeInvalidatedAt.clear()
        workspaces.clear()
        threads.clear()
    }

    fun invalidateScope(scope: String) {
        revision++
        scopeInvalidatedAt[scope] = revision
        workspaces.remove(scope)
        threads.remove(scope)
    }

    fun confirmWorkspace(scope: String, observedRevision: Long): Boolean {
        if (!accepts(scope, observedRevision)) return false
        return workspaces.add(scope)
    }

    fun confirmThread(scope: String, threadId: String, observedRevision: Long): Boolean {
        if (threadId.isEmpty() || !accepts(scope, observedRevision)) return false
        return threads.getOrPut(scope) { mutableSetOf() }.add(threadId)
    }

    fun workspaceReady(scope: String): Boolean = scope in workspaces
    fun threadReady(scope: String, threadId: String): Boolean = threads[scope]?.contains(threadId) == true

    private fun accepts(scope: String, observed: Long): Boolean = scope.isNotEmpty() &&
        observed >= allInvalidatedAt && observed >= (scopeInvalidatedAt[scope] ?: 0) && observed <= revision
}
