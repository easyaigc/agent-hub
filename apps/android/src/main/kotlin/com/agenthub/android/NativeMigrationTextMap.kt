package com.agenthub.android

import java.io.OutputStream
import java.util.AbstractMap.SimpleImmutableEntry

/** A bounded record is authenticated independently. No path, URI, model or command enters this view. */
internal object NativeMigrationTextFrames {
    const val MAX_RECORD_BYTES=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES
    fun read(blob: NativeMigrationBlob): String {
        require(blob.size in 1..MAX_RECORD_BYTES)
        val bytes=ByteArray(blob.size);var used=0
        try {
            blob.verifiedCopy(object: OutputStream() {
                override fun write(value: Int){require(used<bytes.size);bytes[used++]=value.toByte()}
                override fun write(input: ByteArray,offset: Int,count: Int) {
                    require(offset>=0&&count>=0&&offset<=input.size-count&&count<=bytes.size-used)
                    System.arraycopy(input,offset,bytes,used,count);used+=count
                }
            })
            require(used==bytes.size)
            return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }finally{bytes.fill(0)}
    }
    fun bytes(value: String)=Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value)).let {buffer->
        require(buffer.remaining() in 1..MAX_RECORD_BYTES);ByteArray(buffer.remaining()).also {buffer.get(it)}
    }
    /** Legacy bounded maps and small generated catalogs may retain their existing immutable String. */
    fun retained(value: String): NativeMigrationBlob {
        val bytes=bytes(value)
        try {
            val probe=NativeMigrationProbe();probe.write(bytes)
            return NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()) {output->
                val current=bytes(value);try{output.write(current)}finally{current.fill(0)}
            }
        }finally{bytes.fill(0)}
    }
}

/** Sparse index. Iteration loads one exact record at a time; keys/contains/alias never decode values. */
internal class NativeMigrationTextMap private constructor(private val cells: LinkedHashMap<String,NativeMigrationBlob>,
    private val persist: ((String,String)->NativeMigrationBlob)?): AbstractMutableMap<String,String>() {
    constructor(values: Map<String,NativeMigrationBlob>):this(LinkedHashMap(values),null)
    override val size get()=cells.size
    override fun containsKey(key: String)=cells.containsKey(key)
    override fun get(key: String)=cells[key]?.let(NativeMigrationTextFrames::read)
    override val keys: MutableSet<String> get()=cells.keys
    override fun put(key: String,value: String): String? {
        val prior=cells[key]
        cells[key]=persist?.invoke(key,value)?:NativeMigrationTextFrames.retained(value)
        return prior?.let(NativeMigrationTextFrames::read)
    }
    override fun remove(key: String)=cells.remove(key)?.let(NativeMigrationTextFrames::read)
    override fun clear()=cells.clear()
    fun frame(name: String): NativeMigrationBlob=cells.getValue(name)
    fun frames(): Map<String,NativeMigrationBlob> = LinkedHashMap(cells)
    fun forget(keys: Set<String>){keys.forEach(cells::remove)}
    fun alias(source: Map<String,String>,original: String,key: String) {
        val frame=if(source is NativeMigrationTextMap)source.cells.getValue(original)else NativeMigrationTextFrames.retained(source.getValue(original))
        cells[key]?.let {prior->require(prior.size==frame.size&&prior.sha256==frame.sha256&&prior.prefix.contentEquals(frame.prefix))}
        cells[key]=frame
    }
    override val entries: MutableSet<MutableMap.MutableEntry<String,String>> get()=object: AbstractMutableSet<MutableMap.MutableEntry<String,String>>() {
        override val size get()=cells.size
        override fun add(element: MutableMap.MutableEntry<String,String>): Boolean=error("Migration entries are assigned by exact name")
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String,String>> {
            val keys=cells.keys.iterator()
            return object:MutableIterator<MutableMap.MutableEntry<String,String>> {
                override fun hasNext()=keys.hasNext()
                override fun remove()=keys.remove()
                override fun next(): MutableMap.MutableEntry<String,String> {
                    val name=keys.next()
                    return object:MutableMap.MutableEntry<String,String> {
                        override val key=name
                        override val value get()=getValue(name)
                        override fun setValue(newValue: String)=requireNotNull(put(name,newValue))
                    }
                }
            }
        }
    }
    companion object {
        fun copy(source: Map<String,String>,persist: ((String,String)->NativeMigrationBlob)?=null): NativeMigrationTextMap {
            val cells=if(source is NativeMigrationTextMap)source.frames()else source.mapValues {NativeMigrationTextFrames.retained(it.value)}
            return NativeMigrationTextMap(LinkedHashMap(cells),persist)
        }
    }
}

/** Parsed graph values are not an aggregate JSON object cache. Callers retain only their bounded summaries. */
internal class NativeMigrationValueMap<T>(private val names: Set<String>,private val load: (String)->T): AbstractMap<String,T>() {
    override val size get()=names.size
    override val keys get()=names
    override fun containsKey(key: String)=key in names
    override fun get(key: String)=if(key in names)load(key)else null
    override val entries: Set<Map.Entry<String,T>> get()=object:AbstractSet<Map.Entry<String,T>>() {
        override val size get()=names.size
        override fun iterator(): Iterator<Map.Entry<String,T>> =names.iterator().let {keys->object:Iterator<Map.Entry<String,T>> {
            override fun hasNext()=keys.hasNext()
            override fun next(): Map.Entry<String,T> {val key=keys.next();return SimpleImmutableEntry(key,load(key))}
        }}
    }
}
