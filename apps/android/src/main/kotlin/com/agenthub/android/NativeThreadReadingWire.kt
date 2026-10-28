package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import org.json.JSONObject

/** Actual Host /v1/threads/read contract. The central generic HTTP schema does not yet define this named operation. */
internal object NativeThreadReadingWire {
    fun state(value: JSONObject): ThreadReadState? = if(!value.has("readState") || value.isNull("readState"))null
        else ThreadReadState.decode(value.getJSONObject("readState"))
    fun request(value: NativePendingRead) = JSONObject().put("hostId",value.target.hostId).put("threadId",value.threadId)
        .put("readerId",value.readerId).put("position",value.position.json()).put("target",CodexCommandCodec.target(value.target)).also(::validateRequest)
    fun validateRequest(value: JSONObject) {
        require(value.keys().asSequence().toSet()==setOf("hostId","threadId","readerId","position","target"))
        val target=HostReadCodec.target(CodexCommandCodec.validate(value.getJSONObject("target"),"target"))!!
        require(readingString(value,"hostId")==target.hostId)
        ThreadReadingIdentity.key(target,readingString(value,"threadId"))
        require(readingString(value,"readerId").matches(Regex("[a-f0-9]{64}")))
        ThreadReadPosition.decode(value.getJSONObject("position"))
    }
    fun acknowledgement(original: NativePendingRead,value: JSONObject): JSONObject {
        require(value.keys().asSequence().toSet()==setOf("hostId","threadId","target","readerId","read"))
        val target=HostReadCodec.target(CodexCommandCodec.validate(value.getJSONObject("target"),"target"))
        require(RuntimeIdentity.matches(target,original.target) && readingString(value,"hostId")==original.target.hostId &&
            readingString(value,"threadId")==original.threadId && readingString(value,"readerId")==original.readerId &&
            ThreadReadPosition.decode(value.getJSONObject("read"))==original.position)
        return value
    }
}

/** Shared native display predicates; these never infer visibility from a list index or a source unread flag. */
internal object NativePhoneReadingRules {
    fun latest(values: List<ThreadReadPosition?>): ThreadReadPosition? {
        val positions=values.filterNotNull();val revision=positions.maxOfOrNull { it.revision } ?: return null
        return positions.filter { it.revision==revision }.distinct().singleOrNull()
    }
    fun visible(viewLeft: Float,viewTop: Float,viewRight: Float,viewBottom: Float,
        left: Float,top: Float,right: Float,bottom: Float): Boolean =
        listOf(viewLeft,viewTop,viewRight,viewBottom,left,top,right,bottom).all { it.isFinite() } &&
            viewRight>viewLeft && viewBottom>viewTop && right>left && bottom>top &&
            left>=viewLeft-.5f && right<=viewRight+.5f && top>=viewTop-.5f && bottom<=viewBottom+.5f
}
