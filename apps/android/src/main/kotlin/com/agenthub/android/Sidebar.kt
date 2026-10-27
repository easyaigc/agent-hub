package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenthub.core.WorkspaceSidebarRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Sidebar(session: MobileSession,close: () -> Unit,clients: () -> Unit,newChat: () -> Unit,settings: () -> Unit,addProject: ()->Unit={},active: Boolean=true) {
    val colors=MaterialTheme.colorScheme
    var searchVisible by remember { mutableStateOf(false) }
    val display=session.sidebarDisplay
    val query=session.search
    val searching=query.isNotEmpty()
    val list=rememberLazyListState()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val view=LocalView.current
    val nativeKeyboard=remember(view) { androidx.core.view.SoftwareKeyboardControllerCompat(view) }
    val searchFocus=remember { FocusRequester() }
    val resultsFocus=remember { FocusRequester() }
    fun finishSearchEditing() {
        if(active)resultsFocus.requestFocus()
        keyboard?.hide();nativeKeyboard.hide()
    }
    LaunchedEffect(active) { if(active&&searchVisible)finishSearchEditing() }
    val client=session.selectedClient
    val threads=session.threads
    val projects=session.projects
    val order=remember(client.cacheKey(null),threads,projects,session.workspace?.pinnedThreadIds,session.workspace?.sidebar) {
        mobileSidebarOrder(client,threads,projects,session.workspace)
    }
    val byThread=remember(client.id,threads) { threads.filter { it.hostId==client.id }.distinctBy { it.id }.associateBy { it.id } }
    val byProject=remember(client.id,projects) { projects.filter { it.hostId==client.id }.distinctBy { it.id }.associateBy { it.id } }
    val children=remember(order,byThread) { order.projectThreads.associate { it.projectId to it.threadIds.mapNotNull(byThread::get) } }
    fun matches(text: String)=!searching||text.contains(query,ignoreCase=true)
    fun projectMatches(project: MobileProject)=matches(project.name)||children[project.id].orEmpty().any { matches(it.title) }
    fun projectChildren(project: MobileProject)=children[project.id].orEmpty().filter { matches(it.title)||matches(project.name) }
    val pinned=order.pinned.filter { item -> if(item.kind=="thread")byThread[item.id]?.let { matches(it.title) }==true else byProject[item.id]?.let(::projectMatches)==true }
    val recent=order.recentThreadIds.mapNotNull(byThread::get).filter { matches(it.title) }
    val folders=order.projectIds.mapNotNull(byProject::get).filter(::projectMatches)
    Column(Modifier.fillMaxSize().background(colors.surface).padding(horizontal=10.dp).testTag("sidebar")) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min=48.dp).padding(start=9.dp).testTag("sidebar-client-switch")
                .semantics { role=Role.Button;contentDescription="切换客户端，${if(session.hasClient)session.selectedClient.name else "未连接"}，${if(session.hasClient)session.sourceLabel else "尚未连接客户端"}";stateDescription=if(session.hasClient)session.selectedClient.connectionLabel else "未连接" }
                .clickable(onClick=clients)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(session.hasClient) session.selectedClient.name else "Agent Hub",Modifier.weight(1f,fill=false),fontSize=20.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp));ProductIcon("down",Modifier.size(12.dp),colors.onSurfaceVariant)
                }
                Text(if(session.hasClient) session.sourceLabel else "尚未连接客户端",fontSize=11.sp,color=colors.onSurfaceVariant)
            }
            IconAction("search","搜索项目和对话","sidebar-search-toggle") {
                searchVisible=!searchVisible
                if(!searchVisible) { session.search="";focus.clearFocus();keyboard?.hide() }
            }
        }
        if(searchVisible) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).background(colors.background).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                ProductIcon("search",Modifier.size(18.dp),colors.onSurfaceVariant)
                BasicTextField(session.search,{ session.search=it },Modifier.weight(1f).padding(start=8.dp,top=12.dp,bottom=12.dp).focusRequester(searchFocus).testTag("sidebar-search"),singleLine=true,enabled=active,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={ finishSearchEditing() }),
                    textStyle=androidx.compose.ui.text.TextStyle(color=colors.onSurface,fontSize=17.sp),decorationBox={ inner -> if(session.search.isEmpty()) Text("搜索项目和对话",fontSize=17.sp,color=colors.onSurfaceVariant);inner() })
                if(searching)IconAction("close","清除搜索","sidebar-search-clear") { session.search="" }
            }
            LaunchedEffect(Unit) { if(active)searchFocus.requestFocus() }
        }
        TextButton(onClick=newChat,enabled=session.canCreateThread,modifier=Modifier.fillMaxWidth().testTag("sidebar-new")) { ProductIcon("new");Spacer(Modifier.width(8.dp));Text("新聊天",Modifier.weight(1f),color=colors.onSurface) }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing=session.loading,onRefresh={ session.refresh(userInitiated=true) },modifier=Modifier.weight(1f).fillMaxWidth().testTag("sidebar-refresh")) {
            LazyColumn(Modifier.fillMaxSize().focusRequester(resultsFocus).focusable(enabled=active).testTag("sidebar-list"),state=list) {
                if(pinned.isNotEmpty()) {
                    val expanded=WorkspaceSidebarRules.expanded(display.pinnedSectionExpanded,searching)
                    item(key="pinned-section") { SidebarSection("置顶",expanded,!searching,"sidebar-pinned-toggle",session::toggleSidebarPinnedSection) }
                    if(expanded)pinned.forEach { pin ->
                        if(pin.kind=="thread")item(key="pinned-thread-${pin.id}") { ThreadRow(byThread.getValue(pin.id),session,"pinned-thread",close,active) }
                        else sidebarProjectRows(byProject.getValue(pin.id),projectChildren(byProject.getValue(pin.id)),session,searching,close,active)
                    }
                }
                if(recent.isNotEmpty()) {
                    val expanded=WorkspaceSidebarRules.expanded(display.recentSectionExpanded,searching)
                    item(key="recent-section") { SidebarSection("最近",expanded,!searching,"sidebar-recent-toggle",session::toggleSidebarRecentSection) }
                    if(expanded) {
                        val visible=WorkspaceSidebarRules.visibleIds(recent.map { it.id },display.recentExpanded,searching)
                        items(visible,key={ "recent-$it" }) { ThreadRow(byThread.getValue(it),session,"recent-thread",close,active) }
                        if(!searching&&recent.size>5)item { TextButton(onClick=session::toggleSidebarRecent,modifier=Modifier.testTag("sidebar-more-recent")) { Text(if(display.recentExpanded) "收起显示" else "展开显示",color=colors.onSurfaceVariant) } }
                    }
                }
                if(folders.isNotEmpty()||session.canCreateProject) {
                    val expanded=WorkspaceSidebarRules.expanded(display.projectsExpanded,searching)
                    item(key="projects-section") { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SidebarSection("项目",expanded,!searching,"sidebar-projects-toggle",session::toggleSidebarProjects) }
                        if(session.canCreateProject)IconAction("plus","添加项目","add-project",onClick=addProject)
                    } }
                    if(expanded)folders.forEach { project -> sidebarProjectRows(project,projectChildren(project),session,searching,close,active) }
                }
                if(folders.isEmpty()&&recent.isEmpty()&&pinned.isEmpty())item { Text(if(!searching) "还没有项目或对话" else "没有找到相关内容",Modifier.padding(vertical=24.dp,horizontal=9.dp),fontSize=14.sp,color=colors.onSurfaceVariant) }
                if(session.workspace?.nextProjectCursor!=null||session.workspace?.nextThreadCursor!=null)item { TextButton(onClick={ session.refresh(more=true,userInitiated=true) },enabled=!session.loading,modifier=Modifier.testTag("sidebar-load-more")) { Text("加载更多",color=colors.onSurfaceVariant) } }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        LaunchedEffect(list.isScrollInProgress) { if(list.isScrollInProgress)finishSearchEditing() }
        HorizontalDivider(color=colors.onSurface.copy(alpha=.08f))
        Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min=48.dp).testTag("sidebar-settings").semantics { role=Role.Button }.clickable { close();settings() },verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(colors.onSurface),contentAlignment=Alignment.Center) { Text("我",fontSize=12.sp,color=colors.surface) }
                Spacer(Modifier.width(10.dp));Text("个人空间",fontSize=16.sp,color=colors.onSurface)
            }
            IconAction("sidebar","收起侧栏","close-sidebar",onClick=close)
        }
    }
}

