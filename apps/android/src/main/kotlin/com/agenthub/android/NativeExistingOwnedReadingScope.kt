package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import org.json.JSONObject

/** Existing Q04-owned writer only. This does not change the production reading ledger schema or rules.
 * An explicit logical-name permission is also required by ExistingOwnedPolicy. Other admitted rows and
 * scope tombstones are retained; only the one full machine target + QA thread may gain reading state.
 */
internal class NativeExistingOwnedReadingScope(private val target: TargetScope,private val thread: String,baseline: String?) {
    val targetKey=RuntimeIdentity.cacheKey(target.hostId,target,thread,null)
    private val scopeKey=ThreadReadingIdentity.scopeKey(target)
    private val rowKey=ThreadReadingIdentity.key(target,thread)
    private data class State(val revision: Long,val scopes: Set<String>,val rows: Map<String,String>)
    init {
        require(target.agentId=="codex")
        CodexCommandCodec.target(target)
        require(java.util.UUID.fromString(thread).toString()==thread)
    }
    private val existed=baseline!=null
    private val original=baseline?.let(::decode) ?: State(0,emptySet(),emptyMap())
    fun allowsTarget(value: TargetScope)=RuntimeIdentity.matches(value,target)
    fun allows(value: TargetScope,id: String)=allowsTarget(value)&&id==thread
    /** This permit never removes historical scopes or adds any scope except the exact QA machine. */
    fun reconcileScopes(previous: Set<String>,proposed: Set<String>): Set<String> {
        require(proposed.all {it==scopeKey}) {"Reading reconciliation escaped the QA scope"}
        return previous+proposed
    }
    private fun decode(text: String): State {
        // The admitted profile bounds each encrypted leaf to 1 MiB; reserve room for the v3 frame.
        require(text.toByteArray(Charsets.UTF_8).size<=1_000_000)
        NativeReadingLedger.validateEncoded(text) // Actual production parser, positions, notices and bounds.
        val root=JSONObject(text)
        // Production save() emits compact JSONObject text. Reject the Android parser's permissive
        // duplicate keys, comments, trailing input, coercions and alternate spellings at this boundary.
        require(root.toString()==text)
        val scopes=root.getJSONArray("scopes")
        require(scopes.length()==scopes.strings().toSet().size)
        val rows=root.getJSONArray("rows").objects().associate { row ->
            val rowTarget=checkNotNull(HostReadCodec.target(row.getJSONObject("target")))
            val rowThread=readingString(row,"threadId")
            ThreadReadingIdentity.key(rowTarget,rowThread) to easyCanonical(row)
        }
        return State(readingInteger(root,"revision"),scopes.strings().toSet(),rows)
    }
    /** Called under the real storage transaction lock, before encryption or AtomicFile.
     * Read the actual settled previous leaf each time; failed writes never advance a shadow baseline.
     */
    fun validate(previous: String?,proposed: String) {
        require(previous!=null||!existed) {"Admitted reading ledger disappeared"}
        val before=previous?.let(::decode) ?: State(0,emptySet(),emptyMap())
        val after=decode(proposed)
        require(before.revision>=original.revision)
        require((before.scopes-original.scopes).all {it==scopeKey}&&before.scopes.containsAll(original.scopes))
        require(before.rows.filterKeys {it!=rowKey}==original.rows.filterKeys {it!=rowKey})
        require(after.revision==Math.addExact(before.revision,1)) {"Reading revision must advance exactly once"}
        require(after.scopes==before.scopes+scopeKey) {"Reading scope changed outside the admitted target"}
        require(after.rows.filterKeys {it!=rowKey}==before.rows.filterKeys {it!=rowKey}) {"Reading delta escaped the QA thread"}
        require(rowKey !in before.rows||rowKey in after.rows) {"Admitted QA reading row cannot be removed"}
        // Any newly added or changed row must decode to the same complete machine target + thread.
        for(row in JSONObject(proposed).getJSONArray("rows").objects()) {
            val rowTarget=checkNotNull(HostReadCodec.target(row.getJSONObject("target")))
            val id=readingString(row,"threadId");val key=ThreadReadingIdentity.key(rowTarget,id)
            if(before.rows[key]!=after.rows[key])require(allows(rowTarget,id))
        }
        require(after.scopes!=before.scopes||after.rows!=before.rows) {"Reading revision changed without an admitted delta"}
    }
}
