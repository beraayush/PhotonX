package com.photonx.camera.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photonx.camera.BuildConfig
import com.photonx.camera.camera.CameraDiagnosticsProvider
import com.photonx.camera.camera.PhotonXCameraManager
import com.photonx.camera.data.AppLogger
import com.photonx.camera.data.MediaStoreRepository
import com.photonx.camera.data.SettingsRepository
import com.photonx.camera.domain.*
import com.photonx.camera.engine.ComputationalPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CameraUiState(
    val selectedMode: CameraMode = CameraMode.HDR,
    val isCapturingBurst: Boolean = false,
    val pendingJobsCount: Int = 0,
    val statusMessage: String = "Ready • v${BuildConfig.VERSION_NAME}",
    val zoomRatio: Float = 1.0f,
    val exposureIndex: Int = 0,
    val isFrontCamera: Boolean = false,
    val lastCapture: CaptureResult? = null,
    val diagnosticsReport: CameraDiagnosticsReport? = null,
    val isLoadingDiagnostics: Boolean = false
)

private data class ProcessingJob(
    val frames: List<CapturedFrame>,
    val mode: CameraMode,
    val settings: PhotonXSettings,
    val usedVivoExtension: Boolean,
    val captureTimeMs: Long
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mediaStoreRepository: MediaStoreRepository,
    private val pipeline: ComputationalPipeline,
    private val diagnosticsProvider: CameraDiagnosticsProvider,
    val logger: AppLogger
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    val settings: StateFlow<PhotonXSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PhotonXSettings())

    private val processingQueue = Channel<ProcessingJob>(capacity = 2)

    init {
        logger.info("App", "PhotonX started (version=${BuildConfig.VERSION_NAME}, code=${BuildConfig.VERSION_CODE})")
        startBackgroundWorker()
    }

    private fun startBackgroundWorker() {
        viewModelScope.launch(Dispatchers.Default) {
            for (job in processingQueue) {
                try {
                    val tProcStart = System.currentTimeMillis()
                    val firstFrame = job.frames.first()
                    val rot = firstFrame.rotationDegrees

                    if (job.mode == CameraMode.AUTO && !job.usedVivoExtension && firstFrame.jpegBytes != null) {
                        val savedUri = mediaStoreRepository.saveRawJpegToGallery(
                            jpegBytes = firstFrame.jpegBytes,
                            rotationDegrees = rot,
                            modeTag = "AUTO"
                        )
                        val previewThumb = createLightweightThumbnail(firstFrame.jpegBytes, rot)
                        val tProc = System.currentTimeMillis() - tProcStart
                        val total = job.captureTimeMs + tProc

                        val resStr = if (previewThumb != null) "Native 4:3" else "Full JPEG"
                        val metrics = BenchmarkMetrics(
                            mode = CameraMode.AUTO,
                            captureTimeMs = job.captureTimeMs,
                            processingTimeMs = tProc,
                            totalTimeMs = total,
                            frameCount = 1,
                            alignedFrames = 1,
                            resolution = resStr,
                            pipelineNote = "Lossless Native ISP JPEG"
                        )

                        _uiState.update {
                            it.copy(
                                statusMessage = "Saved AUTO (${total}ms)",
                                lastCapture = CaptureResult(previewThumb, savedUri, metrics)
                            )
                        }
                        logger.info("AUTO", "Lossless capture saved", total)
                    } else {
                        val output = pipeline.execute(
                            frames = job.frames,
                            mode = job.mode,
                            settings = job.settings,
                            usedVivoExtension = job.usedVivoExtension
                        )
                        val tProc = System.currentTimeMillis() - tProcStart

                        val savedUri = mediaStoreRepository.saveBitmapToGallery(
                            bitmap = output.bitmap,
                            rotationDegrees = 0,
                            modeTag = job.mode.name,
                            sourceExifBytes = firstFrame.jpegBytes
                        )
                        val total = job.captureTimeMs + tProc

                        val metrics = BenchmarkMetrics(
                            mode = job.mode,
                            captureTimeMs = job.captureTimeMs,
                            processingTimeMs = tProc,
                            totalTimeMs = total,
                            frameCount = job.frames.size,
                            alignedFrames = output.alignedFrames,
                            resolution = "${output.bitmap.width}x${output.bitmap.height}",
                            pipelineNote = output.pipelineNote
                        )

                        job.frames.forEach { frame ->
                            if (frame.bitmap != null && frame.bitmap !== output.bitmap && !frame.bitmap.isRecycled) {
                                frame.bitmap.recycle()
                            }
                        }

                        _uiState.update {
                            it.copy(
                                statusMessage = "Saved ${job.mode.title} (${total}ms)",
                                lastCapture = CaptureResult(output.bitmap, savedUri, metrics)
                            )
                        }
                        logger.info(job.mode.name, "${output.pipelineNote} completed", total)
                    }
                } catch (e: Exception) {
                    logger.error("Pipeline", "Processing failed for ${job.mode.name}", e)
                    _uiState.update {
                        it.copy(statusMessage = "Error: ${e.message ?: "Processing failed"}")
                    }
                } finally {
                    _uiState.update {
                        it.copy(pendingJobsCount = (it.pendingJobsCount - 1).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    fun selectMode(mode: CameraMode) {
        _uiState.update { it.copy(selectedMode = mode, statusMessage = "${mode.title} Mode") }
    }

    fun setZoom(zoom: Float, cameraManager: PhotonXCameraManager) {
        cameraManager.setZoomRatio(zoom)
        _uiState.update { it.copy(zoomRatio = zoom) }
    }

    fun setExposure(ev: Int, cameraManager: PhotonXCameraManager) {
        cameraManager.setExposureIndex(ev)
        _uiState.update { it.copy(exposureIndex = ev) }
    }

    fun onCameraSwitched(isFront: Boolean) {
        _uiState.update { it.copy(isFrontCamera = isFront, zoomRatio = 1.0f) }
    }

    fun toggleNaturalColor(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.toggleNaturalColor(enabled)
    }

    fun toggleVivoExtensions(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.toggleVivoExtensions(enabled)
    }

    fun toggleGrid(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.toggleGrid(enabled)
    }

    fun toggleBenchmarks(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.toggleBenchmarks(enabled)
    }

    fun setHdrStrength(value: Float) = viewModelScope.launch {
        settingsRepository.setHdrStrength(value)
    }

    fun setPortraitBlurStrength(value: Float) = viewModelScope.launch {
        settingsRepository.setPortraitBlurStrength(value)
    }

    fun setMacroClarityStrength(value: Float) = viewModelScope.launch {
        settingsRepository.setMacroClarityStrength(value)
    }

    fun setColorWarmth(value: Float) = viewModelScope.launch {
        settingsRepository.setColorWarmth(value)
    }

    fun loadDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDiagnostics = true) }
            try {
                val report = diagnosticsProvider.collectDiagnostics()
                _uiState.update { it.copy(diagnosticsReport = report, isLoadingDiagnostics = false) }
            } catch (e: Exception) {
                logger.error("Diagnostics", "Failed loading diagnostics", e)
                _uiState.update { it.copy(isLoadingDiagnostics = false) }
            }
        }
    }

    fun capturePhoto(cameraManager: PhotonXCameraManager) {
        if (_uiState.value.isCapturingBurst) return
        if (_uiState.value.pendingJobsCount >= 2) {
            _uiState.update { it.copy(statusMessage = "Queue full — finishing previous shot...") }
            return
        }

        viewModelScope.launch {
            val mode = _uiState.value.selectedMode
            val currentSettings = settings.value
            val isFront = cameraManager.isFrontCamera()

            _uiState.update {
                it.copy(
                    isCapturingBurst = true,
                    statusMessage = "Capturing ${mode.title}..."
                )
            }

            try {
                val t0 = System.currentTimeMillis()
                val frames = cameraManager.captureBurst(mode, isFront)
                val tCap = System.currentTimeMillis() - t0
                val usedExt = cameraManager.isUsingVivoExtension()

                _uiState.update {
                    it.copy(
                        isCapturingBurst = false,
                        pendingJobsCount = it.pendingJobsCount + 1,
                        statusMessage = if (mode == CameraMode.AUTO && !usedExt) "Saving JPEG..." else "Processing ${mode.title}..."
                    )
                }

                processingQueue.send(
                    ProcessingJob(
                        frames = frames,
                        mode = mode,
                        settings = currentSettings,
                        usedVivoExtension = usedExt,
                        captureTimeMs = tCap
                    )
                )
            } catch (e: Exception) {
                logger.error("Capture", "Burst capture failed in ${mode.name}", e)
                _uiState.update {
                    it.copy(
                        isCapturingBurst = false,
                        statusMessage = "Capture failed: ${e.message ?: "error"}"
                    )
                }
            }
        }
    }

    private suspend fun createLightweightThumbnail(jpegBytes: ByteArray, rotationDegrees: Int): Bitmap? =
        withContext(Dispatchers.Default) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return@withContext null
                val normRot = ((rotationDegrees % 360) + 360) % 360
                if (normRot != 0) {
                    val m = Matrix().apply { postRotate(normRot.toFloat()) }
                    val rot = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    if (rot !== raw) raw.recycle()
                    rot
                } else {
                    raw
                }
            } catch (_: Exception) {
                null
            }
        }
}
