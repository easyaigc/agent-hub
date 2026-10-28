package com.agenthub.android

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag as semanticTestTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

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
    val status=voice.statusCopyKey?.let {productText(it)} ?: voice.status
    val stopHint=productText("轻触停止录音")
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).floatingProduct(MobileDesign.composerRadius).padding(4.dp).testTag("voice-panel"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        val displayedText=if(voice.transcript.isEmpty())text else text+if(text.isEmpty() || text.last().isWhitespace())voice.transcript else "\n"+voice.transcript
        Text(if(displayedText.isEmpty())productText("随心输入") else displayedText,Modifier.fillMaxWidth().padding(horizontal=14.dp).padding(top=10.dp).testTag("dictation-transcript"),fontSize=17.sp,color=if(displayedText.isEmpty()) colors.onSurfaceVariant else colors.onSurface,maxLines=6)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
            Box(Modifier.background(colors.onSurface.copy(alpha=0.06f),CircleShape)) { IconAction("close",productText(if(voice.phase in listOf(VoiceInputController.Phase.LISTENING,VoiceInputController.Phase.REQUESTING)) "取消录音" else "结束等待"),"cancel-dictation",onClick=voice::cancel) }
            if(voice.phase==VoiceInputController.Phase.FINISHING) {
                VoiceTranscriptionActivity(status,Modifier.weight(1f))
            } else if(voice.phase==VoiceInputController.Phase.LISTENING) {
                VoiceListeningActivity(voice.levels,status,stopHint,voice::finish,Modifier.weight(1f))
            } else VoiceWaveform(voice.levels,Modifier.weight(1f).testTag("dictation-waveform").semantics { contentDescription=status })
            Box(Modifier.background(colors.onSurface.copy(alpha=0.06f),CircleShape)) {
                if(voice.phase==VoiceInputController.Phase.FINISHING) {
                    IconButton(onClick=voice::finish,enabled=false,modifier=Modifier.size(48.dp).testTag("stop-dictation").semantics { contentDescription=status }) {
                        CircularProgressIndicator(Modifier.size(18.dp),color=colors.onSurfaceVariant,strokeWidth=2.dp)
                    }
                } else IconAction(if(voice.phase==VoiceInputController.Phase.FAILED) "refresh" else "stop",productText(if(voice.phase==VoiceInputController.Phase.FAILED) "重试转写" else "结束录音并转写"),"stop-dictation",voice.phase==VoiceInputController.Phase.LISTENING || voice.canRetry,voice::finish)
            }
        }
        if(voice.phase!=VoiceInputController.Phase.FINISHING) Text(status,Modifier.padding(horizontal=14.dp).testTag("voice-status"),fontSize=12.sp,color=colors.onSurfaceVariant)
        if(voice.phase==VoiceInputController.Phase.FAILED) TextButton(onClick=voice::rerecord,modifier=Modifier.padding(horizontal=14.dp).heightIn(min=48.dp).testTag("voice-record-again")) { Text(productText("重新录音"),fontSize=17.sp,color=Color(0xFF007AFF)) }
    }
}

/** N067/N068: central stop action, mirrored side meters, one accessible button. */
@Composable internal fun VoiceListeningActivity(levels: List<Float>,status: String,stopHint: String,onStop: ()->Unit,modifier: Modifier=Modifier) {
    Row(modifier.heightIn(min=48.dp).testTag("dictation-waveform")
        .clickable(role=Role.Button,onClick=onStop)
        .semantics { contentDescription=stopHint;stateDescription=status },
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        VoiceWaveform(levels,Modifier.weight(1f).clearAndSetSemantics { semanticTestTag="dictation-waveform-leading" })
        Text(stopHint,Modifier.clearAndSetSemantics { semanticTestTag="dictation-stop-hint" },fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
        VoiceWaveform(levels,Modifier.weight(1f).clearAndSetSemantics { semanticTestTag="dictation-waveform-trailing" },mirrored=true)
    }
}
@Composable private fun VoiceWaveform(levels: List<Float>,modifier: Modifier=Modifier,mirrored: Boolean=false) {
    val color=MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.height(44.dp)) {
        val count=maxOf(1,(size.width/8.dp.toPx()).toInt());val center=count/2
        repeat(count) { index->
            val sample=index-center+levels.size/2;val bar=sample in levels.indices
            val height=if(bar) (4+levels[sample].coerceIn(0f,1f)*32).dp.toPx() else 3.dp.toPx()
            val x=(index+0.5f)*size.width/count
            drawRoundRect(color.copy(alpha=if(bar) 0.85f else 0.4f),Offset((if(mirrored) size.width-x else x)-1.5.dp.toPx(),(size.height-height)/2),Size(3.dp.toPx(),height),CornerRadius(1.5.dp.toPx()))
        }
    }
}

@Composable internal fun VoiceTranscriptionActivity(status: String,modifier: Modifier=Modifier) {
    // Compose's animation clock respects the system animator duration scale.
    val transition=rememberInfiniteTransition(label="transcription")
    val phase by transition.animateFloat(0f,(Math.PI*2).toFloat(),infiniteRepeatable(tween(1200,easing=LinearEasing)),label="transcription-pulse")
    val color=MaterialTheme.colorScheme.onSurfaceVariant
    val pulse: @Composable (Modifier,Boolean)->Unit = { pulseModifier,mirrored->
        Canvas(pulseModifier.height(20.dp).clearAndSetSemantics {}) {
            val count=maxOf(1,minOf(12,(size.width/6.dp.toPx()).toInt()))
            val spacing=minOf(6.dp.toPx(),size.width/count)
            repeat(count) { index->
                val wave=(sin(phase-index*0.6f)+1)/2
                val strength=wave*wave
                val height=(3+12*strength).dp.toPx()
                val offset=(index-(count-1)/2f)*spacing
                val x=size.width/2+if(mirrored) -offset else offset
                drawRoundRect(color.copy(alpha=0.3f+0.5f*strength),Offset(x-1.5.dp.toPx(),(size.height-height)/2),Size(3.dp.toPx(),height),CornerRadius(1.5.dp.toPx()))
            }
        }
    }
    Row(modifier.heightIn(min=44.dp).testTag("dictation-waveform").clearAndSetSemantics { contentDescription=status },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        pulse(Modifier.weight(1f),false)
        Text(status,fontSize=12.sp,color=color,textAlign=TextAlign.Center)
        pulse(Modifier.weight(1f),true)
    }
}
