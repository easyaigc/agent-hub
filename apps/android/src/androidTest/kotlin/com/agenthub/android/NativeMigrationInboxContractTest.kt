package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** SDK/JVM-compatible original graph vectors. No filesystem, Android grant, owner or activation proof. */
internal object InboxContractFixture {
    data class Vector(val text: Map<String,String>,val binary: Map<String,NativeMigrationBlob>,val bytes: Map<String,ByteArray>) {
        fun records()=NativeMigrationStreamRecords(text,binary,4)
    }
    fun id(value: String)=UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    fun blob(bytes: ByteArray)=NativeMigrationProbe().let {probe->probe.write(bytes);NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()){it.write(bytes)}}
    fun all(version: Int=2): Vector {
        val text=linkedMapOf<String,String>();val binary=linkedMapOf<String,NativeMigrationBlob>();val bytes=linkedMapOf<String,ByteArray>()
        val batches=JSONArray()
        for(phase in listOf("receiving","failed","pending","consumed")) {
            val slots=JSONArray();val count=if(phase=="pending")10 else 1
            for(n in 0 until count) {
                val fileId=id("$phase-file-$n");val slot=JSONObject().put("id",fileId)
                if(phase=="receiving")slot.put("file",JSONObject.NULL) else {
                    val data=ByteArray(65_537){((it+n*17+phase.length)%251).toByte()}
                    val file=CreationFile(fileId,"原附件-$phase-$n 😀.png","image/png",data.size.toLong(),NativeMigrationInbox.sha(data))
                    slot.put("file",file.body())
                    val payloadId=id("$phase-payload-$n");val payload=NativeDraftFiles.payloadName(fileId,payloadId)
                    binary[payload]=blob(data);bytes[payload]=data
                    if(phase=="pending")text["draft-file:$fileId"]=JSONObject().put("version",2).put("file",file.body()).put("storage","binary-chunks-v1").put("payloadId",payloadId).toString()
                    if(phase=="consumed")text["draft-thumbnail-v1:$fileId"]=JSONObject().put("version",1).put("edge",128).put("file",file.body()).put("png",JSONObject.NULL).put("thumbnailSha256",JSONObject.NULL).toString()
                }
                slots.put(slot)
            }
            val row=JSONObject().put("id",id("$phase-batch")).put("phase",phase).put("sourceHash","a".repeat(64)).put("slots",slots)
                .put("binding",JSONObject().put("key","original-target/draft/😀").put("generation","old-generation"))
            if(version==2)row.put("maximum",if(phase=="pending")10 else 5)
            batches.put(row)
        }
        text[NativeMigrationInboxGraph.INDEX]="\n "+JSONObject().put("version",version).put("revision",17).put("records",batches).toString()+" \n"
        return Vector(text,binary,bytes)
    }
    fun root()=NativeMigrationStreamRecords(mapOf("metadata" to "{}"),emptyMap(),4)
}

