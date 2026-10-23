package com.agenthub.core

import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

class GenerationPreferenceRulesTest {
    @Test fun explicitFieldActionsKeepIndependentDefaultsAndHonorNilResets() {
        val root=Json.parseToJsonElement(Base64.decode(generationPreferenceVectorBase64).decodeToString()).jsonObject
        assertEquals(1,root.getValue("schemaVersion").jsonPrimitive.int)
        fun values(o: JsonObject)=GenerationPreferenceValues(o.getValue("model").jsonPrimitive.contentOrNull,o.getValue("effort").jsonPrimitive.contentOrNull,
            o.getValue("permissionMode").jsonPrimitive.contentOrNull,o.getValue("speed").jsonPrimitive.contentOrNull)
        for(row in root.getValue("cases").jsonArray.map { it.jsonObject }) {
            val actual=GenerationPreferenceRules.remember(values(row.getValue("previous").jsonObject),values(row.getValue("current").jsonObject),
                GenerationPreferenceEdit.valueOf(row.getValue("edit").jsonPrimitive.content.uppercase()))
            assertEquals(values(row.getValue("expected").jsonObject),actual,row.getValue("name").jsonPrimitive.content)
        }
    }
}
