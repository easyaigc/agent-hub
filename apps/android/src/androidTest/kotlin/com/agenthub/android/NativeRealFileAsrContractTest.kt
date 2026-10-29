package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

/** Host-safe contract vectors only, never actual engine or device evidence. */
class NativeRealFileAsrContractTest {
    private val values=mapOf("fileAsrLease" to "00000000-0000-4000-8000-000000000001","fileAsrLocale" to "en-US","fileAsrAudioSha256" to "a".repeat(64))
    @Test fun canonicalLeaseAndClosedLocaleAndHashAreAccepted() {assertEquals("en-US",NativeRealFileAsrContract.parse(values).locale)}
    @Test fun alternateUuidSpellingsAndPathsAreRejected() {for(value in listOf("1-1-1-1-1","../input.wav","https://example.invalid/a","00000000-0000-4000-8000-00000000000A"))assertTrue(runCatching{NativeRealFileAsrContract.parse(values+("fileAsrLease" to value))}.isFailure)}
    @Test fun unsupportedLocaleDoesNotFallBackToEnglishOrMicrophone() {for(locale in listOf("en","zh","en-GB","auto","fr-FR"))assertTrue(runCatching{NativeRealFileAsrContract.parse(values+("fileAsrLocale" to locale))}.isFailure)}
    @Test fun upperShortOrUnknownAudioHashesAreRejected() {for(hash in listOf("A".repeat(64),"a".repeat(63),"g".repeat(64)))assertTrue(runCatching{NativeRealFileAsrContract.parse(values+("fileAsrAudioSha256" to hash))}.isFailure)}
    @Test fun noUrlPathOrMethodParameterCanBePassedThrough() {for(key in listOf("fileAsrPath","fileAsrUrl","fileAsrMethod","fileAsrAllowNetwork"))assertTrue(runCatching{NativeRealFileAsrContract.parse(values+(key to "anything"))}.isFailure)}
    @Test fun declaredPhrasesNormalizeOnlyPunctuationWhitespaceAndCase() {assertEquals(NativeRealFileAsrContract.normalize(NativeRealFileAsrContract.phrase("en-US")),NativeRealFileAsrContract.normalize("Open the project, and show the latest message."));assertEquals("打开项目显示最新消息",NativeRealFileAsrContract.normalize("打开项目，显示最新消息。"))}
}
