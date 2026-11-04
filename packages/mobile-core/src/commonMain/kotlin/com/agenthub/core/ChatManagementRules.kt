package com.agenthub.core

import kotlinx.serialization.json.*

class ChatTaskStatus(val code: String,val lastKnown: Boolean)

/** Archive lifecycle is independent of execution. Host owns action authorization. */
object ChatManagementRules {
    private val titleWhitespace = "\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"
    fun normalizedTitle(value: String): String? {
        val trimmed=value.replace(Regex("^[$titleWhitespace]+|[$titleWhitespace]+$"),"")
        return trimmed.takeIf { it.isNotEmpty()&&it.length<=500 }
    }
    /** Local connection keys are distinct from runtime Host IDs; providers verify wire identities. */
    fun validIntent(json: String): Boolean = runCatching {
        require(DomainContract.validate(json,"chatManagementIntent")==null)
        val row=Json.parseToJsonElement(json).jsonObject
        val target=row.getValue("target").jsonObject
        if(target.getValue("agentId").jsonPrimitive.content!="easywork") {
            require(CodexHttpContract.validate(json,"chatActionRequest")==null)
            require(target.getValue("hostId")==row.getValue("hostId"))
        }
        fun identifier(value: JsonElement)=value.jsonPrimitive.content.let { text->
            text.isNotEmpty()&&text.length<=256&&text.all { it.code>=32 }
        }
        require(listOf("agentId","hostId","workspaceId","runtimeId").all { identifier(target.getValue(it)) })
        target["account"]?.jsonObject?.let { account->require(account.values.all(::identifier)) }
        if(row.getValue("action").jsonPrimitive.content=="rename") {
            val title=row.getValue("title").jsonPrimitive.content
            require(title.length<=500&&normalizedTitle(title)!=null)
        }
        true
    }.getOrDefault(false)
    fun validOutcome(json: String): Boolean = runCatching {
        require(DomainContract.validate(json,"chatManagementOutcome")==null)
        val row=Json.parseToJsonElement(json).jsonObject
        if(row.getValue("target").jsonObject.getValue("agentId").jsonPrimitive.content!="easywork")
            require(CodexHttpContract.validate(json,"chatActionReceipt")==null)
        validIntent(JsonObject(row.filterKeys { it!="phase"&&it!="error" }).toString())
    }.getOrDefault(false)
    fun canManage(demo: Boolean,online: Boolean,supported: Boolean,knownTarget: Boolean,busy: Boolean,pending: Boolean): Boolean =
        !demo&&online&&supported&&knownTarget&&!busy&&!pending
    fun pending(phase: String): Boolean = phase in listOf("ready","submitting","uncertain","submitted","outcome-unknown")
    fun blocksInput(phase: String, waitingEnded: Boolean): Boolean = pending(phase)&&!waitingEnded
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
        require(validIntent(original))
        require(validOutcome(outcome))
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
        require(RuntimeIdentity.matches(a,b))
        true
    }.getOrDefault(false)
}