@Composable private fun SidebarSection(title: String,expanded: Boolean,enabled: Boolean,tag: String,toggle: ()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=9.dp).testTag(tag)
        .semantics { role=Role.Button;stateDescription=if(expanded) "已展开" else "已折叠" }
        .clickable(enabled=enabled,onClick=toggle),verticalAlignment=Alignment.CenterVertically) {
        Text(title,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(6.dp))
        ProductIcon(if(expanded) "down" else "chevron",Modifier.size(12.dp),MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.sidebarProjectRows(project: MobileProject,children: List<MobileThread>,session: MobileSession,searching: Boolean,close: ()->Unit,active: Boolean) {
    val expanded=WorkspaceSidebarRules.expanded(session.projectExpanded(project.id),searching)
    val all=project.id in session.sidebarDisplay.expandedLists
    item(key="project-${project.id}") {
        val colors=MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min=48.dp).padding(horizontal=9.dp).testTag("project-${project.id}")
                .semantics { stateDescription=if(expanded) "已展开" else "已折叠";role=Role.Button }.clickable(enabled=!searching) { session.toggleProject(project.id) },verticalAlignment=Alignment.CenterVertically) {
                ProductIcon("folder");Spacer(Modifier.width(10.dp));Text(project.name,Modifier.weight(1f),maxLines=1,fontSize=16.sp,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp));ProductIcon(if(expanded) "down" else "chevron",Modifier.size(12.dp),colors.onSurfaceVariant)
            }
            if(session.canCreateThread)IconAction("new","在 ${project.name} 新建对话","new-in-${project.id}") { session.newChat(project.id);close() }
        }
    }
    if(expanded) {
        val byId=children.associateBy { it.id }
        items(WorkspaceSidebarRules.visibleIds(children.map { it.id },all,searching),key={ "project-${project.id}-$it" }) { id -> Box(Modifier.padding(start=24.dp)) { ThreadRow(byId.getValue(id),session,"project-thread-${project.id}",close,active) } }
        if(children.size>5&&!searching)item { TextButton(onClick={ session.toggleSidebarList(project.id) },modifier=Modifier.padding(start=24.dp).testTag("project-more-${project.id}")) { Text(if(all) "收起显示" else "展开显示",color=MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable private fun ThreadRow(thread: MobileThread,session: MobileSession,prefix: String,close: () -> Unit,active: Boolean) {
    val colors=MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(10.dp)).background(if(thread.id==session.selectedThread) colors.onSurface.copy(alpha=.06f) else Color.Transparent).testTag("$prefix-${thread.id}").semantics { contentDescription="${thread.title}，${when(thread.state) { "running"->"运行中"; "waiting-for-approval"->"待处理"; else->if(thread.showsUnread) "未读" else "已读" }}" }.clickable { session.selectThread(thread);close() }.padding(horizontal=9.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(thread.title,Modifier.weight(1f),fontSize=16.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        when { thread.state=="running" -> if(active) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp)
                else CircularProgressIndicator(progress={ .25f },modifier=Modifier.size(14.dp),strokeWidth=1.5.dp)
            thread.state=="waiting-for-approval" -> Text("待处理",fontSize=11.sp,color=Color(0xFFD68B00))
            thread.showsUnread -> Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF007AFF)).testTag("unread-${thread.id}")) }
    }
}
