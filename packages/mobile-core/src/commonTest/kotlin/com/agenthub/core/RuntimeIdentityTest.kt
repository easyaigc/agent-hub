package com.agenthub.core

import kotlin.test.*

class RuntimeIdentityTest {
    private fun target(agent: String = "codex", account: String? = "old", host: String = "host", workspace: String = "workspace", runtime: String = "desktop-runtime") =
        TargetScope(agent, account?.let { "https://accounts.invalid" }, account, host, workspace, runtime)

    @Test fun machineIdentityIgnoresOnlyCodexAccount() {
        val original=target()
        assertTrue(RuntimeIdentity.matches(original,target(account="new")))
        assertTrue(RuntimeIdentity.matches(original,target(account=null)))
        for (changed in listOf(target(host="other"),target(workspace="other"),target(runtime="other"),target(agent="easywork")))
            assertFalse(RuntimeIdentity.matches(original,changed))
        assertFalse(RuntimeIdentity.matches(target(agent="easywork"),target(agent="easywork",account="new")))
        assertFalse(RuntimeIdentity.matches(original,null))
        assertTrue(RuntimeIdentity.matches(null,null))
    }

    @Test fun oldNamespacesRemainDistinctAndRequireExplicitConflictChoice() {
        val a=target();val b=target(account="new")
        val oldA=MobileRules.cacheKey("host",a,"thread",null)
        val oldB=MobileRules.cacheKey("host",b,"thread",null)
        val foreign=MobileRules.cacheKey("host",target(workspace="other"),"thread",null)
        val canonical=RuntimeIdentity.cacheKey("host",b,"thread",null)
        val candidates=RuntimeIdentity.compatibleCacheKeys(b,"thread",null,listOf(oldB,oldA,foreign,"agent-v1:invalid",oldA))
        assertEquals(setOf(oldA,oldB),candidates.toSet())
        assertEquals(canonical,RuntimeIdentity.preferredKey(canonical,candidates,null))
        assertEquals(oldA,RuntimeIdentity.preferredKey(canonical,candidates,oldA))
        assertEquals(canonical,RuntimeIdentity.preferredKey(canonical,candidates,foreign))
        assertEquals(canonical,RuntimeIdentity.preferredKey(canonical,candidates+canonical,null))
        assertEquals(oldA,RuntimeIdentity.preferredKey(canonical,candidates+canonical,oldA))
        assertEquals(oldA,RuntimeIdentity.preferredKey(canonical,listOf(oldA),null))
        assertNotEquals(oldA,oldB) // Immutable old payload/storage bytes are never normalized in place.
    }

    @Test fun selectorsAndProviderBoundariesCannotCollide() {
        val a=target();val b=target(account="new")
        assertEquals(RuntimeIdentity.cacheKey("host",a,"thread",null),RuntimeIdentity.cacheKey("host",b,"thread",null))
        assertEquals(RuntimeIdentity.readingKey(a,"thread"),RuntimeIdentity.readingKey(b,"thread"))
        assertNotEquals(RuntimeIdentity.cacheKey("host",a,"thread",null),RuntimeIdentity.cacheKey("host",a,null,"thread"))
        val other=target(agent="easywork")
        assertEquals(MobileRules.cacheKey("host",other,"thread",null),RuntimeIdentity.cacheKey("host",other,"thread",null))
        assertNotEquals(RuntimeIdentity.cacheKey("host",other,"thread",null),RuntimeIdentity.cacheKey("host",target(agent="easywork",account="new"),"thread",null))
        assertTrue(RuntimeIdentity.compatibleCacheKeys(a,null,"thread",listOf(MobileRules.cacheKey("host",a,"thread",null))).isEmpty())
    }

    @Test fun replyProjectionPreservesExactOriginalRunForForkNavigation() {
        val rows=MessageProjection.project(listOf(
            TimelineMessage("one","assistant","answer",null,"completed","actual-turn","final_answer",null)
        ),60,null,false).rows
        val action=rows.single { it.kind=="replyActions" }
        assertEquals("actual-turn",action.runId)
        assertEquals("one",action.id)
    }
}
