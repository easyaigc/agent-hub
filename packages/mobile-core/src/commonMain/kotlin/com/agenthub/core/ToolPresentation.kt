package com.agenthub.core

class ToolPresentationStyle(val category: String, val verb: String, val detailTitle: String)

/** Shared product wording; vector drawing, hover and disclosure remain native. */
object ToolPresentation {
    fun style(kind: String?): ToolPresentationStyle = when(kind) {
        "commandExecution" -> ToolPresentationStyle("命令", "运行", "Shell")
        "fileChange" -> ToolPresentationStyle("文件", "编辑", "Diff")
        "webSearch" -> ToolPresentationStyle("网页", "搜索", "搜索结果")
        "reasoning" -> ToolPresentationStyle("思考", "进行", "思考")
        else -> ToolPresentationStyle("工具", "使用", "工具输出")
    }
    fun status(status: String?): String = when(status) {
        "inProgress" -> "进行中"; "completed" -> "成功"; "failed" -> "失败"; "interrupted" -> "已停止"; else -> "状态未知"
    }
    fun summary(messages: List<TimelineMessage>): String {
        val actions=mutableListOf<String>()
        val sources=messages.filter { it.kind in listOf("mcpToolCall", "dynamicToolCall") }.map { it.text.split(" · ").first() }.filter { it.isNotEmpty() }.distinct()
        if(sources.isNotEmpty()) actions.add("使用 " + sources.joinToString("、"))
        if(messages.any { it.kind=="webSearch" }) actions.add("搜索网页")
        if(messages.any { it.kind=="fileChange" }) actions.add("编辑文件")
        val commands=messages.count { it.kind=="commandExecution" }
        if(commands>0) actions.add(if(commands>1) "运行多个命令" else "运行命令")
        if(actions.isEmpty()) actions.add("使用多个工具")
        return (if(messages.any { it.status=="inProgress" }) "正在" else "已") + actions.joinToString("、")
    }
    fun title(message: TimelineMessage, single: Boolean): String {
        if(!single && message.kind in listOf("mcpToolCall", "dynamicToolCall")) {
            val parts=message.text.split(" · ")
            val activity=if(parts.size>1) parts.drop(1).joinToString(" · ") else message.text
            if(activity.isNotEmpty()) return activity
        }
        val style=style(message.kind)
        val target=if(single || message.text.isEmpty()) style.category else message.text
        val gap=if(single || message.text.isEmpty()) "" else " "
        return when(message.status) {
            "inProgress" -> "正在${style.verb}$gap$target"
            "completed" -> "已${style.verb}$gap$target"
            "failed" -> "${style.verb}$gap${target}失败"
            "interrupted" -> "${style.verb}$gap${target}已停止"
            else -> target
        }
    }
    fun compaction(status: String?, live: Boolean): String = when(status) {
        "inProgress" -> if(live) "正在压缩上下文 · 可能需要几分钟" else "上下文压缩状态待同步"
        "completed" -> "已压缩上下文"
        "interrupted", "cancelled" -> "上下文压缩已停止"
        "failed" -> "上下文压缩失败"
        else -> "上下文压缩状态待同步"
    }
}
