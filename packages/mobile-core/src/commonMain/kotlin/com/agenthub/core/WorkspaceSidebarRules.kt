package com.agenthub.core

class ConversationActivity(val id: String,val hostId: String,val state: String)

class SidebarThreadState(val id: String,val hostId: String,val projectId: String?,
    val projectIds: List<String>,val archived: Boolean)
class SidebarProjectState(val id: String,val hostId: String)
class SidebarPinnedItem(val kind: String,val id: String)
class SidebarProjectThreads(val projectId: String,val threadIds: List<String>)
class SidebarSource(val version: Int,val pinned: List<SidebarPinnedItem>,val recentThreadIds: List<String>,
    val projectIds: List<String>,val projectThreads: List<SidebarProjectThreads>)
class SidebarOrder(val pinned: List<SidebarPinnedItem>,val recentThreadIds: List<String>,
    val projectIds: List<String>,val projectThreads: List<SidebarProjectThreads>)

/** Shared display projection only. IDs and ordering never authorize reading or execution. */
object WorkspaceSidebarRules {
    /** Snapshot-only display count. Unknown availability never manufactures a zero or authorizes control.
     * Lifecycle is deliberately independent: an archived task may still be running. */
    fun runningCount(hostId: String,online: Boolean,demo: Boolean,workspaceAvailable: Boolean,
        connectionMode: String?,conversations: List<ConversationActivity>): Int? {
        if(!online || !demo && (!workspaceAvailable || connectionMode=="history-only"))return null
        return conversations.filter { it.hostId==hostId && it.state in listOf("running","waiting-for-approval") }
            .map { it.id }.toSet().size
    }

    fun order(hostId: String,threads: List<SidebarThreadState>,projects: List<SidebarProjectState>,
        pinnedThreadIds: List<String>,source: SidebarSource?): SidebarOrder {
        val active=threads.filter { it.hostId==hostId }.distinctBy { it.id }.filter { !it.archived }
        val folders=projects.filter { it.hostId==hostId }.distinctBy { it.id }
        val threadIds=active.map { it.id }.toSet()
        val folderIds=folders.map { it.id }.toSet()
        val pinnedIds=pinnedThreadIds.toSet()
        val desktop=source?.takeIf { it.version==1 }
        fun ordered(ids: List<String>,known: Set<String>)=ids.filter { it in known }.distinct()
        val pinned=if(desktop==null)ordered(pinnedThreadIds,threadIds).map { SidebarPinnedItem("thread",it) }
            else desktop.pinned.filter { item -> when(item.kind) {
                "thread" -> item.id in threadIds&&item.id in pinnedIds
                "project" -> item.id in folderIds
                else -> false
            } }.distinctBy { it.kind to it.id }
        val recent=ordered(desktop?.recentThreadIds ?: active.map { it.id },threadIds).filter { it !in pinnedIds }
        val children=desktop?.projectThreads?.associate { it.projectId to it.threadIds }.orEmpty()
        val pinnedFolders=pinned.filter { it.kind=="project" }.map { it.id }.toSet()
        return SidebarOrder(pinned,recent,ordered(desktop?.projectIds ?: folders.map { it.id },folderIds).filter { it !in pinnedFolders },
            folders.map { project ->
                val ids=if(desktop!=null)ordered(children[project.id].orEmpty(),threadIds).filter { it !in pinnedIds }
                    else active.filter { it.projectId==project.id||project.id in it.projectIds }.map { it.id }
                SidebarProjectThreads(project.id,ids)
            })
    }
    fun expanded(preference: Boolean,searching: Boolean): Boolean = preference||searching
    fun visibleIds(ids: List<String>,expanded: Boolean,searching: Boolean): List<String> =
        if(expanded||searching)ids else ids.take(5)
}
