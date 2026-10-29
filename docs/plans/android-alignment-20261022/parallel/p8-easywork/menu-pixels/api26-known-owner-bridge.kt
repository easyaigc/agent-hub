package com.agenthub.android.acceptanceproposal

import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/** Candidate only, not installed in the native getter.
 * Caller must supply a fresh uniquely owned SemanticsNode within a separately proven deadline.
 * Execute via the scoped timed main dispatcher. No fetchSemanticsNode, registry hook or hidden API.
 * Does not prove global Popup uniqueness; initial bounded owner acquisition remains unresolved.
 */
fun api26PopupFromKnownOwner(owner: SemanticsNode,expectedTag: String,packageName: String): View {
    check(Looper.myLooper()==Looper.getMainLooper())
    check(expectedTag in setOf("connection-preference-automatic","connection-preference-lan-only","connection-preference-relay-only"))
    check(owner.config.getOrNull(SemanticsProperties.TestTag)==expectedTag)
    val compose=checkNotNull(owner.root as? ViewRootForTest).view
    val popup=compose.rootView
    check(popup.javaClass.name=="androidx.compose.ui.window.PopupLayout")
    check(popup.context.packageName==packageName&&popup.isAttachedToWindow&&popup.rootView===popup)
    check((popup.layoutParams as? WindowManager.LayoutParams)?.type==WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL)
    return popup
}
