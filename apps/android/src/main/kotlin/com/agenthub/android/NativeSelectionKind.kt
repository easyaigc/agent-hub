package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** Android-only chooser differences. Local transactions and original ownership have one coordinator. */
internal enum class NativeSelectionKind(val registryName: String,val maximum: Int,val tooMany: String) {
    DOCUMENT("document",10,"单条消息最多添加 10 个附件。"),
    PHOTO("photo",NativePhotoIntake.MAXIMUM_PHOTOS,"一次最多选择 5 张图片。"),
    CAMERA("camera",1,"一次只能添加一张拍照结果。");

    suspend fun read(resolver: android.content.ContentResolver,uri: Uri)=when(this) {
        DOCUMENT -> NativeFileIntake.read(resolver,uri)
        PHOTO,CAMERA -> NativePhotoIntake.read(resolver,uri)
    }
    fun contract(output: Uri?=null): ActivityResultContract<Unit,List<Uri>> {
        val documents=ActivityResultContracts.OpenMultipleDocuments()
        val photos=ActivityResultContracts.PickMultipleVisualMedia(NativePhotoIntake.MAXIMUM_PHOTOS)
        val camera=NativeCameraContract()
        return object: ActivityResultContract<Unit,List<Uri>>() {
            override fun createIntent(context: Context,input: Unit): Intent=when(this@NativeSelectionKind) {
                DOCUMENT -> documents.createIntent(context,arrayOf("*/*"))
                PHOTO -> photos.createIntent(context,PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .setMaxItems(NativePhotoIntake.MAXIMUM_PHOTOS).setOrderedSelection(true).build())
                CAMERA -> camera.createIntent(context,checkNotNull(output))
            }
            override fun parseResult(resultCode: Int,intent: Intent?)=when(this@NativeSelectionKind) {
                DOCUMENT -> documents.parseResult(resultCode,intent)
                PHOTO -> photos.parseResult(resultCode,intent)
                CAMERA -> if(camera.parseResult(resultCode,intent))listOf(checkNotNull(output))else emptyList()
            }
        }
    }
}

internal interface NativeSelectionOwner {
    val pending: NativePhotoRequest?
    fun retireBeforeLaunch(original: NativePhotoRequest): Boolean
    fun restoreWait(record: NativeDocumentRecord)
    fun finish(id: String)
    fun selectionLaunchFailed(original: NativePhotoRequest,text: NativeL10n)
}
