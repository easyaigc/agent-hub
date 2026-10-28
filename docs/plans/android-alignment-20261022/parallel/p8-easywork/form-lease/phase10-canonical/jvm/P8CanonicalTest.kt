package com.agenthub.android

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Negative-control encoder from the previous production revision; never a production fallback. */
internal fun p8LegacyCanonical(v: Any?): String=when(v) {
    null,JSONObject.NULL->"null"
    is JSONObject->v.keys().asSequence().toList().sorted().joinToString(",","{","}") {JSONObject.quote(it)+":"+p8LegacyCanonical(v.get(it))}
    is JSONArray->(0 until v.length()).joinToString(",","[","]") {p8LegacyCanonical(v.get(it))}
    is String->JSONObject.quote(v).replace("\\/","/")
    is Boolean,is Number->v.toString()
    else->error("unsupported synthetic value")
}
internal fun p8CanonicalVectors()=JSONObject(File(System.getProperty("p8.root"),"apps/android/src/androidTest/assets/easywork-canonical-keys-v1.json").readText())
class P8CanonicalTest {
    @Test fun actualPinnedAospQuoteDiffersFromJsonOrgHostReplacement() {
        assertEquals("\"\\/\"",JSONObject.quote("/"))
        assertEquals("\"/\"",easyCanonical("/"))
        assertTrue(JSONObject::class.java.protectionDomain.codeSource.location.path.endsWith("aosp-json.jar"))
    }
    @Test fun productionCanonicalMatchesAllIndependentNodeAndSwiftKeyVectors() {
        val values=p8CanonicalVectors().getJSONArray("vectors")
        for(i in 0 until values.length()) {val v=values.getJSONObject(i);val input=v.get("input");val before=input.toString()
            assertEquals(v.getString("id"),v.getString("canonical"),easyCanonical(input));assertEquals(v.getString("sha256"),easyHash(easyCanonical(input).toByteArray()));assertEquals(before,input.toString())}
    }
    @Test fun exactSourceGraphRejectsKeyValueAndLegacyHashChanges() {
        val v=p8CanonicalVectors().getJSONArray("vectors").objects().single {it.getString("id")=="exact-four-source-graph"};val graph=v.getJSONObject("input");val expected=v.getString("sha256")
        assertEquals(expected,easyHash(easyCanonical(graph).toByteArray()))
        val old=easyHash(p8LegacyCanonical(graph).toByteArray());assertNotEquals(expected,old)
        for(change in listOf(JSONObject(graph.toString()).put("pairing-ui/fixture.mjs","0".repeat(64)),JSONObject(graph.toString()).apply {put("pairing-ui/fixture.mjS",remove("pairing-ui/fixture.mjs"))}))assertNotEquals(expected,easyHash(easyCanonical(change).toByteArray()))
        println("P8_CANONICAL_GRAPH: expected=$expected oldAndroid=$old quote=${JSONObject.quote("a/b")}")
    }
    @Test fun validDeclaredKeysKeepTheirPreviouslyValidFingerprint() {
        val v=p8CanonicalVectors().getJSONArray("vectors").objects().single {it.getString("id")=="ordinary-declared-keys"}
        assertEquals(v.getString("canonical"),p8LegacyCanonical(v.get("input")));assertEquals(easyCanonical(v.get("input")),p8LegacyCanonical(v.get("input")))
    }
    @Test fun originalProtectedJweHeaderIsAuthenticatedWithoutReserialization() {
        val v=p8CanonicalVectors().getJSONObject("protectedDelivery");val i=v.getJSONObject("identity");val identity=EasyWorkIdentity(i.getString("deviceId"),i.getString("privateKey"),i.getString("publicKey"));val original=v.getString("delivery");val parts=original.split('.').toMutableList()
        assertEquals(v.getString("deliverySha256"),easyHash(original.toByteArray()));assertEquals(v.getString("protectedHeaderSha256"),easyHash(easyBytes(parts[0])))
        assertEquals(v.getString("payloadCanonical"),easyCanonical(EasyWorkDeviceDelivery.open(identity,original,v.getString("deliveryId"))))
        val replacement=easyBase64(easyCanonical(JSONObject(String(easyBytes(parts[0]),Charsets.UTF_8))).toByteArray());assertNotEquals(parts[0],replacement);parts[0]=replacement
        assertTrue(runCatching {EasyWorkDeviceDelivery.open(identity,parts.joinToString("."),v.getString("deliveryId"))}.isFailure)
        assertEquals(v.getString("deliverySha256"),easyHash(original.toByteArray()))
    }
    @Test fun nodeReceiptWithSlashKeyUsesOriginalInputAndOneStatusReadWithoutReplay()=runBlocking {
        val helper=P8EasyWorkBoundaryTest()
        val vector=p8CanonicalVectors().getJSONArray("vectors").objects().single {it.getString("id")=="request-hash-slash-key"}
        val (requests,result)=helper.recovery(vector.getJSONObject("input").getJSONObject("params")) {helper.receipt(it).put("requestHash",vector.getString("sha256"))}
        assertEquals("original",result.getOrThrow().getString("receipt"));assertEquals(listOf("turn/start","command/status"),requests.map {it.getString("method")})
        assertEquals(vector.getJSONObject("input").getJSONObject("params").getJSONObject("metadata/path").getString("问\"题/😀"),requests.first().getJSONObject("params").getJSONObject("metadata/path").getString("问\"题/😀"))
    }
    @Test fun escapedLegacyReceiptHashNeverBecomesAnAcceptedFallbackOrReplay()=runBlocking {
        val helper=P8EasyWorkBoundaryTest()
        val vector=p8CanonicalVectors().getJSONArray("vectors").objects().single {it.getString("id")=="request-hash-slash-key"}
        val oldHash=easyHash(p8LegacyCanonical(vector.getJSONObject("input")).toByteArray());assertNotEquals(vector.getString("sha256"),oldHash)
        val (requests,result)=helper.recovery(vector.getJSONObject("input").getJSONObject("params")) {helper.receipt(it).put("requestHash",oldHash)}
        assertTrue(result.isFailure);assertEquals(listOf("turn/start","command/status"),requests.map {it.getString("method")})
    }
}
