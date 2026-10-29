package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

/** Pure boundary vectors. Silence/fixture bytes are never an acoustic engine pass. */
class NativeSavedVoicePcmTest {
    @Test fun acceptsTheExactSealedMono16kPcmEnvelope() {SavedVoicePcm.validate(WavePCM.header(3200)+ByteArray(3200))}
    @Test fun rejectsMissingPcm() {assertTrue(runCatching {SavedVoicePcm.validate(WavePCM.header(0))}.isFailure)}
    @Test fun rejectsOddTruncatedPcm() {assertTrue(runCatching {SavedVoicePcm.validate(WavePCM.header(3)+ByteArray(3))}.isFailure)}
    @Test fun rejectsOtherChannelsAndSampleRates() {
        for(offset in listOf(22,24,34)) {val wav=WavePCM.header(3200)+ByteArray(3200);wav[offset]=(wav[offset].toInt() xor 1).toByte();assertTrue(runCatching {SavedVoicePcm.validate(wav)}.isFailure)}
    }
    @Test fun rejectsHeaderPayloadLengthMismatch() {assertTrue(runCatching {SavedVoicePcm.validate(WavePCM.header(3202)+ByteArray(3200))}.isFailure)}
    @Test fun rejectsPastTheOriginalFiveMinuteLimit() {assertTrue(runCatching {SavedVoicePcm.validate(WavePCM.header(9_600_002)+ByteArray(9_600_002))}.isFailure)}
}
