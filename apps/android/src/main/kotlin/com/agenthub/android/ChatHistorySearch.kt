package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenthub.core.*
import kotlinx.coroutines.*

internal fun searchHighlighted(text: String,query: String,color: Color)=buildAnnotatedString {
    append(text)
    ChatSearchRules.ranges(text,query,NativeChatSearchText).forEach {
        addStyle(SpanStyle(background=Color.Yellow.copy(alpha=.45f),color=color),it.start,it.end)
    }
}

@Composable internal fun ChatHistorySearchPage(session: MobileSession,close: ()->Unit) {
    val context=remember(session.targetKey) { session.captureChatSearch() }
    val current=context?.let(session::chatSearchCurrent)==true
    var query by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(session.messages) }
    var historyVersion by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<MobileMessage>>(emptyList()) }
    var resultQuery by remember { mutableStateOf("") }
    var resultVersion by remember { mutableStateOf("") }
    val resultsCurrent=ChatSearchRules.resultsCurrent(resultQuery,query,resultVersion,historyVersion)
    val colors=MaterialTheme.colorScheme
    val keyboard=LocalSoftwareKeyboardController.current
    val view=LocalView.current
    val nativeKeyboard=remember(view) { androidx.core.view.SoftwareKeyboardControllerCompat(view) }
    val resultsFocus=remember { FocusRequester() }
    LaunchedEffect(current) { if(!current)close() }
    LaunchedEffect(context,reload) {
        if(context==null)return@LaunchedEffect
        loading=true;failed=false;history=session.messages;historyVersion=java.util.UUID.randomUUID().toString()
        if(!context.client.isDemo&&!session.selectedClient.online) { loading=false;failed=true;return@LaunchedEffect }
        try { history=session.completeChatSearchHistory(context);historyVersion=java.util.UUID.randomUUID().toString();loading=false }
        catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { if(session.chatSearchCurrent(context)) { failed=true;history=session.messages;historyVersion=java.util.UUID.randomUUID().toString();loading=false } }
    }
    LaunchedEffect(query,historyVersion) {
        val text=ChatSearchRules.query(query);val source=history
        val version=historyVersion
        results=if(text.isEmpty())emptyList() else withContext(Dispatchers.Default) {
            source.filter { ensureActive();ChatSearchRules.matches(it.searchableText(),text,NativeChatSearchText) }
        }
        resultQuery=text;resultVersion=version
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp).background(colors.background,RoundedCornerShape(12.dp)).padding(12.dp)) {
            ProductIcon("search",Modifier.size(18.dp),colors.onSurfaceVariant)
            BasicTextField(query,{ query=it },Modifier.weight(1f).padding(start=8.dp).testTag("chat-search-query"),singleLine=true,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={ resultsFocus.requestFocus();keyboard?.hide();nativeKeyboard.hide() }),
                textStyle=androidx.compose.ui.text.TextStyle(color=colors.onSurface,fontSize=17.sp),
                decorationBox={ inner -> if(query.isEmpty())Text("搜索消息、工具输出或附件名称",fontSize=14.sp,color=colors.onSurfaceVariant);inner() })
            if(query.isNotEmpty())IconAction("close","清除搜索","chat-search-clear") { query="" }
        }
        LazyColumn(Modifier.fillMaxSize().focusRequester(resultsFocus).focusable().testTag("chat-search-results"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(loading)item { Row { CircularProgressIndicator(Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("正在读取完整历史…") } }
            if(failed)item {
                Column { Text("无法读取完整历史。可搜索已缓存内容；连接恢复后重试。",color=colors.onSurfaceVariant)
                    TextButton(onClick={ reload++ },modifier=Modifier.testTag("chat-search-retry")) { Text("重试") } }
            }
            if(!resultsCurrent&&ChatSearchRules.query(query).isNotEmpty())item { CircularProgressIndicator(Modifier.size(18.dp)) }
            if(!loading&&!failed&&resultsCurrent&&ChatSearchRules.query(query).isNotEmpty()&&results.isEmpty())item { Text("没有匹配结果",Modifier.testTag("chat-search-empty"),color=colors.onSurfaceVariant) }
            items(if(resultsCurrent)results else emptyList(),key={ it.id }) { message ->
                Column(Modifier.fillMaxWidth().testTag("chat-search-result-${message.id}").clickable {
                    if(context!=null&&session.chatSearchCurrent(context)&&ChatSearchRules.resultsCurrent(resultQuery,query,resultVersion,historyVersion)) {
                        session.prepareChatSearchSelection(context,message,resultQuery,history,!failed&&!loading);close()
                    }
                }.padding(vertical=8.dp)) {
                    Text(when(message.role) { "tool"->"工具输出";"user"->"你";else->"回复" },fontSize=12.sp,color=colors.onSurfaceVariant)
                    val snippet=ChatSearchRules.snippet(message.searchableText(),query,NativeChatSearchText)
                    Text(searchHighlighted(snippet.text,query,colors.onSurface),Modifier.padding(top=4.dp),maxLines=4,fontSize=16.sp)
                }
            }
        }
    }
}

@Composable internal fun LocatedChatMessage(selection: MobileChatSearchSelection) {
    val colors=MaterialTheme.colorScheme
    val text=remember(selection.token) { selection.message.searchableText() }
    val snippet=remember(selection.token) { ChatSearchRules.snippet(text,selection.query,NativeChatSearchText) }
    var expanded by remember(selection.token) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("chat-search-located-${selection.message.id}")
        .background(Color.Yellow.copy(alpha=.08f),RoundedCornerShape(10.dp)).border(1.dp,Color(0xFFCC8800),RoundedCornerShape(10.dp)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("搜索匹配",fontSize=12.sp,color=colors.onSurfaceVariant)
        SelectionContainer { Text(searchHighlighted(snippet.text,selection.query,colors.onSurface),fontSize=16.sp) }
        if(ChatSearchRules.longMessage(text,NativeChatSearchText)) {
            TextButton(onClick={ expanded=!expanded },modifier=Modifier.testTag("chat-search-full-message")) { Text(if(expanded)"收起完整消息" else "完整消息") }
            if(expanded)SelectionContainer { Text(searchHighlighted(text,selection.query,colors.onSurface),Modifier.testTag("chat-search-full-text"),fontSize=16.sp) }
        }
    }
}
