package com.agenthub.android

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException

/** Strict UTF-8 with a byte limit, without retaining expanding byte and character copies together. */
internal object NativeWireReader {
    fun reader(input: InputStream, limit: Int, active: ()->Boolean): InputStreamReader {
        var bytes=0
        val bounded=object: FilterInputStream(input) {
            override fun read(): Int {
                if(!active()) throw CancellationException("Read cancelled")
                val value=`in`.read(); if(value>=0) { bytes++; require(bytes<=limit) { "电脑返回数据过大" } };return value
            }
            override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                if(!active()) throw CancellationException("Read cancelled")
                val count=`in`.read(buffer,offset,length)
                if(count>0) { bytes+=count;require(bytes<=limit) { "电脑返回数据过大" } };return count
            }
        }
        return InputStreamReader(bounded,Charsets.UTF_8.newDecoder())
    }
    fun read(input: InputStream, limit: Int, active: ()->Boolean): String {
        val parts=ArrayList<String>();var characters=0;val buffer=CharArray(8192)
        reader(input,limit,active).use { reader ->
            while(true) {
                val count=reader.read(buffer);if(count<0) break
                if(!active()) throw CancellationException("Read cancelled")
                parts.add(String(buffer,0,count));characters+=count
            }
        }
        return buildString(characters) { parts.forEach { append(it) } }
    }
}
