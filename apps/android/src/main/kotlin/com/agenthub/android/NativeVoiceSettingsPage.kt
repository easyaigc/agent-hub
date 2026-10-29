package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun voiceProviderTitle(value: VoiceInputController.Provider)=if(value==VoiceInputController.Provider.DESKTOP)"电脑端" else "系统听写"

@Composable internal fun NativeVoiceSettingsEntry(session: MobileSession,navigate: (String)->Unit) {
    GroupTitle(productText("语音输入"))
    GroupCard { SettingsRow(productText("默认转写"),productText(voiceProviderTitle(session.voiceSettings.defaultProvider)),"voice-transcription-picker") { navigate("voice") } }
}

@Composable internal fun NativeVoiceSettingsPage(session: MobileSession) {
    val settings=session.voiceSettings;val colors=MaterialTheme.colorScheme
    LaunchedEffect(settings) { settings.refreshCapabilities() }
    DisposableEffect(settings) { onDispose { settings.leave() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("voice-settings-content")) {
        GroupTitle(productText("默认转写"))
        GroupCard { Column(Modifier.selectableGroup().testTag("voice-provider-group")) { VoiceInputController.Provider.entries.forEach { provider ->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("voice-provider-"+provider.name.lowercase())
                .selectable(settings.defaultProvider==provider,role=Role.RadioButton) { settings.select(provider) }.padding(horizontal=16.dp,vertical=10.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Text(productText(voiceProviderTitle(provider)),Modifier.weight(1f),fontSize=17.sp)
                if(settings.defaultProvider==provider)ProductIcon("check",Modifier.size(18.dp))
            }
        } } }
        Text(productText("优先使用所选方式。手机系统语音服务可能需要联网；不会自动改用其他服务。"),Modifier.fillMaxWidth().padding(horizontal=32.dp,vertical=8.dp),fontSize=12.sp,lineHeight=16.sp,color=colors.onSurfaceVariant)
        val preferenceCopy=when(settings.preferenceStatus) {
            NativeVoiceSettings.PreferenceStatus.LOADING->"正在读取语音设置…"
            NativeVoiceSettings.PreferenceStatus.SAVING->"正在保存语音设置…"
            NativeVoiceSettings.PreferenceStatus.FAILED->if(settings.preferenceError==NativeVoiceSettings.PreferenceFailure.READ)"无法读取语音设置，本次选择仍生效。" else "无法保存语音设置，本次选择仍生效。"
            else->"此选择仅在本次打开期间生效。"
        }
        if(!settings.persisted)Text(productText(preferenceCopy),Modifier.fillMaxWidth().padding(horizontal=32.dp,vertical=8.dp).testTag("voice-preference-persistence").semantics { liveRegion=LiveRegionMode.Polite },fontSize=12.sp,lineHeight=16.sp,color=colors.onSurfaceVariant)
        if(settings.preferenceBound && settings.preferenceStatus==NativeVoiceSettings.PreferenceStatus.FAILED)TextButton(onClick={settings.retryPreferences()},
            modifier=Modifier.padding(horizontal=24.dp).heightIn(min=48.dp).testTag("voice-preference-retry")) { Text(productText("恢复语音设置")) }
        GroupTitle(productText("手机语音能力"))
        val capability=settings.capabilities
        GroupCard {
            Text(productText(when(capability?.route) {
                NativeVoiceCapability.Route.ON_DEVICE->"已发现手机端识别服务"
                NativeVoiceCapability.Route.SYSTEM_SERVICE->"已发现系统语音服务，可能需要联网"
                NativeVoiceCapability.Route.UNAVAILABLE->"未发现可用的系统语音服务"
                else->"系统语音服务能力尚未确认"
            }),Modifier.padding(16.dp).testTag("voice-service-capability"),fontSize=15.sp)
            Text(productText(when(capability?.language) {
                NativeVoiceCapability.Language.INSTALLED->"当前语言的手机端模型已安装"
                NativeVoiceCapability.Language.DOWNLOAD_REQUIRED->"当前语言需要先在系统中下载"
                NativeVoiceCapability.Language.ONLINE->"当前语言由联网服务提供"
                NativeVoiceCapability.Language.UNSUPPORTED->"系统未报告支持当前语言"
                else->"当前语言支持情况尚未确认"
            }),Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("voice-language-capability"),fontSize=12.sp,lineHeight=16.sp,color=colors.onSurfaceVariant)
            TextButton(onClick={ settings.refreshCapabilities() },enabled=!settings.checking,modifier=Modifier.heightIn(min=48.dp).testTag("voice-capability-refresh")) { Text(productText("刷新能力")) }
        }
        Text(productText("可在系统设置中检查语音服务和语言支持。能力检查不会打开麦克风或下载模型。"),Modifier.fillMaxWidth().padding(horizontal=32.dp,vertical=8.dp),fontSize=12.sp,lineHeight=16.sp,color=colors.onSurfaceVariant)
        if(capability?.canTranscribeSavedRecording!=true)Text(productText("重新录音的可用性取决于系统语音服务。可在原录音恢复面板检查离线识别。"),
            Modifier.fillMaxWidth().padding(horizontal=32.dp,vertical=8.dp).testTag("voice-saved-recording-capability"),fontSize=12.sp,lineHeight=16.sp,color=colors.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}
