package com.agenthub.core

import kotlin.test.*
import kotlinx.serialization.json.*

class ThreadForkRulesTest {
    private val available=ForkAvailability(true,true,null,null)
    @Test fun cachedHistoryOrOtherAgentCannotAuthorizeFork() {
        assertNotNull(ThreadForkRules.baseReason(false,true,listOf("threads:fork"),true))
        assertNotNull(ThreadForkRules.baseReason(true,false,listOf("threads:fork"),true))
        assertNotNull(ThreadForkRules.baseReason(true,true,emptyList(),true))
        assertNotNull(ThreadForkRules.baseReason(true,true,listOf("threads:fork"),false))
        assertNull(ThreadForkRules.baseReason(true,true,listOf("threads:fork"),true))
    }
    @Test fun missingTurnPendingReceiptAndGitCapabilityStayExplicit() {
        assertNotNull(ThreadForkRules.reason(null,null,false,available,"same-directory",true,null))
        assertNotNull(ThreadForkRules.reason("turn",null,true,available,"same-directory",true,null))
        assertNotNull(ThreadForkRules.reason("turn",null,false,null,"same-directory",true,null))
        assertNotNull(ThreadForkRules.reason("turn",null,false,available,"worktree",false,null))
        assertEquals("no Git",ThreadForkRules.reason("turn",null,false,ForkAvailability(true,false,null,"no Git"),"worktree",true,null))
        assertNull(ThreadForkRules.reason("turn",null,false,available,"same-directory",false,null))
        assertNull(ThreadForkRules.reason("turn",null,false,available,"worktree",true,null))
    }
    @Test fun confirmedOutcomeCannotChangeOriginalOrReturnSourceAsFork() {
        val request=buildJsonObject { put("hostId","host");put("threadId","source");put("turnId","turn");put("clientOperationId","operation-123");put("environment","worktree") }
        val outcome=JsonObject(request+mapOf("phase" to JsonPrimitive("confirmed"),"forkedThreadId" to JsonPrimitive("fork"),"projectId" to JsonNull,"error" to JsonNull))
        assertTrue(ThreadForkRules.outcomeMatches(request.toString(),outcome.toString()))
        for((key,value) in mapOf("hostId" to "other","threadId" to "other","turnId" to "other","clientOperationId" to "other-operation","environment" to "same-directory","forkedThreadId" to "source"))
            assertFalse(ThreadForkRules.outcomeMatches(request.toString(),JsonObject(outcome+(key to JsonPrimitive(value))).toString()),key)
    }
    @Test fun lateOutcomeCannotRollbackOrChangeConfirmedAndRejected() {
        assertTrue(ThreadForkRules.mayAcceptOutcome("submitted",null,null,"confirmed","fork","project"))
        assertTrue(ThreadForkRules.mayAcceptOutcome("confirmed","fork","project","confirmed","fork","project"))
        assertFalse(ThreadForkRules.mayAcceptOutcome("confirmed","fork","project","submitted",null,null))
        assertFalse(ThreadForkRules.mayAcceptOutcome("confirmed","fork","project","confirmed","different","project"))
        assertFalse(ThreadForkRules.mayAcceptOutcome("rejected",null,null,"confirmed","fork","project"))
    }
}
