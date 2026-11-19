package com.photonx.camera.engine

import android.graphics.Bitmap
import com.photonx.camera.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.roundToInt

@Singleton
class ColorScienceProcessor @Inject constructor(
    private val logger: AppLogger
) {

    suspend fun applyNaturalColorAndSharpening(
        input: Bitmap,
        warmth: Float = 0.15f,
        clarityStrength: Float = 0.35f,
        isPortrait: Boolean = false
    ): Bitmap = withContext(Dispatchers.Default) {
        val rgba = Mat()
        Utils.bitmapToMat(input, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        bgr.release()

        val labChannels = ArrayList<Mat>(3)
        Core.split(lab, labChannels)

        val toneLut = buildToneCurveLut()
        Core.LUT(labChannels[0], toneLut, labChannels[0])
        toneLut.release()

        val sharpAmount = if (isPortrait) 0.28 else (0.32 + clarityStrength * 0.35)
        sharpenLumaOnly(labChannels[0], amount = sharpAmount)

        val warmthShift = (warmth * 8.0).coerceIn(-6.0, 6.0)
        if (kotlin.math.abs(warmthShift) > 0.1) {
            Core.add(labChannels[2], Scalar(warmthShift), labChannels[2])
        }

        Core.merge(labChannels, lab)
        labChannels.forEach { it.release() }

        val tonedBgr = Mat()
        Imgproc.cvtColor(lab, tonedBgr, Imgproc.COLOR_Lab2BGR)
        lab.release()

        val ycrcb = Mat()
        Imgproc.cvtColor(tonedBgr, ycrcb, Imgproc.COLOR_BGR2YCrCb)
        tonedBgr.release()

        val yccChannels = ArrayList<Mat>(3)
        Core.split(ycrcb, yccChannels)

        val crFloat = Mat()
        val cbFloat = Mat()
        yccChannels[1].convertTo(crFloat, CvType.CV_32FC1)
        yccChannels[2].convertTo(cbFloat, CvType.CV_32FC1)

        val dCr = Mat()
        val dCb = Mat()
        Core.subtract(crFloat, Scalar(150.0), dCr)
        Core.subtract(cbFloat, Scalar(112.0), dCb)
        Core.multiply(dCr, dCr, dCr, 1.0 / (2.0 * 14.0 * 14.0))
        Core.multiply(dCb, dCb, dCb, 1.0 / (2.0 * 14.0 * 14.0))

        val distSq = Mat()
        Core.add(dCr, dCb, distSq)
        Core.multiply(distSq, Scalar(-1.0), distSq)
        val skinWeight = Mat()
        Core.exp(distSq, skinWeight)

        val centeredCr = Mat()
        val centeredCb = Mat()
        Core.subtract(crFloat, Scalar(128.0), centeredCr)
        Core.subtract(cbFloat, Scalar(128.0), centeredCb)

        val satGain = Mat()
        Core.multiply(skinWeight, Scalar(-0.04), satGain)
        Core.add(satGain, Scalar(1.05), satGain)

        Core.multiply(centeredCr, satGain, centeredCr)
        Core.multiply(centeredCb, satGain, centeredCb)
        Core.add(centeredCr, Scalar(128.0), centeredCr)
        Core.add(centeredCb, Scalar(128.0), centeredCb)

        centeredCr.convertTo(yccChannels[1], CvType.CV_8UC1)
        centeredCb.convertTo(yccChannels[2], CvType.CV_8UC1)

        Core.merge(yccChannels, ycrcb)
        val finalRgb = Mat()
        Imgproc.cvtColor(ycrcb, finalRgb, Imgproc.COLOR_YCrCb2RGB)
        val finalRgba = Mat()
        Imgproc.cvtColor(finalRgb, finalRgba, Imgproc.COLOR_RGB2RGBA)

        val outBmp = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalRgba, outBmp)

        crFloat.release()
        cbFloat.release()
        dCr.release()
        dCb.release()
        distSq.release()
        skinWeight.release()
        centeredCr.release()
        centeredCb.release()
        satGain.release()
        yccChannels.forEach { it.release() }
        ycrcb.release()
        finalRgb.release()
        finalRgba.release()

        outBmp
    }

    private fun buildToneCurveLut(): Mat {
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val data = ByteArray(256) { i ->
            val x = i / 255.0
            val shadowLift = x + 0.035 * x * (1.0 - x) * (1.0 - x)
            val shoulder = shadowLift.pow(0.96) / (shadowLift.pow(0.96) + (1.0 - shadowLift).pow(1.03))
            (shoulder * 255.0).roundToInt().coerceIn(0, 255).toByte()
        }
        lut.put(0, 0, data)
        return lut
    }

    private fun sharpenLumaOnly(lChannel: Mat, amount: Double) {
        val blurred = Mat()
        Imgproc.GaussianBlur(lChannel, blurred, Size(0.0, 0.0), 1.35)

        val diff = Mat()
        Core.absdiff(lChannel, blurred, diff)
        val edgeMask = Mat()
        Imgproc.threshold(diff, edgeMask, 4.0, 255.0, Imgproc.THRESH_TOZERO)
        Imgproc.GaussianBlur(edgeMask, edgeMask, Size(3.0, 3.0), 0.8)

        val sharpened = Mat()
        Core.addWeighted(lChannel, 1.0 + amount, blurred, -amount, 0.0, sharpened)

        val maskFloat = Mat()
        edgeMask.convertTo(maskFloat, CvType.CV_32FC1, 1.0 / 28.0)
        Core.min(maskFloat, Scalar(1.0), maskFloat)

        val origFloat = Mat()
        val sharpFloat = Mat()
        lChannel.convertTo(origFloat, CvType.CV_32FC1)
        sharpened.convertTo(sharpFloat, CvType.CV_32FC1)

        val invMask = Mat()
        Core.subtract(Mat(maskFloat.size(), CvType.CV_32FC1, Scalar(1.0)), maskFloat, invMask)

        val part1 = Mat()
        val part2 = Mat()
        Core.multiply(sharpFloat, maskFloat, part1)
        Core.multiply(origFloat, invMask, part2)
        Core.add(part1, part2, origFloat)

        origFloat.convertTo(lChannel, CvType.CV_8UC1)

        blurred.release()
        diff.release()
        edgeMask.release()
        sharpened.release()
        maskFloat.release()
        origFloat.release()
        sharpFloat.release()
        invMask.release()
        part1.release()
        part2.release()
    }
}
