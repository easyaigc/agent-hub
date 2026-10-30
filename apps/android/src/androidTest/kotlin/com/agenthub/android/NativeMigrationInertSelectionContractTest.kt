package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Common archive/audit vectors only. No native callback, publication or OS authority proof. */
class NativeMigrationInertSelectionContractTest {
    private val document=NativeMigrationSelectionJournal.Kind.DOCUMENT
    private fun view(journal: NativeMigrationStreamRecords?=null,payloads: NativeMigrationStreamRecords?=null,journalPresent: Boolean=true)=
        NativeMigrationSelectionSource(document,NativeMigrationSelectionSourceNode(journalPresent,journal),NativeMigrationSelectionSourceNode(journalPresent,payloads),null,null,{})
    @Test fun commonEngineKeepsOldInboxIdentityAndExactSelectionBytesOutsideActiveNames() {
        val empty=NativeMigrationInbox.append(InboxContractFixture.root(),null)
        assertEquals("0aa752c6e3aefe4d6dc024119d80fd77b160896acddef8d19b12fd9cb24ece30",NativeMigrationInbox.validate(empty.text,empty.binary).originals.single().id)
        val (journal,payload)=NativeMigrationSelectionContractTest().linked()
        val selection=view(journal,payload);val archived=NativeMigrationSelections.append(InboxContractFixture.root(),selection)
        val names=NativeMigrationSelections.validate(archived.text,archived.binary)
        assertTrue(names.first.contains(NativeMigrationSelections.record(document)));assertEquals(payload.binary.size,names.second.size)
        val catalog=JSONObject(archived.text.getValue(NativeMigrationSelections.record(document))).getJSONArray("archives").getJSONObject(0)
        val id=catalog.getString("id");val entries=catalog.getJSONArray("entries")
        for(n in 0 until entries.length()) {
            val entry=entries.getJSONObject(n);val name=entry.getString("name");val prefix=if(entry.getString("kind")=="text")NativeMigrationSelections.textPrefix(document)else NativeMigrationSelections.binaryPrefix(document)
            val alias="$prefix$id:$n"
            when {
                name.startsWith("journal:")->assertEquals(journal.text.getValue(name.removePrefix("journal:")),archived.text.getValue(alias))
                name.startsWith("payloads:")&&entry.getString("kind")=="text"->assertEquals(payload.text.getValue(name.removePrefix("payloads:")),archived.text.getValue(alias))
                name.startsWith("payloads:")->assertEquals(payload.binary.getValue(name.removePrefix("payloads:")).sha256,archived.binary.getValue(alias).sha256)
                else->assertEquals("selection-original-v1",name)
            }
        }
        assertTrue(archived.text.keys.none {it==NativeMigrationSelectionJournal.RECORD||it==NativeMigrationInboxGraph.INDEX||it.startsWith("draft-file:")})
        assertSame(archived,NativeMigrationSelections.append(archived,selection))
    }
    @Test fun topologyDistinguishesAbsenceAndEmptyAndUnprovedCameraClaimsRefuse() {
        val absent=NativeMigrationSelections.append(InboxContractFixture.root(),view(journalPresent=false))
        val both=NativeMigrationSelections.append(absent,view());NativeMigrationSelections.validate(both.text,both.binary)
        assertEquals(2,JSONObject(both.text.getValue(NativeMigrationSelections.record(document))).getJSONArray("archives").length())
        val camera=NativeMigrationSelectionJournal.Kind.CAMERA;val node=NativeMigrationSelectionSourceNode(true,null)
        val empty=NativeMigrationSelectionSource(camera,node,node,node,NativeMigrationSelectionRawStatus("a".repeat(32),true),{})
        val archived=NativeMigrationSelections.append(both,empty);NativeMigrationSelections.validate(archived.text,archived.binary)
        val id=InboxContractFixture.id("inert-output")
        val claim=NativeMigrationStreamRecords(mapOf("output-v1-$id" to JSONObject().put("version",1).put("id",id).toString()),emptyMap(),4)
        assertTrue(runCatching {NativeMigrationSelections.append(both,empty.copy(outputs=node.copy(records=claim)))}.isFailure)
        assertTrue(runCatching {NativeMigrationSelections.append(both,view(journalPresent=false).copy(payloads=NativeMigrationSelectionSourceNode(true,null)))}.isFailure)
    }
    @Test fun missingChangedExtraAndWrongFamilyAliasesCannotBecomePartialSelectionArchives() {
        val (journal,payload)=NativeMigrationSelectionContractTest().linked();val archived=NativeMigrationSelections.append(InboxContractFixture.root(),view(journal,payload))
        fun rejected(text: Map<String,String> = archived.text,binary: Map<String,NativeMigrationBlob> = archived.binary){assertTrue(runCatching {NativeMigrationSelections.validate(text,binary)}.isFailure)}
        val alias=archived.text.keys.first {it.startsWith(NativeMigrationSelections.textPrefix(document))}
        rejected(archived.text-alias);rejected(archived.text+mapOf(alias to "{}"))
        rejected(archived.text+mapOf(NativeMigrationSelections.textPrefix(NativeMigrationSelectionJournal.Kind.PHOTO)+"a".repeat(64)+":0" to "{}"))
        val binary=archived.binary.toMutableMap();binary[binary.keys.first()]=InboxContractFixture.blob(byteArrayOf(9));rejected(binary=binary)
        rejected(archived.text-NativeMigrationSelections.record(document))
    }
    private fun event(name: String,sources: List<String> = listOf("not a provider URI # raw return"))=NativeSelectionReturnAudit.capture("owned-opaque-root/document-results",document,InboxContractFixture.id(name),null,sources=sources)
    @Test fun firstReturnReceiptRemainsExactAcrossColdReadDuplicateAndChangedCallback() {
        val event=event("first",List(11){"content://owned.invalid/$it"})
        val first=NativeSelectionReturnAudit.admit(null,event);assertTrue(first.acceptsOriginalReturn)
        val cold=NativeSelectionReturnAudit.read(first.raw).single();assertEquals(event.raw,cold.raw);assertEquals(11,cold.sources!!.size)
        val same=NativeSelectionReturnAudit.admit(first.raw,event);assertEquals(first.raw,same.raw);assertTrue(same.acceptsOriginalReturn)
        val changed=NativeSelectionReturnAudit.admit(first.raw,event("first",emptyList()));assertFalse(changed.acceptsOriginalReturn);assertEquals(first.raw,changed.raw)
        assertEquals(setOf(NativeSelectionReturnAudit.RECORD),NativeSelectionReturnAudit.validate(mapOf(NativeSelectionReturnAudit.RECORD to first.raw)))
    }
    @Test fun sourceAndLaterDestinationReceiptsMergeWithoutOverwriteOrExecutableProjection() {
        val original=NativeSelectionReturnAudit.admit(null,event("source")).raw
        val destination=NativeSelectionReturnAudit.admit(null,event("destination")).raw
        val source=mapOf("metadata" to "{}",NativeSelectionReturnAudit.RECORD to original)
        val merged=NativeSelectionReturnAudit.merge(source,mapOf(NativeSelectionReturnAudit.RECORD to destination))
        assertEquals(2,NativeSelectionReturnAudit.read(merged[NativeSelectionReturnAudit.RECORD]).size)
        assertEquals(merged,NativeSelectionReturnAudit.merge(merged,mapOf(NativeSelectionReturnAudit.RECORD to destination)))
        val conflicting=NativeSelectionReturnAudit.admit(null,event("source",emptyList())).raw
        assertTrue(runCatching {NativeSelectionReturnAudit.merge(source,mapOf(NativeSelectionReturnAudit.RECORD to conflicting))}.isFailure)
        assertEquals(setOf("metadata",NativeSelectionReturnAudit.RECORD),merged.keys)
    }
    @Test fun auditUnknownVersionFieldsDuplicateIdsAndCapacityRefuseWithoutChangingFirstReceipt() {
        val first=NativeSelectionReturnAudit.admit(null,event("first")).raw
        val root=JSONObject(first);root.put("version",2);assertTrue(runCatching {NativeSelectionReturnAudit.read(root.toString())}.isFailure)
        val raw=JSONObject(event("first").raw).put("future",true).toString();assertTrue(runCatching {NativeSelectionReturnAudit.decode(raw)}.isFailure)
        val duplicate=JSONObject(first).put("events",JSONArray(listOf(event("first").raw,event("first").raw))).toString()
        assertTrue(runCatching {NativeSelectionReturnAudit.read(duplicate)}.isFailure)
        var full: String?=null;repeat(128){full=NativeSelectionReturnAudit.admit(full,event("bounded-$it")).raw}
        val before=full;assertTrue(runCatching {NativeSelectionReturnAudit.admit(full,event("overflow"))}.isFailure);assertEquals(before,full)
    }
    @Test fun allInertFamiliesMergeExactDestinationBytesAndLegacyInputUsesSameStreamGraph() {
        val (journal,payload)=NativeMigrationSelectionContractTest().linked()
        val destination=NativeMigrationSelections.append(NativeMigrationInbox.append(InboxContractFixture.root(),InboxContractFixture.all().records()),view(journal,payload))
        val source=InboxContractFixture.root().copy(text=mapOf("metadata" to "source metadata remains original"))
        val merged=NativeMigrationOriginals.merge(source,destination)
        assertEquals("source metadata remains original",merged.text["metadata"])
        assertEquals(NativeMigrationSelections.validate(destination.text,destination.binary),NativeMigrationSelections.validate(merged.text,merged.binary))
        assertEquals(1,NativeMigrationInbox.validate(merged.text,merged.binary.filterKeys {it.startsWith(NativeMigrationInbox.BINARY)}).originals.size)
        assertEquals(merged,NativeMigrationOriginals.merge(merged,destination))
        for((name,blob)in destination.binary)assertEquals(blob.sha256,merged.binary.getValue(name).sha256)
        val original=byteArrayOf(1,0,-1,7)
        val legacy=NativeMigrationRecords(mapOf("metadata" to "{}"),mapOf("original" to java.util.Base64.getEncoder().encodeToString(original)))
        val stream=legacy.stream();val output=java.io.ByteArrayOutputStream();stream.binary.getValue("original").verifiedCopy(output);assertArrayEquals(original,output.toByteArray())
    }
    @Test fun captureCountAndExactUtf8ByteBoundsRejectWithoutWeakeningStrictDecoder() {
        val id=InboxContractFixture.id("oversized-return")
        fun capture(sources: List<String>)=NativeSelectionReturnAudit.capture("owned-original",document,id,null,sources=sources)
        assertEquals("source-count",(runCatching {capture(List(8193){"content://owned.invalid/$it"})}.exceptionOrNull() as NativeSelectionReturnAudit.CaptureLimit).boundary)
        val multibyte="中".repeat(NativeMigrationTextFrames.MAX_RECORD_BYTES/3+1)
        assertEquals("source-bytes",(runCatching {capture(listOf(multibyte))}.exceptionOrNull() as NativeSelectionReturnAudit.CaptureLimit).boundary)
        val valid=capture(listOf("😀", "not a URI # opaque first return"));assertEquals(listOf("😀","not a URI # opaque first return"),valid.sources)
        val body=JSONObject(valid.raw).put("sources",JSONArray(List(8193){"$it"})).toString()
        assertTrue(runCatching {NativeSelectionReturnAudit.decode(body)}.isFailure)
        assertTrue(runCatching {NativeSelectionReturnAudit.capture("owned-original",document,id,null,permission=true)}.isFailure)
    }

}
