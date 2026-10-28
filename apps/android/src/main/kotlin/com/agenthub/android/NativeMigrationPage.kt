package com.agenthub.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Always compose in the Activity root so registry result correlation survives sheets/Session replacement. */
@Composable internal fun NativeMigrationSystemHost(controller: NativeMigrationController) {
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->controller.completePicker(NativeMigrationPickerKind.EXPORT,uri)}
    val importPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->controller.completePicker(NativeMigrationPickerKind.IMPORT,uri)}
    val request=controller.picker
    LaunchedEffect(controller,request?.id,request?.launched) {
        val next=controller.claimPicker()?:return@LaunchedEffect
        try {when(next.kind){NativeMigrationPickerKind.EXPORT->export.launch("AgentHub.codexmigration");NativeMigrationPickerKind.IMPORT->importPicker.launch(arrayOf("application/octet-stream","application/json"))}}
        catch(_: Exception){controller.pickerFailed(next.id)}
    }
}

@Composable internal fun NativeMigrationSettingsEntry(controller: NativeMigrationController?) {
    GroupTitle("")
    GroupCard {SettingsRow(productText("安全迁移"),"","settings-migration",icon="shield"){controller?.show()}}
}

/** App-authored copy uses the existing catalog; dynamic values are format arguments only. */
@Composable internal fun NativeMigrationPage(controller: NativeMigrationController?) {
    val text=currentProductText()
    if(controller==null){Text(productText("迁移尚未就绪。"),Modifier.padding(16.dp));return}
    val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("migration-content"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(controller.password,{controller.password=it},enabled=controller.ready&&controller.picker==null,
            label={Text(productText("迁移密码（至少 12 个字符）"))},singleLine=true,visualTransformation=PasswordVisualTransformation(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("migration-password"))
        Button(onClick=controller::export,enabled=controller.ready&&controller.passwordValid,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(min=48.dp).testTag("migration-export")) {
            Text(productText("导出加密迁移文件"))
        }
        OutlinedButton(onClick={controller.chooseImport()},enabled=controller.canImport&&controller.passwordValid,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(min=48.dp).testTag("migration-import")) {
            Text(productText("导入加密迁移文件"))
        }
        Text(productText("妥善保存迁移文件和密码。文件只写入你选择的位置；导入适用于尚未添加真实电脑的新安装，通知需另行明确开启。"),Modifier.padding(horizontal=16.dp),color=colors.onSurfaceVariant)
        if(controller.busy)Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp));Text(text.tr(when(controller.phase){NativeMigrationPhase.RESTORING->"正在恢复本机资料…";NativeMigrationPhase.APPLYING->"正在导入…";NativeMigrationPhase.STAGING->"正在核对迁移文件…";else->"正在准备…"}))
        }
        controller.preview?.let {value->
            Card(Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("migration-preview")) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(productText("核对待导入资料"),style=MaterialTheme.typography.titleMedium)
                Text(productText("%@ 台电脑 · %@ 个草稿 · %@ 条操作与回执 · %@ 个附件",value.computers,value.drafts,value.operations,value.attachments))
                if(value.notificationArchives>0)Text(productText("保留原通知记录供核对，不恢复原安装授权。"))
                Button(onClick=controller::confirmImport,enabled=controller.canImport&&controller.phase==NativeMigrationPhase.PREVIEW,
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("migration-confirm")){Text(productText("确认导入"))}
            }}
        }
        controller.result?.let {result->
            val message=when(result) {
                NativeMigrationResult.SAVED->"加密文件已保存。请核对文件和密码可用。"
                NativeMigrationResult.CANCELLED->"已取消；原资料未更改，已核对的待导入资料仍保留。"
                NativeMigrationResult.READY->"文件已核对，确认后才会导入。"
                NativeMigrationResult.IMPORTED,NativeMigrationResult.PREVIOUS_IMPORT->"连接、草稿和回执已恢复。通知需另行明确开启。"
                NativeMigrationResult.INVALID->"密码错误、文件损坏或资料暂不支持；原数据未更改。"
                NativeMigrationResult.UNSUPPORTED->"本机有尚未支持或未完成的资料，原内容已保留。"
                NativeMigrationResult.NEW_INSTALLATION->"请在尚未添加真实电脑的新安装中导入迁移文件，现有连接未更改。"
                NativeMigrationResult.RESTORE_FAILED->"本机恢复尚未完成，请继续核对；不会重复导入。"
                NativeMigrationResult.UNKNOWN->"保存结果待核对，原候选和回执已保留。"
                NativeMigrationResult.NOT_READY->"请等待本机资料恢复完成。"
                NativeMigrationResult.SAVE_FAILED->"迁移未完成，原本机资料已保留。"
            }
            Text(text.tr(message),Modifier.padding(horizontal=16.dp).testTag("migration-result"),color=colors.onSurfaceVariant)
        }
        if(controller.phase==NativeMigrationPhase.UNKNOWN)OutlinedButton(controller::checkUnknown,modifier=Modifier.padding(horizontal=16.dp).heightIn(min=48.dp).testTag("migration-check-status")){Text(productText("核对原结果"))}
        if(controller.needsOwnerRestore)OutlinedButton(controller::resumeOwner,modifier=Modifier.padding(horizontal=16.dp).heightIn(min=48.dp).testTag("migration-resume")){Text(productText("继续恢复本机"))}
        TextButton(controller::cancel,enabled=controller.phase!=NativeMigrationPhase.UNKNOWN&&controller.phase!=NativeMigrationPhase.RESTORING,
            modifier=Modifier.padding(horizontal=16.dp).heightIn(min=48.dp).testTag("migration-cancel")){Text(productText("取消"))}
        Spacer(Modifier.height(24.dp))
    }
    if(controller.replaceConfirmation)AlertDialog(onDismissRequest=controller::declineReplacement,
        title={Text(productText("更换待导入资料？"))},text={Text(productText("另选文件会更换本次待导入资料，原文件和本机资料不变。"))},
        confirmButton={TextButton({controller.chooseImport(true)}){Text(productText("另选文件"))}},dismissButton={TextButton(controller::declineReplacement){Text(productText("取消"))}})
}
