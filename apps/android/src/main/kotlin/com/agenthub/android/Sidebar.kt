package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Sidebar(session: MobileSession,close: () -> Unit,clients: () -> Unit,newChat: () -> Unit,settings: () -> Unit,addProject: ()->Unit={}) {
    val colors=MaterialTheme.colorScheme
    var searchVisible by remember { mutableStateOf(false) }
    val recentExpanded=session.sidebarDisplay.recentExpanded
    val projectsExpanded=session.sidebarDisplay.projectsExpanded
    val expandedLists=session.sidebarDisplay.expandedLists
    val list=rememberLazyListState()
    val focus=LocalFocusManager.current
    fun matches(text: String)=session.search.isEmpty() || text.contains(session.search,ignoreCase=true)
    val recent=session.threads.filter { matches(it.title) }
    val projects=session.projects.filter { matches(it.name) || session.threads.any { t -> t.belongs(it) && matches(t.title) } }
    Column(Modifier.fillMaxSize().background(colors.surface).padding(horizontal=10.dp).testTag("sidebar")) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min=48.dp).padding(start=9.dp).testTag("sidebar-client-switch").clickable(onClick=clients)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(session.hasClient) session.selectedClient.name else "Agent Hub",Modifier.weight(1f,fill=false),fontSize=20.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    ProductIcon("down",Modifier.size(12.dp),colors.onSurfaceVariant)
                }
                Text(if(session.hasClient) session.sourceLabel else "尚未连接客户端",fontSize=11.sp,color=colors.onSurfaceVariant)
            }
            IconAction("search","搜索项目和对话","sidebar-search-toggle") { searchVisible=!searchVisible; if(!searchVisible) session.search="" }
        }
        if(searchVisible) Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).background(colors.background).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
            ProductIcon("search",Modifier.size(18.dp),colors.onSurfaceVariant)
            BasicTextField(session.search,{ session.search=it },Modifier.weight(1f).padding(start=8.dp,top=12.dp,bottom=12.dp).testTag("sidebar-search"),singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(color=colors.onSurface,fontSize=17.sp),decorationBox={ inner -> if(session.search.isEmpty()) Text("搜索项目和对话",fontSize=17.sp,color=colors.onSurfaceVariant); inner() })
            if(session.search.isNotEmpty()) IconAction("close","清除搜索","sidebar-search-clear") { session.search="" }
        }
        TextButton(onClick=newChat,enabled=session.canCreateThread,modifier=Modifier.fillMaxWidth().testTag("sidebar-new")) { ProductIcon("new"); Spacer(Modifier.width(8.dp)); Text("新聊天",Modifier.weight(1f),color=colors.onSurface) }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing=session.loading,onRefresh={ session.refresh(userInitiated=true) },modifier=Modifier.weight(1f).fillMaxWidth().testTag("sidebar-refresh")) {
            LazyColumn(Modifier.fillMaxSize().testTag("sidebar-list"),state=list) {
                if(recent.isNotEmpty()) {
                    item { Text("最近",Modifier.padding(horizontal=9.dp,vertical=10.dp),fontSize=14.sp,color=colors.onSurfaceVariant) }
                    items(if(recentExpanded || session.search.isNotEmpty()) recent else recent.take(5),key={ "recent-${it.id}" }) { ThreadRow(it,session,"recent-thread",close) }
                    if(session.search.isEmpty() && recent.size>5) item { TextButton(onClick=session::toggleSidebarRecent,modifier=Modifier.testTag("sidebar-more-recent")) { Text(if(recentExpanded) "收起显示" else "展开显示",color=colors.onSurfaceVariant) } }
                }
                if(projects.isNotEmpty()||session.canCreateProject) {
                    item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min=48.dp).testTag("sidebar-projects-toggle").clickable(enabled=session.search.isEmpty()) { session.toggleSidebarProjects() },verticalAlignment=Alignment.CenterVertically) { Text("项目",Modifier.padding(start=9.dp,end=6.dp),fontSize=14.sp,color=colors.onSurfaceVariant); ProductIcon(if(projectsExpanded || session.search.isNotEmpty()) "down" else "chevron",Modifier.size(14.dp),colors.onSurfaceVariant) }
                        if(session.canCreateProject)IconAction("plus","添加项目","add-project",onClick=addProject)
                    } }
                    if(projectsExpanded || session.search.isNotEmpty()) projects.forEach { project ->
                        val expanded=session.projectExpanded(project.id) || session.search.isNotEmpty()
                        item(key="project-${project.id}") { Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=9.dp).testTag("project-${project.id}").semantics { stateDescription=if(expanded) "已展开" else "已折叠" }.clickable(enabled=session.search.isEmpty()) { session.toggleProject(project.id) },verticalAlignment=Alignment.CenterVertically) {
                            ProductIcon("folder")
                            Spacer(Modifier.width(10.dp))
                            Text(project.name,Modifier.weight(1f),maxLines=1,fontSize=16.sp,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            ProductIcon(if(expanded) "down" else "chevron",Modifier.size(12.dp),colors.onSurfaceVariant)
                        } }
                        if(expanded) {
                            val children=session.threads.filter { it.belongs(project) && (matches(it.title) || matches(project.name)) }
                            val all=project.id in expandedLists || session.search.isNotEmpty()
                            items(if(all) children else children.take(5),key={ "project-${project.id}-${it.id}" }) { thread -> Box(Modifier.padding(start=24.dp)) { ThreadRow(thread,session,"project-thread-${project.id}",close) } }
                            if(children.size>5 && session.search.isEmpty()) item { TextButton(onClick={ session.toggleSidebarList(project.id) },modifier=Modifier.padding(start=24.dp).testTag("project-more-${project.id}")) { Text(if(all) "收起显示" else "展开显示",color=colors.onSurfaceVariant) } }
                            if(children.isEmpty()) item { Text("项目中还没有对话",Modifier.padding(start=33.dp,top=8.dp,bottom=8.dp),fontSize=12.sp,color=colors.onSurfaceVariant) }
                        }
                    }
                }
                if(projects.isEmpty() && recent.isEmpty()) item { Text(if(session.search.isEmpty()) "还没有项目或对话" else "没有找到相关内容",Modifier.padding(vertical=24.dp,horizontal=9.dp),fontSize=14.sp,color=colors.onSurfaceVariant) }
                if(session.workspace?.nextProjectCursor != null || session.workspace?.nextThreadCursor != null) item { TextButton(onClick={ session.refresh(more=true,userInitiated=true) },enabled=!session.loading,modifier=Modifier.testTag("sidebar-load-more")) { Text("加载更多",color=colors.onSurfaceVariant) } }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        LaunchedEffect(list.isScrollInProgress) { if(list.isScrollInProgress) focus.clearFocus() }
        HorizontalDivider(color=colors.onSurface.copy(alpha=.08f))
        Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min=48.dp).testTag("sidebar-settings").semantics { role=Role.Button }.clickable { close(); settings() },verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(colors.onSurface),contentAlignment=Alignment.Center) { Text("我",fontSize=12.sp,color=colors.surface) }
                Spacer(Modifier.width(10.dp))
                Text("个人空间",fontSize=16.sp,color=colors.onSurface)
            }
            IconAction("sidebar","收起侧栏","close-sidebar",onClick=close)
        }
    }
}
@Composable private fun ThreadRow(thread: MobileThread,session: MobileSession,prefix: String,close: () -> Unit) {
    val colors=MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(10.dp)).background(if(thread.id==session.selectedThread) colors.onSurface.copy(alpha=.06f) else Color.Transparent).testTag("$prefix-${thread.id}").semantics { contentDescription="${thread.title}，${when(thread.state) { "running"->"运行中"; "waiting-for-approval"->"待处理"; else->if(thread.showsUnread) "未读" else "已读" }}" }.clickable { session.selectThread(thread); close() }.padding(horizontal=9.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(thread.title,Modifier.weight(1f),fontSize=16.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        when { thread.state=="running" -> CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp)
            thread.state=="waiting-for-approval" -> Text("待处理",fontSize=11.sp,color=Color(0xFFD68B00))
            thread.showsUnread -> Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF007AFF)).testTag("unread-${thread.id}")) }
    }
}
