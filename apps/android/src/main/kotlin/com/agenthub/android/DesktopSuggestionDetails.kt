package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Native selectable text and bounded scrolling controls, matching the iOS suggestion sheet. */
@Composable internal fun DesktopSuggestionDetails(action: MobileSuggestionAction,canApply: Boolean,
    close: ()->Unit,apply: (String)->Unit,ignore: ()->Unit) {
    var replacement by remember(action) { mutableStateOf(false) }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.88f).testTag("suggestion-preview"),shape=MaterialTheme.shapes.large) {
            BoxWithConstraints {
                val buttonHeight=maxHeight*.48f
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(productText("下一步建议"),Modifier.weight(1f).padding(vertical=12.dp),fontSize=18.sp)
                        TextButton(onClick=close,modifier=Modifier.testTag("suggestion-details-close")) { Text(productText("完成")) }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("suggestion-body")) {
                        Text(productText("来自桌面的建议"),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
                        SelectionContainer { Text(action.suggestion.text,Modifier.fillMaxWidth().padding(vertical=14.dp).testTag("suggestion-full-text"),fontSize=16.sp) }
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max=buttonHeight).verticalScroll(rememberScrollState()).testTag("suggestion-actions")) {
                        Button(onClick={ apply(if(action.draft.text.isEmpty())"fill" else "append") },enabled=canApply,
                            colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.onSurface,contentColor=MaterialTheme.colorScheme.background),
                            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("suggestion-apply")) {
                            Text(productText(if(action.draft.text.isEmpty())"采用到输入框" else "追加到草稿末尾"))
                        }
                        if(action.draft.text.isNotEmpty()) TextButton(onClick={ replacement=true },enabled=canApply,
                            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("suggestion-replace")) { Text(productText("替换现有草稿…")) }
                        TextButton(onClick=ignore,enabled=canApply,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("suggestion-ignore")) { Text(productText("忽略这条建议")) }
                    }
                }
            }
        }
    }
    if(replacement) AlertDialog(onDismissRequest={ replacement=false },title={ Text(productText("替换现有草稿？")) },
        text={ Text(productText("原草稿将被替换，填入后仍可编辑。")) },
        confirmButton={ TextButton(onClick={ replacement=false;apply("replace") },enabled=canApply,modifier=Modifier.testTag("suggestion-confirm-replace")) { Text(productText("确认替换")) } },
        dismissButton={ TextButton(onClick={ replacement=false },modifier=Modifier.testTag("suggestion-cancel-replace")) { Text(productText("保留原草稿")) } })
}
