package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class WorkspaceArchiveRulesTest {
    private val fixture get()=Json.parseToJsonElement(Base64.decode(stateVectorBase64).decodeToString()).jsonObject
    @Test fun actualCrossLanguageReceiptVectors() {
        val rows=fixture.getValue("workspaceArchive").jsonArray
        assertEquals(12,rows.size)
        for(value in rows) {
            val row=value.jsonObject;val before=row["previous"].takeUnless { it==JsonNull }?.toString()
            val merged=WorkspaceArchiveRules.merge(row.getValue("original").toString(),before,row.getValue("incoming").toString())
            val expected=row.getValue("expected")
            assertEquals(expected.takeUnless { it==JsonNull },merged?.let { Json.parseToJsonElement(it) },row.getValue("name").jsonPrimitive.content)
        }
    }
    @Test fun boundedSearchSelectionIsDistinctAndSubtractsOnlyVisibleIds() {
        val ids=(1..150).map { "chat-$it" };val prior=listOf("hidden-selected")
        val selected=WorkspaceArchiveRules.select(prior,ids+ids,false)
        assertEquals(100,selected.size);assertEquals(100,selected.distinct().size);assertEquals("hidden-selected",selected.first())
        assertEquals(listOf("hidden-selected"),WorkspaceArchiveRules.select(selected,ids,true))
    }
    @Test fun nodeRequestContractVectorsUseActualKotlinSchema() {
        val rows=fixture.getValue("http").jsonArray.map { it.jsonObject }.filter { it.getValue("definition").jsonPrimitive.content=="workspaceManagementRequest" }
        assertEquals(11,rows.size)
        for(row in rows)assertEquals(row.getValue("valid").jsonPrimitive.boolean,WorkspaceArchiveRules.requestValid(row.getValue("json").jsonPrimitive.content),row.getValue("name").jsonPrimitive.content)
    }
    @Test fun malformedPriorCannotAuthorizeReplacementAndTerminalsNeverRegress() {
        val row=fixture.getValue("workspaceArchive").jsonArray.first().jsonObject;val request=row.getValue("original").toString();val receipt=row.getValue("incoming").toString()
        assertNull(WorkspaceArchiveRules.merge(request,"{}",receipt));assertNull(WorkspaceArchiveRules.merge("{}",null,receipt))
        assertTrue(WorkspaceArchiveRules.pending("outcome-unknown"));assertFalse(WorkspaceArchiveRules.pending("completed"));assertFalse(WorkspaceArchiveRules.pending("rejected"))
    }
    @Test fun unresolvedSingleAndBatchOriginalsCannotBecomeAnotherIntent() {
        val rows=fixture.getValue("workspaceArchiveEligibility").jsonArray
        for(value in rows) {
            val row=value.jsonObject;val input=row.getValue("input").jsonObject
            fun flag(key: String)=input.getValue(key).jsonPrimitive.boolean
            assertEquals(row.getValue("expected").jsonPrimitive.boolean,WorkspaceArchiveRules.eligible(flag("knownTarget"),flag("archived"),
                flag("singleBusy"),flag("singlePending"),flag("batchPending")),row.getValue("name").jsonPrimitive.content)
        }
        assertEquals(7,rows.size)
    }

}
