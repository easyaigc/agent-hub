package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exact inert bookkeeping vectors. No Android permission request, media query or callback. */
internal object PhotoLocalContractFixture {
    val recent=NativeMigrationPhotoLocalKind.RECENTS
    val permission=NativeMigrationPhotoLocalKind.PERMISSION
    fun preferences()="\n "+JSONObject().put("version",1).put("ignored",JSONArray((1..256).map {"content://media/external/images/media/$it"}.reversed())).toString()+" \n"
    fun permissions()=mapOf("attempt-external" to listOf("android.permission.READ_EXTERNAL_STORAGE"),"attempt-images" to listOf("android.permission.READ_MEDIA_IMAGES"),"attempt-selected" to listOf("android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VISUAL_USER_SELECTED")).mapValues {(_,values)->JSONObject().put("version",1).put("permissions",JSONArray(values)).put("attempted",true).toString()}
    fun source(kind: NativeMigrationPhotoLocalKind,text: Map<String,String>)=NativeMigrationPhotoLocalSource(kind,"opaque-local-bookkeeping",true,if(text.isEmpty())null else NativeMigrationStreamRecords(text,emptyMap(),4),{})
}
class NativeMigrationPhotoLocalContractTest {
    @Test fun exactHistoricalGroupsAndIgnoredOrderRemainInertWithIndependentDomains() {
        val f=PhotoLocalContractFixture;val originals=listOf(f.source(f.recent,mapOf("preferences" to f.preferences())),f.source(f.permission,f.permissions()))
        var archive=InboxContractFixture.root()
        for(source in originals)archive=NativeMigrationPhotoLocals.append(archive,source)
        val names=NativeMigrationPhotoLocals.validate(archive.text,archive.binary);assertEquals(6,names.size)
        assertTrue(archive.text.keys.none {it=="preferences"||it.startsWith("attempt-")})
        for(source in originals) {
            val catalog=JSONObject(archive.text.getValue(NativeMigrationPhotoLocals.record(source.kind))).getJSONArray("archives").getJSONObject(0)
            val entries=catalog.getJSONArray("entries");val id=catalog.getString("id")
            for(n in 0 until entries.length())assertEquals(source.records!!.text.getValue(entries.getJSONObject(n).getString("name")),archive.text.getValue(NativeMigrationPhotoLocals.textPrefix(source.kind)+id+":"+n))
            assertSame(archive,NativeMigrationPhotoLocals.append(archive,source))
        }
        val one=NativeMigrationPhotoLocals.append(InboxContractFixture.root(),f.source(f.recent,emptyMap()))
        val two=NativeMigrationPhotoLocals.append(one,f.source(f.permission,emptyMap()));assertEquals(2,NativeMigrationPhotoLocals.validate(two.text,two.binary).size)
        assertNotEquals(JSONObject(two.text.getValue(NativeMigrationPhotoLocals.record(f.recent))).getJSONArray("archives").getJSONObject(0).getString("id"),JSONObject(two.text.getValue(NativeMigrationPhotoLocals.record(f.permission))).getJSONArray("archives").getJSONObject(0).getString("id"))
    }
    @Test fun unknownCoercedFutureGroupsAndMalformedMediaReferencesReject() {
        val f=PhotoLocalContractFixture
        fun reject(kind: NativeMigrationPhotoLocalKind,text: Map<String,String>)=assertTrue(runCatching {NativeMigrationPhotoLocals.validateOriginal(kind,text,emptyMap())}.isFailure)
        reject(f.recent,mapOf("future" to "{}"));reject(f.recent,mapOf("preferences" to JSONObject(f.preferences()).put("version",2).toString()))
        reject(f.recent,mapOf("preferences" to JSONObject(f.preferences()).put("ignored",JSONArray(listOf("content://media/external/images/media/1","content://media/external/images/media/1"))).toString()))
        reject(f.recent,mapOf("preferences" to JSONObject(f.preferences()).put("ignored",JSONArray(listOf("content://other.invalid/1"))).toString()))
        reject(f.permission,mapOf("attempt-images" to JSONObject(f.permissions().getValue("attempt-images")).put("attempted","true").toString()))
        reject(f.permission,mapOf("attempt-selected" to JSONObject(f.permissions().getValue("attempt-selected")).put("permissions",JSONArray(listOf("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))).toString()))
        reject(f.permission,mapOf("attempt-future" to f.permissions().getValue("attempt-images")))
        reject(f.permission,mapOf("attempt-images" to JSONObject(f.permissions().getValue("attempt-images")).put("future",true).toString()))
    }
    @Test fun missingExtraChangedAndBinaryAliasesDoNotBecomePartialBookkeepingArchives() {
        val f=PhotoLocalContractFixture;val archive=NativeMigrationPhotoLocals.append(InboxContractFixture.root(),f.source(f.recent,mapOf("preferences" to f.preferences())))
        fun reject(text: Map<String,String> = archive.text,binary: Map<String,NativeMigrationBlob> = archive.binary)=assertTrue(runCatching {NativeMigrationPhotoLocals.validate(text,binary)}.isFailure)
        val alias=archive.text.keys.first {it.startsWith(NativeMigrationPhotoLocals.textPrefix(f.recent))}
        reject(archive.text-alias);reject(archive.text+mapOf(alias to "{}"));reject(archive.text-NativeMigrationPhotoLocals.record(f.recent))
        reject(archive.text+mapOf(NativeMigrationPhotoLocals.textPrefix(f.permission)+"a".repeat(64)+":0" to "{}"))
        reject(binary=mapOf(NativeMigrationPhotoLocals.binaryPrefix(f.recent)+"b".repeat(64)+":0" to InboxContractFixture.blob(byteArrayOf(1))))
    }
    @Test fun sourceAndDestinationOriginalsUnionWithoutApplyingLocalState() {
        val f=PhotoLocalContractFixture;val first=NativeMigrationPhotoLocals.append(InboxContractFixture.root(),f.source(f.recent,mapOf("preferences" to f.preferences())))
        val next=JSONObject(f.preferences()).put("ignored",JSONArray(listOf("content://media/external/images/media/999"))).toString()
        val destination=NativeMigrationPhotoLocals.append(InboxContractFixture.root(),f.source(f.recent,mapOf("preferences" to next)))
        val merged=NativeMigrationOriginals.merge(first,destination)
        assertEquals(2,JSONObject(merged.text.getValue(NativeMigrationPhotoLocals.record(f.recent))).getJSONArray("archives").length())
        assertEquals(merged,NativeMigrationOriginals.merge(merged,destination));assertEquals("{}",merged.text["metadata"])
        assertFalse("preferences" in merged.text);assertTrue(merged.binary.isEmpty())
    }
}
