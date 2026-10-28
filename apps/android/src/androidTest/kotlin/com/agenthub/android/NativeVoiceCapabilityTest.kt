package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

class NativeVoiceCapabilityTest {
    @Test fun installedModelDoesNotMakeTheGeneralSystemServiceOfflineGuaranteed() {
        val language=NativeVoiceCapability.language("en-US",false,listOf("en-US"),emptyList(),emptyList())
        assertEquals(NativeVoiceCapability.Language.INSTALLED,language)
        assertTrue(NativeVoiceCapability(NativeVoiceCapability.Route.SYSTEM_SERVICE,language,"en-US").mayUseNetwork)
        assertFalse(NativeVoiceCapability(NativeVoiceCapability.Route.ON_DEVICE,language,"en-US").mayUseNetwork)
    }
    @Test fun exactLanguageAndRouteCannotBorrowAnotherRegionOrOnlineFallback() {
        assertEquals(NativeVoiceCapability.Language.UNSUPPORTED,NativeVoiceCapability.language("en-GB",true,listOf("en-US"),emptyList(),listOf("en-GB")))
        assertEquals(NativeVoiceCapability.Language.DOWNLOAD_REQUIRED,NativeVoiceCapability.language("zh-CN",true,emptyList(),listOf("zh-CN"),emptyList()))
        assertEquals(NativeVoiceCapability.Language.ONLINE,NativeVoiceCapability.language("en-GB",false,emptyList(),emptyList(),listOf("en-GB")))
    }
}
