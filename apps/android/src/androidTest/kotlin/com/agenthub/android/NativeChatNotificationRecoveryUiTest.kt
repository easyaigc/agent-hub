package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.*

/** Owned acceptance Activity only; callbacks are synthetic and never contact a Host or post a notice. */
class NativeChatNotificationRecoveryUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule = NativeTestForeground()
    @get:Rule(order=2) val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val target=NativeChatNoticeTarget("fixture-host","fixture-workspace","desktop-runtime")
    private val gap=NativeChatRecoveryGapOffer(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),1,4,9,"fixture-revision")
    private val accepted=mutableListOf<Pair<NativeChatRecoveryGapOffer,String>>()
    private val disabled=mutableListOf<String>()
    @Before fun prepare() {
        ui.waitUntil(30_000){!ui.activity.session.restoring}
        ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"chat-notification-recovery-ui");owned.session.resetAcceptance()}
    }
    @After fun preserve() {if(::owned.isInitialized)ui.runOnIdle {owned.session.leaveForeground();owned.verifyDefault();check(owned.session.operationRecords.isEmpty())}}
    private fun mount(report:NativeChatNotificationMonitorReport) {
        ui.runOnIdle {ui.activity.setContent {ProductTextProvider("en") {MaterialTheme {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {NativeChatNotificationRecoveryStatus(report,{offer,id->accepted.add(offer to id)},{id->disabled.add(id)})}
        }}}}
    }
    @Test fun gapHasNoAutomaticAcceptance() {
        mount(NativeChatNotificationMonitorReport("feed-gap-acceptance-required",target,gap=gap))
        ui.onNodeWithTag("chat-notification-accept-gap").assertIsDisplayed();ui.runOnIdle {check(accepted.isEmpty())}
    }
    @Test fun acceptedGapRequiresSeparateConfirmationAndStableUuid() {
        mount(NativeChatNotificationMonitorReport("feed-gap-acceptance-required",target,gap=gap))
        ui.onNodeWithTag("chat-notification-accept-gap").performClick();ui.runOnIdle {check(accepted.isEmpty())}
        ui.onNodeWithTag("chat-notification-confirm-gap").performClick()
        ui.onNodeWithTag("chat-notification-accept-gap").assertIsNotEnabled()
        ui.runOnIdle {check(accepted.single().first==gap);NativeChatNotificationWire.uuid(accepted.single().second)}
    }
    @Test fun originalUnknownRemainsVisibleAfterKnownOff() {
        mount(NativeChatNotificationMonitorReport("disabled",target,unconfirmedOperations=1))
        ui.onNodeWithTag("chat-notification-original-unknown").assertIsDisplayed()
        ui.onNodeWithTag("chat-notification-accept-gap").assertDoesNotExist();ui.runOnIdle{check(disabled.isEmpty()&&accepted.isEmpty())}
    }
    @Test fun explicitOffCallbackIsSeparateAndCannotDoubleSubmit() {
        mount(NativeChatNotificationMonitorReport("mutation-unknown-original-query",target,unconfirmedOperations=1))
        ui.onNodeWithTag("chat-notification-explicit-off").performClick();ui.onNodeWithTag("chat-notification-explicit-off").assertIsNotEnabled()
        ui.runOnIdle{check(disabled.size==1);NativeChatNotificationWire.uuid(disabled.single());check(accepted.isEmpty())}
    }
}
