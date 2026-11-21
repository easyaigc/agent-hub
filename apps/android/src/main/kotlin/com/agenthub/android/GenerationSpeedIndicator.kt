package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Mirrors iOS: zero/one/two bolts; sliders only for a non-accelerated icon-only entry. */
@Composable internal fun GenerationSpeedIndicator(speed: String?, color: Color, enabled: Boolean, compact: Boolean) {
    val bolts=when(speed) { "fast"->1; "ultrafast"->2; else->0 }
    if(bolts==0 && !compact) return
    val tint=color.copy(alpha=if(enabled) 1f else .5f)
    Row(Modifier.testTag("generation-speed-indicator"),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(1.dp)) {
        if(bolts==0) ProductIcon("sliders",Modifier.size(14.dp).testTag("generation-speed-fallback"),tint)
        repeat(bolts) { index ->
            ProductIcon("bolt",Modifier.size(14.dp).testTag("generation-speed-bolt-$index"),tint)
        }
    }
}
