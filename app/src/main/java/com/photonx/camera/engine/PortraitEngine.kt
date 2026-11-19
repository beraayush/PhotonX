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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class PortraitEngine @Inject constructor(
    private val logger: AppLogger
) {

    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(options)
    }

    suspend fun processPortrait(input: Bitmap, blurStrength: Float = 0.70f): Bitmap =
        withContext(Dispatchers.Default) {
            val width = input.width
            val height = input.height

            val image = InputImage.fromBitmap(input, 0)
            val maskResult = segmenter.process(image).await()
            val maskW = maskResult.width
            val maskH = maskResult.height
            val maskBuffer = maskResult.buffer
            maskBuffer.rewind()

            val rawMaskArr = FloatArray(maskW * maskH) { maskBuffer.float.coerceIn(0f, 1f) }
            val maskSmall = Mat(maskH, maskW, CvType.CV_32FC1)
            maskSmall.put(0, 0, rawMaskArr)

            val upsampledMask = Mat()
            Imgproc.resize(
                maskSmall,
                upsampledMask,
                Size(width.toDouble(), height.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR
            )
            maskSmall.release()

            val srcRgba = Mat()
            Utils.bitmapToMat(input, srcRgba)
            val srcRgb = Mat()
            Imgproc.cvtColor(srcRgba, srcRgb, Imgproc.COLOR_RGBA2RGB)
            srcRgba.release()

            val guideGray = Mat()
            Imgproc.cvtColor(srcRgb, guideGray, Imgproc.COLOR_RGB2GRAY)
            val guideFloat = Mat()
            guideGray.convertTo(guideFloat, CvType.CV_32FC1, 1.0 / 255.0)
            guideGray.release()

            val refinedAlpha = guidedFilter(guideFloat, upsampledMask, radius = 16, eps = 0.002)
            guideFloat.release()
            upsampledMask.release()

            val binaryFg = Mat()
            Imgproc.threshold(refinedAlpha, binaryFg, 0.5, 255.0, Imgproc.THRESH_BINARY_INV)
            val binaryFg8U = Mat()
            binaryFg.convertTo(binaryFg8U, CvType.CV_8UC1)
            binaryFg.release()

            val distTransform = Mat()
            Imgproc.distanceTransform(binaryFg8U, distTransform, Imgproc.DIST_L2, 3)
            binaryFg8U.release()

            val maxFalloffPx = (minOf(width, height) * 0.18).coerceAtLeast(30.0)
            Core.multiply(distTransform, Scalar(1.0 / maxFalloffPx), distTransform)
            Core.min(distTransform, Scalar(1.0), distTransform)
            Core.pow(distTransform, 0.75, distTransform)

            val maxKernel = ((15 + blurStrength * 34).roundToInt() or 1).coerceIn(11, 51)
            val midKernel = ((maxKernel * 0.55).roundToInt() or 1).coerceIn(7, 29)

            val nearBokeh = renderDiscBokehWithBloom(srcRgb, midKernel)
            val farBokeh = renderDiscBokehWithBloom(srcRgb, maxKernel)

            val srcFloat = Mat()
            val nearFloat = Mat()
            val farFloat = Mat()
            srcRgb.convertTo(srcFloat, CvType.CV_32FC3)
            nearBokeh.convertTo(nearFloat, CvType.CV_32FC3)
            farBokeh.convertTo(farFloat, CvType.CV_32FC3)

            srcRgb.release()
            nearBokeh.release()
            farBokeh.release()

            val dist3C = Mat()
            Imgproc.cvtColor(distTransform, dist3C, Imgproc.COLOR_GRAY2RGB)
            val invDist3C = Mat()
            Core.subtract(Mat(dist3C.size(), CvType.CV_32FC3, Scalar(1.0, 1.0, 1.0)), dist3C, invDist3C)

            val bgProgFar = Mat()
            val bgProgNear = Mat()
            val bgProgressive = Mat()
            Core.multiply(farFloat, dist3C, bgProgFar)
            Core.multiply(nearFloat, invDist3C, bgProgNear)
            Core.add(bgProgFar, bgProgNear, bgProgressive)

            distTransform.release()
            dist3C.release()
            invDist3C.release()
            nearFloat.release()
            farFloat.release()
            bgProgFar.release()
            bgProgNear.release()

            val alpha3C = Mat()
            Imgproc.cvtColor(refinedAlpha, alpha3C, Imgproc.COLOR_GRAY2RGB)
            val invAlpha3C = Mat()
            Core.subtract(Mat(alpha3C.size(), CvType.CV_32FC3, Scalar(1.0, 1.0, 1.0)), alpha3C, invAlpha3C)

            val fgPart = Mat()
            val bgPart = Mat()
            val finalFloat = Mat()
            Core.multiply(srcFloat, alpha3C, fgPart)
            Core.multiply(bgProgressive, invAlpha3C, bgPart)
            Core.add(fgPart, bgPart, finalFloat)

            val finalRgba = Mat()
            finalFloat.convertTo(finalRgba, CvType.CV_8UC3)
            Imgproc.cvtColor(finalRgba, finalRgba, Imgproc.COLOR_RGB2RGBA)

            val outBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(finalRgba, outBmp)

            refinedAlpha.release()
            srcFloat.release()
            bgProgressive.release()
            alpha3C.release()
            invAlpha3C.release()
            fgPart.release()
            bgPart.release()
            finalFloat.release()
            finalRgba.release()

            logger.info("Portrait", "Rendered guided-filter disc bokeh (kernel=$maxKernel)")
            outBmp
        }

    private fun renderDiscBokehWithBloom(srcRgb: Mat, kernelSize: Int): Mat {
        val smallSize = Size((srcRgb.cols() / 2.0).coerceAtLeast(32.0), (srcRgb.rows() / 2.0).coerceAtLeast(32.0))
        val small = Mat()
        Imgproc.resize(srcRgb, small, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)

        val smallFloat = Mat()
        small.convertTo(smallFloat, CvType.CV_32FC3, 1.0 / 255.0)
        small.release()

        val bloomLinear = Mat()
        Core.pow(smallFloat, 2.2, bloomLinear)
        smallFloat.release()

        val k = ((kernelSize / 2) or 1).coerceAtLeast(5)
        val discKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        val kernelSum = Core.sumElems(discKernel).`val`[0].coerceAtLeast(1.0)
        val normKernel = Mat()
        discKernel.convertTo(normKernel, CvType.CV_32F, 1.0 / kernelSum)
        discKernel.release()

        val convolved = Mat()
        Imgproc.filter2D(bloomLinear, convolved, -1, normKernel)
        bloomLinear.release()
        normKernel.release()

        Core.pow(convolved, 1.0 / 2.2, convolved)
        val blurred8U = Mat()
        convolved.convertTo(blurred8U, CvType.CV_8UC3, 255.0)
        convolved.release()

        val upscaled = Mat()
        Imgproc.resize(blurred8U, upscaled, srcRgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        blurred8U.release()
        return upscaled
    }

    private fun guidedFilter(guideI: Mat, srcP: Mat, radius: Int, eps: Double): Mat {
        val kSize = Size((2 * radius + 1).toDouble(), (2 * radius + 1).toDouble())

        val meanI = Mat()
        val meanP = Mat()
        Imgproc.blur(guideI, meanI, kSize)
        Imgproc.blur(srcP, meanP, kSize)

        val ii = Mat()
        val ip = Mat()
        Core.multiply(guideI, guideI, ii)
        Core.multiply(guideI, srcP, ip)

        val corrI = Mat()
        val corrIp = Mat()
        Imgproc.blur(ii, corrI, kSize)
        Imgproc.blur(ip, corrIp, kSize)
        ii.release()
        ip.release()

        val meanI2 = Mat()
        Core.multiply(meanI, meanI, meanI2)
        val varI = Mat()
        Core.subtract(corrI, meanI2, varI)
        corrI.release()
        meanI2.release()

        val meanIMeanP = Mat()
        Core.multiply(meanI, meanP, meanIMeanP)
        val covIp = Mat()
        Core.subtract(corrIp, meanIMeanP, covIp)
        corrIp.release()
        meanIMeanP.release()

        val denom = Mat()
        Core.add(varI, Scalar(eps), denom)
        val a = Mat()
        Core.divide(covIp, denom, a)
        varI.release()
        covIp.release()
        denom.release()

        val aMeanI = Mat()
        Core.multiply(a, meanI, aMeanI)
        val b = Mat()
        Core.subtract(meanP, aMeanI, b)
        meanI.release()
        meanP.release()
        aMeanI.release()

        val meanA = Mat()
        val meanB = Mat()
        Imgproc.blur(a, meanA, kSize)
        Imgproc.blur(b, meanB, kSize)
        a.release()
        b.release()

        val q = Mat()
        Core.multiply(meanA, guideI, q)
        Core.add(q, meanB, q)
        meanA.release()
        meanB.release()

        Core.max(q, Scalar(0.0), q)
        Core.min(q, Scalar(1.0), q)
        return q
    }
}
