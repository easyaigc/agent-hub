package com.agenthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ComputerFileFamilyInventoryTest {
    private val original = "00000000-0000-4000-8000-000000000001"
    private val absentGeneration = "00000000-0000-4000-8000-000000000002"
    private val emptyGeneration = "00000000-0000-4000-8000-000000000003"
    private val liveGeneration = "00000000-0000-4000-8000-000000000004"
    private val clearHash = "a".repeat(64)
    private val cipherHash = "b".repeat(64)
    private val pointer = ComputerFileFamilySourcePointer.Observed(liveGeneration, "c".repeat(64))
    private val journal = ComputerFileFamilyRecord(
        ComputerFileMigrationEntry("computer-files-v1:" + "0".repeat(64), 37, clearHash), cipherHash
    )
    private val chunk = ComputerFileFamilyRecord(
        ComputerFileMigrationEntry("computer-file-original:$original:0", 25, "d".repeat(64)), "e".repeat(64)
    )
    private val retired = ComputerFileFamilyRetiredChunk(journal.entry.name, clearHash, original, 0, chunk.entry,
        ComputerFileFamilyTerminalObservation.SOURCE_FILE_AND_DIRECTORIES_SYNCED)
    private val root = ComputerFileFamilyMember(null, ComputerFileFamilyChild.PRESENT_WITH_RECORDS,
        listOf(journal), emptyList(), listOf(retired))
    private val absent = ComputerFileFamilyMember(absentGeneration, ComputerFileFamilyChild.ABSENT,
        emptyList(), emptyList(), emptyList())
    private val empty = absent.copy(generation = emptyGeneration, child = ComputerFileFamilyChild.PRESENT_EMPTY)
    private val live = ComputerFileFamilyMember(liveGeneration, ComputerFileFamilyChild.PRESENT_WITH_RECORDS,
        listOf(journal), listOf(chunk), emptyList())
    private val budget = ComputerFileFamilyInventoryBudget(3, 2, 2, 25)

    @Test fun allMemberStatesAndSameNamesRemainSeparateFromThePointer() {
        val borrowed = mutableListOf(journal)
        val members = mutableListOf(root.copy(journals = borrowed), absent, empty, live)
        val observed = ComputerFileFamilyInventory.validated(members, pointer, budget)
        borrowed.clear()
        members.clear()
        assertEquals(listOf(root, absent, empty, live), observed.members)
        assertNull(observed.members.first().generation)
        assertEquals(pointer, observed.sourcePointer)
        assertEquals(listOf(retired), observed.members.first().retired)
        assertEquals(listOf(chunk), observed.members.last().chunks)
    }

    @Test fun allEmptyFamiliesNeedNoFakeRecordsAndPointerMustNameAMember() {
        val noRecords = listOf(absent.copy(generation = null), empty)
        val observed = ComputerFileFamilyInventory.validated(noRecords, ComputerFileFamilySourcePointer.Absent, budget)
        assertEquals(noRecords, observed.members)
        assertFailsWith<IllegalArgumentException> { ComputerFileFamilyInventory.validated(noRecords, pointer, budget) }
        assertFailsWith<IllegalArgumentException> {
            ComputerFileFamilyInventory.validated(noRecords.reversed(), ComputerFileFamilySourcePointer.Absent, budget)
        }
    }

    @Test fun reportedListSizesCannotAdmitExtraMembersOrChunkDescriptors() {
        val emptyRoot = absent.copy(generation = null)
        val members = withReportedSize(listOf(emptyRoot, empty), 1)
        assertFailsWith<IllegalArgumentException> {
            ComputerFileFamilyInventory.validated(
                members, ComputerFileFamilySourcePointer.Absent, budget.copy(maximumGenerations = 0)
            )
        }

        val parts = List(3) { index -> chunk.copy(entry = chunk.entry.copy(name = "computer-file-original:$original:$index")) }
        val rootWithExtraChunk = root.copy(chunks = withReportedSize(parts, 2), retired = emptyList())
        assertFailsWith<IllegalArgumentException> {
            ComputerFileFamilyInventory.validated(
                listOf(rootWithExtraChunk), ComputerFileFamilySourcePointer.Absent, budget.copy(maximumBinaryBytes = 100)
            )
        }
    }

    private fun <T> withReportedSize(values: List<T>, reportedSize: Int): List<T> = object : AbstractList<T>() {
        override val size = reportedSize
        override fun get(index: Int) = values[index]
        override fun iterator() = values.iterator()
    }

    @Test fun retiredJoinAndWholeFamilyBudgetsRefuseBeforeAnyEncoding() {
        for (invalidRoot in listOf(
            root.copy(retired = listOf(retired.copy(terminalJournalSha256 = cipherHash))),
            root.copy(retired = listOf(retired.copy(index = 1))),
            root.copy(chunks = listOf(chunk)),
            root.copy(child = ComputerFileFamilyChild.ABSENT)
        )) {
            assertFailsWith<IllegalArgumentException> {
                ComputerFileFamilyInventory.validated(listOf(invalidRoot, live), pointer, budget.copy(maximumChunkDescriptors = 3))
            }
        }
        for (tooSmall in listOf(
            budget.copy(maximumChunkDescriptors = 1),
            budget.copy(maximumJournals = 1),
            budget.copy(maximumBinaryBytes = 24)
        )) {
            assertFailsWith<IllegalArgumentException> {
                ComputerFileFamilyInventory.validated(listOf(root, live), pointer, tooSmall)
            }
        }
    }
}
