package com.agenthub.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AuthorizationFenceShapeTest {
    private class Store: EasyWorkAuthorizationRecords {
        override val root="public-shape-root-"+UUID.randomUUID()
        val records=mutableMapOf<String,String>()
        override fun <T> transaction(block: ()->T)=synchronized(records,block)
        override fun read(key: String)=records[key]
        override fun write(key: String,value: String) {records[key]=value}
        override fun remove(key: String) {records.remove(key)}
    }
    @Test fun missingGrantAndMissingFenceDoNotCreateRevocationWorkOrRecords() {
        val store=Store();val c=EasyWorkAuthorizationCoordinator(store);assertFalse(c.hasRevokeWork("public-ref"));assertNull(c.revocation("public-ref") {});assertTrue(store.records.isEmpty())
    }
    @Test fun malformedRevocationCannotDisappearIntoAnAbsentOptionalField() {
        val store=Store();val c=EasyWorkAuthorizationCoordinator(store);val key=EasyWorkAuthorizationCoordinator.fenceKey("public-ref")
        for(revoke in listOf<Any>(JSONObject.NULL,"not-object",JSONObject().put("state","unsupported"))) {
            val marker=JSONObject().put("version",1).put("ref","public-ref").put("epoch",UUID.randomUUID().toString()).put("revoke",revoke).toString();store.write(key,marker)
            val failure=runCatching {c.hasRevokeWork("public-ref")}.exceptionOrNull();assertTrue(failure is IllegalArgumentException||failure is org.json.JSONException);assertEquals(marker,store.read(key))
        }
    }
    @Test fun revocationTicketCannotBeCommittedForAnotherRootOrRef() {
        val a=Store();val b=Store();val ca=EasyWorkAuthorizationCoordinator(a);val cb=EasyWorkAuthorizationCoordinator(b)
        val saved=JSONObject().put("origin","https://public-synthetic.invalid").put("grant",JSONObject().put("grantId","public-grant").put("binding",JSONObject().put("target","public-target")).put("accessToken","A".repeat(43)))
        a.write("public-ref",saved.toString());b.write("public-ref",saved.toString());val ticket=requireNotNull(ca.revocation("public-ref") {});assertThrows(IllegalArgumentException::class.java) {cb.revoked("public-ref",ticket)};assertThrows(IllegalArgumentException::class.java) {ca.revoked("other-ref",ticket)};assertEquals(saved.toString(),b.read("public-ref"));assertNotNull(a.read("public-ref"))
    }
    @Test fun confirmedCleanupKeepsNewOpaqueFieldsBeforeRemovingTheRemainingRecord() {
        val store=Store();val c=EasyWorkAuthorizationCoordinator(store)
        val saved=JSONObject().put("origin","https://public-synthetic.invalid").put("grant",JSONObject().put("grantId","public-grant").put("binding",JSONObject().put("target","public-target")).put("accessToken","A".repeat(43)))
        store.write("public-ref",saved.toString());val ticket=requireNotNull(c.revocation("public-ref") {});c.revoked("public-ref",ticket)
        // Recreate the record left by a failed local deletion, then add another owner's metadata.
        store.write("public-ref",JSONObject(ticket.saved).put("futureReceipt","keep").toString());assertNull(c.revocation("public-ref") {});assertNull(store.read("public-ref"));val marker=JSONObject(requireNotNull(store.read(EasyWorkAuthorizationCoordinator.fenceKey("public-ref"))));assertEquals("keep",marker.getJSONObject("revoke").getJSONObject("localReceipt").getString("futureReceipt"));assertEquals(ticket.saved,marker.getJSONObject("revoke").getString("saved"))
    }
}
