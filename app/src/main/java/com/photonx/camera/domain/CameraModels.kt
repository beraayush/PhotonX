package com.photonx.camera.domain

import android.graphics.Bitmap
import android.net.Uri

enum class CameraMode(val title: String, val targetFrames: Int) {
    AUTO("Auto", 1),
    HDR("HDR+", 4),
    NIGHT("Night", 6),
    PORTRAIT("Portrait", 1)
}

enum class CaptureSourceType {
    JPEG_BYTES,
    BITMAP_BURST
}

data class CapturedFrame(
    val bitmap: Bitmap?,
    val jpegBytes: ByteArray?,
    val rotationDegrees: Int,
    val evStopOffset: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CapturedFrame
        if (bitmap != other.bitmap) return false
        if (jpegBytes != null) {
            if (other.jpegBytes == null) return false
            if (!jpegBytes.contentEquals(other.jpegBytes)) return false
        } else if (other.jpegBytes != null) return false
        if (rotationDegrees != other.rotationDegrees) return false
        if (evStopOffset != other.evStopOffset) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bitmap?.hashCode() ?: 0
        result = 31 * result + (jpegBytes?.contentHashCode() ?: 0)
        result = 31 * result + rotationDegrees
        result = 31 * result + evStopOffset.hashCode()
        return result
    }
}

data class PhotonXSettings(
    val naturalColorScience: Boolean = true,
    val useVivoExtensions: Boolean = false,
    val showGrid: Boolean = true,
    val showBenchmarks: Boolean = true,
    val hdrStrength: Float = 0.85f,
    val portraitBlurStrength: Float = 0.70f,
    val macroClarityStrength: Float = 0.35f,
    val colorWarmth: Float = 0.15f
)

data class BenchmarkMetrics(
    val mode: CameraMode,
    val captureTimeMs: Long,
    val processingTimeMs: Long,
    val totalTimeMs: Long,
    val frameCount: Int,
    val alignedFrames: Int,
    val resolution: String,
    val pipelineNote: String
)

data class CaptureResult(
    val previewBitmap: Bitmap?,
    val savedUri: Uri?,
    val metrics: BenchmarkMetrics
)

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
    val durationMs: Long? = null
) {
    fun formatPlainText(): String {
        val dur = durationMs?.let { " (${it}ms)" } ?: ""
        return "[$timestamp] [$level] [$tag]$dur $message"
    }
}

data class CameraDiagnosticsReport(
    val generatedAt: String,
    val deviceModel: String,
    val androidVersion: String,
    val cameraSummaries: List<String>,
    val extensionsReport: String,
    val depthSupported: Boolean
) {
    fun formatPlainText(): String = buildString {
        appendLine("=== PHONETX CAMERA2 & EXTENSIONS DIAGNOSTICS ===")
        appendLine("Generated: $generatedAt")
        appendLine("Device: $deviceModel | Android: $androidVersion")
        appendLine("Depth Output Available: $depthSupported")
        appendLine()
        appendLine("--- CameraX Extensions (Vivo Engine) ---")
        appendLine(extensionsReport)
        appendLine()
        appendLine("--- Camera2 Hardware Devices ---")
        cameraSummaries.forEach { block ->
            appendLine(block)
            appendLine("----------------------------------------------")
        }
    }
}
