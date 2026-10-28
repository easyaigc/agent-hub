package com.agenthub.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AuthorizationOpaqueAuditTest {
    private class Store: EasyWorkAuthorizationRecords {
        override val root="public-opaque-root-"+UUID.randomUUID();val values=mutableMapOf<String,String>()
        override fun <T> transaction(block: ()->T)=synchronized(values,block)
        override fun read(key: String)=values[key]
        override fun write(key: String,value: String) {values[key]=value}
        override fun remove(key: String) {values.remove(key)}
    }
    @Test fun originalPendingKeepsEveryOpaqueFieldEvenWhenAuditNamesOverlap() {
        val s=Store();val ref="public-ref";val coordinator=EasyWorkAuthorizationCoordinator(s)
        val grant=JSONObject().put("protocol","EWDAP/1").put("grantId","public-grant").put("deviceId","public-device").put("binding",JSONObject()).put("target",JSONObject()).put("keyVersion",1).put("scopes",org.json.JSONArray()).put("accessToken","A".repeat(43)).put("refreshToken","B".repeat(43)).put("accessExpiresAt","2099-01-01T00:00:00Z").put("refreshExpiresAt","2099-02-01T00:00:00Z")
        val pending=JSONObject().put("operationId",UUID.randomUUID().toString()).put("token","B".repeat(43)).put("body","opaque-owner-metadata").put("tokenHash","opaque-owner-hash").put("grantHash","opaque-grant-hash").put("originalPending",JSONObject().put("opaque","keep"))
        s.write(ref,JSONObject().put("origin","https://public-synthetic.invalid").put("grant",grant).put("refreshOperation",pending).toString())
        val attempt=requireNotNull(coordinator.access(ref) {null}.refresh);coordinator.refreshed(ref,attempt,JSONObject(grant.toString()).put("accessToken","C".repeat(43)))
        val receipt=JSONObject(requireNotNull(s.read(EasyWorkAuthorizationCoordinator.fenceKey(ref)))).getJSONObject("refreshResults").getJSONObject(attempt.operationId)
        val originalMetadata=JSONObject(pending.toString()).apply {remove("token")};assertEquals(easyCanonical(originalMetadata),easyCanonical(receipt.getJSONObject("originalPending")));assertFalse(receipt.getJSONObject("originalPending").has("token"));assertEquals(easyHash(pending.getString("token").toByteArray()),receipt.getString("tokenHash"));assertEquals(attempt.body,receipt.getString("body"));assertNotEquals(pending.getString("tokenHash"),receipt.getString("tokenHash"));assertEquals("keep",receipt.getJSONObject("originalPending").getJSONObject("originalPending").getString("opaque"));assertFalse(JSONObject(requireNotNull(s.read(ref))).has("refreshOperation"))
    }
}
