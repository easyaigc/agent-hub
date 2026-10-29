package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class NativeMigrationLocalRecordFormatTest {
    @Test fun acceptsTheExistingTextTagsAndExactDataOutputStreamBinaryVersionPrefixes() {
        for(version in listOf(1,3))assertEquals(version,NativeMigrationLocalRecordFormat.read(ByteArrayInputStream(byteArrayOf(version.toByte(),7,8,9))))
        for(version in listOf(2,4)) {
            val source=ByteArrayOutputStream();DataOutputStream(source).use{it.writeInt(version);it.writeInt(1024)}
            val input=ByteArrayInputStream(source.toByteArray());assertEquals(version,NativeMigrationLocalRecordFormat.read(input));assertEquals(4,input.available())
        }
    }
    @Test fun truncatedUnknownAndSingleByteBinaryTagsNeverBecomeSupportedRecords() {
        for(bytes in listOf(byteArrayOf(),byteArrayOf(0),byteArrayOf(0,0),byteArrayOf(0,0,0),byteArrayOf(2),byteArrayOf(4),byteArrayOf(0,0,0,3),byteArrayOf(0,0,1,4),byteArrayOf(5)))
            assertTrue(runCatching {NativeMigrationLocalRecordFormat.read(ByteArrayInputStream(bytes))}.isFailure)
    }
}
