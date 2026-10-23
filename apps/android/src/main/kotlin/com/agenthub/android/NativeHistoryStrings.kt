package com.agenthub.android

import java.lang.ref.WeakReference

/** Reuse equal large immutable text across full snapshots; never retain it after its owners release it. */
internal object NativeHistoryStrings {
    private val values=linkedMapOf<Int,MutableList<WeakReference<String>>>()
    @Synchronized fun share(value: String): String {
        if(value.length<1024) return value
        val hash=value.hashCode(); val bucket=values.getOrPut(hash) { mutableListOf() }
        val iterator=bucket.iterator()
        while(iterator.hasNext()) {
            val existing=iterator.next().get()
            if(existing==null) iterator.remove() else if(existing==value) return existing
        }
        bucket.add(WeakReference(value))
        if(values.size>8192) {
            values.entries.removeAll { (_,items) -> items.removeAll { it.get()==null };items.isEmpty() }
            while(values.size>8192) values.remove(values.keys.first())
        }
        return value
    }
}
