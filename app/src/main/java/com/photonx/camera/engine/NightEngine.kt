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

@Singleton
class NightEngine @Inject constructor(
    private val frameAligner: FrameAligner,
    private val logger: AppLogger
) {

    suspend fun processNightStack(bitmaps: List<Bitmap>): Pair<Bitmap, Int> =
        withContext(Dispatchers.Default) {
            if (bitmaps.isEmpty()) throw IllegalArgumentException("Night burst is empty")

            val bgrMats = bitmaps.map { bmp ->
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)
                val bgr = Mat()
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                rgba.release()
                bgr
            }

            val alignedResult = frameAligner.alignFrames(bgrMats, refIndex = 0)
            bgrMats.forEach { it.release() }

            val aligned = alignedResult.alignedMats
            val refFloat = Mat()
            aligned[0].convertTo(refFloat, CvType.CV_32FC3)

            val accColor = refFloat.clone()
            val accWeight = Mat(refFloat.size(), CvType.CV_32FC1, Scalar(1.0))

            for (i in 1 until aligned.size) {
                val frameFloat = Mat()
                aligned[i].convertTo(frameFloat, CvType.CV_32FC3)

                val diff = Mat()
                Core.absdiff(frameFloat, refFloat, diff)
                val diffGray = Mat()
                Imgproc.cvtColor(diff, diffGray, Imgproc.COLOR_BGR2GRAY)

                val normDiff = Mat()
                Core.multiply(diffGray, Scalar(1.0 / 22.0), normDiff)
                Core.multiply(normDiff, normDiff, normDiff)
                Core.add(normDiff, Scalar(1.0), normDiff)

                val weight = Mat()
                Core.divide(Mat(normDiff.size(), CvType.CV_32FC1, Scalar(1.0)), normDiff, weight)

                val weight3C = Mat()
                Imgproc.cvtColor(weight, weight3C, Imgproc.COLOR_GRAY2BGR)

                val weightedFrame = Mat()
                Core.multiply(frameFloat, weight3C, weightedFrame)
                Core.add(accColor, weightedFrame, accColor)
                Core.add(accWeight, weight, accWeight)

                frameFloat.release()
                diff.release()
                diffGray.release()
                normDiff.release()
                weight.release()
                weight3C.release()
                weightedFrame.release()
            }

            val accWeight3C = Mat()
            Imgproc.cvtColor(accWeight, accWeight3C, Imgproc.COLOR_GRAY2BGR)
            val mergedFloat = Mat()
            Core.divide(accColor, accWeight3C, mergedFloat)

            val merged8U = Mat()
            mergedFloat.convertTo(merged8U, CvType.CV_8UC3)

            aligned.forEach { it.release() }
            refFloat.release()
            accColor.release()
            accWeight.release()
            accWeight3C.release()
            mergedFloat.release()

            val denoised = Mat()
            Imgproc.bilateralFilter(merged8U, denoised, 7, 38.0, 38.0)
            merged8U.release()

            val lab = Mat()
            Imgproc.cvtColor(denoised, lab, Imgproc.COLOR_BGR2Lab)
            denoised.release()

            val channels = ArrayList<Mat>(3)
            Core.split(lab, channels)

            val lFloat = Mat()
            channels[0].convertTo(lFloat, CvType.CV_32FC1, 1.0 / 255.0)
            Core.pow(lFloat, 0.82, lFloat)
            lFloat.convertTo(channels[0], CvType.CV_8UC1, 255.0)
            lFloat.release()

            val clahe = Imgproc.createCLAHE(1.4, Size(8.0, 8.0))
            val claheL = Mat()
            clahe.apply(channels[0], claheL)
            Core.addWeighted(claheL, 0.35, channels[0], 0.65, 0.0, channels[0])
            claheL.release()

            Core.merge(channels, lab)
            channels.forEach { it.release() }

            val outRgba = Mat()
            Imgproc.cvtColor(lab, outRgba, Imgproc.COLOR_Lab2RGBA)
            lab.release()

            val outBmp = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, outBmp)
            outRgba.release()

            logger.info("Night", "Stacked ${aligned.size} aligned frames cleanly")
            Pair(outBmp, aligned.size)
        }
}
