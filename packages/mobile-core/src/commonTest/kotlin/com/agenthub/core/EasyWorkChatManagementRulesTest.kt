package com.agenthub.core

import kotlin.test.*

class EasyWorkChatManagementRulesTest {
    @Test fun batchArchiveKeepsLocalAliasButRejectsChangedRuntimeAndOrderedIntent() {
        val original="""{"hostId":"local-alias","target":{"agentId":"easywork","hostId":"runtime-host","workspaceId":"workspace","runtimeId":"runtime","account":{"authority":"https://account.test","subject":"owner"}},"clientOperationId":"batch-original-id","action":"threads:archive","ids":["first","second"]}"""
        val receipt=original.dropLast(1)+""","items":[{"id":"first","state":"completed","error":null},{"id":"second","state":"outcome-unknown","error":"uncertain"}]}"""
        assertTrue(WorkspaceArchiveRules.requestValid(original))
        assertNotNull(WorkspaceArchiveRules.merge(original,null,receipt))
        for(changed in listOf(receipt.replace("local-alias","other-alias"),receipt.replace("runtime-host","other-host"),
            receipt.replace("\"owner\"","\"other-owner\""),receipt.replace("batch-original-id","other-original-id"),
            receipt.replace("\"id\":\"first\"","\"id\":\"second\"")))assertNull(WorkspaceArchiveRules.merge(original,null,changed))
    }
    private val request="""{"hostId":"local-connection-alias","target":{"agentId":"easywork","hostId":"actual-runtime-host","workspaceId":"workspace","runtimeId":"runtime","account":{"authority":"https://account.test","subject":"owner"}},"threadId":"thread-12345678","clientOperationId":"operation-12345678","action":"rename","title":"phone title"}"""
    private fun outcome(original: String=request)=original.dropLast(1)+""","phase":"confirmed","error":null}"""
    @Test fun localAliasAndFullRuntimeIdentityBindTheOriginalOutcome() {
        assertTrue(ChatManagementRules.validIntent(request))
        assertTrue(ChatManagementRules.outcomeMatches(request,outcome()))
        for(changed in listOf(
            outcome().replace("local-connection-alias","other-connection-alias"),
            outcome().replace("actual-runtime-host","other-runtime-host"),
            outcome().replace("\"owner\"","\"other-owner\""),
            outcome().replace("phone title","different title"),
            outcome().replace("operation-12345678","operation-87654321"))) {
            assertFalse(ChatManagementRules.outcomeMatches(request,changed))
        }
    }
    @Test fun namedIntentRejectsExtraFieldsAndEnforcesTheSameUtf16TitleBoundary() {
        assertFalse(ChatManagementRules.validIntent(request.dropLast(1)+""","method":"shell"}"""))
        assertFalse(ChatManagementRules.validIntent(request.replace("phone title","🙂".repeat(251))))
        assertTrue(ChatManagementRules.validIntent(request.replace("phone title","🙂".repeat(250))))
        assertFalse(ChatManagementRules.validIntent(request.replace("phone title","\uFEFF\u2000")))
        assertEquals("phone title",ChatManagementRules.normalizedTitle("\uFEFF  phone title\u2000"))
        assertFalse(ChatManagementRules.validIntent(request.replace("actual-runtime-host","h".repeat(257))))
        assertFalse(ChatManagementRules.validOutcome(outcome().replace(",\"error\":null","")))
    }
    @Test fun endingLocalWaitingReleasesInputButKeepsTheOriginalOutcomeQueryable() {
        assertTrue(ChatManagementRules.blocksInput("outcome-unknown",false))
        assertFalse(ChatManagementRules.blocksInput("outcome-unknown",true))
        assertTrue(ChatManagementRules.pending("outcome-unknown"))
        assertFalse(ChatManagementRules.blocksInput("confirmed",false))
    }
}
