package com.agenthub.android

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun VoicePermission(controller: VoiceInputController?) {
    var launched by remember { mutableStateOf<String?>(null) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted->
        val id=launched;launched=null
        if(id!=null) controller?.permissionResult(id,granted)
    }
    LaunchedEffect(controller?.permissionRequest,launched) {
        val id=controller?.permissionRequest
        if(id!=null && launched==null) { launched=id;permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
}
@Composable internal fun VoiceInputStrip(voice: VoiceInputController,text: String) {
    val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).floatingProduct(MobileDesign.composerRadius).padding(4.dp).testTag("voice-panel"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(text.ifEmpty { "随心输入" },Modifier.fillMaxWidth().padding(horizontal=14.dp).padding(top=10.dp).testTag("dictation-transcript"),fontSize=17.sp,color=if(text.isEmpty()) colors.onSurfaceVariant else colors.onSurface,maxLines=6)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
            Box(Modifier.background(colors.onSurface.copy(alpha=0.06f),CircleShape)) { IconAction("close",if(voice.phase in listOf(VoiceInputController.Phase.LISTENING,VoiceInputController.Phase.REQUESTING)) "取消录音" else "结束等待","cancel-dictation",onClick=voice::cancel) }
            Canvas(Modifier.weight(1f).height(44.dp).testTag("dictation-waveform").semantics { contentDescription=voice.status }) {
                val count=maxOf(1,(size.width/8.dp.toPx()).toInt());val center=count/2
                repeat(count) { index->
                    val sample=index-center+voice.levels.size/2;val bar=sample in voice.levels.indices
                    val height=if(bar) (4+voice.levels[sample].coerceIn(0f,1f)*32).dp.toPx() else 3.dp.toPx()
                    drawRoundRect(colors.onSurfaceVariant.copy(alpha=if(bar) 0.85f else 0.4f),Offset((index+0.5f)*size.width/count-1.5.dp.toPx(),(size.height-height)/2),Size(3.dp.toPx(),height),CornerRadius(1.5.dp.toPx()))
                }
            }
            Box(Modifier.background(colors.onSurface.copy(alpha=0.06f),CircleShape)) { IconAction(if(voice.phase==VoiceInputController.Phase.FAILED) "refresh" else "stop",if(voice.phase==VoiceInputController.Phase.FAILED) "重试转写" else "结束录音并转写","stop-dictation",voice.phase in listOf(VoiceInputController.Phase.LISTENING,VoiceInputController.Phase.FAILED),voice::finish) }
        }
        Text(voice.status,Modifier.padding(horizontal=14.dp).testTag("voice-status"),fontSize=12.sp,color=colors.onSurfaceVariant)
        if(voice.phase==VoiceInputController.Phase.FAILED) TextButton(onClick=voice::rerecord,modifier=Modifier.padding(horizontal=14.dp).heightIn(min=48.dp).testTag("voice-record-again")) { Text("重新录音",fontSize=17.sp,color=Color(0xFF007AFF)) }
    }
}
