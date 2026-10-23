package com.agenthub.core

import kotlinx.serialization.json.*

data class ForkAvailability(val sameDirectory: Boolean, val worktree: Boolean,
    val reason: String?, val worktreeReason: String?)

/** Product decisions shared by both native clients. No transport or execution here. */
object ThreadForkRules {
    fun baseReason(supportedSource: Boolean, desktopConnected: Boolean, capabilities: List<String>, online: Boolean): String? = when {
        !supportedSource -> "此客户端尚未支持对话分支"
        !desktopConnected -> "需要连接原桌面运行时，历史连接不能创建分支"
        "threads:fork" !in capabilities -> "电脑尚未开放对话分支，请更新连接程序"
        !online -> "电脑未连接，请恢复连接后分支"
        else -> null
    }
    fun reason(turnId: String?, baseReason: String?, pending: Boolean, options: ForkAvailability?,
        environment: String, worktreeCapability: Boolean, optionError: String?): String? = when {
        turnId.isNullOrBlank() -> "此回复缺少准确的轮次 ID，请更新电脑连接程序并重新读取历史"
        baseReason != null -> baseReason
        pending -> "原分支结果尚未确认，请查询原回执或结束等待"
        options == null -> optionError ?: "正在读取分支选项…"
        environment == "same-directory" -> if (options.sameDirectory) null else options.reason ?: "此对话当前不能创建分支"
        environment == "worktree" -> when {
            !worktreeCapability -> "电脑尚未开放工作树分支"
            !options.worktree -> options.worktreeReason ?: options.reason ?: "此对话不在 Git 项目中，不能创建工作树"
            else -> null
        }
        else -> "分支环境无效"
    }
    fun pending(phase: String): Boolean = phase in listOf("ready", "submitting", "uncertain", "submitted", "outcome-unknown")
    fun outcomeMatches(original: String, outcome: String): Boolean = runCatching {
        require(CodexHttpContract.validate(original, "threadForkRequest") == null)
        require(CodexHttpContract.validate(outcome, "threadForkOperation") == null)
        val expected = Json.parseToJsonElement(original).jsonObject
        val actual = Json.parseToJsonElement(outcome).jsonObject
        require(expected.all { (key, value) -> actual[key] == value })
        if (actual.getValue("phase").jsonPrimitive.content == "confirmed") {
            val fork = actual.getValue("forkedThreadId").jsonPrimitive.contentOrNull
            require(!fork.isNullOrBlank() && fork != expected.getValue("threadId").jsonPrimitive.content)
        }
        true
    }.getOrDefault(false)
    fun mayAcceptOutcome(previousPhase: String, previousFork: String?, previousProject: String?,
        phase: String, fork: String?, project: String?): Boolean =
        phase in listOf("submitted", "confirmed", "outcome-unknown", "rejected") &&
            (previousPhase !in listOf("confirmed", "rejected") ||
                previousPhase == phase && previousFork == fork && previousProject == project)
}
