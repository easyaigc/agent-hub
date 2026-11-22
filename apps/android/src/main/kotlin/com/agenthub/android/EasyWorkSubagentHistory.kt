package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Reads only authenticated run-tree lineage; ordinary message text grants no child access. */
internal object EasyWorkSubagentHistory {
    data class Selection(val id: String,val root: String,val revision: Long,val run: JSONObject)
    private fun revision(value: Any?): Long {
        require(value is Number && value.toDouble().isFinite() && value.toDouble()==value.toLong().toDouble() && value.toLong() in 0..9_007_199_254_740_991L)
        return value.toLong()
    }
    fun select(page: JSONObject,request: MobileSubagentHistoryRequest): Selection {
        require(request.association==null && request.messageId.startsWith("subagent:"))
        val snapshot=page.getJSONObject("snapshot");val thread=snapshot.getJSONObject("thread")
        require(thread.getString("id")==request.parentThreadId)
        val rev=revision(thread.opt("branchRevision")?:page.get("branchRevision"))
        require(!page.has("branchRevision") || revision(page.get("branchRevision"))==rev)
        val id=request.messageId.removePrefix("subagent:");require(id.isNotEmpty() && (request.childId==null || request.childId==id))
        val matches=snapshot.optJSONArray("subagentRunTrees")?.objects().orEmpty().flatMap { tree ->
            val root=tree.getJSONObject("root")
            if(root.optString("threadId")!=request.parentThreadId || revision(root.opt("branchRevision"))!=rev || root.optString("id").isEmpty())emptyList()
            else tree.getJSONArray("runs").objects().filter {
                it.optString("id")==id && it.optString("kind")=="child" && it.optString("threadId")==request.parentThreadId &&
                    it.optString("rootRunId")==root.getString("id") && revision(it.opt("branchRevision"))==rev
            }.map { Selection(id,root.getString("id"),rev,it) }
        }
        return matches.single()
    }
    fun originals(selected: Selection): List<JSONObject> {
        val run=selected.run;val transcript=run.optJSONObject("checkpoint")?.optJSONObject("subagentTranscript")
        val messages=if(transcript?.has("messages")==true && !transcript.isNull("messages"))transcript.getJSONArray("messages").objects().toMutableList()else mutableListOf()
        if(messages.isEmpty()) {
            // Real terminal persistence may contain an empty content array while the
            // durable checkpoint still holds the last complete tool/text transcript.
            val parts=run.optJSONObject("result")?.optJSONArray("content")?.takeIf { it.length()>0 }
                ?:transcript?.optJSONArray("content")
            if(parts!=null && parts.length()>0)messages.add(JSONObject().put("id",selected.id+":transcript").put("role","assistant").put("content",parts))
        }
        run.optJSONObject("input")?.optional("task")?.takeIf { it.isNotEmpty() }?.let {
            messages.add(0,JSONObject().put("id",selected.id+":input").put("role","user").put("content",JSONArray().put(JSONObject().put("type","text").put("text",it))))
        }
        return messages.mapIndexed { index,original -> JSONObject(original.toString()).apply {
            if(!has("id"))put("id",selected.id+":message:$index")
            if(!has("role"))put("role","assistant")
            if(!has("sourceRunId"))put("sourceRunId",selected.id)
            if(!has("status"))put("status",run.optional("status"))
        } }
    }
    data class Window(val messages: List<MobileMessage>,val next: String?)
    fun page(messages: List<MobileMessage>,scope: String,cursor: String?): Window {
        require(messages.map { it.id }.distinct().size==messages.size)
        val end=if(cursor==null)messages.size else {
            require(cursor.length<=4096 && cursor.startsWith("ew-subagent-v1."))
            val value=JSONObject(String(easyBytes(cursor.removePrefix("ew-subagent-v1."))))
            require(value.getString("scope")==scope)
            messages.indexOfFirst { it.id==value.getString("before") }.also { require(it>=0) }
        }
        var start=end;var bytes=0
        while(start>0 && end-start<80) {
            val size=encodeMessage(messages[start-1]).toString().toByteArray().size
            if(bytes+size>1_048_576 && start<end)break
            bytes+=size;start--
        }
        val next=if(start>0)"ew-subagent-v1."+easyBase64(easyCanonical(JSONObject().put("scope",scope).put("before",messages[start].id)).toByteArray())else null
        return Window(messages.subList(start,end),next)
    }
}
