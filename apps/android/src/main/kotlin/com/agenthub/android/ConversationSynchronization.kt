package com.agenthub.android

internal data class ConversationSyncIssue(val message: String,val historyFresh: Boolean=false,val liveWindow: Boolean=false) {
    val title: String get()=when {
        liveWindow->"实时进度已同步"
        historyFresh->"实时状态暂不可用"
        else->"对话同步失败"
    }
}

internal fun conversationReadFailure(failure: Throwable): String =
    NativeOperationFailure.describe(failure,"未能取得此对话的最新进度，当前显示缓存。正在自动重试。").message
