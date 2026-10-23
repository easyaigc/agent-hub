package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Versioned native cache projections, separate from either provider's transport wire. */
object MobileSnapshotCodec {
    private fun validate(value: JSONObject) {
        require(value.getInt("mobileSnapshotVersion")==1&&value.getString("providerId")=="easywork")
        val target=AgentConnectionTarget(value.getJSONObject("target"))
        require(target.agentId==value.getString("providerId"))
    }
    private fun AgentConnectionTarget(v: JSONObject)=HostReadCodec.target(v)!!.also {
        require(com.agenthub.core.DomainContract.validate(v.toString(),"target")==null)
    }
    fun workspace(wire: String): MobileWorkspace {
        val v=JSONObject(wire);if(!v.has("mobileSnapshotVersion"))return HostReadCodec.workspace(wire)
        validate(v);val host=v.getString("hostId")
        val projects=v.getJSONArray("projects").objects().map { MobileProject(it.getString("id"),it.getString("hostId"),it.getString("name"),it.getJSONArray("roots").strings()) }
        val threads=v.getJSONArray("threads").objects().map(HostReadCodec::thread)
        require(projects.all { it.hostId==host }&&threads.all { it.hostId==host })
        return MobileWorkspace(host,v.getString("name"),v.getString("connectionMode"),projects,threads,v.optional("nextProjectCursor"),v.optional("nextThreadCursor"),HostReadCodec.target(v.getJSONObject("target")),v.getJSONArray("capabilities").strings(),wire,v.booleanOrNull("runtimeConnected"))
    }
    fun history(wire: String): MobileHistory {
        val v=JSONObject(wire);if(!v.has("mobileSnapshotVersion"))return HostReadCodec.history(wire)
        validate(v);val host=v.getString("hostId");val thread=v.getString("threadId")
        val messages=v.getJSONArray("messages").objects().map(HostReadCodec::message)
        require(messages.flatMap { it.attachments }.all { it.hostId==host&&it.threadId==thread })
        val runtime=v.optJSONObject("runtime")?.let { MobileRuntime.decode(it,host,thread) }
        v.put("messages",JSONArray())
        val windowed=decodeHistoryWindow(v.opt("historyWindow"))
        val revision=decodeHistoryRevision(v.opt("historyRevision"))
        val windowKind=decodeHistoryWindowKind(v.opt("historyWindow"))
        val synchronization=decodeSynchronization(v.opt("synchronization"))
        require(!windowed || revision!=null && revision>=0)
        return MobileHistory(host,thread,messages,v.optional("nextCursor"),HostReadCodec.target(v.getJSONObject("target")),runtime?.state?:"idle",runtime?.turnId,v.toString(),runtime!=null,runtime,v.optional("epoch"),if(v.has("cursor"))v.getLong("cursor") else null,windowed,revision,windowKind,synchronization)
    }
    // Only normalize read metadata. No revision is synthesized for an absent/null value.
    // Exposed internally so provider read boundaries can use this same strict parser.
    internal fun decodeHistoryWindow(value: Any?): Boolean {
        if(value==null || value===JSONObject.NULL)return false
        if(value is Boolean)return value
        require(value is JSONObject) { "Invalid historyWindow" }
        require(value.length()==2 && value.has("version") && value.has("kind")) { "Invalid historyWindow fields" }
        val version=(value.opt("version") as? Number)?.toString()?.toBigDecimalOrNull()
        require(version!=null && version.compareTo(BigDecimal.ONE)==0) { "Unsupported historyWindow version" }
        require(value.opt("kind") in listOf("latest","earlier")) { "Invalid historyWindow kind" }
        return true
    }
    internal fun decodeHistoryRevision(value: Any?): Long? {
        if(value==null || value===JSONObject.NULL)return null
        val revision=(value as? Number)?.toString()?.toBigDecimalOrNull()
        require(revision!=null && revision>=BigDecimal.ZERO && revision<=BigDecimal("9007199254740991") &&
            revision.stripTrailingZeros().scale()<=0) { "Invalid historyRevision" }
        return revision.longValueExact()
    }
    internal fun decodeHistoryWindowKind(value: Any?): String? {
        decodeHistoryWindow(value)
        return (value as? JSONObject)?.getString("kind")
    }
    internal fun decodeSynchronization(value: Any?): MobileHistorySynchronization? {
        if(value==null || value===JSONObject.NULL)return null
        require(value is JSONObject && value.has("state") && value.length()==
            (if(value.has("checkedAt")) 2 else 1)) { "Invalid synchronization fields" }
        val state=value.opt("state")
        require(state in listOf("live","live-window","history","unavailable")) { "Invalid synchronization state" }
        val checkedAt=value.opt("checkedAt")
        require(checkedAt==null || checkedAt===JSONObject.NULL || checkedAt is String) { "Invalid synchronization checkedAt" }
        return MobileHistorySynchronization(state as String,checkedAt as? String)
    }
    fun header(client: MobileClient)=JSONObject().put("mobileSnapshotVersion",1).put("providerId","easywork").put("hostId",client.id).put("target",CodexCommandCodec.target(client.scope!!))
}
