package com.agenthub.core

class TimelineMessage(val id: String, val role: String, val text: String, val kind: String?,
    val status: String?, val runId: String?, val replyPhase: String?, val replyMessageId: String?) {
    internal val groupedTool: Boolean get() = role == "tool" &&
        kind !in listOf("contextCompaction", "plan", "planImplementation", "error", "turnFailure")
}
class TimelineRow(val kind: String, val id: String, val indices: List<Int>, val text: String?, val runId: String? = null)
class TimelineProjection(val rows: List<TimelineRow>, val hasEarlierMessages: Boolean)

/** Projects indices without altering source messages, runtime identity or protocol content. */
object MessageProjection {
    fun project(messages: List<TimelineMessage>, messageLimit: Int, activeRunId: String?, running: Boolean): TimelineProjection {
        val groups = linkedMapOf<String, MutableList<Int>>()
        var key = "legacy-start"
        messages.forEachIndexed { index, message ->
            if (!message.runId.isNullOrEmpty()) key = "run-${message.runId}"
            else if (message.role == "user") key = "legacy-${message.id}"
            groups.getOrPut(key) { mutableListOf() }.add(index)
        }
        val replies = mutableMapOf<Int, TimelineRow>()
        for ((group, indices) in groups) {
            val active = group == activeRunId?.let { "run-$it" } ||
                (group == key && (activeRunId == null || group.startsWith("legacy-")))
            if ((running && active) || indices.any { messages[it].status in listOf("inProgress", "running", "streaming") }) continue
            val candidates = indices.map { messages[it] }.filter {
                it.role == "assistant" && (it.kind == null || it.kind == "agentMessage") &&
                    it.replyPhase != "commentary" && MobileRules.hasContent(it.text, 0)
            }
            val last = candidates.lastOrNull() ?: continue
            var parts = candidates.filter { it.replyPhase == "final_answer" }
            if (parts.isEmpty() && last.replyMessageId != null) parts = candidates.filter { it.replyMessageId == last.replyMessageId }
            replies[indices.last()] = TimelineRow("replyActions", (parts.lastOrNull() ?: last).id,
                emptyList(), if (parts.isEmpty()) last.text else parts.joinToString("\n\n") { it.text }, (parts.lastOrNull() ?: last).runId)
        }
        var start = (messages.size - messageLimit.coerceAtLeast(1)).coerceAtLeast(0)
        while (start > 0 && messages[start].groupedTool && messages[start - 1].groupedTool) start--
        val rows = mutableListOf<TimelineRow>()
        val tools = mutableListOf<Int>()
        val errors = mutableListOf<Int>()
        fun flush(indices: MutableList<Int>, kind: String) {
            if (indices.isNotEmpty()) {
                rows.add(TimelineRow(kind, messages[indices.first()].id, indices.toList(), null))
                indices.clear()
            }
        }
        for (index in start until messages.size) {
            val message = messages[index]
            if (message.kind != "error") flush(errors, "errors")
            if (message.groupedTool) tools.add(index) else {
                flush(tools, "tools")
                if (message.kind == "error") errors.add(index)
                else rows.add(TimelineRow(if (message.kind == "contextCompaction") "compaction" else "message", message.id, listOf(index), null))
            }
            replies[index]?.let { flush(tools, "tools"); flush(errors, "errors"); rows.add(it) }
        }
        flush(tools, "tools")
        flush(errors, "errors")
        return TimelineProjection(rows, start > 0)
    }
}
