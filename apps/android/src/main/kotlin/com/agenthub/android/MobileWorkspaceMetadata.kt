package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Domain display metadata, never proof of a connected owner or execution authority. */
data class MobileSidebarItem(val kind: String, val id: String)
data class MobileWorkspaceSidebar(val version: Int, val pinned: List<MobileSidebarItem>,
    val recentThreadIds: List<String>, val projectIds: List<String>,
    val projectThreadIds: Map<String,List<String>>, internal val wire: String)

internal object MobileWorkspaceMetadata {
    private const val invalid="电脑返回的数据格式无效"
    fun text(row: JSONObject,key: String): String? = if(!row.has(key)||row.isNull(key)) null else
        (row.get(key) as? String ?: error(invalid))
    fun boolean(row: JSONObject,key: String): Boolean? = if(!row.has(key)||row.isNull(key)) null else
        (row.get(key) as? Boolean ?: error(invalid))
    private fun integer(value: Any): Int {
        val number=value as? Number ?: error(invalid)
        val result=number.toInt()
        require(number.toDouble()==result.toDouble()) { invalid }
        return result
    }
    fun count(row: JSONObject,key: String): Int? = if(!row.has(key)||row.isNull(key)) null else
        integer(row.get(key)).also { require(it>=0) { invalid } }
    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map {
        array.get(it) as? String ?: error(invalid)
    }
    fun strings(row: JSONObject,key: String): List<String>? = if(!row.has(key)||row.isNull(key)) null else strings(row.getJSONArray(key))
    fun sidebar(row: JSONObject): MobileWorkspaceSidebar? {
        if(!row.has("sidebar")||row.isNull("sidebar"))return null
        val value=row.getJSONObject("sidebar")
        val version=integer(value.get("version"))
        // Match the iOS version boundary: unknown versions cannot become v1 display order.
        // Keep their opaque JSON so a cache save does not silently rewrite future metadata.
        if(version!=1)return MobileWorkspaceSidebar(version,emptyList(),emptyList(),emptyList(),emptyMap(),value.toString())
        val pinned=value.getJSONArray("pinned").objects().map {
            MobileSidebarItem(requireNotNull(text(it,"kind")) { invalid },requireNotNull(text(it,"id")) { invalid })
        }
        val children=value.getJSONObject("projectThreadIds")
        return MobileWorkspaceSidebar(version,pinned,strings(value.getJSONArray("recentThreadIds")),
            strings(value.getJSONArray("projectIds")),children.keys().asSequence().associateWith { strings(children.getJSONArray(it)) },value.toString())
    }
    fun mergePage(previous: MobileWorkspace,incoming: MobileWorkspace): MobileWorkspace {
        require(previous.hostId==incoming.hostId&&sameScope(previous.scope,incoming.scope)) { "电脑身份不匹配" }
        return incoming.copy(projects=(previous.projects+incoming.projects).distinctBy { it.id },
            threads=(previous.threads+incoming.threads).distinctBy { it.id },
            nextProjectCursor=if(previous.nextProjectCursor==null)null else incoming.nextProjectCursor,
            nextThreadCursor=if(previous.nextThreadCursor==null)null else incoming.nextThreadCursor,
            pinnedThreadIds=incoming.pinnedThreadIds ?: previous.pinnedThreadIds,
            sidebar=incoming.sidebar ?: previous.sidebar)
    }
    fun encode(workspace: MobileWorkspace): JSONObject = JSONObject(workspace.wire)
        .put("nextProjectCursor",workspace.nextProjectCursor).put("nextThreadCursor",workspace.nextThreadCursor)
        .put("projects",JSONArray(workspace.projects.map { JSONObject().put("id",it.id).put("hostId",it.hostId)
            .put("name",it.name).put("roots",JSONArray(it.roots)) }))
        .put("threads",JSONArray(workspace.threads.map { JSONObject().put("id",it.id).put("hostId",it.hostId)
            .put("title",it.title).put("state",it.state).put("projectId",it.projectId).put("projectIds",JSONArray(it.projectIds))
            .put("hasUnreadTurn",it.unread).put("archived",it.archived).put("lastTurnStatus",it.lastTurnStatus)
            .put("pendingAsyncQuestionCount",it.pendingAsyncQuestionCount).put("archiveUnavailableReason",it.archiveUnavailableReason)
            .put("readState",it.readState?.json()) }))
        .put("pinnedThreadIds",workspace.pinnedThreadIds?.let(::JSONArray))
        .put("sidebar",workspace.sidebar?.let { JSONObject(it.wire) })
}
