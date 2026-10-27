package com.agenthub.android

import com.agenthub.core.DesktopSuggestionRules
import com.agenthub.core.SuggestionScope
import com.agenthub.core.SuggestionValue
import com.agenthub.core.SuggestionDraft
import com.agenthub.core.TargetScope
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.*

internal class DesktopSuggestionSourceMismatch: IllegalArgumentException("桌面建议来源已变化，请刷新原对话")

internal class MobileSuggestionAction(val scope: SuggestionScope,val suggestion: MobileDesktopSuggestion,val draft: SuggestionDraft) {
    val handledKey=DesktopSuggestionRules.handledVersionKey(draft.targetKey,scope.sourceTurnId,suggestion.sharedValue(false))
}
internal class MobileSuggestionUndo(val original: String,val applied: SuggestionDraft,val handledKey: String)

/** One read deadline. Timeout is recoverable; cancellation of the owning consumer remains cancellation. */
internal suspend fun <T> boundedDesktopSuggestionRead(read: suspend ()->T): T {
    val started=android.os.SystemClock.elapsedRealtime()
    return try {
        val value=withTimeout(8_000) { read() }
        currentCoroutineContext().ensureActive()
        if(android.os.SystemClock.elapsedRealtime()-started>=8_000)
            throw HostReadError("读取桌面建议超时，请稍后重试",transient=true)
        value
    } catch(timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw HostReadError("读取桌面建议超时，请稍后重试",timeout,transient=true)
    }
}

/** Native crypto/date mapping only; shared policy decides source, version and local adoption. */
data class MobileDesktopSuggestion(val id: String,val text: String,val contentSha256: String,
    val expiresAt: String,val validity: String?) {
    val expirationMillis: Double? by lazy {
        runCatching { Instant.parse(expiresAt).toEpochMilli().toDouble() }.getOrNull()
    }
    val actualTextSha256: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun sharedValue(includeExpiration: Boolean=true)=SuggestionValue(id,text,contentSha256,expiresAt,
        if(includeExpiration)expirationMillis else null,validity)
    fun isValid(at: Double=System.currentTimeMillis().toDouble())=
        DesktopSuggestionRules.valid(sharedValue(),at,actualTextSha256)
    fun sameVersion(other: MobileDesktopSuggestion?)=DesktopSuggestionRules.sameVersion(sharedValue(false),other?.sharedValue(false))
}

data class MobileDesktopSuggestionResponse(val schemaVersion: Int,val target: TargetScope,
    val threadId: String,val sourceTurnId: String,val readOnly: Boolean,
    val suggestion: MobileDesktopSuggestion?,val source: String?) {
    fun matches(scope: SuggestionScope)=DesktopSuggestionRules.matches(scope,schemaVersion,target,threadId,
        sourceTurnId,readOnly,source,suggestion?.sharedValue(false))
    companion object {
        fun decode(value: JSONObject,expected: SuggestionScope): MobileDesktopSuggestionResponse {
            CodexCommandCodec.validate(value,"desktopSuggestionResponse")
            val result=MobileDesktopSuggestionResponse(value.getInt("schemaVersion"),requireNotNull(HostReadCodec.target(value.getJSONObject("target"))),
                value.getString("threadId"),value.getString("sourceTurnId"),value.getBoolean("readOnly"),
                value.optJSONObject("suggestion")?.let { v -> MobileDesktopSuggestion(v.getString("id"),v.getString("text"),
                    v.getString("contentSha256"),v.getString("expiresAt"),v.optional("validity")) },value.optional("source"))
            if(!result.matches(expected))throw DesktopSuggestionSourceMismatch()
            return result
        }
    }
}
