package com.agenthub.core

/** Pure notification admission/projection rules; delivery, authentication and OS permissions stay native. */
object TaskNotificationRules {
    val kinds = listOf("approval","question","plan","paused","interrupted","failed","uncertain","completed","connection","stopped")
    fun actionable(kind: String) = kind in listOf("approval","question","plan","paused","uncertain")
    fun identifier(value: String) = value.length in 1..256 && value.all { it.code >= 32 }
    fun eventId(value: String) = value.matches(Regex("[a-f0-9]{64}"))
    fun sound(value: String) = value.matches(Regex("default|none|[a-zA-Z0-9_-]{1,72}\\.caf"))
    fun summary(value: String): Boolean {
        if(value.length !in 1..160 || value.startsWith(' ') || value.endsWith(' ') || "  " in value)return false
        return value.all { c->
            val n=c.code
            !(c.isWhitespace() && c!=' ') && n !in 0..31 && n !in 127..159 &&
                n !in listOf(173,1564,6158,65279) && n !in 8203..8207 && n !in 8234..8238 && n !in 8288..8303
        }
    }
    fun target(value: TargetScope) = listOf(value.agentId,value.hostId,value.workspaceId,value.runtimeId).all(::identifier) &&
        (value.accountAuthority==null && value.accountSubject==null ||
            value.accountAuthority?.let(::identifier)==true && value.accountSubject?.let(::identifier)==true)
    fun readPosition(kind: String,threadId: String?,readTarget: TargetScope?,position: DeviceReadingVersion?,target: TargetScope): Boolean {
        if(readTarget!=null && !RuntimeIdentity.matches(readTarget,target))return false
        return position==null || kind=="completed" && threadId!=null && readTarget!=null &&
            position.revision in 1..9_007_199_254_740_991L && position.messageId.length in 1..512 && eventId(position.contentVersion)
    }
}
