package com.agenthub.android

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import org.junit.Assert.*
import org.junit.Test

/** Real Intent/Bundle decoding only. No Activity, storage, URI read/grant, Provider or command. */
class MobileExternalIntentContractTest {
    private val clip=Uri.parse("content://synthetic.external/clip-original")
    private val original=Uri.parse("content://synthetic.external/stream-original")
    private fun intent(action: String)=Intent(action).apply {clipData=ClipData.newRawUri("synthetic",clip)}
    private fun rejects(value: Intent) {
        assertTrue(value.hasExtra(Intent.EXTRA_STREAM))
        val clipBefore=value.clipData
        val failure=runCatching {NativeFileIntake.sharedFiles(value)}.exceptionOrNull()
        assertTrue("A malformed original stream must not select ClipData",failure is NativeFileIntakeFailure)
        assertEquals(NativeFileIntakeFailure.CHANGED,(failure as NativeFileIntakeFailure).key)
        assertSame(clipBefore,value.clipData);assertTrue(value.hasExtra(Intent.EXTRA_STREAM))
    }
    @Test fun clipFallbackRequiresAbsentStreamAndValidExplicitStreamsKeepOriginalOrder() {
        for(action in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {
            val fallback=intent(action);assertFalse(fallback.hasExtra(Intent.EXTRA_STREAM))
            assertEquals(listOf(clip),NativeFileIntake.sharedFiles(fallback))
        }
        assertEquals(listOf(original),NativeFileIntake.sharedFiles(intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,original)))
        assertEquals(listOf(original,original),NativeFileIntake.sharedFiles(intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf(original,original))))
    }
    @Test fun explicitNullStreamKeepsClipOnlyShareCompatibility() {
        for(action in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {
            val value=intent(action).putExtra(Intent.EXTRA_STREAM,null as Parcelable?)
            assertTrue(value.hasExtra(Intent.EXTRA_STREAM));assertEquals(listOf(clip),NativeFileIntake.sharedFiles(value))
        }
    }
    @Test fun incompatibleStreamTypesCannotBeReplacedWithAnotherClipDataUri() {
        for(action in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {
            rejects(intent(action).putExtra(Intent.EXTRA_STREAM,"synthetic-not-a-stream"))
            rejects(intent(action).putExtra(Intent.EXTRA_STREAM,17))
            rejects(intent(action).putExtra(Intent.EXTRA_STREAM,Intent(Intent.ACTION_VIEW,original)))
        }
    }
    @Test fun nullStreamsPreserveEveryClipItemInOrderForBothSendActions() {
        val second=Uri.parse("content://synthetic.external/second")
        for(action in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {
            val value=intent(action).putExtra(Intent.EXTRA_STREAM,null as Parcelable?)
            value.clipData!!.addItem(ClipData.Item(second))
            assertEquals(listOf(clip,second),NativeFileIntake.sharedFiles(value))
            assertTrue(value.hasExtra(Intent.EXTRA_STREAM));assertEquals(2,value.clipData!!.itemCount)
        }
    }
    @Test fun wrongContainerAndMixedOrNullMultipleItemsNeverFallBackToClip() {
        rejects(intent(Intent.ACTION_SEND).putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf(original)))
        rejects(intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM,original))
        rejects(intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,
            arrayListOf<Parcelable>(original,Intent(Intent.ACTION_VIEW,original))))
        rejects(intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,
            arrayListOf<Parcelable?>(original,null)))
        rejects(intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf<Parcelable>()))
    }
    @Test fun validExplicitMultipleStreamWinsOverDifferentClipAndDoesNotMutateInput() {
        val second=Uri.parse("content://synthetic.external/second")
        val value=intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf(second,original))
        assertEquals(listOf(second,original),NativeFileIntake.sharedFiles(value))
        assertEquals(clip,value.clipData!!.getItemAt(0).uri)
        assertEquals(listOf(second,original),NativeFileIntake.sharedFiles(value))
    }

}
