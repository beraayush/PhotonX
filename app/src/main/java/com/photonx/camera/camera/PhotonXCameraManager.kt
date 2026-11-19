package com.photonx.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.photonx.camera.data.AppLogger
import com.photonx.camera.domain.CameraMode
import com.photonx.camera.domain.CapturedFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

class PhotonXCameraManager(
    private val context: Context,
    private val logger: AppLogger
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var activeExtensionMode: Int? = null
    private var selected4By3Size: Size? = null
    private var aeStepEv: Float = 1f / 3f
    private var aeRange: android.util.Range<Int>? = null

    fun isFrontCamera(): Boolean = lensFacing == CameraSelector.LENS_FACING_FRONT

    fun getSelectedResolutionString(): String {
        val s = selected4By3Size ?: return "Native 4:3"
        return "${s.width}x${s.height}"
    }

    fun isUsingVivoExtension(): Boolean = activeExtensionMode != null

    @SuppressLint("UnsafeOptInUsageError")
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        mode: CameraMode = CameraMode.AUTO,
        useVivoExtensions: Boolean = false,
        onReady: () -> Unit = {}
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val targetSize = queryLargest4By3JpegSize(lensFacing)
                selected4By3Size = targetSize
                queryAeParameters(lensFacing)

                val baseSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                var finalSelector = baseSelector
                activeExtensionMode = null

                if (useVivoExtensions) {
                    val extMgr = try {
                        ExtensionsManager.getInstanceAsync(context, provider).get()
                    } catch (_: Exception) {
                        null
                    }
                    extensionsManager = extMgr

                    val desiredExt = mapModeToExtension(mode)
                    if (extMgr != null && desiredExt != null) {
                        val available = try {
                            extMgr.isExtensionAvailable(baseSelector, desiredExt)
                        } catch (_: Exception) {
                            false
                        }
                        if (available) {
                            finalSelector = extMgr.getExtensionEnabledCameraSelector(baseSelector, desiredExt)
                            activeExtensionMode = desiredExt
                            logger.info("Camera", "Vivo Extension enabled for ${mode.name} (lens=${lensFacing})")
                        } else {
                            logger.info("Camera", "Vivo Extension unavailable for ${mode.name}; using PhotonX pipeline")
                        }
                    }
                }

                val resolutionSelector = build4By3ResolutionSelector(targetSize)

                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)

                val captureBuilder = ImageCapture.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)

                val interopExtender = Camera2Interop.Extender(captureBuilder)
                interopExtender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )

                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = captureBuilder.build()
                imageCapture = capture

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    finalSelector,
                    preview,
                    capture
                )
                logger.info(
                    "Camera",
                    "Bound camera (lens=${if (lensFacing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT"}, 4:3=${targetSize?.width ?: 0}x${targetSize?.height ?: 0}, ext=${activeExtensionMode != null})"
                )
                onReady()
            } catch (e: Exception) {
                logger.error("Camera", "bindCamera failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        mode: CameraMode,
        useVivoExtensions: Boolean
    ) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCamera(lifecycleOwner, previewView, mode, useVivoExtensions)
    }

    fun setZoomRatio(ratio: Float) {
        try {
            val zoomState = camera?.cameraInfo?.zoomState?.value
            val clamped = if (zoomState != null) {
                ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            } else {
                ratio
            }
            camera?.cameraControl?.setZoomRatio(clamped)
        } catch (e: Exception) {
            logger.error("Camera", "setZoomRatio($ratio) failed", e)
        }
    }

    fun setExposureIndex(index: Int) {
        try {
            val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
            val clamped = if (range != null) index.coerceIn(range.lower, range.upper) else index
            camera?.cameraControl?.setExposureCompensationIndex(clamped)
        } catch (e: Exception) {
            logger.error("Camera", "setExposureIndex($index) failed", e)
        }
    }

    fun tapToFocus(previewView: PreviewView, x: Float, y: Float) {
        try {
            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
            ).build()
            camera?.cameraControl?.startFocusAndMetering(action)
        } catch (e: Exception) {
            logger.error("Camera", "tapToFocus failed", e)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    suspend fun captureBurst(mode: CameraMode, isFront: Boolean): List<CapturedFrame> =
        withContext(Dispatchers.IO) {
            val startEvIndex = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0

            if (mode == CameraMode.AUTO || activeExtensionMode != null) {
                val frame = captureSingleFrame(
                    evStopOffset = 0f,
                    decodeBitmap = (mode == CameraMode.PORTRAIT || activeExtensionMode != null)
                )
                return@withContext listOf(frame)
            }

            if (mode == CameraMode.PORTRAIT) {
                val frame = captureSingleFrame(evStopOffset = 0f, decodeBitmap = true)
                return@withContext listOf(frame)
            }

            val desiredStops: List<Float> = when (mode) {
                CameraMode.HDR -> {
                    if (isFront) listOf(-1.0f, 0.0f, +1.0f)
                    else listOf(-1.5f, 0.0f, +1.2f, +2.0f)
                }
                CameraMode.NIGHT -> {
                    val count = if (isFront) 4 else 6
                    List(count) { 0.0f }
                }
                else -> listOf(0.0f)
            }

            val results = mutableListOf<CapturedFrame>()
            try {
                for (stop in desiredStops) {
                    val targetIndex = computeAeIndexForStop(startEvIndex, stop)
                    if (aeRange != null && targetIndex in aeRange!!.lower..aeRange!!.upper) {
                        try {
                            camera?.cameraControl?.setExposureCompensationIndex(targetIndex)?.await()
                            if (abs(stop) > 0.01f) delay(45L)
                        } catch (_: Exception) {}
                    }
                    val frame = captureSingleFrame(evStopOffset = stop, decodeBitmap = true)
                    results.add(frame)
                }
            } finally {
                try {
                    camera?.cameraControl?.setExposureCompensationIndex(startEvIndex)?.await()
                } catch (_: Exception) {}
            }

            if (results.isEmpty()) {
                throw IllegalStateException("Burst capture returned 0 frames")
            }
            logger.info("Burst", "Captured ${results.size} frames for mode=${mode.name}")
            results
        }

    private fun computeAeIndexForStop(baseIndex: Int, stopDelta: Float): Int {
        val safeStep = if (aeStepEv > 0.001f) aeStepEv else (1f / 3f)
        val deltaSteps = (stopDelta / safeStep).roundToInt()
        val rawTarget = baseIndex + deltaSteps
        val range = aeRange ?: return rawTarget
        return rawTarget.coerceIn(range.lower, range.upper)
    }

    private suspend fun captureSingleFrame(evStopOffset: Float, decodeBitmap: Boolean): CapturedFrame =
        suspendCancellableCoroutine { cont ->
            val capture = imageCapture ?: run {
                cont.resumeWithException(IllegalStateException("ImageCapture not initialized"))
                return@suspendCancellableCoroutine
            }

            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val rotation = image.imageInfo.rotationDegrees
                            val bytes = imageProxyToJpegBytes(image)
                            val bmp = if (decodeBitmap) {
                                decodeAndRotateBitmap(bytes, rotation)
                            } else {
                                null
                            }
                            image.close()
                            cont.resume(
                                CapturedFrame(
                                    bitmap = bmp,
                                    jpegBytes = bytes,
                                    rotationDegrees = rotation,
                                    evStopOffset = evStopOffset
                                )
                            )
                        } catch (e: Exception) {
                            image.close()
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                }
            )
        }

    private fun imageProxyToJpegBytes(image: ImageProxy): ByteArray {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun decodeAndRotateBitmap(jpegBytes: ByteArray, rotationDegrees: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: throw IllegalStateException("Failed to decode captured JPEG frame")

        val normRot = ((rotationDegrees % 360) + 360) % 360
        return if (normRot != 0) {
            val matrix = Matrix().apply { postRotate(normRot.toFloat()) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated != decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }
    }

    private fun queryLargest4By3JpegSize(facing: Int): Size? {
        return try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (facing == CameraSelector.LENS_FACING_FRONT) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val camId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: return null

            val chars = mgr.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: return null

            jpegSizes
                .filter { sz ->
                    if (sz.width <= 0 || sz.height <= 0) false
                    else {
                        val ratio = maxOf(sz.width, sz.height).toFloat() / minOf(sz.width, sz.height).toFloat()
                        abs(ratio - (4f / 3f)) < 0.04f
                    }
                }
                .maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: jpegSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
        } catch (e: Exception) {
            logger.warn("Camera", "Could not query 4:3 size: ${e.message}")
            null
        }
    }

    private fun queryAeParameters(facing: Int) {
        try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (facing == CameraSelector.LENS_FACING_FRONT) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val camId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: return

            val chars = mgr.getCameraCharacteristics(camId)
            aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            aeStepEv = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f)
        } catch (_: Exception) {}
    }

    private fun build4By3ResolutionSelector(preferredSize: Size?): ResolutionSelector {
        val builder = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        if (preferredSize != null) {
            builder.setResolutionStrategy(
                ResolutionStrategy(
                    preferredSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
        }
        return builder.build()
    }

    private fun mapModeToExtension(mode: CameraMode): Int? = when (mode) {
        CameraMode.AUTO -> ExtensionMode.AUTO
        CameraMode.HDR -> ExtensionMode.HDR
        CameraMode.NIGHT -> ExtensionMode.NIGHT
        CameraMode.PORTRAIT -> ExtensionMode.BOKEH
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
