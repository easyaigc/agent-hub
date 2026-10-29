package com.agenthub.android

/** Debug fixture metadata only. Exact observed API37 pair; never a general bit mask or FD grant. */
internal object NativeNaturalProbeTransportFlags {
    fun admitted(api: Int, flags: Int): Boolean = api >= 26 &&
        (flags == 0 || flags == 0x10 || (api == 37 && flags == 0x12))
}
