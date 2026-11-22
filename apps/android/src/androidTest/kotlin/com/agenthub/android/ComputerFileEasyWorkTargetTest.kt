package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import org.junit.Assert.*
import org.junit.Test

/** Domain/target checks only. No storage, grant, network, device, or fixture transport. */
class ComputerFileEasyWorkTargetTest {
    private val scope=TargetScope("easywork","https://issuer.example.invalid","owned-subject","owned-desktop","owned-workspace","owned-runtime")
    private fun changed(workspaceId:String=scope.workspaceId,runtimeId:String=scope.runtimeId,
        accountAuthority:String?=scope.accountAuthority,accountSubject:String?=scope.accountSubject)=
        TargetScope(scope.agentId,accountAuthority,accountSubject,scope.hostId,workspaceId,runtimeId)
    private fun canonicalId(s:TargetScope)="easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(s)).toByteArray())
    private fun client(s:TargetScope=scope)=MobileClient(canonicalId(s),"Owned EasyWork",s,"https://192.0.2.1:44319",isDemo=false,
        agentConnection=AgentConnection(s,listOf(AgentCandidate("first","lan-wss","https://192.0.2.1:44319","a".repeat(64))),"owned-reference"))
    private fun rejects(c:MobileClient) {
        try {ComputerFileTarget.capture(c);fail("Mismatched canonical target was accepted")}
        catch(expected:IllegalArgumentException) {}
    }
    @Test fun canonicalEasyWorkWithExactAuthenticatedConnectionCaptures() {
        val original=client();val target=ComputerFileTarget.capture(original)
        assertEquals(scope,target.scope);assertTrue(target.matches(original));assertNull(target.certificate)
    }
    @Test fun staleIdCannotRebindWorkspaceRuntimeOrEitherAccountField() {
        val original=client();val captured=ComputerFileTarget.capture(original)
        for(changed in listOf(changed(workspaceId="other-workspace"),changed(runtimeId="other-runtime"),
            changed(accountAuthority="https://other-issuer.example.invalid"),changed(accountSubject="other-subject"))) {
            // Keep old presentation ID while attempting a target substitution: must reject.
            rejects(original.copy(scope=changed,agentConnection=original.agentConnection!!.copy(target=changed)))
            assertFalse(captured.matches(original.copy(scope=changed)))
            // A separately legitimate new target has a distinct key, never authority over the old one.
            val separate=client(changed);assertFalse(captured.matches(separate))
            assertNotEquals(captured.key,ComputerFileTarget.capture(separate).key)
        }
    }
    @Test fun missingOrMismatchedConnectionWrongIdAndDemoReject() {
        val original=client();val captured=ComputerFileTarget.capture(original)
        for(changed in listOf(original.copy(agentConnection=null),original.copy(id=scope.hostId),
            original.copy(id="easywork-wrong-hash"),original.copy(isDemo=true),
            original.copy(agentConnection=original.agentConnection!!.copy(target=changed(runtimeId="wrong-owner"))))) {
            rejects(changed);assertFalse(captured.matches(changed))
        }
    }
    @Test fun addressOnlyRouteChangePreservesScopeKeyAndMatch() {
        val original=client();val target=ComputerFileTarget.capture(original)
        val moved=original.copy(endpoint="https://192.0.2.2:44319",agentConnection=original.agentConnection!!.copy(
            candidates=listOf(AgentCandidate("second","lan-wss","https://192.0.2.2:44319","a".repeat(64)))))
        assertTrue(target.matches(moved));assertEquals(target.key,ComputerFileTarget.capture(moved).key)
        assertEquals(target.json().toString(),ComputerFileTarget.capture(moved).json().toString())
    }
    @Test fun codexBaselineIdKeyAndJournalShapeRemainUnchanged() {
        val codex=TargetScope("codex",null,null,"owned-codex-host","owned-codex-workspace","desktop-runtime")
        val original=MobileClient(codex.hostId,"Owned Codex",codex,"https://192.0.2.3:44319",isDemo=false)
        val target=ComputerFileTarget.capture(original)
        val legacyKey=ComputerFileCodec.hash(("computer-files-v1\u0000"+RuntimeIdentity.readingKey(codex,null)).toByteArray(Charsets.UTF_8))
        assertEquals(legacyKey,target.key);assertTrue(target.matches(original));assertNull(target.certificate)
        assertEquals(setOf("scope","certificate"),target.json().keys().asSequence().toSet())
        rejects(original.copy(id="wrong-codex-host"))
    }
}
