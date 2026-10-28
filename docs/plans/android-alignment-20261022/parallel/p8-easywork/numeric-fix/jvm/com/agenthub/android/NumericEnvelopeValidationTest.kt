package com.agenthub.android

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Correctness checks for the fix, distinct from the historical defect-reproduction probe. */
class NumericEnvelopeValidationTest {
    private val root get()=File(System.getProperty("p8.root"))
    private val original get()=JSONObject(File(root,"apps/android/src/androidTest/assets/easywork-crypto-vector.json").readText())
    private val vectors get()=JSONObject(File(root,"docs/plans/android-alignment-20261022/parallel/p8-easywork/numeric-fix/numeric-vectors.json").readText())
    private fun replaceToken(base: String,field: String,token: String): ByteArray {
        val match=Regex("\""+field+"\"\\s*:\\s*(?:42|1)(?=[,}])").find(base)?:error("Missing fixture field")
        return base.replaceRange(match.range,match.value.substringBefore(':')+":"+token).toByteArray(Charsets.UTF_8)
    }
    @Test fun nodeLegacyAndIndependentSafeBoundaryFramesRemainValid() {
        val v=original;val crypto=EasyWorkCrypto(EasyWorkCredential.decode(v.getJSONObject("credential")),EasyWorkBinding(v.getJSONObject("credential").getJSONObject("binding").toString()))
        for(name in listOf("frame","legacy")) {
            val opened=crypto.open(v.getJSONObject(name).toString().toByteArray())
            assertEquals(42L,opened.sequence);assertEquals(easyCanonical(v.getJSONObject("payload")),easyCanonical(opened.payload))
        }
        val frames=vectors.getJSONObject("frames")
        for((name,sequence) in listOf("minimum" to 1L,"maximum" to 9007199254740991L)) {
            val opened=crypto.open(frames.getJSONObject(name).toString().toByteArray())
            assertEquals(sequence,opened.sequence);assertEquals(easyCanonical(v.getJSONObject("payload")),easyCanonical(opened.payload))
        }
    }
    @Test fun rawNumberTypesBoundsAndVersionAreValidatedBeforeAuthentication() {
        val v=original;val c=EasyWorkCredential.decode(v.getJSONObject("credential"));val crypto=EasyWorkCrypto(c,c.binding)
        val cases=vectors.getJSONArray("cases");val base=v.getJSONObject("frame").toString();var accepted=0;var refused=0
        for(index in 0 until cases.length()) {
            val case=cases.getJSONObject(index);val bytes=replaceToken(base,case.getString("field"),case.getString("token"))
            val result=runCatching { crypto.open(bytes) }
            if(case.getBoolean("accepted")) { assertEquals(case.getString("id"),42L,result.getOrThrow().sequence);accepted++ }
            else { val e=result.exceptionOrNull();assertTrue("${case.getString("id")} must fail numeric/JSON validation, not authentication: ${e?.javaClass?.name}",e is IllegalArgumentException||e is JSONException);refused++ }
        }
        // Removed fields cannot default to the authenticated integer values either.
        for(field in listOf("sequence","version")) {
            val frame=JSONObject(base).apply { remove(field) }
            assertTrue(runCatching { crypto.open(frame.toString().toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
        }
        File(System.getProperty("p8.numeric.output")).writeText(JSONObject().put("status","fixed-inbound-numeric-validation-passed")
            .put("acceptedNumericCases",accepted).put("rejectedNumericCases",refused).put("missingFieldsRejected",2)
            .put("nativeAndroidExecuted",false).put("jsonRuntime","org.json:json:20250517").toString())
    }
}
