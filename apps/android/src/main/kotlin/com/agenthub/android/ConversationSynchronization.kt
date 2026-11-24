package com.agenthub.android

internal data class ConversationSyncIssue(val message: String,val historyFresh: Boolean=false,val liveWindow: Boolean=false,val readOnly: Boolean=false) {
    val title: String get()=when {
        readOnly&&historyFresh->"仅可查看历史"
        readOnly->"此对话暂不可用"
        liveWindow->"实时进度已同步"
        historyFresh->"实时状态暂不可用"
        else->"对话同步失败"
    }
}

internal fun MobileHistory.readOnlyIssue(): ConversationSyncIssue? {
    val reason=threadControlRestriction ?: return null
    val message=when(reason) {
        "project-not-authorized"->"此项目尚未开放手机控制。请在电脑端为该项目开启授权。"
        "project-directory-unavailable"->"电脑上的项目目录已不可用。请检查该目录。"
        else->"电脑暂未开放此对话的手机控制。"
    }
    return ConversationSyncIssue(message,historyFresh=synchronization?.state=="history",readOnly=true)
}

internal fun conversationReadFailure(failure: Throwable): String =
    NativeOperationFailure.describe(failure,"未能取得此对话的最新进度，当前显示缓存。正在自动重试。").message
