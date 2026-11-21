package com.agenthub.android

/** Test-only value rules. No Android, storage, network, clock or credentials. */
internal object F31UserDataRulesV2 {
    const val SELECTOR = "attachmentUploadOnlyIndependentCancelPreservesOriginalUserDataV2"
    data class Owner(val host: String, val target: Map<String, Any?>, val requiresReadTarget: Boolean = true)
    private val metadataKeys = setOf("version","appearance","language","demo","selected","clients","drafts","selectedThreads","draftVersions","attachmentImportGenerations","newProjects","draftFiles","photoSources","generation","replyModes","generationPreferences","dismissedReceiptFailures","expanded","sidebar","workspaces","recentProjects")
    private val clientKeys = setOf("id","name","endpoint","mode","scope","readerId","requiresReadTarget","codexDirect","agentConnection","storageIdentity","migrationEasyWork")
    private val workspaceKeys = setOf("hostId","name","connectionMode","projects","threads","nextProjectCursor","nextThreadCursor","capabilities","readTarget","agentConnectionState","pinnedThreadIds","sidebar","mobileSnapshotVersion","providerId","target","runtimeConnected","clientType")
    private val cacheFields = setOf("projects","threads","nextProjectCursor","nextThreadCursor","pinnedThreadIds","sidebar","connectionMode","runtimeConnected")
    private val projectKeys = setOf("id","hostId","name","roots")
    private val threadKeys = setOf("id","hostId","title","state","projectId","projectIds","hasUnreadTurn","archived","lastTurnStatus","pendingAsyncQuestionCount","archiveUnavailableReason","readState")
    @Suppress("UNCHECKED_CAST") fun obj(v: Any?): Map<String, Any?> {
        require(v is Map<*, *> && v.keys.all { it is String })
        return v as Map<String, Any?>
    }
    fun array(v: Any?): List<Any?> { require(v is List<*>); return v }
    private fun closed(v: Map<String, Any?>, allowed: Set<String>) { require(v.keys.all { it in allowed }) }
    private fun target(v: Any?): Map<String, Any?> = obj(v).also {
        closed(it,setOf("agentId","hostId","workspaceId","runtimeId","account"))
        for (k in listOf("agentId","hostId","workspaceId","runtimeId")) require(it[k] is String && (it[k] as String).isNotEmpty())
        if(it.containsKey("account")) obj(it["account"]).also { a -> require(a.keys==setOf("authority","subject") && a.values.all { x -> x is String && x.isNotEmpty() }) }
    }
    private fun candidatePolicy(v: Map<String, Any?>): Map<String, Any?> {
        closed(v,setOf("id","transportId","endpoint","peerFingerprint","networkKind"))
        require(listOf("id","transportId","endpoint").all { v[it] is String && (v[it] as String).isNotEmpty() })
        // Direct IDs must be verified from the production factory by the Android adapter.
        return v.filterKeys { it !in setOf("id","endpoint") }
    }
    private fun client(v: Map<String, Any?>): Map<String, Any?> {
        closed(v,clientKeys);target(v["scope"])
        val stable=v.toMutableMap();stable.remove("mode")
        val mode=v["mode"];require(mode in setOf("desktop-runtime","managed-runtime","shared-runtime","history","easywork-runtime"))
        val connection=v["agentConnection"]
        if(connection!=null) {
            val c=obj(connection);require(c.keys==setOf("target","candidates","credentialRef","preference"))
            require(target(c["target"])==target(v["scope"]))
            val cs=array(c["candidates"]);require(cs.size in 1..32)
            require(cs.map { obj(it)["id"] }.distinct().size==cs.size)
            val policies=cs.map { candidatePolicy(obj(it)) }.toSet()
            val nonDirect=cs.map(::obj).filter { it["transportId"]!="lan-wss" }
            stable["agentConnection"]=c.filterKeys { it!="candidates" } + mapOf("directTrustPolicies" to policies,"nonDirectExact" to nonDirect)
        }
        return stable
    }
    fun project(root: Map<String, Any?>, owners: Map<String, Owner>): Map<String, Any?> {
        closed(root,metadataKeys)
        val result=root.toMutableMap()
        val clients=array(root["clients"]).map(::obj)
        require(clients.map { it["id"] }.distinct().size==clients.size)
        result["clients"]=clients.map(::client) // Client order and every user-owned array stay ordered.
        val workspaces=obj(root["workspaces"])
        result["workspaces"]=workspaces.mapValues { (key,wire) ->
            val owner=requireNotNull(owners[key]);val w=obj(wire);closed(w,workspaceKeys)
            require(w["hostId"]==owner.host && w["name"] is String && w["connectionMode"] is String)
            for(k in listOf("nextProjectCursor","nextThreadCursor")) if(w.containsKey(k)) require(w[k]==null || w[k] is String)
            if(w.containsKey("capabilities") && w["capabilities"]!=null) require(array(w["capabilities"]).all { it is String })
            if(w.containsKey("runtimeConnected")) require(w["runtimeConnected"]==null || w["runtimeConnected"] is Boolean)
            if(w.containsKey("pinnedThreadIds") && w["pinnedThreadIds"]!=null) require(array(w["pinnedThreadIds"]).all {it is String})
            val t=if(w.containsKey("mobileSnapshotVersion")) w["target"] else w["readTarget"]
            if(t==null) require(!owner.requiresReadTarget) // Existing optional legacy read target only; owner key is production-derived.
            else require(target(t)==owner.target)
            for((field,allowed) in listOf("projects" to projectKeys,"threads" to threadKeys)) {
                val rows=array(w[field]);require(rows.size<=10000)
                require(rows.map { obj(it)["id"] }.distinct().size==rows.size)
                for(row in rows.map(::obj)) {
                    closed(row,allowed);require(row["hostId"]==owner.host && row["id"] is String)
                    if(field=="projects") require(row["name"] is String && array(row["roots"]).all {it is String})
                    else require(row["title"] is String)
                }
            }
            val stable=w.filterKeys { it !in cacheFields }.toMutableMap()
            if(w["clientType"]!=null) { val type=obj(w["clientType"]);require(type.keys==setOf("id","name") && type.values.all {it is String}) }
            if(w["agentConnectionState"]!=null) {
                val state=obj(w["agentConnectionState"])
                closed(state,setOf("target","phase","transportId","processHealthy","runtimeConnected","historyReadable"))
                if(state.containsKey("target"))require(target(state["target"])==owner.target)
                if(state.containsKey("phase"))require(state["phase"] in setOf("disconnected","connecting","connected","recovering"))
                for(k in listOf("processHealthy","runtimeConnected","historyReadable")) if(state.containsKey(k))require(state[k] is Boolean || state[k]==null)
                stable["agentConnectionState"]=state.filterKeys { it !in setOf("phase","processHealthy","runtimeConnected","historyReadable") }
            }
            stable // Capabilities, full identity and transport residual stay exact.
        }
        return result
    }
    fun compare(before: Map<String, Any?>, after: Map<String, Any?>): Boolean = before==after
}
