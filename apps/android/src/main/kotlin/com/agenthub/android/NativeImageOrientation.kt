package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.Matrix

/** Android bitmap transforms only; shared by draft thumbnails and resource preview. */
internal object NativeImageOrientation {
    fun apply(image: Bitmap,orientation: Int): Bitmap {
        val matrix=Matrix()
        when(orientation) {
            2->matrix.setScale(-1f,1f)
            3->matrix.setRotate(180f)
            4->matrix.setScale(1f,-1f)
            5->{ matrix.setRotate(90f);matrix.postScale(-1f,1f) }
            6->matrix.setRotate(90f)
            7->{ matrix.setRotate(-90f);matrix.postScale(-1f,1f) }
            8->matrix.setRotate(-90f)
            else->return image
        }
        return Bitmap.createBitmap(image,0,0,image.width,image.height,matrix,true)
    }
}
