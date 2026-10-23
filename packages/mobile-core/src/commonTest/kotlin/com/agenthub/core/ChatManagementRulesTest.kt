package com.agenthub.core

import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

class ChatManagementRulesTest {
    private val target="""{"agentId":"codex","hostId":"host-test-01","workspaceId":"workspace-test","runtimeId":"runtime-test","account":{"authority":"old","subject":"metadata"}}"""
    private fun request(action: String="archive")="""{"hostId":"host-test-01","target":$target,"threadId":"thread-test-01","clientOperationId":"operation-test-01","action":"$action"}"""
    private fun receipt(phase: String="confirmed",action: String="archive")=request(action).dropLast(1)+""","phase":"$phase","error":null}"""
    @Test fun archiveAndTaskStatusRemainIndependent() {
        val fixture=Json.parseToJsonElement(Base64.decode(stateVectorBase64).decodeToString()).jsonObject
        val rows=fixture.getValue("chatLifecycle").jsonArray
        for(value in rows) {
            val row=value.jsonObject
            val status=ChatManagementRules.taskStatus(row.getValue("state").jsonPrimitive.content,row["lastTurnStatus"]?.jsonPrimitive?.contentOrNull,
                row.getValue("archived").jsonPrimitive.boolean,row.getValue("online").jsonPrimitive.boolean,row.getValue("unconfirmed").jsonPrimitive.boolean)
            assertEquals(row.getValue("code").jsonPrimitive.content,status.code,row.getValue("name").jsonPrimitive.content)
            assertEquals(row.getValue("lastKnown").jsonPrimitive.boolean,status.lastKnown)
        }
        assertEquals(17,rows.size)
        assertEquals("unknown",ChatManagementRules.taskStatus("unknown","completed",false,true,false).code)
        assertEquals("completed",ChatManagementRules.taskStatus("unknown","completed",true,true,false).code)
        assertEquals("idle",ChatManagementRules.taskStatus("idle",null,false,true,false).code)
        assertEquals("stopped",ChatManagementRules.taskStatus("idle","cancelled",false,true,false).code)
    }
    @Test fun staleStatusIsASeparateQualifierAndDoesNotGrantManagement() {
        val rows=Json.parseToJsonElement(Base64.decode(stateVectorBase64).decodeToString()).jsonObject.getValue("chatManagementAvailability").jsonArray
        for(value in rows) {
            val r=value.jsonObject;fun flag(key: String)=r.getValue(key).jsonPrimitive.boolean
            assertEquals(flag("expected"),ChatManagementRules.canManage(flag("demo"),flag("online"),flag("supported"),flag("knownTarget"),flag("busy"),flag("pending")))
        }
        assertEquals(7,rows.size)
        assertTrue(ChatManagementRules.taskStatus("running",null,false,false,false).lastKnown)
        assertTrue(ChatManagementRules.taskStatus("completed",null,false,true,true).lastKnown)
        assertFalse(ChatManagementRules.taskStatus("unknown","completed",true,false,true).lastKnown)
        assertFalse(ChatManagementRules.canManage(false,false,true,true,false,false))
        assertFalse(ChatManagementRules.canManage(false,true,false,true,false,false))
        assertFalse(ChatManagementRules.canManage(false,true,true,false,false,false))
        assertFalse(ChatManagementRules.canManage(false,true,true,true,true,false))
        assertFalse(ChatManagementRules.canManage(false,true,true,true,false,true))
    }
    @Test fun exactActionOperationAndMachineBindReceiptsWithoutAccountGate() {
        for(action in listOf("pin","unpin","archive","unarchive","delete"))
            assertTrue(ChatManagementRules.outcomeMatches(request(action),receipt(action=action)))
        val result=receipt().replace("\"old\"","\"new\"").replace("\"metadata\"","\"other\"")
        assertTrue(ChatManagementRules.outcomeMatches(request(),result))
        for((from,to)in listOf("runtime-test" to "runtime-other","thread-test-01" to "thread-other-01","operation-test-01" to "operation-other-01","archive" to "delete"))
            assertFalse(ChatManagementRules.outcomeMatches(request(),receipt().replace(from,to)))
    }
    @Test fun unknownAndTerminalReceiptsCannotBecomeAnotherActionOrRegress() {
        assertTrue(ChatManagementRules.pending("uncertain"))
        assertTrue(ChatManagementRules.pending("outcome-unknown"))
        for(terminal in listOf("confirmed","rejected")) {
            assertFalse(ChatManagementRules.pending(terminal))
            assertTrue(ChatManagementRules.mayAcceptOutcome(terminal,terminal))
            for(next in listOf("submitted","outcome-unknown",if(terminal=="confirmed")"rejected" else "confirmed"))
                assertFalse(ChatManagementRules.mayAcceptOutcome(terminal,next))
        }
        assertFalse(ChatManagementRules.outcomeMatches(request(),receipt().replace("confirmed","opened")))
    }
    @Test fun onlyNamedStrictContractsAllowManagement() {
        assertNotNull(CodexHttpContract.validate(request("shell"),"chatActionRequest"))
        assertNotNull(CodexHttpContract.validate(request().replace("\"archive\"","\"archive\",\"method\":\"arbitrary\""),"chatActionRequest"))
        assertNotNull(CodexHttpContract.validate(receipt().replace(",\"error\":null",""),"chatActionReceipt"))
        assertNotNull(CodexHttpContract.validate(request().replace("thread-test-01","short"),"chatActionRequest"))
        assertFalse(ChatManagementRules.outcomeMatches(request().replace("\"hostId\":\"host-test-01\"","\"hostId\":\"host-other-01\""),receipt()))
    }
}
