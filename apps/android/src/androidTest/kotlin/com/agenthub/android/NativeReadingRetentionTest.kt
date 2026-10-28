package com.agenthub.android

import com.agenthub.core.TargetScope
import org.junit.Assert.*
import org.junit.Test
import java.lang.ref.Reference
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/** Inspect only reading-owned strong references, not the app's ordinary history cache or coroutine owners. */
internal object NativeReadingRetainedGraph {
    data class Size(var histories: Int=0,var messages: Int=0,var largeStrings: Int=0,var largeCharacters: Long=0)
    fun size(roots: List<Any?>): Size {
        val result=Size();val visited=IdentityHashMap<Any,Boolean>()
        fun visit(value: Any?) {
            if(value==null || visited.put(value,true)!=null || value is Reference<*> || value is Function<*>)return
            when(value) {
                is String -> { if(value.length>4096) { result.largeStrings++;result.largeCharacters+=value.length };return }
                is Map<*,*> -> { value.forEach { (k,v) -> visit(k);visit(v) };return }
                is Iterable<*> -> { value.forEach(::visit);return }
                is Pair<*,*> -> { visit(value.first);visit(value.second);return }
                is Triple<*,*,*> -> { visit(value.first);visit(value.second);visit(value.third);return }
            }
            if(value.javaClass.name.startsWith("androidx.compose.runtime.")) {
                value.javaClass.methods.firstOrNull { it.name=="getValue" && it.parameterCount==0 }?.let {
                    it.isAccessible=true;visit(it.invoke(value));return
                }
            }
            if(value is MobileHistory)result.histories++
            if(value is MobileMessage)result.messages++
            if(value is NativeSessionStorage || !value.javaClass.name.startsWith("com.agenthub."))return
            value.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.forEach { field ->
                field.isAccessible=true;visit(field.get(value))
            }
        }
        roots.forEach(::visit);return result
    }
    fun session(session: MobileSession): Size = size(session.javaClass.declaredFields.filter {
        it.name.startsWith("phoneReading") || it.name=="cachedPhoneReading" || it.name=="attemptedPhoneReading"
    }.map { it.isAccessible=true;it.get(session) })
}

/** Pure current-proof and weak digest cache boundary, also runnable on the host JVM without heap/GC assumptions. */
class NativeReadingRetentionTest {
    private val target=TargetScope("codex",null,null,"host","workspace","runtime")
    @Test fun oldLargePagesAreNotRetainedByCurrentProofOrLegacyFingerprint() {
        val fingerprint=NativeLegacyReadingFingerprint();val regression=mutableMapOf<String,MobileHistory>()
        var proof: NativeReadingProof?=null
        repeat(20) { n ->
            val text="reply-$n "+"中文😀".repeat(32_768)
            val message=MobileMessage("message-$n","assistant",text,"completed")
            val page=MobileHistory("host","thread-$n",listOf(message),null,target,"idle",null,"raw-wire-$n:$text")
            val key=ThreadReadingIdentity.key(target,page.threadId)
            val digest=fingerprint.version(key,message.id,text,"completed")
            assertEquals(digest,fingerprint.version(key,message.id,text,"completed"))
            proof=NativeReadingProof(target,page.threadId,null,NativeLegacyReading(target,page.threadId,message.id,digest,true),n.toLong(),1,1)
            regression[page.threadId]=page
        }
        val old=NativeReadingRetainedGraph.size(listOf(regression))
        assertEquals(20,old.histories);assertEquals(20,old.messages);assertTrue(old.largeCharacters>5_000_000)
        val retained=NativeReadingRetainedGraph.size(listOf(proof,fingerprint))
        assertEquals(0,retained.histories);assertEquals(0,retained.messages);assertEquals(0,retained.largeStrings)
        assertEquals("thread-19",proof!!.threadId)
        fingerprint.clear();assertEquals(0,NativeReadingRetainedGraph.size(listOf(fingerprint)).largeStrings)
    }
}
