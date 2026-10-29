package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag

/** Only fills this non-secret answer draft. Approval submission remains a separate explicit action. */
@Composable internal fun NativeQuestionVoice(session: MobileSession,approval: MobileApproval,question: MobileQuestion,
    destination: DraftDestination,value: String,change: (String)->Unit) {
    if(question.secret || approval.kind!="question")return
    val owner=session.questionVoiceIdentity(approval,question,destination)
    val text by rememberUpdatedState(value);val update by rememberUpdatedState(change)
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    DisposableEffect(owner,approval) { onDispose { session.endQuestionVoice(owner) } }
    val voice=session.questionVoice
    if(session.questionVoiceKey==owner && voice?.active==true) {
        Column(Modifier.fillMaxWidth().testTag("question-voice-panel-${question.id}")) {
            VoiceInputStrip(voice,voice.capturedText ?: value)
        }
    }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
        IconAction("refresh",productText("恢复原录音"),"question-voice-restore-${question.id}",session.canBeginQuestionVoice(approval,question,destination)) {
            focus.clearFocus();keyboard?.hide();session.beginQuestionVoice(approval,question,destination,{text},{update(it)},recoverSavedFile=true)
        }
        IconAction("mic",productText("语音输入回复"),"question-voice-${question.id}",session.canBeginQuestionVoice(approval,question,destination)) {
            focus.clearFocus();keyboard?.hide();session.beginQuestionVoice(approval,question,destination,{text},{update(it)})
        }
    }
}