class NativeMigrationInboxContractTest {
    @Test fun exactAllPhaseOriginalsAndHistoricalTenSlotsStayInertAndContentAddressed() {
        val vector=InboxContractFixture.all();val root=InboxContractFixture.root()
        NativeMigrationInboxGraph.validate(vector.text,vector.binary)
        val archived=NativeMigrationInbox.append(root,vector.records());val graph=NativeMigrationInbox.validate(archived.text,archived.binary)
        assertEquals(1,graph.originals.size);val original=graph.originals.single()
        assertEquals(vector.text.keys+vector.binary.keys,original.entries.map {it.name}.toSet())
        assertTrue(archived.text.keys.none {it==NativeMigrationInboxGraph.INDEX||it.startsWith("draft-file:")})
        original.entries.forEachIndexed {n,entry->
            if(entry.kind=="text")assertEquals(vector.text.getValue(entry.name),archived.text.getValue(original.alias(n)))
            else {val out=java.io.ByteArrayOutputStream();archived.binary.getValue(original.alias(n)).verifiedCopy(out);assertArrayEquals(vector.bytes.getValue(entry.name),out.toByteArray())}
        }
        assertSame(archived,NativeMigrationInbox.append(archived,vector.records()))
        val changed=vector.copy(text=vector.text+mapOf(NativeMigrationInboxGraph.INDEX to vector.text.getValue(NativeMigrationInboxGraph.INDEX)+" "))
        val second=NativeMigrationInbox.append(archived,changed.records());assertEquals(2,NativeMigrationInbox.validate(second.text,second.binary).originals.size)
        val legacy=InboxContractFixture.all(1);NativeMigrationInboxGraph.validate(legacy.text,legacy.binary)
        assertEquals(10,JSONObject(legacy.text.getValue(NativeMigrationInboxGraph.INDEX)).getJSONArray("records").getJSONObject(2).getJSONArray("slots").length())
    }
    @Test fun interruptedAndConsumedResidueIsPreservedButOrphanUnknownAndMissingReadyBytesReject() {
        val vector=InboxContractFixture.all()
        fun rejected(text: Map<String,String> = vector.text,binary: Map<String,NativeMigrationBlob> = vector.binary) {
            assertTrue(runCatching {NativeMigrationInboxGraph.validate(text,binary)}.isFailure)
        }
        val index=JSONObject(vector.text.getValue(NativeMigrationInboxGraph.INDEX));val pending=index.getJSONArray("records").getJSONObject(2)
        val first=pending.getJSONArray("slots").getJSONObject(0).getString("id")
        rejected(vector.text-"draft-file:$first")
        val declared=NativeDraftFiles.record(JSONObject(vector.text.getValue("draft-file:$first"))).payload!!
        rejected(binary=vector.binary-declared)
        rejected(binary=vector.binary+mapOf(NativeDraftFiles.payloadName(InboxContractFixture.id("orphan"),InboxContractFixture.id("orphan-generation")) to InboxContractFixture.blob(byteArrayOf(1))))
        rejected(binary=vector.binary+mapOf(NativeDraftFiles.payloadName(first,InboxContractFixture.id("duplicate-generation")) to vector.binary.getValue(declared)))
        rejected(vector.text+mapOf("unrecognized-child-original" to "{}"))
        pending.put("maximum",6);rejected(vector.text+mapOf(NativeMigrationInboxGraph.INDEX to index.toString()))
        pending.put("maximum",10);index.put("future",true);rejected(vector.text+mapOf(NativeMigrationInboxGraph.INDEX to index.toString()))
        assertTrue(vector.binary.keys.any {it.contains(InboxContractFixture.id("consumed-file-0"))})
        assertFalse("draft-file:${InboxContractFixture.id("consumed-file-0")}" in vector.text)
    }
    @Test fun emptyArchiveUnknownCatalogAndMissingExtraOrChangedFramesHaveExplicitOutcomes() {
        val empty=NativeMigrationInbox.append(InboxContractFixture.root(),null);val original=NativeMigrationInbox.validate(empty.text,empty.binary).originals.single()
        assertTrue(original.entries.isEmpty());assertSame(empty,NativeMigrationInbox.append(empty,null))
        val vector=InboxContractFixture.all();val archived=NativeMigrationInbox.append(InboxContractFixture.root(),vector.records())
        fun rejected(text: Map<String,String> = archived.text,binary: Map<String,NativeMigrationBlob> = archived.binary){assertTrue(runCatching {NativeMigrationInbox.validate(text,binary)}.isFailure)}
        rejected(archived.text+mapOf(NativeMigrationInbox.RECORD to archived.text.getValue(NativeMigrationInbox.RECORD)+" ".repeat(1024*1024)))
        val alias=archived.text.keys.first {it.startsWith(NativeMigrationInbox.TEXT)};rejected(archived.text-alias)
        rejected(archived.text+mapOf(NativeMigrationInbox.TEXT+"0".repeat(64)+":0" to "{}"))
        val raw=JSONObject(archived.text.getValue(NativeMigrationInbox.RECORD));raw.put("version",2);rejected(archived.text+mapOf(NativeMigrationInbox.RECORD to raw.toString()))
        val binary=archived.binary.toMutableMap();val key=binary.keys.first();binary[key]=InboxContractFixture.blob(byteArrayOf(9));rejected(binary=binary)
        rejected(archived.text-NativeMigrationInbox.RECORD)
        rejected(binary=archived.binary+mapOf(NativeMigrationInbox.BINARY+"f".repeat(64)+":0" to InboxContractFixture.blob(byteArrayOf(1))))
    }
}
