package com.agenthub.android

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*

/** Activity recreation retains the original file request alongside the Session. */
internal class NativeDocumentPicker(context: Context,private val session: MobileSession,storage: NativeSessionStorage?=null): NativeSelectionOwner {
    private val resolver=context.applicationContext.contentResolver
    override var pending by mutableStateOf<NativePhotoRequest?>(null)
        private set
    internal val recovery=storage?.let { NativeDocumentRecovery(context,session,it,this) }
    fun begin(original: NativePhotoRequest): Boolean {
        if(recovery?.canBegin==false||pending!=null||session.attachmentRecoveryBusy||session.photoPicker?.pending!=null||session.cameraCapture?.pending!=null||!session.acceptsAttachmentAction(original))return false
        pending=original;return true
    }
    fun complete(original: NativePhotoRequest,uris: List<Uri>,text: NativeL10n) {
        if(pending!==original)return
        if(original.launched&&recovery!=null) { recovery.receive(original.id,uris);return }
        pending=null
        if(uris.isEmpty())return
        val sources=uris.map { Uri.parse(it.toString()) }.distinct()
        session.importAttachments(original.destination,sources.map { uri -> {
            try { NativeFileIntake.read(resolver,uri) }
            catch(failure: NativeFileIntakeFailure) { throw IllegalStateException(text.tr(failure.key),failure) }
        } },errorAtStart=original.errorGeneration)
    }
    override fun selectionLaunchFailed(original: NativePhotoRequest,text: NativeL10n)=launchFailed(original,text)
    fun launchFailed(original: NativePhotoRequest,text: NativeL10n) {
        if(pending!==original)return
        pending=null;session.photoSelectionFailure(original,text.tr(NativeFileIntakeFailure.UNAVAILABLE))
    }
    override fun retireBeforeLaunch(original: NativePhotoRequest): Boolean {
        if(pending!==original)return true
        if(session.acceptsAttachmentAction(original))return false
        pending=null;return true
    }
    override fun restoreWait(record: NativeDocumentRecord) {
        if(pending==null)pending=NativePhotoRequest(record.destination,Long.MIN_VALUE,false,record.id).also { it.launched=true }
    }
    override fun finish(id: String) { if(pending?.id==id)pending=null }
}

@Composable internal fun NativeDocumentPickerResult(picker: NativeDocumentPicker?) {
    if(picker?.recovery!=null) { NativeDocumentRecoveryResult(picker,picker.recovery);return }
    val original=picker?.pending ?: return
    val text=currentProductText();val focused=androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val dismissed=original.menuDismissed
    key(original.id) {
        val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picker.complete(original,it,text) }
        LaunchedEffect(original.id,focused,dismissed) {
            if(original.launched||!focused||!dismissed)return@LaunchedEffect
            withFrameNanos { }
            if(picker.retireBeforeLaunch(original))return@LaunchedEffect
            original.launched=true
            try { launcher.launch(arrayOf("*/*")) }catch(failure: Exception) { picker.launchFailed(original,text) }
        }
    }
}
