package com.agenthub.android

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic JSON only. No Context, Session, store, Activity, SDK, HTTP or fixture staging. */
class F31OwnedComposerPolicyTest {
    private val run = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val source = UUID.nameUUIDFromBytes("f31-owned-composer:$run".toByteArray()).toString()
    private val key = ByteArray(32) { 7 } // Synthetic evidence key, never a user/key-store key.
    private fun copy(j: JSONObject) = JSONObject(j.toString())
    private fun entry(v: Any?) = JSONObject().put("present", v != null).put("value", v ?: JSONObject.NULL)
    private fun version(i: Int) = "${9-i}0000000-0000-4000-8000-000000000000" // Deliberately descending lexical order.
    private data class Case(val original: JSONObject, val chain: JSONArray, val staged: JSONObject, val restored: JSONObject)
    private fun scenario(nonempty: Boolean = false, existingGeneration: Boolean = false): Case {
        val original = JSONObject().put("drafts", entry(if (nonempty) "original text" else null))
            .put("draftFiles", entry(if (nonempty) JSONArray().put(JSONObject().put("id", "original-reference")) else null))
            .put("draftVersions", entry(version(0)))
            .put("attachmentImportGenerations", entry(if (existingGeneration) version(8) else null))
            .put("photoSources", JSONObject()).put("otherKeys", JSONObject().apply {
                for (field in listOf("drafts", "draftFiles", "draftVersions", "attachmentImportGenerations", "photoSources"))
                    put(field, "a".repeat(64))
            })
        val originalFiles = if (nonempty) original.getJSONObject("draftFiles").getJSONArray("value") else JSONArray()
        val own = JSONObject().put("id", source).put("name", "f31-owned-$run.bin")
            .put("mediaType", "application/octet-stream").put("sizeBytes", 8192)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(ByteArray(8192) { (it % 251).toByte() })
                .joinToString("") { "%02x".format(it) })
        val operations = listOf("attachmentImportDestination", "importAttachments.await", "MobileSession.text",
            "MobileSession.text.restore", "removeAttachment")
        val chain = JSONArray(); var current = copy(original); var staged = copy(original)
        for (i in operations.indices) {
            val next = copy(current)
            when (i) {
                0 -> if (!existingGeneration) next.put("attachmentImportGenerations", entry(version(8)))
                1 -> next.put("draftFiles", entry(JSONArray(originalFiles.toString()).put(own)))
                2 -> next.put("drafts", entry((if (nonempty) "original text" else "") + "\n[F31-owned-draft:$run]"))
                3 -> next.put("drafts", entry(if (nonempty) "original text" else ""))
                4 -> next.put("draftFiles", entry(JSONArray(originalFiles.toString())))
            }
            if (i > 0) next.put("draftVersions", entry(version(i)))
            chain.put(JSONObject().put("operation", operations[i]).put("before", copy(current)).put("after", copy(next)))
            current = next
            if (i == 2) staged = copy(next)
        }
        return Case(original, chain, staged, current)
    }
    private fun matched(c: Case, before: JSONObject? = c.staged, after: JSONObject? = c.staged,
        cipher: Boolean = true, retired: Boolean = true) = F31OwnedComposerPolicy.evaluate(c.original, c.restored,
            c.chain, run, source, before, after, key, cipher, retired).getBoolean("matched")

    @Test fun closesOnlyProvenEmptyNormalizationAndOpaqueUUIDTransitions() {
        assertTrue(matched(scenario()))
        assertTrue(matched(scenario(nonempty = true, existingGeneration = true)))
    }
    @Test fun rejectsOriginalNonemptyTextOrReferenceLoss() {
        val text = scenario(nonempty = true)
        text.restored.put("drafts", entry(""))
        assertFalse(matched(text))
        val refs = scenario(nonempty = true)
        refs.restored.put("draftFiles", entry(JSONArray()))
        assertFalse(matched(refs))
    }
    @Test fun rejectsExistingGenerationChangeEvenWithSelfConsistentChain() {
        val c = scenario(existingGeneration = true)
        for (i in 0 until c.chain.length()) {
            c.chain.getJSONObject(i).getJSONObject("after").put("attachmentImportGenerations", entry(version(7)))
            if (i > 0) c.chain.getJSONObject(i).getJSONObject("before").put("attachmentImportGenerations", entry(version(7)))
        }
        c.restored.put("attachmentImportGenerations", entry(version(7)))
        assertFalse(matched(c))
    }
    @Test fun rejectsOtherKeyChangeUnknownFieldOrPhotoOriginLoss() {
        val c = scenario()
        c.chain.getJSONObject(1).getJSONObject("after").getJSONObject("otherKeys").put("drafts", "b".repeat(64))
        assertFalse(matched(c))
        val unknown = scenario(); unknown.restored.put("unknownField", true)
        assertFalse(matched(unknown))
        val photo = scenario(); photo.restored.getJSONObject("photoSources").put("original-photo", "changed")
        assertFalse(matched(photo))
    }
    @Test fun rejectsMissingOrWrongProductionChain() {
        val c = scenario(); c.chain.remove(3)
        assertFalse(matched(c))
        val wrong = scenario(); wrong.chain.getJSONObject(0).put("operation", "synthetic-metadata-rewrite")
        assertFalse(matched(wrong))
    }
    @Test fun rejectsCancelVersionTextGenerationOrReferenceChange() {
        val c = scenario()
        for ((field, changed) in listOf("draftVersions" to entry(version(6)), "drafts" to entry("changed"),
            "attachmentImportGenerations" to entry(version(7)), "draftFiles" to entry(JSONArray()))) {
            assertFalse(matched(c, after = copy(c.staged).put(field, changed)))
        }
        assertFalse(matched(c, after = null))
    }
    @Test fun rejectsUnownedFileOrChangedOriginalPresence() {
        val c = scenario()
        c.chain.getJSONObject(1).getJSONObject("after").getJSONObject("draftFiles")
            .getJSONArray("value").getJSONObject(0).put("id", "another-id")
        assertFalse(matched(c))
        val presence = scenario(nonempty = true); presence.restored.put("drafts", entry(null))
        assertFalse(matched(presence))
    }
    @Test fun rejectsUnprovenCipherPreservationOrSourceRetirement() {
        assertFalse(matched(scenario(), cipher = false))
        assertFalse(matched(scenario(), retired = false))
    }
    @Test fun rejectsGenerationWithoutCanonicalUUID() {
        val c = scenario()
        c.chain.getJSONObject(0).getJSONObject("after").put("attachmentImportGenerations", entry("not-a-uuid"))
        assertFalse(matched(c))
        val changed = scenario()
        changed.chain.getJSONObject(2).getJSONObject("after").put("attachmentImportGenerations", entry(version(7)))
        assertFalse(matched(changed))
    }
    @Test fun safeEvidenceDoesNotExportOriginalTextOrMACKey() {
        val c = scenario(nonempty = true)
        val proof = F31OwnedComposerPolicy.evaluate(c.original, c.restored, c.chain, run, source,
            c.staged, c.staged, key, true, true)
        assertTrue(proof.getBoolean("matched"))
        assertFalse(proof.toString().contains("original text"))
        assertFalse(proof.toString().contains("evidenceKey"))
    }
    @Test fun journalCapacityRejectsBeforeAnyWriterCall() {
        assertTrue(F31OwnedComposerPolicy.journalFits(1_048_576))
        assertFalse(F31OwnedComposerPolicy.journalFits(1_048_577))
        assertFalse(F31OwnedComposerPolicy.journalFits(0))
        val large = scenario().original.put("drafts", entry("x".repeat(90_000)))
        assertTrue(F31OwnedComposerPolicy.stageRecoveryBytes(large, JSONArray()) > F31OwnedComposerPolicy.MAX_JOURNAL_BYTES)
    }

    private fun projectedTrustPolicies(reverse: Boolean = false, changedPin: Boolean = false): Map<String, Any?> {
        val target = mapOf("agentId" to "codex", "hostId" to "synthetic-host", "workspaceId" to "synthetic-host", "runtimeId" to "desktop-runtime")
        val candidates = listOf(
            mapOf("id" to "one", "transportId" to "lan-wss", "endpoint" to "https://one.invalid", "peerFingerprint" to "a".repeat(64), "networkKind" to "lan"),
            mapOf("id" to "two", "transportId" to "lan-wss", "endpoint" to "https://two.invalid", "peerFingerprint" to (if (changedPin) "c" else "b").repeat(64), "networkKind" to "vpn")
        ).let { if (reverse) it.reversed() else it }
        val client = mapOf("id" to "synthetic-host", "mode" to "desktop-runtime", "scope" to target,
            "agentConnection" to mapOf("target" to target, "candidates" to candidates, "credentialRef" to "synthetic-ref", "preference" to "automatic"))
        return F31UserDataRulesV2.project(mapOf("version" to 1L, "clients" to listOf(client), "workspaces" to emptyMap<String, Any?>()), emptyMap())
    }
    private fun witnessCanonical(input: Any?) = NativeChatRecoveryCanonical.value(F31MetadataWitnessValues.value(input))
    @Test fun metadataWitnessAcceptsActualProductionProjectionPolicySet() {
        val projected = projectedTrustPolicies()
        val client = F31UserDataRulesV2.obj((projected["clients"] as List<*>).single())
        val policies = F31UserDataRulesV2.obj(client["agentConnection"])["directTrustPolicies"]
        assertTrue(policies is Set<*> && policies.size == 2)
        assertTrue(witnessCanonical(projected) == witnessCanonical(projectedTrustPolicies(reverse = true)))
    }
    @Test fun metadataWitnessKeepsPolicyChangesAndOrderedListsDistinct() {
        assertFalse(witnessCanonical(projectedTrustPolicies()) == witnessCanonical(projectedTrustPolicies(changedPin = true)))
        val one = mapOf("transportId" to "lan-wss", "peerFingerprint" to "a".repeat(64))
        val two = mapOf("transportId" to "lan-wss", "peerFingerprint" to "b".repeat(64))
        assertFalse(witnessCanonical(linkedSetOf(one, two)) == witnessCanonical(listOf(one, two)))
        assertFalse(witnessCanonical(listOf(one, two)) == witnessCanonical(listOf(two, one)))
    }
    @Test fun metadataWitnessRejectsUnrelatedOrOversizedSetWithoutPublishingValues() {
        for (input in listOf(setOf("synthetic-unsupported-value"), (0..32).map { mapOf("transportId" to "p$it") }.toSet())) {
            val failure = runCatching { F31MetadataWitnessValues.value(input) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertFalse(failure.toString().contains("synthetic-unsupported-value"))
        }
    }
}
