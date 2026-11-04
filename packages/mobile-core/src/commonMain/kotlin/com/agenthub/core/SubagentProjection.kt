package com.agenthub.core

import kotlinx.serialization.json.*

class SubagentActivity(val id: String, val title: String, val status: String, val detail: String, val rootRunId: String)

/** Session-local observation only. No commands, replay of input, or execution ownership. */
class SubagentProjection {
    private data class Live(val root: String, val revision: JsonElement?, var title: String, var status: String)
    private val threads = linkedMapOf<String,LinkedHashMap<String,Live>>()
    private val sequences = mutableMapOf<String,Long>()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    fun clear() { threads.clear(); sequences.clear() }
    fun accept(eventJson: String) {
        val event = runCatching { Json.parseToJsonElement(eventJson).jsonObject }.getOrNull() ?: return
        val envelope = event.obj("payload"); val legacy = envelope.obj("event")
        if(legacy.text("type") != "subagent_run_event") return
        val thread = event.text("threadId"); val seq = (event["threadSeq"] as? JsonPrimitive)?.longOrNull ?: return
        if(thread.isEmpty() || seq <= (sequences[thread] ?: -1)) return
        val data = legacy.obj("event"); val id = data.text("childRunId"); val root = data.text("rootRunId")
        if(id.isEmpty() || root.isEmpty()) return
        sequences[thread] = seq
        val runs = threads.getOrPut(thread) { linkedMapOf() }
        while(threads.size > 4) { val old=threads.keys.first(); threads.remove(old); sequences.remove(old) }
        val live = runs.getOrPut(id) { Live(root,envelope["branchRevision"],data.text("title").ifEmpty { data.text("subAgentId") },"unknown") }
        if(live.root != root) return
        if(data.text("title").isNotEmpty())live.title=data.text("title")
        val terminal = live.status in listOf("completed", "failed", "interrupted")
        when(data.text("phase")) {
            "started", "stream" -> if(!terminal)live.status="inProgress"
            "pending" -> if(!terminal)live.status="pending"
            "paused" -> if(!terminal)live.status="paused"
            "completed" -> live.status="completed"
            "failed" -> live.status="failed"
            "cancelled" -> live.status="interrupted"
        }
        // Host delivery coalesces item/delta frames. A received text_delta is
        // therefore not a contiguous text stream. Events wake the normal history
        // read; only the authenticated checkpoint/result supplies output bytes.
        while(runs.size>64)runs.remove(runs.keys.first())
    }
    private fun status(value: String): String = when(value) {
        "running", "in_progress", "starting" -> "inProgress"
        "completed" -> "completed"
        "failed" -> "failed"
        "cancelled", "interrupted" -> "interrupted"
        "pending", "paused" -> value
        else -> "unknown"
    }
    private fun content(parts: JsonArray): String = parts.mapNotNull { raw ->
        val p=raw as? JsonObject ?: return@mapNotNull null
        when(p.text("type")) {
            "text" -> p.text("text")
            "tool-call" -> listOf(p.text("toolName"),p.obj("result").text("text")).filter { it.isNotEmpty() }.joinToString("\n")
            else -> null
        }
    }.filter { it.isNotEmpty() }.joinToString("\n\n").takeLast(65536)
    fun project(snapshotJson: String, threadId: String): List<SubagentActivity> {
        val snapshot=runCatching { Json.parseToJsonElement(snapshotJson).jsonObject }.getOrNull() ?: return emptyList()
        val thread=snapshot.obj("thread"); if(thread.text("id")!=threadId)return emptyList()
        val revision=thread["branchRevision"]
        val activities=linkedMapOf<String,SubagentActivity>(); val roots=mutableSetOf<String>()
        val active=snapshot.obj("activeRun"); listOf(active.text("runId"),active.text("id")).filter { it.isNotEmpty() }.forEach { roots.add(it) }
        for(raw in snapshot.array("subagentRunTrees")) {
            val tree=raw as? JsonObject ?: continue
            val root=tree.obj("root"); val rootId=root.text("id")
            if(rootId.isEmpty() || root.text("threadId")!=threadId || (revision!=null && root["branchRevision"]!=revision))continue
            roots.add(rootId)
            for(entry in tree.array("runs")) {
                val run=entry as? JsonObject ?: continue
                if(run.text("kind")!="child" || run.text("threadId")!=threadId || run.text("rootRunId")!=rootId || (revision!=null && run["branchRevision"]!=revision))continue
                val id=run.text("id"); if(id.isEmpty())continue
                val input=run.obj("input"); val result=run.obj("result")
                val parts=(result["content"] as? JsonArray) ?: run.obj("checkpoint").obj("subagentTranscript").array("content")
                val body=content(parts); val error=run.obj("error").text("message")
                val title=input.text("title").ifEmpty { run.text("subAgentId") }.ifEmpty { id }
                activities[id]=SubagentActivity("subagent:$id",title,status(run.text("status")),listOf(input.text("task"),body,error).filter { it.isNotEmpty() }.joinToString("\n\n").takeLast(65536),rootId)
            }
        }
        for((id,live) in threads[threadId].orEmpty()) {
            if(live.root !in roots || (revision!=null && live.revision!=revision))continue
            val old=activities[id]
            activities[id]=SubagentActivity("subagent:$id",old?.title ?: live.title.ifEmpty { id },old?.status ?: live.status,old?.detail.orEmpty(),live.root)
        }
        return activities.values.toList()
    }
}
