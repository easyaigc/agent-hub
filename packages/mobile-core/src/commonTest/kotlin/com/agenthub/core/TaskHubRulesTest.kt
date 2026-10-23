package com.agenthub.core

import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

class TaskHubRulesTest {
    @Test fun sharedVectors() {
        val input = Json.parseToJsonElement(Base64.decode(taskHubVectorBase64).decodeToString()).jsonObject
        input.getValue("cursors").jsonArray.forEach { row ->
            assertEquals(row.jsonObject.getValue("valid").jsonPrimitive.boolean, TaskHubRules.validCursor(row.jsonObject.getValue("value").jsonPrimitive.content))
        }
        input.getValue("phases").jsonArray.forEach { row ->
            val r = row.jsonObject
            assertEquals(r.getValue("accept").jsonPrimitive.boolean, TaskHubRules.acceptsPhase(r.getValue("current").jsonPrimitive.content, r.getValue("next").jsonPrimitive.content))
        }
        input.getValue("contracts").jsonArray.forEach { row ->
            val r=row.jsonObject
            assertEquals(r.getValue("valid").jsonPrimitive.boolean,TaskHubContract.validate(r.getValue("value").toString(),r.getValue("definition").jsonPrimitive.content)==null,r.getValue("name").jsonPrimitive.content)
        }
    }
    @Test fun identityAndLateResults() {
        val scope = TaskHubRules.scope("a/b", "c", "h", "u", "d")
        assertNotEquals(scope, TaskHubRules.scope("a", "b/c", "h", "u", "d"))
        assertTrue(TaskHubRules.currentSession(scope, scope, "g", "g", "human", false))
        assertFalse(TaskHubRules.currentSession(scope, scope, "old", "new", "human", false))
        assertFalse(TaskHubRules.currentSession(scope, scope, "g", "g", "agent", false))
        assertFalse(TaskHubRules.currentSession(scope, scope, "g", "g", "human", true))
        assertEquals("unknown", TaskHubRules.failurePhase(true, 409))
        assertEquals("unknown", TaskHubRules.failurePhase(true, 0))
        assertEquals("rejected", TaskHubRules.failurePhase(false, 0))
        assertEquals("rejected", TaskHubRules.failurePhase(true, 403))
    }
    @Test fun streamBoundaries() {
        fun decode(text: String): List<TaskHubStreamEvent> {
            val framer = TaskHubSSEFramer()
            return text.encodeToByteArray().asIterable().mapNotNull(framer::consume)
        }
        val events = decode(":keepalive\r\nid: 42\r\ndata: 中文😀\r\ndata: tail\r\n\r\nid: 43\ndata: partial")
        assertEquals(listOf(TaskHubStreamEvent("42", "中文😀\ntail")), events)
        assertEquals(listOf(TaskHubStreamEvent(null, "")), decode("id:\ndata\n\n"))
        assertFails { decode("id: 9007199254740992\ndata: a\n\n") }
        assertFails { val f=TaskHubSSEFramer(); listOf<Byte>(0xc3.toByte(), 10).forEach(f::consume) }
        assertFails { decode("data:" + "x".repeat(256*1024) + "\n\n") }
        assertFails { decode(("data:" + "x".repeat(200000) + "\n").repeat(3) + "\n") }
    }
    @Test fun receiptAndServiceBoundary() {
        val id="00000000-0000-4000-8000-000000000001"
        assertFalse(TaskHubRules.receiptMatches(id, "{\"operationId\":\"$id\",\"phase\":\"confirmed\"}"))
        assertTrue(TaskHubRules.receiptMatches(id, "{\"operationId\":\"$id\",\"phase\":\"confirmed\",\"result\":{}}"))
        assertFalse(TaskHubRules.receiptMatches("other", "{\"operationId\":\"$id\",\"phase\":\"unknown\"}"))
        val service="""{"version":1,"contractVersion":"1","deploymentId":"d","serverInstanceId":"s","primaryHostId":"h","name":"Task Hub","capabilities":[]}"""
        assertNull(TaskHubContract.validate(service,"service"))
        assertNotNull(TaskHubContract.validate(service.replace("\"1\"", "1"),"service"))
        assertNotNull(TaskHubContract.validate(service.replace("\"d\"", "\"\""),"service"))
        assertFalse(TaskHubRules.validPath("tasks/../other"))
        assertFalse(TaskHubRules.validPath("https://other"))
        assertFalse(TaskHubRules.validPath("tasks/%2e%2e"))
        assertTrue(TaskHubRules.validPath("projects/$id/tasks"))
    }
}
