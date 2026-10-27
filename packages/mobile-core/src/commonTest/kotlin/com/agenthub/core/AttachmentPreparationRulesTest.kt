package com.agenthub.core

import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

class AttachmentPreparationRulesTest {
    @Test fun versionedVectorsMatchTheSwiftBridge() {
        val root=Json.parseToJsonElement(Base64.decode(attachmentPreparationVectorBase64).decodeToString()).jsonObject
        assertEquals(1,root.getValue("schemaVersion").jsonPrimitive.int)
        for(row in root.getValue("cases").jsonArray.map { it.jsonObject }) {
            fun text(key:String)=row.getValue(key).jsonPrimitive.content
            fun bool(key:String)=row.getValue(key).jsonPrimitive.boolean
            fun int(key:String)=row.getValue(key).jsonPrimitive.int
            val actual=when(text("kind")) {
                "reserve" -> AttachmentPreparationRules.canReserve(int("existing"),int("reserved"),int("requested"))
                "commit" -> AttachmentPreparationRules.canCommit(text("target"),text("currentTarget"),text("generation"),row["currentGeneration"]?.jsonPrimitive?.contentOrNull,bool("completed"))
                "suggest" -> AttachmentPreparationRules.canSuggest(bool("enabled"),bool("readable"),bool("baseline"),bool("ignored"),row["added"]?.jsonPrimitive?.doubleOrNull,row.getValue("now").jsonPrimitive.double)
                else -> error("Unknown attachment vector kind")
            }
            assertEquals(bool("expected"),actual,text("name"))
        }
    }
    @Test fun reservationsIncludeConcurrentImportsAndRejectOverflow() {
        assertTrue(AttachmentPreparationRules.canReserve(8, 1, 1))
        assertFalse(AttachmentPreparationRules.canReserve(8, 1, 2))
        assertFalse(AttachmentPreparationRules.canReserve(0, Int.MAX_VALUE, 1))
        assertFalse(AttachmentPreparationRules.canReserve(-1, 0, 1))
        assertFalse(AttachmentPreparationRules.canReserve(0, 0, 0))
    }
    @Test fun lowerUiQuotaIncludesConcurrentReservationsWithoutChangingLegacyCeiling() {
        for(existing in 0..10)for(reserved in 0..10)for(requested in 1..10) {
            assertEquals(existing+reserved+requested<=5,AttachmentPreparationRules.canReserveWithinLimit(existing,reserved,requested,5))
            assertEquals(AttachmentPreparationRules.canReserve(existing,reserved,requested),AttachmentPreparationRules.canReserveWithinLimit(existing,reserved,requested,10))
        }
        assertFalse(AttachmentPreparationRules.canReserveWithinLimit(0,Int.MAX_VALUE,1,5))
        assertFalse(AttachmentPreparationRules.canReserveWithinLimit(0,0,1,11))
    }
    @Test fun capturedDraftCannotBeReplacedOrCompletedTwice() {
        assertTrue(AttachmentPreparationRules.canCommit("host-a/thread", "host-a/thread", "draft-1", "draft-1", false))
        assertFalse(AttachmentPreparationRules.canCommit("host-a/thread", "host-b/thread", "draft-1", "draft-1", false))
        assertFalse(AttachmentPreparationRules.canCommit("host-a/thread", "host-a/thread", "draft-1", "draft-2", false))
        assertFalse(AttachmentPreparationRules.canCommit("host-a/thread", "host-a/thread", "draft-1", null, false))
        assertFalse(AttachmentPreparationRules.canCommit("host-a/thread", "host-a/thread", "draft-1", "draft-1", true))
    }
    @Test fun suggestionsRequireActualRecentAdditionAndStablePermissionBaseline() {
        fun eligible(added: Double?, now: Double = 1000.0, baseline: Boolean = true, ignored: Boolean = false) =
            AttachmentPreparationRules.canSuggest(true, true, baseline, ignored, added, now)
        assertTrue(eligible(400.0))
        assertFalse(eligible(399.9))
        assertFalse(eligible(1001.0))
        assertFalse(eligible(null))
        assertFalse(eligible(999.0, baseline = false))
        assertFalse(eligible(999.0, ignored = true))
        assertFalse(eligible(Double.NaN))
    }
}
