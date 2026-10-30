package com.agenthub.android

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import java.util.UUID

internal class NativeAttachmentRequest(val destination: AttachmentImportDestination,val errorGeneration: Long,waitForMenu: Boolean,val id: String=UUID.randomUUID().toString()) {
    var launched=false
    var menuDismissed by mutableStateOf(!waitForMenu)
        internal set
}

internal typealias NativePhotoRequest=NativeAttachmentRequest

/** One outstanding OS request survives Activity recreation with its retained Session. */
internal class NativePhotoPicker(context: Context,private var session: MobileSession,storage: NativeSessionStorage?=null): NativeSelectionOwner {
    private val resolver=context.applicationContext.contentResolver
    override var pending by mutableStateOf<NativePhotoRequest?>(null)
        private set
    internal val recovery=storage?.let { NativeSelectionRecovery(context,session,it,this,NativeSelectionKind.PHOTO) }
    internal fun rebindAfterMigration(replacement: MobileSession,operation: String) {
        session=replacement;recovery?.rebindAfterMigration(replacement,operation)
    }
    fun begin(presentation: DraftDestination,waitForMenu: Boolean=false): Boolean {
        if(!session.isCurrent(presentation)||!session.canCompose||session.importing||session.localRecoveryRequired)return false
        return begin(NativePhotoRequest(session.attachmentImportDestination(),session.attachmentErrorGeneration,waitForMenu))
    }
    fun begin(original: NativePhotoRequest): Boolean {
        if(recovery?.canBegin==false||pending!=null||session.attachmentRecoveryBusy||session.cameraCapture?.pending!=null||session.documentPicker?.pending!=null||!session.acceptsAttachmentAction(original))return false
        pending=original;return true
    }
    fun menuDidDismiss(presentation: DraftDestination) {
        val original=pending ?: return
        if(original.destination.presentation!=presentation)return
        if(session.isCurrent(presentation))original.menuDismissed=true else pending=null
    }
    fun complete(original: NativePhotoRequest,uris: List<Uri>,text: NativeL10n) {
        if(pending!==original)return
        if(original.launched&&recovery!=null) { recovery.receive(original.id,uris);return }
        pending=null
        if(uris.isEmpty())return
        val sources=uris.map { Uri.parse(it.toString()) }.distinct()
        if(sources.size>NativePhotoIntake.MAXIMUM_PHOTOS) { fail(original,text.tr("一次最多选择 5 张图片。"));return }
        session.importAttachments(original.destination,sources.map { uri -> {
            try { NativePhotoIntake.read(resolver,uri) }
            catch(error: NativeFileIntakeFailure) { throw IllegalStateException(text.tr(error.key),error) }
        } },errorAtStart=original.errorGeneration)
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
    override fun selectionLaunchFailed(original: NativePhotoRequest,text: NativeL10n)=launchFailed(original,text)
    internal var lastLaunchFailure: Exception?=null
        private set
    fun launchFailed(original: NativePhotoRequest,text: NativeL10n,cause: Exception?=null) {
        if(pending!==original)return
        lastLaunchFailure=cause;pending=null;fail(original,text.tr("无法读取所选照片"))
    }
    private fun fail(original: NativePhotoRequest,message: String) {
        session.photoSelectionFailure(original,message)
    }
}

@Composable internal fun NativePhotoPickerResult(picker: NativePhotoPicker?) {
    if(picker?.recovery!=null) { NativeDocumentRecoveryResult(picker,picker.recovery);return }
    val original=picker?.pending ?: return
    val text=currentProductText()
    val focused=androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val menuDismissed=original.menuDismissed
    key(original.id) {
        val launcher=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(NativePhotoIntake.MAXIMUM_PHOTOS)) { uris -> picker.complete(original,uris,text) }
        LaunchedEffect(original.id,focused,menuDismissed) {
            if(!original.launched&&focused&&menuDismissed) {
                withFrameNanos { }
                if(picker.retireBeforeLaunch(original))return@LaunchedEffect
                original.launched=true
                try { launcher.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).setMaxItems(NativePhotoIntake.MAXIMUM_PHOTOS).setOrderedSelection(true).build()) }
                catch(error: Exception) { picker.launchFailed(original,text,error) }
            }
        }
    }
}
