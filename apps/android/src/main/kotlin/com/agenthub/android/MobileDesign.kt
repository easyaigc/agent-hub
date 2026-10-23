// Generated from design/mobile/tokens.json. Edit tokens, then pnpm generate:mobile-design.
package com.agenthub.android

import androidx.compose.ui.graphics.Color

internal object MobileDesign {
    const val floatingRadius = 24f
    const val floatingStrokeWidth = 0.5f
    const val floatingStrokeAlpha = 0.08f
    const val floatingShadowRadius = 8f
    const val floatingShadowYOffset = 2f
    const val floatingShadowAlpha = 0.08f
    const val sidebarMaxWidth = 350f
    const val sidebarOutsideMinWidth = 44f
    const val sidebarBackdropAlpha = 0.22f
    const val composerRadius = 27f
    const val composerHorizontalInset = 12f
    const val composerVerticalInset = 4f
    const val composerRowSpacing = 6f
    const val composerInnerInset = 4f
    const val composerDetailRadius = 16f
    const val composerSettingsHeight = 44f
    const val composerVisibleLinesMin = 1f
    const val composerVisibleLinesMax = 6f
    const val iosIconTouchSize = 44f
    const val androidIconTouchSize = 48f
    const val headerHorizontalInset = 12f
    const val headerTopInset = 1f
    const val headerBottomInset = 9f
    const val clientHeaderMaxWidth = 145f
    const val chatHorizontalInset = 16f
    const val chatTopInset = 10f
    const val chatBottomInset = 12f
    const val userBubbleRadius = 20f
    const val userBubbleHorizontalInset = 14f
    const val userBubbleVerticalInset = 10f
    const val userBubbleAttachmentSpacing = 6f
    const val sidebarHorizontalInset = 10f
    const val sidebarRowInset = 9f
    const val sidebarRowHeight = 44f
    const val sidebarSelectionRadius = 10f
    const val sidebarProjectThreadIndent = 24f
    const val sidebarSearchRadius = 12f
    const val toolDetailRadius = 12f
    const val toolDetailInset = 12f
    const val toolDetailSpacing = 8f
    const val toolOutputMaxHeight = 180f
    const val toolDetailStrokeWidth = 1f
    const val toolDetailStrokeAlpha = 0.1f
    const val markdownBlockSpacing = 8f
    const val codeBlockInset = 12f
    const val codeBlockRadius = 12f
    const val runtimePlanInset = 10f
    const val runtimePlanRadius = 12f
    const val planMessageInset = 12f
    const val planMessageRadius = 16f
    const val planReviewInset = 14f
    const val planReviewRadius = 18f
    const val planReviewSpacing = 12f
    const val approvalInset = 12f
    const val approvalRadius = 12f
    const val approvalSpacing = 8f
    const val approvalFillAlpha = 0.08f
    const val approvalStrokeAlpha = 0.2f
    const val questionInputInset = 10f
    const val questionInputRadius = 10f
    const val asyncQuestionSheetCompactHeight = 380f
    const val asyncQuestionSheetRadius = 24f
    const val receiptInset = 10f
    const val receiptRadius = 10f
    const val queuedReplyRowHeight = 48f
    const val queuedReplyMaxHeight = 144f
    fun userBubble(dark: Boolean): Color = if (dark) Color(0.12f, 0.24f, 0.34f) else Color(0.91f, 0.96f, 1f)
    fun userBubbleText(dark: Boolean): Color = if (dark) Color(0.8f, 0.9f, 1f) else Color(0.14f, 0.36f, 0.57f)
    fun toolDetail(dark: Boolean) = Color(if (dark) 0.12f else 0.95f, if (dark) 0.12f else 0.95f, if (dark) 0.12f else 0.95f)
}
