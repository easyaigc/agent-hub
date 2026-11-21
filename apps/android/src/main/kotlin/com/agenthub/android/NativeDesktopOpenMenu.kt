package com.agenthub.android

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable internal fun DesktopOpenMenuItem(session: MobileSession, dismiss: () -> Unit) {
    // Unsupported, offline, archived and unbound targets do not advertise this action.
    if (session.supportsDesktopOpen) DropdownMenuItem(
        text = { Text(productText("在电脑上打开")) },
        enabled = !session.desktopOpenBusy && session.transferStatus == null,
        onClick = { dismiss(); session.openCurrentChatOnDesktop() },
        modifier = Modifier.testTag("chat-open-on-desktop"))
}
