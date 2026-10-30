package com.agenthub.android

/** A nullable result means absent metadata, never permission to select a legacy reader. */
internal class NativeSavedVoiceMetadataWitness(private val readMetadata: ()->String?,private val readPermission: ()->Int) {
    private val before=readMetadata()
    private val permission=readPermission()
    fun verify() {
        check(readMetadata()==before) {"Complete protected default metadata changed"}
        check(readPermission()==permission) {"Microphone permission changed"}
    }
}
