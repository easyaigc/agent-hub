package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** One deadline per original click; polling only observes the real current Semantics. */
private fun ComposeTestRule.readyGenerationControl(tag: String): SemanticsNodeInteraction {
    val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
    fun remaining(): Long = (deadline - android.os.SystemClock.elapsedRealtime()).also {
        check(it > 0) { "Generation control did not become ready within 30 seconds: $tag" }
    }
    fun uniqueEnabled(): Boolean {
        val nodes = onAllNodesWithTag(tag).fetchSemanticsNodes()
        return nodes.size == 1 && !nodes.single().config.contains(SemanticsProperties.Disabled)
    }
    waitUntil(timeoutMillis = remaining()) { uniqueEnabled() }
    val node = onNodeWithTag(tag)
    node.performScrollTo()
    waitUntil(timeoutMillis = remaining()) { uniqueEnabled() && node.isDisplayed() }
    // No click retry. The original enabled assertion remains immediately before one click.
    return node.assertIsEnabled()
}

/** App-owned native controls only; each requested field becomes an explicit local choice before any send. */
internal fun ComposeTestRule.selectNativeGeneration(model: String,effort: String,speed: String,permission: String,observe: ((String) -> Unit)? = null) {
    observe?.invoke("before-model")
    readyGenerationControl("model-picker").performClick()
    readyGenerationControl("model-$model").performClick()
    observe?.invoke("after-model")
    observe?.invoke("before-effort")
    readyGenerationControl("effort-picker").performClick()
    readyGenerationControl("effort-$effort").performClick()
    observe?.invoke("after-effort")
    observe?.invoke("before-speed")
    if(onAllNodesWithTag("speed-picker").fetchSemanticsNodes().isNotEmpty()) {
        readyGenerationControl("speed-picker").performClick()
        readyGenerationControl("speed-$speed").performClick()
    } else {
        require(speed in listOf("standard","fast")) { "This native toggle cannot select an undeclared speed" }
        val toggle=readyGenerationControl("fast-mode-toggle")
        val wanted=speed=="fast"
        val current=toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState]==ToggleableState.On
        // An already matching effective runtime value is not an explicit draft field.
        // Toggle away and back locally so the original examiner still submits its exact requested speed.
        if(current==wanted) { readyGenerationControl("fast-mode-toggle").performClick();if(wanted)toggle.assertIsOff() else toggle.assertIsOn() }
        readyGenerationControl("fast-mode-toggle").performClick();if(wanted)toggle.assertIsOn() else toggle.assertIsOff()
    }
    observe?.invoke("after-speed")
    observe?.invoke("before-permission")
    readyGenerationControl("permission-$permission").performClick()
    observe?.invoke("after-permission")
}
