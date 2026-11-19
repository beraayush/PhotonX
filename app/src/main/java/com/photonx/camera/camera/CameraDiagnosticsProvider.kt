package com.photonx.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import com.photonx.camera.data.AppLogger
import com.photonx.camera.domain.CameraDiagnosticsReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraDiagnosticsProvider @Inject constructor(
    private val context: Context,
    private val logger: AppLogger
) {

    suspend fun collectDiagnostics(): CameraDiagnosticsReport = withContext(Dispatchers.IO) {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE})"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val summaries = mutableListOf<String>()
        var anyDepthSupported = false

        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val lensFacing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }

                val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }

                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val capNames = caps.map { cap ->
                    when (cap) {
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> {
                            anyDepthSupported = true
                            "DEPTH_OUTPUT"
                        }
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
                        else -> "CAP_$cap"
                    }
                }.distinct()

                val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val maxJpeg = streamMap?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                val maxYuv = streamMap?.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                val max43Jpeg = streamMap?.getOutputSizes(ImageFormat.JPEG)
                    ?.filter { is4By3(it.width, it.height) }
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

                val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                val aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                val minEvStops = if (aeRange != null && aeStep != null) aeRange.lower * aeStep.toFloat() else 0f
                val maxEvStops = if (aeRange != null && aeStep != null) aeRange.upper * aeStep.toFloat() else 0f

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.joinToString(", ") { "${it}mm" } ?: "N/A"

                val block = buildString {
                    appendLine("Camera ID: $id | Lens: $lensFacing | HW Level: $hwLevel")
                    appendLine("Focal Lengths: $focalLengths")
                    appendLine("Capabilities: ${if (capNames.isEmpty()) "NONE" else capNames.joinToString(", ")}")
                    appendLine("Max JPEG: ${maxJpeg?.let { "${it.width}x${it.height}" } ?: "N/A"} | Max 4:3 JPEG: ${max43Jpeg?.let { "${it.width}x${it.height}" } ?: "N/A"}")
                    appendLine("Max YUV_420_888: ${maxYuv?.let { "${it.width}x${it.height}" } ?: "N/A"}")
                    appendLine("AE Comp Index Range: ${aeRange?.lower ?: 0}..${aeRange?.upper ?: 0} | Step: ${aeStep?.toFloat() ?: 0f} EV (~${"%.1f".format(minEvStops)} to +${"%.1f".format(maxEvStops)} stops)")
                }.trimEnd()

                summaries.add(block)
            }
        } catch (e: Exception) {
            logger.error("Diagnostics", "Error reading Camera2 characteristics", e)
            summaries.add("Error reading Camera2 devices: ${e.message}")
        }

        val extensionsReport = queryExtensionsReport()
        logger.info("Diagnostics", "Collected Camera2 diagnostics for ${summaries.size} cameras")

        CameraDiagnosticsReport(
            generatedAt = generatedAt,
            deviceModel = deviceModel,
            androidVersion = androidVersion,
            cameraSummaries = summaries,
            extensionsReport = extensionsReport,
            depthSupported = anyDepthSupported
        )
    }

    private suspend fun queryExtensionsReport(): String {
        return try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val extensionsManager = ExtensionsManager.getInstanceAsync(context, provider).get()
            val selectors = listOf(
                "BACK" to androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                "FRONT" to androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
            )
            val modes = listOf(
                "AUTO" to ExtensionMode.AUTO,
                "HDR" to ExtensionMode.HDR,
                "NIGHT" to ExtensionMode.NIGHT,
                "BOKEH" to ExtensionMode.BOKEH
            )
            selectors.joinToString("\n") { (lensLabel, selector) ->
                val hasCam = try {
                    provider.hasCamera(selector)
                } catch (_: Exception) {
                    false
                }
                if (!hasCam) {
                    "$lensLabel: Camera unavailable"
                } else {
                    val modeStatus = modes.joinToString(", ") { (modeName, modeConst) ->
                        val supported = try {
                            extensionsManager.isExtensionAvailable(selector, modeConst)
                        } catch (_: Exception) {
                            false
                        }
                        "$modeName=${if (supported) "YES" else "NO"}"
                    }
                    "$lensLabel: $modeStatus"
                }
            }
        } catch (e: Exception) {
            logger.warn("Diagnostics", "Extensions query fallback: ${e.message ?: "unavailable"}")
            "Extensions query unavailable on device (${e.javaClass.simpleName})"
        }
    }

    private fun is4By3(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        val ratio = maxOf(w, h).toFloat() / minOf(w, h).toFloat()
        return kotlin.math.abs(ratio - (4f / 3f)) < 0.04f
    }
}
