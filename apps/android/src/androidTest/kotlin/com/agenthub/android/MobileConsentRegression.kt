package com.agenthub.android

import com.agenthub.core.AISharingRules

/** Explicit synthetic-fixture precondition for older action assertions. The separate sharing suite verifies the real permission UI/no replay. */
internal suspend fun allowSyntheticRegressionSharing(session: MobileSession) {
    val client=session.selectedClient
    require(!client.isDemo && (client.id=="fixture-host" || client.id=="fork-host" || client.id.contains("fixture") || client.id.matches(Regex("recovery-[a-f0-9-]{36}")))) { "Only synthetic regression targets may use this test setup" }
    val target=client.scope ?: error("Fixture target must be authenticated first")
    val models=listOf("follow-computer","fixture-model",AISharingRules.model(session.runtime?.settings?.model)).distinct()
    for(model in models) session.aiSharing.allow(AISharingRequest(AISharingScope(target,model),session.targetKey,client.name,"Codex",model))
}
