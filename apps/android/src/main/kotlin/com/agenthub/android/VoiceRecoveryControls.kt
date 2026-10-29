package com.agenthub.android

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** A compound control has one name derived from its visible label. Compose 1.12 exports native
 * node text from the owner's unmerged Text property. Keep the real Material role, selected/disabled
 * state and click implementation; never invent an action or duplicate a contentDescription.
 * The visual Text remains in the Compose tree for selection-free layout inspection, but is not a
 * second accessibility target. Tight intrinsic width shrinks short labels and wraps long labels
 * at the incoming maximum, so the paragraph and the Text box describe the same available width. */
private fun Modifier.voiceRecoveryName(label: String)=semantics(mergeDescendants=true) {text=AnnotatedString(label)}

@Composable private fun VoiceRecoveryLabel(label: String,fontSize: TextUnit=TextUnit.Unspecified) {
    Text(label,Modifier.width(IntrinsicSize.Max).semantics {hideFromAccessibility()},fontSize=fontSize)
}

@Composable internal fun VoiceRecoveryTextButton(label: String,onClick: ()->Unit,modifier: Modifier=Modifier,
    enabled: Boolean=true,colors: ButtonColors=ButtonDefaults.textButtonColors(),fontSize: TextUnit=TextUnit.Unspecified) {
    TextButton(onClick=onClick,enabled=enabled,colors=colors,
        modifier=modifier.heightIn(min=48.dp).voiceRecoveryName(label)) {VoiceRecoveryLabel(label,fontSize)}
}

@Composable internal fun VoiceRecoveryLanguageChip(label: String,selected: Boolean,onClick: ()->Unit,
    modifier: Modifier=Modifier,enabled: Boolean=true) {
    FilterChip(selected=selected,onClick=onClick,enabled=enabled,
        label={VoiceRecoveryLabel(label)},modifier=modifier.heightIn(min=48.dp).voiceRecoveryName(label))
}
