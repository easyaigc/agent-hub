package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

class NativeSavedVoiceMetadataWitnessTest {
    @Test fun absentMetadataRemainsAnExplicitReadResult() {
        var reads=0
        val witness=NativeSavedVoiceMetadataWitness({reads++;null},{7})
        repeat(3){witness.verify()}
        assertEquals(4,reads)
    }
    @Test fun completeMetadataAndPermissionMustRemainIdentical() {
        var metadata: String?="{\"drafts\":{},\"attachments\":[],\"unrelated\":1}"
        var permission=0
        val witness=NativeSavedVoiceMetadataWitness({metadata},{permission})
        witness.verify()
        metadata="{\"drafts\":{},\"attachments\":[],\"unrelated\":2}"
        assertThrows(IllegalStateException::class.java){witness.verify()}
        metadata=null
        assertThrows(IllegalStateException::class.java){witness.verify()}
        metadata="{\"drafts\":{},\"attachments\":[],\"unrelated\":1}"
        permission=1
        assertThrows(IllegalStateException::class.java){witness.verify()}
    }
    @Test fun absentToPresentAndReadFailureNeverBecomeFallbackSuccess() {
        var metadata: String?=null
        var failure=false
        val witness=NativeSavedVoiceMetadataWitness({if(failure)error("pending backup");metadata},{0})
        metadata="{}"
        assertThrows(IllegalStateException::class.java){witness.verify()}
        metadata=null;failure=true
        assertThrows(IllegalStateException::class.java){witness.verify()}
        assertThrows(IllegalStateException::class.java){NativeSavedVoiceMetadataWitness({error("missing key")},{0})}
    }
}
