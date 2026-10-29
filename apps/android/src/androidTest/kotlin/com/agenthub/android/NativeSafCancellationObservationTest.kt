package com.agenthub.android

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSafCancellationObservationTest {
    private val app="owned.app"
    private val docs="system.docs"
    private fun next(owner: String?,caller: NativeSafCancellationObservation.Caller=NativeSafCancellationObservation.Caller.ORIGINAL,count: Int=0)=
        NativeSafCancellationObservation.next(owner,app,docs,caller,count)
    @Test fun onlyTheExactDocsOwnerAndOriginalCallerCanReceiveAtMostThreeBacks() {
        for(count in 0..2)assertEquals(NativeSafCancellationObservation.Next.BACK,next(docs,count=count))
        assertEquals(NativeSafCancellationObservation.Next.WAIT,next(docs,count=3))
    }
    @Test fun returningToTheAppStopsInputWhileTheRealCallbackIsPending() {
        assertEquals(NativeSafCancellationObservation.Next.WAIT,next(app))
        assertEquals(NativeSafCancellationObservation.Next.COMPLETE,next(app,NativeSafCancellationObservation.Caller.CANCELLED))
        assertEquals(NativeSafCancellationObservation.Next.WAIT,NativeSafCancellationObservation.next(docs,app,docs,NativeSafCancellationObservation.Caller.ORIGINAL,1,returnedToApp=true))
    }
    @Test fun retiredCallbackAndTemporarilyMissingWindowNeverAuthorizeAnotherBack() {
        assertEquals(NativeSafCancellationObservation.Next.WAIT,next(docs,NativeSafCancellationObservation.Caller.CANCELLED))
        assertEquals(NativeSafCancellationObservation.Next.WAIT,next(null))
    }
    @Test fun thirdOwnerStopsEvenWhenTheOriginalCallbackHasRetired() {
        for(caller in NativeSafCancellationObservation.Caller.entries)
            assertEquals(NativeSafCancellationObservation.Next.STOP_OWNER,next("third.app",caller))
    }
    @Test fun unknownOrChangedCallerStopsEvenWhenDocsUiStillOwnsTheWindow() {
        for(owner in listOf(docs,app,null))
            assertEquals(NativeSafCancellationObservation.Next.STOP_CALLER,next(owner,NativeSafCancellationObservation.Caller.UNKNOWN))
    }
}
