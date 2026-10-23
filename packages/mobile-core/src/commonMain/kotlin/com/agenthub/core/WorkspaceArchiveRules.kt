package com.agenthub.core

import kotlinx.serialization.json.*

/** Immutable batches, exact ordered item receipts and monotonic item terminals, shared by both native apps. */
object WorkspaceArchiveRules {
    /** Selection cannot create a second intent while either original write is unresolved. */
    fun eligible(knownTarget: Boolean,archived: Boolean,singleBusy: Boolean,singlePending: Boolean,batchPending: Boolean): Boolean =
        knownTarget && !archived && !singleBusy && !singlePending && !batchPending

    fun pending(state: String): Boolean = state in listOf("pending","submitting","outcome-unknown")
    fun select(current: List<String>,eligible: List<String>,allSelected: Boolean): List<String> =
        if(allSelected)current.filter { it !in eligible } else (current+eligible).distinct().take(100)
    fun requestValid(wire: String): Boolean = runCatching {
        require(CodexHttpContract.validate(wire,"workspaceManagementRequest")==null)
        val row=Json.parseToJsonElement(wire).jsonObject
        require(row.getValue("target").jsonObject.getValue("hostId")==row.getValue("hostId"))
        require(row.getValue("ids").jsonArray.all { it.jsonPrimitive.content.length<=200 })
        true
    }.getOrDefault(false)
    private fun target(row: JsonObject): TargetScope {
        val v=row.getValue("target").jsonObject;val a=v["account"]?.jsonObject
        return TargetScope(v.getValue("agentId").jsonPrimitive.content,a?.get("authority")?.jsonPrimitive?.content,
            a?.get("subject")?.jsonPrimitive?.content,v.getValue("hostId").jsonPrimitive.content,
            v.getValue("workspaceId").jsonPrimitive.content,v.getValue("runtimeId").jsonPrimitive.content)
    }
    private fun receipt(request: JsonObject,wire: String): JsonObject {
        require(CodexHttpContract.validate(wire,"workspaceManagementOperation")==null)
        val row=Json.parseToJsonElement(wire).jsonObject
        require(request.filterKeys { it!="target" }.all { (key,value)->row[key]==value })
        require(RuntimeIdentity.matches(target(request),target(row)))
        require(row.getValue("items").jsonArray.map { it.jsonObject.getValue("id") }==request.getValue("ids").jsonArray)
        return row
    }
    /** null rejects wrong intent/target; prior terminal state+error is immutable even across stale queries. */
    fun merge(original: String,previous: String?,incoming: String): String? = runCatching {
        require(requestValid(original));val request=Json.parseToJsonElement(original).jsonObject
        val next=receipt(request,incoming);val prior=previous?.let { receipt(request,it) }
        val old=prior?.get("items")?.jsonArray
        val items=next.getValue("items").jsonArray.mapIndexed { index,item ->
            val saved=old?.get(index)?.jsonObject
            if(saved!=null&&!pending(saved.getValue("state").jsonPrimitive.content))saved else item
        }
        JsonObject(next.toMutableMap().apply { put("items",JsonArray(items)) }).toString()
    }.getOrNull()
}
