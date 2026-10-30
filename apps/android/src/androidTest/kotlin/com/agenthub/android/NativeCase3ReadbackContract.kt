package com.agenthub.android

import java.security.MessageDigest

/** Source55 case3 only. Historical synthetic identifiers are an allowlist, never runtime inputs. */
internal object NativeCase3ReadbackContract {
    const val PROFILE="source55-case3-original-v1"
    const val PACKAGE="com.easyai.agenthub.android.acceptance"
    const val UID=10076
    const val RUN="23395eea-62fc-43cf-90d7-328c0e2fcaf0"
    const val METHOD="unresolvedDiscardKeepsModelActionsGatedUntilExplicitReceiptRecovery"
    const val FIXTURE="8f76fb48-6e8a-4920-a26d-08bd121724c1"
    const val RECORDING="a9e05d2a-b339-4453-8806-1bc0bf0c8bf9"
    const val ALIAS="agent-hub-panel-"+FIXTURE
    const val METHOD_PATH="acceptance/P7-voice-interaction/"+RUN+"/"+METHOD
    const val OUTPUT_PATH="acceptance/P7-source55-case3-original-v1"
    const val ARCHIVE_SHA="b9c3b45463fc836a251a35bf419eef84ce294a8f95b6197e0e6ca961be1728e1"
    const val INDEX_SHA="a2d3c7f59988aa22e34e3ba7f64bcfe1acf354a5a496150b6196e20cc51c97f4"
    const val ORIGINAL_SHA="ed6fedee4d955fb2a6af9d469b7a5d9636d123dc0668cde7c07bdda04c81aed4"
    const val WAV_SHA="9d926e59a8259999491bddd13cd65ad6ff3f63916a9dd10a86d8594169ad9550"
    const val WAV_BYTES=3244
    const val DEVICE=65056L
    const val ROW_SHA="2863f1a334172fc9aa10efac53807c049176d6373a57f2c57157243b65c57a56"
    const val CANONICAL_SHA="2e827f681ad2ab9b73c116b6f9ea32e177a66894d5c1936b09e4769298dcd978"
    const val METADATA_SHA="adfb4f5e023bd5029ebdf36954406b3ce1ba25565ac60f3f5d85393db6c3f287"
    const val AUDIT_FILE="747d72f24a82e98411d210dc16d9e713b79ca92b2c02b3c3c21eba4d20ca15b4.bin"
    data class Entry(val path: String,val mode: Int,val links: Int,val bytes: Long,val inode: Long,val time: Long,val sha: String) {
        val directory get()=mode==16832
        fun line()=listOf(path,mode,UID,UID,links,bytes,DEVICE,inode,time,time,sha).joinToString("\t")
    }
    val entries=listOf(
        Entry("",16832,3,4096L,123896L,1793310481L,""),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1",16832,3,4096L,123898L,1793310480L,""),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup",16832,4,4096L,123899L,1793310479L,""),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup/original",16832,2,4096L,123900L,1793310480L,""),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup/original/45447b7afbd5e544f7d0f1df0fccd26014d9850130abd3f020b89ff96b82079f.bin",33152,1,1444L,123905L,1793310480L,"60cbc31aab609e8db45b585c600f0f47e12133a6208009635cee99545d72349f"),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup/original/747d72f24a82e98411d210dc16d9e713b79ca92b2c02b3c3c21eba4d20ca15b4.bin",33152,1,1838L,123908L,1793310480L,"07c769cac7cb389db54096dda1a76d697f1882575c1fd724132c0a98a6469fc7"),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup/original/baaef2edda09014f0da4f5fd8969ffcffd0be4f518b79a1699967c61710376bf.bin",33152,1,429L,123909L,1793310480L,"9c3e49027b4cc7c9f117f29d655e3f2f7c130339052826edef43eff965476a86"),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup/original/cd0c1597cc90fecb84cc6253828bdd67c70a6cbf78f4087c4f1a8f51c6eb04b9.bin",33152,1,87L,123903L,1793310479L,"1541a720ea89ce0f814366d717ace6fdcc9722f3ef08086c0ea48106ef080f46"),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup/original/d2f5dc6680c12214449e11f981350d1ce7da27a49de96b2a33a2eeb9412015d5.bin",33152,1,3364L,123906L,1793310479L,"e3641f9ed8a62ae3921019b8bdfc55f49388302e5c1fa99abed31fcce0bdfd16"),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup/original/ee180ea60b2e53f89b096a9c1e6dbcc211dcdfb4eed8aec90c246e9f75d0be8f.bin",33152,1,1791L,123904L,1793310480L,"a256e35a9bf95a7706a39d06e09fd827c4ee01cc8c52a631988687097fb0af21"),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup/voice-recordings-v1",16832,2,4096L,123901L,1793310480L,""),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/no-backup/voice-recordings-writer-v1.lock",33152,1,0L,123902L,1793310479L,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        Entry("8f76fb48-6e8a-4920-a26d-08bd121724c1/original.json",33152,1,5448L,123907L,1793310480L,"ed6fedee4d955fb2a6af9d469b7a5d9636d123dc0668cde7c07bdda04c81aed4"),
        Entry("intent.json",33152,1,490L,123897L,1793310479L,"aee403b4ce1aebec36d1fa2b938312cc186d53e91bc7ab7420c8e956b0068438"),
        Entry("result.json",33152,1,1750L,123910L,1793310481L,"bb8d7d36ca547c46c6906f10bd7da8f49e33092a80afb069d46fb42d5c3b96e5")
    )
    val inventorySha get()=sha(entries.joinToString("\n",postfix="\n"){it.line()}.toByteArray(Charsets.UTF_8))
    fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun hash(value: String)=value.matches(Regex("[a-f0-9]{64}"))

    /** Canonical ASCII, fixed order, no optional/unknown/duplicate keys or path/alias fields. */
    data class CleanupAck(val receiptSha: String,val rootCaptureSha: String) {
        companion object {
            fun parse(bytes: ByteArray,receiptSha: String): CleanupAck {
                require(bytes.size in 1..4096 && hash(receiptSha))
                require(bytes.all {it.toInt() in 10..126 && it.toInt()!=13})
                val text=bytes.toString(Charsets.US_ASCII);require(text.endsWith("\n"))
                val lines=text.dropLast(1).split('\n');require(lines.size==16)
                val keys=listOf("schema","profile","readReceiptSha256","inventorySha256","sourceArchiveSha256","sourceIndexSha256",
                    "originalProofSha256","wavSha256","wavBytes","rootCaptureSha256","rootFullWavCaptured",
                    "rootAllOriginalStringsCompared","rootSourceCipherArchiveCaptured","rootIndependentDecryption",
                    "rootWorkerExitConfirmed","rootAuthorizesExactFixtureAndKeyCleanup")
                val values=lines.mapIndexed {i,line->require(line.startsWith(keys[i]+"="));line.substring(keys[i].length+1)}
                val capture=values[9];require(hash(capture))
                val expected=listOf("1",PROFILE,receiptSha,inventorySha,ARCHIVE_SHA,INDEX_SHA,ORIGINAL_SHA,WAV_SHA,WAV_BYTES.toString(),
                    capture,"true","true","true","false","true","true")
                require(values==expected)
                return CleanupAck(receiptSha,capture)
            }
        }
    }
}
