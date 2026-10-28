package com.agenthub.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Shared named-workspace scan policy. A safety stop is incomplete, never proof of absence. */
internal object NativeWorkspaceThreadDirectory {
    const val MAX_PAGES = 1024
    const val MAX_THREADS = 16_384
    const val MAX_CURSOR = 16_384
    class Incomplete(val reason: String) : IllegalArgumentException("Workspace directory incomplete: $reason")
    suspend fun checkCurrent(current: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!current()) throw CancellationException("Workspace directory owner changed")
    }
    /** Returns null only when the complete directory ended, or the visitor deliberately found no value. */
    suspend fun <T : Any> scan(client: MobileClient, first: MobileWorkspace? = null, current: () -> Boolean,
        fetch: suspend (String?) -> MobileWorkspace, visit: suspend (MobileWorkspace, Int) -> T?): T? {
        val target = client.scope ?: throw Incomplete("missing-target")
        if (target.hostId != client.id) throw Incomplete("foreign-target")
        val cursors = mutableSetOf<String>(); val ids = mutableSetOf<String>()
        var cached = first; var cursor: String? = null; var pages = 0
        while (true) {
            checkCurrent(current)
            if (pages == MAX_PAGES) throw Incomplete("page-limit")
            val page = cached ?: fetch(cursor); cached = null
            checkCurrent(current)
            if (page.hostId != client.id || page.scope == null || !sameScope(page.scope, target) ||
                page.projects.any { it.hostId != client.id }) throw Incomplete("foreign-target")
            for ((index, thread) in page.threads.withIndex()) {
                if (index > 0 && index % 32 == 0) { checkCurrent(current); yield() }
                if (thread.hostId != client.id) throw Incomplete("foreign-target")
                ids.add(thread.id)
                if (ids.size > MAX_THREADS) throw Incomplete("thread-limit")
            }
            checkCurrent(current)
            visit(page, ++pages)?.let { checkCurrent(current); return it }
            cursor = page.nextThreadCursor ?: return null
            if (cursor.isEmpty() || cursor.length > MAX_CURSOR || !cursors.add(cursor)) throw Incomplete("invalid-cursor")
            yield()
        }
    }
}
