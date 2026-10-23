package com.agenthub.core

import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

class AISharingVoiceRulesTest {
    private val vectors=Json.parseToJsonElement(Base64.decode(aiSharingVoiceVectorBase64).decodeToString()).jsonObject
    private fun scope(v: JsonObject)=TargetScope(v.getValue("agentId").jsonPrimitive.content,v["accountAuthority"]?.jsonPrimitive?.contentOrNull,v["accountSubject"]?.jsonPrimitive?.contentOrNull,
        v.getValue("hostId").jsonPrimitive.content,v.getValue("workspaceId").jsonPrimitive.content,v.getValue("runtimeId").jsonPrimitive.content)
    @Test fun permissionsBindModelAndRuntimeWithoutRelaxingOtherAgentAccounts() {
        for(row in vectors.getValue("consent").jsonArray) {
            val r=row.jsonObject;val a=scope(r.getValue("a").jsonObject);val b=scope(r.getValue("b").jsonObject)
            val am=r.getValue("aModel").jsonPrimitive.content;val bm=r.getValue("bModel").jsonPrimitive.content;val expected=r.getValue("expected").jsonPrimitive.boolean
            assertEquals(expected,AISharingRules.matches(a,am,b,bm),r.getValue("name").jsonPrimitive.content)
            assertEquals(expected,AISharingRules.key(a,am)==AISharingRules.key(b,bm))
        }
        assertEquals("follow-computer",AISharingRules.model(null));assertEquals("follow-computer",AISharingRules.model(""))
        for(decision in listOf("accept","allow","skip","once","session","","unknown")) assertTrue(AISharingRules.requiresContentGrant(decision))
        for(decision in listOf("deny","decline","cancel","abort")) assertFalse(AISharingRules.requiresContentGrant(decision))
    }
    @Test fun originalAudioReceiptRejectsChangedTargetManifestAndUnknownFields() {
        for(row in vectors.getValue("voice").jsonArray) {
            val r=row.jsonObject
            assertEquals(r.getValue("expected").jsonPrimitive.boolean,VoiceTranscriptionRules.receiptMatches(r.getValue("manifest").toString(),r.getValue("receipt").toString()),r.getValue("name").jsonPrimitive.content)
        }
    }
    @Test fun strictSchemaRejectsMalformedWireBeforeNativeDTOConversion() {
        for(row in vectors.getValue("manifests").jsonArray) {
            val r=row.jsonObject;val expected=r.getValue("expected").jsonPrimitive.boolean;val manifest=r.getValue("value").toString()
            assertEquals(expected,VoiceTranscriptionRules.validateManifest(manifest),r.getValue("name").jsonPrimitive.content)
            assertEquals(expected,CodexHttpContract.validate(manifest,"voiceManifest")==null,r.getValue("name").jsonPrimitive.content)
        }
    }
}
