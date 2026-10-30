package com.agenthub.android

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Closed Root-observed contract. Contains hashes/identities, never credentials or an executable operation. */
internal class NativeSavedVoiceExistingPlan private constructor(val value: JSONObject,val sha256: String) {
    val lease get()=value.getString("lease")
    val postwriteRun get()=value.getString("postwriteRun")
    val installed get()=value.getJSONObject("currentInstalled")
    val bootstrap get()=value.getJSONObject("bootstrap")
    val currentDefaultFamily get()=value.getJSONObject("currentDefaultFamily")
    val originals get()=value.getJSONArray("originals").let {a->(0 until a.length()).map {a.getJSONObject(it)}}
    companion object {
        fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun keys(v: JSONObject,vararg expected: String){require(v.keys().asSequence().toSet()==expected.toSet()) {"Unknown or missing protection field"}}
        fun uuid(s: String)=s.also {require(UUID.fromString(it).toString()==it)}
        fun hash(s: String)=s.also {require(it.matches(Regex("[a-f0-9]{64}")))}
        fun integer(v: JSONObject,name: String,min: Long,max: Long): Long {
            val raw=v.get(name);require(raw is Int||raw is Long);return (raw as Number).toLong().also {require(it in min..max)}
        }
        fun installed(v: JSONObject) {
            keys(v,"uid","appSha256","runnerSha256","signerSetSha256")
            integer(v,"uid",10_000,Int.MAX_VALUE.toLong());for(k in listOf("appSha256","runnerSha256","signerSetSha256"))hash(v.getString(k))
        }
        /** Exact already implemented ungenerated system stores; unknown prefix roots remain refused. */
        val currentDefaultRoots=setOf(
            "mobile-v1","mobile-v1-camera-results","mobile-v1-camera-results-outputs","mobile-v1-camera-results-payloads",
            "mobile-v1-document-results","mobile-v1-document-results-payloads","mobile-v1-external-inbox",
            "mobile-v1-photo-permission","mobile-v1-photo-results","mobile-v1-photo-results-payloads","mobile-v1-recent-photos")
        fun currentFamily(v: JSONObject,ciphertextBytes: Long) {
            keys(v,"roots","directories","files","bytes","familyDigest")
            val a=v.getJSONArray("roots");require(a.length() in 1..currentDefaultRoots.size)
            val roots=(0 until a.length()).map {require(a.get(it) is String);a.getString(it)}
            require(roots==roots.sorted()&&roots.distinct().size==roots.size&&"mobile-v1" in roots&&roots.all {it in currentDefaultRoots})
            require(integer(v,"directories",1,currentDefaultRoots.size.toLong())==roots.size.toLong())
            require(integer(v,"files",1,1)==1L&&integer(v,"bytes",29,8192)==ciphertextBytes)
            hash(v.getString("familyDigest"))
        }
        fun recordName(name: String)=sha(name.toByteArray(Charsets.UTF_8))+".bin"
        fun parse(bytes: ByteArray,expectedSha256: String): NativeSavedVoiceExistingPlan {
            require(bytes.size in 1..32_768&&sha(bytes)==hash(expectedSha256))
            val v=StrictMigrationJson.parse(bytes)
            keys(v,"version","action","lease","postwriteRun","currentObservationId","currentObservationSha256","currentBootId","currentBootCount","currentInstalled","bootstrap","originals","currentDefaultFamily")
            require(v.get("version") is Int&&v.getInt("version")==2&&v.getString("action")=="p7-api26-existing-protection-v2")
            val ids=listOf("lease","postwriteRun","currentObservationId","currentBootId").map {uuid(v.getString(it))};require(ids.distinct().size==ids.size)
            hash(v.getString("currentObservationSha256"));integer(v,"currentBootCount",0,Int.MAX_VALUE.toLong());installed(v.getJSONObject("currentInstalled"))
            val b=v.getJSONObject("bootstrap")
            keys(b,"bootstrapId","originalInstalled","intentSha256","completedSha256","ciphertextSha256","ciphertextBytes","familyDigest")
            uuid(b.getString("bootstrapId"));require(b.getString("bootstrapId") !in ids);installed(b.getJSONObject("originalInstalled"))
            for(k in listOf("uid","signerSetSha256"))require(b.getJSONObject("originalInstalled").get(k)==v.getJSONObject("currentInstalled").get(k))
            for(k in listOf("intentSha256","completedSha256","ciphertextSha256","familyDigest"))hash(b.getString(k))
            currentFamily(v.getJSONObject("currentDefaultFamily"),integer(b,"ciphertextBytes",29,8192))
            val a=v.getJSONArray("originals");require(a.length()==2)
            val originalIds=mutableSetOf<String>();val leases=mutableSetOf<String>();val locales=mutableSetOf<String>()
            for(i in 0 until a.length()) {
                val o=a.getJSONObject(i)
                keys(o,"lease","locale","originalId","declarationSha256","baselineSha256","intentSha256","submissionSha256","rowSha256","canonicalSha256","audioSha256","audioBytes","files")
                require(leases.add(uuid(o.getString("lease")))&&originalIds.add(uuid(o.getString("originalId"))))
                require(o.getString("lease") !in ids&&o.getString("originalId") !in ids)
                require(o.getString("locale") in setOf("en-US","zh-CN")&&locales.add(o.getString("locale")))
                for(k in listOf("declarationSha256","baselineSha256","intentSha256","submissionSha256","rowSha256","canonicalSha256","audioSha256"))hash(o.getString(k))
                integer(o,"audioBytes",46,9_600_044)
                val files=o.getJSONObject("files");val id=o.getString("originalId")
                val names=mapOf(recordName("voice-index-v1") to 3,recordName("voice-recording:"+id) to 3,recordName("voice-audio:"+id) to 4)
                require(files.keys().asSequence().toSet()==names.keys)
                for((name,format)in names) {
                    val f=files.getJSONObject(name);keys(f,"sha256","bytes","format");hash(f.getString("sha256"));integer(f,"bytes",29,13*1024*1024)
                    require(f.get("format") is Int&&f.getInt("format")==format)
                }
            }
            require((ids+listOf(b.getString("bootstrapId"))+leases+originalIds).distinct().size==9)
            return NativeSavedVoiceExistingPlan(v,expectedSha256)
        }
    }
}
