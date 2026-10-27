package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class DesktopSuggestionRulesTest {
    @Test fun handledVersionFollowsContentAndSourceWithoutExtendingRefreshLeases() {
        fun value(hash: String="a",expiry: String="first",validity: String?="native-version")=
            SuggestionValue("same-id","text",hash,expiry,2000.0,validity)
        fun key(v: SuggestionValue,target: String="target",source: String="source")=
            DesktopSuggestionRules.handledVersionKey(target,source,v)
        assertEquals(key(value()),key(value(expiry="renewed")))
        assertNotEquals(key(value()),key(value(hash="changed")))
        assertNotEquals(key(value()),key(value(),source="replacement"))
        assertNotEquals(key(value()),key(value(),target="other"))
        assertNotEquals(key(value(validity="source-turn")),key(value(expiry="new",validity="source-turn")))
        assertNotEquals(key(value(),target="a",source="bc"),key(value(),target="ab",source="c"))
    }
    @Test fun sharedVectorsExerciseSourceLeaseAndLocalDraftBoundaries() {
        val root=Json.parseToJsonElement(Base64.decode(desktopSuggestionVectorBase64).decodeToString()).jsonObject
        assertEquals(1,root.getValue("schemaVersion").jsonPrimitive.int)
        val base=root.getValue("value").jsonObject
        val baseTarget=root.getValue("target").jsonObject
        fun merge(a: JsonObject,b: JsonElement?)=JsonObject(a+(b as? JsonObject).orEmpty())
        fun str(o: JsonObject,k: String,otherwise: String="")=o[k]?.jsonPrimitive?.contentOrNull ?: otherwise
        fun nullable(o: JsonObject,k: String)=o[k]?.jsonPrimitive?.contentOrNull
        fun value(o: JsonObject)=SuggestionValue(str(o,"id"),str(o,"text"),str(o,"contentSha256"),str(o,"expiresAt"),o["expirationMillis"]?.jsonPrimitive?.doubleOrNull,nullable(o,"validity"))
        fun target(o: JsonObject)=TargetScope(str(o,"agentId"),nullable(o,"accountAuthority"),nullable(o,"accountSubject"),str(o,"hostId"),str(o,"workspaceId"),str(o,"runtimeId"))
        fun scope(t: TargetScope,p: JsonObject=JsonObject(emptyMap()))=SuggestionScope(t,str(p,"threadId","thread"),str(p,"sourceTurnId","turn"),str(p,"navigation","navigation"),str(p,"connection","connection"),false,false,false)
        val digest=str(base,"contentSha256")
        val rows=root.getValue("cases").jsonArray
        assertEquals(56,rows.size)
        for(row in rows.map { it.jsonObject }) {
            val v=merge(base,row["value"])
            val t=merge(baseTarget,row["target"])
            val s=scope(target(t))
            val now=row["nowMillis"]?.jsonPrimitive?.double ?: root.getValue("nowMillis").jsonPrimitive.double
            val actual: JsonElement=when(str(row,"kind")) {
                "valid" -> JsonPrimitive(DesktopSuggestionRules.valid(value(v),now,digest))
                "version" -> { val a=merge(base,row["base"]);JsonPrimitive(DesktopSuggestionRules.sameVersion(value(a),value(merge(a,row["other"])))) }
                "matches" -> JsonPrimitive(DesktopSuggestionRules.matches(s,row["schemaVersion"]?.jsonPrimitive?.int ?: 1,target(merge(t,row["responseTarget"])),str(row,"threadId","thread"),str(row,"sourceTurnId","turn"),row["readOnly"]?.jsonPrimitive?.boolean ?: true,if("source" in row) nullable(row,"source") else "native-trace",value(v)))
                "proposal" -> {
                    val draft=SuggestionDraft("draft",str(row,"text"),"1")
                    val current=SuggestionDraft(str(row,"currentTargetKey","draft"),str(row,"currentText",draft.text),str(row,"currentRevision","1"))
                    DesktopSuggestionRules.proposedText(str(row,"mode"),s,scope(target(t),(row["currentScope"]as? JsonObject) ?: JsonObject(emptyMap())),value(base),value(merge(base,row["currentSuggestion"])),draft,current,now,digest)?.let(::JsonPrimitive) ?: JsonNull
                }
                "undo" -> {
                    val applied=SuggestionDraft("draft","applied","1");val c=(row["current"]as? JsonObject) ?: JsonObject(emptyMap())
                    DesktopSuggestionRules.restoredText("original",applied,SuggestionDraft(str(c,"targetKey","draft"),str(c,"text","applied"),str(c,"revision","1")))?.let(::JsonPrimitive) ?: JsonNull
                }
                else -> error("Unknown suggestion vector")
            }
            assertEquals(row.getValue("expected"),actual,str(row,"name"))
        }
    }
    @Test fun byteLimitsAndMandatorySourceModesRemainStrict() {
        val digest="a".repeat(64)
        fun value(id: String,text: String)=SuggestionValue(id,text,digest,"future",2000.0,"native-version")
        assertTrue(DesktopSuggestionRules.valid(value("é".repeat(128),"x"),1000.0,digest))
        assertFalse(DesktopSuggestionRules.valid(value("é".repeat(129),"x"),1000.0,digest))
        assertTrue(DesktopSuggestionRules.valid(value("id","😀".repeat(16000)),1000.0,digest))
        assertFalse(DesktopSuggestionRules.valid(value("id","😀".repeat(16001)),1000.0,digest))
        val target=TargetScope("codex",null,null,"host","workspace","runtime")
        for(source in listOf(null,"native-trace","runtime"))assertFalse(DesktopSuggestionRules.matches(SuggestionScope(target,"thread","turn","nav","conn",true,false,false),1,target,"thread","turn",true,source,null))
    }
}
