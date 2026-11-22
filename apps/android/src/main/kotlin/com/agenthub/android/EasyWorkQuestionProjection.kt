package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Project pending requests without turning asynchronous questions into a run barrier. */
internal object EasyWorkQuestionProjection {
    fun approvals(snapshot: JSONObject,threadId: String): List<JSONObject> {
        val states=snapshot.optJSONArray("questionStates")
        val requests=states?.objects()?.filter { it.optString("status")=="pending" && it.optBoolean("async") }?.toMutableList()?:mutableListOf()
        snapshot.optJSONObject("pendingQuestion")?.let { if(!it.optBoolean("async") || states==null)requests.add(0,it) }
        val identities=mutableSetOf<String>()
        return requests.map { request ->
            val captured=JSONObject().put("threadId",threadId)
            require(request.getString("threadId")==threadId)
            for(key in listOf("runId","sessionId","toolCallId"))captured.put(key,request.getString(key).also { require(it.isNotEmpty()) })
            require(identities.add(easyCanonical(captured)))
            val items=request.getJSONArray("questions").objects();require(items.isNotEmpty())
            val ids=mutableSetOf<String>()
            val projected=items.map { q ->
                val id=q.getString("header");require(id.isNotEmpty() && ids.add(id))
                val prompt=q.getString("question");require(prompt.isNotEmpty())
                JSONObject().put("id",id).put("question",prompt).apply {
                    for(key in listOf("multiple","custom","isSecret"))if(q.has(key)) { require(q.get(key) is Boolean);put(key,q.get(key)) }
                    if(q.has("options") && !q.isNull("options"))put("options",JSONArray(q.getJSONArray("options").objects().map { option ->
                        JSONObject().put("label",option.getString("label").also { require(it.isNotEmpty()) }).put("description",option.getString("description"))
                    }))
                }
            }
            val async=request.optBoolean("async")
            captured.put("questions",JSONArray(items)).put("async",async)
            JSONObject().put("id","question."+easyBase64(easyCanonical(captured).toByteArray()))
                .put("threadId",threadId).put("turnId",captured.getString("runId")).put("kind","question")
                .put("title","需要你的回答").put("detail","").put("questions",JSONArray(projected))
                .put("decisions",JSONArray(if(async)emptyList<String>()else listOf("answer","deny")))
                .put("notificationRequestId",captured.getString("toolCallId")).apply { if(async)put("interactionMode","async") }
        }
    }
    fun resolution(threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>): JSONObject {
        val request=JSONObject(String(easyBytes(approvalId.removePrefix("question."))))
        require(request.getString("threadId")==threadId && request.getString("runId")==turnId)
        require(decision in listOf("","answer","deny") || decision=="skip" && request.optBoolean("async"))
        val abandon=decision in listOf("deny","skip")
        val params=JSONObject().put("threadId",threadId).put("kind","question").put("action",if(abandon)"abandon"else "answer")
            .put("serverRequestId",request.getString("toolCallId").also { require(it.isNotEmpty()) })
            .put("sessionId",request.getString("sessionId").also { require(it.isNotEmpty()) }).put("runId",turnId)
        if(!abandon) {
            val items=request.getJSONArray("questions").objects()
            require(answers.keys==items.map { it.getString("header") }.toSet())
            params.put("answer",JSONObject().put("questions",JSONArray(items.map { q ->
                val id=q.getString("header");val values=answers[id].orEmpty().filter { it.isNotBlank() }
                val labels=q.optJSONArray("options")?.objects()?.map { it.getString("label") }.orEmpty()
                val selected=values.filter { it in labels };val custom=values.filter { it !in labels }.joinToString("\n")
                require(values.isNotEmpty() && (q.optBoolean("multiple") || selected.size<=1) && (custom.isEmpty() || q.booleanOrNull("custom")!=false)) { "回答不符合此问题的选项规则" }
                JSONObject().put("header",id).apply { if(selected.isNotEmpty())put("selectedOptions",JSONArray(selected));if(custom.isNotEmpty())put("customAnswer",custom) }
            })))
        }
        return params
    }
}
