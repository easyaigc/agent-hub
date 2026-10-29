package com.agenthub.android

import java.io.DataInputStream
import java.io.InputStream

/** Local text uses a one-byte tag; local chunked binary uses a four-byte big-endian tag.
 * This identifies framing only. Name/body authentication remains mandatory in the storage reader.
 */
internal object NativeMigrationLocalRecordFormat {
    fun read(input: InputStream): Int {
        val first=input.read()
        if(first==1||first==3)return first
        require(first==0){"Unknown local migration format"}
        val tail=DataInputStream(input)
        require(tail.readUnsignedByte()==0&&tail.readUnsignedByte()==0){"Unknown local migration format"}
        val version=tail.readUnsignedByte()
        require(version==2||version==4){"Unknown local migration format"}
        return version
    }
}
