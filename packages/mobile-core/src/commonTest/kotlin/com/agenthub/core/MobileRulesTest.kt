package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

class MobileRulesTest {
    private val vectors = Json.parseToJsonElement(Base64.decode(vectorBase64).decodeToString()).jsonObject
    private fun JsonObject.text(name: String): String? = getValue(name).jsonPrimitive.contentOrNull
    private fun JsonObject.flag(name: String) = getValue(name).jsonPrimitive.boolean

    @Test fun persistedIdentityBytesAndCompleteTargetIsolation() {
        val outputs = mutableMapOf<String, String>()
        for (entry in vectors.getValue("identities").jsonArray) {
            val test = entry.jsonObject
            val input = test.getValue("input").jsonObject
            val target = input["target"]?.takeUnless { it == JsonNull }?.jsonObject?.let {
                TargetScope(it.text("agentId")!!, it.text("accountAuthority"), it.text("accountSubject"),
                    it.text("hostId")!!, it.text("workspaceId")!!, it.text("runtimeId")!!)
            }
            val result = MobileRules.cacheKey(input.text("clientId")!!, target, input.text("threadId"), input.text("projectId"))
            val name = test.text("name")!!
            assertEquals(test.text("expected"), result, name)
            outputs[name] = result
        }
        // Every target component has its own synthetic variant. Thread scope
        // ignores project context, and draft scope cannot collide with thread.
        assertEquals(outputs["complete-thread"], outputs["project-ignored-for-thread"])
        val isolated = outputs.filterKeys { it.startsWith("isolate-") }.values.toList() +
            listOf(outputs.getValue("complete-thread"), outputs.getValue("draft-not-thread"),
                outputs.getValue("account-absent"), outputs.getValue("empty-not-null"))
        assertEquals(isolated.size, isolated.toSet().size)
    }

    @Test fun composerPriorityRecoveryAndEnabledState() {
        for (entry in vectors.getValue("composer").jsonArray) {
            val test = entry.jsonObject
            val input = test.getValue("input").jsonObject
            val result = MobileRules.composer(ComposerInput(
                input.flag("stopping"), input.flag("running"), input.flag("sending"), input.flag("creationPending"),
                input.text("text")!!, input.getValue("attachmentCount").jsonPrimitive.int,
                input.flag("canSend"), input.flag("canStop"), input.flag("canRunAgain"),
                input.text("runtimeState"), input.text("lastReceiptState"),
                input.getValue("messages").jsonArray.map { message -> message.jsonObject.let {
                    ComposerMessage(it.text("role")!!, it.text("status"))
                } },
            ))
            val expected = test.getValue("expected").jsonObject
            assertEquals(expected.text("action"), result.action, test.text("name"))
            assertEquals(expected.flag("enabled"), result.enabled, test.text("name"))
        }
    }

    @Test fun foundationWhitespaceAttachmentsAndExplicitResumeText() {
        for (entry in vectors.getValue("content").jsonArray) {
            val test = entry.jsonObject
            assertEquals(test.flag("expected"), MobileRules.hasContent(test.text("text")!!, test.getValue("count").jsonPrimitive.int))
        }
        assertEquals("继续完成上一个任务。", MobileRules.runAgainText(false))
        assertEquals("请重新执行上一个任务。", MobileRules.runAgainText(true))
    }
}
