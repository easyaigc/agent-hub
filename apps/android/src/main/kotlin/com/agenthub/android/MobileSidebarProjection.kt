package com.agenthub.android

import com.agenthub.core.*

/** Platform DTO mapping only; ordering, exclusions and limits live in mobile-core. */
internal fun mobileSidebarOrder(client: MobileClient,threads: List<MobileThread>,projects: List<MobileProject>,workspace: MobileWorkspace?): SidebarOrder =
    WorkspaceSidebarRules.order(client.id,
        threads.map { SidebarThreadState(it.id,it.hostId,it.projectId,it.projectIds,it.archived==true) },
        projects.map { SidebarProjectState(it.id,it.hostId) },workspace?.pinnedThreadIds.orEmpty(),
        workspace?.sidebar?.let { value -> SidebarSource(value.version,value.pinned.map { SidebarPinnedItem(it.kind,it.id) },
            value.recentThreadIds,value.projectIds,value.projectThreadIds.map { SidebarProjectThreads(it.key,it.value) }) })
