package com.agenthub.android

import com.agenthub.core.DomainContract
import org.json.JSONObject

/** Optional addresses are learned only from an authenticated response for the bound runtime.
 * A bad optional route must not discard an otherwise usable connection or replace an old pin. */
internal object EasyWorkAuthenticatedCandidates {
    fun read(context: JSONObject, target: EasyWorkTarget, original: AgentConnection): AgentConnection? {
        require(EasyWorkTarget.decode(context.getJSONObject("target")) == target && sameScope(original.target,target.scope()))
        if(!context.has("connectionCandidates"))return null
        return try {
            val routes=context.getJSONArray("connectionCandidates");require(routes.length()<=32)
            val learned=mutableListOf<AgentCandidate>()
            for(i in 0 until routes.length()) {
                val route=routes.getJSONObject(i)
                if(route.getString("transportId")!="lan-wss")continue
                val endpoint=route.getString("endpoint");val pin=route.getString("peerFingerprint")
                require(endpoint.toByteArray(Charsets.UTF_8).size<=2048 && pin.matches(Regex("[a-fA-F0-9]{64}")))
                for(candidate in EasyWorkPairing.directCandidates(listOf(endpoint),fingerprint=pin.lowercase())) {
                    val previous=(learned+original.candidates).firstOrNull { it.transportId=="lan-wss" && it.endpoint==candidate.endpoint }
                    require(previous==null || previous.peerFingerprint.equals(candidate.peerFingerprint,ignoreCase=true))
                    if(learned.none { it.endpoint==candidate.endpoint })learned.add(previous ?: candidate)
                }
            }
            val nonDirect=original.candidates.filter { it.transportId!="lan-wss" };require(nonDirect.size<=32)
            val direct=(learned+original.candidates.filter { it.transportId=="lan-wss" }).distinctBy { it.endpoint }
            original.copy(candidates=direct.take(32-nonDirect.size)+nonDirect).also {
                require(DomainContract.validate(it.json().toString(),"connection")==null)
            }
        } catch(_: Exception) { null }
    }
}
