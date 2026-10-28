package com.agenthub.android

import com.agenthub.core.TargetScope
import org.junit.Test
import org.junit.Assert.*

class NativeNoticeVisibilityRulesTest {
    private val target=TargetScope("codex",null,null,"host","workspace","runtime")
    private val approval=MobileApproval("ui-id","thread","run","question","Question","",emptyList(),emptyList(),null,"wire-request")
    private val original=NativeNoticePanelOriginal.Approval(approval)
    private val exact=NativeReadingNotice("event","question","run","wire-request")
    private fun binding(notices: List<NativeReadingNotice>,changed: (NativeNoticeVisibilityReport)->Unit={})=
        NativePanelNoticeVisibility(target,"thread",notices,true,true,Any(),changed)
    @Test fun matchingRequiresOriginalWireRequestRunKindAndNoInventedOperation() {
        val rejected=listOf(exact.copy(id="ui",requestId="ui-id"),exact.copy(id="run",runId="other"),exact.copy(id="kind",kind="approval"),exact.copy(id="operation",operationId="op"),exact.copy(id="seen",seen=true))
        assertEquals(listOf(exact),NativeNoticeVisibilityMatch.notices(binding(listOf(exact)+rejected),original))
        assertTrue(NativeNoticeVisibilityMatch.notices(binding(listOf(exact,exact.copy(requestId="conflict"))),original).isEmpty())
        assertTrue(NativeNoticeVisibilityMatch.notices(binding(listOf(exact)),NativeNoticePanelOriginal.Approval(approval.copy(threadId="other"))).isEmpty())
        val plan=NativeNoticePanelOriginal.Plan(MobileProposedPlan("plan","run","Plan"))
        assertEquals(listOf(exact.copy(kind="plan",requestId="plan")),NativeNoticeVisibilityMatch.notices(binding(listOf(exact.copy(kind="plan",requestId="plan"))),plan))
    }
    @Test fun guardedMutationIsRevokedByGeometryOrDisposalAndOldProofNeverRevives() {
        val rows=mutableListOf<NativeNoticeVisibilityReport>();val fence=NativeNoticeVisibilityFence(binding(listOf(exact),rows::add),original,exact)
        fence.update(true);val first=rows.single().proof;var writes=0
        assertEquals(1,first.ifCurrent { ++writes });fence.update(false);assertFalse(first.isCurrent());assertNull(first.ifCurrent { ++writes })
        fence.update(true);val second=rows.last().proof;assertNotSame(first,second);assertFalse(first.isCurrent());assertTrue(second.isCurrent())
        fence.close();fence.update(true);assertFalse(second.isCurrent());assertNull(second.ifCurrent { ++writes });assertEquals(1,writes)
    }
    @Test fun targetThreadAndAccountPolicyFollowTheExistingReadingIdentity() {
        val b=binding(listOf(exact));assertSame(b,b.forTarget(target,"thread"));assertNull(b.forTarget(TargetScope("codex",null,null,"host","workspace","other"),"thread"));assertNull(b.forTarget(target,"other"))
        assertSame(b,b.forTarget(TargetScope("codex","metadata","different-login","host","workspace","runtime"),"thread"))
    }
}
