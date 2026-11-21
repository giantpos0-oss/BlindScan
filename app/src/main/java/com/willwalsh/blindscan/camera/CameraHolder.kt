package com.willwalsh.blindscan.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.willwalsh.blindscan.ocr.ImageTensors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the back camera: on-screen preview, full-resolution still capture,
 * a stream of frames for live reading / guidance, and the light (torch).
 */
class CameraHolder(private val context: Context) {

    val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null

    var torchOn = false
        private set
    val hasLight: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
        )
        .build()

    private val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()

    /**
     * Starts the camera. [onFrame] gets upright frames on a background thread; it is only
     * called again after the previous call returns, so slow work simply skips frames.
     */
    fun start(owner: LifecycleOwner, onFrame: (Bitmap) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            analysis.setAnalyzer(analysisExecutor) { proxy -> handleFrame(proxy, onFrame) }
            p.unbindAll()
            camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            if (torchOn) camera?.cameraControl?.enableTorch(true)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun handleFrame(proxy: ImageProxy, onFrame: (Bitmap) -> Unit) {
        val bmp = try {
            ImageTensors.rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
        } catch (t: Throwable) {
            null
        } finally {
            proxy.close()
        }
        if (bmp != null) {
            try { onFrame(bmp) } catch (_: Throwable) { }
        }
    }

    fun stop() {
        analysis.clearAnalyzer()
        provider?.unbindAll()
        camera = null
    }

    fun setTorch(on: Boolean) {
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
    }

    /** Focuses on the centre of the view (waits up to 1 s), then takes a full-resolution photo. */
    suspend fun capture(): Bitmap {
        focusCentre()
        return suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bmp = try { image.toBitmap() } finally { image.close() }
                    cont.resume(ImageTensors.rotate(bmp, rotation))
                }
                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }
    }

    private suspend fun focusCentre() {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        val future = cam.cameraControl.startFocusAndMetering(action)
        withTimeoutOrNull(1000) {
            suspendCancellableCoroutine<Unit> { cont ->
                future.addListener({ if (cont.isActive) cont.resume(Unit) }, ContextCompat.getMainExecutor(context))
            }
        }
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
    }
}
