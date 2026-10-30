package com.agenthub.android

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import java.io.File

internal interface NativeCameraAccess { fun granted(): Boolean;fun available(): Boolean }
internal class AndroidCameraAccess(context: Context): NativeCameraAccess {
    private val context=context.applicationContext
    override fun granted()=context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    override fun available()=context.packageManager.resolveActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE),PackageManager.MATCH_DEFAULT_ONLY)!=null
}

internal class NativeCameraRequest(val photo: NativePhotoRequest) {
    val id get()=photo.id
    var permissionRequested=false
    var permissionGranted by mutableStateOf<Boolean?>(null)
    var launched=false
    var output: NativeCameraOutput?=null
        internal set
}
internal class NativeCameraOutput internal constructor(val uri: Uri,internal val file: File)

/** Only the captured output URI is readable/writable by the external camera. */
internal class NativeCameraContract: ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context,input: Uri): Intent = super.createIntent(context,input).apply {
        clipData=ClipData.newRawUri(null,input)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}

/** A camera result only prepares the original encrypted draft; it never uploads or sends. */
internal class NativeCameraCapture(context: Context,private var session: MobileSession,storage: NativeSessionStorage?=null,access: NativeCameraAccess=AndroidCameraAccess(context)) {
    private val context=context.applicationContext
    private val directory=File(this.context.cacheDir,"camera-capture")
    var pending by mutableStateOf<NativeCameraRequest?>(null)
        private set
    internal val selectionOwner: NativeSelectionOwner=object: NativeSelectionOwner {
        override val pending get()=this@NativeCameraCapture.pending?.photo
        override fun retireBeforeLaunch(original: NativePhotoRequest): Boolean {
            if(this@NativeCameraCapture.pending?.photo!==original)return true
            if(session.acceptsAttachmentAction(original))return false
            this@NativeCameraCapture.pending=null;return true
        }
        override fun restoreWait(record: NativeDocumentRecord) {
            if(this@NativeCameraCapture.pending!=null)return
            val photo=NativePhotoRequest(record.destination,Long.MIN_VALUE,false,record.id).also { it.launched=true }
            this@NativeCameraCapture.pending=NativeCameraRequest(photo).also {
                it.permissionRequested=record.phase=="permission";it.launched=record.phase=="launched"
                if(it.launched&&storage!=null)it.output=NativeCameraOutputs(context,storage.child(context,"outputs")).output(record.id)
            }
        }
        override fun finish(id: String) { if(this@NativeCameraCapture.pending?.id==id)this@NativeCameraCapture.pending=null }
        override fun selectionLaunchFailed(original: NativePhotoRequest,text: NativeL10n) {
            if(this@NativeCameraCapture.pending?.photo===original)this@NativeCameraCapture.pending=null
            session.photoSelectionFailure(original,text.tr("当前设备无法使用相机。"))
        }
    }
    internal val recovery: NativeSelectionRecovery?=storage?.let { NativeSelectionRecovery(context,session,it,selectionOwner,NativeSelectionKind.CAMERA,
        permissionRequested={ id -> pending?.takeIf { it.id==id }?.permissionRequested=true },
        outputPrepared={ id,output -> pending?.takeIf { it.id==id }?.let { it.output=output;it.launched=true } },cameraAccess=access) }
    internal fun rebindAfterMigration(replacement: MobileSession,operation: String) {
        session=replacement;recovery?.rebindAfterMigration(replacement,operation)
    }
    fun begin(presentation: DraftDestination,waitForMenu: Boolean=false): Boolean {
        if(!session.isCurrent(presentation)||!session.canCompose||session.importing||session.localRecoveryRequired)return false
        return begin(NativePhotoRequest(session.attachmentImportDestination(),session.attachmentErrorGeneration,waitForMenu))
    }
    fun begin(original: NativePhotoRequest): Boolean {
        if(recovery?.canBegin==false||pending!=null||session.attachmentRecoveryBusy||session.photoPicker?.pending!=null||session.documentPicker?.pending!=null||!session.acceptsAttachmentAction(original))return false
        pending=NativeCameraRequest(original);return true
    }
    fun menuDidDismiss(presentation: DraftDestination) {
        val original=pending ?: return
        if(original.photo.destination.presentation!=presentation)return
        if(session.isCurrent(presentation))original.photo.menuDismissed=true else retire(original)
    }
    internal fun retireBeforeLaunch(original: NativeCameraRequest): Boolean {
        if(pending!==original)return true
        if(session.acceptsAttachmentAction(original.photo))return false
        retire(original);return true
    }
    fun permissionResult(original: NativeCameraRequest,granted: Boolean,text: NativeL10n) {
        if(pending!==original)return
        if(recovery!=null) { recovery.receivePermission(original.id,granted);return }
        if(!granted)fail(original,text.tr(PERMISSION)) else original.permissionGranted=true
    }
    fun prepareOutput(original: NativeCameraRequest): NativeCameraOutput? {
        if(recovery!=null)return null
        if(retireBeforeLaunch(original)||original.output!=null)return null
        check(directory.isDirectory||directory.mkdirs())
        val file=File(directory,"photo-${original.id}.jpg")
        check(file.createNewFile()) { "Camera output must be unique" }
        try {
            val output=NativeCameraOutput(FileProvider.getUriForFile(context,context.packageName+".resources",file),file)
            original.output=output;return output
        }catch(failure: Exception) { file.delete();throw failure }
    }
    fun complete(original: NativeCameraRequest,success: Boolean,text: NativeL10n) {
        if(pending!==original)return
        if(recovery!=null) {
            if(original.photo.launched)recovery.receive(original.id,if(success)listOf(checkNotNull(original.output).uri)else emptyList())
            else { pending=null;recovery.retire(original.id) }
            return
        }
        val output=original.output;pending=null
        if(!success) { output?.let(::cleanup);return }
        if(output==null) { session.photoSelectionFailure(original.photo,text.tr("无法读取所选照片"));return }
        revoke(output)
        val job=session.importAttachments(original.photo.destination,listOf({
            try { NativePhotoIntake.read(context.contentResolver,output.uri) }
            catch(failure: NativeFileIntakeFailure) { throw IllegalStateException(text.tr(failure.key),failure) }
        }),errorAtStart=original.photo.errorGeneration)
        if(job==null)cleanup(output) else job.invokeOnCompletion { cleanup(output) }
    }
    fun launchFailed(original: NativeCameraRequest,text: NativeL10n,failure: Exception?=null) {
        if(pending!==original)return
        fail(original,text.tr(if(failure is SecurityException)PERMISSION else "当前设备无法使用相机。"))
    }
    private fun fail(original: NativeCameraRequest,message: String) {
        if(pending!==original)return
        retire(original);session.photoSelectionFailure(original.photo,message)
    }
    private fun retire(original: NativeCameraRequest) {
        if(pending!==original)return
        pending=null;original.output?.let(::cleanup)
    }
    private fun revoke(output: NativeCameraOutput) {
        context.revokeUriPermission(output.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    private fun cleanup(output: NativeCameraOutput) {
        revoke(output)
        // The owned output object is immutable; never follow/delete a supplied URI path.
        if(output.file.parentFile?.canonicalFile==directory.canonicalFile&&output.file.name.matches(Regex("photo-[a-f0-9-]{36}\\.jpg")))output.file.delete()
    }
    companion object { const val PERMISSION="未获得相机权限，照片未添加。" }
}

@Composable internal fun NativeCameraCaptureResult(capture: NativeCameraCapture?) {
    if(capture?.recovery!=null) { NativeDocumentRecoveryResult(capture.selectionOwner,capture.recovery);return }
    val original=capture?.pending ?: return
    val text=currentProductText();val context=androidx.compose.ui.platform.LocalContext.current
    val focused=androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val menuDismissed=original.photo.menuDismissed;val granted=original.permissionGranted
    key(original.id) {
        val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { capture.permissionResult(original,it,text) }
        val camera=rememberLauncherForActivityResult(NativeCameraContract()) { capture.complete(original,it,text) }
        LaunchedEffect(original.id,focused,menuDismissed,granted) {
            if(original.launched||!focused||!menuDismissed)return@LaunchedEffect
            withFrameNanos { }
            if(capture.retireBeforeLaunch(original))return@LaunchedEffect
            try {
                if(context.packageManager.resolveActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE),PackageManager.MATCH_DEFAULT_ONLY)==null) {
                    capture.launchFailed(original,text);return@LaunchedEffect
                }
                if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
                    if(!original.permissionRequested) { original.permissionRequested=true;permission.launch(Manifest.permission.CAMERA) }
                    else if(granted==true)capture.permissionResult(original,false,text)
                    return@LaunchedEffect
                }
                val output=capture.prepareOutput(original) ?: return@LaunchedEffect
                original.launched=true
                camera.launch(output.uri)
            }catch(failure: Exception) { capture.launchFailed(original,text,failure) }
        }
    }
}
