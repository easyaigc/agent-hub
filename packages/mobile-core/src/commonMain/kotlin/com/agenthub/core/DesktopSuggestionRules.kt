package com.agenthub.core

/** Narrow native adapters supply SHA-256 and parsed epoch milliseconds, never transport handles. */
class SuggestionValue(val id: String, val text: String, val contentSha256: String,
    val expiresAt: String, val expirationMillis: Double?, val validity: String?)
class SuggestionScope(val target: TargetScope, val threadId: String, val sourceTurnId: String,
    val navigation: String, val connection: String, val requiresDisplayed: Boolean,
    val requiresNative: Boolean, val requiresRuntime: Boolean)
class SuggestionDraft(val targetKey: String, val text: String, val revision: String)

/** Versioned desktop-suggestion policy. It can only propose local text; it cannot send or upload. */
object DesktopSuggestionRules {
    /** Stable through navigation and renewable leases; collision-free for arbitrary target/source IDs. */
    fun handledVersionKey(targetKey: String, sourceTurnId: String, value: SuggestionValue): String =
        listOf(targetKey, sourceTurnId, value.id, value.contentSha256, value.validity.orEmpty(),
            if (refreshBound(value.validity)) "" else value.expiresAt).joinToString("") { "${it.length}:$it" }
    fun refreshBound(validity: String?) = validity in listOf("desktop-display", "native-version", "runtime-version")
    fun valid(value: SuggestionValue, nowMillis: Double, actualTextSha256: String): Boolean =
        value.id.isNotEmpty() && value.id.encodeToByteArray().size <= 256 &&
        value.text.isNotEmpty() && value.text.encodeToByteArray().size <= 64_000 &&
        (value.validity == null || value.validity == "source-turn" || refreshBound(value.validity)) &&
        (value.validity == "source-turn" || value.expirationMillis?.let { nowMillis.isFinite() && it.isFinite() && it > nowMillis } == true) &&
        Regex("^[a-f0-9]{64}$").matches(actualTextSha256) && value.contentSha256 == actualTextSha256

    fun sameVersion(a: SuggestionValue?, b: SuggestionValue?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.id == b.id && a.text == b.text && a.contentSha256 == b.contentSha256 &&
            a.validity == b.validity && (refreshBound(a.validity) && refreshBound(b.validity) || a.expiresAt == b.expiresAt)
    }
    fun sameScope(a: SuggestionScope?, b: SuggestionScope?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return RuntimeIdentity.matches(a.target,b.target) && a.threadId == b.threadId &&
            a.sourceTurnId == b.sourceTurnId && a.navigation == b.navigation && a.connection == b.connection &&
            a.requiresDisplayed == b.requiresDisplayed && a.requiresNative == b.requiresNative && a.requiresRuntime == b.requiresRuntime
    }
    fun matches(scope: SuggestionScope, schemaVersion: Int, target: TargetScope, threadId: String,
        sourceTurnId: String, readOnly: Boolean, source: String?, suggestion: SuggestionValue?): Boolean =
        schemaVersion == 1 && readOnly && RuntimeIdentity.matches(scope.target,target) &&
        scope.threadId == threadId && scope.sourceTurnId == sourceTurnId &&
        source in listOf(null,"desktop-display","native-trace","runtime") &&
        (!scope.requiresDisplayed || source == "desktop-display") &&
        (!scope.requiresNative || source == "native-trace") &&
        (!scope.requiresRuntime || source == "runtime") &&
        (source != "runtime" || scope.target.agentId == "easywork") &&
        (source != "runtime" || suggestion == null || suggestion.validity == "runtime-version") &&
        (suggestion?.validity != "runtime-version" || source == "runtime") &&
        (source != "native-trace" || suggestion == null || suggestion.validity == "native-version") &&
        (suggestion?.validity != "native-version" || source == "native-trace") &&
        (source != "desktop-display" || suggestion == null || suggestion.validity == "desktop-display") &&
        (suggestion?.validity != "desktop-display" || source == "desktop-display")

    fun sameDraft(a: SuggestionDraft,b: SuggestionDraft) =
        a.targetKey == b.targetKey && a.text == b.text && a.revision == b.revision

    fun proposedText(mode: String, capturedScope: SuggestionScope, currentScope: SuggestionScope?,
        capturedSuggestion: SuggestionValue, currentSuggestion: SuggestionValue?, capturedDraft: SuggestionDraft,
        currentDraft: SuggestionDraft, nowMillis: Double, actualTextSha256: String): String? {
        if (!sameScope(capturedScope,currentScope) || currentSuggestion == null ||
            !sameVersion(capturedSuggestion,currentSuggestion) || !valid(currentSuggestion,nowMillis,actualTextSha256) ||
            !sameDraft(capturedDraft,currentDraft)) return null
        return when(mode) {
            "fill" -> if (capturedDraft.text.isEmpty()) capturedSuggestion.text else null
            "append" -> if(capturedDraft.text.isEmpty()) capturedSuggestion.text else capturedDraft.text+"\n\n"+capturedSuggestion.text
            "replace" -> capturedSuggestion.text
            else -> null
        }
    }
    /** An adopted draft is local text: expiry, overlay and source replacement do not invalidate its unchanged undo. */
    fun restoredText(originalText: String, applied: SuggestionDraft,current: SuggestionDraft): String? =
        if (sameDraft(applied,current)) originalText else null
}
