package com.agenthub.android
/** Reserved app scope IDs own these captions; Provider display names remain literal. */
internal fun NativeL10n.sharingModelCaption(scope: AISharingScope,rawName: String)=when(scope.model) {
    "codex-dictation"->tr("Codex 语音转写")
    "follow-computer"->tr("跟随电脑端配置")
    else->rawName
}
