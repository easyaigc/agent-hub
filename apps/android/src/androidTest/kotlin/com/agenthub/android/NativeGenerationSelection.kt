package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** App-owned native controls only; each requested field becomes an explicit local choice before any send. */
internal fun ComposeTestRule.selectNativeGeneration(model: String,effort: String,speed: String,permission: String) {
    onNodeWithTag("model-picker").performScrollTo().assertIsEnabled().performClick()
    onNodeWithTag("model-$model").performScrollTo().performClick()
    onNodeWithTag("effort-picker").performScrollTo().assertIsEnabled().performClick()
    onNodeWithTag("effort-$effort").performScrollTo().performClick()
    if(onAllNodesWithTag("speed-picker").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("speed-picker").performScrollTo().assertIsEnabled().performClick()
        onNodeWithTag("speed-$speed").performScrollTo().performClick()
    } else {
        require(speed in listOf("standard","fast")) { "This native toggle cannot select an undeclared speed" }
        val toggle=onNodeWithTag("fast-mode-toggle").performScrollTo().assertIsEnabled()
        val wanted=speed=="fast"
        val current=toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState]==ToggleableState.On
        // An already matching effective runtime value is not an explicit draft field.
        // Toggle away and back locally so the original examiner still submits its exact requested speed.
        if(current==wanted) { toggle.performClick();if(wanted)toggle.assertIsOff() else toggle.assertIsOn() }
        toggle.performClick();if(wanted)toggle.assertIsOn() else toggle.assertIsOff()
    }
    onNodeWithTag("permission-$permission").performScrollTo().performClick()
}
