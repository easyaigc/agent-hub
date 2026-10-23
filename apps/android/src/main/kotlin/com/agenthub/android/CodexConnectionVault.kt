package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** One authenticated AtomicFile publishes a credential and its verified client together. */
internal class CodexConnectionVault(private val storage: NativeSessionStorage) {
    private val key = "codex-private-connections-v1"
    private fun rows(): List<JSONObject> {
        val raw = storage.read(key) ?: return emptyList()
        require(raw.length <= 1_048_576)
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == setOf("version", "connections") && root.get("version") == 1)
        val rows = root.getJSONArray("connections").objects()
        require(rows.size <= 128)
        val ids = mutableSetOf<String>()
        rows.forEach { row ->
            require(row.keys().asSequence().toSet() == setOf("client", "token"))
            val client = decodeClient(row.getJSONObject("client"))
            require(!client.isDemo && client.scope?.agentId == "codex" && client.codexDirect != null && ids.add(client.id))
            require((row.get("token") as? String)?.matches(Regex("[A-Za-z0-9_-]{43}")) == true)
        }
        return rows
    }
    fun clients(): List<MobileClient> = storage.operationTransaction { rows().map { decodeClient(it.getJSONObject("client")) } }
    fun token(id: String): String? = storage.operationTransaction { rows().firstOrNull { it.getJSONObject("client").getString("id") == id }?.getString("token") }
    fun save(client: MobileClient, token: String) = storage.operationTransaction {
        require(!client.isDemo && client.scope?.agentId == "codex" && client.codexDirect != null && token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val next = rows().filterNot { it.getJSONObject("client").getString("id") == client.id } + JSONObject().put("client", encodeClient(client)).put("token", token)
        require(next.size <= 128) { "手机保存的连接数量已满" }
        storage.write(key, JSONObject().put("version", 1).put("connections", JSONArray(next)).toString())
    }
    /** Verified route changes stay with the credential rather than depending on a later metadata snapshot. */
    fun updateClient(client: MobileClient) = storage.operationTransaction {
        val row=rows().firstOrNull { it.getJSONObject("client").getString("id")==client.id } ?: return@operationTransaction
        val prior=decodeClient(row.getJSONObject("client"))
        require(sameScope(prior.scope,client.scope) && prior.codexDirect?.fingerprint==client.codexDirect?.fingerprint)
        save(client,row.getString("token"))
    }
    fun remove(id: String) = storage.operationTransaction {
        val old = rows(); val next = old.filterNot { it.getJSONObject("client").getString("id") == id }
        if (next.size != old.size) storage.write(key, JSONObject().put("version", 1).put("connections", JSONArray(next)).toString())
    }
}
