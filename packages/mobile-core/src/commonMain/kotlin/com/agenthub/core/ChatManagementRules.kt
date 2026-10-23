package com.agenthub.core

import kotlinx.serialization.json.*

class ChatTaskStatus(val code: String,val lastKnown: Boolean)

/** Archive lifecycle is independent of execution. Host owns action authorization. */
object ChatManagementRules {
    fun canManage(demo: Boolean,online: Boolean,supported: Boolean,knownTarget: Boolean,busy: Boolean,pending: Boolean): Boolean =
        !demo&&online&&supported&&knownTarget&&!busy&&!pending
    fun pending(phase: String): Boolean = phase in listOf("ready","submitting","uncertain","submitted","outcome-unknown")
    fun taskStatus(state: String,lastTurnStatus: String?,archived: Boolean,online: Boolean,unconfirmed: Boolean): ChatTaskStatus {
        val code=when(state) {
            "running"->"running"
            "waiting-for-approval"->"approval"
            "failed"->"failed"
            "interrupted","cancelled"->"stopped"
            "completed"->"completed"
            "unknown"->if(archived)when(lastTurnStatus) {
                "interrupted","cancelled"->"stopped";"completed"->"completed";"failed"->"failed";else->"unknown"
            } else "unknown"
            "idle"->when(lastTurnStatus) { "interrupted","cancelled"->"stopped";"completed"->"completed";else->"idle" }
            else->"unknown"
        }
        return ChatTaskStatus(code,state!="unknown"&&(!online||unconfirmed))
    }
    fun mayAcceptOutcome(previousPhase: String,phase: String): Boolean =
        phase in listOf("submitted","confirmed","rejected","outcome-unknown")&&
            (previousPhase !in listOf("confirmed","rejected")||previousPhase==phase)
    fun outcomeMatches(original: String,outcome: String): Boolean = runCatching {
        require(CodexHttpContract.validate(original,"chatActionRequest")==null)
        require(CodexHttpContract.validate(outcome,"chatActionReceipt")==null)
        val expected=Json.parseToJsonElement(original).jsonObject
        val actual=Json.parseToJsonElement(outcome).jsonObject
        require(expected.filterKeys { it!="target" }.all { (key,value)->actual[key]==value })
        fun target(value: JsonElement): TargetScope {
            val row=value.jsonObject;val account=row["account"]?.jsonObject
            return TargetScope(agentId=row.getValue("agentId").jsonPrimitive.content,
                accountAuthority=account?.get("authority")?.jsonPrimitive?.content,accountSubject=account?.get("subject")?.jsonPrimitive?.content,
                hostId=row.getValue("hostId").jsonPrimitive.content,workspaceId=row.getValue("workspaceId").jsonPrimitive.content,
                runtimeId=row.getValue("runtimeId").jsonPrimitive.content)
        }
        val a=target(expected.getValue("target"));val b=target(actual.getValue("target"))
        require(a.hostId==expected.getValue("hostId").jsonPrimitive.content&&RuntimeIdentity.matches(a,b))
        true
    }.getOrDefault(false)
}
