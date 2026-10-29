package com.agenthub.android

/** Host JVM truth-table checks only; no Binder, Parcel, Android service or actual flags observation. */
fun main() {
    var checked=0
    for(api in listOf(-1,0,25,26,27,29,30,31,33,34,35,36,37,38,40)) {
        val admitted=when {api==37 -> setOf(0,16,18);api>=26 -> setOf(0,16);else -> emptySet()}
        for(flags in 0..65535) {
            check(NativeNaturalProbeTransportFlags.admitted(api,flags)==admitted.contains(flags)) {"unexpected metadata decision api=$api flags=$flags"};checked++
        }
        for(flags in listOf(Int.MIN_VALUE,Int.MAX_VALUE,-1,-2,-16,-18,0x10000,0x10010,0x10012,0x40000000)) {
            check(!NativeNaturalProbeTransportFlags.admitted(api,flags));checked++
        }
    }
    check(NativeNaturalProbeTransportFlags.admitted(26,0)&&NativeNaturalProbeTransportFlags.admitted(26,16))
    check(NativeNaturalProbeTransportFlags.admitted(37,18))
    for(api in listOf(26,30,36,38,40))check(!NativeNaturalProbeTransportFlags.admitted(api,18))
    for(flags in listOf(1,2,3,4,8,17,19,20,22,24,26,32,50,64,82,0x112,0x40000012))check(!NativeNaturalProbeTransportFlags.admitted(37,flags))
    println("Natural probe metadata auxiliary: $checked exhaustive/edge decisions passed; no Android/Binder/FD/native proof")
}
