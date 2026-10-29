package com.agenthub.android

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun main(){
    val proof=NativeBootstrapCiphertextProof
    val key=SecretKeySpec(ByteArray(32){(it+1).toByte()},"AES")
    val other=SecretKeySpec(ByteArray(32){(it+2).toByte()},"AES")
    val payload="{\"version\":1,\"purpose\":\"p9-native-key-bootstrap\",\"bootstrapId\":\"synthetic-only\"}".toByteArray()
    var checks=0
    fun reject(body:()->Unit){var refused=false;try{body()}catch(_:Exception){refused=true};check(refused);checks++}
    fun encrypted(name:String=proof.RECORD,aad:ByteArray=byteArrayOf(0xff.toByte(),3)+"agent-hub/android-local-record/v3".toByteArray(),data:ByteArray=payload):ByteArray {
        val bytes=name.toByteArray();val frame=ByteBuffer.allocate(4+bytes.size+data.size).putInt(bytes.size).put(bytes).put(data).array()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");val iv=ByteArray(12){it.toByte()};cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,iv));cipher.updateAAD(aad)
        return byteArrayOf(3)+iv+cipher.doFinal(frame)
    }
    val original=encrypted();val before=original.copyOf();val decoded=proof.authenticatedPayload(original,key);check(decoded.contentEquals(payload)&&original.contentEquals(before));decoded.fill(0);checks++
    reject{proof.authenticatedPayload(original,other)}
    for(i in original.indices){val changed=original.copyOf();changed[i]=(changed[i].toInt() xor 1).toByte();reject{proof.authenticatedPayload(changed,key)}}
    reject{proof.authenticatedPayload(encrypted(name="other-record"),key)}
    reject{proof.authenticatedPayload(encrypted(aad=byteArrayOf(1,2,3)),key)}
    reject{proof.authenticatedPayload(encrypted(data=byteArrayOf(0xc3.toByte(),0x28)),key)}
    reject{proof.authenticatedPayload(byteArrayOf(3),key)}
    reject{proof.authenticatedPayload(ByteArray(8193),key)}
    check(proof.familyDigest(original)!=proof.familyDigest(original.copyOf().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}));checks++
    println("$checks actual fixed-v3 decoder JVM checks passed; synthetic AES only, no AndroidKeyStore/native proof")
}
