package com.agenthub.android

import org.json.JSONObject
import org.junit.Assert.*

/** Real native JSON/graph boundary, called by the fresh full migration scenario. No storage or IO. */
internal object NativeMigrationClientGraphAssertions {
    private fun checkGraph(label: String,accepted: Boolean,change: (JSONObject,JSONObject)->Unit) {
        val records=MigrationActivationFixtures.payload().getJSONObject("records")
        val metadata=JSONObject(records.getString("metadata")).put("draftFiles",JSONObject()).put("drafts",JSONObject())
        val vault=JSONObject(records.getString("codex-private-connections-v1"))
        val saved=vault.getJSONArray("connections").getJSONObject(0).getJSONObject("client")
        val restored=metadata.getJSONArray("clients").getJSONObject(0)
        change(saved,restored)
        val metadataOriginal=metadata.toString();val vaultOriginal=vault.toString()
        val outcome=runCatching {
            NativeMigrationMetadata.validate(metadata)
            val clients=metadata.getJSONArray("clients").objects().map(::decodeClient)
            NativeMigrationReferences.validate(mapOf("metadata" to metadata,"codex-private-connections-v1" to vault),clients,emptySequence())
        }
        assertEquals("$label acceptance",accepted,outcome.isSuccess)
        assertEquals("$label metadata must stay original",metadataOriginal,metadata.toString())
        assertEquals("$label vault must stay original",vaultOriginal,vault.toString())
    }
    fun verify() {
        checkGraph("historical false to restored true",true){saved,current->saved.put("requiresReadTarget",false);current.put("requiresReadTarget",true)}
        checkGraph("historical absent to restored true",true){saved,current->saved.remove("requiresReadTarget");current.put("requiresReadTarget",true)}
        checkGraph("current true remains true",true){saved,current->saved.put("requiresReadTarget",true);current.put("requiresReadTarget",true)}
        checkGraph("historical false stays false",true){saved,current->saved.put("requiresReadTarget",false);current.put("requiresReadTarget",false)}
        checkGraph("historical absent means false",true){saved,current->saved.remove("requiresReadTarget");current.put("requiresReadTarget",false)}
        checkGraph("historical false equals absent",true){saved,current->saved.put("requiresReadTarget",false);current.remove("requiresReadTarget")}
        checkGraph("true cannot weaken to false",false){saved,current->saved.put("requiresReadTarget",true);current.put("requiresReadTarget",false)}
        checkGraph("true cannot disappear",false){saved,current->saved.put("requiresReadTarget",true);current.remove("requiresReadTarget")}
        checkGraph("vault string is not a Boolean",false){saved,current->saved.put("requiresReadTarget","false");current.put("requiresReadTarget",true)}
        checkGraph("vault null is not a Boolean",false){saved,current->saved.put("requiresReadTarget",JSONObject.NULL);current.put("requiresReadTarget",true)}
        checkGraph("metadata string is not a Boolean",false){_,current->current.put("requiresReadTarget","true")}
        checkGraph("certificate mismatch",false){saved,_->saved.getJSONObject("codexDirect").put("fingerprint","b".repeat(64))}
        checkGraph("route mismatch",false){saved,_->saved.getJSONObject("codexDirect").getJSONArray("routes").getJSONObject(0).put("endpoint","https://127.0.0.1:2")}
        checkGraph("internally valid runtime mismatch",false){saved,_->
            saved.getJSONObject("scope").put("runtimeId","different-runtime")
            saved.getJSONObject("codexDirect").getJSONObject("target").put("runtimeId","different-runtime")
        }
        checkGraph("other client fields stay strict",false){saved,_->saved.put("readerId","different-reader")}
        checkGraph("unknown client field refused",false){saved,_->saved.put("future-client-authority",true)}
    }
}
