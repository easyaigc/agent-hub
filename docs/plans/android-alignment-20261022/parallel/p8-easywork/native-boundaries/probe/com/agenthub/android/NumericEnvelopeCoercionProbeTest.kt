package com.agenthub.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Passing means the defect was observed on host JSON, not that production is correct. */
class NumericEnvelopeCoercionProbeTest {
    @Test fun authenticatedIntegerFrameAlsoAcceptsFractionalJsonSequenceOnHostRuntime() {
        val root=File(System.getProperty("p8.root"))
        val vector=JSONObject(File(root,"apps/android/src/androidTest/assets/easywork-crypto-vector.json").readText())
        val credential=EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val crypto=EasyWorkCrypto(credential,credential.binding)
        val original=vector.getJSONObject("frame")
        assertEquals(42L,crypto.open(original.toString().toByteArray()).sequence)
        val changed=JSONObject(original.toString()).put("sequence",42.5)
        val opened=crypto.open(changed.toString().toByteArray())
        assertEquals(42L,opened.sequence)
        assertEquals(easyCanonical(vector.getJSONObject("payload")),easyCanonical(opened.payload))
        val output=File(System.getProperty("p8.probe.output"))
        output.writeText(JSONObject().put("status","defect-reproduced-on-host-json")
            .put("nativeAndroidExecuted",false).put("jsonRuntime","org.json:json:20250517")
            .put("outerSequence",42.5).put("authenticatedSequence",opened.sequence)
            .put("ciphertextChanged",false).put("payloadUnchanged",true)
            .put("meaning","Header numeric normalization accepted a modified JSON sequence; not an AES-GCM break or a native Android pass").toString())
    }
}
