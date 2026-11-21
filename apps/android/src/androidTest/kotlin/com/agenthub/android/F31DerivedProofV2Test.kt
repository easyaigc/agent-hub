package com.agenthub.android

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure androidTest evidence rules. No storage, Session, clock, network, fixture or acknowledgement. */
internal object F31DerivedProofV2 {
    const val MAX_ROWS=4096
    const val MAX_MESSAGES=320 // The existing capture admits at most ten 32-message cache parts.
    const val MAX_BYTES=2*1024*1024
    private const val PROOF="f31-derived-original-records-proof-v2"
    private const val COMPARISON="f31-derived-original-records-preservation-v2"
    private const val LEDGER="99a89ed8167b4c670c70fb66769da87eb2f100fc29df26ccb0ccfb2062a34f1d"
    private val reasons=setOf("NOT_OBSERVED","INVALID_INPUT","LIMIT_EXCEEDED","DUPLICATE_ID","BINDING_MISMATCH")
    data class Row(val rowKeyHash: String,val protectedHash: String,val headRevision: Long?,val headHash: String?)
    data class Message(val idHash: String,val contentHash: String)
    data class History(val messages: List<Message>,val windowed: Boolean,val windowKind: String?,val hasEarlierCursor: Boolean)
    private class Invalid(val fixedReason: String): Exception()
    private fun demand(ok: Boolean,reason: String="INVALID_INPUT") { if(!ok)throw Invalid(reason) }
    private fun keys(v: JSONObject)=v.keys().asSequence().toSet()
    private fun exact(v: JSONObject,vararg names: String) { demand(keys(v)==names.toSet()) }
    private fun hash(v: Any?)=MessageDigest.getInstance("SHA-256").digest(easyCanonical(v).toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it)}
    private fun hex(v: Any?): String { demand(v is String && v.matches(Regex("[a-f0-9]{64}")));return v as String }
    private fun integer(v: Any?): Long { demand(v is Int || v is Long);return (v as Number).toLong() }
    private fun natural(v: Any?): Long=integer(v).also { demand(it>=0) }
    private fun nullableLong(v: Any?): Long?=if(v==JSONObject.NULL)null else natural(v)
    private fun nullableHash(v: Any?): String?=if(v==JSONObject.NULL)null else hex(v)
    private fun unknown(reason: String="NOT_OBSERVED")=JSONObject().put("status","UNKNOWN").put("reason",reason)
    private fun observed(block: ()->JSONObject): JSONObject = try { block() }
        catch (failure: Invalid) { unknown(failure.fixedReason) }
        catch (_: Exception) { unknown("INVALID_INPUT") }
    private fun sized(v: JSONObject): JSONObject { demand(v.toString().toByteArray(Charsets.UTF_8).size<=MAX_BYTES,"LIMIT_EXCEEDED");return v }
    private fun isObserved(v: JSONObject)=v.getString("status")=="OBSERVED"
    private fun unique(ids: List<String>) { demand(ids.distinct().size==ids.size,"DUPLICATE_ID") }
    private fun array(v: JSONObject,name: String,limit: Int): List<JSONObject> {
        val a=v.getJSONArray(name);demand(a.length()<=limit,"LIMIT_EXCEEDED")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    private fun records(v: JSONArray): JSONArray {
        demand(v.length()<=12,"LIMIT_EXCEEDED")
        val result=JSONArray();val ids=mutableListOf<String>();val parts=mutableListOf<Pair<String,Long>>()
        var ledgers=0;var indexes=0
        for(i in 0 until v.length()) {
            val r=v.getJSONObject(i);val kind=r.getString("kind")
            val base=setOf("kind","logicalNameHash","relativePath","sha256","bytes","stat")
            demand(keys(r)==if(kind=="qa-history-part")base+setOf("ordinal","generationHash") else base)
            demand(kind in setOf("global-reading-ledger","qa-history-index","qa-history-part"))
            val id=hex(r.get("logicalNameHash"));ids.add(id)
            demand(r.getString("relativePath")=="no_backup/mobile-v1/$id.bin")
            hex(r.get("sha256"));val bytes=natural(r.get("bytes"));demand(bytes in 29L..1_114_145L)
            val stat=r.getJSONArray("stat");demand(stat.length()==9)
            val s=(0 until 9).map { integer(stat.get(it)) }
            demand(s[0]>0 && s[1]>0 && s[2]>=0 && s[3]>=0 && (s[4] and 0xf000L)==0x8000L &&
                (s[4] and 0xfffL)==0x180L && s[5]==1L && s[6]==bytes && s[7]>=0 && s[8]>=0)
            if(kind=="global-reading-ledger") { demand(id==LEDGER);ledgers++ }
            if(kind=="qa-history-index") { demand(id!=LEDGER);indexes++ }
            if(kind=="qa-history-part") {
                demand(id!=LEDGER);val ordinal=natural(r.get("ordinal"));demand(ordinal<=9)
                parts.add(hex(r.get("generationHash")) to ordinal)
            }
            result.put(JSONObject(r.toString()))
        }
        unique(ids);demand(ledgers<=1 && indexes<=1)
        demand(parts.map { it.first }.distinct().size<=1 && parts.map { it.second }.sorted()==parts.indices.map { it.toLong() })
        return result
    }
    private fun binding(safe: JSONObject,qa: String): JSONObject=JSONObject()
        .put("targetHash",hex(safe.get("targetHash"))).put("threadHash",hex(safe.get("threadHash")))
        .put("cacheKeyHash",hex(safe.get("cacheKeyHash"))).put("qaRowHash",hex(qa))
        .put("records",records(safe.getJSONArray("records")))
    private fun hasRecord(binding: JSONObject,kind: String): Boolean =
        array(binding,"records",12).count { it.getString("kind")==kind }==1
    fun proof(safe: JSONObject,scopes: Set<String>?,rows: List<Row>?,qa: String,history: History?): JSONObject = try {
        val bound=binding(safe,qa)
        val ledger=observed {
            demand(scopes!=null && rows!=null && safe.getBoolean("ledgerProductionDecoded") && hasRecord(bound,"global-reading-ledger"),"NOT_OBSERVED")
            demand(scopes!!.size<=MAX_ROWS && rows!!.size<=MAX_ROWS,"LIMIT_EXCEEDED")
            val scopeHashes=scopes!!.map(::hex).sorted();val originals=rows!!.sortedBy { it.rowKeyHash }
            unique(originals.map { hex(it.rowKeyHash) })
            val values=originals.map { row ->
                hex(row.protectedHash);demand((row.headRevision==null)==(row.headHash==null))
                row.headRevision?.let { demand(it>=0) };row.headHash?.let(::hex)
                JSONObject().put("rowKeyHash",row.rowKeyHash).put("protectedHash",row.protectedHash)
                    .put("headRevision",row.headRevision ?: JSONObject.NULL).put("headHash",row.headHash ?: JSONObject.NULL)
            }
            JSONObject().put("status","OBSERVED").put("revision",natural(safe.get("ledgerRevision")))
                .put("scopeCount",scopeHashes.size).put("scopeSetHash",hash(JSONArray(scopeHashes)))
                .put("rowCount",values.size).put("rows",JSONArray(values))
        }
        val messages=observed {
            demand(history!=null && safe.getBoolean("historyProductionDecoded") && hasRecord(bound,"qa-history-index"),"NOT_OBSERVED")
            val h=history!!;demand(h.messages.size<=MAX_MESSAGES,"LIMIT_EXCEEDED")
            demand(h.windowKind==null || h.windowKind in setOf("latest","earlier"))
            unique(h.messages.map { hex(it.idHash) });h.messages.forEach { hex(it.contentHash) }
            JSONObject().put("status","OBSERVED").put("encoding","PRODUCTION_encodeMessage_CANONICAL_SHA256")
                .put("windowed",h.windowed).put("windowKind",h.windowKind ?: JSONObject.NULL)
                .put("hasEarlierCursor",h.hasEarlierCursor).put("messageCount",h.messages.size)
                .put("messages",JSONArray(h.messages.map { JSONObject().put("idHash",it.idHash).put("contentHash",it.contentHash) }))
        }
        sized(JSONObject().put("version",2).put("contract",PROOF).put("status",if(isObserved(ledger)&&isObserved(messages))"OBSERVED" else "UNKNOWN")
            .put("binding",bound).put("ledger",ledger).put("history",messages))
    } catch (failure: Invalid) { unknownProof(failure.fixedReason) }
      catch (_: Exception) { unknownProof("INVALID_INPUT") }
    private fun unknownProof(reason: String)=JSONObject().put("version",2).put("contract",PROOF).put("status","UNKNOWN")
        .put("binding",JSONObject.NULL).put("ledger",unknown(reason)).put("history",unknown(reason))
    private fun section(v: JSONObject,known: Set<String>): Boolean {
        if(v.getString("status")=="UNKNOWN") { exact(v,"status","reason");demand(v.getString("reason") in reasons);return false }
        demand(v.getString("status")=="OBSERVED" && keys(v)==known);return true
    }
    /** Revalidate serialized proofs as closed data; no arbitrary strings can flow into comparisons. */
    private fun validate(v: JSONObject) {
        sized(v);exact(v,"version","contract","status","binding","ledger","history")
        demand(integer(v.get("version"))==2L && v.getString("contract")==PROOF)
        val l=v.getJSONObject("ledger");val h=v.getJSONObject("history")
        val observedLedger=section(l,setOf("status","revision","scopeCount","scopeSetHash","rowCount","rows"))
        val observedHistory=section(h,setOf("status","encoding","windowed","windowKind","hasEarlierCursor","messageCount","messages"))
        demand(v.getString("status")==if(observedLedger&&observedHistory)"OBSERVED" else "UNKNOWN")
        if(v.isNull("binding")) { demand(!observedLedger && !observedHistory);return }
        val b=v.getJSONObject("binding");exact(b,"targetHash","threadHash","cacheKeyHash","qaRowHash","records")
        for(k in listOf("targetHash","threadHash","cacheKeyHash","qaRowHash"))hex(b.get(k))
        records(b.getJSONArray("records"))
        if(observedLedger) {
            demand(hasRecord(b,"global-reading-ledger"));natural(l.get("revision"));demand(natural(l.get("scopeCount"))<=MAX_ROWS);hex(l.get("scopeSetHash"))
            val rows=array(l,"rows",MAX_ROWS);demand(natural(l.get("rowCount"))==rows.size.toLong());unique(rows.map { hex(it.get("rowKeyHash")) })
            rows.forEach { r ->
                exact(r,"rowKeyHash","protectedHash","headRevision","headHash");hex(r.get("protectedHash"))
                val revision=nullableLong(r.get("headRevision"));val head=nullableHash(r.get("headHash"));demand((revision==null)==(head==null))
            }
        }
        if(observedHistory) {
            demand(hasRecord(b,"qa-history-index") && h.getString("encoding")=="PRODUCTION_encodeMessage_CANONICAL_SHA256")
            demand(h.get("windowed") is Boolean && h.get("hasEarlierCursor") is Boolean)
            demand(h.get("windowKind")==JSONObject.NULL || h.get("windowKind") in setOf("latest","earlier"))
            val messages=array(h,"messages",MAX_MESSAGES);demand(natural(h.get("messageCount"))==messages.size.toLong())
            unique(messages.map { hex(it.get("idHash")) });messages.forEach { exact(it,"idHash","contentHash");hex(it.get("contentHash")) }
        }
    }
    private fun compareLedger(a: JSONObject,b: JSONObject,qa: String): JSONObject {
        if(!isObserved(a)||!isObserved(b))return unknown()
        val old=array(a,"rows",MAX_ROWS).associateBy { it.getString("rowKeyHash") }
        val next=array(b,"rows",MAX_ROWS).associateBy { it.getString("rowKeyHash") }
        val missing=(old.keys-next.keys).sorted();val added=(next.keys-old.keys).sorted()
        val changed=old.keys.intersect(next.keys).filter { old.getValue(it).getString("protectedHash")!=next.getValue(it).getString("protectedHash") }.sorted()
        val heads=old.filter { (key,row) ->
            val n=next[key];val rev=nullableLong(row.get("headRevision"))
            n==null || rev!=null && (nullableLong(n.get("headRevision"))?.let { it<rev || it==rev && n.get("headHash")!=row.get("headHash") } ?: true)
        }.keys.sorted()
        val qaProof=JSONObject().put("status",when { qa !in old->"NO_ORIGINAL_ROW";qa in missing->"MISSING_ORIGINAL_ROW";qa in changed->"CHANGED_REQUIRES_PRODUCT_PROOF";else->"UNCHANGED" })
            .put("originalPresent",qa in old).put("afterPresent",qa in next)
            .put("protectedUnchanged",if(qa in old)qa !in missing && qa !in changed else JSONObject.NULL)
            .put("readingAcknowledgement","NOT_PROVED")
        return JSONObject().put("status","OBSERVED").put("originalRowCount",old.size)
            .put("nonSelectedOriginalRowCount",old.keys.count { it!=qa })
            .put("originalRowsPresent",missing.isEmpty()).put("originalProtectedFieldsPreserved",missing.isEmpty()&&changed.isEmpty())
            .put("nonSelectedProtectedPreserved",missing.none { it!=qa }&&changed.none { it!=qa })
            .put("originalHeadsNoRegression",heads.isEmpty()).put("scopeSetPreserved",natural(a.get("scopeCount"))==natural(b.get("scopeCount"))&&a.get("scopeSetHash")==b.get("scopeSetHash"))
            .put("ledgerRevisionNondecreasing",natural(b.get("revision"))>=natural(a.get("revision")))
            .put("addedRowCount",added.size).put("addedRowHashes",JSONArray(added))
            .put("missingOriginalRowHashes",JSONArray(missing)).put("changedOriginalProtectedRowHashes",JSONArray(changed))
            .put("regressedOriginalHeadRowHashes",JSONArray(heads)).put("qa",qaProof)
    }
    private fun compareHistory(a: JSONObject,b: JSONObject): JSONObject {
        if(!isObserved(a)||!isObserved(b))return unknown()
        val old=array(a,"messages",MAX_MESSAGES);val next=array(b,"messages",MAX_MESSAGES)
        val oldIds=old.map { it.getString("idHash") };val newIds=next.map { it.getString("idHash") }
        val byId=next.associateBy { it.getString("idHash") };val missing=(oldIds.toSet()-newIds.toSet()).sorted()
        val changed=old.filter { row -> byId[row.getString("idHash")]?.let { it.getString("contentHash")!=row.getString("contentHash") }==true }.map { it.getString("idHash") }.sorted()
        val order=missing.isEmpty() && newIds.filter { it in oldIds }==oldIds
        val preserved=missing.isEmpty() && changed.isEmpty() && order
        val reason=when {
            missing.isNotEmpty() && (b.getBoolean("windowed")||b.getBoolean("hasEarlierCursor")||b.optString("windowKind")=="earlier")->"WINDOW_NOT_COVERING_ORIGINALS"
            missing.isNotEmpty()->"ORIGINAL_MESSAGE_NOT_OBSERVED"
            changed.isNotEmpty()->"ORIGINAL_MESSAGE_CONTENT_CHANGED"
            !order->"ORIGINAL_MESSAGE_ORDER_CHANGED"
            else->"NONE"
        }
        return JSONObject().put("status",if(preserved)"OBSERVED" else "UNKNOWN").put("reason",reason)
            .put("coverage","CAPTURED_ORIGINAL_MESSAGES_ONLY").put("originalMessageCount",old.size)
            .put("originalMessagesPresent",missing.isEmpty()).put("originalMessagesUnchanged",missing.isEmpty()&&changed.isEmpty())
            .put("originalOrderPreserved",order).put("addedMessageHashes",JSONArray((newIds.toSet()-oldIds.toSet()).sorted()))
            .put("missingOriginalMessageHashes",JSONArray(missing)).put("changedOriginalMessageHashes",JSONArray(changed))
    }
    private fun unknownComparison(reason: String)=JSONObject().put("version",2).put("contract",COMPARISON).put("status","UNKNOWN")
        .put("beforeProofSHA256",JSONObject.NULL).put("afterProofSHA256",JSONObject.NULL)
        .put("ledger",unknown(reason)).put("history",unknown(reason)).put("originalRecordsPreserved",JSONObject.NULL)
        .put("completeUserDataProtectionPassed",JSONObject.NULL)
    fun compare(before: JSONObject,after: JSONObject): JSONObject = try {
        validate(before);validate(after)
        demand(!before.isNull("binding")&&!after.isNull("binding"),"NOT_OBSERVED")
        val a=before.getJSONObject("binding");val b=after.getJSONObject("binding")
        demand(listOf("targetHash","threadHash","cacheKeyHash","qaRowHash").all { a.get(it)==b.get(it) },"BINDING_MISMATCH")
        val ledger=compareLedger(before.getJSONObject("ledger"),after.getJSONObject("ledger"),a.getString("qaRowHash"))
        val history=compareHistory(before.getJSONObject("history"),after.getJSONObject("history"))
        val ledgerOK=isObserved(ledger) && listOf("originalRowsPresent","originalProtectedFieldsPreserved","originalHeadsNoRegression","scopeSetPreserved","ledgerRevisionNondecreasing").all { ledger.getBoolean(it) }
        val failed=isObserved(ledger) && !ledgerOK
        val pass=ledgerOK && isObserved(history)
        sized(JSONObject().put("version",2).put("contract",COMPARISON).put("status",if(failed)"FAILED" else if(pass)"PRESERVED" else "UNKNOWN")
            .put("beforeProofSHA256",hash(before)).put("afterProofSHA256",hash(after)).put("ledger",ledger).put("history",history)
            .put("originalRecordsPreserved",if(failed)false else if(pass)true else JSONObject.NULL)
            .put("completeUserDataProtectionPassed",JSONObject.NULL))
    } catch (failure: Invalid) { unknownComparison(failure.fixedReason) }
      catch (_: Exception) { unknownComparison("INVALID_INPUT") }
}

