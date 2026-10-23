package com.agenthub.android

import org.json.JSONObject

/** QR material contains a public identity/pin only, never a human login token. */
internal data class TaskHubPairing(val deploymentId: String,val serverInstanceId: String,val primaryHostId: String,
    val name: String,val endpoint: String,val fingerprint: String,val registryKeySha256: String) {
    companion object {
        fun parse(text: String): TaskHubPairing {
            require(text.toByteArray(Charsets.UTF_8).size<=16384) { "任务服务二维码内容过长" }
            val v=JSONObject(text)
            require(v.get("version") is Number && listOf("type","deploymentId","serverInstanceId","primaryHostId","name","endpoint","canonicalOrigin","fingerprint","registryKeySha256").all { v.get(it) is String })
            require(v.getInt("version")==1 && v.getString("type")=="agenthub-task")
            val ids=listOf("deploymentId","serverInstanceId","primaryHostId").map(v::getString)
            require(ids.all { it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")) })
            val endpoint=CodexEndpoints.local(v.getString("endpoint"))
            require(endpoint==CodexEndpoints.local(v.getString("canonicalOrigin"))) { "任务主服务地址不一致" }
            val name=v.getString("name");require(name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size<=80)
            val fingerprint=v.getString("fingerprint");val registry=v.getString("registryKeySha256")
            require(listOf(fingerprint,registry).all { it.matches(Regex("[a-fA-F0-9]{64}")) })
            return TaskHubPairing(ids[0],ids[1],ids[2],name,endpoint,fingerprint,registry)
        }
    }
    suspend fun probe(deviceId: String): TaskHubBinding {
        val expected=TaskHubService(deploymentId,serverInstanceId,primaryHostId,name,emptyList())
        val binding=TaskHubBinding(expected,endpoint,fingerprint)
        val service=TaskHubService.decode(TaskHubClient(binding,deviceId).request("service"))
        require(expected.same(service)) { "主服务身份已变化，未保存连接" }
        return binding.copy(service=service)
    }
}
