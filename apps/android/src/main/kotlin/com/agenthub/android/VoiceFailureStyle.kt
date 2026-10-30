package com.agenthub.android

import android.app.UiModeManager
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** Explicit Android mapping of the current Apple HIG default Subhead and system orange table.
 * SP follows Android's actual font scaling; it is not a replacement for iOS Dynamic Type curves. */
internal object VoiceFailureStyle {
    val fontSize=15.sp
    val lineHeight=20.sp
    val titleWeight=FontWeight.Medium
    val actionIconSize=17.dp
    val actionHitSize=48.dp
    fun tint(dark: Boolean,increasedContrast: Boolean)=Color(when {
        increasedContrast&&dark->0xFFFFA056
        increasedContrast->0xFFC55300
        dark->0xFFFF9230
        else->0xFFFF8D28
    })
}

/** App-selected light/dark palette; Android 14+ contrast maps high>=0.5 to increased contrast.
 * Older Android has no equivalent public UiMode contrast API, so keeps the default theme tint. */
@Composable internal fun voiceFailureTint(): Color {
    val context=LocalContext.current
    val manager=remember(context){context.getSystemService(UiModeManager::class.java)}
    var contrast by remember(manager){mutableFloatStateOf(if(Build.VERSION.SDK_INT>=34)manager?.contrast ?: 0f else 0f)}
    DisposableEffect(manager) {
        if(Build.VERSION.SDK_INT>=34&&manager!=null) {
            val listener=UiModeManager.ContrastChangeListener {contrast=it}
            manager.addContrastChangeListener(context.mainExecutor,listener)
            onDispose {manager.removeContrastChangeListener(listener)}
        }else onDispose {}
    }
    return VoiceFailureStyle.tint(MaterialTheme.colorScheme.background.luminance()<.5f,contrast>=.5f)
}