/** Meaningful counterexamples for the actual evidence producer/comparator; no App or storage. */
class F31DerivedProofV2Test {
    private val qa="a".repeat(64)
    private val other="b".repeat(64)
    private val added="c".repeat(64)
    private val content="d".repeat(64)
    private fun row(id: String,protected: String=content,rev: Long?=4,head: String?=content)=F31DerivedProofV2.Row(id,protected,rev,head)
    private fun safe()=JSONObject().put("targetHash",qa).put("threadHash",other).put("cacheKeyHash",added)
        .put("ledgerProductionDecoded",true).put("historyProductionDecoded",true).put("ledgerRevision",9)
        .put("records",JSONArray(listOf("global-reading-ledger" to "99a89ed8167b4c670c70fb66769da87eb2f100fc29df26ccb0ccfb2062a34f1d","qa-history-index" to other).map { (kind,id) ->
            JSONObject().put("kind",kind).put("logicalNameHash",id).put("relativePath","no_backup/mobile-v1/$id.bin")
                .put("sha256",content).put("bytes",100).put("stat",JSONArray(listOf(1L,2L,10231L,10231L,33152L,1L,100L,10L,10L)))
        }))
    private fun history(vararg ids: String)=F31DerivedProofV2.History(ids.map { F31DerivedProofV2.Message(it,content) },false,null,false)
    private fun proof(rows: List<F31DerivedProofV2.Row>,scopes: Set<String> = setOf(qa),history: F31DerivedProofV2.History? = history(qa))=
        F31DerivedProofV2.proof(safe(),scopes,rows,qa,history)
    private fun compare(a: List<F31DerivedProofV2.Row>,b: List<F31DerivedProofV2.Row>)=F31DerivedProofV2.compare(proof(a),proof(b))
    @Test fun addingRowsDoesNotSuppressOriginalChecks() {
        val result=compare(listOf(row(qa),row(other)),listOf(row(qa),row(other),row(added)))
        assertEquals("PRESERVED",result.getString("status"))
        val ledger=result.getJSONObject("ledger");assertTrue(ledger.getBoolean("nonSelectedProtectedPreserved"))
        assertTrue(ledger.getBoolean("originalHeadsNoRegression"));assertEquals(added,ledger.getJSONArray("addedRowHashes").getString(0))
    }
    @Test fun deletionAndNonSelectedMutationFailIndependentlyDespiteAnAddition() {
        val old=listOf(row(qa),row(other))
        val missing=compare(old,listOf(row(qa),row(added)))
        assertEquals("FAILED",missing.getString("status"));assertFalse(missing.getJSONObject("ledger").getBoolean("originalRowsPresent"))
        val changed=compare(old,listOf(row(qa),row(other,added),row(added)))
        assertTrue(changed.getJSONObject("ledger").getBoolean("originalRowsPresent"))
        assertFalse(changed.getJSONObject("ledger").getBoolean("nonSelectedProtectedPreserved"))
        assertTrue(changed.getJSONObject("ledger").getBoolean("originalHeadsNoRegression"))
        assertEquals("FAILED",changed.getString("status"))
    }
    @Test fun lowerHeadsAndEqualRevisionConflictsFailButAdvancingHeadsPass() {
        for(next in listOf(row(qa,rev=3),row(qa,head=other),row(qa,rev=null,head=null))) {
            assertFalse(compare(listOf(row(qa)),listOf(next)).getJSONObject("ledger").getBoolean("originalHeadsNoRegression"))
        }
        assertEquals("PRESERVED",compare(listOf(row(qa)),listOf(row(qa,rev=5,head=other))).getString("status"))
    }
    @Test fun absentOriginalQAIsNotReadingSuccessAndChangedOriginalQANeedsProof() {
        val absent=compare(listOf(row(other)),listOf(row(other),row(qa)))
        val q=absent.getJSONObject("ledger").getJSONObject("qa")
        assertEquals("NO_ORIGINAL_ROW",q.getString("status"));assertTrue(q.isNull("protectedUnchanged"))
        assertEquals("NOT_PROVED",q.getString("readingAcknowledgement"))
        val changed=compare(listOf(row(qa)),listOf(row(qa,other)))
        assertEquals("FAILED",changed.getString("status"))
        assertEquals("CHANGED_REQUIRES_PRODUCT_PROOF",changed.getJSONObject("ledger").getJSONObject("qa").getString("status"))
    }
    @Test fun scopeAndLedgerRevisionChangesCannotHideBehindMatchingRows() {
        val old=proof(listOf(row(qa)));val scopes=F31DerivedProofV2.compare(old,proof(listOf(row(qa)),setOf(other)))
        assertFalse(scopes.getJSONObject("ledger").getBoolean("scopeSetPreserved"));assertEquals("FAILED",scopes.getString("status"))
        val next=proof(listOf(row(qa)));next.getJSONObject("ledger").put("revision",8)
        assertFalse(F31DerivedProofV2.compare(old,next).getJSONObject("ledger").getBoolean("ledgerRevisionNondecreasing"))
    }
    @Test fun duplicatesMissingObservationsAndClosedSchemaNeverBecomeEmptyPasses() {
        val duplicate=proof(listOf(row(qa),row(qa)))
        assertEquals("UNKNOWN",F31DerivedProofV2.compare(duplicate,duplicate).getString("status"))
        val original=proof(listOf(row(qa)));val malformed=JSONObject(original.toString())
        malformed.getJSONObject("ledger").getJSONArray("rows").getJSONObject(0).put("unapproved","synthetic-secret")
        val result=F31DerivedProofV2.compare(original,malformed)
        assertEquals("UNKNOWN",result.getString("status"));assertFalse(result.toString().contains("synthetic-secret"))
        assertEquals("UNKNOWN",F31DerivedProofV2.compare(proof(emptyList(),history=null),proof(emptyList(),history=null)).getString("status"))
        val badHistory=proof(emptyList(),history=history(qa,qa))
        assertEquals("UNKNOWN",F31DerivedProofV2.compare(badHistory,badHistory).getString("status"))
    }
    @Test fun bindingMismatchAndAbsentLedgerRemainUnknownWithoutBlockingKnownHistory() {
        val original=proof(listOf(row(qa)))
        val wrong=JSONObject(original.toString());wrong.getJSONObject("binding").put("threadHash",content)
        assertEquals("UNKNOWN",F31DerivedProofV2.compare(original,wrong).getString("status"))
        val missing=F31DerivedProofV2.proof(safe(),null,null,qa,history(qa))
        assertEquals("OBSERVED",missing.getJSONObject("history").getString("status"))
        val result=F31DerivedProofV2.compare(missing,missing)
        assertEquals("UNKNOWN",result.getJSONObject("ledger").getString("status"))
        assertEquals("OBSERVED",result.getJSONObject("history").getString("status"))
        assertEquals("UNKNOWN",result.getString("status"))
    }
    @Test fun rowMessageAndSerializedByteLimitsRejectInsteadOfTruncating() {
        val repeated=List(F31DerivedProofV2.MAX_ROWS+1) { row(qa) }
        assertEquals("UNKNOWN",proof(repeated).getJSONObject("ledger").getString("status"))
        val many=F31DerivedProofV2.History(List(F31DerivedProofV2.MAX_MESSAGES+1) { F31DerivedProofV2.Message(qa,content) },false,null,false)
        assertEquals("UNKNOWN",proof(emptyList(),history=many).getJSONObject("history").getString("status"))
        val p=proof(emptyList());p.put("oversized","x".repeat(F31DerivedProofV2.MAX_BYTES+1))
        assertEquals("UNKNOWN",F31DerivedProofV2.compare(p,p).getString("status"))
    }
    @Test fun exactOriginalMessageRowsSurviveAnAdditionButAWindowOrUpdateIsUnknown() {
        val old=proof(emptyList(),history=history(qa,other))
        val expanded=proof(emptyList(),history=history(qa,added,other))
        assertEquals("PRESERVED",F31DerivedProofV2.compare(old,expanded).getString("status"))
        val cut=proof(emptyList(),history=F31DerivedProofV2.History(history(other).messages,true,"latest",true))
        assertEquals("WINDOW_NOT_COVERING_ORIGINALS",F31DerivedProofV2.compare(old,cut).getJSONObject("history").getString("reason"))
        val changed=proof(emptyList(),history=F31DerivedProofV2.History(listOf(F31DerivedProofV2.Message(qa,added),F31DerivedProofV2.Message(other,content)),false,null,false))
        val result=F31DerivedProofV2.compare(old,changed)
        assertEquals("UNKNOWN",result.getString("status"));assertFalse(result.getJSONObject("history").getBoolean("originalMessagesUnchanged"))
        val reordered=proof(emptyList(),history=history(other,qa))
        assertFalse(F31DerivedProofV2.compare(old,reordered).getJSONObject("history").getBoolean("originalOrderPreserved"))
        assertEquals("UNKNOWN",F31DerivedProofV2.compare(old,reordered).getString("status"))
    }
    @Test fun actualProductionMessageEncodingDetectsBodyAndAttachmentChanges() {
        fun page(message: MobileMessage)=MobileHistory("synthetic-host","synthetic-thread",listOf(message),null,null,"completed",null,"{}")
        val original=MobileMessage("synthetic-message","assistant","synthetic-original","completed")
        val a=F31UserDataSnapshotV2.historyProofV2(page(original))!!
        val b=F31UserDataSnapshotV2.historyProofV2(page(original.copy(text="synthetic-changed")))!!
        val c=F31UserDataSnapshotV2.historyProofV2(page(original.copy(files=listOf("synthetic-file"))))!!
        assertEquals(a.messages.single().idHash,b.messages.single().idHash)
        assertNotEquals(a.messages.single().contentHash,b.messages.single().contentHash)
        assertNotEquals(a.messages.single().contentHash,c.messages.single().contentHash)
        assertFalse(proof(emptyList(),history=a).toString().contains("synthetic-original"))
        assertFalse(proof(emptyList(),history=a).toString().contains("synthetic-message"))
        val duplicate=page(original).copy(messages=listOf(original,original))
        assertNull(F31UserDataSnapshotV2.historyProofV2(duplicate))
        val tooLarge=page(original.copy(text="x".repeat(1_048_577)))
        assertNull(F31UserDataSnapshotV2.historyProofV2(tooLarge))
    }
    @Test fun derivedAPIsHashPrivateKeysAndDoNotChangeLegacySetEquality() {
        val key="synthetic-private-row-key"
        val rawRow=F31UserDataSnapshotV2.DerivedRow(content,content,4)
        val before=F31UserDataSnapshotV2.Derived(safe(),setOf(qa),mapOf(key to rawRow),key,history(qa))
        val after=before.copy(rows=before.rows!!+mapOf("synthetic-new-row-key" to rawRow))
        assertFalse(before.preservation(after).getBoolean("rowKeysPreserved"))
        assertEquals("PRESERVED",before.preservationV2(after).getString("status"))
        val encoded=before.proofV2()
        assertFalse(encoded.toString().contains(key))
        val expected=MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString(""){"%02x".format(it)}
        assertEquals(expected,encoded.getJSONObject("ledger").getJSONArray("rows").getJSONObject(0).getString("rowKeyHash"))
        assertTrue(before.preservationV2(after).isNull("completeUserDataProtectionPassed"))
    }
}
