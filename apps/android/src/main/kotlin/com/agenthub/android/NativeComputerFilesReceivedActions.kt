package com.agenthub.android

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString

/** A row owns its errors. A retired row cannot launch, copy, or accept late failure. */
internal class NativeReceivedFileActionState(val resource:MobileSession.PreparedMessageResource,private val current:()->Boolean) {
    private var retired=false
    val isCurrent get()=!retired&&current()
    var failed by mutableStateOf(false);private set
    fun retire(){retired=true;failed=false}
    fun external(share:Boolean,title:String,launch:(MobileSession.PreparedMessageResource,Boolean,String)->Unit) {
        if(!isCurrent)return
        failed=false
        try {launch(resource,share,title)}
        catch(_:android.content.ActivityNotFoundException){failure()}
        catch(_:SecurityException){failure()}
        catch(_:IllegalArgumentException){failure()}
    }
    private fun failure(){if(isCurrent)failed=true}
    fun copyPath(copy:(String)->Unit){if(isCurrent)copy(resource.local.path)}
}

@Composable internal fun NativeReceivedFileActions(resource:MobileSession.PreparedMessageResource,id:String,current:()->Boolean,enabled:Boolean=true,
    launch:((MobileSession.PreparedMessageResource,Boolean,String)->Unit)?=null) {
    val context=LocalContext.current;val clipboard=LocalClipboardManager.current;val t=currentProductText()
    val currentAction by rememberUpdatedState(current)
    val state=remember(resource){NativeReceivedFileActionState(resource){currentAction()}}
    DisposableEffect(state){onDispose {state.retire()}}
    LaunchedEffect(state,currentAction()){if(!currentAction())state.retire()}
    Row {
        for(share in listOf(false,true))TextButton(onClick={
            if(enabled)state.external(share,t.tr("保存或分享文件"),launch?:{file,sharing,title->NativeResourceIntents.launch(context,file,sharing,title)})
        },enabled=state.isCurrent&&enabled,modifier=Modifier.testTag((if(share)"share" else "open")+"-received-$id")) {
            Text(t.tr(if(share)"分享" else "打开"))
        }
        TextButton(onClick={state.copyPath {clipboard.setText(AnnotatedString(it))}},enabled=state.isCurrent,
            modifier=Modifier.testTag("copy-received-$id")) {Text(t.tr("复制保存路径"))}
    }
    if(state.isCurrent&&state.failed)Text(t.tr("手机上没有可打开此链接的应用。"),
        modifier=Modifier.testTag("received-file-open-error-$id"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
}
