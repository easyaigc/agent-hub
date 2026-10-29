package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

class NativeVoiceDestinationKeysTest {
    private val base="codex-v3:full-runtime-thread"
    @Test fun exactComposerAndClosedQuestionSuffixAreAccepted() {assertTrue(NativeVoiceDestinationKeys.matches(setOf(base),base));assertTrue(NativeVoiceDestinationKeys.matches(setOf(base),base+":question:"+"a".repeat(64)))}
    @Test fun otherThreadAndOtherTargetAreRejected() {assertFalse(NativeVoiceDestinationKeys.matches(setOf(base),"other-thread:question:"+"a".repeat(64)));assertFalse(NativeVoiceDestinationKeys.matches(setOf("other-runtime"),base+":question:"+"a".repeat(64)))}
    @Test fun arbitraryPrefixAndTrailingDataAreRejected() {for(key in listOf(base+"x:question:"+"a".repeat(64),base+":question:"+"a".repeat(64)+"x"))assertFalse(NativeVoiceDestinationKeys.matches(setOf(base),key))}
    @Test fun NoncanonicalDigestFormsAreRejected() {for(hash in listOf("A".repeat(64),"a".repeat(63),"a".repeat(65),"g".repeat(64)))assertFalse(NativeVoiceDestinationKeys.matches(setOf(base),base+":question:"+hash))}
    @Test fun versionedQuestionOwnerRoundTripsTheWholeLogicalDestination() {val key=base+":question:"+"a".repeat(64);val owner=NativeVoiceDestinationKeys.owner(key);assertEquals(key,String(java.util.Base64.getUrlDecoder().decode(owner.removePrefix("voice-question-draft-v1:")),Charsets.UTF_8))}
    @Test fun exactBaseWinsOverSuffixLikeThreadNames() {val key=base+":question:"+"a".repeat(64);assertEquals(key,NativeVoiceDestinationKeys.owner(key,setOf(key)))}
}
