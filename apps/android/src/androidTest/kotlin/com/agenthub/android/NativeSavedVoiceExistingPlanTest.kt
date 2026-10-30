package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Memory-only closed-contract vectors; no key, storage, original recording or fixture is created. */
class NativeSavedVoiceExistingPlanTest {
    @Test fun asrOriginalProofCanonicalRetainsItsOriginalSlashProfile() {
        val value=JSONObject().put("z","audio/wav").put("a/b",JSONArray().put("x/y").put(JSONObject.NULL).put(true).put(9))
        assertEquals("""{"a\/b":["x\/y",null,true,9],"z":"audio\/wav"}""",NativeSavedVoiceExistingProtection.asrProofCanonical(value))
        assertEquals("""{"a/b":["x/y",null,true,9],"z":"audio/wav"}""",easyCanonical(value))
        assertEquals("9007199254740991",NativeSavedVoiceExistingProtection.asrProofCanonical(9_007_199_254_740_991L))
        assertEquals("null",NativeSavedVoiceExistingProtection.asrProofCanonical(null))
    }
    @Test fun asrOriginalProofRejectsNumberProfilesNeverUsedByOriginalHarness() {
        for(value in listOf<Any>(0.5,0.5f,java.math.BigInteger.ONE))
            assertThrows(IllegalStateException::class.java){NativeSavedVoiceExistingProtection.asrProofCanonical(value)}
    }
    companion object {
        private fun id(n: Int)="00000000-0000-4000-8000-"+n.toString().padStart(12,'0')
        fun value(): JSONObject {
            fun installed()=JSONObject().put("uid",10076).put("appSha256","1".repeat(64)).put("runnerSha256","2".repeat(64)).put("signerSetSha256","3".repeat(64))
            val originals=JSONArray()
            for((index,locale)in listOf("en-US","zh-CN").withIndex()) {
                val record=id(20+index);val files=JSONObject()
                for((name,format)in mapOf("voice-index-v1" to 3,"voice-recording:"+record to 3,"voice-audio:"+record to 4))files.put(NativeSavedVoiceExistingPlan.recordName(name),JSONObject().put("sha256","4".repeat(64)).put("bytes",100).put("format",format))
                val o=JSONObject().put("lease",id(10+index)).put("locale",locale).put("originalId",record).put("audioBytes",3244).put("files",files)
                for(k in listOf("declarationSha256","baselineSha256","intentSha256","submissionSha256","rowSha256","canonicalSha256","audioSha256"))o.put(k,"4".repeat(64))
                originals.put(o)
            }
            return JSONObject().put("version",2).put("action","p7-api26-existing-protection-v2").put("lease",id(1)).put("postwriteRun",id(2)).put("currentObservationId",id(3))
                .put("currentObservationSha256","5".repeat(64)).put("currentBootId",id(4)).put("currentBootCount",7).put("currentInstalled",installed())
                .put("bootstrap",JSONObject().put("bootstrapId",id(5)).put("originalInstalled",installed()).put("intentSha256","6".repeat(64)).put("completedSha256","7".repeat(64))
                    .put("ciphertextSha256","8".repeat(64)).put("ciphertextBytes",160).put("familyDigest","9".repeat(64))).put("originals",originals)
                .put("currentDefaultFamily",JSONObject().put("roots",JSONArray(listOf("mobile-v1"))).put("directories",1)
                    .put("files",1).put("bytes",160).put("familyDigest","a".repeat(64)))
        }
    }
    @Test fun currentFamilyIsClosedAndIndependentOfHistoricalBootstrap() {
        fun parse(v: JSONObject): NativeSavedVoiceExistingPlan {val raw=v.toString().toByteArray();return NativeSavedVoiceExistingPlan.parse(raw,NativeSavedVoiceExistingPlan.sha(raw))}
        val one=parse(value());assertNotEquals(one.bootstrap.getString("familyDigest"),one.currentDefaultFamily.getString("familyDigest"))
        val all=value();val family=all.getJSONObject("currentDefaultFamily")
        family.put("roots",JSONArray(NativeSavedVoiceExistingPlan.currentDefaultRoots.sorted())).put("directories",11)
        assertEquals(11,parse(all).currentDefaultFamily.getJSONArray("roots").length())
        val changes=listOf<Pair<String,(JSONObject)->Unit>>(
            "v1 rejected" to {it.put("version",1).put("action","p7-api26-existing-protection-v1")},
            "missing family" to {it.remove("currentDefaultFamily")},
            "unknown family field" to {it.getJSONObject("currentDefaultFamily").put("extra",true)},
            "missing family digest" to {it.getJSONObject("currentDefaultFamily").remove("familyDigest")},
            "empty roots" to {it.getJSONObject("currentDefaultFamily").put("roots",JSONArray())},
            "duplicate roots" to {it.getJSONObject("currentDefaultFamily").put("roots",JSONArray(listOf("mobile-v1","mobile-v1"))).put("directories",2)},
            "unsorted roots" to {it.getJSONObject("currentDefaultFamily").put("roots",JSONArray(listOf("mobile-v1-recent-photos","mobile-v1"))).put("directories",2)},
            "unknown sibling" to {it.getJSONObject("currentDefaultFamily").put("roots",JSONArray(listOf("mobile-v1","mobile-v1-unknown"))).put("directories",2)},
            "nested root" to {it.getJSONObject("currentDefaultFamily").put("roots",JSONArray(listOf("mobile-v1","mobile-v1/camera-results"))).put("directories",2)},
            "missing mobile-v1" to {it.getJSONObject("currentDefaultFamily").put("roots",JSONArray(listOf("mobile-v1-recent-photos")))},
            "non-string root" to {it.getJSONObject("currentDefaultFamily").put("roots",JSONArray().put(true))},
            "directory count mismatch" to {it.getJSONObject("currentDefaultFamily").put("directories",2)},
            "nested extra directory" to {it.getJSONObject("currentDefaultFamily").put("directories",12)},
            "bool directories" to {it.getJSONObject("currentDefaultFamily").put("directories",true)},
            "extra nonempty child file" to {it.getJSONObject("currentDefaultFamily").put("files",2)},
            "missing cipher" to {it.getJSONObject("currentDefaultFamily").put("files",0)},
            "bool files" to {it.getJSONObject("currentDefaultFamily").put("files",true)},
            "extra bytes" to {it.getJSONObject("currentDefaultFamily").put("bytes",161)},
            "bool bytes" to {it.getJSONObject("currentDefaultFamily").put("bytes",true)},
            "bad current digest" to {it.getJSONObject("currentDefaultFamily").put("familyDigest","A".repeat(64))}
        )
        for((name,change)in changes) {val v=value();change(v);assertThrows(name,Exception::class.java){parse(v)}}
        println("P7_EXISTING_CURRENT_FAMILY_VECTORS="+(changes.size+2))
    }
    @Test fun closedIdentityNamespaceAndMissingFieldVectorsRejectBeforeAnyAccess() {
        fun parse(v: JSONObject): NativeSavedVoiceExistingPlan {val raw=v.toString().toByteArray();return NativeSavedVoiceExistingPlan.parse(raw,NativeSavedVoiceExistingPlan.sha(raw))}
        assertEquals(2,parse(value()).originals.size)
        val changes=listOf<Pair<String,(JSONObject)->Unit>>(
            "unknown field" to {it.put("extra",true)},"missing field" to {it.remove("bootstrap")},"wrong action" to {it.put("action","initialize-empty-default")},
            "bool version" to {it.put("version",true)},"bool boot" to {it.put("currentBootCount",false)},
            "negative boot" to {it.put("currentBootCount",-1)},"old boot field" to {it.put("originalBootCount",7)},"reused current observation" to {it.put("currentObservationId",it.getString("lease"))},
            "old bootstrap reused as current lease" to {it.put("lease",it.getJSONObject("bootstrap").getString("bootstrapId"))},
            "bad hash" to {it.put("currentObservationSha256","not-a-hash")},"different old UID" to {it.getJSONObject("bootstrap").getJSONObject("originalInstalled").put("uid",10077)},
            "different signer" to {it.getJSONObject("bootstrap").getJSONObject("originalInstalled").put("signerSetSha256","0".repeat(64))},
            "no originals" to {it.put("originals",JSONArray())},"duplicate original" to {it.getJSONArray("originals").put(1,it.getJSONArray("originals").getJSONObject(0))},
            "unknown locale" to {it.getJSONArray("originals").getJSONObject(0).put("locale","other")},
            "oversized audio" to {it.getJSONArray("originals").getJSONObject(0).put("audioBytes",9_600_045)},
            "bool audio" to {it.getJSONArray("originals").getJSONObject(0).put("audioBytes",true)},
            "bool UID" to {it.getJSONObject("currentInstalled").put("uid",true)},
            "unknown key alias" to {it.getJSONArray("originals").getJSONObject(0).put("keyAlias","other")},
            "unknown path" to {it.getJSONArray("originals").getJSONObject(0).put("namespace","../default")},
            "missing namespace file" to {val f=it.getJSONArray("originals").getJSONObject(0).getJSONObject("files");f.remove(f.keys().next())},
            "unknown file" to {it.getJSONArray("originals").getJSONObject(0).getJSONObject("files").put("unexpected.bin",JSONObject())},
            "unsupported format" to {val f=it.getJSONArray("originals").getJSONObject(0).getJSONObject("files");f.getJSONObject(f.keys().next()).put("format",1)},
            "zero cipher length" to {it.getJSONObject("bootstrap").put("ciphertextBytes",0)},
            "oversized cipher" to {it.getJSONObject("bootstrap").put("ciphertextBytes",8193)}
        )
        for((name,change)in changes) {val v=value();change(v);assertThrows(name,Exception::class.java){parse(v)}}
        val raw=value().toString().toByteArray();assertThrows(Exception::class.java){NativeSavedVoiceExistingPlan.parse(raw,"0".repeat(64))}
        val floatVersion=value().toString().replace("\"version\":2","\"version\":2.0").toByteArray();assertThrows(Exception::class.java){NativeSavedVoiceExistingPlan.parse(floatVersion,NativeSavedVoiceExistingPlan.sha(floatVersion))}
        val duplicate=("{\"version\":2,"+value().toString().drop(1)).toByteArray();assertThrows(Exception::class.java){NativeSavedVoiceExistingPlan.parse(duplicate,NativeSavedVoiceExistingPlan.sha(duplicate))}
        val invalidUtf8=byteArrayOf(0xff.toByte());assertThrows(Exception::class.java){NativeSavedVoiceExistingPlan.parse(invalidUtf8,NativeSavedVoiceExistingPlan.sha(invalidUtf8))}
        println("P7_EXISTING_PLAN_VECTORS="+(changes.size+5))
    }
}
