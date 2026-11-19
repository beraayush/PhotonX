package com.photonx.camera.engine

import android.graphics.Bitmap
import com.photonx.camera.data.AppLogger
import com.photonx.camera.domain.CameraMode
import com.photonx.camera.domain.CapturedFrame
import com.photonx.camera.domain.PhotonXSettings
import javax.inject.Inject
import javax.inject.Singleton

data class PipelineOutput(
    val bitmap: Bitmap,
    val alignedFrames: Int,
    val pipelineNote: String
)

@Singleton
class ComputationalPipeline @Inject constructor(
    private val openCvInitializer: OpenCvInitializer,
    private val hdrEngine: HdrEngine,
    private val nightEngine: NightEngine,
    private val portraitEngine: PortraitEngine,
    private val colorScienceProcessor: ColorScienceProcessor,
    private val logger: AppLogger
) {

    suspend fun execute(
        frames: List<CapturedFrame>,
        mode: CameraMode,
        settings: PhotonXSettings,
        usedVivoExtension: Boolean
    ): PipelineOutput {
        val cvReady = openCvInitializer.ensureInitialized()
        if (!cvReady) {
            throw IllegalStateException("OpenCV native library failed to initialize")
        }

        val bitmaps = frames.mapNotNull { it.bitmap }
        if (bitmaps.isEmpty()) {
            throw IllegalStateException("No decoded frames available for pipeline execution")
        }

        if (usedVivoExtension) {
            val base = bitmaps.first()
            val finalBmp = if (settings.naturalColorScience) {
                colorScienceProcessor.applyNaturalColorAndSharpening(
                    input = base,
                    warmth = settings.colorWarmth,
                    clarityStrength = settings.macroClarityStrength,
                    isPortrait = (mode == CameraMode.PORTRAIT)
                )
            } else {
                base
            }
            return PipelineOutput(finalBmp, 1, "Vivo Hardware Extension")
        }

        return when (mode) {
            CameraMode.HDR -> {
                val (hdrBmp, alignedCount) = hdrEngine.processHdrBurst(
                    bitmaps = bitmaps,
                    evOffsets = frames.map { it.evStopOffset },
                    hdrStrength = settings.hdrStrength
                )
                val finalBmp = if (settings.naturalColorScience) {
                    val colored = colorScienceProcessor.applyNaturalColorAndSharpening(
                        input = hdrBmp,
                        warmth = settings.colorWarmth,
                        clarityStrength = settings.macroClarityStrength,
                        isPortrait = false
                    )
                    if (colored !== hdrBmp) hdrBmp.recycle()
                    colored
                } else {
                    hdrBmp
                }
                PipelineOutput(finalBmp, alignedCount, "HDR+ Linear Merge (${alignedCount}/${bitmaps.size} aligned)")
            }

            CameraMode.NIGHT -> {
                val (nightBmp, alignedCount) = nightEngine.processNightStack(bitmaps)
                val finalBmp = if (settings.naturalColorScience) {
                    val colored = colorScienceProcessor.applyNaturalColorAndSharpening(
                        input = nightBmp,
                        warmth = settings.colorWarmth,
                        clarityStrength = settings.macroClarityStrength * 0.8f,
                        isPortrait = false
                    )
                    if (colored !== nightBmp) nightBmp.recycle()
                    colored
                } else {
                    nightBmp
                }
                PipelineOutput(finalBmp, alignedCount, "Night Stack (${alignedCount}/${bitmaps.size} aligned)")
            }

            CameraMode.PORTRAIT -> {
                val portraitBmp = portraitEngine.processPortrait(
                    input = bitmaps.first(),
                    blurStrength = settings.portraitBlurStrength
                )
                val finalBmp = if (settings.naturalColorScience) {
                    val colored = colorScienceProcessor.applyNaturalColorAndSharpening(
                        input = portraitBmp,
                        warmth = settings.colorWarmth,
                        clarityStrength = 0.25f,
                        isPortrait = true
                    )
                    if (colored !== portraitBmp) portraitBmp.recycle()
                    colored
                } else {
                    portraitBmp
                }
                PipelineOutput(finalBmp, 1, "Guided-Filter Disc Bokeh")
            }

            CameraMode.AUTO -> {
                val base = bitmaps.first()
                val finalBmp = if (settings.naturalColorScience) {
                    colorScienceProcessor.applyNaturalColorAndSharpening(
                        input = base,
                        warmth = settings.colorWarmth,
                        clarityStrength = settings.macroClarityStrength,
                        isPortrait = false
                    )
                } else {
                    base
                }
                PipelineOutput(finalBmp, 1, "Auto")
            }
        }
    }
}
