package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class WorkspaceSidebarRulesTest {
    private val vectors=Json.parseToJsonElement(Base64.decode(stateVectorBase64).decodeToString()).jsonObject
    private fun JsonObject.text(key: String)=getValue(key).jsonPrimitive.content
    private fun JsonObject.strings(key: String)=getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun source(value: JsonElement?): SidebarSource? {
        val row=value as? JsonObject ?: return null
        val version=row.getValue("version").jsonPrimitive.int
        if(version!=1)return SidebarSource(version,emptyList(),emptyList(),emptyList(),emptyList())
        return SidebarSource(version,row.getValue("pinned").jsonArray.map { it.jsonObject.let { p -> SidebarPinnedItem(p.text("kind"),p.text("id")) } },
            row.strings("recentThreadIds"),row.strings("projectIds"),row.getValue("projectThreadIds").jsonObject.map { (key,ids) ->
                SidebarProjectThreads(key,ids.jsonArray.map { it.jsonPrimitive.content }) })
    }
    @Test fun retainedGoldenOrdersExcludeWrongHostArchivedMissingAndDuplicateIds() {
        for(entry in vectors.getValue("sidebar").jsonArray) {
            val test=entry.jsonObject;val input=test.getValue("input").jsonObject
            val threads=input.getValue("threads").jsonArray.map { it.jsonObject.let { t -> SidebarThreadState(t.text("id"),t.text("hostId"),
                t["projectId"]?.jsonPrimitive?.contentOrNull,t.strings("projectIds"),t.getValue("archived").jsonPrimitive.boolean) } }
            val projects=input.getValue("projects").jsonArray.map { it.jsonObject.let { p -> SidebarProjectState(p.text("id"),p.text("hostId")) } }
            val actual=WorkspaceSidebarRules.order(input.text("hostId"),threads,projects,input.strings("pinnedThreadIds"),source(input["source"]))
            val json=buildJsonObject {
                put("pinned",JsonArray(actual.pinned.map { buildJsonObject { put("kind",it.kind);put("id",it.id) } }))
                put("recentThreadIds",JsonArray(actual.recentThreadIds.map(::JsonPrimitive)))
                put("projectIds",JsonArray(actual.projectIds.map(::JsonPrimitive)))
                put("projectThreadIds",buildJsonObject { actual.projectThreads.forEach { put(it.projectId,JsonArray(it.threadIds.map(::JsonPrimitive))) } })
            }
            assertEquals(test.getValue("expected"),json,test.text("name"))
        }
    }
    @Test fun searchExpansionAndFiveRowLimitNeverChangeTheSavedPreferenceOrInputList() {
        for(entry in vectors.getValue("sidebarVisibility").jsonArray) {
            val test=entry.jsonObject;val ids=test.strings("ids").toMutableList();val before=ids.toList()
            val expanded=test.getValue("expanded").jsonPrimitive.boolean;val searching=test.getValue("searching").jsonPrimitive.boolean
            assertEquals(test.getValue("expectedExpanded").jsonPrimitive.boolean,WorkspaceSidebarRules.expanded(expanded,searching),test.text("name"))
            assertEquals(test.strings("expected"),WorkspaceSidebarRules.visibleIds(ids,expanded,searching),test.text("name"))
            assertEquals(before,ids,test.text("name"))
        }
    }
    @Test fun runningCountsKeepUnknownSeparateAndLifecycleIndependent() {
        val rows=vectors.getValue("runningCounts").jsonArray
        for(entry in rows) {
            val row=entry.jsonObject;val input=row.getValue("input").jsonObject
            val conversations=input.getValue("conversations").jsonArray.map { it.jsonObject.let { t -> ConversationActivity(t.text("id"),t.text("hostId"),t.text("state")) } }
            val expected=row.getValue("expected").jsonPrimitive.intOrNull
            assertEquals(expected,WorkspaceSidebarRules.runningCount(input.text("hostId"),input.getValue("online").jsonPrimitive.boolean,
                input.getValue("demo").jsonPrimitive.boolean,input.getValue("workspaceAvailable").jsonPrimitive.boolean,
                input["connectionMode"]?.jsonPrimitive?.contentOrNull,conversations),row.text("name"))
        }
        assertEquals(11,rows.size)
    }

}
