package com.photonx.camera.engine

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.photonx.camera.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HdrEngine @Inject constructor(
    private val frameAligner: FrameAligner,
    private val logger: AppLogger
) {

    private val personSegmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(options)
    }

    suspend fun processHdrBurst(
        bitmaps: List<Bitmap>,
        evOffsets: List<Float>,
        hdrStrength: Float,
        isMacroOrFood: Boolean = false
    ): Pair<Bitmap, Int> = withContext(Dispatchers.Default) {
        if (bitmaps.isEmpty()) throw IllegalArgumentException("HDR burst is empty")

        val refIdx = evOffsets.indexOfFirst { kotlin.math.abs(it) < 0.05f }
            .takeIf { it >= 0 } ?: (bitmaps.size / 2)

        val bgrMats = bitmaps.map { bmp ->
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()
            bgr
        }

        val alignedResult = frameAligner.alignFrames(bgrMats, refIdx)
        bgrMats.forEach { it.release() }

        val alignedMats = alignedResult.alignedMats
        val refMat = alignedMats[0].clone()

        val fusedBgr = if (alignedMats.size >= 2) {
            val mergeMertens = Photo.createMergeMertens(
                1.0f,
                1.0f,
                1.15f
            )
            val fusionFloat = Mat()
            mergeMertens.process(alignedMats, fusionFloat)

            val fusion8U = Mat()
            Core.normalize(fusionFloat, fusionFloat, 0.0, 255.0, Core.NORM_MINMAX)
            fusionFloat.convertTo(fusion8U, CvType.CV_8UC3)
            fusionFloat.release()

            val blended = Mat()
            val alpha = hdrStrength.coerceIn(0f, 1f).toDouble()
            Core.addWeighted(fusion8U, alpha, refMat, 1.0 - alpha, 0.0, blended)
            fusion8U.release()
            blended
        } else {
            applySingleFrameLocalToneMap(refMat, hdrStrength)
        }

        alignedMats.forEach { it.release() }
        refMat.release()

        val lab = Mat()
        Imgproc.cvtColor(fusedBgr, lab, Imgproc.COLOR_BGR2Lab)
        val labChannels = ArrayList<Mat>(3)
        Core.split(lab, labChannels)

        val clipLimit = if (isMacroOrFood) 1.6 else (1.1 + hdrStrength * 0.7)
        val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
        val enhancedL = Mat()
        clahe.apply(labChannels[0], enhancedL)
        Core.addWeighted(enhancedL, 0.45, labChannels[0], 0.55, 0.0, labChannels[0])
        enhancedL.release()

        Core.merge(labChannels, lab)
        labChannels.forEach { it.release() }
        Imgproc.cvtColor(lab, fusedBgr, Imgproc.COLOR_Lab2BGR)
        lab.release()

        val outBmp = Bitmap.createBitmap(fusedBgr.cols(), fusedBgr.rows(), Bitmap.Config.ARGB_8888)
        val outRgba = Mat()
        Imgproc.cvtColor(fusedBgr, outRgba, Imgproc.COLOR_BGR2RGBA)
        Utils.matToBitmap(outRgba, outBmp)
        outRgba.release()
        fusedBgr.release()

        val faceLiftedBmp = liftBacklitSubjectsIfDetected(outBmp)
        if (faceLiftedBmp !== outBmp) outBmp.recycle()

        Pair(faceLiftedBmp, alignedMats.size)
    }

    private fun applySingleFrameLocalToneMap(bgr: Mat, strength: Float): Mat {
        val floatImg = Mat()
        bgr.convertTo(floatImg, CvType.CV_32FC3, 1.0 / 255.0)
        val linear = Mat()
        Core.pow(floatImg, 2.2, linear)
        floatImg.release()

        val lumBlur = Mat()
        Imgproc.GaussianBlur(linear, lumBlur, Size(0.0, 0.0), 25.0)

        val oneMat = Mat(linear.size(), linear.type(), Scalar(1.0, 1.0, 1.0))
        val denom = Mat()
        Core.add(oneMat, lumBlur, denom)
        val mapped = Mat()
        Core.divide(linear, denom, mapped)

        Core.pow(mapped, 1.0 / 2.15, mapped)
        val out8 = Mat()
        mapped.convertTo(out8, CvType.CV_8UC3, 255.0)

        linear.release()
        lumBlur.release()
        oneMat.release()
        denom.release()
        mapped.release()

        val blended = Mat()
        val alpha = (strength * 0.75f).coerceIn(0f, 1f).toDouble()
        Core.addWeighted(out8, alpha, bgr, 1.0 - alpha, 0.0, blended)
        out8.release()
        return blended
    }

    private suspend fun liftBacklitSubjectsIfDetected(src: Bitmap): Bitmap {
        return try {
            val image = InputImage.fromBitmap(src, 0)
            val maskResult = personSegmenter.process(image).await()
            val maskW = maskResult.width
            val maskH = maskResult.height
            val buf = maskResult.buffer
            buf.rewind()

            val floatMask = FloatArray(maskW * maskH)
            var personPixelCount = 0
            for (i in floatMask.indices) {
                val conf = buf.float
                floatMask[i] = conf
                if (conf > 0.65f) personPixelCount++
            }

            if (personPixelCount < (floatMask.size * 0.02f)) {
                return src
            }

            val maskSmall = Mat(maskH, maskW, CvType.CV_32FC1)
            maskSmall.put(0, 0, floatMask)

            val maskFull = Mat()
            Imgproc.resize(maskSmall, maskFull, Size(src.width.toDouble(), src.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.GaussianBlur(maskFull, maskFull, Size(31.0, 31.0), 12.0)
            maskSmall.release()

            val rgba = Mat()
            Utils.bitmapToMat(src, rgba)
            val lab = Mat()
            Imgproc.cvtColor(rgba, lab, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)

            val channels = ArrayList<Mat>(3)
            Core.split(lab, channels)
            val lFloat = Mat()
            channels[0].convertTo(lFloat, CvType.CV_32FC1)

            val invL = Mat()
            Core.subtract(Mat(lFloat.size(), CvType.CV_32FC1, Scalar(255.0)), lFloat, invL)
            Core.multiply(invL, Scalar(1.0 / 255.0), invL)
            Core.pow(invL, 1.4, invL)

            val boost = Mat()
            Core.multiply(maskFull, invL, boost, 22.0)
            Core.add(lFloat, boost, lFloat)
            lFloat.convertTo(channels[0], CvType.CV_8UC1)

            Core.merge(channels, lab)
            val outRgb = Mat()
            Imgproc.cvtColor(lab, outRgb, Imgproc.COLOR_Lab2RGBA)

            val outBmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgb, outBmp)

            maskFull.release()
            rgba.release()
            lab.release()
            channels.forEach { it.release() }
            lFloat.release()
            invL.release()
            boost.release()
            outRgb.release()

            logger.info("HDR+", "Applied backlit subject fill-light lift")
            outBmp
        } catch (e: Exception) {
            logger.warn("HDR+", "Subject lift skipped: ${e.message}")
            src
        }
    }
}
