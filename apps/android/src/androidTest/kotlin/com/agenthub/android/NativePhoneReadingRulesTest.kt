package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

/** Same production geometry and version resolution used by AgentHubApp; executable on a host JVM. */
class NativePhoneReadingRulesTest {
    private fun position(n: Long,text: String="body-$n") = ThreadReadPosition(n,"message-$n",ThreadReadPosition.contentVersion("message-$n",text,"completed"))
    @Test fun markerMustBeInsideTheActualViewport() {
        assertTrue(NativePhoneReadingRules.visible(0f,100f,300f,500f,0f,498f,300f,499f))
        assertFalse(NativePhoneReadingRules.visible(0f,100f,300f,500f,0f,501f,300f,502f))
        assertFalse(NativePhoneReadingRules.visible(0f,100f,300f,500f,0f,99f,300f,101f))
        assertFalse(NativePhoneReadingRules.visible(0f,100f,300f,500f,0f,500f,300f,500f))
        assertFalse(NativePhoneReadingRules.visible(0f,100f,300f,100f,0f,100f,300f,101f))
        assertFalse(NativePhoneReadingRules.visible(0f,100f,300f,500f,Float.NaN,498f,300f,499f))
    }
    @Test fun authoritativeLatestNeverFallsBackToAnOlderMatchingBody() {
        assertEquals(position(3),NativePhoneReadingRules.latest(listOf(position(1),position(3),position(2))))
        assertNull(NativePhoneReadingRules.latest(listOf(position(3),position(3,"conflict"),position(2))))
        assertEquals(position(3),NativePhoneReadingRules.latest(listOf(position(3),position(3),null)))
        assertNull(NativePhoneReadingRules.latest(listOf(null,null)))
    }
}
