package com.agenthub.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Shared scanner decoder uses local pixels only; it has no network or logging. */
internal object NativeQRDecoder {
    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        require(width>0 && height>0 && width.toLong()*height<=luminance.size)
        var pixels=luminance; var w=width; var h=height
        repeat(4) { angle ->
            try {
                val source=PlanarYUVLuminanceSource(pixels,w,h,0,0,w,h,false)
                return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)).text
            } catch (_: ReaderException) { /* Dense QR alignment detection can depend on orientation. */ }
            if(angle<3) {
                val rotated=ByteArray(w*h)
                for(y in 0 until h) for(x in 0 until w) rotated[x*h+h-1-y]=pixels[y*w+x]
                pixels=rotated; val previousWidth=w; w=h; h=previousWidth
            }
        }
        return null
    }
}

@Composable internal fun NativeQRScanner(prefix: String, close: () -> Unit, additionalPrefixes: List<String> = emptyList(), scanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    val callback by rememberUpdatedState(scanned)
    val currentPrefix by rememberUpdatedState(prefix)
    val currentAdditional by rememberUpdatedState(additionalPrefixes)
    LaunchedEffect(Unit) { if(!granted) permission.launch(Manifest.permission.CAMERA) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().testTag("qr-scanner")) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp), verticalAlignment=Alignment.CenterVertically) {
                Text("扫描电脑二维码", Modifier.weight(1f))
                TextButton(onClick=close, modifier=Modifier.testTag("qr-scanner-close")) { Text("取消") }
            }
            Text("将电脑上的连接二维码放在画面中。", Modifier.padding(16.dp), color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(granted) {
                val view = remember { PreviewView(context) }
                AndroidView(factory={ view }, modifier=Modifier.weight(1f).fillMaxWidth().testTag("qr-camera-preview"))
                DisposableEffect(lifecycle, view) {
                    val alive = AtomicBoolean(true); val delivered = AtomicBoolean(false)
                    val executor = Executors.newSingleThreadExecutor()
                    val main = ContextCompat.getMainExecutor(context)
                    val future = ProcessCameraProvider.getInstance(context)
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if(alive.get() && !delivered.get()) {
                                val plane = image.planes[0]; val buffer = plane.buffer
                                val pixels = ByteArray(image.width * image.height)
                                val origin = buffer.position()
                                for(y in 0 until image.height) for(x in 0 until image.width) pixels[y * image.width + x] = buffer.get(origin + y * plane.rowStride + x * plane.pixelStride)
                                val result = NativeQRDecoder.decode(pixels, image.width, image.height)
                                if(result != null) main.execute {
                                    if(alive.get() && !delivered.get()) {
                                        if(result.startsWith(currentPrefix)||currentAdditional.any(result::startsWith)) { delivered.set(true); callback(result) }
                                        else failure = "请扫描电脑提供的连接二维码"
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            main.execute { if(alive.get()) failure = "无法读取相机画面，可返回粘贴连接内容" }
                        } finally { image.close() }
                    }
                    future.addListener({
                        if(alive.get()) try { future.get().bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                        catch (_: Exception) { failure = "相机暂不可用，可返回粘贴连接内容" }
                    }, main)
                    onDispose {
                        alive.set(false); analysis.clearAnalyzer()
                        if(future.isDone) try { future.get().unbind(preview, analysis) } catch (_: Exception) {}
                        executor.shutdownNow()
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                if(denied) Text("未获得相机权限，可返回粘贴电脑的连接内容。", Modifier.padding(24.dp).testTag("qr-camera-denied"))
                Spacer(Modifier.weight(1f))
            }
            failure?.let { Text(it, Modifier.padding(16.dp).testTag("qr-scanner-error"), color=MaterialTheme.colorScheme.error) }
        }
    }
}
