package com.agenthub.android

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** R3 action wiring only: no files created, real chooser, Host, device data or model. */
@RunWith(AndroidJUnit4::class)
class R3ReceivedFileActionsTest {
    @get:Rule val ui=createComposeRule()
    private fun resource(name:String="r3-owned.bin")=MobileSession.PreparedMessageResource(name,"application/octet-stream",File("/synthetic-r3/resources/$name"))
    @Test fun forwardsExactPreparedResourceShareTitleAndCopyPath() {
        val file=resource();val state=NativeReceivedFileActionState(file){true}
        val calls=mutableListOf<Boolean>()
        for(share in listOf(false,true))state.external(share,"localized-share-title") {actual,sharing,title->
            assertSame(file,actual);assertEquals(share,sharing);assertEquals("localized-share-title",title);calls.add(sharing)
        }
        var path:String?=null;state.copyPath {path=it}
        assertEquals(listOf(false,true),calls);assertEquals(file.local.path,path);assertFalse(state.failed)
    }
    @Test fun currentAndRetirementGuardLaunchCopyAndLateFailure() {
        var current=true;val state=NativeReceivedFileActionState(resource()){current}
        state.external(false,"title"){_,_,_->current=false;throw ActivityNotFoundException()}
        assertFalse(state.failed)
        state.external(true,"title"){_,_,_->fail("stale launch")};state.copyPath {fail("stale copy")}
        current=true
        state.external(false,"title"){_,_,_->state.retire();throw SecurityException()}
        assertFalse(state.failed)
        state.external(false,"title"){_,_,_->fail("retired launch")};state.copyPath {fail("retired copy")}
    }
    @Test fun expectedOpenFailuresAreVisibleAndNextSuccessClearsOnlyThisRow() {
        val first=NativeReceivedFileActionState(resource()){true};val other=NativeReceivedFileActionState(resource("r3-other.bin")){true}
        for(error in listOf(ActivityNotFoundException(),SecurityException(),IllegalArgumentException())) {
            first.external(false,"title"){_,_,_->throw error};assertTrue(first.failed);assertFalse(other.failed)
            first.external(true,"title"){_,_,_->};assertFalse(first.failed)
        }
        try {first.external(false,"title"){_,_,_->throw IOException("unexpected synthetic failure")};fail("unexpected exception swallowed")}
        catch(expected:IOException) {}
    }
    @Test fun rowShowsFailureAndDoesNotLeakItAcrossCloseOrTargetChange() {
        var shown by mutableStateOf(true);var current by mutableStateOf(true)
        val file=resource();val calls=mutableListOf<Boolean>()
        ui.setContent {MaterialTheme {if(shown)Column {
            NativeReceivedFileActions(file,"r3-first",{current}) {actual,share,_->
                assertSame(file,actual);calls.add(share);throw ActivityNotFoundException()
            }
            NativeReceivedFileActions(resource("r3-other.bin"),"r3-other",{current}) {_,_,_->fail("other row launch")}
        }}}
        ui.onNodeWithTag("open-received-r3-first").performClick()
        ui.onNodeWithTag("received-file-open-error-r3-first").assertIsDisplayed()
        ui.onNodeWithTag("received-file-open-error-r3-other").assertDoesNotExist()
        ui.runOnIdle {current=false}
        ui.onNodeWithTag("received-file-open-error-r3-first").assertDoesNotExist()
        ui.onNodeWithTag("copy-received-r3-first").assertIsNotEnabled()
        ui.runOnIdle {current=true}
        ui.onNodeWithTag("received-file-open-error-r3-first").assertDoesNotExist()
        ui.onNodeWithTag("open-received-r3-first").assertIsNotEnabled()
        ui.runOnIdle {shown=false};ui.waitForIdle()
        ui.runOnIdle {shown=true;current=true}
        ui.onNodeWithTag("received-file-open-error-r3-first").assertDoesNotExist()
        ui.onNodeWithTag("share-received-r3-first").performClick()
        ui.onNodeWithTag("received-file-open-error-r3-first").assertIsDisplayed()
        ui.runOnIdle {assertEquals(listOf(false,true),calls)}
    }
}
